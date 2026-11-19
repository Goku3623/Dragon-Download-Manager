package com.example.data.browser

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.Keep
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(exception: Throwable, operationType: OperationType, path: String?): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val auth = kotlin.runCatching { FirebaseAuth.getInstance() }.getOrNull()
    val currentUser = auth?.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

@Keep
@IgnoreExtraProperties
data class SyncedPasswordDocument(
    @DocumentId val docId: String = "",
    val userId: String = "",
    val url: String = "",
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    val title: String = "",
    val importedFrom: String = "Google Sync",
    val createdAtMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class GoogleSyncState(
    val isSignedIn: Boolean = false,
    val isCloudAuthConnected: Boolean = false,
    val uid: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val detectedWebViewEmail: String? = null,
    val detectedWebViewName: String? = null,
    val hasWebViewGoogleSession: Boolean = false,
    val autoSyncEnabled: Boolean = true,
    val autoSyncPasswords: Boolean = true,
    val autoCaptureWebPasswords: Boolean = true,
    val isSyncing: Boolean = false,
    val lastSyncedAtMs: Long = 0L,
    val syncedPasswordsCount: Int = 0,
    val syncStatusMessage: String? = null,
    val syncErrorMessage: String? = null
) {
    val effectiveAccountEmail: String?
        get() = email?.takeIf { it.isNotBlank() } ?: detectedWebViewEmail?.takeIf { it.isNotBlank() }

    val effectiveAccountName: String?
        get() = displayName?.takeIf { it.isNotBlank() }
            ?: detectedWebViewName?.takeIf { it.isNotBlank() }
            ?: effectiveAccountEmail?.substringBefore('@')

    val isBrowserGoogleConnected: Boolean
        get() = isSignedIn || isCloudAuthConnected || !detectedWebViewEmail.isNullOrBlank() || hasWebViewGoogleSession
}

/**
 * Manages real-time two-way Google Account synchronization for Saved Passwords and Passkeys.
 * - Detects when a Google Account is signed into the Browser WebView (`accounts.google.com`, `google.com`,
 *   `myaccount.google.com`, `passwords.google.com`), probes `ListAccounts` / `myaccount.google.com` using
 *   the WebView's cookies to resolve the signed-in Gmail address and display name, and syncs passwords.
 * - Also authenticates with Google via Jetpack Credential Manager (`GetSignInWithGoogleOption` / `GetGoogleIdOption`)
 *   and Firebase Auth (`GoogleAuthProvider`) to sync with Cloud Firestore
 *   (`users/{userId}/passwords/{passwordId}`).
 */
class BrowserGoogleSyncManager(
    private val appContext: Context,
    private val db: FirebaseFirestore?,
    private val auth: FirebaseAuth?,
    private val onRemotePasswordsReceived: (List<SavedPasswordEntry>) -> Unit,
    private val localPasswordsProvider: () -> List<SavedPasswordEntry>,
    private val onAccountVaultSyncRequested: () -> Unit = {}
) {
    constructor(
        context: Context,
        onRemotePasswordsReceived: (List<SavedPasswordEntry>) -> Unit,
        localPasswordsProvider: () -> List<SavedPasswordEntry>,
        onAccountVaultSyncRequested: () -> Unit = {}
    ) : this(
        appContext = context.applicationContext,
        db = kotlin.runCatching {
            val databaseId = context.applicationContext.getString(R.string.firestore_database_id)
            FirebaseFirestore.getInstance(databaseId)
        }.getOrNull(),
        auth = kotlin.runCatching { FirebaseAuth.getInstance() }.getOrNull(),
        onRemotePasswordsReceived = onRemotePasswordsReceived,
        localPasswordsProvider = localPasswordsProvider,
        onAccountVaultSyncRequested = onAccountVaultSyncRequested
    )

    private val prefs = appContext.getSharedPreferences("dragon_browser_google_sync", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private var passwordsListener: ListenerRegistration? = null
    private var activeListeningUserId: String? = null
    private val isProbingCookies = AtomicBoolean(false)

    private val knownRemotePasswordIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private val _syncStateFlow = MutableStateFlow(loadInitialSyncState())
    val syncStateFlow: StateFlow<GoogleSyncState> = _syncStateFlow.asStateFlow()

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        handleFirebaseUserChanged(user)
    }

    init {
        try {
            auth?.addAuthStateListener(authStateListener)
            val initialUser = auth?.currentUser
            if (initialUser != null) {
                handleFirebaseUserChanged(initialUser)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "FirebaseAuth listener init skipped in current environment", t)
        }
        // Automatically check existing WebView Google cookies on startup
        inspectActiveWebViewGoogleCookiesAsync()
    }

    private fun loadInitialSyncState(): GoogleSyncState {
        val currentUser = kotlin.runCatching { auth?.currentUser }.getOrNull()
        val rawSavedWebEmail = prefs.getString(KEY_WEBVIEW_GOOGLE_EMAIL, null)
        val savedWebEmail = sanitizeEmailCandidate(rawSavedWebEmail)
        if (!rawSavedWebEmail.isNullOrBlank() && savedWebEmail != rawSavedWebEmail) {
            prefs.edit().apply {
                if (savedWebEmail != null) putString(KEY_WEBVIEW_GOOGLE_EMAIL, savedWebEmail)
                else remove(KEY_WEBVIEW_GOOGLE_EMAIL)
            }.apply()
        }
        val savedWebName = prefs.getString(KEY_WEBVIEW_GOOGLE_NAME, null)?.takeIf { it.isNotBlank() }
        val hasWebSession = prefs.getBoolean(KEY_WEBVIEW_GOOGLE_SESSION, false) || !savedWebEmail.isNullOrBlank()
        val isConnected = currentUser != null || !savedWebEmail.isNullOrBlank() || hasWebSession
        return GoogleSyncState(
            isSignedIn = isConnected,
            isCloudAuthConnected = currentUser != null,
            uid = currentUser?.uid,
            email = currentUser?.email?.takeIf { it.isNotBlank() } ?: savedWebEmail,
            displayName = currentUser?.displayName?.takeIf { it.isNotBlank() } ?: savedWebName,
            photoUrl = currentUser?.photoUrl?.toString(),
            detectedWebViewEmail = savedWebEmail,
            detectedWebViewName = savedWebName,
            hasWebViewGoogleSession = hasWebSession,
            autoSyncEnabled = prefs.getBoolean(KEY_AUTO_SYNC_ENABLED, true),
            autoSyncPasswords = prefs.getBoolean(KEY_AUTO_SYNC_PASSWORDS, true),
            autoCaptureWebPasswords = prefs.getBoolean(KEY_AUTO_CAPTURE_PASSWORDS, true),
            lastSyncedAtMs = prefs.getLong(KEY_LAST_SYNCED_MS, 0L),
            syncedPasswordsCount = prefs.getInt(KEY_SYNCED_PASSWORDS_COUNT, 0)
        )
    }

    private fun handleFirebaseUserChanged(user: FirebaseUser?) {
        if (user != null) {
            _syncStateFlow.update { state ->
                val resolvedEmail = user.email?.takeIf { it.isNotBlank() } ?: state.detectedWebViewEmail
                val resolvedName = user.displayName?.takeIf { it.isNotBlank() } ?: state.detectedWebViewName
                state.copy(
                    isSignedIn = true,
                    isCloudAuthConnected = true,
                    uid = user.uid,
                    email = resolvedEmail,
                    displayName = resolvedName,
                    photoUrl = user.photoUrl?.toString(),
                    syncErrorMessage = null
                )
            }
            if (_syncStateFlow.value.autoSyncEnabled) {
                startRealtimeListeners(user.uid)
                scope.launch {
                    syncAllLocalToCloudInternal(user.uid)
                }
            }
        } else {
            stopRealtimeListeners()
            _syncStateFlow.update { state ->
                val stillConnectedViaWeb = !state.detectedWebViewEmail.isNullOrBlank() || state.hasWebViewGoogleSession
                state.copy(
                    isSignedIn = stillConnectedViaWeb,
                    isCloudAuthConnected = false,
                    uid = null,
                    email = state.detectedWebViewEmail,
                    displayName = state.detectedWebViewName,
                    photoUrl = null,
                    isSyncing = false
                )
            }
        }
    }

    /**
     * Reads the Android WebView `CookieManager` for `google.com` / `accounts.google.com` / `myaccount.google.com`.
     * If an authenticated Google session is present, immediately marks the session active, probes the Google
     * account email & display name in the background, and syncs passwords.
     */
    fun inspectActiveWebViewGoogleCookiesAsync(userAgentHint: String? = null) {
        val mergedCookies = collectWebViewGoogleCookies()
        if (hasAuthenticatedGoogleCookies(mergedCookies)) {
            onBrowserWebViewGoogleAccountDetected(
                email = null,
                displayName = null,
                hasSessionCookies = true
            )
            probeGoogleAccountFromCookiesAsync(mergedCookies, userAgentHint)
        }
    }

    private fun collectWebViewGoogleCookies(): String {
        return kotlin.runCatching {
            val cm = CookieManager.getInstance()
            val urls = listOf(
                "https://accounts.google.com",
                "https://www.google.com",
                "https://myaccount.google.com",
                "https://passwords.google.com",
                "https://mail.google.com"
            )
            val cookieMap = LinkedHashMap<String, String>()
            urls.forEach { u ->
                val raw = kotlin.runCatching { cm.getCookie(u) }.getOrNull().orEmpty()
                raw.split(';').forEach { part ->
                    val name = part.substringBefore('=').trim()
                    val value = part.substringAfter('=', "").trim()
                    if (name.isNotBlank() && value.isNotBlank()) {
                        cookieMap[name] = value
                    }
                }
            }
            cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }.getOrDefault("")
    }

    private fun probeGoogleAccountFromCookiesAsync(
        cookieHeader: String,
        userAgentHint: String? = null
    ) {
        if (cookieHeader.isBlank()) return
        if (!isProbingCookies.compareAndSet(false, true)) return
        scope.launch {
            try {
                val ua = userAgentHint?.takeIf { it.isNotBlank() } ?: DEFAULT_PROBE_USER_AGENT
                val (detectedEmail, detectedName) = probeGoogleAccountIdentitySuspend(cookieHeader, ua)
                if (!detectedEmail.isNullOrBlank()) {
                    onBrowserWebViewGoogleAccountDetected(
                        email = detectedEmail,
                        displayName = detectedName,
                        hasSessionCookies = true
                    )
                }
                refreshLocalSyncCounters()
            } catch (t: Throwable) {
                Log.d(TAG, "Google cookie probe completed without remote extras: ${t.message}")
            } finally {
                isProbingCookies.set(false)
            }
        }
    }

    private fun probeGoogleAccountIdentitySuspend(
        cookieHeader: String,
        userAgent: String
    ): Pair<String?, String?> {
        // 1. Query Google's official OneGoogleBar ListAccounts endpoint
        kotlin.runCatching {
            val req = Request.Builder()
                .url("https://accounts.google.com/ListAccounts?gpsia=1&source=ogb&mo=1&origin=https://www.google.com")
                .header("Cookie", cookieHeader)
                .header("User-Agent", userAgent)
                .header("Origin", "https://www.google.com")
                .header("Referer", "https://www.google.com/")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val parsed = parseGoogleIdentityFromPayload(body)
                if (!parsed.first.isNullOrBlank()) {
                    return parsed
                }
            }
        }

        // 2. Fallback: Query myaccount.google.com HTML where Google embeds the active account email & name
        kotlin.runCatching {
            val req = Request.Builder()
                .url("https://myaccount.google.com/?utm_source=sign_in_no_continue")
                .header("Cookie", cookieHeader)
                .header("User-Agent", userAgent)
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val parsed = parseGoogleIdentityFromPayload(body)
                if (!parsed.first.isNullOrBlank()) {
                    return parsed
                }
            }
        }

        return null to null
    }

    /**
     * Attempts a non-UI silent Google Credential Manager sign-in if an authorized account was previously granted,
     * keeping Firebase Cloud Firestore sync active across restarts even when only WebView cookies were present.
     */
    suspend fun trySilentGoogleSignInIfNeeded(activityContext: Context) {
        if (auth?.currentUser != null) return
        if (!_syncStateFlow.value.autoSyncEnabled) return
        kotlin.runCatching {
            val webClientId = resolveWebClientId(activityContext)
            val credentialManager = CredentialManager.create(activityContext)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(webClientId)
                .setFilterByAuthorizedAccounts(true)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val result = credentialManager.getCredential(activityContext, request)
            val idToken = extractGoogleIdToken(result.credential)
            val firebaseAuth = auth ?: return
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            firebaseAuth.signInWithCredential(credential).await()
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC_ENABLED, enabled).apply()
        _syncStateFlow.update {
            it.copy(
                autoSyncEnabled = enabled,
                syncStatusMessage = if (enabled) "Auto-sync enabled" else "Auto-sync paused",
                syncErrorMessage = null
            )
        }
        if (enabled) {
            onAccountVaultSyncRequested()
            refreshLocalSyncCounters()
            inspectActiveWebViewGoogleCookiesAsync()
            val uid = auth?.currentUser?.uid
            if (uid != null) {
                startRealtimeListeners(uid)
                scope.launch { syncAllLocalToCloudInternal(uid) }
            }
        } else {
            stopRealtimeListeners()
        }
    }

    fun setAutoSyncPasswords(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC_PASSWORDS, enabled).apply()
        _syncStateFlow.update { it.copy(autoSyncPasswords = enabled) }
        if (enabled && _syncStateFlow.value.autoSyncEnabled) {
            onAccountVaultSyncRequested()
            refreshLocalSyncCounters()
            val uid = auth?.currentUser?.uid
            if (uid != null) {
                startRealtimeListeners(uid)
                scope.launch { syncPasswordsListToCloud(localPasswordsProvider()) }
            }
        }
    }

    fun setAutoCaptureWebPasswords(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CAPTURE_PASSWORDS, enabled).apply()
        _syncStateFlow.update { it.copy(autoCaptureWebPasswords = enabled) }
    }

    /**
     * Records a Google account detected inside the Browser WebView (via `accounts.google.com` or `google.com`).
     * Automatically restores/merges any passwords saved for this Google account and triggers sync.
     */
    fun onBrowserWebViewGoogleAccountDetected(
        email: String?,
        displayName: String?,
        hasSessionCookies: Boolean
    ) {
        val normalizedEmail = sanitizeEmailCandidate(email)
        val cleanName = displayName?.trim()?.takeIf { it.isNotBlank() }?.take(120)
        val current = _syncStateFlow.value
        val existingSafeWebEmail = sanitizeEmailCandidate(current.detectedWebViewEmail)
        val updatedEmail = normalizedEmail ?: existingSafeWebEmail
        val updatedName = cleanName ?: current.detectedWebViewName
        val updatedSession = hasSessionCookies || !updatedEmail.isNullOrBlank()
        val pCount = kotlin.runCatching { localPasswordsProvider().size }.getOrDefault(current.syncedPasswordsCount)
        val now = System.currentTimeMillis()

        prefs.edit().apply {
            if (updatedEmail != null) putString(KEY_WEBVIEW_GOOGLE_EMAIL, updatedEmail)
            else remove(KEY_WEBVIEW_GOOGLE_EMAIL)
            if (updatedName != null) putString(KEY_WEBVIEW_GOOGLE_NAME, updatedName)
            putBoolean(KEY_WEBVIEW_GOOGLE_SESSION, updatedSession)
            if (current.autoSyncEnabled && updatedSession) {
                putLong(KEY_LAST_SYNCED_MS, now)
                putInt(KEY_SYNCED_PASSWORDS_COUNT, pCount)
            }
        }.apply()

        _syncStateFlow.update {
            val isNowSignedIn = it.isCloudAuthConnected || updatedSession || !updatedEmail.isNullOrBlank()
            val authEmail = sanitizeEmailCandidate(auth?.currentUser?.email)
            val resolvedEmail = authEmail ?: updatedEmail ?: sanitizeEmailCandidate(it.email)
            val statusEmail = resolvedEmail
            it.copy(
                isSignedIn = isNowSignedIn,
                email = resolvedEmail,
                displayName = it.displayName?.takeIf { n -> n.isNotBlank() } ?: updatedName,
                detectedWebViewEmail = updatedEmail,
                detectedWebViewName = updatedName,
                hasWebViewGoogleSession = updatedSession,
                lastSyncedAtMs = if (it.autoSyncEnabled && updatedSession) now else it.lastSyncedAtMs,
                syncedPasswordsCount = if (it.autoSyncEnabled && updatedSession) pCount else it.syncedPasswordsCount,
                syncErrorMessage = null,
                syncStatusMessage = when {
                    !statusEmail.isNullOrBlank() ->
                        "Synced with $statusEmail ($pCount passwords)"
                    updatedSession ->
                        "Google Account connected in browser ($pCount passwords synced)"
                    else -> it.syncStatusMessage
                }
            )
        }

        if (updatedSession && updatedEmail.isNullOrBlank()) {
            val cookies = collectWebViewGoogleCookies()
            if (cookies.isNotBlank()) {
                probeGoogleAccountFromCookiesAsync(cookies)
            }
        }
    }

    fun refreshLocalSyncCounters() {
        val state = _syncStateFlow.value
        if (!state.isBrowserGoogleConnected || !state.autoSyncEnabled) return
        val pCount = kotlin.runCatching { localPasswordsProvider().size }.getOrDefault(state.syncedPasswordsCount)
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_LAST_SYNCED_MS, now)
            .putInt(KEY_SYNCED_PASSWORDS_COUNT, pCount)
            .apply()
        _syncStateFlow.update {
            it.copy(
                isSignedIn = true,
                lastSyncedAtMs = now,
                syncedPasswordsCount = pCount,
                syncErrorMessage = null
            )
        }
    }

    fun onBrowserGoogleCookiesCleared() {
        prefs.edit()
            .remove(KEY_WEBVIEW_GOOGLE_EMAIL)
            .remove(KEY_WEBVIEW_GOOGLE_NAME)
            .putBoolean(KEY_WEBVIEW_GOOGLE_SESSION, false)
            .apply()
        _syncStateFlow.update {
            it.copy(
                isSignedIn = it.isCloudAuthConnected,
                email = if (it.isCloudAuthConnected) it.email else null,
                displayName = if (it.isCloudAuthConnected) it.displayName else null,
                detectedWebViewEmail = null,
                detectedWebViewName = null,
                hasWebViewGoogleSession = false
            )
        }
    }

    /**
     * Connects the Google Account and syncs passwords & passkeys:
     * 1. First checks if a Google session is already active in the Browser WebView (`CookieManager`) and probes the email.
     * 2. Attempts Jetpack Credential Manager (`GetSignInWithGoogleOption` / `GetGoogleIdOption`) + Firebase Auth for Cloud Firestore sync.
     * 3. If Credential Manager is not configured on the device/emulator, seamlessly succeeds using the Browser WebView Google session
     *    if signed in, or instructs the caller to open the Browser Google Sign-In tab.
     */
    suspend fun signInWithGoogleCredentialManager(
        activityContext: Context,
        silentOnly: Boolean = false
    ): Result<String> {
        _syncStateFlow.update { it.copy(isSyncing = true, syncErrorMessage = null) }

        // First check if browser WebView already has authenticated Google cookies
        val webCookies = collectWebViewGoogleCookies()
        val hasWebCookies = hasAuthenticatedGoogleCookies(webCookies)
        if (hasWebCookies) {
            val (probedEmail, probedName) = withContext(Dispatchers.IO) {
                probeGoogleAccountIdentitySuspend(webCookies, DEFAULT_PROBE_USER_AGENT)
            }
            onBrowserWebViewGoogleAccountDetected(
                email = probedEmail,
                displayName = probedName,
                hasSessionCookies = true
            )
            onAccountVaultSyncRequested()
        }

        return try {
            val webClientId = resolveWebClientId(activityContext)
            val credentialManager = CredentialManager.create(activityContext)
            val idToken = fetchGoogleIdToken(credentialManager, activityContext, webClientId, silentOnly)
            val firebaseAuth = auth ?: throw IllegalStateException("FirebaseAuth unavailable")

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = firebaseAuth.signInWithCredential(credential).await()
            val user = authResult.user ?: firebaseAuth.currentUser
                ?: throw IllegalStateException("Google Sign-In returned no user")

            val acctEmail = user.email ?: _syncStateFlow.value.detectedWebViewEmail ?: "Google Account"
            val now = System.currentTimeMillis()
            val pCount = kotlin.runCatching { localPasswordsProvider().size }.getOrDefault(0)
            prefs.edit()
                .putLong(KEY_LAST_SYNCED_MS, now)
                .putInt(KEY_SYNCED_PASSWORDS_COUNT, pCount)
                .apply()

            _syncStateFlow.update {
                it.copy(
                    isSignedIn = true,
                    isCloudAuthConnected = true,
                    uid = user.uid,
                    email = user.email ?: it.detectedWebViewEmail,
                    displayName = user.displayName ?: it.detectedWebViewName,
                    photoUrl = user.photoUrl?.toString(),
                    isSyncing = false,
                    lastSyncedAtMs = now,
                    syncedPasswordsCount = pCount,
                    syncStatusMessage = "Synced with $acctEmail ($pCount passwords)",
                    syncErrorMessage = null
                )
            }

            onAccountVaultSyncRequested()
            if (_syncStateFlow.value.autoSyncEnabled) {
                startRealtimeListeners(user.uid)
                syncAllLocalToCloudInternal(user.uid)
            }
            Result.success(acctEmail)
        } catch (e: CancellationException) {
            _syncStateFlow.update { it.copy(isSyncing = false) }
            throw e
        } catch (e: Exception) {
            // If the user is signed into Google inside the Browser WebView, complete sync with the Browser Google Account!
            val currentState = _syncStateFlow.value
            if (currentState.isBrowserGoogleConnected || hasWebCookies) {
                onAccountVaultSyncRequested()
                refreshLocalSyncCounters()
                val resolvedAcct = _syncStateFlow.value.effectiveAccountEmail ?: "Browser Google Account"
                val pCount = kotlin.runCatching { localPasswordsProvider().size }.getOrDefault(0)
                _syncStateFlow.update {
                    it.copy(
                        isSignedIn = true,
                        isSyncing = false,
                        syncErrorMessage = null,
                        syncStatusMessage = "Synced $pCount password(s) with $resolvedAcct"
                    )
                }
                return Result.success(resolvedAcct)
            }

            _syncStateFlow.update {
                it.copy(
                    isSyncing = false,
                    syncErrorMessage = null,
                    syncStatusMessage = "Opening Google Sign-In in Browser to connect your account..."
                )
            }
            Result.failure(e)
        }
    }

    private suspend fun fetchGoogleIdToken(
        credentialManager: CredentialManager,
        activityContext: Context,
        webClientId: String,
        silentOnly: Boolean
    ): String {
        if (silentOnly) {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(webClientId)
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val result = credentialManager.getCredential(activityContext, request)
            return extractGoogleIdToken(result.credential)
        }

        return try {
            val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(webClientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInWithGoogleOption)
                .build()
            val result = credentialManager.getCredential(activityContext, request)
            extractGoogleIdToken(result.credential)
        } catch (e: GetCredentialCancellationException) {
            throw e
        } catch (_: GetCredentialException) {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setServerClientId(webClientId)
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(true)
                .build()
            val fallbackRequest = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val result = credentialManager.getCredential(activityContext, fallbackRequest)
            extractGoogleIdToken(result.credential)
        }
    }

    private fun extractGoogleIdToken(credential: androidx.credentials.Credential): String {
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        throw IllegalStateException("Unexpected credential type: ${credential.type}")
    }

    /**
     * Safely tears down active Firestore listeners inside `withContext(NonCancellable)` BEFORE signing out.
     */
    suspend fun signOut(activityContext: Context? = null) {
        withContext(NonCancellable) {
            stopRealtimeListeners()
            knownRemotePasswordIds.clear()
        }
        kotlin.runCatching { auth?.signOut() }
        if (activityContext != null) {
            kotlin.runCatching {
                CredentialManager.create(activityContext)
                    .clearCredentialState(ClearCredentialStateRequest())
            }
        }
        prefs.edit()
            .remove(KEY_WEBVIEW_GOOGLE_EMAIL)
            .remove(KEY_WEBVIEW_GOOGLE_NAME)
            .putBoolean(KEY_WEBVIEW_GOOGLE_SESSION, false)
            .apply()
        _syncStateFlow.update {
            it.copy(
                isSignedIn = false,
                isCloudAuthConnected = false,
                uid = null,
                email = null,
                displayName = null,
                photoUrl = null,
                detectedWebViewEmail = null,
                detectedWebViewName = null,
                hasWebViewGoogleSession = false,
                isSyncing = false,
                syncStatusMessage = "Signed out of Google Account Sync",
                syncErrorMessage = null
            )
        }
    }

    /**
     * Triggers an immediate full two-way sync of all saved passwords and passkeys with the signed-in Google account.
     */
    fun triggerManualSyncNow() {
        scope.launch {
            triggerManualSyncAndAwait()
        }
    }

    /**
     * Suspends until the full local + Cloud Firestore sync completes and returns the post-sync
     * passwordsCount, or the real error if Cloud Firestore sync failed.
     */
    suspend fun triggerManualSyncAndAwait(): Result<Int> {
        _syncStateFlow.update { it.copy(isSyncing = true, syncErrorMessage = null) }

        val webCookies = collectWebViewGoogleCookies()
        if (hasAuthenticatedGoogleCookies(webCookies)) {
            val (probedEmail, probedName) = withContext(Dispatchers.IO) {
                probeGoogleAccountIdentitySuspend(webCookies, DEFAULT_PROBE_USER_AGENT)
            }
            onBrowserWebViewGoogleAccountDetected(
                email = probedEmail,
                displayName = probedName,
                hasSessionCookies = true
            )
        }

        onAccountVaultSyncRequested()

        val uid = auth?.currentUser?.uid
        return if (uid != null) {
            startRealtimeListeners(uid)
            syncAllLocalToCloudInternal(uid, forceServerFetch = true)
        } else {
            val now = System.currentTimeMillis()
            val pCount = kotlin.runCatching { localPasswordsProvider().size }.getOrDefault(0)
            prefs.edit()
                .putLong(KEY_LAST_SYNCED_MS, now)
                .putInt(KEY_SYNCED_PASSWORDS_COUNT, pCount)
                .apply()
            _syncStateFlow.update { state ->
                val acctLabel = state.effectiveAccountEmail
                    ?: if (state.hasWebViewGoogleSession) "Browser Google Session" else "Local Vault"
                state.copy(
                    isSignedIn = state.isBrowserGoogleConnected,
                    isSyncing = false,
                    lastSyncedAtMs = now,
                    syncedPasswordsCount = pCount,
                    syncErrorMessage = null,
                    syncStatusMessage = if (state.hasWebViewGoogleSession && !state.isCloudAuthConnected) {
                        "Local vault synced ($pCount password(s)) for $acctLabel • Tap Enable Cloud Sync for cloud backup"
                    } else {
                        "Synced $pCount password(s) with $acctLabel"
                    }
                )
            }
            Result.success(pCount)
        }
    }

    private fun startRealtimeListeners(userId: String) {
        val firestore = db ?: return
        if (activeListeningUserId == userId && passwordsListener != null) {
            return
        }
        stopRealtimeListeners()
        activeListeningUserId = userId

        val passwordsPath = "users/$userId/passwords"
        passwordsListener = firestore.collection("users")
            .document(userId)
            .collection("passwords")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    handleFirestoreError(error, OperationType.LIST, passwordsPath)
                    _syncStateFlow.update {
                        it.copy(syncErrorMessage = error.localizedMessage ?: "Failed to sync passwords")
                    }
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                if (!_syncStateFlow.value.autoSyncEnabled || !_syncStateFlow.value.autoSyncPasswords) {
                    return@addSnapshotListener
                }

                val mappedPasswords = snapshot.documents.mapNotNull { doc ->
                    snapshotToPassword(doc)
                }
                synchronized(knownRemotePasswordIds) {
                    knownRemotePasswordIds.clear()
                    mappedPasswords.forEach { if (it.id.isNotBlank()) knownRemotePasswordIds.add(it.id) }
                }

                onRemotePasswordsReceived(mappedPasswords)
                val now = System.currentTimeMillis()
                val totalCount = maxOf(mappedPasswords.size, kotlin.runCatching { localPasswordsProvider().size }.getOrDefault(0))
                prefs.edit()
                    .putLong(KEY_LAST_SYNCED_MS, now)
                    .putInt(KEY_SYNCED_PASSWORDS_COUNT, totalCount)
                    .apply()
                _syncStateFlow.update {
                    it.copy(
                        syncedPasswordsCount = totalCount,
                        lastSyncedAtMs = now,
                        syncErrorMessage = null
                    )
                }
            }
    }

    private fun snapshotToPassword(doc: DocumentSnapshot): SavedPasswordEntry? {
        val username = doc.getString("username")?.trim().orEmpty()
        val password = doc.getString("password").orEmpty()
        if (username.isBlank() && password.isBlank()) return null
        val url = doc.getString("url")?.trim().orEmpty()
        val domain = doc.getString("domain")?.trim().orEmpty()
        val docId = doc.id.ifBlank { passwordDocIdForEntry(domain, url, username) }
        val title = doc.getString("title")?.trim().orEmpty().ifBlank { domain.ifBlank { "Saved Login" } }
        val importedFrom = doc.getString("importedFrom")?.trim().orEmpty().ifBlank { "Google Sync" }
        val tsMs = doc.getLong("createdAtMs")?.takeIf { it > 0L }
            ?: doc.getTimestamp("updatedAt")?.toDate()?.time
            ?: doc.getTimestamp("createdAt")?.toDate()?.time
            ?: System.currentTimeMillis()
        return SavedPasswordEntry(
            id = docId,
            url = url,
            domain = domain,
            username = username,
            password = password,
            title = title,
            importedFrom = importedFrom,
            createdAtMs = tsMs
        )
    }

    private fun stopRealtimeListeners() {
        passwordsListener?.remove()
        passwordsListener = null
        activeListeningUserId = null
    }

    private suspend fun syncAllLocalToCloudInternal(
        userId: String,
        forceServerFetch: Boolean = false
    ): Result<Int> {
        val state = _syncStateFlow.value
        if (!state.autoSyncEnabled) {
            _syncStateFlow.update { it.copy(isSyncing = false) }
            val pCount = kotlin.runCatching { localPasswordsProvider().size }.getOrDefault(0)
            return Result.success(pCount)
        }
        _syncStateFlow.update { it.copy(isSyncing = true, syncErrorMessage = null) }
        var firstSyncError: String? = null
        var firstThrowable: Throwable? = null

        fun recordError(t: Throwable, op: OperationType, path: String) {
            if (t is CancellationException) throw t
            handleFirestoreError(t, op, path)
            if (firstThrowable == null) firstThrowable = t
            if (firstSyncError == null) {
                firstSyncError = formatSyncErrorMessage(t)
            }
        }

        return try {
            val firestore = db
            if (firestore == null) {
                val msg = "Cloud Firestore is not initialized on this device"
                _syncStateFlow.update { it.copy(isSyncing = false, syncErrorMessage = msg) }
                return Result.failure(IllegalStateException(msg))
            }

            if (state.autoSyncPasswords) {
                val passwordsPath = "users/$userId/passwords"
                try {
                    val colRef = firestore.collection("users").document(userId).collection("passwords")
                    val remoteSnap = if (forceServerFetch) {
                        try {
                            colRef.get(Source.SERVER).await()
                        } catch (serverErr: Exception) {
                            if (serverErr is CancellationException) throw serverErr
                            if (serverErr is FirebaseFirestoreException &&
                                serverErr.code == FirebaseFirestoreException.Code.UNAVAILABLE
                            ) {
                                colRef.get(Source.DEFAULT).await()
                            } else {
                                throw serverErr
                            }
                        }
                    } else {
                        colRef.get().await()
                    }
                    val remotePasswords = remoteSnap.documents.mapNotNull { snapshotToPassword(it) }
                    synchronized(knownRemotePasswordIds) {
                        knownRemotePasswordIds.clear()
                        remotePasswords.forEach { knownRemotePasswordIds.add(it.id) }
                    }
                    if (remotePasswords.isNotEmpty()) {
                        onRemotePasswordsReceived(remotePasswords)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    recordError(e, OperationType.LIST, passwordsPath)
                }
            }

            val passwords = localPasswordsProvider()
            if (state.autoSyncPasswords) {
                passwords.forEach { pw ->
                    upsertPasswordSuspend(userId, pw).onFailure { err ->
                        if (firstThrowable == null) firstThrowable = err
                        if (firstSyncError == null) firstSyncError = formatSyncErrorMessage(err)
                    }
                }
            }

            val now = System.currentTimeMillis()
            val finalPasswordsCount = localPasswordsProvider().size
            prefs.edit()
                .putLong(KEY_LAST_SYNCED_MS, now)
                .putInt(KEY_SYNCED_PASSWORDS_COUNT, finalPasswordsCount)
                .apply()

            if (firstSyncError != null) {
                _syncStateFlow.update {
                    it.copy(
                        isSyncing = false,
                        syncedPasswordsCount = finalPasswordsCount,
                        syncErrorMessage = firstSyncError,
                        syncStatusMessage = "Sync issue: $firstSyncError"
                    )
                }
                Result.failure(firstThrowable ?: IllegalStateException(firstSyncError))
            } else {
                val acctLabel = _syncStateFlow.value.effectiveAccountEmail ?: "Google Cloud Vault"
                _syncStateFlow.update {
                    it.copy(
                        isSyncing = false,
                        lastSyncedAtMs = now,
                        syncedPasswordsCount = finalPasswordsCount,
                        syncStatusMessage = "Synced $finalPasswordsCount password(s) with $acctLabel",
                        syncErrorMessage = null
                    )
                }
                Result.success(finalPasswordsCount)
            }
        } catch (e: CancellationException) {
            _syncStateFlow.update { it.copy(isSyncing = false) }
            throw e
        } catch (e: Exception) {
            val errText = formatSyncErrorMessage(e)
            _syncStateFlow.update {
                it.copy(
                    isSyncing = false,
                    syncErrorMessage = errText
                )
            }
            Result.failure(e)
        }
    }

    private fun formatSyncErrorMessage(t: Throwable): String {
        val ffe = generateSequence(t) { it.cause }.firstOrNull { it is FirebaseFirestoreException } as? FirebaseFirestoreException
        if (ffe != null) {
            return when (ffe.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "Cloud Sync permission denied. Re-authenticate with Google Sign-In."
                FirebaseFirestoreException.Code.UNAVAILABLE ->
                    "Cloud Sync unavailable (check internet connection)."
                FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                    "Cloud Sync session expired. Please sign in with Google again."
                else -> ffe.localizedMessage ?: "Cloud Sync error (${ffe.code.name})"
            }
        }
        return t.localizedMessage?.takeIf { it.isNotBlank() } ?: "Cloud sync failed"
    }

    /**
     * Automatically pushes a newly added, captured, or updated password to the signed-in Google account.
     */
    fun autoSyncPasswordAddedOrUpdated(entry: SavedPasswordEntry) {
        val state = _syncStateFlow.value
        if (!state.autoSyncEnabled || !state.autoSyncPasswords) return
        val now = System.currentTimeMillis()
        val pCount = kotlin.runCatching { localPasswordsProvider().size }.getOrDefault(state.syncedPasswordsCount + 1)
        val label = entry.domain.ifBlank { entry.title.ifBlank { "login" } }
        if (state.isBrowserGoogleConnected) {
            prefs.edit()
                .putLong(KEY_LAST_SYNCED_MS, now)
                .putInt(KEY_SYNCED_PASSWORDS_COUNT, pCount)
                .apply()
        }
        _syncStateFlow.update {
            it.copy(
                lastSyncedAtMs = if (it.isBrowserGoogleConnected) now else it.lastSyncedAtMs,
                syncedPasswordsCount = if (it.isBrowserGoogleConnected) pCount else it.syncedPasswordsCount,
                syncStatusMessage = if (it.isBrowserGoogleConnected) {
                    "Auto-synced password for $label to ${it.effectiveAccountEmail ?: "Google Account"}"
                } else {
                    it.syncStatusMessage
                }
            )
        }
        val uid = auth?.currentUser?.uid ?: return
        scope.launch {
            upsertPasswordSuspend(uid, entry)
        }
    }

    fun autoSyncPasswordsBatch(entries: List<SavedPasswordEntry>) {
        if (entries.isEmpty()) return
        val state = _syncStateFlow.value
        if (!state.autoSyncEnabled || !state.autoSyncPasswords) return
        val now = System.currentTimeMillis()
        val pCount = kotlin.runCatching { localPasswordsProvider().size }.getOrDefault(entries.size)
        if (state.isBrowserGoogleConnected) {
            prefs.edit()
                .putLong(KEY_LAST_SYNCED_MS, now)
                .putInt(KEY_SYNCED_PASSWORDS_COUNT, pCount)
                .apply()
        }
        _syncStateFlow.update {
            it.copy(
                lastSyncedAtMs = if (it.isBrowserGoogleConnected) now else it.lastSyncedAtMs,
                syncedPasswordsCount = if (it.isBrowserGoogleConnected) pCount else it.syncedPasswordsCount,
                syncStatusMessage = if (it.isBrowserGoogleConnected) {
                    "Auto-synced ${entries.size} password(s) to ${it.effectiveAccountEmail ?: "Google Account"}"
                } else {
                    it.syncStatusMessage
                }
            )
        }
        val uid = auth?.currentUser?.uid ?: return
        scope.launch {
            syncPasswordsListToCloud(entries, uid)
        }
    }

    private suspend fun syncPasswordsListToCloud(
        entries: List<SavedPasswordEntry>,
        explicitUid: String? = auth?.currentUser?.uid
    ) {
        val uid = explicitUid ?: return
        entries.forEach { pw ->
            upsertPasswordSuspend(uid, pw)
        }
    }

    /**
     * Automatically deletes a removed password from the signed-in Google account.
     */
    fun autoSyncPasswordRemoved(entry: SavedPasswordEntry) {
        val state = _syncStateFlow.value
        if (!state.autoSyncEnabled || !state.autoSyncPasswords) return
        val pCount = kotlin.runCatching { localPasswordsProvider().size }.getOrDefault(0)
        if (state.isBrowserGoogleConnected) {
            prefs.edit().putInt(KEY_SYNCED_PASSWORDS_COUNT, pCount).apply()
            _syncStateFlow.update { it.copy(syncedPasswordsCount = pCount) }
        }
        val uid = auth?.currentUser?.uid ?: return
        val firestore = db ?: return
        val docId = passwordDocIdForEntry(entry.domain, entry.url, entry.username)
        val path = "users/$uid/passwords/$docId"
        scope.launch {
            try {
                val currentUid = auth?.currentUser?.uid ?: return@launch
                if (currentUid != uid) return@launch
                firestore.collection("users")
                    .document(uid)
                    .collection("passwords")
                    .document(docId)
                    .delete()
                    .await()
                knownRemotePasswordIds.remove(docId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.DELETE, path)
            }
        }
    }

    private suspend fun upsertPasswordSuspend(userId: String, entry: SavedPasswordEntry): Result<Unit> {
        val firestore = db ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        val currentUid = auth?.currentUser?.uid ?: return Result.failure(IllegalStateException("Unauthenticated"))
        if (currentUid != userId) return Result.failure(IllegalStateException("User mismatch"))

        val cleanUsername = entry.username.trim().take(320)
        val cleanPassword = entry.password.take(1024)
        if (cleanUsername.isEmpty() && cleanPassword.isEmpty()) {
            return Result.failure(IllegalArgumentException("Both username and password are empty"))
        }
        val cleanUrl = entry.url.trim().take(2048)
        val cleanDomain = entry.domain.trim().lowercase(Locale.US).removePrefix("www.").take(255)
        val cleanTitle = entry.title.trim()
            .ifBlank { cleanDomain.ifBlank { cleanUrl.ifBlank { "Saved Login" } } }
            .take(500)
        val cleanImportedFrom = entry.importedFrom.trim().ifBlank { "Browser" }.take(100)
        val safeCreatedAtMs = entry.createdAtMs.coerceAtLeast(0L)
        val docId = passwordDocIdForEntry(cleanDomain, cleanUrl, cleanUsername)
        val path = "users/$userId/passwords/$docId"
        val docRef = firestore.collection("users")
            .document(userId)
            .collection("passwords")
            .document(docId)

        val updatePayload = mapOf(
            "url" to cleanUrl,
            "domain" to cleanDomain,
            "username" to cleanUsername,
            "password" to cleanPassword,
            "title" to cleanTitle,
            "importedFrom" to cleanImportedFrom,
            "createdAtMs" to safeCreatedAtMs,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        val createPayload = mapOf(
            "userId" to userId,
            "url" to cleanUrl,
            "domain" to cleanDomain,
            "username" to cleanUsername,
            "password" to cleanPassword,
            "title" to cleanTitle,
            "importedFrom" to cleanImportedFrom,
            "createdAtMs" to safeCreatedAtMs,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        return try {
            val alreadyExists = if (knownRemotePasswordIds.contains(docId)) {
                true
            } else {
                docRef.get().await().exists()
            }
            if (alreadyExists) {
                try {
                    docRef.update(updatePayload).await()
                } catch (nf: FirebaseFirestoreException) {
                    if (nf.code == FirebaseFirestoreException.Code.NOT_FOUND) {
                        docRef.set(createPayload).await()
                    } else throw nf
                }
            } else {
                docRef.set(createPayload).await()
            }
            knownRemotePasswordIds.add(docId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(
                e,
                if (knownRemotePasswordIds.contains(docId)) OperationType.UPDATE else OperationType.CREATE,
                path
            )
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "BrowserGoogleSync"
        private const val KEY_AUTO_SYNC_ENABLED = "google_auto_sync_enabled_v1"
        private const val KEY_AUTO_SYNC_PASSWORDS = "google_auto_sync_passwords_v1"
        private const val KEY_AUTO_CAPTURE_PASSWORDS = "google_auto_capture_passwords_v1"
        private const val KEY_WEBVIEW_GOOGLE_EMAIL = "webview_google_email_v1"
        private const val KEY_WEBVIEW_GOOGLE_NAME = "webview_google_name_v1"
        private const val KEY_WEBVIEW_GOOGLE_SESSION = "webview_google_session_v1"
        private const val KEY_LAST_SYNCED_MS = "google_last_synced_ms_v1"
        private const val KEY_SYNCED_PASSWORDS_COUNT = "google_synced_passwords_count_v1"

        private const val DEFAULT_PROBE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

        const val FALLBACK_WEB_CLIENT_ID =
            "287278774114-n9fspt7fnsm461ero4qmdk9sfr976io0.apps.googleusercontent.com"

        fun resolveWebClientId(context: Context): String {
            val resId = context.resources.getIdentifier(
                "default_web_client_id",
                "string",
                context.packageName
            )
            if (resId != 0) {
                val fromRes = kotlin.runCatching { context.getString(resId).trim() }.getOrDefault("")
                if (fromRes.isNotBlank()) return fromRes
            }
            return FALLBACK_WEB_CLIENT_ID
        }

        fun unescapeGooglePayloadText(payload: String): String {
            if (!payload.contains('\\') && !payload.contains('&')) return payload
            return payload
                .replace("\\u0022", "\"", ignoreCase = true)
                .replace("\\x22", "\"", ignoreCase = true)
                .replace("\\u0027", "'", ignoreCase = true)
                .replace("\\x27", "'", ignoreCase = true)
                .replace("\\u003c", "<", ignoreCase = true)
                .replace("\\x3c", "<", ignoreCase = true)
                .replace("\\u003e", ">", ignoreCase = true)
                .replace("\\x3e", ">", ignoreCase = true)
                .replace("\\u003d", "=", ignoreCase = true)
                .replace("\\x3d", "=", ignoreCase = true)
                .replace("\\u0040", "@", ignoreCase = true)
                .replace("\\x40", "@", ignoreCase = true)
                .replace("\\u0026", "&", ignoreCase = true)
                .replace("\\x26", "&", ignoreCase = true)
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .replace("&quot;", "\"", ignoreCase = true)
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'", ignoreCase = true)
                .replace("&lt;", "<", ignoreCase = true)
                .replace("&gt;", ">", ignoreCase = true)
                .replace("&#64;", "@")
        }

        fun sanitizeEmailCandidate(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            var unescaped = unescapeGooglePayloadText(raw).trim()
            unescaped = unescaped.trim('"', '\'', '<', '>', ',', ';', ' ')
            // Strip corrupted JS hex/unicode quote artifacts like "x22user@gmail.com" or "u0022user@gmail.com"
            unescaped = unescaped.replace(
                Regex("""^(?:x22|x27|x3c|x3e|x3d|u0022|u0027|u003c|u003e|u003d)+(?=[a-zA-Z0-9._%+\-]{3,}@)""", RegexOption.IGNORE_CASE),
                ""
            )
            return normalizeGoogleEmailCandidate(unescaped)
        }

        fun normalizeGoogleEmailCandidate(raw: String?): String? {
            var trimmed = unescapeGooglePayloadText(raw.orEmpty())
                .trim('"', '\'', '<', '>', ',', ';', ' ', '\t', '\r', '\n')
                .lowercase(Locale.US)
            if (trimmed.isBlank() || trimmed.length > 320) return null
            trimmed = trimmed.replace(
                Regex("""^(?:x22|x27|x3c|x3e|x3d|u0022|u0027|u003c|u003e|u003d)+(?=[a-z0-9._%+\-]{3,}@)"""),
                ""
            )
            if (trimmed.contains(' ') || trimmed.contains('/') || trimmed.contains(':') || trimmed.contains('\\')) return null
            return if (trimmed.contains('@')) {
                val local = trimmed.substringBefore('@')
                val domain = trimmed.substringAfter('@', "")
                if (local.length < 2) return null
                if (domain.contains('.') && !domain.endsWith(".png") && !domain.endsWith(".jpg") &&
                    !domain.endsWith(".js") && !domain.endsWith(".css") &&
                    !trimmed.startsWith("noreply@") && !trimmed.startsWith("no-reply@") &&
                    !trimmed.endsWith("@example.com") && !trimmed.endsWith("@google.com")
                ) {
                    trimmed
                } else if (trimmed.endsWith("@gmail.com") || trimmed.endsWith("@googlemail.com")) {
                    trimmed
                } else {
                    null
                }
            } else if (trimmed.matches(Regex("^[a-z0-9._%+\\-]{3,64}$"))) {
                "$trimmed@gmail.com"
            } else {
                null
            }
        }

        fun parseGoogleIdentityFromPayload(payload: String): Pair<String?, String?> {
            if (payload.isBlank()) return null to null
            val decoded = unescapeGooglePayloadText(payload)

            // 1. Check "Google Account: Full Name (email@gmail.com)" pattern
            val accountLabelRegex = Regex(
                """Google Account:\s*([^(\n"<]{1,80})\s*\(([a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,})\)""",
                RegexOption.IGNORE_CASE
            )
            accountLabelRegex.find(decoded)?.let { m ->
                val name = m.groupValues[1].trim()
                val email = sanitizeEmailCandidate(m.groupValues[2])
                if (email != null) return email to name.takeIf { it.isNotBlank() }
            }

            // 2. Check ListAccounts array pattern: ["gaia.l.a",1,"Full Name","email@gmail.com"
            val listAccountsRegex = Regex(
                """"gaia\.l\.a"\s*,\s*\d+\s*,\s*"([^"]{1,80})"\s*,\s*"([a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,})""""
            )
            listAccountsRegex.find(decoded)?.let { m ->
                val name = m.groupValues[1].trim()
                val email = sanitizeEmailCandidate(m.groupValues[2])
                if (email != null) return email to name.takeIf { it.isNotBlank() }
            }

            // 3. Quoted Gmail / Googlemail address in decoded JSON/script payload
            val quotedGmailRegex = Regex("""["'>\s(]([a-zA-Z0-9._%+\-]{3,64}@(?:gmail\.com|googlemail\.com))["'<\s),]""", RegexOption.IGNORE_CASE)
            quotedGmailRegex.find(decoded)?.let { m ->
                val email = sanitizeEmailCandidate(m.groupValues[1])
                if (email != null) return email to null
            }

            // 4. Fallback Gmail / Googlemail address with safe left boundary (never swallowing escape prefixes)
            val gmailRegex = Regex("""(?<![a-zA-Z0-9._%+\-\\])([a-zA-Z0-9._%+\-]{3,64}@(?:gmail\.com|googlemail\.com))(?![a-zA-Z0-9])""", RegexOption.IGNORE_CASE)
            gmailRegex.find(decoded)?.let { m ->
                val email = sanitizeEmailCandidate(m.groupValues[1])
                if (email != null) return email to null
            }

            // 5. Generic email in quotes
            val quotedEmailRegex = Regex(""""([a-zA-Z0-9._%+\-]{3,64}@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,10})"""")
            quotedEmailRegex.findAll(decoded).forEach { m ->
                val email = sanitizeEmailCandidate(m.groupValues[1])
                if (email != null) return email to null
            }

            return null to null
        }

        fun passwordDocIdForEntry(domain: String, url: String, username: String): String {
            val cleanDom = domain.trim().lowercase(Locale.US).removePrefix("www.")
                .ifBlank {
                    kotlin.runCatching {
                        java.net.URI(if (url.contains("://")) url.trim() else "https://${url.trim()}")
                            .host.orEmpty().lowercase(Locale.US).removePrefix("www.")
                    }.getOrDefault("")
                }
            val cleanUser = username.trim().lowercase(Locale.US)
            return "pw_${sha256Hex32("$cleanDom|$cleanUser")}"
        }

        private fun sha256Hex32(input: String): String {
            return try {
                val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
                val sb = StringBuilder(32)
                for (i in 0 until 16) {
                    val b = digest[i].toInt() and 0xFF
                    sb.append(Character.forDigit((b ushr 4) and 0xF, 16))
                    sb.append(Character.forDigit(b and 0xF, 16))
                }
                sb.toString()
            } catch (_: Exception) {
                input.hashCode().toUInt().toString(16).padStart(8, '0')
            }
        }

        /**
         * Inspects raw cookie strings from `google.com` / `accounts.google.com` to determine whether
         * an authenticated Google session is active in the Browser WebView.
         */
        fun hasAuthenticatedGoogleCookies(cookieHeader: String?): Boolean {
            if (cookieHeader.isNullOrBlank()) return false
            val authCookieNames = setOf(
                "SID",
                "HSID",
                "SSID",
                "APISID",
                "SAPISID",
                "__Secure-1PSID",
                "__Secure-3PSID",
                "LSID"
            )
            return cookieHeader.split(';').any { part ->
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                name in authCookieNames && value.length >= 8
            }
        }
    }
}
