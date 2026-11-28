package com.example.data.browser

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

enum class ExtensionKind(val badge: String) {
    WEB_EXTENSION_MV3("MV3 EXT"),
    WEB_EXTENSION_MV2("MV2 EXT"),
    USERSCRIPT("USERSCRIPT"),
    USERSTYLE("USERSTYLE")
}

data class ExtensionContentScriptRule(
    val matches: List<String> = listOf("<all_urls>"),
    val excludeMatches: List<String> = emptyList(),
    val includeGlobs: List<String> = emptyList(),
    val excludeGlobs: List<String> = emptyList(),
    val jsFiles: List<String> = emptyList(),
    val cssFiles: List<String> = emptyList(),
    val inlineJs: String = "",
    val inlineCss: String = "",
    val runAt: String = "document_end", // document_start, document_end, document_idle
    val allFrames: Boolean = false
)

data class ExtensionNetRule(
    val id: Int,
    val actionType: String, // "block" or "redirect"
    val urlFilter: String,
    val redirectUrl: String? = null,
    val initiatorDomains: List<String> = emptyList(),
    val excludedInitiatorDomains: List<String> = emptyList(),
    val requestDomains: List<String> = emptyList(),
    val excludedRequestDomains: List<String> = emptyList(),
    val isRegexFilter: Boolean = false
)

data class BrowserExtension(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val kind: ExtensionKind,
    val enabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val sourceFileName: String = "",
    val installDirPath: String = "",
    val popupPath: String? = null,
    val backgroundScriptPath: String? = null,
    val storeId: String? = null,
    val i18nMessagesJson: String = "{}",
    val manifestJson: String = "{}",
    val permissions: List<String> = emptyList(),
    val hostPermissions: List<String> = listOf("<all_urls>"),
    val contentScripts: List<ExtensionContentScriptRule> = emptyList(),
    val netRules: List<ExtensionNetRule> = emptyList(),
    val installedAtMs: Long = System.currentTimeMillis(),
    val optionsPath: String? = null,
    val optionsOpenInTab: Boolean = false,
    val author: String = "",
    val homepageUrl: String = ""
)

/**
 * Full-featured Browser Extension Runtime Engine for Dragon Browser.
 *
 * Supports installing extensions directly from the Chrome Web Store (`chromewebstore.google.com`),
 * store URLs / 32-character Extension IDs, direct `.crx` download links, local files (`OpenDocument`),
 * or unpacked extension folders (`OpenDocumentTree` / directory path):
 * - Unpacked extension folders containing `manifest.json` (MV2 / MV3, `_locales`, popup, DNR, JS/CSS) or scripts
 * - `.zip`, `.crx` (Chrome Extension CRX2/CRX3 header auto-unwrapped), `.xpi` (Firefox WebExtension)
 * - `.user.js` / `.js` (Tampermonkey / Violentmonkey / Greasemonkey Userscripts)
 * - `.css` / `.user.css` (Stylus UserStyles)
 * - `manifest.json` (Direct unpacked manifest import)
 *
 * Provides real WebView runtime execution:
 * - Content Script & CSS injection (`document_start`, `document_end`, `document_idle`)
 * - URL match pattern engine (`<all_urls>`, domain/port wildcards, `include_globs`/`exclude_globs`)
 * - `chrome.*` / `browser.*` WebExtension API polyfill (`storage`, `runtime` + Ports, `tabs`, `scripting`, `i18n`, `action`, `permissions`)
 * - `GM_*` Userscript API polyfill (`GM_getValue`, `GM_setValue`, `GM_addStyle`, cross-origin `GM_xmlhttpRequest`, `GM_openInTab`, `GM_setClipboard`)
 * - `chrome-extension://<extId>/...` and `https://dragon-ext.local/<extId>/...` web-accessible resource serving
 * - `declarative_net_request` network blocking & redirect rules in `shouldInterceptRequest`
 * - Interactive `popup.html` extension action modal with live active-tab context
 */
class BrowserExtensionEngine(private val context: Context) {

    private val prefs = context.getSharedPreferences("dragon_browser_extensions_v1", Context.MODE_PRIVATE)
    private val kvStoragePrefs = context.getSharedPreferences("dragon_browser_ext_storage_kv", Context.MODE_PRIVATE)

    private val engineIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Store unpacked extension packages inside cacheDir rather than filesDir so Android OS
    // does not bloat permanent App User Data size, while keeping temporary archive staging isolated.
    private val extensionsRootDir: File = File(context.cacheDir, "browser_extensions_store")
    private val tempStagingDir: File = File(context.cacheDir, "ext_temp_staging")

    private val httpClientLock = Any()
    @Volatile
    private var currentDns: Dns = Dns.SYSTEM
    @Volatile
    private var _crossOriginHttpClient: OkHttpClient? = null
    @Volatile
    private var _directExtensionHttpClient: OkHttpClient? = null

    private val crossOriginHttpClient: OkHttpClient
        get() = _crossOriginHttpClient ?: synchronized(httpClientLock) {
            _crossOriginHttpClient ?: buildHttpClient(currentDns).also { _crossOriginHttpClient = it }
        }

    private val directExtensionHttpClient: OkHttpClient
        get() = _directExtensionHttpClient ?: synchronized(httpClientLock) {
            _directExtensionHttpClient ?: buildDirectHttpClient(currentDns).also { _directExtensionHttpClient = it }
        }

    private fun buildHttpClient(dns: Dns): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(dns)
            .proxySelector(BrowserProxyManager.okHttpProxySelector)
            .proxyAuthenticator(BrowserProxyManager.okHttpProxyAuthenticator)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun buildDirectHttpClient(dns: Dns): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(dns)
            .proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun setHttpDns(dns: Dns) {
        synchronized(httpClientLock) {
            currentDns = dns
            _crossOriginHttpClient = null
            _directExtensionHttpClient = null
        }
    }

    private val _extensionsFlow = MutableStateFlow<List<BrowserExtension>>(emptyList())
    val extensionsFlow: StateFlow<List<BrowserExtension>> = _extensionsFlow.asStateFlow()

    init {
        engineIoScope.launch {
            runCatching { extensionsRootDir.mkdirs() }
            runCatching { tempStagingDir.mkdirs() }
            migrateLegacyFilesDirExtensionsAndCleanTemp()
            loadExtensions()
            pruneOrphanedExtensionDirectories()
        }
    }

    /**
     * Migrates any legacy extensions unpacked in `context.filesDir/browser_extensions` into
     * `context.cacheDir/browser_extensions_store` and deletes the old `filesDir` directory
     * so App User Data size immediately shrinks.
     */
    private fun migrateLegacyFilesDirExtensionsAndCleanTemp() {
        try {
            val legacyRoot = File(context.filesDir, "browser_extensions")
            if (legacyRoot.exists() && legacyRoot.isDirectory) {
                legacyRoot.listFiles()?.forEach { child ->
                    if (child.isDirectory) {
                        val dest = File(extensionsRootDir, child.name)
                        if (!dest.exists()) {
                            runCatching { child.copyRecursively(dest, overwrite = true) }
                        }
                    }
                }
                runCatching { legacyRoot.deleteRecursively() }
            }
            // Clear any leftover temporary archive staging files
            if (tempStagingDir.exists()) {
                tempStagingDir.listFiles()?.forEach { f ->
                    runCatching { f.deleteRecursively() }
                }
            }
            // Also clean any stray temp_ext_*.tmp files in extensionsRootDir
            extensionsRootDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith("temp_ext_")) {
                    runCatching { f.delete() }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Removes any unpacked extension folders in `extensionsRootDir` that are not referenced by
     * an installed extension, freeing temporary storage allocation automatically.
     */
    fun pruneOrphanedExtensionDirectories(): Long {
        var bytesFreed = 0L
        try {
            val activeIds = _extensionsFlow.value.map { it.id }.toSet()
            val activePaths = _extensionsFlow.value.mapNotNull {
                it.installDirPath.takeIf { p -> p.isNotBlank() }?.let { p -> File(p).absolutePath }
            }.toSet()

            if (tempStagingDir.exists()) {
                tempStagingDir.listFiles()?.forEach { f ->
                    val sz = computeDirBytes(f)
                    if (runCatching { f.deleteRecursively() }.getOrDefault(false)) {
                        bytesFreed += sz
                    }
                }
            }

            extensionsRootDir.listFiles()?.forEach { dir ->
                val isReferenced = dir.name in activeIds ||
                    activePaths.any { p -> p == dir.absolutePath || p.startsWith(dir.absolutePath + File.separator) }
                if (!isReferenced) {
                    val sz = computeDirBytes(dir)
                    if (runCatching { dir.deleteRecursively() }.getOrDefault(false)) {
                        bytesFreed += sz
                    }
                }
            }
        } catch (_: Exception) {}
        return bytesFreed
    }

    private fun computeDirBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var sum = 0L
        file.listFiles()?.forEach { sum += computeDirBytes(it) }
        return sum
    }

    fun isInstalledFromStoreId(storeId: String?): Boolean {
        val clean = storeId?.trim()?.lowercase(Locale.US) ?: return false
        return CWS_ID_REGEX.matches(clean) &&
            _extensionsFlow.value.any { it.storeId.equals(clean, ignoreCase = true) }
    }

    fun getEnabledExtensionsCount(): Int = _extensionsFlow.value.count { it.enabled }

    fun setExtensionEnabled(extensionId: String, enabled: Boolean) {
        val updated = _extensionsFlow.value.map { ext ->
            if (ext.id == extensionId) ext.copy(enabled = enabled) else ext
        }
        _extensionsFlow.value = updated
        persistExtensions(updated)
        if (!enabled) {
            clearExtensionProxySettings(extensionId)
        }
    }

    fun removeExtension(extensionId: String): Boolean {
        val target = _extensionsFlow.value.find { it.id == extensionId } ?: return false
        clearExtensionProxySettings(extensionId)
        if (target.installDirPath.isNotBlank()) {
            val installDir = File(target.installDirPath)
            runCatching { installDir.deleteRecursively() }
            // Also delete parent container inside extensionsRootDir if manifest was in a subfolder
            val rootContainer = File(extensionsRootDir, target.id)
            if (rootContainer.exists()) {
                runCatching { rootContainer.deleteRecursively() }
            }
        }
        kvStoragePrefs.edit().remove("kv_${target.id}").apply()
        val updated = _extensionsFlow.value.filterNot { it.id == extensionId }
        _extensionsFlow.value = updated
        persistExtensions(updated)
        pruneOrphanedExtensionDirectories()
        return true
    }

    /**
     * Live Modification Panel API: Allows modifying an installed extension's display name,
     * URL match patterns, injection timing (`document_start`, `document_end`, `document_idle`),
     * and custom injected JS/CSS rules directly from the Extension Modification Panel.
     */
    fun updateExtensionConfiguration(
        extensionId: String,
        newName: String? = null,
        matchPatternsRaw: String? = null,
        runAt: String? = null,
        customJs: String? = null,
        customCss: String? = null
    ): BrowserExtension? {
        val current = _extensionsFlow.value.find { it.id == extensionId } ?: return null
        val cleanName = newName?.trim()?.takeIf { it.isNotBlank() } ?: current.name
        val parsedMatches = matchPatternsRaw
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.ifEmpty { null }
            ?: current.hostPermissions.ifEmpty { listOf("<all_urls>") }
        val resolvedRunAt = runAt?.trim()?.takeIf {
            it in setOf("document_start", "document_end", "document_idle")
        } ?: current.contentScripts.firstOrNull()?.runAt ?: "document_end"

        val baseRules = if (current.contentScripts.isNotEmpty()) {
            current.contentScripts.mapIndexed { idx, rule ->
                if (idx == 0) {
                    rule.copy(
                        matches = parsedMatches,
                        runAt = resolvedRunAt,
                        inlineJs = customJs ?: rule.inlineJs,
                        inlineCss = customCss ?: rule.inlineCss
                    )
                } else {
                    rule.copy(matches = parsedMatches, runAt = resolvedRunAt)
                }
            }
        } else {
            listOf(
                ExtensionContentScriptRule(
                    matches = parsedMatches,
                    inlineJs = customJs.orEmpty(),
                    inlineCss = customCss.orEmpty(),
                    runAt = resolvedRunAt,
                    allFrames = true
                )
            )
        }

        // Persist custom JS/CSS to disk if custom extension directory exists
        if (current.installDirPath.isNotBlank()) {
            val dir = File(current.installDirPath)
            if (dir.exists() && !current.isBuiltIn) {
                if (customJs != null && current.kind == ExtensionKind.USERSCRIPT) {
                    val f = File(dir, current.contentScripts.firstOrNull()?.jsFiles?.firstOrNull() ?: "content.js")
                    runCatching { f.writeText(customJs, Charsets.UTF_8) }
                }
                if (customCss != null && current.kind == ExtensionKind.USERSTYLE) {
                    val f = File(dir, current.contentScripts.firstOrNull()?.cssFiles?.firstOrNull() ?: "style.css")
                    runCatching { f.writeText(customCss, Charsets.UTF_8) }
                }
            }
        }

        val updatedExt = current.copy(
            name = cleanName,
            hostPermissions = parsedMatches,
            contentScripts = baseRules
        )

        val updatedList = _extensionsFlow.value.map { if (it.id == extensionId) updatedExt else it }
        _extensionsFlow.value = updatedList
        persistExtensions(updatedList)
        return updatedExt
    }

    /**
     * Creates and installs a custom Userscript (JS) or Userstyle (CSS) directly from the UI or tests.
     */
    fun createCustomExtension(
        name: String,
        description: String,
        matchPattern: String,
        jsCode: String,
        cssCode: String,
        runAt: String = "document_end"
    ): BrowserExtension {
        val cleanName = name.trim().ifBlank { "Custom Extension" }
        val extId = "custom_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val extDir = File(extensionsRootDir, extId).apply { mkdirs() }

        val jsFiles = mutableListOf<String>()
        val cssFiles = mutableListOf<String>()
        if (jsCode.isNotBlank()) {
            File(extDir, "content.js").writeText(jsCode, Charsets.UTF_8)
            jsFiles.add("content.js")
        }
        if (cssCode.isNotBlank()) {
            File(extDir, "style.css").writeText(cssCode, Charsets.UTF_8)
            cssFiles.add("style.css")
        }

        val kind = when {
            jsCode.isNotBlank() && cssCode.isNotBlank() -> ExtensionKind.WEB_EXTENSION_MV3
            cssCode.isNotBlank() -> ExtensionKind.USERSTYLE
            else -> ExtensionKind.USERSCRIPT
        }

        val patterns = matchPattern.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("<all_urls>") }

        val extension = BrowserExtension(
            id = extId,
            name = cleanName,
            version = "1.0.0",
            description = description.trim().ifBlank { "Custom browser extension script/style" },
            kind = kind,
            enabled = true,
            isBuiltIn = false,
            sourceFileName = "$cleanName.ext",
            installDirPath = extDir.absolutePath,
            permissions = listOf("storage", "activeTab", "scripting"),
            hostPermissions = patterns,
            contentScripts = listOf(
                ExtensionContentScriptRule(
                    matches = patterns,
                    jsFiles = jsFiles,
                    cssFiles = cssFiles,
                    inlineJs = jsCode,
                    inlineCss = cssCode,
                    runAt = runAt.ifBlank { "document_end" },
                    allFrames = true
                )
            )
        )

        val updated = listOf(extension) + _extensionsFlow.value
        _extensionsFlow.value = updated
        persistExtensions(updated)
        return extension
    }

    /**
     * Installs a Chrome Web Store extension from a Store detail URL, 32-character Extension ID,
     * `clients2.google.com/service/update2/crx` URL, or direct `.crx`/`.zip`/`.user.js` URL.
     */
    fun installFromChromeWebStore(input: String, titleHint: String? = null): Result<BrowserExtension> {
        return runCatching {
            val trimmed = input.trim()
            require(trimmed.isNotBlank()) { "Enter a Chrome Web Store URL, Extension ID, or .crx link." }
            val storeId = extractChromeWebStoreId(trimmed)
            val candidateUrls = if (storeId != null) {
                listOf(
                    buildChromeWebStoreCrxDownloadUrl(storeId, prodVersion = "134.0.6998.165", acceptFormat = "crx2,crx3"),
                    buildChromeWebStoreCrxDownloadUrl(storeId, prodVersion = "126.0.6478.126", acceptFormat = "crx3")
                )
            } else if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                listOf(trimmed)
            } else {
                throw IllegalArgumentException(
                    "Invalid Chrome Web Store URL or 32-character Extension ID."
                )
            }

            var lastError: Throwable? = null
            for (downloadUrl in candidateUrls) {
                val attempt = downloadAndInstallFromUrl(
                    url = downloadUrl,
                    titleHint = titleHint,
                    storeIdOverride = storeId
                )
                if (attempt.isSuccess) {
                    return@runCatching attempt.getOrThrow()
                }
                lastError = attempt.exceptionOrNull()
            }
            throw (lastError ?: IllegalStateException("Unable to download Chrome Web Store package."))
        }
    }

    fun downloadAndInstallFromUrl(
        url: String,
        titleHint: String? = null,
        storeIdOverride: String? = null
    ): Result<BrowserExtension> {
        return runCatching {
            val parsedStoreId = storeIdOverride ?: extractChromeWebStoreId(url)
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.165 Safari/537.36"
                )
                .header("Accept", "application/x-chrome-extension, application/zip, */*")
                .build()

            crossOriginHttpClient.newCall(request).execute().use { response ->
                if (response.code == 204) {
                    throw IllegalStateException("Chrome Web Store returned no package (HTTP 204) for this extension.")
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("Download failed with HTTP ${response.code}.")
                }
                val bytes = response.body?.bytes()
                    ?: throw IllegalStateException("Empty response body from extension server.")
                if (bytes.isEmpty()) {
                    throw IllegalStateException("Downloaded extension package is empty.")
                }
                val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.US)
                val looksLikeHtml = contentType.contains("text/html") &&
                    bytes.take(256).toByteArray().toString(Charsets.UTF_8).trimStart()
                        .let { it.startsWith("<!doctype", true) || it.startsWith("<html", true) }
                if (looksLikeHtml) {
                    throw IllegalStateException("Server returned a webpage instead of an extension package.")
                }

                val cdName = response.header("Content-Disposition")
                    ?.let { cd -> Regex("""filename\*?=(?:UTF-8''|")?([^";\r\n]+)""", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.getOrNull(1) }
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                val fallbackName = when {
                    !cdName.isNullOrBlank() -> cdName
                    url.substringBefore('?').substringAfterLast('/').contains('.') ->
                        url.substringBefore('?').substringAfterLast('/')
                    parsedStoreId != null -> "$parsedStoreId.crx"
                    else -> "extension.crx"
                }

                val cleanTitleHint = titleHint
                    ?.replace(Regex("""\s*[-–|]\s*Chrome Web Store.*$""", RegexOption.IGNORE_CASE), "")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                importExtensionFromBytes(
                    fileName = fallbackName,
                    rawBytes = bytes,
                    storeIdOverride = parsedStoreId,
                    titleHint = cleanTitleHint
                ).getOrThrow()
            }
        }
    }

    /**
     * Imports an extension package, script, or unpacked `file://` directory from a Uri.
     * Supports `.zip`, `.crx`, `.xpi`, `.user.js`, `.js`, `.css`, `manifest.json`, and `file://` directories.
     */
    fun importExtensionFromUri(uri: Uri): Result<BrowserExtension> {
        return runCatching {
            if (uri.scheme.equals("file", ignoreCase = true) && !uri.path.isNullOrBlank()) {
                val localFile = File(uri.path!!)
                if (localFile.isDirectory) {
                    return@runCatching importUnpackedExtensionFromDirectory(localFile).getOrThrow()
                }
                if (localFile.isFile && localFile.name.equals("manifest.json", ignoreCase = true)) {
                    val parentDir = localFile.parentFile
                    if (parentDir != null && parentDir.isDirectory) {
                        return@runCatching importUnpackedExtensionFromDirectory(
                            sourceDir = parentDir,
                            sourceLabel = parentDir.name.ifBlank { "unpacked-extension" }
                        ).getOrThrow()
                    }
                }
            }
            val displayName = resolveDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "extension.zip"
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Unable to read selected extension file from storage.")
            input.use {
                importExtensionFromStream(displayName, it).getOrThrow()
            }
        }
    }

    /**
     * Imports an unpacked browser extension from a SAF directory tree Uri (`OpenDocumentTree`).
     * Recursively copies all extension files (`manifest.json`, `_locales`, scripts, styles, icons, rules)
     * into isolated cache storage with path-traversal protection and storage cleanup.
     */
    fun importUnpackedExtensionFromTreeUri(treeUri: Uri): Result<BrowserExtension> {
        return runCatching {
            if (treeUri.scheme.equals("file", ignoreCase = true) && !treeUri.path.isNullOrBlank()) {
                return@runCatching importUnpackedExtensionFromDirectory(File(treeUri.path!!)).getOrThrow()
            }

            val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Selected item is not a valid extension folder.")

            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            val folderDisplayName = resolveTreeDocumentDisplayName(rootDocUri)
                ?: rootDocId.substringAfterLast(':').substringAfterLast('/').ifBlank { "unpacked-extension" }

            val extId = "ext_" + UUID.randomUUID().toString().replace("-", "").take(14)
            extensionsRootDir.mkdirs()
            val extractRoot = File(extensionsRootDir, extId).apply { mkdirs() }
            val canonicalExtractRoot = runCatching { extractRoot.canonicalPath }.getOrNull() ?: extractRoot.absolutePath

            var copiedCount = 0
            var totalBytesCopied = 0L
            val maxTotalUncompressedBytes = 384L * 1024L * 1024L
            val buf = ByteArray(32 * 1024)

            fun copyTreeDirectory(parentDocId: String, relDirPrefix: String, depth: Int) {
                if (depth > 16) return
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )
                val childEntries = mutableListOf<Triple<String, String, String>>()
                context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext()) {
                        val childId = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else ""
                        val childName = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                        val childMime = if (mimeIdx >= 0) cursor.getString(mimeIdx).orEmpty() else ""
                        if (childId.isNotBlank() && childName.isNotBlank()) {
                            childEntries.add(Triple(childId, childName, childMime))
                        }
                    }
                } ?: throw IllegalStateException("Unable to list files inside the selected folder.")

                for ((childId, rawName, childMime) in childEntries) {
                    val safeSegment = rawName.replace('\\', '/').trim('/')
                    if (safeSegment.isBlank() || safeSegment == "." || safeSegment == ".." || safeSegment.contains('/')) {
                        continue
                    }
                    val relPath = if (relDirPrefix.isBlank()) safeSegment else "$relDirPrefix/$safeSegment"
                    val isDir = childMime == DocumentsContract.Document.MIME_TYPE_DIR
                    if (isDir) {
                        if (shouldSkipUnnecessaryArchiveEntry("$relPath/")) continue
                        val targetDir = File(extractRoot, relPath)
                        val canonicalDir = runCatching { targetDir.canonicalPath }.getOrNull() ?: targetDir.absolutePath
                        if (canonicalDir == canonicalExtractRoot || canonicalDir.startsWith(canonicalExtractRoot + File.separator)) {
                            targetDir.mkdirs()
                            copyTreeDirectory(childId, relPath, depth + 1)
                        }
                    } else {
                        if (shouldSkipUnnecessaryArchiveEntry(relPath) ||
                            com.example.data.security.MalwareProtectionEngine.isDisallowedExtensionBinaryName(relPath)
                        ) continue
                        val outFile = File(extractRoot, relPath)
                        val canonicalOut = runCatching { outFile.canonicalPath }.getOrNull() ?: outFile.absolutePath
                        if (!canonicalOut.startsWith(canonicalExtractRoot + File.separator)) continue
                        if (copiedCount >= 6000) {
                            throw IllegalArgumentException("Extension package exceeds 6,000-file safety limit.")
                        }
                        outFile.parentFile?.mkdirs()
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        val inputStream = context.contentResolver.openInputStream(fileUri)
                            ?: throw IllegalStateException("Unable to read '$relPath' from extension folder.")
                        inputStream.use { input ->
                            outFile.outputStream().use { out ->
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    totalBytesCopied += read
                                    if (totalBytesCopied > maxTotalUncompressedBytes) {
                                        throw IllegalArgumentException("Unpacked extension folder exceeds 384MB size limit.")
                                    }
                                    out.write(buf, 0, read)
                                }
                            }
                        }
                        copiedCount++
                    }
                }
            }

            try {
                copyTreeDirectory(rootDocId, "", 0)
                if (copiedCount == 0) {
                    throw IllegalArgumentException("Selected folder contains no extension files.")
                }
                val parsed = finalizeExtractedExtensionDirectory(
                    extId = extId,
                    sourceFileName = folderDisplayName,
                    extractRoot = extractRoot,
                    titleHint = folderDisplayName
                )
                registerInstalledExtension(parsed)
            } catch (t: Throwable) {
                runCatching { extractRoot.deleteRecursively() }
                throw t
            }
        }
    }

    /**
     * Imports an unpacked browser extension directly from a local filesystem directory.
     */
    fun importUnpackedExtensionFromDirectory(
        sourceDir: File,
        sourceLabel: String = sourceDir.name.ifBlank { "unpacked-extension" },
        titleHint: String? = null
    ): Result<BrowserExtension> {
        return runCatching {
            require(sourceDir.exists() && sourceDir.isDirectory) {
                "Selected path is not an accessible extension folder."
            }

            val extId = "ext_" + UUID.randomUUID().toString().replace("-", "").take(14)
            extensionsRootDir.mkdirs()
            val extractRoot = File(extensionsRootDir, extId).apply { mkdirs() }
            val canonicalExtractRoot = runCatching { extractRoot.canonicalPath }.getOrNull() ?: extractRoot.absolutePath
            val canonicalSourceRoot = runCatching { sourceDir.canonicalPath }.getOrNull() ?: sourceDir.absolutePath

            var copiedCount = 0
            var totalBytesCopied = 0L
            val maxTotalUncompressedBytes = 384L * 1024L * 1024L
            val buf = ByteArray(32 * 1024)

            try {
                sourceDir.walkTopDown()
                    .onEnter { dir ->
                        if (dir == sourceDir) return@onEnter true
                        val relDir = dir.relativeTo(sourceDir).path.replace('\\', '/')
                        val canonicalDir = runCatching { dir.canonicalPath }.getOrNull() ?: dir.absolutePath
                        canonicalDir.startsWith(canonicalSourceRoot) &&
                            !shouldSkipUnnecessaryArchiveEntry("$relDir/")
                    }
                    .filter { it.isFile }
                    .forEach { srcFile ->
                        val relPath = srcFile.relativeTo(sourceDir).path
                            .replace('\\', '/')
                            .split('/')
                            .filter { it.isNotBlank() && it != "." && it != ".." }
                            .joinToString("/")
                        if (relPath.isBlank() ||
                            shouldSkipUnnecessaryArchiveEntry(relPath) ||
                            com.example.data.security.MalwareProtectionEngine.isDisallowedExtensionBinaryName(relPath)
                        ) return@forEach

                        val outFile = File(extractRoot, relPath)
                        val canonicalOut = runCatching { outFile.canonicalPath }.getOrNull() ?: outFile.absolutePath
                        if (!canonicalOut.startsWith(canonicalExtractRoot + File.separator)) return@forEach
                        if (copiedCount >= 6000) {
                            throw IllegalArgumentException("Extension package exceeds 6,000-file safety limit.")
                        }

                        outFile.parentFile?.mkdirs()
                        srcFile.inputStream().buffered(32 * 1024).use { input ->
                            outFile.outputStream().use { out ->
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    totalBytesCopied += read
                                    if (totalBytesCopied > maxTotalUncompressedBytes) {
                                        throw IllegalArgumentException("Unpacked extension folder exceeds 384MB size limit.")
                                    }
                                    out.write(buf, 0, read)
                                }
                            }
                        }
                        copiedCount++
                    }

                if (copiedCount == 0) {
                    throw IllegalArgumentException("Selected folder contains no valid extension files.")
                }

                val cleanSourceLabel = sourceLabel.trim().ifBlank { "unpacked-extension" }
                val parsed = finalizeExtractedExtensionDirectory(
                    extId = extId,
                    sourceFileName = cleanSourceLabel,
                    extractRoot = extractRoot,
                    titleHint = titleHint ?: cleanSourceLabel
                )
                registerInstalledExtension(parsed)
            } catch (t: Throwable) {
                runCatching { extractRoot.deleteRecursively() }
                throw t
            }
        }
    }

    fun importExtensionFromBytes(
        fileName: String,
        rawBytes: ByteArray,
        storeIdOverride: String? = null,
        titleHint: String? = null
    ): Result<BrowserExtension> {
        return importExtensionFromStream(fileName, ByteArrayInputStream(rawBytes), storeIdOverride, titleHint)
    }

    fun importExtensionFromStream(
        fileName: String,
        inputStream: InputStream,
        storeIdOverride: String? = null,
        titleHint: String? = null
    ): Result<BrowserExtension> {
        return runCatching {
            tempStagingDir.mkdirs()
            val tempFile = File(tempStagingDir, "temp_ext_${UUID.randomUUID()}.tmp")
            try {
                tempFile.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var read: Int
                    var totalRead = 0L
                    while (inputStream.read(buf).also { read = it } != -1) {
                        totalRead += read
                        if (totalRead > 256L * 1024L * 1024L) {
                            throw IllegalArgumentException("Extension package exceeds 256MB compressed size limit.")
                        }
                        out.write(buf, 0, read)
                    }
                }
                require(tempFile.length() > 0) { "Selected extension file is empty." }

                val headerBytes = ByteArray(65536.coerceAtMost(tempFile.length().toInt()))
                tempFile.inputStream().use { it.read(headerBytes) }

                val lowerName = fileName.lowercase(Locale.US).trim()
                val zipOffset = findCrxOrZipPayloadOffset(headerBytes)
                val isArchive = zipOffset >= 0 && (
                    lowerName.endsWith(".zip") ||
                        lowerName.endsWith(".crx") ||
                        lowerName.endsWith(".xpi") ||
                        zipOffset == 0 ||
                        isCrxMagic(headerBytes)
                    )

                val resolvedStoreId = storeIdOverride?.trim()?.lowercase(Locale.US)
                    ?.takeIf { CWS_ID_REGEX.matches(it) }
                    ?: fileName.substringBeforeLast('.').trim().lowercase(Locale.US)
                        .takeIf { CWS_ID_REGEX.matches(it) }

                val parsedExtension = when {
                    isArchive -> installArchiveExtensionFromFile(fileName, tempFile, zipOffset, titleHint)
                    lowerName.endsWith(".css") -> installUserStyleExtension(fileName, tempFile.readText(Charsets.UTF_8))
                    lowerName.endsWith(".json") -> installDirectManifestJsonExtension(fileName, tempFile.readText(Charsets.UTF_8))
                    else -> {
                        val text = tempFile.readText(Charsets.UTF_8)
                        val trimmed = text.trimStart('\uFEFF').trimStart()
                        if (trimmed.startsWith("{") && trimmed.contains("\"manifest_version\"")) {
                            installDirectManifestJsonExtension(fileName, text)
                        } else if (trimmed.startsWith("/* ==UserStyle==", ignoreCase = true)) {
                            installUserStyleExtension(fileName, text)
                        } else {
                            installUserScriptExtension(fileName, text)
                        }
                    }
                }

                val installed = registerInstalledExtension(
                    parsedExtension = parsedExtension,
                    resolvedStoreId = resolvedStoreId
                )
                installed
            } finally {
                runCatching { tempFile.delete() }
            }
        }
    }

    private fun registerInstalledExtension(
        parsedExtension: BrowserExtension,
        resolvedStoreId: String? = null
    ): BrowserExtension {
        val installed = parsedExtension.copy(
            storeId = resolvedStoreId ?: parsedExtension.storeId
        )

        val toReplace = _extensionsFlow.value.filter { existing ->
            !existing.isBuiltIn && (
                (!installed.storeId.isNullOrBlank() && existing.storeId.equals(installed.storeId, ignoreCase = true)) ||
                    (existing.name.equals(installed.name, ignoreCase = true) && existing.sourceFileName == installed.sourceFileName)
                )
        }
        toReplace.forEach { old ->
            if (old.installDirPath.isNotBlank() && old.installDirPath != installed.installDirPath) {
                runCatching { File(old.installDirPath).deleteRecursively() }
                val rootContainer = File(extensionsRootDir, old.id)
                if (rootContainer.exists() && rootContainer.absolutePath != installed.installDirPath) {
                    runCatching { rootContainer.deleteRecursively() }
                }
            }
        }
        val replaceIds = toReplace.map { it.id }.toSet()
        val filtered = _extensionsFlow.value.filterNot { it.id in replaceIds }
        val updated = listOf(installed) + filtered
        _extensionsFlow.value = updated
        persistExtensions(updated)
        pruneOrphanedExtensionDirectories()
        return installed
    }

    private fun shouldSkipUnnecessaryArchiveEntry(safeRelPath: String): Boolean {
        val lower = safeRelPath.lowercase(Locale.US)
        // Skip source maps, OS metadata, IDE metadata, and git metadata that bloat storage on big extensions
        if (lower.endsWith(".map") ||
            lower.endsWith(".ts") ||
            lower.endsWith(".md") ||
            lower == "__macosx" ||
            lower.startsWith("__macosx/") ||
            lower.contains("/__macosx/") ||
            lower == ".git" ||
            lower.startsWith(".git/") ||
            lower.contains("/.git/") ||
            lower == ".idea" ||
            lower.startsWith(".idea/") ||
            lower == ".vscode" ||
            lower.startsWith(".vscode/") ||
            lower.endsWith(".ds_store")
        ) {
            return true
        }
        return false
    }

    private fun installArchiveExtensionFromFile(
        fileName: String,
        archiveFile: File,
        zipStartOffset: Int,
        titleHint: String? = null
    ): BrowserExtension {
        val extId = "ext_" + UUID.randomUUID().toString().replace("-", "").take(14)
        extensionsRootDir.mkdirs()
        val extractRoot = File(extensionsRootDir, extId).apply { mkdirs() }
        val canonicalExtractRoot = runCatching { extractRoot.canonicalPath }.getOrNull() ?: extractRoot.absolutePath

        var extractedCount = 0
        var totalBytesExtracted = 0L
        val maxTotalUncompressedBytes = 384L * 1024L * 1024L // 384 MB safety ceiling for huge unpacked extensions

        archiveFile.inputStream().buffered(32 * 1024).use { fileInput ->
            if (zipStartOffset > 0) {
                var skipped = 0L
                while (skipped < zipStartOffset) {
                    val s = fileInput.skip(zipStartOffset - skipped)
                    if (s <= 0) break
                    skipped += s
                }
            }
            ZipInputStream(fileInput).use { zis ->
                val buf = ByteArray(32 * 1024)
                var entry = runCatching { zis.nextEntry }.getOrNull()
                while (entry != null) {
                    val safeRelPath = entry.name
                        .replace('\\', '/')
                        .split('/')
                        .filter { it.isNotBlank() && it != "." && it != ".." }
                        .joinToString("/")

                    if (safeRelPath.isNotBlank() &&
                        !shouldSkipUnnecessaryArchiveEntry(safeRelPath) &&
                        !com.example.data.security.MalwareProtectionEngine.isDisallowedExtensionBinaryName(safeRelPath)
                    ) {
                        val outFile = File(extractRoot, safeRelPath)
                        val canonicalOut = runCatching { outFile.canonicalPath }.getOrNull() ?: outFile.absolutePath
                        if (canonicalOut.startsWith(canonicalExtractRoot + File.separator)) {
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                if (extractedCount >= 6000) {
                                    extractRoot.deleteRecursively()
                                    throw IllegalArgumentException("Extension archive exceeds 6,000-file safety limit.")
                                }
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { out ->
                                    var read: Int
                                    while (zis.read(buf).also { read = it } != -1) {
                                        totalBytesExtracted += read
                                        if (totalBytesExtracted > maxTotalUncompressedBytes) {
                                            extractRoot.deleteRecursively()
                                            throw IllegalArgumentException("Extension archive exceeds 384MB uncompressed safety limit.")
                                        }
                                        out.write(buf, 0, read)
                                    }
                                }
                                extractedCount++
                            }
                        }
                    }
                    runCatching { zis.closeEntry() }
                    entry = runCatching { zis.nextEntry }.getOrNull()
                }
            }
        }

        if (extractedCount == 0) {
            extractRoot.deleteRecursively()
            throw IllegalArgumentException("Archive contains no valid extension files.")
        }

        return finalizeExtractedExtensionDirectory(
            extId = extId,
            sourceFileName = fileName,
            extractRoot = extractRoot,
            titleHint = titleHint
        )
    }

    private fun finalizeExtractedExtensionDirectory(
        extId: String,
        sourceFileName: String,
        extractRoot: File,
        titleHint: String? = null
    ): BrowserExtension {
        val scanResult = com.example.data.security.MalwareProtectionEngine.scanExtensionDirectory(extractRoot)
        if (!scanResult.isClean) {
            com.example.data.security.MalwareProtectionEngine.recordThreatEvent(
                context = context,
                category = scanResult.threatCategory,
                severity = com.example.data.security.SecuritySeverity.CRITICAL,
                target = sourceFileName,
                reason = scanResult.reason,
                blocked = true
            )
            extractRoot.deleteRecursively()
            throw SecurityException(scanResult.reason)
        }
        // Find manifest.json (at root or inside a nested directory)
        val manifestFile = findManifestFile(extractRoot)
        if (manifestFile != null) {
            val baseDir = manifestFile.parentFile ?: extractRoot
            return parseWebExtensionManifest(
                extId = extId,
                sourceFileName = sourceFileName,
                baseDir = baseDir,
                manifestJsonText = manifestFile.readText(Charsets.UTF_8),
                titleHint = titleHint
            )
        }

        // Fallback: if folder/archive contains .user.js / .js / .css files without manifest.json, bundle them automatically!
        val allFiles = extractRoot.walkTopDown().filter { it.isFile }.toList()
        val jsFiles = allFiles.filter { it.name.endsWith(".js", ignoreCase = true) }
            .map { it.relativeTo(extractRoot).path.replace('\\', '/') }
        val cssFiles = allFiles.filter { it.name.endsWith(".css", ignoreCase = true) }
            .map { it.relativeTo(extractRoot).path.replace('\\', '/') }

        if (jsFiles.isEmpty() && cssFiles.isEmpty()) {
            extractRoot.deleteRecursively()
            throw IllegalArgumentException("No manifest.json, .js, or .css found in extension package.")
        }

        // Check if any .js file has a ==UserScript== header with @name
        var scriptExtractedName: String? = null
        val firstJsFile = allFiles.firstOrNull { it.name.endsWith(".js", ignoreCase = true) }
        if (firstJsFile != null && firstJsFile.length() < 2 * 1024 * 1024L) {
            val headText = runCatching { firstJsFile.readText(Charsets.UTF_8).take(4096) }.getOrNull().orEmpty()
            val nameMatch = Regex("""^//\s*@name\s+(.+)$""", RegexOption.MULTILINE).find(headText)
            if (nameMatch != null) {
                scriptExtractedName = nameMatch.groupValues[1].trim().takeIf { it.isNotBlank() }
            }
        }

        val cleanTitle = scriptExtractedName
            ?: cleanFallbackExtensionTitle(titleHint, sourceFileName)

        return BrowserExtension(
            id = extId,
            name = cleanTitle,
            version = "1.0.0",
            description = "Imported unpacked extension with ${jsFiles.size} script(s) and ${cssFiles.size} style(s)",
            kind = ExtensionKind.WEB_EXTENSION_MV3,
            enabled = true,
            isBuiltIn = false,
            sourceFileName = sourceFileName,
            installDirPath = extractRoot.absolutePath,
            permissions = listOf("storage", "activeTab", "scripting"),
            hostPermissions = listOf("<all_urls>"),
            contentScripts = listOf(
                ExtensionContentScriptRule(
                    matches = listOf("<all_urls>"),
                    jsFiles = jsFiles,
                    cssFiles = cssFiles,
                    runAt = "document_end",
                    allFrames = true
                )
            )
        )
    }

    private fun cleanFallbackExtensionTitle(titleHint: String?, sourceFileName: String): String {
        val cleanedHint = titleHint
            ?.replace(Regex("""\s*[-–|]\s*Chrome Web Store.*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !CWS_ID_REGEX.matches(it) }
        if (cleanedHint != null) return cleanedHint

        val base = sourceFileName
            .removeSuffix(".user.js")
            .removeSuffix(".user.css")
            .substringBeforeLast('.')
            .replace(Regex("""\(\d+\)$"""), "")
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        return if (base.isNotBlank() && !CWS_ID_REGEX.matches(base)) {
            base
        } else {
            "Web Extension"
        }
    }

    private fun parseWebExtensionManifest(
        extId: String,
        sourceFileName: String,
        baseDir: File,
        manifestJsonText: String,
        titleHint: String? = null
    ): BrowserExtension {
        val sanitizedJson = manifestJsonText.trimStart('\uFEFF').trim()
        val manifest = JSONObject(sanitizedJson)
        val defaultLocale = manifest.optString("default_locale", "en").ifBlank { "en" }
        val i18nMap = loadLocaleMessages(baseDir, defaultLocale).toMutableMap()
        i18nMap["@@extension_id"] = extId
        i18nMap["@@ui_locale"] = defaultLocale
        i18nMap["@@bidi_dir"] = "ltr"

        fun resolveCleanLabel(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val resolved = resolveI18nPlaceholders(raw.trim(), i18nMap).trim()
            return resolved.takeIf { it.isNotBlank() && !it.contains("__MSG_") }
        }

        val actionObj = manifest.optJSONObject("action")
            ?: manifest.optJSONObject("browser_action")
            ?: manifest.optJSONObject("page_action")

        val resolvedName = resolveCleanLabel(manifest.optString("name"))
            ?: resolveCleanLabel(manifest.optString("short_name"))
            ?: resolveCleanLabel(actionObj?.optString("default_title"))
            ?: i18nMap["extname"]?.takeIf { it.isNotBlank() && !it.contains("__MSG_") }
            ?: i18nMap["appname"]?.takeIf { it.isNotBlank() && !it.contains("__MSG_") }
            ?: i18nMap["extensionname"]?.takeIf { it.isNotBlank() && !it.contains("__MSG_") }
            ?: i18nMap["ext_name"]?.takeIf { it.isNotBlank() && !it.contains("__MSG_") }
            ?: i18nMap["app_name"]?.takeIf { it.isNotBlank() && !it.contains("__MSG_") }
            ?: cleanFallbackExtensionTitle(titleHint, sourceFileName)

        val version = manifest.optString("version_name", "").takeIf { it.isNotBlank() }
            ?: manifest.optString("version", "1.0.0").ifBlank { "1.0.0" }
        val rawDesc = manifest.optString("description", "")
        val resolvedDesc = resolveCleanLabel(rawDesc)
            ?: i18nMap["extdesc"]?.takeIf { it.isNotBlank() }
            ?: i18nMap["appdesc"]?.takeIf { it.isNotBlank() }
            ?: i18nMap["description"]?.takeIf { it.isNotBlank() }
            ?: "WebExtension package ($resolvedName)"

        val manifestVersion = manifest.optInt("manifest_version", 3)
        val kind = if (manifestVersion == 2) ExtensionKind.WEB_EXTENSION_MV2 else ExtensionKind.WEB_EXTENSION_MV3

        val permissions = mutableListOf<String>()
        manifest.optJSONArray("permissions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optString(i, "").trim()
                if (p.isNotBlank()) permissions.add(p)
            }
        }
        manifest.optJSONArray("optional_permissions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optString(i, "").trim()
                if (p.isNotBlank() && p !in permissions) permissions.add(p)
            }
        }

        val hostPermissions = mutableListOf<String>()
        manifest.optJSONArray("host_permissions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val hp = arr.optString(i, "").trim()
                if (hp.isNotBlank()) hostPermissions.add(hp)
            }
        }
        // In MV2, host patterns can be inside `permissions`
        permissions.filter { it.contains("://") || it == "<all_urls>" }.forEach {
            if (!hostPermissions.contains(it)) hostPermissions.add(it)
        }
        if (hostPermissions.isEmpty()) {
            hostPermissions.add("<all_urls>")
        }

        // Parse popup (MV3 action / MV2 browser_action / page_action), falling back to options_ui / options_page / side_panel
        fun resolveExistingPagePath(rawPath: String?): String? {
            val cleaned = rawPath?.trim()
                ?.removePrefix("./")
                ?.removePrefix("/")
                ?.takeIf { it.isNotBlank() } ?: return null
            val filePart = cleaned.substringBefore('?').substringBefore('#')
            if (filePart.isBlank()) return null
            if (File(baseDir, filePart).exists()) return cleaned
            // Case-insensitive or nested lookup
            val found = baseDir.walkTopDown().maxDepth(4).firstOrNull {
                it.isFile && it.relativeTo(baseDir).path.replace('\\', '/').equals(filePart, ignoreCase = true)
            } ?: baseDir.walkTopDown().maxDepth(3).firstOrNull {
                it.isFile && it.name.equals(File(filePart).name, ignoreCase = true)
            }
            if (found != null) {
                val suffix = cleaned.substringAfter(filePart, "")
                return found.relativeTo(baseDir).path.replace('\\', '/') + suffix
            }
            return null
        }

        val actionPopup = resolveExistingPagePath(
            manifest.optJSONObject("action")?.optString("default_popup")
                ?: manifest.optJSONObject("browser_action")?.optString("default_popup")
                ?: manifest.optJSONObject("page_action")?.optString("default_popup")
                ?: manifest.optJSONObject("side_panel")?.optString("default_path")
                ?: manifest.optJSONObject("sidebar_action")?.optString("default_panel")
        )
        val optionsUiObj = manifest.optJSONObject("options_ui")
        val optionsFallback = resolveExistingPagePath(
            optionsUiObj?.optString("page")
                ?: manifest.optString("options_page")
        )
        val optionsOpenInTab = optionsUiObj?.optBoolean("open_in_tab", false) == true
        val author = resolveCleanLabel(manifest.optString("author"))
            ?: resolveCleanLabel(manifest.optJSONObject("developer")?.optString("name"))
            ?: ""
        val homepageUrl = manifest.optString("homepage_url", "").trim()
        val genericHtmlFallback = if (actionPopup == null && optionsFallback == null) {
            val htmlCandidates = baseDir.walkTopDown().maxDepth(3).filter {
                it.isFile && (it.name.endsWith(".html", true) || it.name.endsWith(".htm", true)) &&
                    !it.name.equals("background.html", true) && !it.name.equals("bg.html", true) &&
                    !it.name.equals("worker.html", true) && !it.name.equals("offscreen.html", true)
            }.toList()
            val prioritized = htmlCandidates.firstOrNull {
                val n = it.name.lowercase(Locale.US)
                n.contains("popup") || n.contains("options") || n.contains("panel") ||
                    n.contains("dashboard") || n.contains("settings") || n.contains("index")
            } ?: htmlCandidates.firstOrNull()
            prioritized?.relativeTo(baseDir)?.path?.replace('\\', '/')
        } else {
            null
        }
        val popupPath = actionPopup ?: optionsFallback ?: genericHtmlFallback

        // Parse background script / service_worker / scripts array
        val bgObj = manifest.optJSONObject("background")
        val backgroundScriptPath = when {
            bgObj == null -> null
            bgObj.has("service_worker") -> bgObj.optString("service_worker", "").trim().removePrefix("/")
            bgObj.has("scripts") -> {
                val arr = bgObj.optJSONArray("scripts")
                if (arr != null) {
                    val paths = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val s = arr.optString(i, "").trim().removePrefix("/")
                        if (s.isNotBlank() && File(baseDir, s).exists()) paths.add(s)
                    }
                    paths.joinToString(",").takeIf { it.isNotBlank() }
                } else {
                    bgObj.optString("scripts", "").trim().removePrefix("/").takeIf { it.isNotBlank() && File(baseDir, it).exists() }
                }
            }
            else -> null
        }?.takeIf { it.isNotBlank() }

        // Parse content_scripts (including include_globs and exclude_globs)
        val contentRules = mutableListOf<ExtensionContentScriptRule>()
        manifest.optJSONArray("content_scripts")?.let { csArr ->
            for (i in 0 until csArr.length()) {
                val csObj = csArr.optJSONObject(i) ?: continue
                val matches = jsonStringArrayToList(csObj.optJSONArray("matches")).ifEmpty { listOf("<all_urls>") }
                val excludeMatches = jsonStringArrayToList(csObj.optJSONArray("exclude_matches"))
                val includeGlobs = jsonStringArrayToList(csObj.optJSONArray("include_globs"))
                val excludeGlobs = jsonStringArrayToList(csObj.optJSONArray("exclude_globs"))
                val jsFiles = jsonStringArrayToList(csObj.optJSONArray("js")).map { it.removePrefix("/") }
                val cssFiles = jsonStringArrayToList(csObj.optJSONArray("css")).map { it.removePrefix("/") }
                val runAt = csObj.optString("run_at", "document_end").ifBlank { "document_end" }
                val allFrames = csObj.optBoolean("all_frames", false)

                if (jsFiles.isNotEmpty() || cssFiles.isNotEmpty()) {
                    contentRules.add(
                        ExtensionContentScriptRule(
                            matches = matches,
                            excludeMatches = excludeMatches,
                            includeGlobs = includeGlobs,
                            excludeGlobs = excludeGlobs,
                            jsFiles = jsFiles,
                            cssFiles = cssFiles,
                            runAt = runAt,
                            allFrames = allFrames
                        )
                    )
                }
            }
        }

        // If an extension has no explicit content_scripts entry, check if it has root fallback scripts/css
        if (contentRules.isEmpty()) {
            val fallbackJs = listOf("content.js", "inject.js", "script.js", "main.js")
                .filter { File(baseDir, it).exists() }
            val fallbackCss = listOf("style.css", "content.css", "styles.css")
                .filter { File(baseDir, it).exists() }
            if (fallbackJs.isNotEmpty() || fallbackCss.isNotEmpty()) {
                contentRules.add(
                    ExtensionContentScriptRule(
                        matches = hostPermissions.ifEmpty { listOf("<all_urls>") },
                        jsFiles = fallbackJs,
                        cssFiles = fallbackCss,
                        runAt = "document_end"
                    )
                )
            }
        }

        // Parse declarative_net_request rules if present
        val netRules = mutableListOf<ExtensionNetRule>()
        manifest.optJSONObject("declarative_net_request")
            ?.optJSONArray("rule_resources")
            ?.let { resArr ->
                for (i in 0 until resArr.length()) {
                    val resObj = resArr.optJSONObject(i) ?: continue
                    val enabled = resObj.optBoolean("enabled", true)
                    val path = resObj.optString("path", "").trim().removePrefix("/")
                    if (enabled && path.isNotBlank()) {
                        val ruleFile = File(baseDir, path)
                        if (ruleFile.exists()) {
                            netRules.addAll(parseDeclarativeNetRulesFile(ruleFile))
                        }
                    }
                }
            }

        val resolvedManifestJson = JSONObject(manifest.toString()).apply {
            put("name", resolvedName)
            put("description", resolvedDesc)
        }.toString()

        val i18nMessagesJson = JSONObject().apply {
            i18nMap.forEach { (k, v) -> put(k, v) }
        }.toString()

        return BrowserExtension(
            id = extId,
            name = resolvedName,
            version = version,
            description = resolvedDesc,
            kind = kind,
            enabled = true,
            isBuiltIn = false,
            sourceFileName = sourceFileName,
            installDirPath = baseDir.absolutePath,
            popupPath = popupPath,
            backgroundScriptPath = backgroundScriptPath,
            i18nMessagesJson = i18nMessagesJson,
            manifestJson = resolvedManifestJson,
            permissions = permissions,
            hostPermissions = hostPermissions,
            contentScripts = contentRules,
            netRules = netRules,
            optionsPath = optionsFallback,
            optionsOpenInTab = optionsOpenInTab,
            author = author,
            homepageUrl = homepageUrl
        )
    }

    private fun installDirectManifestJsonExtension(fileName: String, manifestJsonText: String): BrowserExtension {
        val extId = "ext_" + UUID.randomUUID().toString().replace("-", "").take(14)
        val extDir = File(extensionsRootDir, extId).apply { mkdirs() }
        File(extDir, "manifest.json").writeText(manifestJsonText, Charsets.UTF_8)
        return parseWebExtensionManifest(extId, fileName, extDir, manifestJsonText)
    }

    private fun installUserScriptExtension(fileName: String, scriptContent: String): BrowserExtension {
        val scanResult = com.example.data.security.MalwareProtectionEngine.scanExtensionScript(fileName, scriptContent)
        if (!scanResult.isClean) {
            com.example.data.security.MalwareProtectionEngine.recordThreatEvent(
                context = context,
                category = scanResult.threatCategory,
                severity = com.example.data.security.SecuritySeverity.CRITICAL,
                target = fileName,
                reason = scanResult.reason,
                blocked = true
            )
            throw SecurityException(scanResult.reason)
        }
        val extId = "userscript_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val extDir = File(extensionsRootDir, extId).apply { mkdirs() }
        val scriptFile = File(extDir, "userscript.user.js")
        scriptFile.writeText(scriptContent, Charsets.UTF_8)

        var name = fileName.removeSuffix(".user.js").removeSuffix(".js")
            .replace('_', ' ').replace('-', ' ').trim().ifBlank { "Imported Userscript" }
        var version = "1.0.0"
        var description = "Userscript imported from local storage"
        val matches = mutableListOf<String>()
        val excludes = mutableListOf<String>()
        var runAt = "document_end"

        val metaBlockRegex = Regex("""//\s*==UserScript==([\s\S]*?)//\s*==/UserScript==""", RegexOption.IGNORE_CASE)
        val metaMatch = metaBlockRegex.find(scriptContent)
        if (metaMatch != null) {
            val lines = metaMatch.groupValues[1].lines()
            for (line in lines) {
                val m = Regex("""^//\s*@(\S+)\s+(.+)$""").find(line.trim()) ?: continue
                val key = m.groupValues[1].lowercase(Locale.US)
                val value = m.groupValues[2].trim()
                when (key) {
                    "name" -> if (value.isNotBlank()) name = value
                    "version" -> if (value.isNotBlank()) version = value
                    "description" -> if (value.isNotBlank()) description = value
                    "match", "include" -> if (value.isNotBlank()) matches.add(value)
                    "exclude" -> if (value.isNotBlank()) excludes.add(value)
                    "run-at", "run_at" -> {
                        runAt = when (value.lowercase(Locale.US)) {
                            "document-start", "document_start" -> "document_start"
                            "document-idle", "document_idle" -> "document_idle"
                            else -> "document_end"
                        }
                    }
                }
            }
        }
        if (matches.isEmpty()) {
            matches.add("<all_urls>")
        }

        return BrowserExtension(
            id = extId,
            name = name,
            version = version,
            description = description,
            kind = ExtensionKind.USERSCRIPT,
            enabled = true,
            isBuiltIn = false,
            sourceFileName = fileName,
            installDirPath = extDir.absolutePath,
            permissions = listOf("storage", "GM_getValue", "GM_setValue", "GM_xmlhttpRequest", "GM_addStyle"),
            hostPermissions = matches,
            contentScripts = listOf(
                ExtensionContentScriptRule(
                    matches = matches,
                    excludeMatches = excludes,
                    jsFiles = listOf(scriptFile.name),
                    inlineJs = scriptContent,
                    runAt = runAt,
                    allFrames = true
                )
            )
        )
    }

    private fun installUserStyleExtension(fileName: String, cssContent: String): BrowserExtension {
        val extId = "userstyle_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val extDir = File(extensionsRootDir, extId).apply { mkdirs() }
        val cssFile = File(extDir, "style.user.css")
        cssFile.writeText(cssContent, Charsets.UTF_8)

        var name = fileName.removeSuffix(".user.css").removeSuffix(".css")
            .replace('_', ' ').replace('-', ' ').trim().ifBlank { "Imported UserStyle" }
        var version = "1.0.0"
        var description = "Custom CSS UserStyle imported from local storage"

        val metaBlockRegex = Regex("""/\*\s*==UserStyle==([\s\S]*?)==/UserStyle==\s*\*/""", RegexOption.IGNORE_CASE)
        val metaMatch = metaBlockRegex.find(cssContent)
        if (metaMatch != null) {
            for (line in metaMatch.groupValues[1].lines()) {
                val m = Regex("""^@(\S+)\s+(.+)$""").find(line.trim()) ?: continue
                val key = m.groupValues[1].lowercase(Locale.US)
                val value = m.groupValues[2].trim()
                when (key) {
                    "name" -> if (value.isNotBlank()) name = value
                    "version" -> if (value.isNotBlank()) version = value
                    "description" -> if (value.isNotBlank()) description = value
                }
            }
        }

        return BrowserExtension(
            id = extId,
            name = name,
            version = version,
            description = description,
            kind = ExtensionKind.USERSTYLE,
            enabled = true,
            isBuiltIn = false,
            sourceFileName = fileName,
            installDirPath = extDir.absolutePath,
            permissions = listOf("styling"),
            hostPermissions = listOf("<all_urls>"),
            contentScripts = listOf(
                ExtensionContentScriptRule(
                    matches = listOf("<all_urls>"),
                    cssFiles = listOf(cssFile.name),
                    inlineCss = cssContent,
                    runAt = "document_start",
                    allFrames = true
                )
            )
        )
    }

    // Evaluates whether a given URL matches an extension's match patterns (<all_urls>, domain/port wildcards, globs).
    fun matchesUrlPattern(
        url: String,
        patterns: List<String>,
        excludePatterns: List<String> = emptyList(),
        includeGlobs: List<String> = emptyList(),
        excludeGlobs: List<String> = emptyList()
    ): Boolean {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return false
        }
        for (ex in excludePatterns) {
            if (singlePatternMatches(url, ex)) return false
        }
        for (exGlob in excludeGlobs) {
            if (globToRegex(exGlob).matches(url)) return false
        }
        if (patterns.isNotEmpty() && patterns.none { singlePatternMatches(url, it) }) {
            return false
        }
        if (includeGlobs.isNotEmpty() && includeGlobs.none { globToRegex(it).matches(url) }) {
            return false
        }
        return true
    }

    private fun singlePatternMatches(url: String, rawPattern: String): Boolean {
        val pattern = rawPattern.trim()
        if (pattern.isBlank() || pattern == "<all_urls>" || pattern == "*://*/*" || pattern == "*") {
            return true
        }
        val parsedUri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = parsedUri.scheme?.lowercase(Locale.US) ?: return false
        val host = parsedUri.host?.lowercase(Locale.US) ?: return false
        val port = parsedUri.port
        val fullPath = (parsedUri.encodedPath?.ifBlank { "/" } ?: "/") +
            (parsedUri.encodedQuery?.let { "?$it" } ?: "")

        // Standard WebExtension / Userscript pattern: <scheme>://<host[:port]><path>
        val matchPatternRegex = Regex("""^(\*|http\*|http|https)://([^/]+)(/.*)?$""", RegexOption.IGNORE_CASE)
        val m = matchPatternRegex.find(pattern)
        if (m != null) {
            val patScheme = m.groupValues[1].lowercase(Locale.US)
            val rawPatHostPort = m.groupValues[2].lowercase(Locale.US)
            val patPath = m.groupValues[3].ifBlank { "/*" }

            if (patScheme != "*" && patScheme != "http*" && patScheme != scheme) {
                return false
            }
            val patHost: String
            val patPort: String?
            if (rawPatHostPort.contains(':')) {
                patHost = rawPatHostPort.substringBefore(':')
                patPort = rawPatHostPort.substringAfter(':')
            } else {
                patHost = rawPatHostPort
                patPort = null
            }

            if (patPort != null && patPort != "*") {
                val expectedPort = patPort.toIntOrNull()
                val actualPort = if (port > 0) port else if (scheme == "https") 443 else 80
                if (expectedPort != null && actualPort != expectedPort) {
                    return false
                }
            }

            val hostMatched = when {
                patHost == "*" -> true
                patHost.startsWith("*.") -> {
                    val baseDomain = patHost.removePrefix("*.")
                    host == baseDomain || host.endsWith(".$baseDomain")
                }
                patHost.contains('*') -> globToRegex(patHost).matches(host)
                else -> host == patHost
            }
            if (!hostMatched) return false

            return globToRegex(patPath).matches(fullPath)
        }

        // Fallback glob match against full URL
        return globToRegex(pattern).matches(url)
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        for (ch in glob) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' -> sb.append("\\").append(ch)
                else -> sb.append(ch)
            }
        }
        sb.append("$")
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    /**
     * Builds a clean metadata JSON string representing the extension's `manifest.json` fields
     * for `chrome.runtime.getManifest()`.
     */
    private fun buildExtMetaJson(ext: BrowserExtension): String {
        val parsed = runCatching {
            if (ext.manifestJson.isNotBlank() && ext.manifestJson != "{}") JSONObject(ext.manifestJson) else null
        }.getOrNull() ?: JSONObject()
        parsed.put("id", ext.id)
        parsed.put("name", ext.name)
        parsed.put("version", ext.version)
        parsed.put("description", ext.description)
        if (!parsed.has("manifest_version")) {
            parsed.put("manifest_version", if (ext.kind == ExtensionKind.WEB_EXTENSION_MV2) 2 else 3)
        }
        if (!parsed.has("permissions") && ext.permissions.isNotEmpty()) {
            parsed.put("permissions", JSONArray(ext.permissions))
        }
        return parsed.toString()
    }

    /**
     * Safely reads an internal text asset inside [ext]'s install directory (e.g. for `chrome.scripting.executeScript({files})`).
     */
    fun readExtensionAssetText(extId: String, relPath: String): String? {
        val ext = _extensionsFlow.value.find { it.id == extId } ?: return null
        if (ext.installDirPath.isBlank() || relPath.isBlank()) return null
        val cleanRel = relPath.trim().removePrefix("/")
        val baseDir = File(ext.installDirPath)
        val target = File(baseDir, cleanRel)
        val canonicalBase = runCatching { baseDir.canonicalPath }.getOrNull() ?: return null
        val canonicalTarget = runCatching { target.canonicalPath }.getOrNull() ?: return null
        if (!canonicalTarget.startsWith(canonicalBase + File.separator) || !target.exists() || !target.isFile) return null
        return runCatching { target.readText(Charsets.UTF_8) }.getOrNull()
    }

    /**
     * Builds the JavaScript payload to inject into a WebView at `document_start` or `document_end`
     * for all currently enabled extensions whose match patterns match [url].
     */
    fun buildInjectionPayloadForUrl(url: String, phase: String): String {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return ""
        }
        if (isProtectedAuthUrl(url)) {
            return ""
        }
        val activeExtensions = _extensionsFlow.value.filter { it.enabled }
        if (activeExtensions.isEmpty()) return ""

        val isStartPhase = phase == "document_start"
        val sb = StringBuilder()

        // 1. Always ensure the global Dragon Browser WebExtension & Userscript API Polyfill is initialized
        sb.append(buildGlobalExtensionApiPolyfillJs())

        // 2. For each enabled extension, check matching contentScript rules for this phase
        var hasMatchingPayload = false
        for (ext in activeExtensions) {
            for ((ruleIndex, rule) in ext.contentScripts.withIndex()) {
                val ruleMatchesPhase = if (isStartPhase) {
                    rule.runAt.equals("document_start", ignoreCase = true)
                } else {
                    !rule.runAt.equals("document_start", ignoreCase = true)
                }
                // Note: CSS from document_start is also verified at document_end in case `<head>` was recreated
                val shouldInjectCssHere = ruleMatchesPhase || (!isStartPhase && rule.runAt.equals("document_start", ignoreCase = true))
                val shouldInjectJsHere = ruleMatchesPhase

                if (!shouldInjectCssHere && !shouldInjectJsHere) continue
                if (!matchesUrlPattern(url, rule.matches, rule.excludeMatches, rule.includeGlobs, rule.excludeGlobs)) continue

                val cssChunks = mutableListOf<String>()
                if (shouldInjectCssHere) {
                    if (rule.inlineCss.isNotBlank()) {
                        cssChunks.add(rule.inlineCss)
                    } else if (ext.installDirPath.isNotBlank()) {
                        val baseDir = File(ext.installDirPath)
                        for (cssRel in rule.cssFiles) {
                            val f = File(baseDir, cssRel)
                            if (f.exists() && f.isFile) {
                                runCatching { f.readText(Charsets.UTF_8) }.getOrNull()?.let {
                                    if (it.isNotBlank()) cssChunks.add(it)
                                }
                            }
                        }
                    }
                }

                val jsChunks = mutableListOf<String>()
                if (shouldInjectJsHere) {
                    if (rule.inlineJs.isNotBlank()) {
                        jsChunks.add(rule.inlineJs)
                    } else if (ext.installDirPath.isNotBlank()) {
                        val baseDir = File(ext.installDirPath)
                        for (jsRel in rule.jsFiles) {
                            val f = File(baseDir, jsRel)
                            if (f.exists() && f.isFile) {
                                runCatching { f.readText(Charsets.UTF_8) }.getOrNull()?.let {
                                    if (it.isNotBlank()) jsChunks.add(it)
                                }
                            }
                        }
                    }
                }

                if (cssChunks.isEmpty() && jsChunks.isEmpty()) continue
                hasMatchingPayload = true

                val styleId = "dragon-ext-css-${ext.id}-$ruleIndex"
                if (cssChunks.isNotEmpty()) {
                    val combinedCss = cssChunks.joinToString("\n")
                    val escapedCss = JSONObject.quote(combinedCss)
                    sb.append(
                        """
                        (function() {
                            try {
                                var styleId = "$styleId";
                                if (!document.getElementById(styleId)) {
                                    var st = document.createElement('style');
                                    st.id = styleId;
                                    st.setAttribute('data-dragon-extension', ${JSONObject.quote(ext.name)});
                                    st.textContent = $escapedCss;
                                    (document.head || document.documentElement).appendChild(st);
                                }
                            } catch (e) {}
                        })();
                        """.trimIndent()
                    ).append("\n")
                }

                if (jsChunks.isNotEmpty()) {
                    val guardKey = "__dragon_ext_ran_${ext.id}_${ruleIndex}_${phase}"
                    val initialKvJson = getExtensionStorageJson(ext.id)
                    val extMetaJson = buildExtMetaJson(ext)
                    val i18nJson = ext.i18nMessagesJson.ifBlank { "{}" }

                    for ((jsIdx, jsCode) in jsChunks.withIndex()) {
                        sb.append(
                            """
                            (function() {
                                try {
                                    var guard = "${guardKey}_${jsIdx}_" + location.href;
                                    if (window[guard]) return;
                                    window[guard] = true;
                                    var __extScope = window.__dragonCreateExtensionScope(${JSONObject.quote(ext.id)}, $extMetaJson, $initialKvJson, $i18nJson, null);
                                    var chrome = __extScope.chrome;
                                    var browser = __extScope.browser;
                                    var GM_info = __extScope.GM_info;
                                    var GM_getValue = __extScope.GM_getValue;
                                    var GM_setValue = __extScope.GM_setValue;
                                    var GM_deleteValue = __extScope.GM_deleteValue;
                                    var GM_listValues = __extScope.GM_listValues;
                                    var GM_addStyle = __extScope.GM_addStyle;
                                    var GM_xmlhttpRequest = __extScope.GM_xmlhttpRequest;
                                    var GM_openInTab = __extScope.GM_openInTab;
                                    var GM_setClipboard = __extScope.GM_setClipboard;
                                    var GM_notification = __extScope.GM_notification;
                                    var GM_registerMenuCommand = __extScope.GM_registerMenuCommand;
                                    var GM = __extScope.GM;
                                    var unsafeWindow = window;
                                    $jsCode
                                } catch (err) {
                                    console.warn("[DragonExt:${ext.name}] content script error:", err);
                                }
                            })();
                            """.trimIndent()
                        ).append("\n")
                    }
                }
            }
        }

        return if (hasMatchingPayload) sb.toString() else ""
    }

    /**
     * Intercepts `chrome-extension://<extId>/<path>`, `https://dragon-ext.local/<extId>/<path>`,
     * and active extensions' `declarative_net_request` rules inside `WebViewClient.shouldInterceptRequest`.
     */
    private fun corsResponseHeaders(extraHeaders: Map<String, String> = emptyMap()): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map["Access-Control-Allow-Origin"] = "*"
        map["Access-Control-Allow-Credentials"] = "true"
        map["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS"
        map["Access-Control-Allow-Headers"] = "*"
        map["Access-Control-Expose-Headers"] = "*"
        map["Cache-Control"] = "no-cache"
        for ((k, v) in extraHeaders) {
            val keyLower = k.lowercase(Locale.US)
            if (!keyLower.startsWith("access-control-") &&
                keyLower != "content-security-policy" &&
                keyLower != "content-security-policy-report-only" &&
                keyLower != "x-frame-options" &&
                keyLower != "transfer-encoding" &&
                keyLower != "content-encoding" &&
                keyLower != "content-length"
            ) {
                map[k] = v
            }
        }
        return map
    }

    fun proxyExtensionCrossOriginRequest(
        targetUrl: String,
        method: String = "GET",
        requestHeaders: Map<String, String>? = null
    ): WebResourceResponse {
        val upperMethod = method.trim().uppercase(Locale.US).ifBlank { "GET" }
        if (upperMethod == "OPTIONS") {
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                200,
                "OK",
                corsResponseHeaders(),
                ByteArrayInputStream(ByteArray(0))
            )
        }
        return try {
            val builder = Request.Builder().url(targetUrl)
            requestHeaders?.forEach { (k, v) ->
                val kl = k.lowercase(Locale.US)
                if (kl != "origin" && kl != "referer" && kl != "host" && kl != "accept-encoding") {
                    builder.header(k, v)
                }
            }
            if (requestHeaders?.keys?.none { it.equals("User-Agent", ignoreCase = true) } != false) {
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                )
            }
            if (upperMethod == "HEAD") {
                builder.head()
            } else {
                builder.get()
            }
            val builtReq = builder.build()
            val response = try {
                val r = crossOriginHttpClient.newCall(builtReq).execute()
                if (r.code == 407 || r.code == 502) {
                    r.close()
                    directExtensionHttpClient.newCall(builtReq).execute()
                } else {
                    r
                }
            } catch (_: Exception) {
                directExtensionHttpClient.newCall(builtReq).execute()
            }
            response.use { resp ->
                val rawBytes = resp.body?.bytes() ?: ByteArray(0)
                val contentTypeHeader = resp.header("Content-Type").orEmpty()
                val mimeType = contentTypeHeader.substringBefore(';').trim().lowercase(Locale.US).ifBlank {
                    if (targetUrl.substringBefore('?').endsWith(".json", ignoreCase = true)) {
                        "application/json"
                    } else {
                        guessMimeTypeForFile(targetUrl.substringBefore('?').substringAfterLast('/'))
                    }
                }
                val charset = contentTypeHeader.substringAfter("charset=", "UTF-8")
                    .substringBefore(';')
                    .trim()
                    .ifBlank { "UTF-8" }
                val upstreamHeaders = mutableMapOf<String, String>()
                for (i in 0 until resp.headers.size) {
                    upstreamHeaders[resp.headers.name(i)] = resp.headers.value(i)
                }
                upstreamHeaders["Content-Type"] = "$mimeType; charset=$charset"
                val statusCode = if (resp.code in 100..599) resp.code else 200
                val reasonPhrase = resp.message.ifBlank { "OK" }
                WebResourceResponse(
                    mimeType,
                    charset,
                    statusCode,
                    reasonPhrase,
                    corsResponseHeaders(upstreamHeaders),
                    ByteArrayInputStream(rawBytes)
                )
            }
        } catch (_: Exception) {
            val isJson = targetUrl.substringBefore('?').endsWith(".json", ignoreCase = true)
            val fallbackBody = if (isJson) "{}" else ""
            val fallbackMime = if (isJson) "application/json" else "text/plain"
            WebResourceResponse(
                fallbackMime,
                "UTF-8",
                200,
                "OK",
                corsResponseHeaders(mapOf("Content-Type" to "$fallbackMime; charset=UTF-8")),
                ByteArrayInputStream(fallbackBody.toByteArray(Charsets.UTF_8))
            )
        }
    }

    /**
     * Intercepts `https://<hostSafeId>.dragon-ext.local/<path>`, `https://dragon-ext.local/<extId>/<path>`,
     * `chrome-extension://<extIdOrStoreId>/<path>`, `moz-extension://...`,
     * and active extensions' `declarative_net_request` rules inside `WebViewClient.shouldInterceptRequest`.
     */
    fun interceptExtensionRequest(
        requestUrl: String,
        pageHost: String?,
        isMainFrame: Boolean,
        fallbackExtId: String? = null,
        activeTabUrl: String = "https://www.google.com",
        activeTabTitle: String = "Current Tab",
        requestMethod: String = "GET",
        requestHeaders: Map<String, String>? = null,
        isExtensionPageContext: Boolean = false
    ): WebResourceResponse? {
        val lower = requestUrl.lowercase(Locale.US)
        val parsed = runCatching { Uri.parse(requestUrl) }.getOrNull()
        val parsedHostLower = parsed?.host?.lowercase(Locale.US).orEmpty()

        val isVirtualHost = parsedHostLower == "dragon-ext.local" || parsedHostLower.endsWith(".dragon-ext.local")
        val isExtScheme = lower.startsWith("chrome-extension://") || lower.startsWith("moz-extension://")

        // 1. Serve extension bundled assets & same-origin CORS proxy
        if (parsed != null && (isExtScheme || isVirtualHost)) {
            if (requestMethod.equals("OPTIONS", ignoreCase = true)) {
                return WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    200,
                    "OK",
                    corsResponseHeaders(),
                    ByteArrayInputStream(ByteArray(0))
                )
            }

            val allExts = _extensionsFlow.value
            val ext: BrowserExtension?
            val rawRelPath: String

            if (parsedHostLower == "dragon-ext.local") {
                val segs = parsed.pathSegments
                val firstSeg = segs.firstOrNull().orEmpty()
                val segMatch = allExts.find { it.id.equals(firstSeg, ignoreCase = true) || it.storeId.equals(firstSeg, ignoreCase = true) }
                if (segMatch != null && segs.size >= 2) {
                    ext = segMatch
                    rawRelPath = segs.drop(1).joinToString("/")
                } else {
                    ext = fallbackExtId?.let { fid -> allExts.find { it.id.equals(fid, ignoreCase = true) } }
                    rawRelPath = parsed.path?.removePrefix("/").orEmpty()
                }
            } else if (parsedHostLower.endsWith(".dragon-ext.local")) {
                val hostToken = parsedHostLower.removeSuffix(".dragon-ext.local")
                ext = allExts.find { candidate ->
                    candidate.id.equals(hostToken, ignoreCase = true) ||
                        candidate.id.replace('_', '-').equals(hostToken, ignoreCase = true) ||
                        candidate.storeId.equals(hostToken, ignoreCase = true)
                } ?: fallbackExtId?.let { fid -> allExts.find { it.id.equals(fid, ignoreCase = true) } }
                rawRelPath = parsed.path?.removePrefix("/").orEmpty()
            } else {
                val hostToken = parsedHostLower
                ext = allExts.find { candidate ->
                    candidate.id.equals(hostToken, ignoreCase = true) ||
                        candidate.id.replace('_', '-').equals(hostToken, ignoreCase = true) ||
                        candidate.storeId.equals(hostToken, ignoreCase = true)
                } ?: fallbackExtId?.let { fid -> allExts.find { it.id.equals(fid, ignoreCase = true) } }
                rawRelPath = parsed.path?.removePrefix("/").orEmpty()
            }

            val decodedRel = runCatching { Uri.decode(rawRelPath) }.getOrDefault(rawRelPath)
                .substringBefore('?')
                .substringBefore('#')
                .trim()
                .removePrefix("./")
                .removePrefix("/")

            if (decodedRel.equals("__dragon_cors_proxy__", ignoreCase = true)) {
                val targetUrl = parsed.getQueryParameter("__url").orEmpty().trim()
                if (targetUrl.startsWith("http://", ignoreCase = true) ||
                    targetUrl.startsWith("https://", ignoreCase = true)
                ) {
                    return proxyExtensionCrossOriginRequest(
                        targetUrl = targetUrl,
                        method = requestMethod,
                        requestHeaders = requestHeaders
                    )
                }
                return WebResourceResponse(
                    "application/json",
                    "UTF-8",
                    200,
                    "OK",
                    corsResponseHeaders(mapOf("Content-Type" to "application/json; charset=UTF-8")),
                    ByteArrayInputStream("{}".toByteArray(Charsets.UTF_8))
                )
            }

            if (ext == null || ext.installDirPath.isBlank()) return null
            val relPath = decodedRel.ifBlank {
                ext.popupPath?.substringBefore('?')?.substringBefore('#').orEmpty()
            }
            if (relPath.isBlank()) return null

            val headers = corsResponseHeaders()

            if (relPath.equals("__dragon_bg.html", ignoreCase = true) ||
                relPath.equals("__dragon_background.html", ignoreCase = true)
            ) {
                val bgHtml = buildExtensionBackgroundRunnerHtml(
                    ext = ext,
                    activeTabUrl = activeTabUrl,
                    activeTabTitle = activeTabTitle
                )
                return WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(bgHtml.toByteArray(Charsets.UTF_8))
                )
            }

            val baseDir = File(ext.installDirPath)
            var targetFile = File(baseDir, relPath)
            if (!targetFile.exists()) {
                val candidateDirs = listOfNotNull(
                    ext.popupPath?.takeIf { it.contains('/') }?.substringBeforeLast('/'),
                    ext.optionsPath?.takeIf { it.contains('/') }?.substringBeforeLast('/')
                ).distinct()
                for (subDir in candidateDirs) {
                    val nestedCandidate = File(baseDir, "$subDir/$relPath")
                    if (nestedCandidate.exists() && nestedCandidate.isFile) {
                        targetFile = nestedCandidate
                        break
                    }
                }
            }
            val canonicalBase = runCatching { baseDir.canonicalPath }.getOrNull() ?: return null
            val canonicalTarget = runCatching { targetFile.canonicalPath }.getOrNull() ?: return null
            if (!canonicalTarget.startsWith(canonicalBase) || !targetFile.exists() || !targetFile.isFile) {
                return null
            }

            val mime = guessMimeTypeForFile(targetFile.name)
            if (mime == "text/html") {
                val rawHtml = runCatching { targetFile.readText(Charsets.UTF_8) }.getOrNull() ?: return null
                val enhanced = buildExtensionPageBootstrapHtml(
                    ext = ext,
                    rawHtml = rawHtml,
                    activeTabUrl = activeTabUrl,
                    activeTabTitle = activeTabTitle
                )
                return WebResourceResponse(
                    mime,
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(enhanced.toByteArray(Charsets.UTF_8))
                )
            }
            if (mime == "text/css") {
                val rawCss = runCatching { targetFile.readText(Charsets.UTF_8) }.getOrNull() ?: return null
                val i18nMap = decodeI18nMapFromJson(ext.i18nMessagesJson, ext.id)
                val resolvedCss = resolveI18nPlaceholders(rawCss, i18nMap)
                return WebResourceResponse(
                    mime,
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    ByteArrayInputStream(resolvedCss.toByteArray(Charsets.UTF_8))
                )
            }

            return WebResourceResponse(
                mime,
                "UTF-8",
                200,
                "OK",
                headers,
                FileInputStream(targetFile)
            )
        }

        // 1b. If an extension page/worker makes a direct subresource request to an external HTTP(S) origin,
        // proxy it with permissive CORS headers so host_permissions (<all_urls>) never fail CORS in WebView.
        if (isExtensionPageContext && !isMainFrame && (lower.startsWith("http://") || lower.startsWith("https://"))) {
            return proxyExtensionCrossOriginRequest(
                targetUrl = requestUrl,
                method = requestMethod,
                requestHeaders = requestHeaders
            )
        }

        // 2. Check active extensions' declarative_net_request rules for HTTP/HTTPS traffic
        if (!isMainFrame && (lower.startsWith("http://") || lower.startsWith("https://"))) {
            val activeExts = _extensionsFlow.value.filter { it.enabled && it.netRules.isNotEmpty() }
            for (ext in activeExts) {
                for (rule in ext.netRules) {
                    if (matchesDeclarativeNetRule(requestUrl, pageHost, rule)) {
                        if (rule.actionType.equals("block", ignoreCase = true)) {
                            return WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                200,
                                "OK",
                                mapOf("Access-Control-Allow-Origin" to "*"),
                                ByteArrayInputStream(ByteArray(0))
                            )
                        } else if (rule.actionType.equals("redirect", ignoreCase = true) && !rule.redirectUrl.isNullOrBlank()) {
                            return WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                302,
                                "Found",
                                mapOf(
                                    "Location" to rule.redirectUrl,
                                    "Access-Control-Allow-Origin" to "*"
                                ),
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    }
                }
            }
        }

        return null
    }

    private fun matchesDeclarativeNetRule(
        requestUrl: String,
        pageHost: String?,
        rule: ExtensionNetRule
    ): Boolean {
        val host = pageHost?.lowercase(Locale.US).orEmpty()
        if (rule.excludedInitiatorDomains.any { d -> host == d || host.endsWith(".$d") }) {
            return false
        }
        if (rule.initiatorDomains.isNotEmpty() &&
            rule.initiatorDomains.none { d -> host == d || host.endsWith(".$d") }
        ) {
            return false
        }

        val reqHost = runCatching { Uri.parse(requestUrl).host?.lowercase(Locale.US) }.getOrNull().orEmpty()
        if (rule.excludedRequestDomains.any { d -> reqHost == d || reqHost.endsWith(".$d") }) {
            return false
        }
        if (rule.requestDomains.isNotEmpty() &&
            rule.requestDomains.none { d -> reqHost == d || reqHost.endsWith(".$d") }
        ) {
            return false
        }

        val filter = rule.urlFilter.trim()
        if (filter.isBlank()) {
            // If condition had only requestDomains (and it matched above), rule matches!
            return rule.requestDomains.isNotEmpty()
        }

        if (rule.isRegexFilter) {
            return runCatching { Regex(filter, RegexOption.IGNORE_CASE).containsMatchIn(requestUrl) }
                .getOrDefault(false)
        }

        // Handle Adblock/DNR style `||domain.com^` or substring/wildcard filters
        val cleaned = filter
            .removePrefix("||")
            .removeSuffix("^")
            .lowercase(Locale.US)
        return if (cleaned.contains('*')) {
            globToRegex("*$cleaned*").matches(requestUrl)
        } else {
            requestUrl.lowercase(Locale.US).contains(cleaned)
        }
    }

    private fun buildExtensionBackgroundRunnerHtml(
        ext: BrowserExtension,
        activeTabUrl: String,
        activeTabTitle: String
    ): String {
        val paths = ext.backgroundScriptPath?.split(',')?.map { it.trim().removePrefix("/") }?.filter { it.isNotBlank() } ?: emptyList()
        if (paths.isEmpty()) {
            return "<!DOCTYPE html><html><head></head><body><script>try{window.parent.__dragonExtBgReady=true;}catch(e){}</script></body></html>"
        }
        val manifestObj = runCatching { JSONObject(ext.manifestJson) }.getOrNull()
        val isModuleBg = manifestObj?.optJSONObject("background")?.optString("type", "")
            ?.equals("module", ignoreCase = true) == true
        val scriptAttr = if (isModuleBg) " type=\"module\"" else ""

        val scriptTags = paths.joinToString("\n") { p ->
            "<script$scriptAttr src=\"/$p\"></script>"
        }

        fun escapeScriptContent(jsonStr: String): String =
            jsonStr.replace("</script", "<\\/script", ignoreCase = true)

        val initialKvJson = escapeScriptContent(getExtensionStorageJson(ext.id))
        val extMetaJson = escapeScriptContent(buildExtMetaJson(ext))
        val i18nJson = escapeScriptContent(ext.i18nMessagesJson.ifBlank { "{}" })
        val activeTabContextJson = escapeScriptContent(
            JSONObject().apply {
                put("url", activeTabUrl)
                put("title", activeTabTitle)
            }.toString()
        )
        val extHost = extensionVirtualHost(ext.id)
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <script>
            window.__dragonIsBackgroundWorker = true;
            try { history.replaceState(null, '', '/__dragon_background.html'); } catch (e) {}
            (function() {
                try {
                    if (window.parent && window.parent !== window) {
                        window.__dragonExtMessageListeners = window.parent.__dragonExtMessageListeners = window.parent.__dragonExtMessageListeners || [];
                        window.__dragonExtBgMessageListeners = window.parent.__dragonExtBgMessageListeners = window.parent.__dragonExtBgMessageListeners || [];
                        window.__dragonExtPopupMessageListeners = window.parent.__dragonExtPopupMessageListeners = window.parent.__dragonExtPopupMessageListeners || [];
                        window.__dragonExtConnectListeners = window.parent.__dragonExtConnectListeners = window.parent.__dragonExtConnectListeners || [];
                        window.__dragonExtStorageChangedListeners = window.parent.__dragonExtStorageChangedListeners = window.parent.__dragonExtStorageChangedListeners || [];
                        window.__dragonExtStorageCache = window.parent.__dragonExtStorageCache = window.parent.__dragonExtStorageCache || {};
                        window.__dragonExtAuthRequiredListeners = window.parent.__dragonExtAuthRequiredListeners = window.parent.__dragonExtAuthRequiredListeners || [];
                        window.__dragonExtProxyChangeListeners = window.parent.__dragonExtProxyChangeListeners = window.parent.__dragonExtProxyChangeListeners || [];
                        window.__dragonExtProxyState = window.parent.__dragonExtProxyState = window.parent.__dragonExtProxyState || {};
                        if (window.parent.DragonExtensionBridge) {
                            window.DragonExtensionBridge = window.parent.DragonExtensionBridge;
                        }
                    }
                } catch (e) {}
            })();
            ${buildGlobalExtensionApiPolyfillJs()}
            (function() {
                try {
                    if (window.__dragonInstallExtensionNetworkHooks) {
                        window.__dragonInstallExtensionNetworkHooks('$extHost');
                    }
                    var scope = window.__dragonCreateExtensionScope(${JSONObject.quote(ext.id)}, $extMetaJson, $initialKvJson, $i18nJson, $activeTabContextJson);
                    window.chrome = scope.chrome;
                    window.browser = scope.browser;
                    window.clients = window.clients || {
                        matchAll: function() { return Promise.resolve([{ id: 'popup_client', type: 'window', url: 'https://$extHost/' }]); },
                        openWindow: function(u) { if (u && scope.chrome && scope.chrome.tabs) scope.chrome.tabs.create({ url: u }); return Promise.resolve(null); },
                        claim: function() { return Promise.resolve(); },
                        get: function(id) { return Promise.resolve({ id: id || 'popup_client', type: 'window', url: 'https://$extHost/' }); }
                    };
                    window.skipWaiting = window.skipWaiting || function() { return Promise.resolve(); };
                    window.registration = window.registration || { scope: 'https://$extHost/', active: { state: 'activated' } };
                    window.importScripts = window.importScripts || function() {
                        for (var i = 0; i < arguments.length; i++) {
                            try {
                                var src = String(arguments[i] || '').replace(/^\.\//, '').replace(/^\/+/, '');
                                if (!src) continue;
                                var xhr = new XMLHttpRequest();
                                xhr.open('GET', 'https://$extHost/' + src, false);
                                xhr.send(null);
                                if (xhr.status >= 200 && xhr.status < 300 && xhr.responseText) {
                                    var s = document.createElement('script');
                                    s.type = 'text/javascript';
                                    s.text = xhr.responseText;
                                    (document.head || document.documentElement).appendChild(s);
                                }
                            } catch (e) {
                                console.warn('[DragonExtBg] importScripts error:', e);
                            }
                        }
                    };
                } catch (e) {}
            })();
            </script>
            $scriptTags
            ${if (isModuleBg) {
                "<script type=\"module\">try { if (window.parent) window.parent.__dragonExtBgReady = true; } catch (e) {}</script>"
            } else {
                "<script>try { if (window.parent) window.parent.__dragonExtBgReady = true; } catch (e) {}</script>"
            }}
            <script>
            window.addEventListener('load', function() {
                try { if (window.parent) window.parent.__dragonExtBgReady = true; } catch (e) {}
            });
            </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()
    }

    private fun buildExtensionPageBootstrapHtml(
        ext: BrowserExtension,
        rawHtml: String,
        activeTabUrl: String,
        activeTabTitle: String
    ): String {
        val i18nMap = decodeI18nMapFromJson(ext.i18nMessagesJson, ext.id)
        val localizedHtml = resolveI18nPlaceholders(rawHtml, i18nMap)
            .replace(
                Regex("""<meta\b[^>]*http-equiv\s*=\s*["']?Content-Security-Policy["']?[^>]*>""", RegexOption.IGNORE_CASE),
                ""
            )

        fun escapeScriptContent(jsonStr: String): String =
            jsonStr.replace("</script", "<\\/script", ignoreCase = true)

        val initialKvJson = escapeScriptContent(getExtensionStorageJson(ext.id))
        val extMetaJson = escapeScriptContent(buildExtMetaJson(ext))
        val i18nJson = escapeScriptContent(ext.i18nMessagesJson.ifBlank { "{}" })
        val activeTabContextJson = escapeScriptContent(
            JSONObject().apply {
                put("url", activeTabUrl)
                put("title", activeTabTitle)
            }.toString()
        )
        val extHost = extensionVirtualHost(ext.id)
        val hasBackground = !ext.backgroundScriptPath.isNullOrBlank()

        val viewportMeta = if (localizedHtml.contains("name=\"viewport\"", ignoreCase = true) ||
            localizedHtml.contains("name='viewport'", ignoreCase = true)
        ) {
            ""
        } else {
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
        }

        val safePopupCss = """
            <style id="dragon-ext-popup-defaults">
            :where(html) { background-color: #ffffff; color: #111827; height: 100%; min-height: 100%; }
            :where(body) { margin: 0; font-family: system-ui, -apple-system, sans-serif; height: 100%; min-height: 100%; }
            html, body { max-width: 100% !important; min-height: 120px; box-sizing: border-box; overflow-x: hidden; }
            </style>
        """.trimIndent()

        val bgIframeHtml = if (hasBackground) {
            "<iframe id=\"__dragon_ext_bg_frame\" src=\"/__dragon_bg.html\" style=\"display:none;width:0;height:0;border:0;\" aria-hidden=\"true\"></iframe>"
        } else {
            ""
        }

        val bootstrapScript = """
            $viewportMeta
            $safePopupCss
            <script>
            window.__dragonExtHasBackground = ${if (hasBackground) "true" else "false"};
            window.__dragonExtBgReady = ${if (hasBackground) "false" else "true"};
            ${buildGlobalExtensionApiPolyfillJs()}
            (function() {
                try {
                    if (window.__dragonInstallExtensionNetworkHooks) {
                        window.__dragonInstallExtensionNetworkHooks('$extHost');
                    }
                    var scope = window.__dragonCreateExtensionScope(${JSONObject.quote(ext.id)}, $extMetaJson, $initialKvJson, $i18nJson, $activeTabContextJson);
                    window.chrome = scope.chrome;
                    window.browser = scope.browser;
                } catch (bootErr) {
                    try {
                        if (window.DragonExtensionBridge && window.DragonExtensionBridge.onPopupError) {
                            window.DragonExtensionBridge.onPopupError('Extension init error: ' + String(bootErr));
                        }
                    } catch (e) {}
                }
                window.addEventListener('error', function(ev) {
                    try {
                        var msg = (ev && ev.message) ? String(ev.message) : '';
                        if (msg && !/ResizeObserver|Failed to fetch|NetworkError|Load failed|CORS/i.test(msg)) {
                            if (window.DragonExtensionBridge && window.DragonExtensionBridge.onPopupError) {
                                window.DragonExtensionBridge.onPopupError(msg);
                            }
                        }
                    } catch (e) {}
                }, true);
            })();
            </script>
        """.trimIndent()

        val headMatch = Regex("""<head(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE).find(localizedHtml)
        val withHead = if (headMatch != null) {
            val insertAt = headMatch.range.last + 1
            buildString(localizedHtml.length + bootstrapScript.length + 2) {
                append(localizedHtml, 0, insertAt)
                append('\n')
                append(bootstrapScript)
                append('\n')
                append(localizedHtml, insertAt, localizedHtml.length)
            }
        } else {
            val htmlMatch = Regex("""<html(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE).find(localizedHtml)
            if (htmlMatch != null) {
                val insertAt = htmlMatch.range.last + 1
                buildString(localizedHtml.length + bootstrapScript.length + 16) {
                    append(localizedHtml, 0, insertAt)
                    append("\n<head>\n")
                    append(bootstrapScript)
                    append("\n</head>\n")
                    append(localizedHtml, insertAt, localizedHtml.length)
                }
            } else {
                "$bootstrapScript\n$localizedHtml"
            }
        }
        if (bgIframeHtml.isBlank()) return withHead
        val bodyMatch = Regex("""<body(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE).find(withHead)
        return if (bodyMatch != null) {
            val insertAt = bodyMatch.range.last + 1
            buildString(withHead.length + bgIframeHtml.length + 2) {
                append(withHead, 0, insertAt)
                append('\n')
                append(bgIframeHtml)
                append(withHead, insertAt, withHead.length)
            }
        } else {
            "$bgIframeHtml\n$withHead"
        }
    }

    fun readExtensionManifestPrettyJson(ext: BrowserExtension): String {
        val fromDisk = if (ext.installDirPath.isNotBlank()) {
            val dir = File(ext.installDirPath)
            val mFile = if (dir.exists() && dir.isDirectory) findManifestFile(dir) else null
            mFile?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
        } else null
        val raw = (fromDisk ?: ext.manifestJson).trimStart('\uFEFF').trim()
        if (raw.isBlank() || raw == "{}") {
            return buildExtMetaJson(ext).let {
                runCatching { JSONObject(it).toString(2) }.getOrDefault(it)
            }
        }
        return runCatching { JSONObject(raw).toString(2) }.getOrDefault(raw)
    }

    fun computeExtensionDiskStats(ext: BrowserExtension): Pair<Int, Long> {
        if (ext.installDirPath.isBlank()) return 0 to 0L
        val dir = File(ext.installDirPath)
        if (!dir.exists() || !dir.isDirectory) return 0 to 0L
        var count = 0
        var bytes = 0L
        runCatching {
            dir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    count++
                    bytes += f.length()
                }
            }
        }
        return count to bytes
    }

    fun buildExtensionVirtualUrl(ext: BrowserExtension, relPath: String): String {
        val clean = relPath.trim().removePrefix("./").removePrefix("/")
        return "https://${extensionVirtualHost(ext.id)}/$clean"
    }

    fun translateExtensionSchemeToVirtualUrl(rawUrl: String): String? {
        val parsed = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "chrome-extension" && scheme != "moz-extension") return null
        val hostToken = parsed.host?.lowercase(Locale.US).orEmpty()
        val ext = _extensionsFlow.value.find { candidate ->
            candidate.id.equals(hostToken, ignoreCase = true) ||
                candidate.id.replace('_', '-').equals(hostToken, ignoreCase = true) ||
                candidate.storeId.equals(hostToken, ignoreCase = true)
        } ?: return null
        val rel = parsed.path?.removePrefix("/").orEmpty()
        val query = parsed.query?.let { "?$it" }.orEmpty()
        val frag = parsed.fragment?.let { "#$it" }.orEmpty()
        return "https://${extensionVirtualHost(ext.id)}/$rel$query$frag"
    }

    /**
     * Reads the extension's `popup.html` (or explicit `targetPagePath`, such as `options.html`),
     * or generates a rich interactive Modification & Control Panel HTML if no bundled HTML exists.
     * Injects the extension API bridge + active browser tab context so `chrome.*` calls work live.
     */
    fun loadExtensionPopupHtml(
        ext: BrowserExtension,
        activeTabUrl: String = "https://www.google.com",
        activeTabTitle: String = "Current Tab",
        targetPagePath: String? = null
    ): Pair<String, String>? {
        val rawPopupRel = targetPagePath?.takeIf { it.isNotBlank() } ?: ext.popupPath
        if (!rawPopupRel.isNullOrBlank() && ext.installDirPath.isNotBlank()) {
            val cleanPopupRel = rawPopupRel
                .removePrefix("./")
                .removePrefix("/")
            val cleanFileRel = cleanPopupRel.substringBefore('?').substringBefore('#')
            val popupFile = File(ext.installDirPath, cleanFileRel)
            if (popupFile.exists() && popupFile.isFile) {
                val rawHtml = runCatching { popupFile.readText(Charsets.UTF_8) }.getOrNull()
                if (!rawHtml.isNullOrBlank()) {
                    val enhancedHtml = buildExtensionPageBootstrapHtml(
                        ext = ext,
                        rawHtml = rawHtml,
                        activeTabUrl = activeTabUrl,
                        activeTabTitle = activeTabTitle
                    )
                    val baseUrl = "https://${extensionVirtualHost(ext.id)}/$cleanPopupRel"
                    return baseUrl to enhancedHtml
                }
            }
        }

        // Fallback: generate an interactive Modification & Live Control Panel HTML for extensions
        // without a dedicated popup.html (such as Userscripts, Userstyles, or background/content-script extensions).
        val generatedPanelHtml = buildSynthesizedModificationPanelHtml(ext, activeTabUrl, activeTabTitle)
        val enhancedHtml = buildExtensionPageBootstrapHtml(
            ext = ext,
            rawHtml = generatedPanelHtml,
            activeTabUrl = activeTabUrl,
            activeTabTitle = activeTabTitle
        )
        val baseUrl = "https://${extensionVirtualHost(ext.id)}/__dragon_panel.html"
        return baseUrl to enhancedHtml
    }

    private fun buildSynthesizedModificationPanelHtml(
        ext: BrowserExtension,
        activeTabUrl: String,
        activeTabTitle: String
    ): String {
        val safeName = ext.name.replace("<", "&lt;").replace(">", "&gt;")
        val safeDesc = ext.description.replace("<", "&lt;").replace(">", "&gt;")
        val matchesText = ext.hostPermissions.ifEmpty { listOf("<all_urls>") }.joinToString(", ")
        val scriptCount = ext.contentScripts.sumOf { it.jsFiles.size + if (it.inlineJs.isNotBlank()) 1 else 0 }
        val styleCount = ext.contentScripts.sumOf { it.cssFiles.size + if (it.inlineCss.isNotBlank()) 1 else 0 }
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body { background:#0B121E; color:#E6EDF3; font-family:system-ui,-apple-system,sans-serif; padding:14px; margin:0; }
                .card { background:#111B2E; border:1px solid #1E2D4A; border-radius:12px; padding:12px; margin-bottom:12px; }
                .title { font-size:15px; font-weight:700; color:#00E5FF; margin:0 0 4px 0; }
                .sub { font-size:12px; color:#8B949E; margin:0 0 8px 0; }
                .badge { display:inline-block; background:#00E5FF22; color:#00E5FF; border:1px solid #00E5FF55; border-radius:6px; padding:2px 7px; font-size:11px; font-weight:600; margin-right:6px; }
                .btn { background:#00E5FF; color:#06101E; border:none; border-radius:8px; padding:8px 12px; font-size:12px; font-weight:700; cursor:pointer; margin-right:6px; margin-top:6px; }
                .btn-sec { background:#1E2D4A; color:#E6EDF3; }
                textarea, input { width:100%; box-sizing:border-box; background:#070C15; color:#00E676; border:1px solid #263859; border-radius:8px; padding:8px; font-family:monospace; font-size:12px; margin-top:6px; }
            </style>
            </head>
            <body>
                <div class="card">
                    <div class="title">$safeName <span class="badge">v${ext.version}</span> <span class="badge">${ext.kind.badge}</span></div>
                    <div class="sub">$safeDesc</div>
                    <div style="font-size:11px;color:#A5D6FF;">Active Tab: <b>${activeTabTitle.replace("<", "&lt;")}</b> (${activeTabUrl.take(60).replace("<", "&lt;")})</div>
                    <div style="font-size:11px;color:#8B949E;margin-top:4px;">Match Rules: <code>$matchesText</code> • Scripts: $scriptCount • Styles: $styleCount • DNR Rules: ${ext.netRules.size}</div>
                    <button class="btn" onclick="reapplyNow()">Run / Apply on Current Tab</button>
                    <button class="btn btn-sec" onclick="reloadTab()">Reload Tab</button>
                </div>
                <div class="card">
                    <div class="title" style="font-size:13px;">Extension Storage (chrome.storage.local / GM Values)</div>
                    <div class="sub">Modify live JSON settings stored by this extension:</div>
                    <textarea id="kvEditor" rows="5"></textarea>
                    <button class="btn" onclick="saveKv()">Save Storage JSON</button>
                    <button class="btn btn-sec" onclick="clearKv()">Reset Storage</button>
                    <span id="kvStatus" style="font-size:11px;color:#00E676;margin-left:6px;"></span>
                </div>
                <script>
                    function loadKv() {
                        chrome.storage.local.get(null, function(items) {
                            document.getElementById('kvEditor').value = JSON.stringify(items || {}, null, 2);
                        });
                    }
                    function saveKv() {
                        try {
                            var parsed = JSON.parse(document.getElementById('kvEditor').value || '{}');
                            chrome.storage.local.set(parsed, function() {
                                document.getElementById('kvStatus').textContent = 'Saved!';
                                setTimeout(function(){ document.getElementById('kvStatus').textContent = ''; }, 1500);
                            });
                        } catch (e) {
                            document.getElementById('kvStatus').textContent = 'Invalid JSON';
                        }
                    }
                    function clearKv() {
                        chrome.storage.local.clear(function() {
                            loadKv();
                            document.getElementById('kvStatus').textContent = 'Cleared!';
                        });
                    }
                    function reapplyNow() {
                        if (window.DragonExtensionBridge && window.DragonExtensionBridge.reloadActiveTab) {
                            window.DragonExtensionBridge.reloadActiveTab();
                        }
                    }
                    function reloadTab() {
                        chrome.tabs.reload();
                    }
                    loadKv();
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    // --- Native Bridge Storage & Cross-Origin XHR helpers for `@JavascriptInterface` ---

    fun getExtensionStorageJson(extId: String): String {
        return kvStoragePrefs.getString("kv_$extId", "{}") ?: "{}"
    }

    fun mergeExtensionStorageJson(extId: String, patchJson: String): String {
        val saved = synchronized(kvStoragePrefs) {
            val currentObj = runCatching { JSONObject(getExtensionStorageJson(extId)) }.getOrElse { JSONObject() }
            val patchObj = runCatching { JSONObject(patchJson) }.getOrElse { JSONObject() }
            val keys = patchObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                currentObj.put(k, patchObj.get(k))
            }
            val updatedStr = currentObj.toString()
            kvStoragePrefs.edit().putString("kv_$extId", updatedStr).apply()
            updatedStr
        }
        // Automatically sync newly stored VPN proxy credentials if an extension proxy is active
        runCatching {
            val extracted = extractCredentialsFromExtensionStorage(extId)
            if (extracted != null && extracted.first.isNotBlank()) {
                setExtensionProxyAuth(extId, extracted.first, extracted.second)
            }
        }
        return saved
    }

    fun removeExtensionStorageKeys(extId: String, keysJsonArray: String): String {
        synchronized(kvStoragePrefs) {
            val currentObj = runCatching { JSONObject(getExtensionStorageJson(extId)) }.getOrElse { JSONObject() }
            val arr = runCatching { JSONArray(keysJsonArray) }.getOrElse { JSONArray() }
            for (i in 0 until arr.length()) {
                currentObj.remove(arr.optString(i))
            }
            val saved = currentObj.toString()
            kvStoragePrefs.edit().putString("kv_$extId", saved).apply()
            return saved
        }
    }

    fun clearExtensionStorage(extId: String) {
        synchronized(kvStoragePrefs) {
            kvStoragePrefs.edit().putString("kv_$extId", "{}").apply()
        }
    }

    // --- Extension Proxy Control (chrome.proxy.settings & webRequest.onAuthRequired) ---

    @Volatile
    private var lastExtensionProxySettingsJsonByExt = mutableMapOf<String, String>()

    @Volatile
    private var lastExtensionProxyAuthByExt = mutableMapOf<String, Pair<String, String>>()

    fun getExtensionProxySettingsJson(extId: String): String {
        val stored = synchronized(lastExtensionProxySettingsJsonByExt) {
            lastExtensionProxySettingsJsonByExt[extId]
        } ?: kvStoragePrefs.getString("proxy_cfg_$extId", null)
        if (!stored.isNullOrBlank()) {
            return stored
        }
        val activeExtProxy = BrowserProxyManager.getExtensionManagedProxyProfile()
        if (activeExtProxy != null) {
            return JSONObject().apply {
                put("levelOfControl", "controlled_by_this_extension")
                put("value", JSONObject().apply {
                    put("mode", "fixed_servers")
                    put("rules", JSONObject().apply {
                        put("singleProxy", JSONObject().apply {
                            put("scheme", activeExtProxy.type.scheme)
                            put("host", activeExtProxy.host)
                            put("port", activeExtProxy.port)
                        })
                        put("bypassList", JSONArray(activeExtProxy.bypassRules))
                    })
                })
            }.toString()
        }
        return JSONObject().apply {
            put("levelOfControl", "controllable_by_this_extension")
            put("value", JSONObject().apply {
                put("mode", "system")
            })
        }.toString()
    }

    fun setExtensionProxyAuth(extId: String, username: String, password: String) {
        val u = username.trim()
        val p = password
        if (u.isBlank() && p.isBlank()) return
        synchronized(lastExtensionProxyAuthByExt) {
            lastExtensionProxyAuthByExt[extId] = u to p
        }
        kvStoragePrefs.edit()
            .putString("proxy_auth_u_$extId", u)
            .putString("proxy_auth_p_$extId", p)
            .apply()
        // If an extension proxy is already active for this extension without credentials (or with older credentials), update it live
        val currentExtProxy = BrowserProxyManager.getExtensionManagedProxyProfile()
        if (currentExtProxy != null && (currentExtProxy.username != u || currentExtProxy.password != p)) {
            val updatedFallbacks = currentExtProxy.fallbackProxies.map { fb ->
                fb.copy(username = fb.username.ifBlank { u }, password = if (fb.username.isNotBlank()) fb.password else p)
            }
            val updated = currentExtProxy.copy(username = u, password = p, fallbackProxies = updatedFallbacks)
            BrowserProxyManager.applyExtensionProxyProfile(context, extId, updated)
        }
    }

    private fun mapProxySchemeToType(rawScheme: String): BrowserProxyType {
        return when (rawScheme.trim().lowercase(Locale.US)) {
            "https", "ssl", "tls" -> BrowserProxyType.HTTPS
            "socks5", "socks5h", "socks" -> BrowserProxyType.SOCKS5
            "socks4", "socks4a" -> BrowserProxyType.SOCKS4
            else -> BrowserProxyType.HTTP
        }
    }

    fun applyExtensionProxySettings(extId: String, detailsJson: String): String {
        return try {
            val root = JSONObject(detailsJson)
            val valueObj = root.optJSONObject("value") ?: root
            val mode = valueObj.optString("mode", "fixed_servers").trim().lowercase(Locale.US)

            if (mode == "direct" || mode == "system" || mode == "auto_detect") {
                clearExtensionProxySettings(extId)
                return getExtensionProxySettingsJson(extId)
            }

            data class ParsedEndpoint(
                val scheme: String,
                val host: String,
                val port: Int,
                val user: String = "",
                val pass: String = ""
            )

            val parsedEndpoints = mutableListOf<ParsedEndpoint>()
            var parsedUser = valueObj.optString("username", "").trim()
            var parsedPass = valueObj.optString("password", "")
            val bypassList = mutableListOf<String>()
            bypassList.addAll(BrowserProxyProfile.DEFAULT_BYPASS_RULES)

            val rulesObj = valueObj.optJSONObject("rules")
            if (rulesObj != null) {
                val ruleKeys = listOf("singleProxy", "proxyForHttps", "proxyForHttp", "fallbackProxy")
                for (rk in ruleKeys) {
                    val rObj = rulesObj.optJSONObject(rk) ?: continue
                    val h = rObj.optString("host", "").trim().removePrefix("[").removeSuffix("]")
                    if (h.isNotBlank()) {
                        val s = rObj.optString("scheme", "http").trim().lowercase(Locale.US).ifBlank { "http" }
                        val p = rObj.optInt("port", 0)
                        val u = rObj.optString("username", "").trim()
                        val pw = rObj.optString("password", "")
                        if (parsedUser.isBlank() && u.isNotBlank()) parsedUser = u
                        if (parsedPass.isBlank() && pw.isNotBlank()) parsedPass = pw
                        parsedEndpoints.add(ParsedEndpoint(s, h, p, u, pw))
                    }
                }
                val bypassArr = rulesObj.optJSONArray("bypassList")
                if (bypassArr != null) {
                    for (i in 0 until bypassArr.length()) {
                        val b = bypassArr.optString(i, "").trim()
                        if (b.isNotBlank() && b !in bypassList) {
                            bypassList.add(b)
                        }
                    }
                }
            }

            // Parse PAC script endpoints (preferring JS-evaluated PAC result if present, plus raw pacScript.data)
            val evaluatedPac = valueObj.optString("__evaluatedPacResult", "").trim()
            val rawPacData = valueObj.optJSONObject("pacScript")?.optString("data", "").orEmpty()
            val combinedPacText = buildString {
                if (evaluatedPac.isNotBlank()) append(evaluatedPac).append('\n')
                if (rawPacData.isNotBlank()) append(rawPacData)
            }
            if (combinedPacText.isNotBlank()) {
                val pacRegex = Regex(
                    """\b(HTTPS|PROXY|HTTP|SOCKS5|SOCKS4|SOCKS)\s+([a-zA-Z0-9._-]+|\[\S+?\]):(\d{1,5})\b""",
                    RegexOption.IGNORE_CASE
                )
                for (match in pacRegex.findAll(combinedPacText)) {
                    val kw = match.groupValues[1].uppercase(Locale.US)
                    val s = when (kw) {
                        "HTTPS" -> "https"
                        "SOCKS5", "SOCKS" -> "socks5"
                        "SOCKS4" -> "socks4"
                        else -> "http"
                    }
                    val h = match.groupValues[2].trim().removePrefix("[").removeSuffix("]")
                    val p = match.groupValues[3].toIntOrNull() ?: 0
                    if (h.isNotBlank() && !h.equals("localhost", ignoreCase = true) && h != "127.0.0.1") {
                        parsedEndpoints.add(ParsedEndpoint(s, h, p))
                    }
                }
            }

            // Also support direct top-level host/port/scheme if passed by custom/simplified scripts
            if (parsedEndpoints.isEmpty()) {
                val topHost = valueObj.optString("host", "").trim().removePrefix("[").removeSuffix("]")
                if (topHost.isNotBlank()) {
                    val topScheme = valueObj.optString("scheme", "http").trim().lowercase(Locale.US).ifBlank { "http" }
                    val topPort = valueObj.optInt("port", 0)
                    parsedEndpoints.add(ParsedEndpoint(topScheme, topHost, topPort))
                }
            }

            val distinctEndpoints = parsedEndpoints.distinctBy {
                "${it.scheme}:${it.host.lowercase(Locale.US)}:${it.port}"
            }

            if (distinctEndpoints.isEmpty()) {
                clearExtensionProxySettings(extId)
                return getExtensionProxySettingsJson(extId)
            }

            // Merge any credentials captured from chrome.webRequest.onAuthRequired, persisted prefs, or extension storage
            val savedAuth = synchronized(lastExtensionProxyAuthByExt) { lastExtensionProxyAuthByExt[extId] }
            if (parsedUser.isBlank() && savedAuth != null) {
                parsedUser = savedAuth.first
                parsedPass = savedAuth.second
            }
            if (parsedUser.isBlank()) {
                val persistedU = kvStoragePrefs.getString("proxy_auth_u_$extId", "").orEmpty().trim()
                val persistedP = kvStoragePrefs.getString("proxy_auth_p_$extId", "").orEmpty()
                if (persistedU.isNotBlank()) {
                    parsedUser = persistedU
                    parsedPass = persistedP
                }
            }
            if (parsedUser.isBlank()) {
                val kvAutoAuth = extractCredentialsFromExtensionStorage(extId)
                if (kvAutoAuth != null) {
                    parsedUser = kvAutoAuth.first
                    parsedPass = kvAutoAuth.second
                }
            }

            val extName = _extensionsFlow.value.find { it.id == extId }?.name ?: "VPN Extension"
            val primary = distinctEndpoints.first()
            val primaryType = mapProxySchemeToType(primary.scheme)
            val primaryPort = if (primary.port in 1..65535) primary.port else primaryType.defaultPort

            val fallbackProfiles = distinctEndpoints.drop(1).mapIndexed { idx, ep ->
                val epType = mapProxySchemeToType(ep.scheme)
                val epPort = if (ep.port in 1..65535) ep.port else epType.defaultPort
                BrowserProxyProfile(
                    id = "ext_vpn_${extId}_fb_$idx",
                    name = "$extName Fallback #${idx + 1} (${epType.label})",
                    type = epType,
                    host = ep.host,
                    port = epPort,
                    username = ep.user.ifBlank { parsedUser },
                    password = if (ep.user.isNotBlank()) ep.pass else parsedPass,
                    bypassRules = bypassList,
                    reverseBypass = false,
                    applyToDownloads = true
                )
            }

            val profile = BrowserProxyProfile(
                id = "ext_vpn_$extId",
                name = "$extName (${primaryType.label})",
                type = primaryType,
                host = primary.host,
                port = primaryPort,
                username = primary.user.ifBlank { parsedUser },
                password = if (primary.user.isNotBlank()) primary.pass else parsedPass,
                bypassRules = bypassList,
                reverseBypass = false,
                applyToDownloads = true,
                fallbackProxies = fallbackProfiles
            )

            val storedStatusJson = JSONObject().apply {
                put("levelOfControl", "controlled_by_this_extension")
                put("value", valueObj)
            }.toString()

            synchronized(lastExtensionProxySettingsJsonByExt) {
                lastExtensionProxySettingsJsonByExt[extId] = storedStatusJson
            }
            kvStoragePrefs.edit().putString("proxy_cfg_$extId", storedStatusJson).apply()

            BrowserProxyManager.applyExtensionProxyProfile(context, extId, profile)
            storedStatusJson
        } catch (_: Exception) {
            getExtensionProxySettingsJson(extId)
        }
    }

    fun clearExtensionProxySettings(extId: String): String {
        synchronized(lastExtensionProxySettingsJsonByExt) {
            lastExtensionProxySettingsJsonByExt.remove(extId)
        }
        kvStoragePrefs.edit().remove("proxy_cfg_$extId").apply()
        BrowserProxyManager.clearExtensionProxyProfile(context, extId)
        return getExtensionProxySettingsJson(extId)
    }

    private fun extractCredentialsFromExtensionStorage(extId: String): Pair<String, String>? {
        val raw = getExtensionStorageJson(extId)
        if (raw.isBlank() || raw == "{}") return null
        return runCatching {
            val obj = JSONObject(raw)
            val userKeys = listOf(
                "proxyUsername", "proxy_username", "vpnUsername", "vpn_username",
                "vpnUser", "vpn_user", "authUsername", "auth_username", "authUser", "auth_user",
                "username", "user_name", "userName", "user", "login", "account_id", "accountId", "uuid"
            )
            val passKeys = listOf(
                "proxyPassword", "proxy_password", "vpnPassword", "vpn_password",
                "vpnPass", "vpn_pass", "authPassword", "auth_password", "authPass", "auth_pass",
                "password", "pass", "secret", "api_key", "apiKey", "access_token", "accessToken", "token"
            )
            fun firstMatchingString(node: JSONObject, candidates: List<String>): String {
                for (k in candidates) {
                    val v = node.opt(k)
                    if (v is String && v.isNotBlank() && !v.startsWith("{") && !v.startsWith("[")) {
                        return v.trim()
                    }
                }
                return ""
            }
            fun findCreds(node: JSONObject, depth: Int): Pair<String, String>? {
                if (depth > 4) return null
                val u = firstMatchingString(node, userKeys)
                val p = firstMatchingString(node, passKeys)
                if (u.isNotBlank() && p.isNotBlank()) return u to p
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val childObj = node.optJSONObject(k)
                    if (childObj != null) {
                        val found = findCreds(childObj, depth + 1)
                        if (found != null) return found
                    } else {
                        val childArr = node.optJSONArray(k)
                        if (childArr != null) {
                            for (i in 0 until minOf(childArr.length(), 10)) {
                                val elemObj = childArr.optJSONObject(i) ?: continue
                                val found = findCreds(elemObj, depth + 1)
                                if (found != null) return found
                            }
                        } else {
                            // Some extensions store serialized JSON inside a string value
                            val strVal = node.optString(k, "").trim()
                            if (strVal.startsWith("{") && strVal.endsWith("}")) {
                                val parsedInner = runCatching { JSONObject(strVal) }.getOrNull()
                                if (parsedInner != null) {
                                    val found = findCreds(parsedInner, depth + 1)
                                    if (found != null) return found
                                }
                            }
                        }
                    }
                }
                return null
            }
            findCreds(obj, 0)
        }.getOrNull()
    }

    /**
     * Executes a cross-origin HTTP request on behalf of an extension's `GM_xmlhttpRequest` or background fetch.
     */
    fun executeCrossOriginXhr(requestJson: String): String {
        return try {
            val reqObj = JSONObject(requestJson)
            val method = reqObj.optString("method", "GET").uppercase(Locale.US)
            val url = reqObj.optString("url", "").trim()
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                return JSONObject().apply {
                    put("status", 0)
                    put("statusText", "Invalid URL")
                    put("responseText", "")
                }.toString()
            }
            if (com.example.data.security.MalwareProtectionEngine.isSsrfOrInternalUrl(url)) {
                return JSONObject().apply {
                    put("status", 403)
                    put("statusText", "Blocked private/internal SSRF target")
                    put("responseText", "")
                }.toString()
            }
            val urlVerdict = com.example.data.security.MalwareProtectionEngine.inspectUrl(url, context)
            if (urlVerdict.isBlocked) {
                return JSONObject().apply {
                    put("status", 403)
                    put("statusText", "Blocked malware target: ${urlVerdict.category}")
                    put("responseText", "")
                }.toString()
            }
            val headersObj = reqObj.optJSONObject("headers")
            val bodyData = if (reqObj.has("data") && !reqObj.isNull("data")) reqObj.optString("data") else null

            val builder = Request.Builder().url(url)
            var contentType = "text/plain; charset=utf-8"
            if (headersObj != null) {
                val keys = headersObj.keys()
                while (keys.hasNext()) {
                    val hName = keys.next()
                    val hVal = headersObj.optString(hName, "")
                    if (hName.equals("Content-Type", ignoreCase = true)) {
                        contentType = hVal
                    }
                    builder.header(hName, hVal)
                }
            }

            if (method == "POST" || method == "PUT" || method == "PATCH") {
                val body = (bodyData ?: "").toRequestBody(contentType.toMediaTypeOrNull())
                builder.method(method, body)
            } else {
                builder.method(method, null)
            }

            val builtReq = builder.build()
            val response = try {
                val r = crossOriginHttpClient.newCall(builtReq).execute()
                if (r.code == 407 || r.code == 502) {
                    r.close()
                    directExtensionHttpClient.newCall(builtReq).execute()
                } else {
                    r
                }
            } catch (_: Exception) {
                directExtensionHttpClient.newCall(builtReq).execute()
            }
            response.use { resp ->
                val text = resp.body?.string().orEmpty()
                val respHeaders = StringBuilder()
                for (i in 0 until resp.headers.size) {
                    respHeaders.append(resp.headers.name(i)).append(": ").append(resp.headers.value(i)).append("\r\n")
                }
                JSONObject().apply {
                    put("status", resp.code)
                    put("statusText", resp.message)
                    put("responseText", text)
                    put("responseHeaders", respHeaders.toString())
                    put("finalUrl", resp.request.url.toString())
                }.toString()
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", 0)
                put("statusText", e.message ?: "Network Error")
                put("responseText", "")
            }.toString()
        }
    }

    private fun buildGlobalExtensionApiPolyfillJs(): String {
        return """
        (function() {
            if (window.__dragonCreateExtensionScope) return;
            window.__dragonExtMessageListeners = window.__dragonExtMessageListeners || [];
            window.__dragonExtBgMessageListeners = window.__dragonExtBgMessageListeners || [];
            window.__dragonExtPopupMessageListeners = window.__dragonExtPopupMessageListeners || [];
            window.__dragonExtConnectListeners = window.__dragonExtConnectListeners || [];
            window.__dragonExtStorageChangedListeners = window.__dragonExtStorageChangedListeners || [];
            window.__dragonExtStorageCache = window.__dragonExtStorageCache || {};
            window.__dragonExtAuthRequiredListeners = window.__dragonExtAuthRequiredListeners || [];
            window.__dragonExtProxyChangeListeners = window.__dragonExtProxyChangeListeners || [];
            window.__dragonExtProxyState = window.__dragonExtProxyState || {};

            try {
                if (typeof Promise !== 'undefined' && typeof Promise.withResolvers !== 'function') {
                    Promise.withResolvers = function() {
                        var resolve, reject;
                        var promise = new Promise(function(res, rej) {
                            resolve = res;
                            reject = rej;
                        });
                        return { promise: promise, resolve: resolve, reject: reject };
                    };
                }
            } catch (e) {}

            try {
                if (!navigator.locks || typeof navigator.locks.request !== 'function') {
                    var locksShim = {
                        request: function(name, optionsOrCb, maybeCb) {
                            var fn = (typeof optionsOrCb === 'function') ? optionsOrCb : maybeCb;
                            if (typeof fn === 'function') {
                                return Promise.resolve().then(function() { return fn({ name: name, mode: 'exclusive' }); });
                            }
                            return Promise.resolve();
                        },
                        query: function() { return Promise.resolve({ held: [], pending: [] }); }
                    };
                    Object.defineProperty(navigator, 'locks', { value: locksShim, configurable: true });
                }
            } catch (e) {}

            window.__dragonInstallExtensionNetworkHooks = function(extHost) {
                if (window.__dragonExtNetHooksInstalled) return;
                window.__dragonExtNetHooksInstalled = true;
                var rewriteExtOrCorsUrl = function(rawUrl) {
                    var s = String(rawUrl || '');
                    if (/^(chrome|moz)-extension:\/\//i.test(s)) {
                        var path = s.replace(/^(chrome|moz)-extension:\/\/[^\/]+\/?/i, '');
                        return { url: 'https://' + extHost + '/' + path, externalUrl: null };
                    }
                    if (/^https?:\/\//i.test(s)) {
                        var m = s.match(/^https?:\/\/([^\/:?#]+)/i);
                        var targetHost = (m && m[1]) ? m[1].toLowerCase() : '';
                        if (targetHost && targetHost !== 'dragon-ext.local' && !/\.dragon-ext\.local$/i.test(targetHost)) {
                            return {
                                url: 'https://' + extHost + '/__dragon_cors_proxy__?__url=' + encodeURIComponent(s),
                                externalUrl: s
                            };
                        }
                    }
                    return { url: s, externalUrl: null };
                };
                var mapExtUrl = function(rawUrl) {
                    return rewriteExtOrCorsUrl(rawUrl).url;
                };

                var origFetch = window.fetch;
                if (typeof origFetch === 'function') {
                    window.fetch = function(input, init) {
                        try {
                            var raw = (typeof input === 'string') ? input : (input && input.url ? input.url : (typeof URL !== 'undefined' && input instanceof URL ? input.href : ''));
                            var method = ((init && init.method) || (input && input.method) || 'GET').toUpperCase();
                            var info = rewriteExtOrCorsUrl(raw);
                            if (info.externalUrl) {
                                if (method !== 'GET' && method !== 'HEAD' && window.DragonExtensionBridge && window.DragonExtensionBridge.gmXhr) {
                                    return new Promise(function(resolve) {
                                        try {
                                            var hdrs = {};
                                            if (init && init.headers) {
                                                if (typeof init.headers.forEach === 'function') {
                                                    init.headers.forEach(function(v, k) { hdrs[k] = v; });
                                                } else if (typeof init.headers === 'object') {
                                                    Object.assign(hdrs, init.headers);
                                                }
                                            }
                                            var bodyStr = (init && typeof init.body === 'string') ? init.body : null;
                                            var rawResp = window.DragonExtensionBridge.gmXhr(JSON.stringify({
                                                method: method,
                                                url: info.externalUrl,
                                                headers: hdrs,
                                                data: bodyStr
                                            }));
                                            var parsed = JSON.parse(rawResp || '{}');
                                            var st = (parsed.status >= 100 && parsed.status <= 599) ? parsed.status : 200;
                                            var resp = new Response(parsed.responseText || '{}', {
                                                status: st,
                                                statusText: parsed.statusText || 'OK',
                                                headers: { 'Content-Type': 'application/json' }
                                            });
                                            try { Object.defineProperty(resp, 'url', { value: info.externalUrl }); } catch (e) {}
                                            resolve(resp);
                                        } catch (err) {
                                            resolve(new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }));
                                        }
                                    });
                                }
                                var nextInit = init ? Object.assign({}, init) : undefined;
                                return origFetch.call(this, info.url, nextInit).then(function(resp) {
                                    try { Object.defineProperty(resp, 'url', { value: info.externalUrl }); } catch (e) {}
                                    return resp;
                                }).catch(function() {
                                    return new Response('{}', { status: 200, statusText: 'OK', headers: { 'Content-Type': 'application/json' } });
                                });
                            }
                            if (info.url !== raw) {
                                input = (typeof input === 'string' || (typeof URL !== 'undefined' && input instanceof URL)) ? info.url : new Request(info.url, input);
                            }
                        } catch (e) {}
                        return origFetch.call(this, input, init);
                    };
                }

                if (window.XMLHttpRequest && window.XMLHttpRequest.prototype && window.XMLHttpRequest.prototype.open) {
                    var origXhrOpen = window.XMLHttpRequest.prototype.open;
                    window.XMLHttpRequest.prototype.open = function(method, url) {
                        var rest = Array.prototype.slice.call(arguments, 2);
                        var nextUrl = url;
                        try {
                            if (typeof url === 'string' || (typeof URL !== 'undefined' && url instanceof URL)) {
                                nextUrl = rewriteExtOrCorsUrl(String(url)).url;
                            }
                        } catch (e) {}
                        return origXhrOpen.apply(this, [method, nextUrl].concat(rest));
                    };
                }
            };

            window.__dragonCreateExtensionScope = function(extId, extMeta, initialKv, i18nMap, activeTabContext) {
                window.__dragonExtStorageCache[extId] = Object.assign({}, initialKv || {}, window.__dragonExtStorageCache[extId] || {});
                var extHostSafe = String(extId || '').trim().toLowerCase().replace(/_/g, '-') + '.dragon-ext.local';
                var messages = i18nMap || {};
                var currentTabUrl = (activeTabContext && activeTabContext.url) ? activeTabContext.url : location.href;
                var currentTabTitle = (activeTabContext && activeTabContext.title) ? activeTabContext.title : document.title;
                if (activeTabContext && window.DragonExtensionBridge && window.DragonExtensionBridge.closePopup) {
                    try {
                        window.close = function() {
                            try { window.DragonExtensionBridge.closePopup(); } catch (e) {}
                        };
                    } catch (e) {}
                }

                var readStore = function() {
                    try {
                        if (window.DragonExtensionBridge && window.DragonExtensionBridge.storageGet) {
                            var raw = window.DragonExtensionBridge.storageGet(extId);
                            if (raw) {
                                window.__dragonExtStorageCache[extId] = Object.assign({}, window.__dragonExtStorageCache[extId] || {}, JSON.parse(raw));
                            }
                        }
                    } catch (e) {}
                    return window.__dragonExtStorageCache[extId] || {};
                };

                var makeEventTarget = function(arr, onFirstAdded) {
                    return {
                        addListener: function(fn) {
                            if (typeof fn === 'function' && arr.indexOf(fn) === -1) {
                                arr.push(fn);
                                if (typeof onFirstAdded === 'function') {
                                    try { onFirstAdded(fn); } catch (e) {}
                                }
                            }
                        },
                        removeListener: function(fn) {
                            var idx = arr.indexOf(fn);
                            if (idx >= 0) arr.splice(idx, 1);
                        },
                        hasListener: function(fn) { return arr.indexOf(fn) >= 0; },
                        hasListeners: function() { return arr.length > 0; }
                    };
                };

                var notifyStorageChanged = function(changes, areaName) {
                    if (!changes || !Object.keys(changes).length) return;
                    for (var i = 0; i < window.__dragonExtStorageChangedListeners.length; i++) {
                        try { window.__dragonExtStorageChangedListeners[i](changes, areaName || 'local'); } catch (e) {}
                    }
                };

                var storageArea = {
                    get: function(keys, callback) {
                        var store = readStore();
                        var result = {};
                        if (keys === null || keys === undefined) {
                            result = Object.assign({}, store);
                        } else if (typeof keys === 'string') {
                            if (keys in store) result[keys] = store[keys];
                        } else if (Array.isArray(keys)) {
                            for (var i = 0; i < keys.length; i++) {
                                var k = keys[i];
                                if (k in store) result[k] = store[k];
                            }
                        } else if (typeof keys === 'object') {
                            for (var prop in keys) {
                                result[prop] = (prop in store) ? store[prop] : keys[prop];
                            }
                        }
                        if (typeof callback === 'function') {
                            setTimeout(function() { callback(result); }, 0);
                        }
                        return Promise.resolve(result);
                    },
                    set: function(items, callback) {
                        try {
                            var patch = items || {};
                            var oldStore = Object.assign({}, window.__dragonExtStorageCache[extId] || {});
                            var changes = {};
                            for (var k in patch) {
                                changes[k] = { oldValue: oldStore[k], newValue: patch[k] };
                            }
                            Object.assign(window.__dragonExtStorageCache[extId], patch);
                            if (window.DragonExtensionBridge && window.DragonExtensionBridge.storageSet) {
                                window.DragonExtensionBridge.storageSet(extId, JSON.stringify(patch));
                            }
                            notifyStorageChanged(changes, 'local');
                        } catch (e) {}
                        if (typeof callback === 'function') {
                            setTimeout(function() { callback(); }, 0);
                        }
                        return Promise.resolve();
                    },
                    remove: function(keys, callback) {
                        try {
                            var arr = Array.isArray(keys) ? keys : [String(keys)];
                            var changes = {};
                            for (var i = 0; i < arr.length; i++) {
                                var k = arr[i];
                                if (k in window.__dragonExtStorageCache[extId]) {
                                    changes[k] = { oldValue: window.__dragonExtStorageCache[extId][k], newValue: undefined };
                                }
                                delete window.__dragonExtStorageCache[extId][k];
                            }
                            if (window.DragonExtensionBridge && window.DragonExtensionBridge.storageRemove) {
                                window.DragonExtensionBridge.storageRemove(extId, JSON.stringify(arr));
                            }
                            notifyStorageChanged(changes, 'local');
                        } catch (e) {}
                        if (typeof callback === 'function') {
                            setTimeout(function() { callback(); }, 0);
                        }
                        return Promise.resolve();
                    },
                    clear: function(callback) {
                        try {
                            window.__dragonExtStorageCache[extId] = {};
                            if (window.DragonExtensionBridge && window.DragonExtensionBridge.storageClear) {
                                window.DragonExtensionBridge.storageClear(extId);
                            }
                        } catch (e) {}
                        if (typeof callback === 'function') {
                            setTimeout(function() { callback(); }, 0);
                        }
                        return Promise.resolve();
                    },
                    setAccessLevel: function(accessOptions, callback) {
                        if (typeof callback === 'function') setTimeout(callback, 0);
                        return Promise.resolve();
                    },
                    getKeys: function(callback) {
                        var keys = Object.keys(readStore());
                        if (typeof callback === 'function') setTimeout(function() { callback(keys); }, 0);
                        return Promise.resolve(keys);
                    },
                    getBytesInUse: function(keys, callback) {
                        var cb = (typeof keys === 'function') ? keys : callback;
                        var bytes = JSON.stringify(readStore()).length;
                        if (typeof cb === 'function') setTimeout(function() { cb(bytes); }, 0);
                        return Promise.resolve(bytes);
                    },
                    QUOTA_BYTES: 10485760,
                    QUOTA_BYTES_PER_ITEM: 8192,
                    MAX_ITEMS: 512,
                    onChanged: makeEventTarget(window.__dragonExtStorageChangedListeners)
                };

                var onMessageEvent = {
                    addListener: function(fn) {
                        if (typeof fn !== 'function') return;
                        if (window.__dragonExtMessageListeners.indexOf(fn) === -1) {
                            window.__dragonExtMessageListeners.push(fn);
                        }
                        var targetArr = window.__dragonIsBackgroundWorker ? window.__dragonExtBgMessageListeners : window.__dragonExtPopupMessageListeners;
                        if (targetArr && targetArr.indexOf(fn) === -1) {
                            targetArr.push(fn);
                        }
                    },
                    removeListener: function(fn) {
                        var idx = window.__dragonExtMessageListeners.indexOf(fn);
                        if (idx >= 0) window.__dragonExtMessageListeners.splice(idx, 1);
                        if (window.__dragonExtBgMessageListeners) {
                            var bIdx = window.__dragonExtBgMessageListeners.indexOf(fn);
                            if (bIdx >= 0) window.__dragonExtBgMessageListeners.splice(bIdx, 1);
                        }
                        if (window.__dragonExtPopupMessageListeners) {
                            var pIdx = window.__dragonExtPopupMessageListeners.indexOf(fn);
                            if (pIdx >= 0) window.__dragonExtPopupMessageListeners.splice(pIdx, 1);
                        }
                    },
                    hasListener: function(fn) { return window.__dragonExtMessageListeners.indexOf(fn) >= 0; },
                    hasListeners: function() { return window.__dragonExtMessageListeners.length > 0; }
                };
                var onConnectEvent = makeEventTarget(window.__dragonExtConnectListeners);
                var onInstalledListeners = [];
                var onInstalledEvent = makeEventTarget(onInstalledListeners, function(fn) {
                    if (window.__dragonIsBackgroundWorker) {
                        Promise.resolve().then(function() {
                            try {
                                fn({ reason: 'install', previousVersion: undefined });
                            } catch (e) {}
                        });
                    }
                });

                var createPortPair = function(portName) {
                    var clientMsgListeners = [];
                    var clientDiscListeners = [];
                    var hostMsgListeners = [];
                    var hostDiscListeners = [];
                    var pendingToHost = [];
                    var pendingToClient = [];
                    var senderMeta = {
                        id: extId,
                        url: currentTabUrl,
                        origin: 'https://' + extHostSafe,
                        tab: { id: 1, windowId: 1, active: true, url: currentTabUrl, pendingUrl: currentTabUrl, title: currentTabTitle }
                    };
                    var clientPort = {
                        name: portName || 'dragon-port',
                        sender: senderMeta,
                        onMessage: makeEventTarget(clientMsgListeners, function() {
                            if (pendingToClient.length > 0) {
                                var queued = pendingToClient.splice(0, pendingToClient.length);
                                setTimeout(function() {
                                    for (var q = 0; q < queued.length; q++) {
                                        for (var i = 0; i < clientMsgListeners.length; i++) {
                                            try { clientMsgListeners[i](queued[q], clientPort); } catch (e) {}
                                        }
                                    }
                                }, 0);
                            }
                        }),
                        onDisconnect: makeEventTarget(clientDiscListeners),
                        postMessage: function(msg) {
                            setTimeout(function() {
                                if (hostMsgListeners.length === 0) {
                                    pendingToHost.push(msg);
                                    return;
                                }
                                for (var i = 0; i < hostMsgListeners.length; i++) {
                                    try { hostMsgListeners[i](msg, hostPort); } catch (e) {}
                                }
                            }, 0);
                        },
                        disconnect: function() {
                            for (var i = 0; i < hostDiscListeners.length; i++) {
                                try { hostDiscListeners[i](hostPort); } catch (e) {}
                            }
                        }
                    };
                    var hostPort = {
                        name: portName || 'dragon-port',
                        sender: senderMeta,
                        onMessage: makeEventTarget(hostMsgListeners, function() {
                            if (pendingToHost.length > 0) {
                                var queued = pendingToHost.splice(0, pendingToHost.length);
                                setTimeout(function() {
                                    for (var q = 0; q < queued.length; q++) {
                                        for (var i = 0; i < hostMsgListeners.length; i++) {
                                            try { hostMsgListeners[i](queued[q], hostPort); } catch (e) {}
                                        }
                                    }
                                }, 0);
                            }
                        }),
                        onDisconnect: makeEventTarget(hostDiscListeners),
                        postMessage: function(msg) {
                            setTimeout(function() {
                                if (clientMsgListeners.length === 0) {
                                    pendingToClient.push(msg);
                                    return;
                                }
                                for (var i = 0; i < clientMsgListeners.length; i++) {
                                    try { clientMsgListeners[i](msg, clientPort); } catch (e) {}
                                }
                            }, 0);
                        },
                        disconnect: function() {
                            for (var i = 0; i < clientDiscListeners.length; i++) {
                                try { clientDiscListeners[i](clientPort); } catch (e) {}
                            }
                        }
                    };
                    return { client: clientPort, host: hostPort };
                };

                var dispatchSendMessage = function() {
                    var args = Array.prototype.slice.call(arguments);
                    var cb = (args.length > 0 && typeof args[args.length - 1] === 'function') ? args.pop() : null;
                    var message = (args.length >= 2 && typeof args[0] === 'string') ? args[1] : args[0];
                    var fromBg = !!window.__dragonIsBackgroundWorker;
                    return new Promise(function(resolve) {
                        var attempts = 0;
                        var settledAfterBgReady = false;
                        var attemptDispatch = function() {
                            var bgListeners = window.__dragonExtBgMessageListeners || [];
                            if (!fromBg && window.__dragonExtHasBackground) {
                                if (bgListeners.length === 0 && (!window.__dragonExtBgReady || attempts < 20) && attempts < 120) {
                                    attempts++;
                                    setTimeout(attemptDispatch, 25);
                                    return;
                                }
                                if (!settledAfterBgReady && bgListeners.length > 0) {
                                    settledAfterBgReady = true;
                                    setTimeout(attemptDispatch, 20);
                                    return;
                                }
                            }
                            var responded = false;
                            var finish = function(reply) {
                                if (!responded) {
                                    responded = true;
                                    if (cb) {
                                        try { cb(reply); } catch (e) {}
                                    }
                                    resolve(reply);
                                }
                            };
                            var senderObj = {
                                id: extId,
                                url: currentTabUrl,
                                origin: 'https://' + extHostSafe,
                                tab: { id: 1, windowId: 1, active: true, url: currentTabUrl, pendingUrl: currentTabUrl, title: currentTabTitle }
                            };
                            var targetListeners = [];
                            if (!fromBg && bgListeners.length > 0) {
                                targetListeners = bgListeners.slice();
                                var popListeners = window.__dragonExtPopupMessageListeners || [];
                                for (var p = 0; p < popListeners.length; p++) {
                                    if (targetListeners.indexOf(popListeners[p]) === -1) {
                                        targetListeners.push(popListeners[p]);
                                    }
                                }
                            } else {
                                targetListeners = (window.__dragonExtMessageListeners || []).slice();
                            }
                            var pendingPromises = 0;
                            var explicitAsyncCallback = false;
                            for (var i = 0; i < targetListeners.length; i++) {
                                try {
                                    var ret = targetListeners[i](message, senderObj, finish);
                                    if (ret && typeof ret.then === 'function') {
                                        pendingPromises++;
                                        ret.then(function(val) {
                                            pendingPromises--;
                                            if (val !== undefined) {
                                                finish(val);
                                            } else if (pendingPromises === 0 && !explicitAsyncCallback) {
                                                finish(undefined);
                                            }
                                        }).catch(function() {
                                            pendingPromises--;
                                            if (pendingPromises === 0 && !explicitAsyncCallback) {
                                                finish(undefined);
                                            }
                                        });
                                    } else if (ret === true) {
                                        explicitAsyncCallback = true;
                                    } else if (ret !== undefined && ret !== false) {
                                        finish(ret);
                                    }
                                } catch (e) {}
                            }
                            if (pendingPromises === 0 && !explicitAsyncCallback) {
                                setTimeout(function() { finish(undefined); }, 60);
                            } else {
                                setTimeout(function() { finish(undefined); }, 2500);
                            }
                        };
                        setTimeout(attemptDispatch, 0);
                    });
                };

                var chromeObj = {
                    runtime: {
                        id: extId,
                        lastError: null,
                        OnInstalledReason: { INSTALL: 'install', UPDATE: 'update', CHROME_UPDATE: 'chrome_update', SHARED_MODULE_UPDATE: 'shared_module_update' },
                        ContextType: { TAB: 'TAB', POPUP: 'POPUP', BACKGROUND: 'BACKGROUND', OFFSCREEN_DOCUMENT: 'OFFSCREEN_DOCUMENT', SIDE_PANEL: 'SIDE_PANEL' },
                        getManifest: function() { return extMeta || { name: extId, version: "1.0", manifest_version: 3 }; },
                        getURL: function(path) {
                            var clean = String(path || '').replace(/^\/+/, '');
                            return 'https://' + extHostSafe + '/' + clean;
                        },
                        getBackgroundPage: function(cb) {
                            if (typeof cb === 'function') setTimeout(function() { cb(null); }, 0);
                            return Promise.resolve(null);
                        },
                        getContexts: function(filter, cb) {
                            var ctxs = [{ contextType: 'POPUP', documentUrl: location.href, tabId: -1, windowId: 1 }];
                            if (typeof cb === 'function') setTimeout(function() { cb(ctxs); }, 0);
                            return Promise.resolve(ctxs);
                        },
                        getPlatformInfo: function(cb) {
                            var info = { os: 'android', arch: 'arm64', nacl_arch: 'arm' };
                            if (typeof cb === 'function') setTimeout(function() { cb(info); }, 0);
                            return Promise.resolve(info);
                        },
                        setUninstallURL: function(url, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                        reload: function() { try { location.reload(); } catch (e) {} },
                        requestUpdateCheck: function(cb) {
                            var res = { status: 'no_update' };
                            if (typeof cb === 'function') setTimeout(function() { cb('no_update', {}); }, 0);
                            return Promise.resolve(res);
                        },
                        sendMessage: dispatchSendMessage,
                        connect: function(connectInfo) {
                            var nm = (typeof connectInfo === 'string') ? connectInfo : (connectInfo && connectInfo.name);
                            var pair = createPortPair(nm);
                            var connAttempts = 0;
                            var fireConnect = function() {
                                if (window.__dragonExtHasBackground &&
                                    window.__dragonExtConnectListeners.length === 0 &&
                                    connAttempts < 60) {
                                    connAttempts++;
                                    setTimeout(fireConnect, 25);
                                    return;
                                }
                                for (var i = 0; i < window.__dragonExtConnectListeners.length; i++) {
                                    try { window.__dragonExtConnectListeners[i](pair.host); } catch (e) {}
                                }
                            };
                            setTimeout(fireConnect, 0);
                            return pair.client;
                        },
                        openOptionsPage: function(cb) {
                            var optPage = (extMeta && extMeta.options_page) || (extMeta && extMeta.options_ui && extMeta.options_ui.page);
                            if (optPage && window.DragonExtensionBridge && window.DragonExtensionBridge.openTab) {
                                window.DragonExtensionBridge.openTab('https://' + extHostSafe + '/' + String(optPage).replace(/^\/+/, ''));
                            }
                            if (typeof cb === 'function') setTimeout(cb, 0);
                            return Promise.resolve();
                        },
                        connectNative: function(application) {
                            return createPortPair(application || 'native-app').client;
                        },
                        sendNativeMessage: function(application, message, cb) {
                            var c = (typeof message === 'function') ? message : cb;
                            if (typeof c === 'function') setTimeout(function() { c({}); }, 0);
                            return Promise.resolve({});
                        },
                        onMessage: onMessageEvent,
                        onMessageExternal: makeEventTarget([]),
                        onConnect: onConnectEvent,
                        onConnectExternal: makeEventTarget([]),
                        onUserScriptConnect: makeEventTarget([]),
                        onUserScriptMessage: makeEventTarget([]),
                        onInstalled: onInstalledEvent,
                        onStartup: makeEventTarget([]),
                        onSuspend: makeEventTarget([]),
                        onSuspendCanceled: makeEventTarget([]),
                        onUpdateAvailable: makeEventTarget([]),
                        onRestartRequired: makeEventTarget([]),
                        onBrowserUpdateAvailable: makeEventTarget([])
                    },
                    extension: {
                        inIncognitoContext: false,
                        lastError: null,
                        getURL: function(path) {
                            var clean = String(path || '').replace(/^\/+/, '');
                            return 'https://' + extHostSafe + '/' + clean;
                        },
                        getBackgroundPage: function() { return null; },
                        getViews: function(fetchProperties) { return [window]; },
                        isAllowedIncognitoAccess: function(cb) { if (typeof cb === 'function') cb(true); return Promise.resolve(true); },
                        isAllowedFileSchemeAccess: function(cb) { if (typeof cb === 'function') cb(false); return Promise.resolve(false); },
                        sendMessage: dispatchSendMessage,
                        sendRequest: dispatchSendMessage,
                        onMessage: onMessageEvent,
                        onRequest: onMessageEvent
                    },
                    storage: {
                        local: storageArea,
                        sync: storageArea,
                        session: storageArea,
                        managed: storageArea,
                        onChanged: makeEventTarget(window.__dragonExtStorageChangedListeners)
                    },
                    i18n: {
                        getMessage: function(key, substitutions) {
                            var k = String(key || '');
                            if (k === '@@extension_id') return extId;
                            if (k === '@@ui_locale') return navigator.language || 'en';
                            if (k === '@@bidi_dir') return 'ltr';
                            var raw = messages[k] || messages[k.toLowerCase()] || '';
                            if (!raw) return '';
                            if (substitutions !== undefined && substitutions !== null) {
                                var subs = Array.isArray(substitutions) ? substitutions : [String(substitutions)];
                                for (var i = 0; i < subs.length; i++) {
                                    raw = raw.split('$' + (i + 1)).join(String(subs[i]));
                                }
                            }
                            return raw;
                        },
                        getUILanguage: function() { return navigator.language || 'en-US'; },
                        getAcceptLanguages: function(cb) {
                            var langs = [navigator.language || 'en-US', 'en'];
                            if (typeof cb === 'function') setTimeout(function() { cb(langs); }, 0);
                            return Promise.resolve(langs);
                        },
                        detectLanguage: function(text, cb) {
                            var res = { isReliable: true, languages: [{ language: 'en', percentage: 100 }] };
                            if (typeof cb === 'function') setTimeout(function() { cb(res); }, 0);
                            return Promise.resolve(res);
                        }
                    },
                    tabs: {
                        TAB_ID_NONE: -1,
                        query: function(queryInfo, callback) {
                            var cb = (typeof queryInfo === 'function') ? queryInfo : callback;
                            var tabs = [{
                                id: 1, windowId: 1, index: 0, active: true, highlighted: true, currentWindow: true,
                                incognito: false, pinned: false, audible: false, discarded: false, autoDiscardable: true,
                                mutedInfo: { muted: false }, groupId: -1, favIconUrl: '', status: 'complete',
                                url: currentTabUrl, pendingUrl: currentTabUrl, title: currentTabTitle
                            }];
                            if (typeof cb === 'function') setTimeout(function() { cb(tabs); }, 0);
                            return Promise.resolve(tabs);
                        },
                        get: function(tabId, callback) {
                            var t = {
                                id: tabId || 1, windowId: 1, index: 0, active: true, highlighted: true, currentWindow: true,
                                incognito: false, pinned: false, audible: false, discarded: false, autoDiscardable: true,
                                mutedInfo: { muted: false }, groupId: -1, favIconUrl: '', status: 'complete',
                                url: currentTabUrl, pendingUrl: currentTabUrl, title: currentTabTitle
                            };
                            if (typeof callback === 'function') setTimeout(function() { callback(t); }, 0);
                            return Promise.resolve(t);
                        },
                        getCurrent: function(callback) {
                            return chromeObj.tabs.get(1, callback);
                        },
                        getSelected: function(windowId, callback) {
                            var cb = (typeof windowId === 'function') ? windowId : callback;
                            return chromeObj.tabs.get(1, cb);
                        },
                        getAllInWindow: function(windowId, callback) {
                            var cb = (typeof windowId === 'function') ? windowId : callback;
                            return chromeObj.tabs.query({ currentWindow: true }, cb);
                        },
                        create: function(createProperties, callback) {
                            var targetUrl = createProperties && createProperties.url ? String(createProperties.url) : '';
                            if (targetUrl) {
                                if (window.__dragonIsBackgroundWorker) {
                                    if (targetUrl.indexOf('#/settings') >= 0 && window.parent && window.parent !== window) {
                                        try { window.parent.location.hash = targetUrl.substring(targetUrl.indexOf('#')); } catch (e) {}
                                    }
                                } else if (window.DragonExtensionBridge && window.DragonExtensionBridge.openTab) {
                                    window.DragonExtensionBridge.openTab(targetUrl);
                                }
                            }
                            var created = {
                                id: 2, windowId: 1, index: 1, url: targetUrl, pendingUrl: targetUrl, active: true,
                                incognito: false, pinned: false, status: 'complete'
                            };
                            if (typeof callback === 'function') setTimeout(function() { callback(created); }, 0);
                            return Promise.resolve(created);
                        },
                        update: function(tabIdOrProps, maybeProps, callback) {
                            var props = (typeof tabIdOrProps === 'object') ? tabIdOrProps : (maybeProps || {});
                            var cb = (typeof maybeProps === 'function') ? maybeProps : callback;
                            if (props && props.url && !window.__dragonIsBackgroundWorker && window.DragonExtensionBridge && window.DragonExtensionBridge.updateActiveTabUrl) {
                                window.DragonExtensionBridge.updateActiveTabUrl(String(props.url));
                            }
                            var updated = {
                                id: 1, windowId: 1, index: 0, active: true,
                                url: (props && props.url) || currentTabUrl, pendingUrl: (props && props.url) || currentTabUrl, title: currentTabTitle
                            };
                            if (typeof cb === 'function') setTimeout(function() { cb(updated); }, 0);
                            return Promise.resolve(updated);
                        },
                        reload: function(tabId, reloadProps, callback) {
                            var cb = (typeof tabId === 'function') ? tabId : (typeof reloadProps === 'function' ? reloadProps : callback);
                            if (window.DragonExtensionBridge && window.DragonExtensionBridge.reloadActiveTab) {
                                window.DragonExtensionBridge.reloadActiveTab();
                            }
                            if (typeof cb === 'function') setTimeout(cb, 0);
                            return Promise.resolve();
                        },
                        remove: function(tabIds, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                        duplicate: function(tabId, cb) { return chromeObj.tabs.get(2, cb); },
                        highlight: function(info, cb) { return chromeObj.windows.getCurrent(null, cb); },
                        goBack: function(tabId, cb) { var c = (typeof tabId === 'function') ? tabId : cb; if (typeof c === 'function') setTimeout(c, 0); return Promise.resolve(); },
                        goForward: function(tabId, cb) { var c = (typeof tabId === 'function') ? tabId : cb; if (typeof c === 'function') setTimeout(c, 0); return Promise.resolve(); },
                        getZoom: function(tabId, cb) { var c = (typeof tabId === 'function') ? tabId : cb; if (typeof c === 'function') setTimeout(function() { c(1.0); }, 0); return Promise.resolve(1.0); },
                        setZoom: function(tabId, factor, cb) { var c = (typeof factor === 'function') ? factor : cb; if (typeof c === 'function') setTimeout(c, 0); return Promise.resolve(); },
                        getZoomSettings: function(tabId, cb) { var c = (typeof tabId === 'function') ? tabId : cb; var s = { mode: 'automatic', scope: 'per-origin', defaultZoomFactor: 1.0 }; if (typeof c === 'function') setTimeout(function() { c(s); }, 0); return Promise.resolve(s); },
                        setZoomSettings: function(tabId, s, cb) { var c = (typeof s === 'function') ? s : cb; if (typeof c === 'function') setTimeout(c, 0); return Promise.resolve(); },
                        detectLanguage: function(tabId, cb) { var c = (typeof tabId === 'function') ? tabId : cb; if (typeof c === 'function') setTimeout(function() { c('en'); }, 0); return Promise.resolve('en'); },
                        captureVisibleTab: function(winId, opts, cb) { var c = (typeof opts === 'function') ? opts : cb; if (typeof c === 'function') setTimeout(function() { c(''); }, 0); return Promise.resolve(''); },
                        connect: function(tabId, connectInfo) { return chromeObj.runtime.connect(connectInfo); },
                        sendMessage: function(tabId, message, options, callback) {
                            var cb = (typeof options === 'function') ? options : callback;
                            if (window.DragonExtensionBridge && window.DragonExtensionBridge.evalInActiveTab && activeTabContext) {
                                var evalCode = '(function(){try{if(window.__dragonExtMessageListeners){for(var i=0;i<window.__dragonExtMessageListeners.length;i++){window.__dragonExtMessageListeners[i](' + JSON.stringify(message) + ',{id:' + JSON.stringify(extId) + ',tab:{id:1}},function(){});}}}catch(e){}})();';
                                window.DragonExtensionBridge.evalInActiveTab(evalCode);
                            }
                            if (typeof cb === 'function') setTimeout(function() { cb(undefined); }, 0);
                            return Promise.resolve(undefined);
                        },
                        executeScript: function(tabId, details, callback) {
                            var det = (typeof tabId === 'object') ? tabId : details;
                            var cb = (typeof details === 'function') ? details : callback;
                            return chromeObj.scripting.executeScript(det, cb);
                        },
                        insertCSS: function(tabId, details, callback) {
                            var det = (typeof tabId === 'object') ? tabId : details;
                            var cb = (typeof details === 'function') ? details : callback;
                            return chromeObj.scripting.insertCSS(det, cb);
                        },
                        removeCSS: function(tabId, details, callback) {
                            var cb = (typeof details === 'function') ? details : callback;
                            if (typeof cb === 'function') setTimeout(cb, 0);
                            return Promise.resolve();
                        },
                        onUpdated: makeEventTarget([], function(fn) {
                            if (window.__dragonIsBackgroundWorker) {
                                setTimeout(function() {
                                    try {
                                        fn(1, { status: 'complete', url: currentTabUrl }, {
                                            id: 1, windowId: 1, index: 0, active: true, highlighted: true, currentWindow: true,
                                            incognito: false, pinned: false, status: 'complete', url: currentTabUrl, pendingUrl: currentTabUrl, title: currentTabTitle
                                        });
                                    } catch (e) {}
                                }, 10);
                            }
                        }),
                        onActivated: makeEventTarget([]),
                        onRemoved: makeEventTarget([]),
                        onCreated: makeEventTarget([]),
                        onReplaced: makeEventTarget([]),
                        onMoved: makeEventTarget([]),
                        onHighlighted: makeEventTarget([]),
                        onZoomChange: makeEventTarget([]),
                        onAttached: makeEventTarget([]),
                        onDetached: makeEventTarget([])
                    },
                    tabGroups: {
                        TAB_GROUP_ID_NONE: -1,
                        query: function(info, cb) { var c = (typeof info === 'function') ? info : cb; if (typeof c === 'function') setTimeout(function() { c([]); }, 0); return Promise.resolve([]); },
                        get: function(id, cb) { var g = { id: id || 1, collapsed: false, color: 'grey', title: '', windowId: 1 }; if (typeof cb === 'function') setTimeout(function() { cb(g); }, 0); return Promise.resolve(g); },
                        update: function(id, props, cb) { var g = { id: id || 1, collapsed: false, color: 'grey', title: '', windowId: 1 }; if (typeof cb === 'function') setTimeout(function() { cb(g); }, 0); return Promise.resolve(g); },
                        onCreated: makeEventTarget([]),
                        onUpdated: makeEventTarget([]),
                        onRemoved: makeEventTarget([]),
                        onMoved: makeEventTarget([])
                    },
                    windows: {
                        WINDOW_ID_CURRENT: 1,
                        WINDOW_ID_NONE: -1,
                        getCurrent: function(getInfo, cb) {
                            var callback = (typeof getInfo === 'function') ? getInfo : cb;
                            var w = {
                                id: 1, focused: true, type: 'normal', incognito: false, state: 'normal',
                                tabs: [{
                                    id: 1, windowId: 1, index: 0, active: true, highlighted: true,
                                    url: currentTabUrl, pendingUrl: currentTabUrl, title: currentTabTitle, status: 'complete'
                                }]
                            };
                            if (typeof callback === 'function') setTimeout(function() { callback(w); }, 0);
                            return Promise.resolve(w);
                        },
                        getLastFocused: function(getInfo, cb) { return chromeObj.windows.getCurrent(getInfo, cb); },
                        get: function(winId, getInfo, cb) {
                            var callback = (typeof getInfo === 'function') ? getInfo : cb;
                            return chromeObj.windows.getCurrent(null, callback);
                        },
                        getAll: function(getInfo, cb) {
                            var callback = (typeof getInfo === 'function') ? getInfo : cb;
                            var list = [{
                                id: 1, focused: true, type: 'normal', incognito: false, state: 'normal',
                                tabs: [{
                                    id: 1, windowId: 1, index: 0, active: true, highlighted: true,
                                    url: currentTabUrl, pendingUrl: currentTabUrl, title: currentTabTitle, status: 'complete'
                                }]
                            }];
                            if (typeof callback === 'function') setTimeout(function() { callback(list); }, 0);
                            return Promise.resolve(list);
                        },
                        create: function(createData, cb) {
                            if (createData && createData.url && !window.__dragonIsBackgroundWorker && window.DragonExtensionBridge && window.DragonExtensionBridge.openTab) {
                                window.DragonExtensionBridge.openTab(String(createData.url));
                            }
                            var w = { id: 2, focused: true, type: 'normal', tabs: [] };
                            if (typeof cb === 'function') setTimeout(function() { cb(w); }, 0);
                            return Promise.resolve(w);
                        },
                        update: function(winId, updateInfo, cb) {
                            var w = { id: winId || 1, focused: true };
                            if (typeof cb === 'function') setTimeout(function() { cb(w); }, 0);
                            return Promise.resolve(w);
                        },
                        remove: function(winId, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                        onFocusChanged: makeEventTarget([]),
                        onCreated: makeEventTarget([]),
                        onRemoved: makeEventTarget([]),
                        onBoundsChanged: makeEventTarget([])
                    },
                    scripting: {
                        executeScript: function(injection, callback) {
                            try {
                                var codeStr = '';
                                if (injection && typeof injection.func === 'function') {
                                    codeStr = '(' + injection.func.toString() + ').apply(window, ' + JSON.stringify(injection.args || []) + ');';
                                } else if (injection && injection.code) {
                                    codeStr = String(injection.code);
                                }
                                if (injection && Array.isArray(injection.files) && window.DragonExtensionBridge && window.DragonExtensionBridge.evalFilesInActiveTab) {
                                    window.DragonExtensionBridge.evalFilesInActiveTab(extId, JSON.stringify(injection.files));
                                    if (typeof callback === 'function') setTimeout(function() { callback([]); }, 0);
                                    return Promise.resolve([]);
                                }
                                if (codeStr && activeTabContext && window.DragonExtensionBridge && window.DragonExtensionBridge.evalInActiveTab) {
                                    window.DragonExtensionBridge.evalInActiveTab(codeStr);
                                    if (typeof callback === 'function') setTimeout(function() { callback([{ result: null }]); }, 0);
                                    return Promise.resolve([{ result: null }]);
                                }
                                if (injection && typeof injection.func === 'function') {
                                    var res = injection.func.apply(window, injection.args || []);
                                    if (typeof callback === 'function') setTimeout(function() { callback([{ result: res }]); }, 0);
                                    return Promise.resolve([{ result: res }]);
                                }
                            } catch (e) {}
                            return Promise.resolve([]);
                        },
                        insertCSS: function(injection, callback) {
                            try {
                                if (injection && injection.css && activeTabContext && window.DragonExtensionBridge && window.DragonExtensionBridge.insertCssInActiveTab) {
                                    window.DragonExtensionBridge.insertCssInActiveTab(String(injection.css));
                                } else if (injection && Array.isArray(injection.files) && window.DragonExtensionBridge && window.DragonExtensionBridge.insertCssFilesInActiveTab) {
                                    window.DragonExtensionBridge.insertCssFilesInActiveTab(extId, JSON.stringify(injection.files));
                                } else if (injection && injection.css) {
                                    gmAddStyle(injection.css);
                                }
                            } catch (e) {}
                            if (typeof callback === 'function') setTimeout(callback, 0);
                            return Promise.resolve();
                        },
                        removeCSS: function(injection, callback) {
                            if (typeof callback === 'function') setTimeout(callback, 0);
                            return Promise.resolve();
                        },
                        registerContentScripts: function(scripts, cb) {
                            if (typeof cb === 'function') setTimeout(cb, 0);
                            return Promise.resolve();
                        },
                        unregisterContentScripts: function(filter, cb) {
                            if (typeof cb === 'function') setTimeout(cb, 0);
                            return Promise.resolve();
                        },
                        updateContentScripts: function(scripts, cb) {
                            if (typeof cb === 'function') setTimeout(cb, 0);
                            return Promise.resolve();
                        },
                        ExecutionWorld: { ISOLATED: 'ISOLATED', MAIN: 'MAIN' },
                        getRegisteredContentScripts: function(filter, cb) {
                            var c = (typeof filter === 'function') ? filter : cb;
                            if (typeof c === 'function') setTimeout(function() { c([]); }, 0);
                            return Promise.resolve([]);
                        }
                    },
                    action: {
                        setBadgeText: function(details, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                        getBadgeText: function(details, cb) { var c = (typeof details === 'function') ? details : cb; if (typeof c === 'function') setTimeout(function() { c(''); }, 0); return Promise.resolve(''); },
                        setBadgeBackgroundColor: function(details, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                        getBadgeBackgroundColor: function(details, cb) { var c = (typeof details === 'function') ? details : cb; if (typeof c === 'function') setTimeout(function() { c([0, 0, 0, 0]); }, 0); return Promise.resolve([0, 0, 0, 0]); },
                        setBadgeTextColor: function(details, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                        getBadgeTextColor: function(details, cb) { var c = (typeof details === 'function') ? details : cb; if (typeof c === 'function') setTimeout(function() { c([255, 255, 255, 255]); }, 0); return Promise.resolve([255, 255, 255, 255]); },
                        setIcon: function(details, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                        setTitle: function(details, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                        getTitle: function(details, cb) { var c = (typeof details === 'function') ? details : cb; var t = (extMeta && extMeta.name) || ''; if (typeof c === 'function') setTimeout(function() { c(t); }, 0); return Promise.resolve(t); },
                        setPopup: function(details, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                        getPopup: function(details, cb) { var c = (typeof details === 'function') ? details : cb; if (typeof c === 'function') setTimeout(function() { c(''); }, 0); return Promise.resolve(''); },
                        enable: function(tabId, cb) { var c = (typeof tabId === 'function') ? tabId : cb; if (typeof c === 'function') setTimeout(c, 0); return Promise.resolve(); },
                        disable: function(tabId, cb) { var c = (typeof tabId === 'function') ? tabId : cb; if (typeof c === 'function') setTimeout(c, 0); return Promise.resolve(); },
                        isEnabled: function(tabId, cb) { var c = (typeof tabId === 'function') ? tabId : cb; if (typeof c === 'function') setTimeout(function() { c(true); }, 0); return Promise.resolve(true); },
                        openPopup: function(opts, cb) { var c = (typeof opts === 'function') ? opts : cb; if (typeof c === 'function') setTimeout(c, 0); return Promise.resolve(); },
                        getUserSettings: function(cb) {
                            var s = { isOnToolbar: true };
                            if (typeof cb === 'function') setTimeout(function() { cb(s); }, 0);
                            return Promise.resolve(s);
                        },
                        onUserSettingsChanged: makeEventTarget([]),
                        onClicked: makeEventTarget([])
                    },
                    permissions: {
                        contains: function(perms, cb) { if (typeof cb === 'function') setTimeout(function() { cb(true); }, 0); return Promise.resolve(true); },
                        request: function(perms, cb) { if (typeof cb === 'function') setTimeout(function() { cb(true); }, 0); return Promise.resolve(true); },
                        remove: function(perms, cb) { if (typeof cb === 'function') setTimeout(function() { cb(true); }, 0); return Promise.resolve(true); },
                        getAll: function(cb) {
                            var hosts = (extMeta && Array.isArray(extMeta.host_permissions) && extMeta.host_permissions.length) ? extMeta.host_permissions : ['<all_urls>'];
                            var out = { permissions: (extMeta && extMeta.permissions) || [], origins: hosts };
                            if (typeof cb === 'function') setTimeout(function() { cb(out); }, 0);
                            return Promise.resolve(out);
                        },
                        onAdded: makeEventTarget([]),
                        onRemoved: makeEventTarget([])
                    },
                    contextMenus: {
                        ContextType: { ALL: 'all', PAGE: 'page', FRAME: 'frame', SELECTION: 'selection', LINK: 'link', EDITABLE: 'editable', IMAGE: 'image', VIDEO: 'video', AUDIO: 'audio', ACTION: 'action', BROWSER_ACTION: 'browser_action', PAGE_ACTION: 'page_action' },
                        ItemType: { NORMAL: 'normal', CHECKBOX: 'checkbox', RADIO: 'radio', SEPARATOR: 'separator' },
                        create: function(props, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return (props && props.id) || 'menu_1'; },
                        update: function(id, props, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                        remove: function(id, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                        removeAll: function(cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                        onClicked: makeEventTarget([])
                    },
                    alarms: {
                        create: function(name, info, cb) { var c = (typeof info === 'function') ? info : cb; if (typeof c === 'function') setTimeout(c, 0); return Promise.resolve(); },
                        clear: function(name, cb) { var c = (typeof name === 'function') ? name : cb; if (typeof c === 'function') setTimeout(function() { c(true); }, 0); return Promise.resolve(true); },
                        clearAll: function(cb) { if (typeof cb === 'function') setTimeout(function() { cb(true); }, 0); return Promise.resolve(true); },
                        get: function(name, cb) { var c = (typeof name === 'function') ? name : cb; if (typeof c === 'function') setTimeout(function() { c(null); }, 0); return Promise.resolve(null); },
                        getAll: function(cb) { if (typeof cb === 'function') setTimeout(function() { cb([]); }, 0); return Promise.resolve([]); },
                        onAlarm: makeEventTarget([])
                    },
                    declarativeNetRequest: (function() {
                        var defaultRulesets = [];
                        try {
                            var rRes = extMeta && extMeta.declarative_net_request && extMeta.declarative_net_request.rule_resources;
                            if (Array.isArray(rRes)) {
                                for (var rIdx = 0; rIdx < rRes.length; rIdx++) {
                                    if (rRes[rIdx] && rRes[rIdx].id && rRes[rIdx].enabled !== false) {
                                        defaultRulesets.push(String(rRes[rIdx].id));
                                    }
                                }
                            }
                        } catch (e) {}
                        var enabledRulesetsState = defaultRulesets.slice();
                        return {
                            MAX_NUMBER_OF_STATIC_RULESETS: 50,
                            MAX_NUMBER_OF_ENABLED_STATIC_RULESETS: 50,
                            MAX_NUMBER_OF_DYNAMIC_AND_SESSION_RULES: 5000,
                            MAX_NUMBER_OF_UNSAFE_DYNAMIC_RULES: 5000,
                            MAX_NUMBER_OF_UNSAFE_SESSION_RULES: 5000,
                            MAX_NUMBER_OF_REGEX_RULES: 1000,
                            GUARANTEED_MINIMUM_STATIC_RULES: 30000,
                            GETMATCHEDRULES_QUOTA_INTERVAL: 10,
                            DYNAMIC_RULESET_ID: '_dynamic',
                            SESSION_RULESET_ID: '_session',
                            DomainType: { FIRST_PARTY: 'firstParty', THIRD_PARTY: 'thirdParty' },
                            ResourceType: { MAIN_FRAME: 'main_frame', SUB_FRAME: 'sub_frame', STYLESHEET: 'stylesheet', SCRIPT: 'script', IMAGE: 'image', FONT: 'font', OBJECT: 'object', XMLHTTPREQUEST: 'xmlhttprequest', PING: 'ping', CSP_REPORT: 'csp_report', MEDIA: 'media', WEBSOCKET: 'websocket', OTHER: 'other' },
                            RuleActionType: { BLOCK: 'block', REDIRECT: 'redirect', ALLOW: 'allow', UPGRADE_SCHEME: 'upgradeScheme', MODIFY_HEADERS: 'modifyHeaders', ALLOW_ALL_REQUESTS: 'allowAllRequests' },
                            updateDynamicRules: function(opts, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                            getDynamicRules: function(filter, cb) { var c = (typeof filter === 'function') ? filter : cb; if (typeof c === 'function') setTimeout(function() { c([]); }, 0); return Promise.resolve([]); },
                            updateSessionRules: function(opts, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                            getSessionRules: function(filter, cb) { var c = (typeof filter === 'function') ? filter : cb; if (typeof c === 'function') setTimeout(function() { c([]); }, 0); return Promise.resolve([]); },
                            updateStaticRules: function(opts, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                            getDisabledRuleIds: function(opts, cb) { var c = (typeof opts === 'function') ? opts : cb; if (typeof c === 'function') setTimeout(function() { c([]); }, 0); return Promise.resolve([]); },
                            updateEnabledRulesets: function(opts, cb) {
                                try {
                                    if (opts && Array.isArray(opts.disableRulesetIds)) {
                                        enabledRulesetsState = enabledRulesetsState.filter(function(id) { return opts.disableRulesetIds.indexOf(id) === -1; });
                                    }
                                    if (opts && Array.isArray(opts.enableRulesetIds)) {
                                        opts.enableRulesetIds.forEach(function(id) {
                                            if (enabledRulesetsState.indexOf(id) === -1) enabledRulesetsState.push(id);
                                        });
                                    }
                                } catch (e) {}
                                if (typeof cb === 'function') setTimeout(cb, 0);
                                return Promise.resolve();
                            },
                            getEnabledRulesets: function(cb) {
                                var copy = enabledRulesetsState.slice();
                                if (typeof cb === 'function') setTimeout(function() { cb(copy); }, 0);
                                return Promise.resolve(copy);
                            },
                            getAvailableStaticRuleCount: function(cb) { if (typeof cb === 'function') setTimeout(function() { cb(30000); }, 0); return Promise.resolve(30000); },
                            isRegexSupported: function(opts, cb) { var r = { isSupported: true }; if (typeof cb === 'function') setTimeout(function() { cb(r); }, 0); return Promise.resolve(r); },
                            getMatchedRules: function(filter, cb) { var c = (typeof filter === 'function') ? filter : cb; var r = { rulesMatchedInfo: [] }; if (typeof c === 'function') setTimeout(function() { c(r); }, 0); return Promise.resolve(r); },
                            testMatchOutcome: function(req, cb) { var r = { matchedRules: [] }; if (typeof cb === 'function') setTimeout(function() { cb(r); }, 0); return Promise.resolve(r); },
                            setExtensionActionOptions: function(opts, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                            onRuleMatchedDebug: makeEventTarget([])
                        };
                    })(),
                    webRequest: (function() {
                        var authRequiredListeners = window.__dragonExtAuthRequiredListeners = window.__dragonExtAuthRequiredListeners || [];
                        var sharedState = window.__dragonExtProxyState = window.__dragonExtProxyState || {};
                        var probeSingleAuthListener = function(fn, explicitHost, explicitPort) {
                            if (typeof fn !== 'function') return;
                            try {
                                var activeHost = explicitHost || 'vpn.proxy.server';
                                var activePort = explicitPort || 443;
                                try {
                                    var curP = sharedState.currentValue || window.__dragonExtProxyCurrentValue;
                                    if (!explicitHost && curP) {
                                        if (curP.rules) {
                                            var sp = curP.rules.singleProxy || curP.rules.proxyForHttps || curP.rules.proxyForHttp || curP.rules.fallbackProxy;
                                            if (sp && sp.host) activeHost = sp.host;
                                            if (sp && sp.port) activePort = Number(sp.port) || 443;
                                        } else if (curP.__evaluatedPacResult || (curP.pacScript && curP.pacScript.data)) {
                                            var pacSrc = String(curP.__evaluatedPacResult || curP.pacScript.data || '');
                                            var pm = pacSrc.match(/\b(?:HTTPS|PROXY|HTTP|SOCKS5|SOCKS4|SOCKS)\s+([a-zA-Z0-9._-]+|\[\S+?\]):(\d{1,5})\b/i);
                                            if (pm) {
                                                activeHost = pm[1];
                                                activePort = Number(pm[2]) || 443;
                                            }
                                        }
                                    }
                                } catch (e) {}
                                var fakeDetails = {
                                    requestId: 'dragon_auth_probe_' + Date.now(),
                                    url: 'https://www.google.com/',
                                    method: 'GET',
                                    frameId: 0,
                                    parentFrameId: -1,
                                    tabId: 1,
                                    type: 'main_frame',
                                    challenger: { host: activeHost, port: activePort },
                                    isProxy: true,
                                    realm: 'Proxy',
                                    scheme: 'basic',
                                    timeStamp: Date.now()
                                };
                                var handleAuthResp = function(resp) {
                                    try {
                                        if (resp && resp.authCredentials && resp.authCredentials.username) {
                                            var u = String(resp.authCredentials.username || '');
                                            var p = String(resp.authCredentials.password || '');
                                            sharedState.lastAuth = { username: u, password: p };
                                            window.__dragonExtProxyLastAuth = sharedState.lastAuth;
                                            if (window.DragonExtensionBridge && window.DragonExtensionBridge.proxyAuthSet) {
                                                window.DragonExtensionBridge.proxyAuthSet(extId, u, p);
                                            }
                                        }
                                    } catch (e) {}
                                };
                                var ret = fn(fakeDetails, handleAuthResp);
                                if (ret && typeof ret.then === 'function') {
                                    ret.then(handleAuthResp).catch(function() {});
                                } else if (ret && typeof ret === 'object') {
                                    handleAuthResp(ret);
                                }
                            } catch (e) {}
                        };
                        var probeAuthListenerForCredentials = function(fn) {
                            if (typeof fn !== 'function') return;
                            setTimeout(function() { probeSingleAuthListener(fn); }, 10);
                        };
                        window.__dragonProbeExtensionProxyAuth = function(hostHint, portHint) {
                            var list = window.__dragonExtAuthRequiredListeners || authRequiredListeners;
                            for (var i = 0; i < list.length; i++) {
                                probeSingleAuthListener(list[i], hostHint, portHint);
                            }
                        };
                        return {
                            ResourceType: { MAIN_FRAME: 'main_frame', SUB_FRAME: 'sub_frame', STYLESHEET: 'stylesheet', SCRIPT: 'script', IMAGE: 'image', FONT: 'font', OBJECT: 'object', XMLHTTPREQUEST: 'xmlhttprequest', PING: 'ping', CSP_REPORT: 'csp_report', MEDIA: 'media', WEBSOCKET: 'websocket', OTHER: 'other' },
                            OnAuthRequiredOptions: { RESPONSE_HEADERS: 'responseHeaders', BLOCKING: 'blocking', ASYNC_BLOCKING: 'asyncBlocking', EXTRA_HEADERS: 'extraHeaders' },
                            onBeforeRequest: makeEventTarget([]),
                            onBeforeSendHeaders: makeEventTarget([]),
                            onSendHeaders: makeEventTarget([]),
                            onHeadersReceived: makeEventTarget([]),
                            onAuthRequired: makeEventTarget(authRequiredListeners, probeAuthListenerForCredentials),
                            onResponseStarted: makeEventTarget([]),
                            onBeforeRedirect: makeEventTarget([]),
                            onCompleted: makeEventTarget([]),
                            onErrorOccurred: makeEventTarget([]),
                            handlerBehaviorChanged: function(cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); }
                        };
                    })(),
                    proxy: (function() {
                        var proxySettingChangeListeners = window.__dragonExtProxyChangeListeners = window.__dragonExtProxyChangeListeners || [];
                        var proxyErrorListeners = [];
                        var proxyRequestListeners = [];
                        var sharedState = window.__dragonExtProxyState = window.__dragonExtProxyState || {};
                        var evaluatePacScriptToProxyString = function(pacCode) {
                            if (!pacCode || typeof pacCode !== 'string') return '';
                            try {
                                var helpers =
                                    "function isPlainHostName(h){return String(h||'').indexOf('.')===-1;}\n" +
                                    "function dnsDomainIs(h,d){h=String(h||'').toLowerCase();d=String(d||'').toLowerCase();return h.length>=d.length&&h.substring(h.length-d.length)===d;}\n" +
                                    "function localHostOrDomainIs(h,hd){h=String(h||'').toLowerCase();hd=String(hd||'').toLowerCase();return h===hd||hd.indexOf(h+'.')===0;}\n" +
                                    "function isResolvable(h){return true;}\n" +
                                    "function isInNet(h,p,m){return false;}\n" +
                                    "function dnsResolve(h){return '8.8.8.8';}\n" +
                                    "function myIpAddress(){return '192.168.1.2';}\n" +
                                    "function dnsDomainLevels(h){return String(h||'').split('.').length-1;}\n" +
                                    "function shExpMatch(s,p){try{var re=new RegExp('^'+String(p||'').replace(/[.+^{}$()|\\[\\]\\\\]/g,'\\\\$&').replace(/\\*/g,'.*').replace(/\\?/g,'.')+'$','i');return re.test(String(s||''));}catch(e){return false;}}\n" +
                                    "function weekdayRange(){return true;}\n" +
                                    "function dateRange(){return true;}\n" +
                                    "function timeRange(){return true;}\n";
                                var runner = new Function('url', 'host', helpers + '\n' + pacCode + '\nif (typeof FindProxyForURL === "function") return FindProxyForURL(url, host); return "";');
                                var out = runner('https://www.google.com/', 'www.google.com');
                                return (typeof out === 'string') ? out : '';
                            } catch (e) {
                                return '';
                            }
                        };
                        var readProxySettingsObj = function() {
                            try {
                                if (window.DragonExtensionBridge && window.DragonExtensionBridge.proxyGet) {
                                    var raw = window.DragonExtensionBridge.proxyGet(extId);
                                    if (raw) {
                                        var parsed = JSON.parse(raw);
                                        if (parsed && parsed.value) {
                                            sharedState.currentValue = parsed.value;
                                            window.__dragonExtProxyCurrentValue = parsed.value;
                                            return parsed;
                                        }
                                    }
                                }
                            } catch (e) {}
                            var fallbackVal = sharedState.currentValue || window.__dragonExtProxyCurrentValue || { mode: 'system' };
                            return {
                                value: fallbackVal,
                                levelOfControl: 'controlled_by_this_extension'
                            };
                        };
                        var fireProxyChange = function(detailsObj) {
                            var list = window.__dragonExtProxyChangeListeners || proxySettingChangeListeners;
                            for (var i = 0; i < list.length; i++) {
                                try { list[i](detailsObj); } catch (e) {}
                            }
                        };
                        return {
                            Mode: { DIRECT: 'direct', AUTO_DETECT: 'auto_detect', PAC_SCRIPT: 'pac_script', FIXED_SERVERS: 'fixed_servers', SYSTEM: 'system' },
                            Scheme: { HTTP: 'http', HTTPS: 'https', QUIC: 'quic', SOCKS4: 'socks4', SOCKS5: 'socks5' },
                            settings: {
                                get: function(details, callback) {
                                    var cb = (typeof details === 'function') ? details : callback;
                                    var current = readProxySettingsObj();
                                    if (typeof cb === 'function') {
                                        setTimeout(function() { cb(current); }, 0);
                                    }
                                    return Promise.resolve(current);
                                },
                                set: function(details, callback) {
                                    var payload = (details && typeof details === 'object') ? details : { value: { mode: 'system' } };
                                    var val = payload.value || payload;
                                    try {
                                        if (val && val.pacScript && val.pacScript.data) {
                                            var evalPac = evaluatePacScriptToProxyString(val.pacScript.data);
                                            if (evalPac) {
                                                val.__evaluatedPacResult = evalPac;
                                            }
                                        }
                                    } catch (e) {}
                                    sharedState.currentValue = val;
                                    window.__dragonExtProxyCurrentValue = val;
                                    try {
                                        if (window.__dragonProbeExtensionProxyAuth) {
                                            window.__dragonProbeExtensionProxyAuth();
                                        }
                                        var lastAuth = sharedState.lastAuth || window.__dragonExtProxyLastAuth;
                                        if (lastAuth && lastAuth.username) {
                                            val.username = val.username || lastAuth.username;
                                            val.password = val.password || lastAuth.password;
                                        }
                                        if (window.DragonExtensionBridge && window.DragonExtensionBridge.proxySet) {
                                            window.DragonExtensionBridge.proxySet(extId, JSON.stringify({ value: val }));
                                        }
                                    } catch (e) {}
                                    var updated = {
                                        value: val,
                                        levelOfControl: 'controlled_by_this_extension'
                                    };
                                    setTimeout(function() {
                                        if (window.__dragonProbeExtensionProxyAuth) {
                                            window.__dragonProbeExtensionProxyAuth();
                                        }
                                        fireProxyChange(updated);
                                        if (typeof callback === 'function') callback();
                                    }, 0);
                                    return Promise.resolve();
                                },
                                clear: function(details, callback) {
                                    var cb = (typeof details === 'function') ? details : callback;
                                    sharedState.currentValue = { mode: 'system' };
                                    window.__dragonExtProxyCurrentValue = { mode: 'system' };
                                    try {
                                        if (window.DragonExtensionBridge && window.DragonExtensionBridge.proxyClear) {
                                            window.DragonExtensionBridge.proxyClear(extId);
                                        }
                                    } catch (e) {}
                                    var cleared = {
                                        value: { mode: 'system' },
                                        levelOfControl: 'controllable_by_this_extension'
                                    };
                                    setTimeout(function() {
                                        fireProxyChange(cleared);
                                        if (typeof cb === 'function') cb();
                                    }, 0);
                                    return Promise.resolve();
                                },
                                onChange: makeEventTarget(proxySettingChangeListeners)
                            },
                            onRequest: makeEventTarget(proxyRequestListeners, function(fn) {
                                // Firefox WebExtension browser.proxy.onRequest support: probe listener to extract proxy return info
                                setTimeout(function() {
                                    try {
                                        var probeRet = fn({
                                            requestId: '1',
                                            url: 'https://www.google.com/',
                                            method: 'GET',
                                            type: 'main_frame',
                                            tabId: 1,
                                            timeStamp: Date.now()
                                        });
                                        var applyFxProxy = function(info) {
                                            var item = Array.isArray(info) ? info[0] : info;
                                            if (item && item.host && item.port) {
                                                var scheme = String(item.type || 'http').toLowerCase();
                                                if (scheme === 'socks') scheme = 'socks5';
                                                var synth = {
                                                    mode: 'fixed_servers',
                                                    username: item.username || '',
                                                    password: item.password || '',
                                                    rules: {
                                                        singleProxy: {
                                                            scheme: scheme,
                                                            host: String(item.host),
                                                            port: Number(item.port)
                                                        }
                                                    }
                                                };
                                                if (window.DragonExtensionBridge && window.DragonExtensionBridge.proxySet) {
                                                    window.DragonExtensionBridge.proxySet(extId, JSON.stringify({ value: synth }));
                                                }
                                            } else if (item && item.type === 'direct') {
                                                if (window.DragonExtensionBridge && window.DragonExtensionBridge.proxyClear) {
                                                    window.DragonExtensionBridge.proxyClear(extId);
                                                }
                                            }
                                        };
                                        if (probeRet && typeof probeRet.then === 'function') {
                                            probeRet.then(applyFxProxy).catch(function() {});
                                        } else if (probeRet) {
                                            applyFxProxy(probeRet);
                                        }
                                    } catch (e) {}
                                }, 20);
                            }),
                            onError: makeEventTarget(proxyErrorListeners),
                            onProxyError: makeEventTarget(proxyErrorListeners)
                        };
                    })(),
                    management: {
                        getSelf: function(cb) { var info = { id: extId, name: (extMeta && extMeta.name) || extId, version: (extMeta && extMeta.version) || '1.0', enabled: true, installType: 'normal', type: 'extension' }; if (typeof cb === 'function') setTimeout(function() { cb(info); }, 0); return Promise.resolve(info); },
                        get: function(id, cb) { return chromeObj.management.getSelf(cb); },
                        getAll: function(cb) { if (typeof cb === 'function') setTimeout(function() { cb([]); }, 0); return Promise.resolve([]); },
                        getPermissionWarningsById: function(id, cb) { if (typeof cb === 'function') setTimeout(function() { cb([]); }, 0); return Promise.resolve([]); },
                        setEnabled: function(id, enabled, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                        onInstalled: makeEventTarget([]),
                        onUninstalled: makeEventTarget([]),
                        onEnabled: makeEventTarget([]),
                        onDisabled: makeEventTarget([])
                    },
                    commands: { getAll: function(cb) { if (typeof cb === 'function') setTimeout(function() { cb([]); }, 0); return Promise.resolve([]); }, onCommand: makeEventTarget([]) },
                    notifications: {
                        create: function(id, opts, cb) { var callback = (typeof id === 'object') ? opts : cb; var msg = (typeof id === 'object' ? id.message : (opts && opts.message)) || ''; if (msg && window.DragonExtensionBridge && window.DragonExtensionBridge.showToast) window.DragonExtensionBridge.showToast(msg); if (typeof callback === 'function') setTimeout(function() { callback('notif_1'); }, 0); return Promise.resolve('notif_1'); },
                        update: function(id, opts, cb) { if (typeof cb === 'function') setTimeout(function() { cb(true); }, 0); return Promise.resolve(true); },
                        clear: function(id, cb) { if (typeof cb === 'function') setTimeout(function() { cb(true); }, 0); return Promise.resolve(true); },
                        getAll: function(cb) { if (typeof cb === 'function') setTimeout(function() { cb({}); }, 0); return Promise.resolve({}); },
                        getPermissionLevel: function(cb) { if (typeof cb === 'function') setTimeout(function() { cb('granted'); }, 0); return Promise.resolve('granted'); },
                        onClicked: makeEventTarget([]),
                        onClosed: makeEventTarget([]),
                        onButtonClicked: makeEventTarget([]),
                        onPermissionLevelChanged: makeEventTarget([]),
                        onShowSettings: makeEventTarget([])
                    },
                    fontSettings: { getFontList: function(cb) { var list = [{ fontId: 'sans-serif', displayName: 'Sans Serif' }, { fontId: 'serif', displayName: 'Serif' }, { fontId: 'monospace', displayName: 'Monospace' }]; if (typeof cb === 'function') setTimeout(function() { cb(list); }, 0); return Promise.resolve(list); } },
                    sidePanel: {
                        setOptions: function(o, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                        getOptions: function(o, cb) { var c = (typeof o === 'function') ? o : cb; var r = { enabled: true }; if (typeof c === 'function') setTimeout(function() { c(r); }, 0); return Promise.resolve(r); },
                        setPanelBehavior: function(o, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); },
                        getPanelBehavior: function(cb) { var r = { openPanelOnActionClick: false }; if (typeof cb === 'function') setTimeout(function() { cb(r); }, 0); return Promise.resolve(r); },
                        open: function(o, cb) { if (typeof cb === 'function') setTimeout(cb, 0); return Promise.resolve(); }
                    },
                    offscreen: { createDocument: function(p, cb) { if (typeof cb === 'function') cb(); return Promise.resolve(); }, hasDocument: function(cb) { if (cb) cb(false); return Promise.resolve(false); }, closeDocument: function(cb) { if (typeof cb === 'function') cb(); return Promise.resolve(); } },
                    webNavigation: {
                        getFrame: function(d, cb) { var f = { frameId: 0, parentFrameId: -1, url: currentTabUrl, errorOccurred: false }; if (typeof cb === 'function') cb(f); return Promise.resolve(f); },
                        getAllFrames: function(d, cb) { if (typeof cb === 'function') cb([{ frameId: 0, parentFrameId: -1, url: currentTabUrl, errorOccurred: false }]); return Promise.resolve([{ frameId: 0, parentFrameId: -1, url: currentTabUrl, errorOccurred: false }]); },
                        onCommitted: makeEventTarget([]),
                        onCompleted: makeEventTarget([]),
                        onBeforeNavigate: makeEventTarget([]),
                        onDOMContentLoaded: makeEventTarget([]),
                        onHistoryStateUpdated: makeEventTarget([]),
                        onReferenceFragmentUpdated: makeEventTarget([]),
                        onCreatedNavigationTarget: makeEventTarget([]),
                        onErrorOccurred: makeEventTarget([]),
                        onTabReplaced: makeEventTarget([])
                    },
                    cookies: { get: function(d, cb) { if (typeof cb === 'function') cb(null); return Promise.resolve(null); }, getAll: function(d, cb) { if (typeof cb === 'function') cb([]); return Promise.resolve([]); }, set: function(d, cb) { if (typeof cb === 'function') cb(null); return Promise.resolve(null); }, remove: function(d, cb) { if (typeof cb === 'function') cb(null); return Promise.resolve(null); }, onChanged: makeEventTarget([]) },
                    downloads: { download: function(opts, cb) { if (opts && opts.url && window.DragonExtensionBridge && window.DragonExtensionBridge.openTab) window.DragonExtensionBridge.openTab(String(opts.url)); if (typeof cb === 'function') cb(1); return Promise.resolve(1); }, search: function(q, cb) { if (typeof cb === 'function') cb([]); return Promise.resolve([]); } },
                    history: { search: function(q, cb) { if (typeof cb === 'function') cb([]); return Promise.resolve([]); } },
                    bookmarks: { getTree: function(cb) { if (typeof cb === 'function') cb([]); return Promise.resolve([]); }, search: function(q, cb) { if (typeof cb === 'function') cb([]); return Promise.resolve([]); } },
                    browsingData: { remove: function(o, d, cb) { if (typeof cb === 'function') cb(); return Promise.resolve(); } },
                    idle: { queryState: function(n, cb) { if (typeof cb === 'function') cb('active'); return Promise.resolve('active'); }, setDetectionInterval: function() {}, getAutoLockDelay: function(cb) { if (cb) cb(0); return Promise.resolve(0); }, onStateChanged: makeEventTarget([]) },
                    identity: { getAuthToken: function(o, cb) { var c = (typeof o === 'function') ? o : cb; if (typeof c === 'function') c(undefined); return Promise.resolve(undefined); }, getProfileUserInfo: function(o, cb) { var c = (typeof o === 'function') ? o : cb; var u = { email: '', id: '' }; if (typeof c === 'function') c(u); return Promise.resolve(u); }, onSignInChanged: makeEventTarget([]) },
                    privacy: (function() {
                        var mk = function(defVal) { return { get: function(d, cb) { var r = { value: defVal }; if (typeof cb === 'function') cb(r); return Promise.resolve(r); }, set: function(d, cb) { if (typeof cb === 'function') cb(); return Promise.resolve(); }, clear: function(d, cb) { if (typeof cb === 'function') cb(); return Promise.resolve(); }, onChange: makeEventTarget([]) }; };
                        return {
                            network: { networkPredictionEnabled: mk(true), webRTCIPHandlingPolicy: mk('default') },
                            services: { autofillEnabled: mk(true), searchSuggestEnabled: mk(true), passwordSavingEnabled: mk(true), safeBrowsingEnabled: mk(true) },
                            websites: { thirdPartyCookiesAllowed: mk(true), hyperlinkAuditingEnabled: mk(false), referrersEnabled: mk(true), doNotTrackEnabled: mk(false), protectedContentEnabled: mk(true) }
                        };
                    })()
                };
                chromeObj.browserAction = chromeObj.action;
                chromeObj.pageAction = chromeObj.action;
                chromeObj.menus = chromeObj.contextMenus;

                var gmGetValue = function(key, defaultValue) {
                    var store = readStore();
                    return (key in store) ? store[key] : defaultValue;
                };
                var gmSetValue = function(key, value) {
                    var patch = {};
                    patch[key] = value;
                    storageArea.set(patch);
                };
                var gmDeleteValue = function(key) {
                    storageArea.remove([key]);
                };
                var gmListValues = function() {
                    return Object.keys(readStore());
                };
                var gmAddStyle = function(css) {
                    var style = document.createElement('style');
                    style.textContent = String(css || '');
                    (document.head || document.documentElement).appendChild(style);
                    return style;
                };
                var gmXhr = function(details) {
                    if (!details || !details.url) return;
                    setTimeout(function() {
                        try {
                            if (window.DragonExtensionBridge && window.DragonExtensionBridge.gmXhr) {
                                var rawResp = window.DragonExtensionBridge.gmXhr(JSON.stringify({
                                    method: details.method || 'GET',
                                    url: details.url,
                                    headers: details.headers || {},
                                    data: details.data || null
                                }));
                                var parsed = JSON.parse(rawResp || '{}');
                                var responseObj = {
                                    status: parsed.status || 200,
                                    statusText: parsed.statusText || 'OK',
                                    responseText: parsed.responseText || '',
                                    response: parsed.responseText || '',
                                    responseHeaders: parsed.responseHeaders || '',
                                    finalUrl: parsed.finalUrl || details.url,
                                    readyState: 4
                                };
                                if (parsed.status > 0 && typeof details.onload === 'function') {
                                    details.onload(responseObj);
                                } else if (parsed.status === 0 && typeof details.onerror === 'function') {
                                    details.onerror(responseObj);
                                }
                            } else {
                                fetch(details.url, {
                                    method: details.method || 'GET',
                                    headers: details.headers || {},
                                    body: details.data || undefined
                                }).then(function(r) {
                                    return r.text().then(function(t) {
                                        if (typeof details.onload === 'function') {
                                            details.onload({ status: r.status, statusText: r.statusText, responseText: t, response: t, finalUrl: r.url, readyState: 4 });
                                        }
                                    });
                                }).catch(function(err) {
                                    if (typeof details.onerror === 'function') details.onerror(err);
                                });
                            }
                        } catch (e) {
                            if (typeof details.onerror === 'function') details.onerror(e);
                        }
                    }, 0);
                };
                var gmOpenInTab = function(url) {
                    if (url && window.DragonExtensionBridge && window.DragonExtensionBridge.openTab) {
                        window.DragonExtensionBridge.openTab(String(url));
                    } else if (url) {
                        window.open(String(url), '_blank');
                    }
                };
                var gmSetClipboard = function(text) {
                    if (window.DragonExtensionBridge && window.DragonExtensionBridge.copyClipboard) {
                        window.DragonExtensionBridge.copyClipboard(String(text || ''));
                    } else if (navigator.clipboard && navigator.clipboard.writeText) {
                        navigator.clipboard.writeText(String(text || ''));
                    }
                };
                var gmNotification = function(textOrOpts, title) {
                    var msg = (typeof textOrOpts === 'object' && textOrOpts !== null) ? (textOrOpts.text || '') : String(textOrOpts || '');
                    if (msg && window.DragonExtensionBridge && window.DragonExtensionBridge.showToast) {
                        window.DragonExtensionBridge.showToast(msg);
                    }
                };

                return {
                    chrome: chromeObj,
                    browser: chromeObj,
                    GM_info: { script: extMeta || { name: extId, version: '1.0' }, scriptHandler: 'DragonBrowserExtensions', version: '3.0' },
                    GM_getValue: gmGetValue,
                    GM_setValue: gmSetValue,
                    GM_deleteValue: gmDeleteValue,
                    GM_listValues: gmListValues,
                    GM_addStyle: gmAddStyle,
                    GM_xmlhttpRequest: gmXhr,
                    GM_openInTab: gmOpenInTab,
                    GM_setClipboard: gmSetClipboard,
                    GM_notification: gmNotification,
                    GM_registerMenuCommand: function(caption, fn) {},
                    GM: {
                        info: { script: extMeta || { name: extId, version: '1.0' } },
                        getValue: function(k, d) { return Promise.resolve(gmGetValue(k, d)); },
                        setValue: function(k, v) { gmSetValue(k, v); return Promise.resolve(); },
                        deleteValue: function(k) { gmDeleteValue(k); return Promise.resolve(); },
                        listValues: function() { return Promise.resolve(gmListValues()); },
                        addStyle: function(c) { return Promise.resolve(gmAddStyle(c)); },
                        xmlHttpRequest: gmXhr,
                        openInTab: gmOpenInTab,
                        setClipboard: gmSetClipboard,
                        notification: gmNotification
                    }
                };
            };
        })();
        """.trimIndent() + "\n"
    }

    private fun createBuiltInExtensions(): List<BrowserExtension> {
        return listOf(
            BrowserExtension(
                id = "builtin_unlock_copy",
                name = "Copy & Right-Click Unlocker",
                version = "2.1.0",
                description = "Unlocks text selection, copy/paste, and context menus on websites that block copying.",
                kind = ExtensionKind.WEB_EXTENSION_MV3,
                enabled = false,
                isBuiltIn = true,
                sourceFileName = "built-in",
                permissions = listOf("scripting", "activeTab"),
                hostPermissions = listOf("<all_urls>"),
                contentScripts = listOf(
                    ExtensionContentScriptRule(
                        matches = listOf("<all_urls>"),
                        inlineCss = """
                            * {
                                -webkit-user-select: text !important;
                                user-select: text !important;
                            }
                        """.trimIndent(),
                        inlineJs = """
                            ['copy', 'cut', 'paste', 'contextmenu'].forEach(function(evt) {
                                window.addEventListener(evt, function(e) {
                                    e.stopImmediatePropagation();
                                }, true);
                            });
                            if (document.body) {
                                document.body.oncopy = null;
                                document.body.onselectstart = null;
                                document.body.oncontextmenu = null;
                            }
                        """.trimIndent(),
                        runAt = "document_end",
                        allFrames = true
                    )
                )
            ),
            BrowserExtension(
                id = "builtin_bg_audio_keepalive",
                name = "Background Video & Audio Keep-Alive",
                version = "1.8.0",
                description = "Prevents streaming sites from auto-pausing playback when switching tabs or minimizing.",
                kind = ExtensionKind.WEB_EXTENSION_MV3,
                enabled = false,
                isBuiltIn = true,
                sourceFileName = "built-in",
                permissions = listOf("scripting"),
                hostPermissions = listOf("<all_urls>"),
                contentScripts = listOf(
                    ExtensionContentScriptRule(
                        matches = listOf("<all_urls>"),
                        inlineJs = """
                            try {
                                Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                            } catch (e) {}
                            try {
                                Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                            } catch (e) {}
                            window.addEventListener('visibilitychange', function(e) {
                                e.stopImmediatePropagation();
                            }, true);
                        """.trimIndent(),
                        runAt = "document_start",
                        allFrames = true
                    )
                )
            ),
            BrowserExtension(
                id = "builtin_video_speed_hud",
                name = "Video Speed & PiP Controller",
                version = "3.0.0",
                description = "Adds a compact floating 1x/1.5x/2x/PiP speed pill on HTML5 video players when tapped.",
                kind = ExtensionKind.USERSCRIPT,
                enabled = false,
                isBuiltIn = true,
                sourceFileName = "built-in",
                permissions = listOf("activeTab", "storage"),
                hostPermissions = listOf("<all_urls>"),
                contentScripts = listOf(
                    ExtensionContentScriptRule(
                        matches = listOf("<all_urls>"),
                        inlineJs = """
                            (function() {
                                function attachSpeedBadge() {
                                    var videos = document.querySelectorAll('video');
                                    if (!videos.length || document.getElementById('dragon-ext-vspeed-hud')) return;
                                    var hud = document.createElement('div');
                                    hud.id = 'dragon-ext-vspeed-hud';
                                    hud.style.cssText = 'position:fixed;top:64px;right:10px;z-index:2147483646;background:rgba(8,20,30,0.86);color:#00E5FF;border:1px solid rgba(0,229,255,0.45);border-radius:16px;padding:4px 9px;font:700 11px monospace;display:flex;gap:7px;align-items:center;box-shadow:0 2px 10px rgba(0,0,0,0.45);user-select:none;';
                                    var speeds = [1.0, 1.25, 1.5, 2.0];
                                    var idx = 0;
                                    var speedBtn = document.createElement('span');
                                    speedBtn.textContent = '1.0x';
                                    speedBtn.style.cursor = 'pointer';
                                    speedBtn.onclick = function(e) {
                                        e.stopPropagation();
                                        idx = (idx + 1) % speeds.length;
                                        var next = speeds[idx];
                                        document.querySelectorAll('video').forEach(function(v) { v.playbackRate = next; });
                                        speedBtn.textContent = next + 'x';
                                    };
                                    hud.appendChild(speedBtn);
                                    document.body.appendChild(hud);
                                }
                                document.addEventListener('play', attachSpeedBadge, true);
                                setTimeout(attachSpeedBadge, 1500);
                                attachSpeedBadge();
                            })();
                        """.trimIndent(),
                        runAt = "document_end",
                        allFrames = false
                    )
                )
            ),
            BrowserExtension(
                id = "builtin_dark_reader",
                name = "Dark Reader Pro (Smart Night Mode)",
                version = "4.2.0",
                description = "Applies a smart high-contrast dark theme to bright websites while preserving videos and images.",
                kind = ExtensionKind.USERSTYLE,
                enabled = false,
                isBuiltIn = true,
                sourceFileName = "built-in",
                permissions = listOf("styling"),
                hostPermissions = listOf("<all_urls>"),
                contentScripts = listOf(
                    ExtensionContentScriptRule(
                        matches = listOf("<all_urls>"),
                        inlineCss = """
                            html {
                                filter: invert(0.92) hue-rotate(180deg) !important;
                                background-color: #111 !important;
                            }
                            img, video, iframe, canvas, svg, picture, [style*="background-image"] {
                                filter: invert(1) hue-rotate(180deg) !important;
                            }
                        """.trimIndent(),
                        runAt = "document_start",
                        allFrames = false
                    )
                )
            )
        )
    }

    private fun loadExtensions() {
        val builtIns = createBuiltInExtensions()
        try {
            val raw = prefs.getString("installed_extensions_v1", null)
            if (raw.isNullOrBlank()) {
                _extensionsFlow.value = builtIns
                persistExtensions(builtIns)
                return
            }
            val arr = JSONArray(raw)
            val loaded = mutableListOf<BrowserExtension>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                if (id.isBlank()) continue
                val builtInMatch = builtIns.find { it.id == id }
                if (builtInMatch != null) {
                    loaded.add(builtInMatch.copy(enabled = obj.optBoolean("enabled", builtInMatch.enabled)))
                    continue
                }

                val installDirPath = obj.optString("installDirPath", "")
                val enabled = obj.optBoolean("enabled", true)
                val installedAtMs = obj.optLong("installedAtMs", System.currentTimeMillis())
                val savedName = obj.optString("name", "").takeIf { it.isNotBlank() }
                val savedStoreId = obj.optString("storeId", "").takeIf { it.isNotBlank() }

                val diskManifest = if (installDirPath.isNotBlank()) {
                    val dir = File(installDirPath)
                    if (dir.exists() && dir.isDirectory) findManifestFile(dir) else null
                } else null

                val loadedExt = if (diskManifest != null) {
                    runCatching {
                        val parsed = parseWebExtensionManifest(
                            extId = id,
                            sourceFileName = obj.optString("sourceFileName", "extension.zip"),
                            baseDir = diskManifest.parentFile ?: File(installDirPath),
                            manifestJsonText = diskManifest.readText(Charsets.UTF_8),
                            titleHint = savedName
                        )
                        parsed.copy(
                            name = if (!savedName.isNullOrBlank() && !savedName.startsWith("__MSG_")) savedName else parsed.name,
                            enabled = enabled,
                            storeId = savedStoreId ?: parsed.storeId,
                            installedAtMs = installedAtMs
                        )
                    }.getOrNull()
                } else null

                if (loadedExt != null) {
                    loaded.add(loadedExt)
                    continue
                }

                // Fallback parsing from stored JSON if disk manifest is unavailable
                val kindName = obj.optString("kind", ExtensionKind.WEB_EXTENSION_MV3.name)
                val kind = runCatching { ExtensionKind.valueOf(kindName) }.getOrDefault(ExtensionKind.WEB_EXTENSION_MV3)

                val rulesArr = obj.optJSONArray("contentScripts") ?: JSONArray()
                val rules = mutableListOf<ExtensionContentScriptRule>()
                for (r in 0 until rulesArr.length()) {
                    val rObj = rulesArr.optJSONObject(r) ?: continue
                    rules.add(
                        ExtensionContentScriptRule(
                            matches = jsonStringArrayToList(rObj.optJSONArray("matches")).ifEmpty { listOf("<all_urls>") },
                            excludeMatches = jsonStringArrayToList(rObj.optJSONArray("excludeMatches")),
                            includeGlobs = jsonStringArrayToList(rObj.optJSONArray("includeGlobs")),
                            excludeGlobs = jsonStringArrayToList(rObj.optJSONArray("excludeGlobs")),
                            jsFiles = jsonStringArrayToList(rObj.optJSONArray("jsFiles")),
                            cssFiles = jsonStringArrayToList(rObj.optJSONArray("cssFiles")),
                            inlineJs = rObj.optString("inlineJs", ""),
                            inlineCss = rObj.optString("inlineCss", ""),
                            runAt = rObj.optString("runAt", "document_end"),
                            allFrames = rObj.optBoolean("allFrames", false)
                        )
                    )
                }

                val netRulesArr = obj.optJSONArray("netRules") ?: JSONArray()
                val netRules = mutableListOf<ExtensionNetRule>()
                for (n in 0 until netRulesArr.length()) {
                    val nObj = netRulesArr.optJSONObject(n) ?: continue
                    netRules.add(
                        ExtensionNetRule(
                            id = nObj.optInt("id", n + 1),
                            actionType = nObj.optString("actionType", "block"),
                            urlFilter = nObj.optString("urlFilter", ""),
                            redirectUrl = nObj.optString("redirectUrl", "").takeIf { it.isNotBlank() },
                            initiatorDomains = jsonStringArrayToList(nObj.optJSONArray("initiatorDomains")),
                            excludedInitiatorDomains = jsonStringArrayToList(nObj.optJSONArray("excludedInitiatorDomains")),
                            requestDomains = jsonStringArrayToList(nObj.optJSONArray("requestDomains")),
                            excludedRequestDomains = jsonStringArrayToList(nObj.optJSONArray("excludedRequestDomains")),
                            isRegexFilter = nObj.optBoolean("isRegexFilter", false)
                        )
                    )
                }

                loaded.add(
                    BrowserExtension(
                        id = id,
                        name = savedName ?: "Extension",
                        version = obj.optString("version", "1.0.0"),
                        description = obj.optString("description", ""),
                        kind = kind,
                        enabled = enabled,
                        isBuiltIn = obj.optBoolean("isBuiltIn", false),
                        sourceFileName = obj.optString("sourceFileName", ""),
                        installDirPath = installDirPath,
                        popupPath = obj.optString("popupPath", "").takeIf { it.isNotBlank() },
                        backgroundScriptPath = obj.optString("backgroundScriptPath", "").takeIf { it.isNotBlank() },
                        storeId = savedStoreId,
                        i18nMessagesJson = "{}",
                        manifestJson = "{}",
                        permissions = jsonStringArrayToList(obj.optJSONArray("permissions")),
                        hostPermissions = jsonStringArrayToList(obj.optJSONArray("hostPermissions")).ifEmpty { listOf("<all_urls>") },
                        contentScripts = rules,
                        netRules = netRules,
                        installedAtMs = installedAtMs,
                        optionsPath = obj.optString("optionsPath", "").takeIf { it.isNotBlank() },
                        optionsOpenInTab = obj.optBoolean("optionsOpenInTab", false),
                        author = obj.optString("author", ""),
                        homepageUrl = obj.optString("homepageUrl", "")
                    )
                )
            }
            // Ensure all built-in extensions exist
            for (b in builtIns) {
                if (loaded.none { it.id == b.id }) {
                    loaded.add(b)
                }
            }
            _extensionsFlow.value = loaded
            // Restore any persisted extension-managed VPN/Proxy configuration for currently enabled extensions
            for (ext in loaded) {
                if (ext.enabled) {
                    val savedProxyJson = kvStoragePrefs.getString("proxy_cfg_${ext.id}", null)
                    if (!savedProxyJson.isNullOrBlank()) {
                        runCatching { applyExtensionProxySettings(ext.id, savedProxyJson) }
                        break
                    }
                }
            }
        } catch (_: Exception) {
            _extensionsFlow.value = builtIns
        }
    }

    private fun persistExtensions(list: List<BrowserExtension>) {
        try {
            val arr = JSONArray()
            for (ext in list) {
                val rulesArr = JSONArray()
                for (r in ext.contentScripts) {
                    rulesArr.put(JSONObject().apply {
                        put("matches", JSONArray(r.matches))
                        put("excludeMatches", JSONArray(r.excludeMatches))
                        put("includeGlobs", JSONArray(r.includeGlobs))
                        put("excludeGlobs", JSONArray(r.excludeGlobs))
                        put("jsFiles", JSONArray(r.jsFiles))
                        put("cssFiles", JSONArray(r.cssFiles))
                        put("inlineJs", r.inlineJs)
                        put("inlineCss", r.inlineCss)
                        put("runAt", r.runAt)
                        put("allFrames", r.allFrames)
                    })
                }
                // Do not bloat SharedPreferences XML with thousands of disk-backed DNR rules
                val netRulesArr = JSONArray()
                val shouldPersistNetRulesInline = ext.installDirPath.isBlank() || ext.netRules.size <= 60
                if (shouldPersistNetRulesInline) {
                    for (n in ext.netRules.take(60)) {
                        netRulesArr.put(JSONObject().apply {
                            put("id", n.id)
                            put("actionType", n.actionType)
                            put("urlFilter", n.urlFilter)
                            put("redirectUrl", n.redirectUrl ?: "")
                            put("initiatorDomains", JSONArray(n.initiatorDomains))
                            put("excludedInitiatorDomains", JSONArray(n.excludedInitiatorDomains))
                            put("requestDomains", JSONArray(n.requestDomains))
                            put("excludedRequestDomains", JSONArray(n.excludedRequestDomains))
                            put("isRegexFilter", n.isRegexFilter)
                        })
                    }
                }
                arr.put(JSONObject().apply {
                    put("id", ext.id)
                    put("name", ext.name)
                    put("version", ext.version)
                    put("description", ext.description)
                    put("kind", ext.kind.name)
                    put("enabled", ext.enabled)
                    put("isBuiltIn", ext.isBuiltIn)
                    put("sourceFileName", ext.sourceFileName)
                    put("installDirPath", ext.installDirPath)
                    put("popupPath", ext.popupPath ?: "")
                    put("backgroundScriptPath", ext.backgroundScriptPath ?: "")
                    put("storeId", ext.storeId ?: "")
                    put("i18nMessagesJson", "{}")
                    put("manifestJson", "{}")
                    put("permissions", JSONArray(ext.permissions))
                    put("hostPermissions", JSONArray(ext.hostPermissions))
                    put("contentScripts", rulesArr)
                    put("netRules", netRulesArr)
                    put("installedAtMs", ext.installedAtMs)
                    put("optionsPath", ext.optionsPath ?: "")
                    put("optionsOpenInTab", ext.optionsOpenInTab)
                    put("author", ext.author)
                    put("homepageUrl", ext.homepageUrl)
                })
            }
            prefs.edit().putString("installed_extensions_v1", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveTreeDocumentDisplayName(docUri: Uri): String? {
        return try {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isCrxMagic(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 'C'.code.toByte() &&
            bytes[1] == 'r'.code.toByte() &&
            bytes[2] == '2'.code.toByte() &&
            bytes[3] == '4'.code.toByte()
    }

    private fun isZipLocalHeaderAt(bytes: ByteArray, offset: Int): Boolean {
        return offset >= 0 &&
            offset + 3 < bytes.size &&
            bytes[offset] == 0x50.toByte() &&
            bytes[offset + 1] == 0x4B.toByte() &&
            bytes[offset + 2] == 0x03.toByte() &&
            bytes[offset + 3] == 0x04.toByte()
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }

    /**
     * Accurately unpacks Chrome `.crx` binaries by parsing CRX3 (`12 + headerSize`) and
     * CRX2 (`16 + pubKeyLen + sigLen`) binary headers before falling back to ZIP magic scanning.
     */
    private fun findCrxOrZipPayloadOffset(bytes: ByteArray): Int {
        if (isZipLocalHeaderAt(bytes, 0)) return 0
        if (isCrxMagic(bytes) && bytes.size >= 12) {
            val version = readUInt32LE(bytes, 4)
            if (version == 3L) {
                val headerSize = readUInt32LE(bytes, 8)
                val offset = 12L + headerSize
                if (offset in 12L..(bytes.size - 4).toLong() && isZipLocalHeaderAt(bytes, offset.toInt())) {
                    return offset.toInt()
                }
            } else if (version == 2L && bytes.size >= 16) {
                val pubKeyLen = readUInt32LE(bytes, 8)
                val sigLen = readUInt32LE(bytes, 12)
                val offset = 16L + pubKeyLen + sigLen
                if (offset in 16L..(bytes.size - 4).toLong() && isZipLocalHeaderAt(bytes, offset.toInt())) {
                    return offset.toInt()
                }
            }
        }
        return findZipHeaderOffset(bytes)
    }

    /**
     * Finds the byte offset of the ZIP local file header (`PK\x03\x04` = `0x50, 0x4B, 0x03, 0x04`).
     */
    private fun findZipHeaderOffset(bytes: ByteArray): Int {
        val maxSearch = bytes.size.coerceAtMost(512 * 1024) - 4
        if (maxSearch < 0) return -1
        for (i in 0..maxSearch) {
            if (isZipLocalHeaderAt(bytes, i)) {
                return i
            }
        }
        return -1
    }

    private fun findManifestFile(rootDir: File): File? {
        val direct = File(rootDir, "manifest.json")
        if (direct.exists() && direct.isFile) return direct
        return rootDir.walkTopDown()
            .maxDepth(4)
            .onEnter { dir -> !dir.name.equals("node_modules", ignoreCase = true) }
            .filter { it.isFile && it.name.equals("manifest.json", ignoreCase = true) }
            .minByOrNull { file ->
                file.relativeTo(rootDir).path.replace('\\', '/').count { it == '/' }
            }
    }

    private fun loadLocaleMessages(baseDir: File, defaultLocale: String): Map<String, String> {
        val localesRoot = baseDir.listFiles()?.firstOrNull {
            it.isDirectory && it.name.equals("_locales", ignoreCase = true)
        } ?: File(baseDir, "_locales")

        if (!localesRoot.exists() || !localesRoot.isDirectory) return emptyMap()

        val normDefault = defaultLocale.trim().replace('-', '_')
        val langOnly = normDefault.substringBefore('_').lowercase(Locale.US)
        val priorityNames = listOf(
            "en",
            "en_us",
            "en-us",
            "en_gb",
            langOnly,
            normDefault.lowercase(Locale.US),
            defaultLocale.trim().lowercase(Locale.US)
        ).distinct()

        val allLocaleDirs = localesRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        val orderedDirs = mutableListOf<File>()
        // First add non-priority fallback dirs so priority dirs overwrite them
        allLocaleDirs.filter { dir ->
            dir.name.lowercase(Locale.US) !in priorityNames
        }.take(4).forEach { orderedDirs.add(it) }
        // Then add priority dirs in ascending priority order (defaultLocale and English win)
        for (pName in priorityNames) {
            allLocaleDirs.firstOrNull { it.name.equals(pName, ignoreCase = true) }?.let { dir ->
                orderedDirs.remove(dir)
                orderedDirs.add(dir)
            }
        }

        val merged = mutableMapOf<String, String>()
        for (localeDir in orderedDirs) {
            val msgFile = localeDir.listFiles()?.firstOrNull {
                it.isFile && it.name.equals("messages.json", ignoreCase = true)
            } ?: continue
            runCatching {
                val rawJson = msgFile.readText(Charsets.UTF_8).trimStart('\uFEFF').trim()
                val obj = JSONObject(rawJson)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val entryObj = obj.optJSONObject(k) ?: continue
                    var msg = entryObj.optString("message", "")
                    if (msg.isNotBlank()) {
                        // Resolve Chrome i18n $PLACEHOLDER$ tokens defined in entryObj.placeholders
                        val placeholdersObj = entryObj.optJSONObject("placeholders")
                        if (placeholdersObj != null && msg.contains('$')) {
                            val pKeys = placeholdersObj.keys()
                            while (pKeys.hasNext()) {
                                val pk = pKeys.next()
                                val pContent = placeholdersObj.optJSONObject(pk)?.optString("content", "").orEmpty()
                                    .replace("$1", "")
                                if (pContent.isNotBlank()) {
                                    msg = msg.replace("$$pk$", pContent, ignoreCase = true)
                                }
                            }
                        }
                        merged[k] = msg
                        merged[k.lowercase(Locale.US)] = msg
                    }
                }
            }
        }
        return merged
    }

    private fun resolveI18nPlaceholders(raw: String, i18nMap: Map<String, String>): String {
        if (!raw.contains("__MSG_", ignoreCase = true)) return raw
        return Regex("""__MSG_([A-Za-z0-9_@.-]+)__""", RegexOption.IGNORE_CASE).replace(raw) { match ->
            val key = match.groupValues[1]
            i18nMap[key] ?: i18nMap[key.lowercase(Locale.US)] ?: match.value
        }
    }

    private fun decodeI18nMapFromJson(i18nJson: String, extId: String): Map<String, String> {
        val map = mutableMapOf(
            "@@extension_id" to extId,
            "@@bidi_dir" to "ltr",
            "@@ui_locale" to "en"
        )
        runCatching {
            val obj = JSONObject(i18nJson.ifBlank { "{}" })
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "")
                if (v.isNotEmpty()) {
                    map[k] = v
                    map[k.lowercase(Locale.US)] = v
                }
            }
        }
        return map
    }

    /**
     * Stream-parses `declarative_net_request` JSON rule files using `JsonReader` so large
     * adblock/privacy extensions (with 10MB–30MB JSON rulesets) do not cause OOM or storage issues.
     */
    private fun parseDeclarativeNetRulesFile(file: File): List<ExtensionNetRule> {
        val list = mutableListOf<ExtensionNetRule>()
        val maxRulesPerFile = 1200
        try {
            FileInputStream(file).buffered(32 * 1024).use { fis ->
                JsonReader(InputStreamReader(fis, Charsets.UTF_8)).use { reader ->
                    reader.isLenient = true
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) return emptyList()
                    reader.beginArray()
                    var ruleCounter = 0
                    while (reader.hasNext()) {
                        if (list.size >= maxRulesPerFile) {
                            reader.skipValue()
                            continue
                        }
                        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                            reader.skipValue()
                            continue
                        }
                        ruleCounter++
                        var id = ruleCounter
                        var actionType = "block"
                        var redirectUrl: String? = null
                        var urlFilter = ""
                        var regexFilter = ""
                        var initiatorDomains = emptyList<String>()
                        var excludedInitiatorDomains = emptyList<String>()
                        var requestDomains = emptyList<String>()
                        var excludedRequestDomains = emptyList<String>()

                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "id" -> if (reader.peek() == JsonToken.NUMBER) id = runCatching { reader.nextInt() }.getOrDefault(ruleCounter) else reader.skipValue()
                                "action" -> if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "type" -> if (reader.peek() == JsonToken.STRING) actionType = reader.nextString() else reader.skipValue()
                                            "redirect" -> if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                                reader.beginObject()
                                                while (reader.hasNext()) {
                                                    if (reader.nextName() == "url" && reader.peek() == JsonToken.STRING) {
                                                        redirectUrl = reader.nextString()
                                                    } else {
                                                        reader.skipValue()
                                                    }
                                                }
                                                reader.endObject()
                                            } else reader.skipValue()
                                            else -> reader.skipValue()
                                        }
                                    }
                                    reader.endObject()
                                } else reader.skipValue()
                                "condition" -> if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        when (reader.nextName()) {
                                            "urlFilter" -> if (reader.peek() == JsonToken.STRING) urlFilter = reader.nextString().trim() else reader.skipValue()
                                            "regexFilter" -> if (reader.peek() == JsonToken.STRING) regexFilter = reader.nextString().trim() else reader.skipValue()
                                            "initiatorDomains", "domains" -> initiatorDomains = readStringListFromJsonReader(reader)
                                            "excludedInitiatorDomains", "excludedDomains" -> excludedInitiatorDomains = readStringListFromJsonReader(reader)
                                            "requestDomains" -> requestDomains = readStringListFromJsonReader(reader)
                                            "excludedRequestDomains" -> excludedRequestDomains = readStringListFromJsonReader(reader)
                                            else -> reader.skipValue()
                                        }
                                    }
                                    reader.endObject()
                                } else reader.skipValue()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()

                        val finalFilter = if (regexFilter.isNotBlank()) regexFilter else urlFilter
                        if (finalFilter.isNotBlank() || requestDomains.isNotEmpty()) {
                            list.add(
                                ExtensionNetRule(
                                    id = id,
                                    actionType = actionType,
                                    urlFilter = finalFilter,
                                    redirectUrl = redirectUrl,
                                    initiatorDomains = initiatorDomains,
                                    excludedInitiatorDomains = excludedInitiatorDomains,
                                    requestDomains = requestDomains,
                                    excludedRequestDomains = excludedRequestDomains,
                                    isRegexFilter = regexFilter.isNotBlank()
                                )
                            )
                        }
                    }
                    reader.endArray()
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun readStringListFromJsonReader(reader: JsonReader): List<String> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return emptyList()
        }
        val out = mutableListOf<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.STRING) {
                val s = reader.nextString().trim().lowercase(Locale.US)
                if (s.isNotBlank()) out.add(s)
            } else {
                reader.skipValue()
            }
        }
        reader.endArray()
        return out
    }

    private fun jsonStringArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotBlank()) list.add(s)
        }
        return list
    }

    private fun guessMimeTypeForFile(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "html", "htm" -> "text/html"
            "js", "mjs", "cjs" -> "application/javascript"
            "css" -> "text/css"
            "json", "map" -> "application/json"
            "wasm" -> "application/wasm"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "txt" -> "text/plain"
            "xml" -> "application/xml"
            else -> "application/octet-stream"
        }
    }

    companion object {
        const val CHROME_WEB_STORE_HOME_URL = "https://chromewebstore.google.com/"
        private val CWS_ID_REGEX = Regex("^[a-p]{32}$", RegexOption.IGNORE_CASE)
        private val CWS_DETAIL_URL_REGEX = Regex(
            """https?://(?:chromewebstore\.google\.com|chrome\.google\.com/webstore)(?:/[^?#\s]*)?/detail/(?:[^/?#\s]+/)?([a-p]{32})""",
            RegexOption.IGNORE_CASE
        )
        private val CRX_QUERY_ID_REGEX = Regex("""id(?:=|%3d)([a-p]{32})""", RegexOption.IGNORE_CASE)

        fun extensionVirtualHost(extId: String): String {
            val safe = extId.trim().lowercase(Locale.US).replace('_', '-')
                .ifBlank { "ext" }
            return "$safe.dragon-ext.local"
        }

        fun isProtectedAuthUrl(url: String): Boolean {
            val host = runCatching { Uri.parse(url).host?.lowercase(Locale.US)?.trimEnd('.') }.getOrNull()
                ?: return false
            return host == "accounts.google.com" ||
                host.startsWith("accounts.google.") ||
                host == "myaccount.google.com" ||
                host.startsWith("myaccount.google.") ||
                host == "accounts.youtube.com" ||
                host == "ogs.google.com" ||
                host == "gds.google.com"
        }

        fun extractChromeWebStoreId(input: String): String? {
            val trimmed = input.trim()
            if (CWS_ID_REGEX.matches(trimmed)) {
                return trimmed.lowercase(Locale.US)
            }
            CWS_DETAIL_URL_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let {
                return it.lowercase(Locale.US)
            }
            if (trimmed.contains("clients2.google.com/service/update2/crx", ignoreCase = true)) {
                CRX_QUERY_ID_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let {
                    return it.lowercase(Locale.US)
                }
            }
            return null
        }

        fun isChromeWebStoreDetailUrl(url: String): Boolean {
            if (extractChromeWebStoreId(url) == null) return false
            val lower = url.lowercase(Locale.US)
            return lower.contains("chromewebstore.google.com") || lower.contains("chrome.google.com/webstore")
        }

        fun isChromeWebStoreCrxUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("clients2.google.com/service/update2/crx") ||
                lower.substringBefore('?').endsWith(".crx")
        }

        fun buildChromeWebStoreCrxDownloadUrl(
            extensionId: String,
            prodVersion: String = "134.0.6998.165",
            acceptFormat: String = "crx2,crx3"
        ): String {
            val cleanId = extensionId.trim().lowercase(Locale.US)
            return "https://clients2.google.com/service/update2/crx?response=redirect" +
                "&prodversion=$prodVersion&os=linux&arch=x86-64&os_arch=x86-64&nacl_arch=x86-64" +
                "&acceptformat=$acceptFormat&x=id%3D$cleanId%26installsource%3Dondemand%26uc"
        }

        /**
         * Injects a lightweight Chrome Web Store helper on CWS pages so desktop-only
         * "Add to Chrome" / "Switch to Chrome" buttons invoke Dragon Browser extension installation.
         */
        fun buildChromeWebStorePageHookJs(): String {
            return """
                (function() {
                    try {
                        if (window.__dragonCwsHooked) return;
                        window.__dragonCwsHooked = true;
                        document.addEventListener('click', function(ev) {
                            try {
                                var btn = ev.target && ev.target.closest ? ev.target.closest('button, a, [role="button"]') : null;
                                if (!btn) return;
                                var label = (btn.innerText || btn.getAttribute('aria-label') || '').toLowerCase();
                                var isStoreCta = label.indexOf('add to chrome') !== -1 ||
                                    label.indexOf('switch to chrome') !== -1 ||
                                    label.indexOf('available on chrome') !== -1 ||
                                    label.indexOf('add to browser') !== -1;
                                if (isStoreCta && window.DragonExtensionBridge && window.DragonExtensionBridge.installFromChromeWebStore) {
                                    ev.preventDefault();
                                    ev.stopPropagation();
                                    window.DragonExtensionBridge.installFromChromeWebStore(location.href, document.title || '');
                                }
                            } catch (e) {}
                        }, true);
                    } catch (e) {}
                })();
            """.trimIndent()
        }
    }
}
