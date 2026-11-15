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

        // Verify non-blocking prewarmHostAsync, package name com.android.ddm & App Version 1.0
        dm.warpDns.prewarmHostAsync("www.google.com")
        assertEquals("1.0", BuildConfig.VERSION_NAME)
        assertEquals(1, BuildConfig.VERSION_CODE)
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
}
