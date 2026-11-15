package com.example.data.browser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.WebResourceResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
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
    val installedAtMs: Long = System.currentTimeMillis()
)

/**
 * Full-featured Browser Extension Runtime Engine for Dragon Browser.
 *
 * Supports installing extensions directly from the Chrome Web Store (`chromewebstore.google.com`),
 * store URLs / 32-character Extension IDs, direct `.crx` download links, or local storage (`OpenDocument`):
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
    private val extensionsRootDir: File = File(context.filesDir, "browser_extensions").apply { mkdirs() }

    @Volatile
    private var crossOriginHttpClient: OkHttpClient = buildHttpClient(Dns.SYSTEM)

    private fun buildHttpClient(dns: Dns): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun setHttpDns(dns: Dns) {
        crossOriginHttpClient = buildHttpClient(dns)
    }

    private val _extensionsFlow = MutableStateFlow<List<BrowserExtension>>(emptyList())
    val extensionsFlow: StateFlow<List<BrowserExtension>> = _extensionsFlow.asStateFlow()

    init {
        loadExtensions()
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
    }

    fun removeExtension(extensionId: String): Boolean {
        val target = _extensionsFlow.value.find { it.id == extensionId } ?: return false
        if (target.installDirPath.isNotBlank()) {
            runCatching { File(target.installDirPath).deleteRecursively() }
        }
        kvStoragePrefs.edit().remove("kv_${target.id}").apply()
        val updated = _extensionsFlow.value.filterNot { it.id == extensionId }
        _extensionsFlow.value = updated
        persistExtensions(updated)
        return true
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
     * Imports an extension package or script from a local storage Uri (`content://` or `file://`).
     * Supports `.zip`, `.crx`, `.xpi`, `.user.js`, `.js`, `.css`, and `manifest.json`.
     */
    fun importExtensionFromUri(uri: Uri): Result<BrowserExtension> {
        return runCatching {
            val displayName = resolveDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "extension.zip"
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Unable to read selected extension file from storage.")
            input.use {
                importExtensionFromStream(displayName, it).getOrThrow()
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
            val tempFile = File(extensionsRootDir, "temp_ext_${UUID.randomUUID()}.tmp")
            try {
                tempFile.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    var read: Int
                    var totalRead = 0L
                    while (inputStream.read(buf).also { read = it } != -1) {
                        totalRead += read
                        if (totalRead > 64 * 1024 * 1024L) {
                            throw IllegalArgumentException("Extension file exceeds 64MB size limit.")
                        }
                        out.write(buf, 0, read)
                    }
                }
                require(tempFile.length() > 0) { "Selected extension file is empty." }

                val headerBytes = ByteArray(512.coerceAtMost(tempFile.length().toInt()))
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
                        val trimmed = text.trimStart()
                        if (trimmed.startsWith("{") && trimmed.contains("\"manifest_version\"")) {
                            installDirectManifestJsonExtension(fileName, text)
                        } else if (trimmed.startsWith("/* ==UserStyle==", ignoreCase = true)) {
                            installUserStyleExtension(fileName, text)
                        } else {
                            installUserScriptExtension(fileName, text)
                        }
                    }
                }

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
                    }
                }
                val replaceIds = toReplace.map { it.id }.toSet()
                val filtered = _extensionsFlow.value.filterNot { it.id in replaceIds }
                val updated = listOf(installed) + filtered
                _extensionsFlow.value = updated
                persistExtensions(updated)
                installed
            } finally {
                runCatching { tempFile.delete() }
            }
        }
    }

    private fun installArchiveExtensionFromFile(
        fileName: String,
        archiveFile: File,
        zipStartOffset: Int,
        titleHint: String? = null
    ): BrowserExtension {
        val extId = "ext_" + UUID.randomUUID().toString().replace("-", "").take(14)
        val extractRoot = File(extensionsRootDir, extId).apply { mkdirs() }
        val canonicalExtractRoot = runCatching { extractRoot.canonicalPath }.getOrNull() ?: extractRoot.absolutePath

        var extractedCount = 0
        var totalBytesExtracted = 0L
        archiveFile.inputStream().use { fileInput ->
            if (zipStartOffset > 0) {
                var skipped = 0L
                while (skipped < zipStartOffset) {
                    val s = fileInput.skip(zipStartOffset - skipped)
                    if (s <= 0) break
                    skipped += s
                }
            }
            ZipInputStream(fileInput).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (extractedCount > 2000 || totalBytesExtracted > 64 * 1024 * 1024L) {
                        extractRoot.deleteRecursively()
                        throw IllegalArgumentException("Extension archive exceeds maximum file count or 64MB uncompressed size limit.")
                    }
                    val safeRelPath = entry.name
                        .replace('\\', '/')
                        .split('/')
                        .filter { it.isNotBlank() && it != "." && it != ".." }
                        .joinToString("/")

                    if (safeRelPath.isNotBlank()) {
                        val outFile = File(extractRoot, safeRelPath)
                        val canonicalOut = runCatching { outFile.canonicalPath }.getOrNull() ?: outFile.absolutePath
                        if (!canonicalOut.startsWith(canonicalExtractRoot)) {
                            entry = zis.nextEntry
                            continue
                        }

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                val buf = ByteArray(8192)
                                var read: Int
                                while (zis.read(buf).also { read = it } != -1) {
                                    totalBytesExtracted += read
                                    if (totalBytesExtracted > 64 * 1024 * 1024L) {
                                        extractRoot.deleteRecursively()
                                        throw IllegalArgumentException("Extension archive exceeds 64MB uncompressed size limit.")
                                    }
                                    out.write(buf, 0, read)
                                }
                            }
                            extractedCount++
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        if (extractedCount == 0) {
            extractRoot.deleteRecursively()
            throw IllegalArgumentException("Archive contains no valid extension files.")
        }

        // Find manifest.json (at root or inside a single top-level directory)
        val manifestFile = findManifestFile(extractRoot)
        if (manifestFile != null) {
            val baseDir = manifestFile.parentFile ?: extractRoot
            return parseWebExtensionManifest(
                extId = extId,
                sourceFileName = fileName,
                baseDir = baseDir,
                manifestJsonText = manifestFile.readText(Charsets.UTF_8),
                titleHint = titleHint
            )
        }

        // Fallback: if archive contains .user.js / .js / .css files without manifest.json, bundle them automatically!
        val allFiles = extractRoot.walkTopDown().filter { it.isFile }.toList()
        val jsFiles = allFiles.filter { it.name.endsWith(".js", ignoreCase = true) }
            .map { it.relativeTo(extractRoot).path.replace('\\', '/') }
        val cssFiles = allFiles.filter { it.name.endsWith(".css", ignoreCase = true) }
            .map { it.relativeTo(extractRoot).path.replace('\\', '/') }

        if (jsFiles.isEmpty() && cssFiles.isEmpty()) {
            extractRoot.deleteRecursively()
            throw IllegalArgumentException("No manifest.json, .js, or .css found in extension archive.")
        }

        val cleanTitle = titleHint?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
                .ifBlank { "Imported Archive Extension" }

        return BrowserExtension(
            id = extId,
            name = cleanTitle,
            version = "1.0.0",
            description = "Imported archive with ${jsFiles.size} script(s) and ${cssFiles.size} style(s)",
            kind = ExtensionKind.WEB_EXTENSION_MV3,
            enabled = true,
            isBuiltIn = false,
            sourceFileName = fileName,
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

    private fun parseWebExtensionManifest(
        extId: String,
        sourceFileName: String,
        baseDir: File,
        manifestJsonText: String,
        titleHint: String? = null
    ): BrowserExtension {
        val manifest = JSONObject(manifestJsonText)
        val defaultLocale = manifest.optString("default_locale", "en").ifBlank { "en" }
        val i18nMap = loadLocaleMessages(baseDir, defaultLocale).toMutableMap()
        i18nMap["@@extension_id"] = extId
        i18nMap["@@ui_locale"] = defaultLocale
        i18nMap["@@bidi_dir"] = "ltr"

        val rawName = manifest.optString("name", "").ifBlank {
            titleHint?.takeIf { it.isNotBlank() } ?: sourceFileName.substringBeforeLast('.')
        }
        val resolvedName = resolveI18nPlaceholders(rawName, i18nMap)
            .let { if (it.startsWith("__MSG_") && !titleHint.isNullOrBlank()) titleHint else it }
            .ifBlank { titleHint ?: sourceFileName.substringBeforeLast('.') }

        val version = manifest.optString("version", "1.0.0").ifBlank { "1.0.0" }
        val rawDesc = manifest.optString("description", "")
        val resolvedDesc = resolveI18nPlaceholders(rawDesc, i18nMap)
            .ifBlank { "WebExtension package ($sourceFileName)" }

        val manifestVersion = manifest.optInt("manifest_version", 3)
        val kind = if (manifestVersion == 2) ExtensionKind.WEB_EXTENSION_MV2 else ExtensionKind.WEB_EXTENSION_MV3

        val permissions = mutableListOf<String>()
        manifest.optJSONArray("permissions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optString(i, "").trim()
                if (p.isNotBlank()) permissions.add(p)
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

        // Parse popup (MV3 action / MV2 browser_action / page_action), falling back to options_ui / options_page
        fun resolveExistingPagePath(rawPath: String?): String? {
            val cleaned = rawPath?.trim()
                ?.removePrefix("./")
                ?.removePrefix("/")
                ?.takeIf { it.isNotBlank() } ?: return null
            val filePart = cleaned.substringBefore('?').substringBefore('#')
            return if (filePart.isNotBlank() && File(baseDir, filePart).exists()) cleaned else null
        }

        val actionPopup = resolveExistingPagePath(
            manifest.optJSONObject("action")?.optString("default_popup")
                ?: manifest.optJSONObject("browser_action")?.optString("default_popup")
                ?: manifest.optJSONObject("page_action")?.optString("default_popup")
        )
        val optionsFallback = resolveExistingPagePath(
            manifest.optJSONObject("options_ui")?.optString("page")
                ?: manifest.optString("options_page")
        )
        val genericHtmlFallback = if (actionPopup == null && optionsFallback == null) {
            baseDir.walkTopDown().maxDepth(3).firstOrNull {
                it.isFile && (it.name.endsWith(".html", true) || it.name.endsWith(".htm", true)) &&
                !it.name.equals("background.html", true) && !it.name.equals("bg.html", true) &&
                !it.name.equals("worker.html", true)
            }?.relativeTo(baseDir)?.path?.replace('\\', '/')
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
            netRules = netRules
        )
    }

    private fun installDirectManifestJsonExtension(fileName: String, manifestJsonText: String): BrowserExtension {
        val extId = "ext_" + UUID.randomUUID().toString().replace("-", "").take(14)
        val extDir = File(extensionsRootDir, extId).apply { mkdirs() }
        File(extDir, "manifest.json").writeText(manifestJsonText, Charsets.UTF_8)
        return parseWebExtensionManifest(extId, fileName, extDir, manifestJsonText)
    }

    private fun installUserScriptExtension(fileName: String, scriptContent: String): BrowserExtension {
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
        if (!canonicalTarget.startsWith(canonicalBase) || !target.exists() || !target.isFile) return null
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
        activeTabTitle: String = "Current Tab"
    ): WebResourceResponse? {
        val lower = requestUrl.lowercase(Locale.US)
        val parsed = runCatching { Uri.parse(requestUrl) }.getOrNull()
        val parsedHostLower = parsed?.host?.lowercase(Locale.US).orEmpty()

        val isVirtualHost = parsedHostLower == "dragon-ext.local" || parsedHostLower.endsWith(".dragon-ext.local")
        val isExtScheme = lower.startsWith("chrome-extension://") || lower.startsWith("moz-extension://")

        // 1. Serve extension bundled assets
        if (parsed != null && (isExtScheme || isVirtualHost)) {
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

            if (ext == null || ext.installDirPath.isBlank()) return null
            val decodedRel = runCatching { Uri.decode(rawRelPath) }.getOrDefault(rawRelPath)
                .substringBefore('?')
                .substringBefore('#')
                .trim()
                .removePrefix("./")
                .removePrefix("/")
            val relPath = decodedRel.ifBlank {
                ext.popupPath?.substringBefore('?')?.substringBefore('#').orEmpty()
            }
            if (relPath.isBlank()) return null

            val headers = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                "Cache-Control" to "no-cache"
            )

            if (relPath.equals("__dragon_bg.html", ignoreCase = true)) {
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
            if (!targetFile.exists() && ext.popupPath != null && ext.popupPath.contains('/')) {
                val popupDir = ext.popupPath.substringBeforeLast('/')
                val nestedCandidate = File(baseDir, "$popupDir/$relPath")
                if (nestedCandidate.exists() && nestedCandidate.isFile) {
                    targetFile = nestedCandidate
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
            (function() {
                try {
                    if (window.parent && window.parent !== window) {
                        window.__dragonExtMessageListeners = window.parent.__dragonExtMessageListeners = window.parent.__dragonExtMessageListeners || [];
                        window.__dragonExtConnectListeners = window.parent.__dragonExtConnectListeners = window.parent.__dragonExtConnectListeners || [];
                        window.__dragonExtStorageChangedListeners = window.parent.__dragonExtStorageChangedListeners = window.parent.__dragonExtStorageChangedListeners || [];
                        window.__dragonExtStorageCache = window.parent.__dragonExtStorageCache = window.parent.__dragonExtStorageCache || {};
                        if (window.parent.DragonExtensionBridge) {
                            window.DragonExtensionBridge = window.parent.DragonExtensionBridge;
                        }
                    }
                } catch (e) {}
            })();
            ${buildGlobalExtensionApiPolyfillJs()}
            (function() {
                try {
                    var scope = window.__dragonCreateExtensionScope(${JSONObject.quote(ext.id)}, $extMetaJson, $initialKvJson, $i18nJson, $activeTabContextJson);
                    window.chrome = scope.chrome;
                    window.browser = scope.browser;
                    window.clients = window.clients || {
                        matchAll: function() { return Promise.resolve([{ id: 'popup_client', type: 'window', url: 'https://$extHost/' }]); },
                        openWindow: function(u) { if (u && scope.chrome && scope.chrome.tabs) scope.chrome.tabs.create({ url: u }); return Promise.resolve(null); },
                        claim: function() { return Promise.resolve(); }
                    };
                    window.skipWaiting = window.skipWaiting || function() { return Promise.resolve(); };
                    window.registration = window.registration || { scope: 'https://$extHost/', active: { state: 'activated' } };
                    window.importScripts = window.importScripts || function() {
                        for (var i = 0; i < arguments.length; i++) {
                            try {
                                var src = String(arguments[i] || '').replace(/^\/+/, '');
                                if (!src) continue;
                                var xhr = new XMLHttpRequest();
                                xhr.open('GET', 'https://$extHost/' + src, false);
                                xhr.send(null);
                                if (xhr.status >= 200 && xhr.status < 300 && xhr.responseText) {
                                    (0, eval)(xhr.responseText);
                                }
                            } catch (e) {}
                        }
                    };
                    var origFetch = window.fetch;
                    if (typeof origFetch === 'function') {
                        window.fetch = function(input, init) {
                            try {
                                var raw = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                                if (/^(chrome|moz)-extension:\/\//i.test(raw)) {
                                    var path = raw.replace(/^(chrome|moz)-extension:\/\/[^\/]+\/?/i, '');
                                    var mapped = 'https://$extHost/' + path;
                                    input = (typeof input === 'string') ? mapped : new Request(mapped, input);
                                }
                            } catch (e) {}
                            return origFetch.call(this, input, init);
                        };
                    }
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
                    var mapExtUrl = function(rawUrl) {
                        var s = String(rawUrl || '');
                        if (/^(chrome|moz)-extension:\/\//i.test(s)) {
                            var path = s.replace(/^(chrome|moz)-extension:\/\/[^\/]+\/?/i, '');
                            return 'https://$extHost/' + path;
                        }
                        return s;
                    };
                    var origFetch = window.fetch;
                    if (typeof origFetch === 'function') {
                        window.fetch = function(input, init) {
                            try {
                                var raw = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                                if (/^(chrome|moz)-extension:\/\//i.test(raw)) {
                                    var mapped = mapExtUrl(raw);
                                    input = (typeof input === 'string') ? mapped : new Request(mapped, input);
                                }
                            } catch (e) {}
                            return origFetch.call(this, input, init);
                        };
                    }
                    if (window.XMLHttpRequest && window.XMLHttpRequest.prototype && window.XMLHttpRequest.prototype.open) {
                        var origXhrOpen = window.XMLHttpRequest.prototype.open;
                        window.XMLHttpRequest.prototype.open = function(method, url) {
                            var rest = Array.prototype.slice.call(arguments, 2);
                            var nextUrl = (typeof url === 'string') ? mapExtUrl(url) : url;
                            return origXhrOpen.apply(this, [method, nextUrl].concat(rest));
                        };
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
                        var msg = '';
                        if (ev && ev.message) {
                            msg = ev.message;
                        } else if (ev && ev.target && (ev.target.src || ev.target.href)) {
                            msg = 'Resource failed: ' + (ev.target.src || ev.target.href);
                        }
                        if (msg && window.DragonExtensionBridge && window.DragonExtensionBridge.onPopupError) {
                            window.DragonExtensionBridge.onPopupError(msg);
                        }
                    } catch (e) {}
                }, true);
                window.addEventListener('unhandledrejection', function(ev) {
                    try {
                        if (window.DragonExtensionBridge && window.DragonExtensionBridge.onPopupError) {
                            var reason = ev && ev.reason ? (ev.reason.message || String(ev.reason)) : 'Promise rejection';
                            window.DragonExtensionBridge.onPopupError(reason);
                        }
                    } catch (e) {}
                });
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

    /**
     * Reads the extension's `popup.html` (or `options.html` fallback) and injects the extension API bridge
     * + active browser tab context so its UI and `chrome.*` calls render cleanly without CORS/path breakage.
     */
    fun loadExtensionPopupHtml(
        ext: BrowserExtension,
        activeTabUrl: String = "https://www.google.com",
        activeTabTitle: String = "Current Tab"
    ): Pair<String, String>? {
        val rawPopupRel = ext.popupPath ?: return null
        if (ext.installDirPath.isBlank()) return null
        val cleanPopupRel = rawPopupRel
            .removePrefix("./")
            .removePrefix("/")
        val cleanFileRel = cleanPopupRel.substringBefore('?').substringBefore('#')
        val popupFile = File(ext.installDirPath, cleanFileRel)
        if (!popupFile.exists() || !popupFile.isFile) return null

        val rawHtml = runCatching { popupFile.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val enhancedHtml = buildExtensionPageBootstrapHtml(
            ext = ext,
            rawHtml = rawHtml,
            activeTabUrl = activeTabUrl,
            activeTabTitle = activeTabTitle
        )
        val baseUrl = "https://${extensionVirtualHost(ext.id)}/$cleanPopupRel"
        return baseUrl to enhancedHtml
    }

    // --- Native Bridge Storage & Cross-Origin XHR helpers for `@JavascriptInterface` ---

    fun getExtensionStorageJson(extId: String): String {
        return kvStoragePrefs.getString("kv_$extId", "{}") ?: "{}"
    }

    fun mergeExtensionStorageJson(extId: String, patchJson: String): String {
        synchronized(kvStoragePrefs) {
            val currentObj = runCatching { JSONObject(getExtensionStorageJson(extId)) }.getOrElse { JSONObject() }
            val patchObj = runCatching { JSONObject(patchJson) }.getOrElse { JSONObject() }
            val keys = patchObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                currentObj.put(k, patchObj.get(k))
            }
            val saved = currentObj.toString()
            kvStoragePrefs.edit().putString("kv_$extId", saved).apply()
            return saved
        }
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

            crossOriginHttpClient.newCall(builder.build()).execute().use { resp ->
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
            window.__dragonExtConnectListeners = window.__dragonExtConnectListeners || [];
            window.__dragonExtStorageChangedListeners = window.__dragonExtStorageChangedListeners || [];
            window.__dragonExtStorageCache = window.__dragonExtStorageCache || {};

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
                                window.__dragonExtStorageCache[extId] = JSON.parse(raw);
                            }
                        }
                    } catch (e) {}
                    return window.__dragonExtStorageCache[extId] || {};
                };

                var makeEventTarget = function(arr) {
                    return {
                        addListener: function(fn) {
                            if (typeof fn === 'function' && arr.indexOf(fn) === -1) arr.push(fn);
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
                    getBytesInUse: function(keys, callback) {
                        var cb = (typeof keys === 'function') ? keys : callback;
                        var bytes = JSON.stringify(readStore()).length;
                        if (typeof cb === 'function') setTimeout(function() { cb(bytes); }, 0);
                        return Promise.resolve(bytes);
                    },
                    onChanged: makeEventTarget(window.__dragonExtStorageChangedListeners)
                };

                var onMessageEvent = makeEventTarget(window.__dragonExtMessageListeners);
                var onConnectEvent = makeEventTarget(window.__dragonExtConnectListeners);

                var createPortPair = function(portName) {
                    var clientMsgListeners = [];
                    var clientDiscListeners = [];
                    var hostMsgListeners = [];
                    var hostDiscListeners = [];
                    var clientPort = {
                        name: portName || 'dragon-port',
                        sender: { id: extId, url: currentTabUrl },
                        onMessage: makeEventTarget(clientMsgListeners),
                        onDisconnect: makeEventTarget(clientDiscListeners),
                        postMessage: function(msg) {
                            setTimeout(function() {
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
                        sender: { id: extId, url: currentTabUrl },
                        onMessage: makeEventTarget(hostMsgListeners),
                        onDisconnect: makeEventTarget(hostDiscListeners),
                        postMessage: function(msg) {
                            setTimeout(function() {
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
                    return new Promise(function(resolve) {
                        var attempts = 0;
                        var attemptDispatch = function() {
                            if (window.__dragonExtHasBackground && !window.__dragonExtBgReady &&
                                window.__dragonExtMessageListeners.length === 0 && attempts < 14) {
                                attempts++;
                                setTimeout(attemptDispatch, 25);
                                return;
                            }
                            var responded = false;
                            var finish = function(reply) {
                                if (!responded) {
                                    responded = true;
                                    if (cb) cb(reply);
                                    resolve(reply);
                                }
                            };
                            for (var i = 0; i < window.__dragonExtMessageListeners.length; i++) {
                                try {
                                    window.__dragonExtMessageListeners[i](
                                        message,
                                        { id: extId, url: currentTabUrl, tab: { id: 1, url: currentTabUrl, title: currentTabTitle } },
                                        finish
                                    );
                                } catch (e) {}
                            }
                            setTimeout(function() { finish({}); }, 180);
                        };
                        setTimeout(attemptDispatch, 0);
                    });
                };

                var chromeObj = {
                    runtime: {
                        id: extId,
                        lastError: null,
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
                            var pair = createPortPair(connectInfo && connectInfo.name);
                            var connAttempts = 0;
                            var fireConnect = function() {
                                if (window.__dragonExtHasBackground && !window.__dragonExtBgReady &&
                                    window.__dragonExtConnectListeners.length === 0 && connAttempts < 14) {
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
                        onMessage: onMessageEvent,
                        onMessageExternal: makeEventTarget([]),
                        onConnect: onConnectEvent,
                        onConnectExternal: makeEventTarget([]),
                        onInstalled: makeEventTarget([]),
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
                        query: function(queryInfo, callback) {
                            var tabs = [{ id: 1, windowId: 1, index: 0, active: true, highlighted: true, currentWindow: true, status: 'complete', url: currentTabUrl, title: currentTabTitle }];
                            if (typeof callback === 'function') setTimeout(function() { callback(tabs); }, 0);
                            return Promise.resolve(tabs);
                        },
                        get: function(tabId, callback) {
                            var t = { id: tabId || 1, windowId: 1, index: 0, active: true, highlighted: true, currentWindow: true, status: 'complete', url: currentTabUrl, title: currentTabTitle };
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
                        create: function(createProperties, callback) {
                            var targetUrl = createProperties && createProperties.url ? String(createProperties.url) : '';
                            if (targetUrl && window.DragonExtensionBridge && window.DragonExtensionBridge.openTab) {
                                window.DragonExtensionBridge.openTab(targetUrl);
                            }
                            var created = { id: 2, windowId: 1, url: targetUrl, active: true };
                            if (typeof callback === 'function') setTimeout(function() { callback(created); }, 0);
                            return Promise.resolve(created);
                        },
                        update: function(tabIdOrProps, maybeProps, callback) {
                            var props = (typeof tabIdOrProps === 'object') ? tabIdOrProps : (maybeProps || {});
                            var cb = (typeof maybeProps === 'function') ? maybeProps : callback;
                            if (props && props.url && window.DragonExtensionBridge && window.DragonExtensionBridge.updateActiveTabUrl) {
                                window.DragonExtensionBridge.updateActiveTabUrl(String(props.url));
                            }
                            var updated = { id: 1, windowId: 1, url: (props && props.url) || currentTabUrl, title: currentTabTitle };
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
                        getZoom: function(tabId, cb) { var c = (typeof tabId === 'function') ? tabId : cb; if (typeof c === 'function') setTimeout(function() { c(1.0); }, 0); return Promise.resolve(1.0); },
                        setZoom: function(tabId, factor, cb) { var c = (typeof factor === 'function') ? factor : cb; if (typeof c === 'function') setTimeout(c, 0); return Promise.resolve(); },
                        detectLanguage: function(tabId, cb) { var c = (typeof tabId === 'function') ? tabId : cb; if (typeof c === 'function') setTimeout(function() { c('en'); }, 0); return Promise.resolve('en'); },
                        captureVisibleTab: function(winId, opts, cb) { var c = (typeof opts === 'function') ? opts : cb; if (typeof c === 'function') setTimeout(function() { c(''); }, 0); return Promise.resolve(''); },
                        sendMessage: function(tabId, message, options, callback) {
                            var cb = (typeof options === 'function') ? options : callback;
                            if (window.DragonExtensionBridge && window.DragonExtensionBridge.evalInActiveTab && activeTabContext) {
                                var evalCode = '(function(){try{if(window.__dragonExtMessageListeners){for(var i=0;i<window.__dragonExtMessageListeners.length;i++){window.__dragonExtMessageListeners[i](' + JSON.stringify(message) + ',{id:' + JSON.stringify(extId) + ',tab:{id:1}},function(){});}}}catch(e){}})();';
                                window.DragonExtensionBridge.evalInActiveTab(evalCode);
                            }
                            return dispatchSendMessage(message, cb);
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
                        onUpdated: makeEventTarget([]),
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
                    windows: {
                        WINDOW_ID_CURRENT: 1,
                        WINDOW_ID_NONE: -1,
                        getCurrent: function(getInfo, cb) {
                            var callback = (typeof getInfo === 'function') ? getInfo : cb;
                            var w = { id: 1, focused: true, type: 'normal', incognito: false, state: 'normal', tabs: [{ id: 1, windowId: 1, active: true, url: currentTabUrl, title: currentTabTitle }] };
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
                            var list = [{ id: 1, focused: true, type: 'normal', incognito: false, state: 'normal' }];
                            if (typeof callback === 'function') setTimeout(function() { callback(list); }, 0);
                            return Promise.resolve(list);
                        },
                        create: function(createData, cb) {
                            if (createData && createData.url && window.DragonExtensionBridge && window.DragonExtensionBridge.openTab) {
                                window.DragonExtensionBridge.openTab(String(createData.url));
                            }
                            var w = { id: 2, focused: true, type: 'normal' };
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
                        onRemoved: makeEventTarget([])
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
                        onClicked: makeEventTarget([])
                    },
                    permissions: {
                        contains: function(perms, cb) { if (typeof cb === 'function') setTimeout(function() { cb(true); }, 0); return Promise.resolve(true); },
                        request: function(perms, cb) { if (typeof cb === 'function') setTimeout(function() { cb(true); }, 0); return Promise.resolve(true); },
                        remove: function(perms, cb) { if (typeof cb === 'function') setTimeout(function() { cb(true); }, 0); return Promise.resolve(true); },
                        getAll: function(cb) {
                            var out = { permissions: (extMeta && extMeta.permissions) || [], origins: ['<all_urls>'] };
                            if (typeof cb === 'function') setTimeout(function() { cb(out); }, 0);
                            return Promise.resolve(out);
                        },
                        onAdded: makeEventTarget([]),
                        onRemoved: makeEventTarget([])
                    },
                    contextMenus: { create: function() {}, update: function() {}, remove: function() {}, removeAll: function(cb) { if (typeof cb === 'function') cb(); }, onClicked: makeEventTarget([]) },
                    alarms: { create: function() {}, clear: function(name, cb) { if (typeof cb === 'function') cb(true); return Promise.resolve(true); }, clearAll: function(cb) { if (typeof cb === 'function') cb(true); return Promise.resolve(true); }, get: function(name, cb) { if (typeof cb === 'function') cb(null); return Promise.resolve(null); }, getAll: function(cb) { if (cb) cb([]); return Promise.resolve([]); }, onAlarm: makeEventTarget([]) },
                    declarativeNetRequest: {
                        MAX_NUMBER_OF_STATIC_RULESETS: 50,
                        MAX_NUMBER_OF_DYNAMIC_AND_SESSION_RULES: 5000,
                        MAX_NUMBER_OF_REGEX_RULES: 1000,
                        GUARANTEED_MINIMUM_STATIC_RULES: 30000,
                        GETMATCHEDRULES_QUOTA_INTERVAL: 10,
                        ResourceType: { MAIN_FRAME: 'main_frame', SUB_FRAME: 'sub_frame', STYLESHEET: 'stylesheet', SCRIPT: 'script', IMAGE: 'image', FONT: 'font', OBJECT: 'object', XMLHTTPREQUEST: 'xmlhttprequest', PING: 'ping', CSP_REPORT: 'csp_report', MEDIA: 'media', WEBSOCKET: 'websocket', OTHER: 'other' },
                        RuleActionType: { BLOCK: 'block', REDIRECT: 'redirect', ALLOW: 'allow', UPGRADE_SCHEME: 'upgradeScheme', MODIFY_HEADERS: 'modifyHeaders', ALLOW_ALL_REQUESTS: 'allowAllRequests' },
                        updateDynamicRules: function(opts, cb) { if (typeof cb === 'function') cb(); return Promise.resolve(); },
                        getDynamicRules: function(cb) { if (cb) cb([]); return Promise.resolve([]); },
                        updateSessionRules: function(opts, cb) { if (typeof cb === 'function') cb(); return Promise.resolve(); },
                        getSessionRules: function(cb) { if (cb) cb([]); return Promise.resolve([]); },
                        updateEnabledRulesets: function(opts, cb) { if (typeof cb === 'function') cb(); return Promise.resolve(); },
                        getEnabledRulesets: function(cb) { if (cb) cb([]); return Promise.resolve([]); },
                        getAvailableStaticRuleCount: function(cb) { if (cb) cb(30000); return Promise.resolve(30000); },
                        isRegexSupported: function(opts, cb) { var r = { isSupported: true }; if (cb) cb(r); return Promise.resolve(r); },
                        getMatchedRules: function(filter, cb) { var c = (typeof filter === 'function') ? filter : cb; var r = { rulesMatchedInfo: [] }; if (c) c(r); return Promise.resolve(r); },
                        setExtensionActionOptions: function(opts, cb) { if (cb) cb(); return Promise.resolve(); },
                        onRuleMatchedDebug: makeEventTarget([])
                    },
                    webRequest: {
                        ResourceType: { MAIN_FRAME: 'main_frame', SUB_FRAME: 'sub_frame', STYLESHEET: 'stylesheet', SCRIPT: 'script', IMAGE: 'image', FONT: 'font', OBJECT: 'object', XMLHTTPREQUEST: 'xmlhttprequest', PING: 'ping', CSP_REPORT: 'csp_report', MEDIA: 'media', WEBSOCKET: 'websocket', OTHER: 'other' },
                        onBeforeRequest: makeEventTarget([]),
                        onBeforeSendHeaders: makeEventTarget([]),
                        onSendHeaders: makeEventTarget([]),
                        onHeadersReceived: makeEventTarget([]),
                        onResponseStarted: makeEventTarget([]),
                        onBeforeRedirect: makeEventTarget([]),
                        onCompleted: makeEventTarget([]),
                        onErrorOccurred: makeEventTarget([]),
                        handlerBehaviorChanged: function(cb) { if (typeof cb === 'function') cb(); return Promise.resolve(); }
                    },
                    management: {
                        getSelf: function(cb) { var info = { id: extId, name: (extMeta && extMeta.name) || extId, version: (extMeta && extMeta.version) || '1.0', enabled: true, installType: 'normal', type: 'extension' }; if (typeof cb === 'function') setTimeout(function() { cb(info); }, 0); return Promise.resolve(info); },
                        getAll: function(cb) { if (typeof cb === 'function') setTimeout(function() { cb([]); }, 0); return Promise.resolve([]); },
                        onInstalled: makeEventTarget([]),
                        onUninstalled: makeEventTarget([]),
                        onEnabled: makeEventTarget([]),
                        onDisabled: makeEventTarget([])
                    },
                    commands: { getAll: function(cb) { if (typeof cb === 'function') setTimeout(function() { cb([]); }, 0); return Promise.resolve([]); }, onCommand: makeEventTarget([]) },
                    notifications: { create: function(id, opts, cb) { var callback = (typeof id === 'object') ? opts : cb; var msg = (typeof id === 'object' ? id.message : (opts && opts.message)) || ''; if (msg && window.DragonExtensionBridge && window.DragonExtensionBridge.showToast) window.DragonExtensionBridge.showToast(msg); if (typeof callback === 'function') setTimeout(function() { callback('notif_1'); }, 0); return Promise.resolve('notif_1'); }, clear: function(id, cb) { if (typeof cb === 'function') setTimeout(function() { cb(true); }, 0); return Promise.resolve(true); }, onClicked: makeEventTarget([]), onClosed: makeEventTarget([]) },
                    fontSettings: { getFontList: function(cb) { var list = [{ fontId: 'sans-serif', displayName: 'Sans Serif' }, { fontId: 'serif', displayName: 'Serif' }, { fontId: 'monospace', displayName: 'Monospace' }]; if (typeof cb === 'function') setTimeout(function() { cb(list); }, 0); return Promise.resolve(list); } },
                    sidePanel: { setOptions: function(o, cb) { if (typeof cb === 'function') cb(); return Promise.resolve(); }, setPanelBehavior: function(o, cb) { if (typeof cb === 'function') cb(); return Promise.resolve(); } },
                    offscreen: { createDocument: function(p, cb) { if (typeof cb === 'function') cb(); return Promise.resolve(); }, hasDocument: function(cb) { if (cb) cb(false); return Promise.resolve(false); }, closeDocument: function(cb) { if (typeof cb === 'function') cb(); return Promise.resolve(); } },
                    webNavigation: { getAllFrames: function(d, cb) { if (typeof cb === 'function') cb([{ frameId: 0, url: currentTabUrl }]); return Promise.resolve([{ frameId: 0, url: currentTabUrl }]); }, onCommitted: makeEventTarget([]), onCompleted: makeEventTarget([]), onBeforeNavigate: makeEventTarget([]), onDOMContentLoaded: makeEventTarget([]) },
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

                val diskManifest = if (installDirPath.isNotBlank()) {
                    val dir = File(installDirPath)
                    if (dir.exists() && dir.isDirectory) findManifestFile(dir) else null
                } else null

                val loadedExt = if (diskManifest != null) {
                    runCatching {
                        parseWebExtensionManifest(
                            extId = id,
                            sourceFileName = obj.optString("sourceFileName", "extension.zip"),
                            baseDir = diskManifest.parentFile ?: File(installDirPath),
                            manifestJsonText = diskManifest.readText(Charsets.UTF_8)
                        ).copy(enabled = enabled, installedAtMs = installedAtMs)
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
                        name = obj.optString("name", "Extension"),
                        version = obj.optString("version", "1.0.0"),
                        description = obj.optString("description", ""),
                        kind = kind,
                        enabled = enabled,
                        isBuiltIn = obj.optBoolean("isBuiltIn", false),
                        sourceFileName = obj.optString("sourceFileName", ""),
                        installDirPath = installDirPath,
                        popupPath = obj.optString("popupPath", "").takeIf { it.isNotBlank() },
                        backgroundScriptPath = obj.optString("backgroundScriptPath", "").takeIf { it.isNotBlank() },
                        storeId = obj.optString("storeId", "").takeIf { it.isNotBlank() },
                        i18nMessagesJson = "{}",
                        manifestJson = "{}",
                        permissions = jsonStringArrayToList(obj.optJSONArray("permissions")),
                        hostPermissions = jsonStringArrayToList(obj.optJSONArray("hostPermissions")).ifEmpty { listOf("<all_urls>") },
                        contentScripts = rules,
                        netRules = netRules,
                        installedAtMs = installedAtMs
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
                val netRulesArr = JSONArray()
                for (n in ext.netRules) {
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
        return rootDir.walkTopDown().maxDepth(3).firstOrNull {
            it.isFile && it.name.equals("manifest.json", ignoreCase = true)
        }
    }

    private fun loadLocaleMessages(baseDir: File, defaultLocale: String): Map<String, String> {
        val candidateFiles = listOf(
            File(baseDir, "_locales/en/messages.json"),
            File(baseDir, "_locales/en_US/messages.json"),
            File(baseDir, "_locales/$defaultLocale/messages.json")
        ).distinctBy { it.absolutePath }

        val merged = mutableMapOf<String, String>()
        for (msgFile in candidateFiles) {
            if (!msgFile.exists() || !msgFile.isFile) continue
            runCatching {
                val obj = JSONObject(msgFile.readText(Charsets.UTF_8))
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val msg = obj.optJSONObject(k)?.optString("message", "").orEmpty()
                    if (msg.isNotBlank()) {
                        merged[k] = msg
                        merged[k.lowercase(Locale.US)] = msg
                    }
                }
            }
        }
        return merged
    }

    private fun resolveI18nPlaceholders(raw: String, i18nMap: Map<String, String>): String {
        if (!raw.contains("__MSG_")) return raw
        return Regex("""__MSG_([A-Za-z0-9_@]+)__""").replace(raw) { match ->
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

    private fun parseDeclarativeNetRulesFile(file: File): List<ExtensionNetRule> {
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val list = mutableListOf<ExtensionNetRule>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optInt("id", i + 1)
                val actionObj = obj.optJSONObject("action") ?: continue
                val actionType = actionObj.optString("type", "block")
                val redirectUrl = actionObj.optJSONObject("redirect")?.optString("url")
                val condObj = obj.optJSONObject("condition") ?: continue
                val regexFilter = condObj.optString("regexFilter", "").trim()
                val urlFilter = if (regexFilter.isNotBlank()) regexFilter else condObj.optString("urlFilter", "").trim()
                val requestDomains = jsonStringArrayToList(condObj.optJSONArray("requestDomains"))
                    .map { it.lowercase(Locale.US) }
                val excludedRequestDomains = jsonStringArrayToList(condObj.optJSONArray("excludedRequestDomains"))
                    .map { it.lowercase(Locale.US) }
                if (urlFilter.isNotBlank() || requestDomains.isNotEmpty()) {
                    list.add(
                        ExtensionNetRule(
                            id = id,
                            actionType = actionType,
                            urlFilter = urlFilter,
                            redirectUrl = redirectUrl,
                            initiatorDomains = jsonStringArrayToList(condObj.optJSONArray("initiatorDomains")).ifEmpty {
                                jsonStringArrayToList(condObj.optJSONArray("domains"))
                            }.map { it.lowercase(Locale.US) },
                            excludedInitiatorDomains = jsonStringArrayToList(condObj.optJSONArray("excludedInitiatorDomains")).ifEmpty {
                                jsonStringArrayToList(condObj.optJSONArray("excludedDomains"))
                            }.map { it.lowercase(Locale.US) },
                            requestDomains = requestDomains,
                            excludedRequestDomains = excludedRequestDomains,
                            isRegexFilter = regexFilter.isNotBlank()
                        )
                    )
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
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
