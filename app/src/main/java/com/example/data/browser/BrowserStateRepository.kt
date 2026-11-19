package com.example.data.browser

import android.content.Context
import com.example.data.security.MalwareProtectionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val url: String = BrowserStateRepository.DEFAULT_HOME_URL,
    val title: String = "Google",
    val isIncognito: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val progress: Int = 100,
    val isLoading: Boolean = false,
    val desktopMode: Boolean = false
)

data class BrowserHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val visitedAtMs: Long = System.currentTimeMillis()
)

data class BrowserBookmark(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

enum class OmniboxSuggestionSource(val label: String) {
    BOOKMARK("BOOKMARK"),
    HISTORY("HISTORY")
}

data class OmniboxSuggestion(
    val title: String,
    val url: String,
    val source: OmniboxSuggestionSource,
    val timestampMs: Long = 0L,
    val score: Int = 0
)

enum class GrabbedMediaKind(val label: String) {
    VIDEO("VIDEO"),
    AUDIO("AUDIO"),
    FILE("FILE")
}

data class GrabbedMediaCandidate(
    val url: String,
    val title: String,
    val kind: GrabbedMediaKind,
    val containerExt: String,
    val sourceLabel: String
)

data class WebConsoleLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val level: String, // "LOG", "INFO", "WARN", "ERROR"
    val message: String,
    val source: String = "",
    val lineNumber: Int = 0,
    val timestampMs: Long = System.currentTimeMillis()
)

data class WebNetworkLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val method: String,
    val resourceType: String, // "Document", "Script", "Stylesheet", "Image", "Media", "XHR/Fetch", "Font", "Other"
    val statusLabel: String, // "Allowed", "AdBlocked", "Ext DNR", "DNS Tunnel"
    val domain: String,
    val timestampMs: Long = System.currentTimeMillis()
)

data class SavedPasswordEntry(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val domain: String,
    val username: String,
    val password: String,
    val title: String = "",
    val importedFrom: String = "CSV",
    val createdAtMs: Long = System.currentTimeMillis()
)

enum class DownloadedImportKind {
    PASSWORDS,
    BOOKMARKS
}

data class DownloadedSyncImportResult(
    val kind: DownloadedImportKind,
    val added: Int,
    val skipped: Int
)

/**
 * Manages multi-tab browser state, Google default home URL, incognito tab isolation,
 * persisted browsing history, persisted bookmarks, ad-blocker counters, and page media grabber hits.
 */
class BrowserStateRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("dragon_browser_state", Context.MODE_PRIVATE)
    private val vaultsDir = java.io.File(appContext.filesDir, "google_account_vaults").apply { mkdirs() }

    val extensionEngine = BrowserExtensionEngine(context)

    private val _tabsFlow = MutableStateFlow(
        listOf(BrowserTab(url = DEFAULT_HOME_URL, title = "Google"))
    )
    val tabsFlow: StateFlow<List<BrowserTab>> = _tabsFlow.asStateFlow()

    private val _activeTabIdFlow = MutableStateFlow(_tabsFlow.value.first().id)
    val activeTabIdFlow: StateFlow<String> = _activeTabIdFlow.asStateFlow()

    private val _historyFlow = MutableStateFlow<List<BrowserHistoryEntry>>(emptyList())
    val historyFlow: StateFlow<List<BrowserHistoryEntry>> = _historyFlow.asStateFlow()

    private val _bookmarksFlow = MutableStateFlow<List<BrowserBookmark>>(emptyList())
    val bookmarksFlow: StateFlow<List<BrowserBookmark>> = _bookmarksFlow.asStateFlow()

    private val _grabbedMediaByTabFlow = MutableStateFlow<Map<String, List<GrabbedMediaCandidate>>>(emptyMap())
    val grabbedMediaByTabFlow: StateFlow<Map<String, List<GrabbedMediaCandidate>>> = _grabbedMediaByTabFlow.asStateFlow()

    private val _adsBlockedCountFlow = MutableStateFlow(0)
    val adsBlockedCountFlow: StateFlow<Int> = _adsBlockedCountFlow.asStateFlow()

    private val _consoleLogsByTabFlow = MutableStateFlow<Map<String, List<WebConsoleLogEntry>>>(emptyMap())
    val consoleLogsByTabFlow: StateFlow<Map<String, List<WebConsoleLogEntry>>> = _consoleLogsByTabFlow.asStateFlow()

    private val _networkLogsByTabFlow = MutableStateFlow<Map<String, List<WebNetworkLogEntry>>>(emptyMap())
    val networkLogsByTabFlow: StateFlow<Map<String, List<WebNetworkLogEntry>>> = _networkLogsByTabFlow.asStateFlow()

    private val _mediaGrabberEnabledFlow = MutableStateFlow(prefs.getBoolean("media_grabber_enabled", true))
    val mediaGrabberEnabledFlow: StateFlow<Boolean> = _mediaGrabberEnabledFlow.asStateFlow()

    private val _pageInspectorEnabledFlow = MutableStateFlow(prefs.getBoolean("page_inspector_enabled", false))
    val pageInspectorEnabledFlow: StateFlow<Boolean> = _pageInspectorEnabledFlow.asStateFlow()

    private val _savedPasswordsFlow = MutableStateFlow<List<SavedPasswordEntry>>(emptyList())
    val savedPasswordsFlow: StateFlow<List<SavedPasswordEntry>> = _savedPasswordsFlow.asStateFlow()

    private val _proxyEnabledFlow = MutableStateFlow(false)
    val proxyEnabledFlow: StateFlow<Boolean> = _proxyEnabledFlow.asStateFlow()

    private val _proxyProfilesFlow = MutableStateFlow<List<BrowserProxyProfile>>(emptyList())
    val proxyProfilesFlow: StateFlow<List<BrowserProxyProfile>> = _proxyProfilesFlow.asStateFlow()

    private val _activeProxyIdFlow = MutableStateFlow<String?>(null)
    val activeProxyIdFlow: StateFlow<String?> = _activeProxyIdFlow.asStateFlow()

    val googleSyncManager: BrowserGoogleSyncManager
    val passkeyManager: BrowserPasskeyManager

    val googleSyncStateFlow: StateFlow<GoogleSyncState>
        get() = googleSyncManager.syncStateFlow

    init {
        MalwareProtectionEngine.ensureInitialized(appContext)
        passkeyManager = BrowserPasskeyManager(appContext) { entry ->
            addOrUpdateSavedPassword(
                url = entry.url,
                username = entry.username,
                password = entry.password,
                title = entry.title,
                source = entry.importedFrom
            )
        }
        loadHistory()
        loadBookmarks()
        loadSavedPasswords()
        loadProxyState()
        googleSyncManager = BrowserGoogleSyncManager(
            context = appContext,
            onRemotePasswordsReceived = { remotePasswords ->
                mergeRemotePasswords(remotePasswords)
            },
            localPasswordsProvider = { _savedPasswordsFlow.value },
            onAccountVaultSyncRequested = {
                syncAccountVaultNow()
            }
        )
        syncAccountVaultNow()
    }

    fun getActiveTab(): BrowserTab {
        val activeId = _activeTabIdFlow.value
        return _tabsFlow.value.find { it.id == activeId } ?: _tabsFlow.value.first()
    }

    fun openNewTab(
        initialUrl: String = DEFAULT_HOME_URL,
        isIncognito: Boolean = false
    ): BrowserTab {
        val tab = BrowserTab(
            id = UUID.randomUUID().toString(),
            url = initialUrl,
            title = if (isIncognito) "Incognito • Google" else "Google",
            isIncognito = isIncognito
        )
        _tabsFlow.value = _tabsFlow.value + tab
        _activeTabIdFlow.value = tab.id
        return tab
    }

    fun selectTab(tabId: String) {
        if (_tabsFlow.value.any { it.id == tabId }) {
            _activeTabIdFlow.value = tabId
        }
    }

    /**
     * Closes a tab and returns true if any incognito tabs remain open,
     * allowing the UI to clear private WebView storage when the last incognito tab closes.
     */
    fun closeTab(tabId: String): Boolean {
        val current = _tabsFlow.value
        val closingTab = current.find { it.id == tabId }
        val remaining = current.filter { it.id != tabId }

        _grabbedMediaByTabFlow.value = _grabbedMediaByTabFlow.value - tabId
        _consoleLogsByTabFlow.update { it - tabId }
        _networkLogsByTabFlow.update { it - tabId }

        if (remaining.isEmpty()) {
            val fresh = BrowserTab(url = DEFAULT_HOME_URL, title = "Google", isIncognito = false)
            _tabsFlow.value = listOf(fresh)
            _activeTabIdFlow.value = fresh.id
        } else {
            _tabsFlow.value = remaining
            if (_activeTabIdFlow.value == tabId) {
                _activeTabIdFlow.value = remaining.last().id
            }
        }
        val wasLastIncognito = closingTab?.isIncognito == true && _tabsFlow.value.none { it.isIncognito }
        return wasLastIncognito
    }

    fun closeAllTabs() {
        _grabbedMediaByTabFlow.value = emptyMap()
        _consoleLogsByTabFlow.value = emptyMap()
        _networkLogsByTabFlow.value = emptyMap()
        val fresh = BrowserTab(url = DEFAULT_HOME_URL, title = "Google", isIncognito = false)
        _tabsFlow.value = listOf(fresh)
        _activeTabIdFlow.value = fresh.id
    }

    fun updateTabState(tabId: String, updater: (BrowserTab) -> BrowserTab) {
        _tabsFlow.value = _tabsFlow.value.map { tab ->
            if (tab.id == tabId) updater(tab) else tab
        }
    }

    fun toggleCurrentTabIncognito(): BrowserTab {
        val cur = getActiveTab()
        return if (cur.isIncognito) {
            // Switch to an existing normal tab or open a fresh normal tab
            val existingNormal = _tabsFlow.value.firstOrNull { !it.isIncognito }
            if (existingNormal != null) {
                _activeTabIdFlow.value = existingNormal.id
                existingNormal
            } else {
                openNewTab(initialUrl = DEFAULT_HOME_URL, isIncognito = false)
            }
        } else {
            openNewTab(initialUrl = DEFAULT_HOME_URL, isIncognito = true)
        }
    }

    fun recordVisit(url: String, title: String?, isIncognito: Boolean) {
        if (isIncognito) return
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return
        }
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() && it != "about:blank" }
            ?: runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()
            ?: url

        val current = _historyFlow.value
        val latest = current.firstOrNull()
        if (latest != null && latest.url == url && (System.currentTimeMillis() - latest.visitedAtMs) < 5_000L) {
            return
        }

        val entry = BrowserHistoryEntry(
            title = cleanTitle,
            url = url,
            visitedAtMs = System.currentTimeMillis()
        )
        val deduped = current.filterNot { it.url == url && (entry.visitedAtMs - it.visitedAtMs) < 60_000L }
        val updated = (listOf(entry) + deduped).take(200)
        _historyFlow.value = updated
        persistHistory(updated)
    }

    fun deleteHistoryEntry(entryId: String) {
        val updated = _historyFlow.value.filterNot { it.id == entryId }
        _historyFlow.value = updated
        persistHistory(updated)
    }

    fun clearHistory() {
        _historyFlow.value = emptyList()
        prefs.edit().remove("browser_history_v1").apply()
    }

    fun isBookmarked(url: String): Boolean {
        val normalized = url.trim().trimEnd('/')
        return _bookmarksFlow.value.any { it.url.trim().trimEnd('/').equals(normalized, ignoreCase = true) }
    }

    fun toggleBookmark(url: String, title: String?): Boolean {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return false
        }
        val normalized = url.trim().trimEnd('/')
        val existing = _bookmarksFlow.value.find {
            it.url.trim().trimEnd('/').equals(normalized, ignoreCase = true)
        }
        return if (existing != null) {
            val updated = _bookmarksFlow.value.filterNot { it.id == existing.id }
            _bookmarksFlow.value = updated
            persistBookmarks(updated)
            false
        } else {
            val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
                ?: runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull()
                ?: url
            val addedBookmark = BrowserBookmark(title = cleanTitle, url = url.trim())
            val updated = listOf(addedBookmark) + _bookmarksFlow.value
            _bookmarksFlow.value = updated
            persistBookmarks(updated)
            true
        }
    }

    fun addOrUpdateBookmark(url: String, title: String?): BrowserBookmark? {
        val raw = url.trim()
        if (raw.isBlank()) return null
        val resolvedUrl = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            raw
        } else {
            "https://$raw"
        }
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
            ?: runCatching { java.net.URI(resolvedUrl).host?.removePrefix("www.") }.getOrNull()
            ?: resolvedUrl
        val normalized = resolvedUrl.trimEnd('/').lowercase(Locale.US)
        val current = _bookmarksFlow.value
        val existing = current.find { it.url.trim().trimEnd('/').lowercase(Locale.US) == normalized }
        val saved = if (existing != null) {
            existing.copy(title = cleanTitle, url = resolvedUrl, createdAtMs = System.currentTimeMillis())
        } else {
            BrowserBookmark(title = cleanTitle, url = resolvedUrl)
        }
        val updated = listOf(saved) + current.filterNot {
            it.url.trim().trimEnd('/').lowercase(Locale.US) == normalized
        }
        _bookmarksFlow.value = updated
        persistBookmarks(updated)
        return saved
    }

    fun removeBookmark(bookmarkId: String) {
        val updated = _bookmarksFlow.value.filterNot { it.id == bookmarkId }
        _bookmarksFlow.value = updated
        persistBookmarks(updated)
    }

    fun setMediaGrabberEnabled(enabled: Boolean) {
        _mediaGrabberEnabledFlow.value = enabled
        prefs.edit().putBoolean("media_grabber_enabled", enabled).apply()
        if (!enabled) {
            _grabbedMediaByTabFlow.value = emptyMap()
        }
    }

    fun setPageInspectorEnabled(enabled: Boolean) {
        _pageInspectorEnabledFlow.value = enabled
        prefs.edit().putBoolean("page_inspector_enabled", enabled).apply()
        if (!enabled) {
            _consoleLogsByTabFlow.value = emptyMap()
            _networkLogsByTabFlow.value = emptyMap()
        }
    }

    fun recordGrabbedMedia(tabId: String, candidate: GrabbedMediaCandidate) {
        if (!_mediaGrabberEnabledFlow.value) return
        val existing = _grabbedMediaByTabFlow.value[tabId].orEmpty()
        if (existing.any { it.url == candidate.url }) return
        val updated = (listOf(candidate) + existing).take(40)
        _grabbedMediaByTabFlow.value = _grabbedMediaByTabFlow.value + (tabId to updated)
    }

    fun clearGrabbedMediaForTab(tabId: String) {
        _grabbedMediaByTabFlow.value = _grabbedMediaByTabFlow.value - tabId
    }

    fun addOrUpdateSavedPassword(
        url: String,
        username: String,
        password: String,
        title: String = "",
        source: String = "Browser"
    ): SavedPasswordEntry? {
        val cleanUser = username.trim()
        val cleanPass = password
        if (cleanUser.isBlank() && cleanPass.isBlank()) return null
        val cleanUrl = url.trim()
        val withScheme = if (cleanUrl.isNotBlank() && !cleanUrl.contains("://")) "https://$cleanUrl" else cleanUrl
        val dom = runCatching {
            java.net.URI(withScheme).host.orEmpty().lowercase(Locale.US).removePrefix("www.")
        }.getOrDefault("")
            .ifBlank { cleanUrl.substringBefore('/').substringBefore(':').lowercase(Locale.US).removePrefix("www.") }
        val finalUrl = withScheme.ifBlank { if (dom.isNotBlank()) "https://$dom" else "" }
        val finalTitle = title.trim().ifBlank { dom.ifBlank { "Saved Login" } }

        val current = _savedPasswordsFlow.value
        val matchIdx = current.indexOfFirst {
            it.domain.equals(dom, ignoreCase = true) && it.username.equals(cleanUser, ignoreCase = true)
        }
        val saved = if (matchIdx >= 0) {
            val prev = current[matchIdx]
            prev.copy(
                url = finalUrl.ifBlank { prev.url },
                domain = dom.ifBlank { prev.domain },
                username = cleanUser,
                password = cleanPass,
                title = finalTitle,
                importedFrom = source,
                createdAtMs = System.currentTimeMillis()
            )
        } else {
            SavedPasswordEntry(
                url = finalUrl,
                domain = dom,
                username = cleanUser,
                password = cleanPass,
                title = finalTitle,
                importedFrom = source,
                createdAtMs = System.currentTimeMillis()
            )
        }
        val updated = listOf(saved) + current.filterIndexed { index, _ -> index != matchIdx }
        _savedPasswordsFlow.value = updated
        persistSavedPasswords(updated)
        persistAccountVaultIfSignedIn()
        googleSyncManager.autoSyncPasswordAddedOrUpdated(saved)
        return saved
    }

    fun deleteSavedPassword(entryId: String) {
        val existing = _savedPasswordsFlow.value.find { it.id == entryId }
        val updated = _savedPasswordsFlow.value.filterNot { it.id == entryId }
        _savedPasswordsFlow.value = updated
        persistSavedPasswords(updated)
        persistAccountVaultIfSignedIn()
        if (existing != null) {
            googleSyncManager.autoSyncPasswordRemoved(existing)
        }
    }

    fun clearSavedPasswords() {
        val previous = _savedPasswordsFlow.value
        _savedPasswordsFlow.value = emptyList()
        prefs.edit().remove("browser_saved_passwords_v1").apply()
        persistAccountVaultIfSignedIn()
        previous.forEach { googleSyncManager.autoSyncPasswordRemoved(it) }
    }

    /**
     * Removes all locally stored & cached passwords on this device while preserving
     * any passwords already synced in the user's Google Cloud Firestore account.
     */
    fun removeLocalPasswords(): Int {
        val removedCount = _savedPasswordsFlow.value.size
        _savedPasswordsFlow.value = emptyList()
        val editor = prefs.edit().remove("browser_saved_passwords_v1")
        prefs.all.keys
            .filter { it.startsWith("google_account_vault_") && it.endsWith("_passwords") }
            .forEach { key -> editor.remove(key) }
        editor.apply()

        kotlin.runCatching {
            vaultsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") }
                ?.forEach { file -> file.delete() }
        }
        googleSyncManager.refreshLocalSyncCounters()
        return removedCount
    }

    fun getActiveProxyProfile(): BrowserProxyProfile? {
        val activeId = _activeProxyIdFlow.value
        val profiles = _proxyProfilesFlow.value
        return profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    }

    fun setProxyEnabled(enabled: Boolean) {
        val profiles = _proxyProfilesFlow.value
        if (enabled && profiles.isEmpty()) {
            _proxyEnabledFlow.value = false
            prefs.edit().putBoolean("browser_proxy_enabled_v1", false).apply()
            BrowserProxyManager.syncNetworkProxyState(false, null)
            return
        }
        val activeProfile = getActiveProxyProfile()
        if (enabled && _activeProxyIdFlow.value != activeProfile?.id && activeProfile != null) {
            _activeProxyIdFlow.value = activeProfile.id
            prefs.edit().putString("browser_proxy_active_id_v1", activeProfile.id).apply()
        }
        _proxyEnabledFlow.value = enabled
        prefs.edit().putBoolean("browser_proxy_enabled_v1", enabled).apply()
        BrowserProxyManager.syncNetworkProxyState(enabled, activeProfile)
    }

    fun selectProxyProfile(profileId: String) {
        val target = _proxyProfilesFlow.value.firstOrNull { it.id == profileId } ?: return
        _activeProxyIdFlow.value = target.id
        _proxyEnabledFlow.value = true
        prefs.edit()
            .putString("browser_proxy_active_id_v1", target.id)
            .putBoolean("browser_proxy_enabled_v1", true)
            .apply()
        BrowserProxyManager.syncNetworkProxyState(true, target)
    }

    fun saveProxyProfile(
        profile: BrowserProxyProfile,
        activateImmediately: Boolean = true
    ): BrowserProxyProfile {
        val cleaned = profile.copy(
            name = profile.name.trim().ifBlank { "${profile.type.label} • ${profile.host.trim()}:${profile.port}" },
            host = profile.host.trim().removePrefix("[").removeSuffix("]"),
            port = profile.port.coerceIn(1, 65535),
            username = profile.username.trim(),
            bypassRules = profile.bypassRules.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        )
        val existingIdx = _proxyProfilesFlow.value.indexOfFirst { it.id == cleaned.id }
        val updated = if (existingIdx >= 0) {
            _proxyProfilesFlow.value.map { if (it.id == cleaned.id) cleaned else it }
        } else {
            listOf(cleaned) + _proxyProfilesFlow.value
        }
        _proxyProfilesFlow.value = updated
        persistProxyProfiles(updated)

        if (activateImmediately || _activeProxyIdFlow.value == cleaned.id) {
            if (activateImmediately) {
                _activeProxyIdFlow.value = cleaned.id
                _proxyEnabledFlow.value = true
                prefs.edit()
                    .putString("browser_proxy_active_id_v1", cleaned.id)
                    .putBoolean("browser_proxy_enabled_v1", true)
                    .apply()
            }
            BrowserProxyManager.syncNetworkProxyState(_proxyEnabledFlow.value, getActiveProxyProfile())
        }
        return cleaned
    }

    fun deleteProxyProfile(profileId: String) {
        val updated = _proxyProfilesFlow.value.filterNot { it.id == profileId }
        _proxyProfilesFlow.value = updated
        persistProxyProfiles(updated)

        if (_activeProxyIdFlow.value == profileId) {
            val next = updated.firstOrNull()
            _activeProxyIdFlow.value = next?.id
            if (next == null) {
                _proxyEnabledFlow.value = false
                prefs.edit()
                    .remove("browser_proxy_active_id_v1")
                    .putBoolean("browser_proxy_enabled_v1", false)
                    .apply()
            } else {
                prefs.edit().putString("browser_proxy_active_id_v1", next.id).apply()
            }
        }
        BrowserProxyManager.syncNetworkProxyState(_proxyEnabledFlow.value, getActiveProxyProfile())
    }

    fun findSavedPasswordsForUrl(pageUrl: String): List<SavedPasswordEntry> {
        val host = runCatching { java.net.URI(pageUrl).host?.lowercase(Locale.US)?.removePrefix("www.") }.getOrNull().orEmpty()
        if (host.isBlank()) return emptyList()
        return _savedPasswordsFlow.value.filter { entry ->
            val dom = entry.domain.lowercase(Locale.US).removePrefix("www.")
            dom.isNotBlank() && (host == dom || host.endsWith(".$dom") || dom.endsWith(".$host"))
        }
    }

    /**
     * Imports bookmarks from Netscape HTML (Chrome/Firefox/Edge/Brave/Safari),
     * Chrome/Chromium `Bookmarks` JSON, or Firefox `bookmarks-*.json` backup files.
     * Returns Pair(importedCount, duplicateSkippedCount).
     */
    fun importBookmarksFromText(rawText: String, fileName: String = ""): Pair<Int, Int> {
        val cleaned = rawText.removePrefix("\uFEFF").trim()
        if (cleaned.isBlank()) return 0 to 0

        val parsed = mutableListOf<Pair<String, String>>() // url to title

        if (cleaned.startsWith("{") || cleaned.startsWith("[")) {
            // Try Chrome / Edge / Brave / Firefox JSON formats
            fun collectFromJsonNode(node: Any?) {
                when (node) {
                    is JSONObject -> {
                        val directUrl = node.optString("url", "").ifBlank { node.optString("uri", "") }.trim()
                        val directTitle = node.optString("name", "").ifBlank { node.optString("title", "") }.trim()
                        if (directUrl.startsWith("http://", true) || directUrl.startsWith("https://", true)) {
                            parsed.add(directUrl to directTitle.ifBlank { directUrl })
                        }
                        val roots = node.optJSONObject("roots")
                        if (roots != null) {
                            val rKeys = roots.keys()
                            while (rKeys.hasNext()) {
                                collectFromJsonNode(roots.opt(rKeys.next()))
                            }
                        }
                        val children = node.optJSONArray("children")
                        if (children != null) {
                            for (i in 0 until children.length()) {
                                collectFromJsonNode(children.opt(i))
                            }
                        }
                        val keys = node.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            if (k != "roots" && k != "children") {
                                val v = node.opt(k)
                                if (v is JSONObject || v is JSONArray) collectFromJsonNode(v)
                            }
                        }
                    }
                    is JSONArray -> {
                        for (i in 0 until node.length()) {
                            collectFromJsonNode(node.opt(i))
                        }
                    }
                }
            }
            runCatching {
                val rootVal = org.json.JSONTokener(cleaned).nextValue()
                collectFromJsonNode(rootVal)
            }
        }

        if (parsed.isEmpty()) {
            // Netscape Bookmarks HTML (<A HREF="...">Title</A>) used by Chrome, Firefox, Edge, Brave, Opera, Safari
            val anchorRegex = Regex(
                "<a\\s+[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            anchorRegex.findAll(cleaned).forEach { match ->
                val rawUrl = decodeHtmlEntities(match.groupValues[1].trim())
                if (rawUrl.startsWith("http://", true) || rawUrl.startsWith("https://", true)) {
                    val rawTitle = decodeHtmlEntities(
                        match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                    )
                    parsed.add(rawUrl to rawTitle.ifBlank {
                        runCatching { java.net.URI(rawUrl).host?.removePrefix("www.") }.getOrNull() ?: rawUrl
                    })
                }
            }
        }

        if (parsed.isEmpty()) return 0 to 0

        val existingNorms = _bookmarksFlow.value.map { it.url.trim().trimEnd('/').lowercase(Locale.US) }.toMutableSet()
        val toAdd = mutableListOf<BrowserBookmark>()
        var skipped = 0
        parsed.forEach { (u, t) ->
            val norm = u.trim().trimEnd('/').lowercase(Locale.US)
            if (existingNorms.add(norm)) {
                toAdd.add(BrowserBookmark(title = t.take(180), url = u.trim()))
            } else {
                skipped++
            }
        }
        if (toAdd.isNotEmpty()) {
            val updated = toAdd + _bookmarksFlow.value
            _bookmarksFlow.value = updated
            persistBookmarks(updated)
        }
        return toAdd.size to skipped
    }

    /**
     * Imports passwords from CSV (Chrome, Edge, Brave, Opera, Firefox logins.csv, KeePass, 1Password, Bitwarden CSV)
     * or JSON (Bitwarden JSON export, Firefox/generic login arrays).
     * Returns Pair(importedCount, duplicateSkippedCount).
     */
    fun importPasswordsFromText(rawText: String, fileName: String = ""): Pair<Int, Int> {
        val cleaned = rawText.removePrefix("\uFEFF").trim()
        if (cleaned.isBlank()) return 0 to 0

        val candidates = mutableListOf<SavedPasswordEntry>()
        val sourceLabel = when {
            fileName.contains("firefox", true) -> "Firefox"
            fileName.contains("chrome", true) -> "Chrome"
            fileName.contains("edge", true) -> "Edge"
            fileName.contains("brave", true) -> "Brave"
            fileName.contains("bitwarden", true) -> "Bitwarden"
            else -> if (cleaned.startsWith("{") || cleaned.startsWith("[")) "JSON" else "CSV"
        }

        fun extractDomain(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isBlank()) return ""
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            return runCatching { java.net.URI(withScheme).host.orEmpty().removePrefix("www.") }
                .getOrDefault("")
                .ifBlank { trimmed.substringBefore('/').substringBefore(':').lowercase(Locale.US) }
        }

        if (cleaned.startsWith("{") || cleaned.startsWith("[")) {
            runCatching {
                val root = org.json.JSONTokener(cleaned).nextValue()
                val arr: JSONArray? = when (root) {
                    is JSONArray -> root
                    is JSONObject -> root.optJSONArray("items")
                        ?: root.optJSONArray("logins")
                        ?: root.optJSONArray("passwords")
                    else -> null
                }
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val login = item.optJSONObject("login") ?: item
                        val user = login.optString("username", "").ifBlank { login.optString("user", "") }.trim()
                        val pass = login.optString("password", "").trim()
                        if (user.isBlank() && pass.isBlank()) continue
                        var urlVal = item.optString("url", "").ifBlank { login.optString("url", "") }
                            .ifBlank { item.optString("origin", "") }.trim()
                        if (urlVal.isBlank()) {
                            val uris = login.optJSONArray("uris")
                            if (uris != null && uris.length() > 0) {
                                urlVal = uris.optJSONObject(0)?.optString("uri", "").orEmpty().trim()
                            }
                        }
                        val title = item.optString("name", "").ifBlank { item.optString("title", "") }.trim()
                        val dom = extractDomain(urlVal).ifBlank { title.lowercase(Locale.US) }
                        candidates.add(
                            SavedPasswordEntry(
                                url = urlVal.ifBlank { if (dom.isNotBlank()) "https://$dom" else "" },
                                domain = dom,
                                username = user,
                                password = pass,
                                title = title.ifBlank { dom.ifBlank { "Saved Login" } },
                                importedFrom = sourceLabel
                            )
                        )
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            val rows = parseCsvRows(cleaned)
            if (rows.size >= 2) {
                val headers = rows.first().map { it.trim().lowercase(Locale.US).replace("_", "").replace(" ", "") }
                val idxUrl = headers.indexOfFirst { it in setOf("url", "origin", "originurl", "formactionorigin", "website", "loginuri", "host", "hostname") }
                val idxUser = headers.indexOfFirst { it in setOf("username", "user", "loginusername", "login", "email", "account") }
                val idxPass = headers.indexOfFirst { it in setOf("password", "pass", "loginpassword", "pwd") }
                val idxName = headers.indexOfFirst { it in setOf("name", "title", "servicename") }

                for (r in 1 until rows.size) {
                    val cols = rows[r]
                    if (cols.isEmpty() || cols.all { it.isBlank() }) continue
                    val rawUrl = cols.getOrNull(idxUrl)?.trim().orEmpty()
                    val user = cols.getOrNull(idxUser)?.trim().orEmpty()
                    val pass = cols.getOrNull(idxPass)?.trim().orEmpty()
                    val title = cols.getOrNull(idxName)?.trim().orEmpty()
                    if (user.isBlank() && pass.isBlank()) continue
                    val dom = extractDomain(rawUrl).ifBlank { title.lowercase(Locale.US) }
                    candidates.add(
                        SavedPasswordEntry(
                            url = rawUrl.ifBlank { if (dom.isNotBlank()) "https://$dom" else "" },
                            domain = dom,
                            username = user,
                            password = pass,
                            title = title.ifBlank { dom.ifBlank { "Saved Login" } },
                            importedFrom = sourceLabel
                        )
                    )
                }
            }
        }

        if (candidates.isEmpty()) return 0 to 0

        val existingKeys = _savedPasswordsFlow.value.map {
            "${it.domain.lowercase(Locale.US)}|${it.username}|${it.password}"
        }.toMutableSet()
        val toAdd = mutableListOf<SavedPasswordEntry>()
        var skipped = 0
        candidates.forEach { entry ->
            val key = "${entry.domain.lowercase(Locale.US)}|${entry.username}|${entry.password}"
            if (existingKeys.add(key)) {
                toAdd.add(entry)
            } else {
                skipped++
            }
        }
        if (toAdd.isNotEmpty()) {
            val updated = toAdd + _savedPasswordsFlow.value
            _savedPasswordsFlow.value = updated
            persistSavedPasswords(updated)
            persistAccountVaultIfSignedIn()
            googleSyncManager.autoSyncPasswordsBatch(toAdd)
        }
        return toAdd.size to skipped
    }

    fun onWebViewGoogleAccountDetected(
        email: String?,
        displayName: String?,
        hasSessionCookies: Boolean
    ) {
        googleSyncManager.onBrowserWebViewGoogleAccountDetected(
            email = email,
            displayName = displayName,
            hasSessionCookies = hasSessionCookies
        )
        syncAccountVaultNow()
    }

    /**
     * Inspects a downloaded text export (e.g., `Google Passwords.csv` from `passwords.google.com/options`,
     * Bitwarden JSON, Netscape HTML `bookmarks_*.html`, or Chrome/Firefox JSON bookmarks) and automatically
     * imports and syncs its entries when recognized.
     */
    fun tryAutoImportFromDownloadedText(
        fileName: String,
        mimeType: String?,
        rawText: String
    ): DownloadedSyncImportResult? {
        val trimmed = rawText.removePrefix("\uFEFF").trim()
        if (trimmed.isBlank()) return null
        val lowerName = fileName.lowercase(Locale.US)
        val lowerMime = mimeType?.lowercase(Locale.US).orEmpty()

        // 1. Check password CSV / JSON exports first (e.g. Google Passwords.csv)
        val looksLikePasswordCsv = (lowerName.endsWith(".csv") || lowerMime.contains("csv") || !trimmed.startsWith("<")) &&
            run {
                val firstLine = trimmed.lineSequence().firstOrNull { it.isNotBlank() }?.lowercase(Locale.US).orEmpty()
                val hasUrlCol = firstLine.contains("url") || firstLine.contains("origin") || firstLine.contains("website") || firstLine.contains("host")
                val hasUserCol = firstLine.contains("username") || firstLine.contains("user") || firstLine.contains("email") || firstLine.contains("login")
                val hasPassCol = firstLine.contains("password") || firstLine.contains("pwd")
                hasUrlCol && hasUserCol && hasPassCol
            }

        val looksLikePasswordJson = (trimmed.startsWith("{") || trimmed.startsWith("[")) &&
            (trimmed.contains("\"password\"", ignoreCase = true) &&
                (trimmed.contains("\"username\"", ignoreCase = true) || trimmed.contains("\"login\"", ignoreCase = true)))

        if (looksLikePasswordCsv || looksLikePasswordJson) {
            val resolvedName = if (lowerName.contains("google") && !lowerName.contains("chrome")) {
                "Chrome Google Passwords.csv"
            } else {
                fileName
            }
            val (added, skipped) = importPasswordsFromText(trimmed, resolvedName)
            if (added > 0 || skipped > 0) {
                return DownloadedSyncImportResult(
                    kind = DownloadedImportKind.PASSWORDS,
                    added = added,
                    skipped = skipped
                )
            }
        }

        // 2. Check Netscape HTML or Chrome/Firefox JSON bookmark exports
        val looksLikeBookmarkHtml = trimmed.contains("NETSCAPE-Bookmark-file-1", ignoreCase = true) ||
            ((lowerName.contains("bookmark") || lowerName.contains("favorite")) &&
                trimmed.contains("<a ", ignoreCase = true) &&
                trimmed.contains("href=", ignoreCase = true))

        val looksLikeBookmarkJson = (trimmed.startsWith("{") || trimmed.startsWith("[")) &&
            (trimmed.contains("\"roots\"", ignoreCase = true) ||
                trimmed.contains("\"bookmark_bar\"", ignoreCase = true) ||
                (trimmed.contains("\"children\"", ignoreCase = true) &&
                    (trimmed.contains("\"uri\"", ignoreCase = true) || trimmed.contains("\"url\"", ignoreCase = true))))

        if (looksLikeBookmarkHtml || looksLikeBookmarkJson) {
            val (added, skipped) = importBookmarksFromText(trimmed, fileName)
            if (added > 0 || skipped > 0) {
                return DownloadedSyncImportResult(
                    kind = DownloadedImportKind.BOOKMARKS,
                    added = added,
                    skipped = skipped
                )
            }
        }

        return null
    }

    fun syncAccountVaultNow() {
        val state = kotlin.runCatching { googleSyncManager.syncStateFlow.value }.getOrNull() ?: return
        if (!state.autoSyncEnabled || !state.isBrowserGoogleConnected) return
        val rawEffective = state.effectiveAccountEmail?.takeIf { it.isNotBlank() }
        val effectiveEmail = BrowserGoogleSyncManager.sanitizeEmailCandidate(rawEffective) ?: rawEffective
        // First merge any session-level vault entries captured before the exact email was resolved
        loadAndMergeAccountVault(DEFAULT_WEB_SESSION_VAULT_KEY)
        if (!effectiveEmail.isNullOrBlank()) {
            // Migrate any legacy corrupted x22/u0022 vault key from earlier versions
            listOf("x22$effectiveEmail", "u0022$effectiveEmail").forEach { legacyKey ->
                migrateLegacyAccountVaultIfPresent(legacyKey)
            }
            loadAndMergeAccountVault(effectiveEmail)
        }
        persistAccountVaultIfSignedIn()
        googleSyncManager.refreshLocalSyncCounters()
    }

    private fun migrateLegacyAccountVaultIfPresent(legacyAccountKey: String) {
        val legacyPrefix = accountVaultKeyPrefix(legacyAccountKey)
        val legacyFile = java.io.File(vaultsDir, "${accountVaultSafeKey(legacyAccountKey)}.json")
        val hasPrefs = prefs.contains("${legacyPrefix}bookmarks") || prefs.contains("${legacyPrefix}passwords")
        if (!hasPrefs && !legacyFile.exists()) return
        loadAndMergeAccountVault(legacyAccountKey)
        prefs.edit()
            .remove("${legacyPrefix}bookmarks")
            .remove("${legacyPrefix}passwords")
            .apply()
        kotlin.runCatching { legacyFile.delete() }
    }

    fun mergeRemotePasswords(remotePasswords: List<SavedPasswordEntry>) {
        if (remotePasswords.isEmpty()) return
        val current = _savedPasswordsFlow.value
        val byKey = LinkedHashMap<String, SavedPasswordEntry>()
        current.forEach { pw ->
            val key = "${pw.domain.trim().lowercase(Locale.US).removePrefix("www.")}|${pw.username.trim().lowercase(Locale.US)}"
            byKey[key] = pw
        }
        var changed = false
        remotePasswords.forEach { remote ->
            val key = "${remote.domain.trim().lowercase(Locale.US).removePrefix("www.")}|${remote.username.trim().lowercase(Locale.US)}"
            val local = byKey[key]
            if (local == null) {
                byKey[key] = remote
                changed = true
            } else if (remote.createdAtMs > local.createdAtMs &&
                (remote.password != local.password || remote.title != local.title)
            ) {
                byKey[key] = local.copy(
                    password = remote.password.ifEmpty { local.password },
                    title = remote.title.ifBlank { local.title },
                    url = remote.url.ifBlank { local.url },
                    importedFrom = remote.importedFrom.ifBlank { local.importedFrom },
                    createdAtMs = remote.createdAtMs
                )
                changed = true
            }
        }
        if (changed) {
            val merged = byKey.values.sortedByDescending { it.createdAtMs }
            _savedPasswordsFlow.value = merged
            merged.forEach { passkeyManager.importPasskeyFromPasswordEntryIfPresent(it) }
            persistSavedPasswords(merged)
            persistAccountVaultIfSignedIn()
        }
    }

    private fun accountVaultSafeKey(accountKey: String): String {
        return accountKey.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9_@.-]"), "_")
    }

    private fun accountVaultKeyPrefix(accountKey: String): String {
        return "google_account_vault_${accountVaultSafeKey(accountKey)}_"
    }

    private fun persistAccountVaultIfSignedIn() {
        val state = kotlin.runCatching { googleSyncManager.syncStateFlow.value }.getOrNull() ?: return
        if (!state.autoSyncEnabled || !state.isBrowserGoogleConnected) return
        val keysToUpdate = buildList {
            add(DEFAULT_WEB_SESSION_VAULT_KEY)
            state.effectiveAccountEmail?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.distinct()

        try {
            val pwArr = JSONArray()
            _savedPasswordsFlow.value.forEach { entry ->
                pwArr.put(JSONObject().apply {
                    put("id", entry.id)
                    put("url", entry.url)
                    put("domain", entry.domain)
                    put("username", entry.username)
                    put("password", entry.password)
                    put("title", entry.title)
                    put("importedFrom", entry.importedFrom)
                    put("createdAtMs", entry.createdAtMs)
                })
            }

            keysToUpdate.forEach { key ->
                val prefix = accountVaultKeyPrefix(key)
                val editor = prefs.edit()
                editor.remove("${prefix}bookmarks")
                if (state.autoSyncPasswords) {
                    val encPw = MalwareProtectionEngine.encryptSensitiveString(appContext, pwArr.toString())
                    editor.putString("${prefix}passwords", encPw)
                }
                editor.apply()

                kotlin.runCatching {
                    val fileObj = JSONObject().apply {
                        put("account", key)
                        put("updatedAtMs", System.currentTimeMillis())
                        if (state.autoSyncPasswords) put("passwords", pwArr)
                    }
                    val encFile = MalwareProtectionEngine.encryptSensitiveString(appContext, fileObj.toString())
                    java.io.File(vaultsDir, "${accountVaultSafeKey(key)}.json")
                        .writeText(encFile, Charsets.UTF_8)
                }
            }
            googleSyncManager.refreshLocalSyncCounters()
        } catch (_: Exception) {}
    }

    private fun loadAndMergeAccountVault(accountKey: String) {
        val prefix = accountVaultKeyPrefix(accountKey)
        try {
            val fileObj = kotlin.runCatching {
                val f = java.io.File(vaultsDir, "${accountVaultSafeKey(accountKey)}.json")
                if (f.exists()) {
                    val decText = MalwareProtectionEngine.decryptSensitiveString(appContext, f.readText(Charsets.UTF_8))
                    if (decText.isNotBlank()) JSONObject(decText) else null
                } else null
            }.getOrNull()

            val rawPrefPw = prefs.getString("${prefix}passwords", null)
            val decPrefPw = if (!rawPrefPw.isNullOrBlank()) {
                MalwareProtectionEngine.decryptSensitiveString(appContext, rawPrefPw).takeIf { it.isNotBlank() }
            } else null
            val rawPw = decPrefPw
                ?: fileObj?.optJSONArray("passwords")?.toString()
            if (!rawPw.isNullOrBlank()) {
                val arr = JSONArray(rawPw)
                val list = mutableListOf<SavedPasswordEntry>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val u = obj.optString("username", "")
                    val p = obj.optString("password", "")
                    if (u.isBlank() && p.isBlank()) continue
                    list.add(
                        SavedPasswordEntry(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            url = obj.optString("url", ""),
                            domain = obj.optString("domain", ""),
                            username = u,
                            password = p,
                            title = obj.optString("title", ""),
                            importedFrom = obj.optString("importedFrom", "Google Sync"),
                            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    mergeRemotePasswords(list)
                }
            }
        } catch (_: Exception) {}
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'")
            .replace("&apos;", "'", ignoreCase = true)
    }

    private fun parseCsvRows(csvText: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < csvText.length) {
            val ch = csvText[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        if (i + 1 < csvText.length && csvText[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(ch)
                    }
                }
                ch == '"' -> inQuotes = true
                ch == ',' -> {
                    currentRow.add(field.toString())
                    field.setLength(0)
                }
                ch == '\r' -> {
                    if (i + 1 < csvText.length && csvText[i + 1] == '\n') i++
                    currentRow.add(field.toString())
                    rows.add(currentRow.toList())
                    currentRow.clear()
                    field.setLength(0)
                }
                ch == '\n' -> {
                    currentRow.add(field.toString())
                    rows.add(currentRow.toList())
                    currentRow.clear()
                    field.setLength(0)
                }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(field.toString())
            rows.add(currentRow.toList())
        }
        return rows
    }

    fun incrementAdsBlocked() {
        _adsBlockedCountFlow.value += 1
    }

    fun recordConsoleMessage(
        tabId: String,
        level: String,
        message: String,
        source: String = "",
        lineNumber: Int = 0
    ) {
        if (!_pageInspectorEnabledFlow.value || message.isBlank()) return
        val entry = WebConsoleLogEntry(
            level = level.uppercase(Locale.US),
            message = message.take(2000),
            source = source.take(300),
            lineNumber = lineNumber
        )
        _consoleLogsByTabFlow.update { currentMap ->
            val existing = currentMap[tabId].orEmpty()
            val updated = (existing + entry).takeLast(150)
            currentMap + (tabId to updated)
        }
    }

    fun clearConsoleLogs(tabId: String) {
        _consoleLogsByTabFlow.update { it - tabId }
    }

    fun recordNetworkRequest(
        tabId: String,
        url: String,
        method: String,
        resourceType: String,
        statusLabel: String
    ) {
        if (!_pageInspectorEnabledFlow.value || url.isBlank()) return
        val domain = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        val entry = WebNetworkLogEntry(
            url = url.take(1500),
            method = method.ifBlank { "GET" },
            resourceType = resourceType,
            statusLabel = statusLabel,
            domain = domain
        )
        _networkLogsByTabFlow.update { currentMap ->
            val existing = currentMap[tabId].orEmpty()
            val updated = (existing + entry).takeLast(200)
            currentMap + (tabId to updated)
        }
    }

    fun clearNetworkLogs(tabId: String) {
        _networkLogsByTabFlow.update { it - tabId }
    }

    private fun persistHistory(entries: List<BrowserHistoryEntry>) {
        try {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject().apply {
                    put("id", e.id)
                    put("title", e.title)
                    put("url", e.url)
                    put("visitedAtMs", e.visitedAtMs)
                })
            }
            prefs.edit().putString("browser_history_v1", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadHistory() {
        try {
            val raw = prefs.getString("browser_history_v1", null) ?: return
            val arr = JSONArray(raw)
            val list = mutableListOf<BrowserHistoryEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url", "")
                if (url.isBlank()) continue
                list.add(
                    BrowserHistoryEntry(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", url),
                        url = url,
                        visitedAtMs = obj.optLong("visitedAtMs", System.currentTimeMillis())
                    )
                )
            }
            _historyFlow.value = list
        } catch (_: Exception) {}
    }

    private fun persistBookmarks(items: List<BrowserBookmark>) {
        try {
            val arr = JSONArray()
            items.forEach { b ->
                arr.put(JSONObject().apply {
                    put("id", b.id)
                    put("title", b.title)
                    put("url", b.url)
                    put("createdAtMs", b.createdAtMs)
                })
            }
            prefs.edit().putString("browser_bookmarks_v1", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadBookmarks() {
        try {
            if (!prefs.contains("browser_bookmarks_v1")) {
                val defaults = listOf(
                    BrowserBookmark(title = "Google", url = DEFAULT_HOME_URL)
                )
                _bookmarksFlow.value = defaults
                persistBookmarks(defaults)
                return
            }
            val raw = prefs.getString("browser_bookmarks_v1", null) ?: return
            val arr = JSONArray(raw)
            val list = mutableListOf<BrowserBookmark>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url", "")
                if (url.isBlank()) continue
                list.add(
                    BrowserBookmark(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", url),
                        url = url,
                        createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
                    )
                )
            }
            _bookmarksFlow.value = list
        } catch (_: Exception) {}
    }

    private fun persistSavedPasswords(items: List<SavedPasswordEntry>) {
        try {
            val arr = JSONArray()
            items.forEach { entry ->
                arr.put(JSONObject().apply {
                    put("id", entry.id)
                    put("url", entry.url)
                    put("domain", entry.domain)
                    put("username", entry.username)
                    put("password", entry.password)
                    put("title", entry.title)
                    put("importedFrom", entry.importedFrom)
                    put("createdAtMs", entry.createdAtMs)
                })
            }
            val encrypted = MalwareProtectionEngine.encryptSensitiveString(appContext, arr.toString())
            prefs.edit().putString("browser_saved_passwords_v1", encrypted).apply()
        } catch (_: Exception) {}
    }

    private fun loadSavedPasswords() {
        try {
            val rawStored = prefs.getString("browser_saved_passwords_v1", null) ?: return
            val raw = MalwareProtectionEngine.decryptSensitiveString(appContext, rawStored)
            if (raw.isBlank()) return
            val arr = JSONArray(raw)
            val list = mutableListOf<SavedPasswordEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val user = obj.optString("username", "")
                val pass = obj.optString("password", "")
                if (user.isBlank() && pass.isBlank()) continue
                list.add(
                    SavedPasswordEntry(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        url = obj.optString("url", ""),
                        domain = obj.optString("domain", ""),
                        username = user,
                        password = pass,
                        title = obj.optString("title", ""),
                        importedFrom = obj.optString("importedFrom", "CSV"),
                        createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
                    )
                )
            }
            _savedPasswordsFlow.value = list
            list.forEach { passkeyManager.importPasskeyFromPasswordEntryIfPresent(it) }
            // Seamlessly upgrade legacy plaintext storage to AES-GCM encrypted storage
            if (!rawStored.startsWith("ENC_AESGCM_V1:") && list.isNotEmpty()) {
                persistSavedPasswords(list)
            }
        } catch (_: Exception) {}
    }

    private fun persistProxyProfiles(profiles: List<BrowserProxyProfile>) {
        try {
            val arr = JSONArray()
            profiles.forEach { p ->
                arr.put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("type", p.type.id)
                    put("host", p.host)
                    put("port", p.port)
                    put("username", p.username)
                    put("password", p.password)
                    put("bypassRules", JSONArray(p.bypassRules))
                    put("reverseBypass", p.reverseBypass)
                    put("applyToDownloads", p.applyToDownloads)
                    put("createdAtMs", p.createdAtMs)
                })
            }
            prefs.edit().putString("browser_proxy_profiles_v1", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadProxyState() {
        try {
            val raw = prefs.getString("browser_proxy_profiles_v1", null)
            val list = mutableListOf<BrowserProxyProfile>()
            if (!raw.isNullOrBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val host = obj.optString("host", "").trim()
                    val port = obj.optInt("port", 0)
                    if (host.isBlank() || port !in 1..65535) continue
                    val bypassArr = obj.optJSONArray("bypassRules")
                    val bypass = if (bypassArr != null) {
                        (0 until bypassArr.length()).mapNotNull { idx ->
                            bypassArr.optString(idx, "").trim().takeIf { it.isNotBlank() }
                        }
                    } else {
                        BrowserProxyProfile.DEFAULT_BYPASS_RULES
                    }
                    list.add(
                        BrowserProxyProfile(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            name = obj.optString("name", "$host:$port"),
                            type = BrowserProxyType.fromId(obj.optString("type", "HTTP")),
                            host = host,
                            port = port,
                            username = obj.optString("username", ""),
                            password = obj.optString("password", ""),
                            bypassRules = bypass,
                            reverseBypass = obj.optBoolean("reverseBypass", false),
                            applyToDownloads = obj.optBoolean("applyToDownloads", true),
                            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
                        )
                    )
                }
            }
            _proxyProfilesFlow.value = list
            val savedActiveId = prefs.getString("browser_proxy_active_id_v1", null)
            val resolvedActive = list.firstOrNull { it.id == savedActiveId } ?: list.firstOrNull()
            _activeProxyIdFlow.value = resolvedActive?.id
            val enabled = prefs.getBoolean("browser_proxy_enabled_v1", false) && resolvedActive != null
            _proxyEnabledFlow.value = enabled
            BrowserProxyManager.syncNetworkProxyState(enabled, resolvedActive)
        } catch (_: Exception) {}
    }

    companion object {
        const val DEFAULT_HOME_URL = "https://www.google.com"
        private const val DEFAULT_WEB_SESSION_VAULT_KEY = "active_google_session"

        fun findOmniboxSuggestions(
            query: String,
            bookmarks: List<BrowserBookmark>,
            history: List<BrowserHistoryEntry>,
            maxResults: Int = 8
        ): List<OmniboxSuggestion> {
            val trimmed = query.trim().lowercase(Locale.US)
            val queryNoScheme = trimmed
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
            val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }

            fun stripUrlForMatch(rawUrl: String): String {
                val lower = rawUrl.trim().lowercase(Locale.US)
                return lower
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .removePrefix("www.")
            }

            fun scoreCandidate(title: String, url: String, isBookmark: Boolean): Int? {
                val cleanUrl = url.trim()
                if (cleanUrl.isBlank() || cleanUrl.equals("about:blank", ignoreCase = true)) return null
                val urlLower = cleanUrl.lowercase(Locale.US)
                val strippedUrl = stripUrlForMatch(cleanUrl)
                val host = strippedUrl.substringBefore('/').substringBefore('?')
                val titleLower = title.trim().lowercase(Locale.US)

                if (tokens.isEmpty()) {
                    return if (isBookmark) 200 else 100
                }

                val combined = "$urlLower $strippedUrl $titleLower"
                if (tokens.any { token -> !combined.contains(token) }) {
                    return null
                }

                var score = if (isBookmark) 150 else 0
                if (queryNoScheme.isNotEmpty() && host.startsWith(queryNoScheme)) {
                    score += 1200
                } else if (queryNoScheme.isNotEmpty() && strippedUrl.startsWith(queryNoScheme)) {
                    score += 1000
                } else if (urlLower.startsWith(trimmed)) {
                    score += 950
                }

                if (titleLower.startsWith(trimmed)) {
                    score += 700
                } else if (queryNoScheme.isNotEmpty() && host.contains(queryNoScheme)) {
                    score += 500
                } else if (titleLower.contains(trimmed)) {
                    score += 320
                } else if (urlLower.contains(trimmed)) {
                    score += 240
                } else {
                    score += 120
                }
                return score
            }

            val deduped = linkedMapOf<String, OmniboxSuggestion>()

            bookmarks.forEach { b ->
                val s = scoreCandidate(b.title, b.url, isBookmark = true) ?: return@forEach
                val normKey = b.url.trim().trimEnd('/').lowercase(Locale.US)
                val displayTitle = b.title.trim().ifBlank { stripUrlForMatch(b.url).substringBefore('/') }
                val candidate = OmniboxSuggestion(
                    title = displayTitle,
                    url = b.url.trim(),
                    source = OmniboxSuggestionSource.BOOKMARK,
                    timestampMs = b.createdAtMs,
                    score = s
                )
                val prev = deduped[normKey]
                if (prev == null || candidate.score > prev.score) {
                    deduped[normKey] = candidate
                }
            }

            history.forEach { h ->
                val s = scoreCandidate(h.title, h.url, isBookmark = false) ?: return@forEach
                val normKey = h.url.trim().trimEnd('/').lowercase(Locale.US)
                val displayTitle = h.title.trim().ifBlank { stripUrlForMatch(h.url).substringBefore('/') }
                val prev = deduped[normKey]
                if (prev == null) {
                    deduped[normKey] = OmniboxSuggestion(
                        title = displayTitle,
                        url = h.url.trim(),
                        source = OmniboxSuggestionSource.HISTORY,
                        timestampMs = h.visitedAtMs,
                        score = s
                    )
                } else if (prev.source == OmniboxSuggestionSource.BOOKMARK) {
                    // Preserve BOOKMARK source badge while retaining latest visit timestamp if newer
                    val betterTitle = prev.title.ifBlank { displayTitle }
                    deduped[normKey] = prev.copy(
                        title = betterTitle,
                        timestampMs = maxOf(prev.timestampMs, h.visitedAtMs)
                    )
                } else if (s > prev.score || h.visitedAtMs > prev.timestampMs) {
                    deduped[normKey] = OmniboxSuggestion(
                        title = displayTitle,
                        url = h.url.trim(),
                        source = OmniboxSuggestionSource.HISTORY,
                        timestampMs = h.visitedAtMs,
                        score = maxOf(s, prev.score)
                    )
                }
            }

            return deduped.values
                .sortedWith(
                    compareByDescending<OmniboxSuggestion> { it.score }
                        .thenByDescending { it.timestampMs }
                )
                .take(maxResults.coerceAtLeast(1))
        }

        /**
         * Resolves omnibox input cleanly:
         * - Blank -> default google.com homepage
         * - Explicit scheme -> direct URL
         * - Domain-like string (no spaces, contains dot) -> https://domain
         * - Anything else -> Google Search query
         */
        fun resolveBrowserInputToUrl(rawInput: String): String {
            val trimmed = rawInput.trim()
            if (trimmed.isBlank()) return DEFAULT_HOME_URL
            val lower = trimmed.lowercase(Locale.US)
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return trimmed
            }
            if (!trimmed.contains(' ') && trimmed.contains('.') && !trimmed.startsWith(".")) {
                return "https://$trimmed"
            }
            val encoded = try {
                URLEncoder.encode(trimmed, "UTF-8")
            } catch (_: Exception) {
                trimmed.replace(" ", "+")
            }
            return "https://www.google.com/search?q=$encoded"
        }
    }
}
