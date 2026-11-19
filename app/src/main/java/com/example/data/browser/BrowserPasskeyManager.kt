package com.example.data.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import com.example.data.security.MalwareProtectionEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Locale

data class StoredPasskeyCredential(
    val credentialIdBase64Url: String,
    val rpId: String,
    val rpName: String,
    val userIdBase64Url: String,
    val userName: String,
    val userDisplayName: String,
    val privateKeyPkcs8Base64: String,
    val publicKeySpkiBase64: String,
    val cosePublicKeyBase64: String,
    val signCount: Long = 1L,
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * Provides W3C WebAuthn / FIDO2 Passkey support (`navigator.credentials.create` & `navigator.credentials.get`)
 * for the Browser WebView.
 *
 * Combines:
 * 1. Built-in FIDO2 / WebAuthn Software Platform Authenticator (ECDSA P-256 `secp256r1` / `ES256`) that works
 *    on all domains without requiring the website to list the app package in `.well-known/assetlinks.json`.
 * 2. Android Jetpack `CredentialManager` (`GetPublicKeyCredentialOption` / `CreatePublicKeyCredentialRequest`)
 *    when an Activity context and compatible OS credential provider are available.
 * 3. Automatic synchronization of passkey entries with `BrowserStateRepository`'s Saved Passwords & Google Sync vault.
 */
class BrowserPasskeyManager(
    context: Context,
    private val onPasskeyRecorded: (SavedPasswordEntry) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    @Volatile
    private var passkeys: List<StoredPasskeyCredential> = loadPasskeys()

    fun getPasskeys(): List<StoredPasskeyCredential> = passkeys

    fun hasPasskeyForRp(rpIdOrHost: String?): Boolean {
        val clean = rpIdOrHost?.trim()?.lowercase(Locale.US)?.removePrefix("www.") ?: return false
        if (clean.isBlank()) return false
        return passkeys.any { pk ->
            val storedRp = pk.rpId.lowercase(Locale.US).removePrefix("www.")
            clean == storedRp || clean.endsWith(".$storedRp") || storedRp.endsWith(".$clean")
        }
    }

    /**
     * Imports a synced passkey from a `SavedPasswordEntry` if its password field stores an encoded passkey payload.
     */
    fun importPasskeyFromPasswordEntryIfPresent(entry: SavedPasswordEntry) {
        if (!entry.password.startsWith(PASSKEY_SECRET_PREFIX)) return
        kotlin.runCatching {
            val rawJson = String(
                Base64.decode(entry.password.removePrefix(PASSKEY_SECRET_PREFIX), Base64.NO_WRAP),
                Charsets.UTF_8
            )
            val obj = JSONObject(rawJson)
            val credId = obj.optString("credentialIdBase64Url", "").ifBlank { obj.optString("c", "") }
            val privKey = obj.optString("privateKeyPkcs8Base64", "").ifBlank { obj.optString("pk", "") }
            if (credId.isBlank() || privKey.isBlank()) return
            val stored = StoredPasskeyCredential(
                credentialIdBase64Url = credId,
                rpId = obj.optString("rpId", "").ifBlank { obj.optString("r", entry.domain) },
                rpName = obj.optString("rpName", "").ifBlank { obj.optString("n", entry.title) },
                userIdBase64Url = obj.optString("userIdBase64Url", "").ifBlank { obj.optString("u", "") },
                userName = obj.optString("userName", "").ifBlank { obj.optString("un", entry.username) },
                userDisplayName = obj.optString("userDisplayName", "").ifBlank { obj.optString("ud", entry.username) },
                privateKeyPkcs8Base64 = privKey,
                publicKeySpkiBase64 = obj.optString("publicKeySpkiBase64", ""),
                cosePublicKeyBase64 = obj.optString("cosePublicKeyBase64", ""),
                signCount = obj.optLong("signCount", obj.optLong("s", 1L)),
                createdAtMs = obj.optLong("createdAtMs", obj.optLong("t", entry.createdAtMs))
            )
            upsertPasskey(stored, notifyPasswordVault = false)
        }
    }

    fun createPasskeyFromWebView(
        optionsJsonStr: String,
        originUrl: String,
        activityContext: Context? = null
    ): String {
        return try {
            val options = JSONObject(optionsJsonStr)
            val parsedOrigin = runCatching { Uri.parse(originUrl) }.getOrNull()
            val scheme = parsedOrigin?.scheme?.lowercase(Locale.US).orEmpty()
            val host = extractHost(originUrl)
            if (scheme !in setOf("https") && host != "localhost" && host != "127.0.0.1") {
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "Passkey registration requires a secure HTTPS origin.")
                }.toString()
            }
            val origin = extractOrigin(originUrl)
            val rpObj = options.optJSONObject("rp")
            val rpId = rpObj?.optString("id", "")?.trim()?.takeIf { it.isNotBlank() } ?: host
            val cleanHost = host.lowercase(Locale.US).removePrefix("www.")
            val cleanRp = rpId.lowercase(Locale.US).removePrefix("www.")
            if (cleanHost != cleanRp && !cleanHost.endsWith(".$cleanRp")) {
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "Blocked cross-domain passkey spoofing for rpId=$rpId from $host.")
                }.toString()
            }
            val rpName = rpObj?.optString("name", "")?.trim()?.takeIf { it.isNotBlank() } ?: rpId
            val userObj = options.optJSONObject("user")
            val userIdB64 = userObj?.optString("id", "")?.trim().orEmpty()
                .ifBlank { toBase64Url("user_${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)) }
            val userName = userObj?.optString("name", "")?.trim().orEmpty().ifBlank { "user@$rpId" }
            val userDisplayName = userObj?.optString("displayName", "")?.trim().orEmpty().ifBlank { userName }
            val challengeB64 = options.optString("challenge", "").trim()
                .ifBlank {
                    val rnd = ByteArray(32).also { secureRandom.nextBytes(it) }
                    toBase64Url(rnd)
                }

            // 1. Try Android CredentialManager if an Activity is available and domain is not known to block WebView origins
            val activity = findActivity(activityContext)
            if (activity != null && !rpId.endsWith("google.com", ignoreCase = true)) {
                val osResponse = runBlocking {
                    withTimeoutOrNull(30_000L) {
                        kotlin.runCatching {
                            val cm = CredentialManager.create(activity)
                            val req = CreatePublicKeyCredentialRequest(requestJson = optionsJsonStr)
                            val res = cm.createCredential(activity, req) as? CreatePublicKeyCredentialResponse
                            res?.registrationResponseJson
                        }.getOrNull()
                    }
                }
                if (!osResponse.isNullOrBlank()) {
                    recordPasskeyInPasswordVault(
                        rpId = rpId,
                        rpName = rpName,
                        originUrl = originUrl,
                        userName = userName,
                        secretPayload = "Android CredentialManager Passkey"
                    )
                    return JSONObject().apply {
                        put("ok", true)
                        put("source", "credential_manager")
                        put("credential", JSONObject(osResponse))
                    }.toString()
                }
            }

            // 2. Built-in FIDO2 / WebAuthn ES256 (ECDSA P-256) Platform Passkey Authenticator
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
            val keyPair = kpg.generateKeyPair()
            val ecPub = keyPair.public as ECPublicKey
            val privBytes = keyPair.private.encoded
            val spkiBytes = keyPair.public.encoded

            val credIdBytes = ByteArray(16).also { secureRandom.nextBytes(it) }
            val credIdB64Url = toBase64Url(credIdBytes)

            val xBytes = bigIntToFixed32Bytes(ecPub.w.affineX)
            val yBytes = bigIntToFixed32Bytes(ecPub.w.affineY)
            val coseKeyBytes = encodeCoseEc2PublicKey(xBytes, yBytes)

            val authDataBytes = buildRegistrationAuthenticatorData(
                rpId = rpId,
                signCount = 1L,
                credentialId = credIdBytes,
                cosePublicKey = coseKeyBytes
            )
            val attestationObjectBytes = encodeNoneAttestationObject(authDataBytes)

            val clientDataJsonStr = JSONObject().apply {
                put("type", "webauthn.create")
                put("challenge", challengeB64)
                put("origin", origin)
                put("crossOrigin", false)
            }.toString()
            val clientDataB64Url = toBase64Url(clientDataJsonStr.toByteArray(Charsets.UTF_8))

            val stored = StoredPasskeyCredential(
                credentialIdBase64Url = credIdB64Url,
                rpId = rpId,
                rpName = rpName,
                userIdBase64Url = userIdB64,
                userName = userName,
                userDisplayName = userDisplayName,
                privateKeyPkcs8Base64 = Base64.encodeToString(privBytes, Base64.NO_WRAP),
                publicKeySpkiBase64 = Base64.encodeToString(spkiBytes, Base64.NO_WRAP),
                cosePublicKeyBase64 = Base64.encodeToString(coseKeyBytes, Base64.NO_WRAP),
                signCount = 1L,
                createdAtMs = System.currentTimeMillis()
            )
            upsertPasskey(stored, notifyPasswordVault = true)

            val credJson = JSONObject().apply {
                put("id", credIdB64Url)
                put("rawId", credIdB64Url)
                put("type", "public-key")
                put("authenticatorAttachment", "platform")
                put(
                    "response",
                    JSONObject().apply {
                        put("clientDataJSON", clientDataB64Url)
                        put("attestationObject", toBase64Url(attestationObjectBytes))
                        put("authenticatorData", toBase64Url(authDataBytes))
                        put("publicKey", toBase64Url(spkiBytes))
                        put("publicKeyAlgorithm", -7)
                        put("transports", JSONArray().put("internal").put("hybrid"))
                    }
                )
            }

            JSONObject().apply {
                put("ok", true)
                put("source", "browser_passkey_vault")
                put("credential", credJson)
            }.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "createPasskeyFromWebView error: ${t.message}")
            JSONObject().apply {
                put("ok", false)
                put("error", t.message ?: "Passkey creation failed")
            }.toString()
        }
    }

    fun getPasskeyAssertionFromWebView(
        optionsJsonStr: String,
        originUrl: String,
        activityContext: Context? = null
    ): String {
        return try {
            val options = JSONObject(optionsJsonStr)
            val parsedOrigin = runCatching { Uri.parse(originUrl) }.getOrNull()
            val scheme = parsedOrigin?.scheme?.lowercase(Locale.US).orEmpty()
            val host = extractHost(originUrl)
            if (scheme !in setOf("https") && host != "localhost" && host != "127.0.0.1") {
                return JSONObject().apply {
                    put("ok", false)
                    put("fallbackToPassword", false)
                    put("error", "Passkey assertion requires a secure HTTPS origin.")
                }.toString()
            }
            val origin = extractOrigin(originUrl)
            val rpId = options.optString("rpId", "").trim().takeIf { it.isNotBlank() } ?: host
            val cleanHost = host.lowercase(Locale.US).removePrefix("www.")
            val cleanRp = rpId.lowercase(Locale.US).removePrefix("www.")
            if (cleanHost != cleanRp && !cleanHost.endsWith(".$cleanRp")) {
                return JSONObject().apply {
                    put("ok", false)
                    put("fallbackToPassword", false)
                    put("error", "Blocked cross-domain passkey assertion for rpId=$rpId from $host.")
                }.toString()
            }
            val challengeB64 = options.optString("challenge", "").trim()
                .ifBlank {
                    val rnd = ByteArray(32).also { secureRandom.nextBytes(it) }
                    toBase64Url(rnd)
                }

            val allowedIds = mutableSetOf<String>()
            val allowArr = options.optJSONArray("allowCredentials")
            if (allowArr != null) {
                for (i in 0 until allowArr.length()) {
                    val item = allowArr.optJSONObject(i) ?: continue
                    val id = item.optString("id", "").trim()
                    if (id.isNotBlank()) allowedIds.add(normalizeBase64Url(id))
                }
            }

            // 1. Check local/synced Browser Passkey Vault first
            val matchingPasskey = passkeys.firstOrNull { pk ->
                val storedRp = pk.rpId.lowercase(Locale.US).removePrefix("www.")
                val targetRp = rpId.lowercase(Locale.US).removePrefix("www.")
                val rpMatches = targetRp == storedRp || targetRp.endsWith(".$storedRp") || storedRp.endsWith(".$targetRp")
                rpMatches && (allowedIds.isEmpty() || normalizeBase64Url(pk.credentialIdBase64Url) in allowedIds)
            }

            if (matchingPasskey != null) {
                val nextSignCount = matchingPasskey.signCount + 1L
                val effectiveRpId = matchingPasskey.rpId.ifBlank { rpId }
                val clientDataJsonStr = JSONObject().apply {
                    put("type", "webauthn.get")
                    put("challenge", challengeB64)
                    put("origin", origin)
                    put("crossOrigin", false)
                }.toString()
                val clientDataBytes = clientDataJsonStr.toByteArray(Charsets.UTF_8)
                val clientDataHash = sha256(clientDataBytes)
                val authDataBytes = buildAssertionAuthenticatorData(effectiveRpId, nextSignCount)

                val signedPayload = ByteArray(authDataBytes.size + clientDataHash.size)
                System.arraycopy(authDataBytes, 0, signedPayload, 0, authDataBytes.size)
                System.arraycopy(clientDataHash, 0, signedPayload, authDataBytes.size, clientDataHash.size)

                val privBytes = Base64.decode(matchingPasskey.privateKeyPkcs8Base64, Base64.DEFAULT)
                val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privBytes))
                val signature = Signature.getInstance("SHA256withECDSA").apply {
                    initSign(privateKey, secureRandom)
                    update(signedPayload)
                }.sign()

                upsertPasskey(matchingPasskey.copy(signCount = nextSignCount), notifyPasswordVault = false)

                val credJson = JSONObject().apply {
                    put("id", matchingPasskey.credentialIdBase64Url)
                    put("rawId", matchingPasskey.credentialIdBase64Url)
                    put("type", "public-key")
                    put("authenticatorAttachment", "platform")
                    put(
                        "response",
                        JSONObject().apply {
                            put("clientDataJSON", toBase64Url(clientDataBytes))
                            put("authenticatorData", toBase64Url(authDataBytes))
                            put("signature", toBase64Url(signature))
                            put("userHandle", matchingPasskey.userIdBase64Url)
                        }
                    )
                }
                return JSONObject().apply {
                    put("ok", true)
                    put("source", "browser_passkey_vault")
                    put("credential", credJson)
                }.toString()
            }

            // 2. Try Android CredentialManager if an Activity is available
            val activity = findActivity(activityContext)
            if (activity != null) {
                val osAssertion = runBlocking {
                    withTimeoutOrNull(60_000L) {
                        kotlin.runCatching {
                            val cm = CredentialManager.create(activity)
                            val opt = GetPublicKeyCredentialOption(requestJson = optionsJsonStr)
                            val res = cm.getCredential(activity, GetCredentialRequest(listOf(opt)))
                            (res.credential as? PublicKeyCredential)?.authenticationResponseJson
                        }.getOrNull()
                    }
                }
                if (!osAssertion.isNullOrBlank()) {
                    return JSONObject().apply {
                        put("ok", true)
                        put("source", "credential_manager")
                        put("credential", JSONObject(osAssertion))
                    }.toString()
                }
            }

            // 3. If this is Google Sign-In (`google.com` / `accounts.google.com`) and the hardware passkey from
            // another device is blocked by WebView assetlinks validation, instruct the page to fall back to password/alternative challenge
            val isGoogleDomain = rpId.equals("google.com", ignoreCase = true) ||
                rpId.endsWith(".google.com", ignoreCase = true)
            JSONObject().apply {
                put("ok", false)
                put("fallbackToPassword", isGoogleDomain)
                put("error", "No matching passkey available in browser vault for $rpId")
            }.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "getPasskeyAssertionFromWebView error: ${t.message}")
            JSONObject().apply {
                put("ok", false)
                put("fallbackToPassword", true)
                put("error", t.message ?: "Passkey assertion failed")
            }.toString()
        }
    }

    private fun upsertPasskey(credential: StoredPasskeyCredential, notifyPasswordVault: Boolean) {
        val current = passkeys.toMutableList()
        val idx = current.indexOfFirst {
            normalizeBase64Url(it.credentialIdBase64Url) == normalizeBase64Url(credential.credentialIdBase64Url) ||
                (it.rpId.equals(credential.rpId, ignoreCase = true) && it.userName.equals(credential.userName, ignoreCase = true))
        }
        if (idx >= 0) {
            current[idx] = credential
        } else {
            current.add(0, credential)
        }
        passkeys = current
        persistPasskeys(current)

        if (notifyPasswordVault) {
            val encodedPasskeyJson = JSONObject().apply {
                put("c", credential.credentialIdBase64Url)
                put("r", credential.rpId)
                put("n", credential.rpName.take(64))
                put("u", credential.userIdBase64Url)
                put("un", credential.userName.take(120))
                put("ud", credential.userDisplayName.take(80))
                put("pk", credential.privateKeyPkcs8Base64)
                put("s", credential.signCount)
                put("t", credential.createdAtMs)
            }.toString()
            val secretPayload = PASSKEY_SECRET_PREFIX +
                Base64.encodeToString(encodedPasskeyJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            recordPasskeyInPasswordVault(
                rpId = credential.rpId,
                rpName = credential.rpName,
                originUrl = "https://${credential.rpId}",
                userName = credential.userName,
                secretPayload = secretPayload
            )
        }
    }

    private fun recordPasskeyInPasswordVault(
        rpId: String,
        rpName: String,
        originUrl: String,
        userName: String,
        secretPayload: String
    ) {
        kotlin.runCatching {
            onPasskeyRecorded(
                SavedPasswordEntry(
                    url = originUrl.ifBlank { "https://$rpId" },
                    domain = rpId.lowercase(Locale.US).removePrefix("www."),
                    username = userName,
                    password = secretPayload,
                    title = "${rpName.ifBlank { rpId }} (Passkey)",
                    importedFrom = "Passkey • Google Sync"
                )
            )
        }
    }

    private fun loadPasskeys(): List<StoredPasskeyCredential> {
        return try {
            val rawStored = prefs.getString(KEY_STORED_PASSKEYS, null) ?: return emptyList()
            val raw = MalwareProtectionEngine.decryptSensitiveString(appContext, rawStored)
            if (raw.isBlank()) return emptyList()
            val arr = JSONArray(raw)
            val list = mutableListOf<StoredPasskeyCredential>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val credId = obj.optString("credentialIdBase64Url", "")
                val priv = obj.optString("privateKeyPkcs8Base64", "")
                if (credId.isBlank() || priv.isBlank()) continue
                list.add(
                    StoredPasskeyCredential(
                        credentialIdBase64Url = credId,
                        rpId = obj.optString("rpId", ""),
                        rpName = obj.optString("rpName", ""),
                        userIdBase64Url = obj.optString("userIdBase64Url", ""),
                        userName = obj.optString("userName", ""),
                        userDisplayName = obj.optString("userDisplayName", ""),
                        privateKeyPkcs8Base64 = priv,
                        publicKeySpkiBase64 = obj.optString("publicKeySpkiBase64", ""),
                        cosePublicKeyBase64 = obj.optString("cosePublicKeyBase64", ""),
                        signCount = obj.optLong("signCount", 1L),
                        createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistPasskeys(items: List<StoredPasskeyCredential>) {
        try {
            val arr = JSONArray()
            items.forEach { pk ->
                arr.put(
                    JSONObject().apply {
                        put("credentialIdBase64Url", pk.credentialIdBase64Url)
                        put("rpId", pk.rpId)
                        put("rpName", pk.rpName)
                        put("userIdBase64Url", pk.userIdBase64Url)
                        put("userName", pk.userName)
                        put("userDisplayName", pk.userDisplayName)
                        put("privateKeyPkcs8Base64", pk.privateKeyPkcs8Base64)
                        put("publicKeySpkiBase64", pk.publicKeySpkiBase64)
                        put("cosePublicKeyBase64", pk.cosePublicKeyBase64)
                        put("signCount", pk.signCount)
                        put("createdAtMs", pk.createdAtMs)
                    }
                )
            }
            val encrypted = MalwareProtectionEngine.encryptSensitiveString(appContext, arr.toString())
            prefs.edit().putString(KEY_STORED_PASSKEYS, encrypted).apply()
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "BrowserPasskeyManager"
        private const val PREFS_NAME = "dragon_browser_passkeys_v1"
        private const val KEY_STORED_PASSKEYS = "stored_passkeys_json_v1"
        const val PASSKEY_SECRET_PREFIX = "PASSKEY_V1:"

        fun isPasskeySecret(secret: String): Boolean {
            return secret.startsWith(PASSKEY_SECRET_PREFIX) || secret == "Android CredentialManager Passkey"
        }

        fun formatSecretForDisplay(secret: String, revealed: Boolean): String {
            if (isPasskeySecret(secret)) {
                return if (revealed) "FIDO2 / WebAuthn Passkey (ES256 P-256)" else "Passkey ••••••••"
            }
            return if (revealed) secret else "•".repeat(secret.length.coerceIn(6, 14))
        }

        private fun findActivity(context: Context?): Activity? {
            var cur = context
            while (cur is ContextWrapper) {
                if (cur is Activity) return cur
                cur = cur.baseContext
            }
            return null
        }

        private fun extractOrigin(url: String): String {
            return kotlin.runCatching {
                val u = Uri.parse(url)
                val scheme = u.scheme?.lowercase(Locale.US) ?: "https"
                val host = u.host?.lowercase(Locale.US) ?: "localhost"
                val port = u.port
                if (port > 0 && port != 80 && port != 443) "$scheme://$host:$port" else "$scheme://$host"
            }.getOrDefault("https://www.google.com")
        }

        private fun extractHost(url: String): String {
            return kotlin.runCatching {
                Uri.parse(url).host?.lowercase(Locale.US)?.removePrefix("www.").orEmpty()
            }.getOrDefault("").ifBlank { "google.com" }
        }

        private fun sha256(bytes: ByteArray): ByteArray {
            return MessageDigest.getInstance("SHA-256").digest(bytes)
        }

        fun toBase64Url(bytes: ByteArray): String {
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        private fun normalizeBase64Url(input: String): String {
            return input.trim().trimEnd('=').replace('+', '-').replace('/', '_')
        }

        private fun bigIntToFixed32Bytes(value: BigInteger): ByteArray {
            val raw = value.toByteArray()
            return when {
                raw.size == 32 -> raw
                raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
                else -> ByteArray(32).also { dest ->
                    System.arraycopy(raw, 0, dest, 32 - raw.size, raw.size)
                }
            }
        }

        /**
         * Encodes an EC2 P-256 public key in COSE_Key CBOR format:
         * `{1: 2, 3: -7, -1: 1, -2: xBytes, -3: yBytes}`
         */
        internal fun encodeCoseEc2PublicKey(xBytes: ByteArray, yBytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            // CBOR map of 5 pairs: 0xA5
            out.write(0xA5)
            // 1: 2 (kty: EC2)
            out.write(0x01)
            out.write(0x02)
            // 3: -7 (alg: ES256 -> negative int -7 is 0x20 | 6 = 0x26)
            out.write(0x03)
            out.write(0x26)
            // -1: 1 (crv: P-256 -> negative int -1 is 0x20)
            out.write(0x20)
            out.write(0x01)
            // -2: h'<32-byte X>' (negative int -2 is 0x21; 32-byte bstr header is 0x58, 0x20)
            out.write(0x21)
            out.write(0x58)
            out.write(0x20)
            out.write(xBytes)
            // -3: h'<32-byte Y>' (negative int -3 is 0x22; 32-byte bstr header is 0x58, 0x20)
            out.write(0x22)
            out.write(0x58)
            out.write(0x20)
            out.write(yBytes)
            return out.toByteArray()
        }

        internal fun buildRegistrationAuthenticatorData(
            rpId: String,
            signCount: Long,
            credentialId: ByteArray,
            cosePublicKey: ByteArray
        ): ByteArray {
            val out = ByteArrayOutputStream()
            // 32-byte rpIdHash
            out.write(sha256(rpId.toByteArray(Charsets.UTF_8)))
            // 1-byte flags: UP (0x01) | UV (0x04) | BE (0x08) | BS (0x10) | AT (0x40) = 0x5D
            out.write(0x5D)
            // 4-byte signCount (big-endian)
            out.write(ByteBuffer.allocate(4).putInt(signCount.toInt()).array())
            // 16-byte AAGUID (all zeros)
            out.write(ByteArray(16))
            // 2-byte credentialId length (big-endian)
            out.write(ByteBuffer.allocate(2).putShort(credentialId.size.toShort()).array())
            // credentialId bytes
            out.write(credentialId)
            // COSE_Key public key bytes
            out.write(cosePublicKey)
            return out.toByteArray()
        }

        internal fun buildAssertionAuthenticatorData(rpId: String, signCount: Long): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(sha256(rpId.toByteArray(Charsets.UTF_8)))
            // 1-byte flags: UP (0x01) | UV (0x04) | BE (0x08) | BS (0x10) = 0x1D
            out.write(0x1D)
            out.write(ByteBuffer.allocate(4).putInt(signCount.toInt()).array())
            return out.toByteArray()
        }

        /**
         * Encodes a WebAuthn `"none"` attestationObject in CBOR:
         * `{"fmt": "none", "attStmt": {}, "authData": h'<authDataBytes>'}`
         */
        internal fun encodeNoneAttestationObject(authDataBytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            // Map of 3 pairs: 0xA3
            out.write(0xA3)
            // "fmt" (3-char tstr: 0x63) -> "none" (4-char tstr: 0x64)
            out.write(0x63)
            out.write("fmt".toByteArray(Charsets.UTF_8))
            out.write(0x64)
            out.write("none".toByteArray(Charsets.UTF_8))
            // "attStmt" (7-char tstr: 0x67) -> {} (empty map: 0xA0)
            out.write(0x67)
            out.write("attStmt".toByteArray(Charsets.UTF_8))
            out.write(0xA0)
            // "authData" (8-char tstr: 0x68) -> bstr(authDataBytes)
            out.write(0x68)
            out.write("authData".toByteArray(Charsets.UTF_8))
            val len = authDataBytes.size
            when {
                len < 24 -> out.write(0x40 or len)
                len <= 0xFF -> {
                    out.write(0x58)
                    out.write(len)
                }
                else -> {
                    out.write(0x59)
                    out.write((len ushr 8) and 0xFF)
                    out.write(len and 0xFF)
                }
            }
            out.write(authDataBytes)
            return out.toByteArray()
        }
    }
}
