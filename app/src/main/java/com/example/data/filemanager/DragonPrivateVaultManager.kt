package com.example.data.filemanager

import android.content.Context
import android.os.Environment
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class DragonVaultEntry(
    val id: String,
    val originalName: String,
    val originalPath: String,
    val vaultPayloadPath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val addedAtMs: Long,
    val extension: String,
    val itemCount: Int = 1
) {
    val payloadFile: File
        get() = File(vaultPayloadPath)
}

data class VaultRestoreOutcome(
    val restoredCount: Int,
    val lastRestoredFile: File?
)

/**
 * Password-protected & AES-256 encrypted Private Vault Manager for Dragon File Manager.
 *
 * - Protects vault access with PBKDF2WithHmacSHA256 (12,000 iterations + 128-bit random salt)
 *   and an AES-256-GCM wrapped Master Vault Key so password changes are instant O(1).
 * - Encrypts all vaulted files and folders on disk using streaming AES-256-CTR with a
 *   `DVAULT01` magic header + 16-byte random per-file IV inside a hidden `.nomedia` directory
 *   so external apps, galleries, or other users cannot view or read vaulted files without
 *   unlocking the Private Vault with the user's password.
 */
object DragonPrivateVaultManager {

    private const val VAULT_ROOT_DIR_NAME = ".DragonPrivateVault"
    private const val VAULT_ITEMS_SUBDIR = "items"
    private const val VAULT_INDEX_FILE_NAME = "vault_index.json"

    private const val PREFS_NAME = "dragon_private_vault_prefs_v1"
    private const val KEY_SALT_B64 = "vault_pwd_salt_b64"
    private const val KEY_VERIFIER_B64 = "vault_pwd_verifier_b64"
    private const val KEY_WRAPPED_MASTER_B64 = "vault_wrapped_master_b64"
    private const val KEY_WRAP_IV_B64 = "vault_wrap_iv_b64"
    private const val KEY_LOCAL_SEAL_B64 = "vault_local_seal_b64"
    private const val KEY_PASSWORD_HINT = "vault_password_hint"

    private const val PBKDF2_ITERATIONS = 12_000
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_BITS = 128
    private val MAGIC_HEADER = "DVAULT01".toByteArray(StandardCharsets.US_ASCII)

    private val lock = Any()
    private val secureRandom = SecureRandom()

    @Volatile
    private var isInitialized = false

    @Volatile
    private var unlockedMasterKey: ByteArray? = null

    private val _vaultItemsFlow = MutableStateFlow<List<DragonVaultEntry>>(emptyList())
    val vaultItemsFlow: StateFlow<List<DragonVaultEntry>> = _vaultItemsFlow.asStateFlow()

    private val _isPasswordConfiguredFlow = MutableStateFlow(false)
    val isPasswordConfiguredFlow: StateFlow<Boolean> = _isPasswordConfiguredFlow.asStateFlow()

    private val _isVaultUnlockedFlow = MutableStateFlow(false)
    val isVaultUnlockedFlow: StateFlow<Boolean> = _isVaultUnlockedFlow.asStateFlow()

    fun ensureLoaded(context: Context) {
        if (isInitialized) return
        synchronized(lock) {
            if (isInitialized) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val configured = !prefs.getString(KEY_SALT_B64, null).isNullOrBlank() &&
                !prefs.getString(KEY_VERIFIER_B64, null).isNullOrBlank()
            _isPasswordConfiguredFlow.value = configured
            reloadFromDiskLocked(context.applicationContext)
            isInitialized = true
        }
    }

    fun isPasswordConfigured(context: Context): Boolean {
        ensureLoaded(context)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val configured = !prefs.getString(KEY_SALT_B64, null).isNullOrBlank() &&
            !prefs.getString(KEY_VERIFIER_B64, null).isNullOrBlank()
        _isPasswordConfiguredFlow.value = configured
        return configured
    }

    fun getPasswordHint(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PASSWORD_HINT, "").orEmpty()
    }

    /**
     * Sets up the Private Vault password for the first time (or resets if vault is empty).
     * Automatically unlocks the vault session upon setup.
     */
    fun setupPassword(
        context: Context,
        password: String,
        hint: String = ""
    ): Boolean {
        val cleanPwd = password.trim()
        if (cleanPwd.isEmpty()) return false
        synchronized(lock) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
            val derivedKey = deriveKeyFromPassword(cleanPwd, salt)

            // Reuse existing master key if present, or generate a new 256-bit master key
            val masterKey = resolveActiveMasterKeyLocked(appContext)
                ?: ByteArray(32).also { secureRandom.nextBytes(it) }

            val wrapIv = ByteArray(12).also { secureRandom.nextBytes(it) }
            val wrappedMaster = aesGcmEncrypt(derivedKey, wrapIv, masterKey)
            val verifier = sha256(derivedKey + masterKey)
            val localSeal = obfuscateLocalSeal(appContext, masterKey)

            prefs.edit()
                .putString(KEY_SALT_B64, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_VERIFIER_B64, Base64.encodeToString(verifier, Base64.NO_WRAP))
                .putString(KEY_WRAPPED_MASTER_B64, Base64.encodeToString(wrappedMaster, Base64.NO_WRAP))
                .putString(KEY_WRAP_IV_B64, Base64.encodeToString(wrapIv, Base64.NO_WRAP))
                .putString(KEY_LOCAL_SEAL_B64, Base64.encodeToString(localSeal, Base64.NO_WRAP))
                .putString(KEY_PASSWORD_HINT, hint.trim())
                .commit()

            unlockedMasterKey = masterKey
            _isPasswordConfiguredFlow.value = true
            _isVaultUnlockedFlow.value = true
            reloadFromDiskLocked(appContext)
            return true
        }
    }

    /**
     * Verifies [password] against the stored PBKDF2 + AES-GCM wrapped master key and unlocks the vault.
     */
    fun unlockVault(context: Context, password: String): Boolean {
        val cleanPwd = password.trim()
        if (cleanPwd.isEmpty()) return false
        synchronized(lock) {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saltB64 = prefs.getString(KEY_SALT_B64, null) ?: return false
            val verifierB64 = prefs.getString(KEY_VERIFIER_B64, null) ?: return false
            val wrappedB64 = prefs.getString(KEY_WRAPPED_MASTER_B64, null) ?: return false
            val wrapIvB64 = prefs.getString(KEY_WRAP_IV_B64, null) ?: return false

            return try {
                val salt = Base64.decode(saltB64, Base64.NO_WRAP)
                val expectedVerifier = Base64.decode(verifierB64, Base64.NO_WRAP)
                val wrappedMaster = Base64.decode(wrappedB64, Base64.NO_WRAP)
                val wrapIv = Base64.decode(wrapIvB64, Base64.NO_WRAP)

                val derivedKey = deriveKeyFromPassword(cleanPwd, salt)
                val masterKey = aesGcmDecrypt(derivedKey, wrapIv, wrappedMaster) ?: return false
                val actualVerifier = sha256(derivedKey + masterKey)
                if (!MessageDigest.isEqual(expectedVerifier, actualVerifier)) {
                    return false
                }
                unlockedMasterKey = masterKey
                _isVaultUnlockedFlow.value = true
                reloadFromDiskLocked(appContext)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun verifyPassword(context: Context, password: String): Boolean {
        return unlockVault(context, password)
    }

    /**
     * Changes the vault password after verifying [oldPassword].
     * Re-wraps the 256-bit Master Vault Key in O(1) so all existing encrypted vault files remain valid.
     */
    fun changePassword(
        context: Context,
        oldPassword: String,
        newPassword: String,
        newHint: String = ""
    ): Boolean {
        val cleanNew = newPassword.trim()
        if (cleanNew.isEmpty()) return false
        synchronized(lock) {
            if (!unlockVault(context, oldPassword)) return false
            val masterKey = unlockedMasterKey ?: return false
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val newSalt = ByteArray(16).also { secureRandom.nextBytes(it) }
            val newDerivedKey = deriveKeyFromPassword(cleanNew, newSalt)
            val newWrapIv = ByteArray(12).also { secureRandom.nextBytes(it) }
            val newWrappedMaster = aesGcmEncrypt(newDerivedKey, newWrapIv, masterKey)
            val newVerifier = sha256(newDerivedKey + masterKey)
            val localSeal = obfuscateLocalSeal(appContext, masterKey)

            prefs.edit()
                .putString(KEY_SALT_B64, Base64.encodeToString(newSalt, Base64.NO_WRAP))
                .putString(KEY_VERIFIER_B64, Base64.encodeToString(newVerifier, Base64.NO_WRAP))
                .putString(KEY_WRAPPED_MASTER_B64, Base64.encodeToString(newWrappedMaster, Base64.NO_WRAP))
                .putString(KEY_WRAP_IV_B64, Base64.encodeToString(newWrapIv, Base64.NO_WRAP))
                .putString(KEY_LOCAL_SEAL_B64, Base64.encodeToString(localSeal, Base64.NO_WRAP))
                .putString(KEY_PASSWORD_HINT, newHint.trim())
                .commit()
            return true
        }
    }

    fun lockVault(context: Context? = null) {
        synchronized(lock) {
            unlockedMasterKey = null
            _isVaultUnlockedFlow.value = false
            if (context != null) {
                clearPreviewCache(context)
            }
        }
    }

    fun refresh(context: Context) {
        synchronized(lock) {
            isInitialized = true
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _isPasswordConfiguredFlow.value = !prefs.getString(KEY_SALT_B64, null).isNullOrBlank()
            reloadFromDiskLocked(context.applicationContext)
        }
    }

    /**
     * Encrypts and moves [source] file or directory into the Private Vault, removing the
     * original unencrypted file/directory from public storage.
     */
    fun moveToVault(
        context: Context,
        source: File,
        onProgressBytes: ((Long) -> Unit)? = null
    ): DragonVaultEntry? {
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        if (!AndroidDataObbBridge.existsUniversal(appContext, source)) return null

        val vaultRoot = resolveVaultRootDir(appContext)
        if (source.absolutePath.startsWith(vaultRoot.absolutePath)) return null

        val masterKey = synchronized(lock) {
            resolveActiveMasterKeyLocked(appContext)
        } ?: return null

        val isDir = AndroidDataObbBridge.isDirectoryUniversal(appContext, source)
        val estimatedSize = runCatching {
            AndroidDataObbBridge.estimateTotalBytesUniversal(appContext, source)
        }.getOrDefault(if (isDir) 0L else source.length())
        val childCount = if (isDir) {
            runCatching { countFilesRecursively(source) }.getOrDefault(1).coerceAtLeast(1)
        } else 1

        val id = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val itemsDir = File(vaultRoot, VAULT_ITEMS_SUBDIR).apply { mkdirs() }
        val payloadDest = File(itemsDir, "$id.dvault")

        val encryptedOk = try {
            if (isDir) {
                encryptDirectoryToVaultPayload(appContext, source, payloadDest, masterKey, onProgressBytes)
            } else {
                encryptFileToVaultPayload(appContext, source, payloadDest, masterKey, onProgressBytes)
            }
        } catch (_: Exception) {
            false
        }

        if (!encryptedOk || !payloadDest.exists() || payloadDest.length() <= MAGIC_HEADER.size + 16) {
            runCatching { payloadDest.delete() }
            return null
        }

        // Delete original unencrypted file or folder from storage
        val deletedOriginal = runCatching {
            if (source.exists() && source.deleteRecursively()) {
                true
            } else {
                AndroidDataObbBridge.deleteFileOrDirectory(appContext, source)
            }
        }.getOrDefault(false)

        if (!deletedOriginal && AndroidDataObbBridge.existsUniversal(appContext, source)) {
            runCatching { payloadDest.delete() }
            return null
        }

        val entry = DragonVaultEntry(
            id = id,
            originalName = source.name,
            originalPath = source.absolutePath,
            vaultPayloadPath = payloadDest.absolutePath,
            isDirectory = isDir,
            sizeBytes = estimatedSize.coerceAtLeast(0L),
            addedAtMs = System.currentTimeMillis(),
            extension = if (isDir) "" else source.extension.lowercase(Locale.US),
            itemCount = childCount
        )

        synchronized(lock) {
            val current = loadIndexEntriesLocked(vaultRoot).toMutableList()
            current.add(0, entry)
            saveIndexEntriesLocked(vaultRoot, current)
            _vaultItemsFlow.value = current
        }
        DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(source.parentFile?.absolutePath)
        DragonFileManagerTaskEngine.notifyExternalDirectoryMutation()
        return entry
    }

    /**
     * Decrypts and restores [entries] from the Private Vault back to their original paths
     * (or to [overrideTargetDir] if specified).
     */
    fun restoreFromVault(
        context: Context,
        entries: List<DragonVaultEntry>,
        overrideTargetDir: File? = null
    ): VaultRestoreOutcome {
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        val masterKey = synchronized(lock) {
            resolveActiveMasterKeyLocked(appContext)
        } ?: return VaultRestoreOutcome(0, null)

        val vaultRoot = resolveVaultRootDir(appContext)
        var restoredCount = 0
        var lastRestoredFile: File? = null
        val restoredIds = HashSet<String>()
        val touchedParents = HashSet<String>()

        entries.forEach { entry ->
            val payloadFile = entry.payloadFile
            if (!payloadFile.exists()) {
                restoredIds.add(entry.id)
                return@forEach
            }

            val desiredTarget = if (overrideTargetDir != null) {
                File(overrideTargetDir, entry.originalName)
            } else {
                File(entry.originalPath)
            }
            val parentDir = desiredTarget.parentFile ?: appContext.filesDir
            AndroidDataObbBridge.createDirectory(appContext, parentDir)

            val finalDest = resolveNonCollidingDestination(appContext, desiredTarget)
            val decryptedOk = try {
                if (entry.isDirectory) {
                    decryptVaultPayloadToDirectory(appContext, payloadFile, finalDest, masterKey)
                } else {
                    decryptVaultPayloadToFile(appContext, payloadFile, finalDest, masterKey)
                }
            } catch (_: Exception) {
                false
            }

            if (decryptedOk && AndroidDataObbBridge.existsUniversal(appContext, finalDest)) {
                runCatching { payloadFile.delete() }
                restoredIds.add(entry.id)
                restoredCount++
                lastRestoredFile = finalDest
                parentDir.absolutePath.let { touchedParents.add(it) }
            }
        }

        if (restoredIds.isNotEmpty()) {
            synchronized(lock) {
                val updated = loadIndexEntriesLocked(vaultRoot).filterNot { it.id in restoredIds }
                saveIndexEntriesLocked(vaultRoot, updated)
                _vaultItemsFlow.value = updated
            }
            touchedParents.forEach { DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(it) }
            DragonFileManagerTaskEngine.notifyExternalDirectoryMutation()
        }

        return VaultRestoreOutcome(
            restoredCount = restoredCount,
            lastRestoredFile = lastRestoredFile
        )
    }

    /**
     * Decrypts a single file entry to an app-private ephemeral preview file so the user can
     * view/play/open the file directly from inside the unlocked Private Vault.
     */
    fun decryptToPreviewTempFile(
        context: Context,
        entry: DragonVaultEntry
    ): File? {
        if (entry.isDirectory) return null
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        val masterKey = synchronized(lock) {
            resolveActiveMasterKeyLocked(appContext)
        } ?: return null

        val payloadFile = entry.payloadFile
        if (!payloadFile.exists()) return null

        val previewDir = File(appContext.cacheDir, "vault_preview_cache").apply {
            mkdirs()
            runCatching { File(this, ".nomedia").createNewFile() }
        }
        val safeName = entry.originalName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "vault_item" }
        val outFile = File(previewDir, "${entry.id.takeLast(6)}_$safeName")
        if (outFile.exists() && outFile.length() > 0L) {
            return outFile
        }
        val ok = decryptVaultPayloadToFile(appContext, payloadFile, outFile, masterKey)
        return if (ok && outFile.exists()) outFile else null
    }

    fun clearPreviewCache(context: Context) {
        runCatching {
            File(context.applicationContext.cacheDir, "vault_preview_cache").deleteRecursively()
        }
    }

    /**
     * Permanently deletes [entries] from the Private Vault.
     */
    fun deletePermanentlyFromVault(
        context: Context,
        entries: List<DragonVaultEntry>
    ): Int {
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        val vaultRoot = resolveVaultRootDir(appContext)
        var deletedCount = 0
        val removedIds = HashSet<String>()

        entries.forEach { entry ->
            val payload = entry.payloadFile
            val ok = if (payload.exists()) {
                runCatching { payload.delete() }.getOrDefault(false)
            } else true
            if (ok || !payload.exists()) {
                removedIds.add(entry.id)
                deletedCount++
            }
        }

        if (removedIds.isNotEmpty()) {
            synchronized(lock) {
                val updated = loadIndexEntriesLocked(vaultRoot).filterNot { it.id in removedIds }
                saveIndexEntriesLocked(vaultRoot, updated)
                _vaultItemsFlow.value = updated
            }
        }
        return deletedCount
    }

    // =========================================================================
    // Cryptography & Streaming AES-256 Payload Helpers
    // =========================================================================

    private fun deriveKeyFromPassword(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun sha256(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    private fun aesGcmEncrypt(keyBytes: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(plain)
    }

    private fun aesGcmDecrypt(keyBytes: ByteArray, iv: ByteArray, cipherBytes: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(cipherBytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun localSealMask(context: Context): ByteArray {
        val seed = "DragonVaultSeal::${context.packageName}::v1"
        return sha256(seed.toByteArray(StandardCharsets.UTF_8))
    }

    private fun obfuscateLocalSeal(context: Context, masterKey: ByteArray): ByteArray {
        val mask = localSealMask(context)
        return ByteArray(masterKey.size) { i ->
            (masterKey[i].toInt() xor mask[i % mask.size].toInt()).toByte()
        }
    }

    private fun resolveActiveMasterKeyLocked(context: Context): ByteArray? {
        unlockedMasterKey?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sealB64 = prefs.getString(KEY_LOCAL_SEAL_B64, null) ?: return null
        return try {
            val sealed = Base64.decode(sealB64, Base64.NO_WRAP)
            if (sealed.size != 32) null else obfuscateLocalSeal(context, sealed)
        } catch (_: Exception) {
            null
        }
    }

    private fun encryptFileToVaultPayload(
        context: Context,
        sourceFile: File,
        payloadDest: File,
        masterKey: ByteArray,
        onProgressBytes: ((Long) -> Unit)? = null
    ): Boolean {
        payloadDest.parentFile?.mkdirs()
        val iv = ByteArray(16).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"), IvParameterSpec(iv))

        val inputStream = AndroidDataObbBridge.openInputStreamForFile(context, sourceFile) ?: return false
        inputStream.use { rawIn ->
            BufferedInputStream(rawIn, 64 * 1024).use { input ->
                BufferedOutputStream(FileOutputStream(payloadDest), 64 * 1024).use { rawOut ->
                    rawOut.write(MAGIC_HEADER)
                    rawOut.write(iv)
                    CipherOutputStream(rawOut, cipher).use { cipherOut ->
                        val buffer = ByteArray(64 * 1024)
                        var read = input.read(buffer)
                        while (read > 0) {
                            cipherOut.write(buffer, 0, read)
                            onProgressBytes?.invoke(read.toLong())
                            read = input.read(buffer)
                        }
                        cipherOut.flush()
                    }
                }
            }
        }
        return payloadDest.exists() && payloadDest.length() >= MAGIC_HEADER.size + 16
    }

    private fun decryptVaultPayloadToFile(
        context: Context,
        payloadFile: File,
        destFile: File,
        masterKey: ByteArray
    ): Boolean {
        val tempOut = if (AndroidDataObbBridge.isAndroidDataOrObbPath(destFile.absolutePath) ||
            AndroidDataObbBridge.isRootProtectedRomPath(destFile.absolutePath)
        ) {
            File(context.cacheDir, "vault_restore_${System.nanoTime()}.tmp")
        } else {
            destFile.parentFile?.mkdirs()
            destFile
        }

        val ok = try {
            BufferedInputStream(FileInputStream(payloadFile), 64 * 1024).use { input ->
                val magic = ByteArray(MAGIC_HEADER.size)
                if (input.read(magic) != MAGIC_HEADER.size || !magic.contentEquals(MAGIC_HEADER)) {
                    return false
                }
                val iv = ByteArray(16)
                if (input.read(iv) != 16) {
                    return false
                }
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(masterKey, "AES"), IvParameterSpec(iv))
                CipherInputStream(input, cipher).use { cipherIn ->
                    BufferedOutputStream(FileOutputStream(tempOut), 64 * 1024).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var read = cipherIn.read(buffer)
                        while (read > 0) {
                            out.write(buffer, 0, read)
                            read = cipherIn.read(buffer)
                        }
                        out.flush()
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }

        if (!ok) {
            if (tempOut != destFile) runCatching { tempOut.delete() }
            return false
        }

        if (tempOut != destFile) {
            val copied = AndroidDataObbBridge.copyFileOrDirectoryUniversal(context, tempOut, destFile, isDirectoryHint = false)
            runCatching { tempOut.delete() }
            return copied
        }
        return destFile.exists()
    }

    private fun encryptDirectoryToVaultPayload(
        context: Context,
        sourceDir: File,
        payloadDest: File,
        masterKey: ByteArray,
        onProgressBytes: ((Long) -> Unit)? = null
    ): Boolean {
        val stagingDir = if (sourceDir.exists() && sourceDir.canRead()) {
            sourceDir
        } else {
            val tmpDir = File(context.cacheDir, "vault_dir_stage_${System.nanoTime()}").apply { mkdirs() }
            if (!AndroidDataObbBridge.copyFileOrDirectoryUniversal(context, sourceDir, tmpDir, isDirectoryHint = true)) {
                runCatching { tmpDir.deleteRecursively() }
                return false
            }
            tmpDir
        }

        return try {
            payloadDest.parentFile?.mkdirs()
            val iv = ByteArray(16).also { secureRandom.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"), IvParameterSpec(iv))

            BufferedOutputStream(FileOutputStream(payloadDest), 64 * 1024).use { rawOut ->
                rawOut.write(MAGIC_HEADER)
                rawOut.write(iv)
                CipherOutputStream(rawOut, cipher).use { cipherOut ->
                    ZipOutputStream(BufferedOutputStream(cipherOut, 64 * 1024)).use { zos ->
                        fun addNode(node: File, relPath: String) {
                            val children = node.listFiles()
                            if (node.isDirectory) {
                                if (relPath.isNotEmpty()) {
                                    val dirEntry = ZipEntry(if (relPath.endsWith("/")) relPath else "$relPath/")
                                    dirEntry.time = node.lastModified()
                                    zos.putNextEntry(dirEntry)
                                    zos.closeEntry()
                                }
                                children?.forEach { child ->
                                    val childRel = if (relPath.isEmpty()) child.name else "$relPath/${child.name}"
                                    addNode(child, childRel)
                                }
                            } else if (node.isFile) {
                                val entry = ZipEntry(relPath)
                                entry.time = node.lastModified()
                                zos.putNextEntry(entry)
                                BufferedInputStream(FileInputStream(node), 64 * 1024).use { fileIn ->
                                    val buf = ByteArray(64 * 1024)
                                    var r = fileIn.read(buf)
                                    while (r > 0) {
                                        zos.write(buf, 0, r)
                                        onProgressBytes?.invoke(r.toLong())
                                        r = fileIn.read(buf)
                                    }
                                }
                                zos.closeEntry()
                            }
                        }
                        addNode(stagingDir, "")
                        zos.finish()
                    }
                }
            }
            payloadDest.exists() && payloadDest.length() > MAGIC_HEADER.size + 16
        } finally {
            if (stagingDir != sourceDir) {
                runCatching { stagingDir.deleteRecursively() }
            }
        }
    }

    private fun decryptVaultPayloadToDirectory(
        context: Context,
        payloadFile: File,
        destDir: File,
        masterKey: ByteArray
    ): Boolean {
        val isRestricted = AndroidDataObbBridge.isAndroidDataOrObbPath(destDir.absolutePath) ||
            AndroidDataObbBridge.isRootProtectedRomPath(destDir.absolutePath)
        val unpackDir = if (isRestricted) {
            File(context.cacheDir, "vault_dir_unpack_${System.nanoTime()}").apply { mkdirs() }
        } else {
            destDir.apply { mkdirs() }
        }

        val ok = try {
            val canonicalRoot = unpackDir.canonicalPath
            val canonicalRootPrefix = if (canonicalRoot.endsWith(File.separator)) canonicalRoot else canonicalRoot + File.separator
            BufferedInputStream(FileInputStream(payloadFile), 64 * 1024).use { input ->
                val magic = ByteArray(MAGIC_HEADER.size)
                if (input.read(magic) != MAGIC_HEADER.size || !magic.contentEquals(MAGIC_HEADER)) {
                    return false
                }
                val iv = ByteArray(16)
                if (input.read(iv) != 16) {
                    return false
                }
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(masterKey, "AES"), IvParameterSpec(iv))
                CipherInputStream(input, cipher).use { cipherIn ->
                    ZipInputStream(BufferedInputStream(cipherIn, 64 * 1024)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val cleanName = entry.name.replace('\\', '/').trimStart('/')
                            if (cleanName.isNotBlank()) {
                                val outFile = File(unpackDir, cleanName)
                                if (outFile.canonicalPath.startsWith(canonicalRootPrefix)) {
                                    if (entry.isDirectory) {
                                        outFile.mkdirs()
                                    } else {
                                        outFile.parentFile?.mkdirs()
                                        BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { out ->
                                            val buf = ByteArray(64 * 1024)
                                            var r = zis.read(buf)
                                            while (r > 0) {
                                                out.write(buf, 0, r)
                                                r = zis.read(buf)
                                            }
                                            out.flush()
                                        }
                                    }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }

        if (!ok) {
            if (unpackDir != destDir) runCatching { unpackDir.deleteRecursively() }
            return false
        }

        if (unpackDir != destDir) {
            val copied = AndroidDataObbBridge.copyFileOrDirectoryUniversal(context, unpackDir, destDir, isDirectoryHint = true)
            runCatching { unpackDir.deleteRecursively() }
            return copied
        }
        return destDir.exists()
    }

    private fun countFilesRecursively(dir: File, depth: Int = 0): Int {
        if (depth > 6 || !dir.exists()) return 0
        val children = dir.listFiles() ?: return 0
        var count = 0
        for (c in children) {
            count++
            if (c.isDirectory) {
                count += countFilesRecursively(c, depth + 1)
            }
        }
        return count.coerceAtLeast(1)
    }

    private fun resolveNonCollidingDestination(context: Context, desired: File): File {
        if (!AndroidDataObbBridge.existsUniversal(context, desired)) return desired
        val parent = desired.parentFile ?: return desired
        val isDir = desired.isDirectory
        val base = if (isDir) desired.name else desired.nameWithoutExtension.ifBlank { desired.name }
        val ext = if (isDir) "" else desired.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var index = 1
        var candidate = File(parent, "${base}_restored$ext")
        while (AndroidDataObbBridge.existsUniversal(context, candidate)) {
            candidate = File(parent, "${base}_restored_($index)$ext")
            index++
        }
        return candidate
    }

    private fun resolveVaultRootDir(context: Context): File {
        val publicDdmVault = runCatching {
            val pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(File(pubDownloads, "DDM"), VAULT_ROOT_DIR_NAME)
        }.getOrNull()

        if (publicDdmVault != null && (publicDdmVault.exists() || publicDdmVault.mkdirs()) && publicDdmVault.canWrite()) {
            ensureNoMediaMarker(publicDdmVault)
            return publicDdmVault
        }

        val appVault = File(context.filesDir, "dragon_private_vault").apply { mkdirs() }
        ensureNoMediaMarker(appVault)
        return appVault
    }

    private fun ensureNoMediaMarker(vaultRoot: File) {
        runCatching {
            vaultRoot.mkdirs()
            File(vaultRoot, VAULT_ITEMS_SUBDIR).mkdirs()
            val noMedia = File(vaultRoot, ".nomedia")
            if (!noMedia.exists()) {
                noMedia.createNewFile()
            }
        }
    }

    private fun reloadFromDiskLocked(context: Context) {
        val vaultRoot = resolveVaultRootDir(context)
        val entries = loadIndexEntriesLocked(vaultRoot)
        _vaultItemsFlow.value = entries
    }

    private fun loadIndexEntriesLocked(vaultRoot: File): List<DragonVaultEntry> {
        val indexFile = File(vaultRoot, VAULT_INDEX_FILE_NAME)
        if (!indexFile.exists()) return emptyList()
        val parsed = runCatching {
            val raw = indexFile.readText(StandardCharsets.UTF_8)
            val arr = JSONArray(raw)
            val list = ArrayList<DragonVaultEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val originalName = obj.optString("originalName").trim()
                val originalPath = obj.optString("originalPath").trim()
                val vaultPayloadPath = obj.optString("vaultPayloadPath").trim()
                if (id.isEmpty() || originalName.isEmpty() || vaultPayloadPath.isEmpty()) continue
                val payloadFile = File(vaultPayloadPath)
                if (!payloadFile.exists()) continue
                list.add(
                    DragonVaultEntry(
                        id = id,
                        originalName = originalName,
                        originalPath = originalPath,
                        vaultPayloadPath = vaultPayloadPath,
                        isDirectory = obj.optBoolean("isDirectory", false),
                        sizeBytes = obj.optLong("sizeBytes", payloadFile.length()),
                        addedAtMs = obj.optLong("addedAtMs", payloadFile.lastModified()),
                        extension = obj.optString("extension", ""),
                        itemCount = obj.optInt("itemCount", 1).coerceAtLeast(1)
                    )
                )
            }
            list.sortedByDescending { it.addedAtMs }
        }.getOrDefault(emptyList())

        return parsed
    }

    private fun saveIndexEntriesLocked(vaultRoot: File, entries: List<DragonVaultEntry>) {
        runCatching {
            vaultRoot.mkdirs()
            val arr = JSONArray()
            entries.forEach { entry ->
                val obj = JSONObject().apply {
                    put("id", entry.id)
                    put("originalName", entry.originalName)
                    put("originalPath", entry.originalPath)
                    put("vaultPayloadPath", entry.vaultPayloadPath)
                    put("isDirectory", entry.isDirectory)
                    put("sizeBytes", entry.sizeBytes)
                    put("addedAtMs", entry.addedAtMs)
                    put("extension", entry.extension)
                    put("itemCount", entry.itemCount)
                }
                arr.put(obj)
            }
            val indexFile = File(vaultRoot, VAULT_INDEX_FILE_NAME)
            indexFile.writeText(arr.toString(2), StandardCharsets.UTF_8)
        }
    }
}
