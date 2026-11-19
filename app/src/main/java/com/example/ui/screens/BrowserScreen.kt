package com.example.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toAndroidRectF
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.core.graphics.toRect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.haptics.LocalDragonHaptics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import com.example.data.browser.BrowserBookmark
import com.example.data.browser.BrowserExtension
import com.example.data.browser.BrowserExtensionEngine
import com.example.data.browser.BrowserHistoryEntry
import com.example.data.browser.BrowserPasskeyManager
import com.example.data.browser.BrowserPipController
import com.example.data.browser.BrowserProxyManager
import com.example.data.browser.BrowserProxyType
import com.example.data.browser.BrowserStateRepository
import com.example.data.browser.BrowserTab
import com.example.data.browser.DesktopViewportController
import com.example.data.browser.GrabbedMediaCandidate
import com.example.data.browser.GrabbedMediaKind
import com.example.data.browser.OmniboxSuggestionSource
import com.example.data.browser.SavedPasswordEntry
import com.example.data.downloader.DnsProviderMode
import com.example.data.downloader.DragonDownloadManager
import com.example.data.downloader.WebViewMediaExtractor
import com.example.data.model.DownloadStateStage
import com.example.data.model.MediaFormatOption
import com.example.data.model.MediaInfo
import com.example.data.model.MediaPlatform
import com.example.data.repository.KernelRepository
import com.example.data.security.DownloadSecurityVerdict
import com.example.data.security.MalwareProtectionEngine
import com.example.data.security.SecuritySeverity
import com.example.data.security.UrlSecurityVerdict
import com.example.ui.components.StartDownloadPopupDialog
import com.example.ui.haptics.LocalDragonHaptics
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class BrowserOverlaySheet {
    NONE,
    TABS,
    HISTORY,
    BOOKMARKS,
    DNS,
    PROXY,
    EXTENSIONS,
    MEDIA_GRABBER,
    PASSWORDS,
    GOOGLE_SYNC,
    SECURITY_SHIELD
}

private const val MOBILE_USER_AGENT_FALLBACK =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
private const val DESKTOP_USER_AGENT_FALLBACK =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

internal fun clearAllBrowserCookies(onCompleted: () -> Unit = {}) {
    runCatching {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies {
            runCatching { cm.flush() }
            onCompleted()
        }
        cm.removeSessionCookies(null)
    }.onFailure {
        onCompleted()
    }
}

internal fun buildGoogleSignInCompatibleUserAgent(systemUserAgent: String, desktopMode: Boolean): String {
    val chromeVersion = Regex("Chrome/([0-9.]+)").find(systemUserAgent)?.groupValues?.getOrNull(1)
        ?.takeIf { it.isNotBlank() } ?: "134.0.0.0"
    return if (desktopMode) {
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
    } else {
        // Strip Android WebView markers (`; wv` and `Version/4.0`) while matching Sec-CH-UA's Chrome version
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Mobile Safari/537.36"
    }
}

private fun resolveBrowserUserAgent(context: android.content.Context, desktopMode: Boolean): String {
    val sysUa = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull().orEmpty()
    if (sysUa.isBlank()) {
        return if (desktopMode) DESKTOP_USER_AGENT_FALLBACK else MOBILE_USER_AGENT_FALLBACK
    }
    return buildGoogleSignInCompatibleUserAgent(sysUa, desktopMode)
}

private fun applyGoogleSignInCompatibleWebSettings(webView: WebView) {
    val settings = webView.settings
    runCatching {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }
    }
    runCatching {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            WebSettingsCompat.setWebAuthenticationSupport(
                settings,
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private object ActiveBrowserScreenCallbacks {
    @Volatile var token: Any? = null
    @Volatile var onDownloadIntercepted: ((MediaInfo, MediaFormatOption) -> Unit)? = null
    @Volatile var onRendererCrashed: ((String, WebView?) -> Unit)? = null
    @Volatile var onLongPressLink: ((String) -> Unit)? = null
    @Volatile var onShowFullscreenVideo: ((View, WebChromeClient.CustomViewCallback?) -> Unit)? = null
    @Volatile var onHideFullscreenVideo: (() -> Unit)? = null
    @Volatile var onOpenInlineStreamPlayer: ((String, String) -> Unit)? = null
    @Volatile var onInstallChromeWebStoreExtension: ((String, String?) -> Unit)? = null
    @Volatile var onUpdateTabUrl: ((String) -> Unit)? = null
    @Volatile var onOpenWindowPopup: ((WebView) -> Unit)? = null
    @Volatile var onAttachSubPopup: ((WebView) -> Unit)? = null
    @Volatile var onCloseWindowPopup: ((WebView) -> Unit)? = null
    @Volatile var onInspectorElementPicked: ((String) -> Unit)? = null
    @Volatile var onPasskeyFallbackRequested: ((String, String) -> Unit)? = null
    @Volatile var onSecurityThreatBlocked: ((String, UrlSecurityVerdict) -> Unit)? = null
    @Volatile var onOriginPermissionRequested: ((PermissionRequest) -> Unit)? = null
    @Volatile var onWebPipRequested: ((String, Boolean, Int, Int, Float, Float, Float, Float) -> Unit)? = null
    @Volatile var onSystemPipPopOutRequested: ((String, Int, Int, Float, Float, Float, Float) -> Unit)? = null

    fun clearIfMatches(ownerToken: Any) {
        if (token === ownerToken) {
            token = null
            onDownloadIntercepted = null
            onRendererCrashed = null
            onLongPressLink = null
            onShowFullscreenVideo = null
            onHideFullscreenVideo = null
            onOpenInlineStreamPlayer = null
            onInstallChromeWebStoreExtension = null
            onUpdateTabUrl = null
            onOpenWindowPopup = null
            onAttachSubPopup = null
            onCloseWindowPopup = null
            onInspectorElementPicked = null
            onPasskeyFallbackRequested = null
            onSecurityThreatBlocked = null
            onOriginPermissionRequested = null
            onWebPipRequested = null
            onSystemPipPopOutRequested = null
        }
    }
}

@Composable
fun BrowserScreen(
    repository: KernelRepository,
    onCloseBrowser: () -> Unit,
    onOpenDownloadsTab: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val haptics = LocalDragonHaptics.current
    val isInPipMode by BrowserPipController.isInPipModeFlow.collectAsState()
    val isActivityResumed by BrowserPipController.isActivityResumedFlow.collectAsState()
    val isBackgroundMinimized by BrowserPipController.isBackgroundMinimizedFlow.collectAsState()

    val browserRepo = repository.browserRepository
    val downloadManager = repository.downloadManager

    val tabs by browserRepo.tabsFlow.collectAsState()
    val activeTabId by browserRepo.activeTabIdFlow.collectAsState()
    val activeTab = remember(tabs, activeTabId) {
        tabs.find { it.id == activeTabId } ?: tabs.first()
    }
    val history by browserRepo.historyFlow.collectAsState()
    val bookmarks by browserRepo.bookmarksFlow.collectAsState()
    val grabbedMediaByTab by browserRepo.grabbedMediaByTabFlow.collectAsState()
    val consoleLogsByTab by browserRepo.consoleLogsByTabFlow.collectAsState()
    val networkLogsByTab by browserRepo.networkLogsByTabFlow.collectAsState()
    val mediaGrabberEnabled by browserRepo.mediaGrabberEnabledFlow.collectAsState()
    val pageInspectorEnabled by browserRepo.pageInspectorEnabledFlow.collectAsState()
    val savedPasswords by browserRepo.savedPasswordsFlow.collectAsState()
    val googleSyncState by browserRepo.googleSyncStateFlow.collectAsState()
    val proxyEnabled by browserRepo.proxyEnabledFlow.collectAsState()
    val proxyProfiles by browserRepo.proxyProfilesFlow.collectAsState()
    val activeProxyId by browserRepo.activeProxyIdFlow.collectAsState()
    val activeProxyProfile = remember(proxyProfiles, activeProxyId) {
        proxyProfiles.firstOrNull { it.id == activeProxyId } ?: proxyProfiles.firstOrNull()
    }
    val extensions by browserRepo.extensionEngine.extensionsFlow.collectAsState()
    val enabledExtensionsCount = remember(extensions) { extensions.count { it.enabled } }
    val maxConnections by downloadManager.maxConnectionsFlow.collectAsState()
    val dnsMode by downloadManager.dnsModeFlow.collectAsState()
    val customDnsList by downloadManager.customDnsListFlow.collectAsState()
    val activeCustomDns by downloadManager.activeCustomDnsFlow.collectAsState()
    val warpDnsTelemetry by downloadManager.warpDnsTelemetryFlow.collectAsState()
    val securityShieldState by MalwareProtectionEngine.stateFlow.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val currentGrabbedMedia = grabbedMediaByTab[activeTab.id].orEmpty()
    val currentConsoleLogs = consoleLogsByTab[activeTab.id].orEmpty()
    val currentNetworkLogs = networkLogsByTab[activeTab.id].orEmpty()

    LaunchedEffect(Unit) {
        MalwareProtectionEngine.ensureInitialized(context)
    }

    LaunchedEffect(dnsMode, activeCustomDns) {
        browserRepo.extensionEngine.setHttpDns(
            if (dnsMode != DnsProviderMode.SYSTEM_DEFAULT) downloadManager.warpDns else okhttp3.Dns.SYSTEM
        )
    }

    LaunchedEffect(proxyEnabled, activeProxyProfile, dnsMode, activeCustomDns) {
        BrowserProxyManager.configureWebViewNetworkRouting(
            context = context,
            proxyEnabled = proxyEnabled,
            proxyProfile = activeProxyProfile,
            warpDns = downloadManager.warpDns
        )
    }

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showMoreSubmenu by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf(BrowserOverlaySheet.NONE) }
    var activeExtensionPopup by remember { mutableStateOf<Pair<BrowserExtension, Int>?>(null) }
    var isFloatingInspectorOpen by remember { mutableStateOf(false) }
    var isElementPickerActive by remember { mutableStateOf(false) }
    var inspectorSelectedSelector by remember { mutableStateOf("") }
    var inspectorInitialTab by remember { mutableStateOf(0) }
    var isInstallingCwsExtension by remember { mutableStateOf(false) }
    var isImportingLocalExtension by remember { mutableStateOf(false) }
    var localImportStatusLabel by remember { mutableStateOf("") }
    var cwsInstallError by remember { mutableStateOf<String?>(null) }

    // Download confirmation popup request & security alerts
    var pendingPopupDownload by remember { mutableStateOf<Pair<MediaInfo, MediaFormatOption>?>(null) }
    var pendingDownloadSecurityPrompt by remember {
        mutableStateOf<Triple<MediaInfo, MediaFormatOption, DownloadSecurityVerdict>?>(null)
    }
    var pendingThreatPrompt by remember { mutableStateOf<Pair<String, UrlSecurityVerdict>?>(null) }
    var pendingOriginPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    var longPressedLinkUrl by remember { mutableStateOf<String?>(null) }
    var activeInlineStreamPlayer by remember { mutableStateOf<Pair<String, String>?>(null) }
    var oauthPopupWebView by remember { mutableStateOf<WebView?>(null) }
    var subPopupWebView by remember { mutableStateOf<WebView?>(null) }
    var passkeyFallbackPrompt by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showPipPermissionPrompt by remember { mutableStateOf(false) }
    var floatingPipTabId by remember { mutableStateOf<String?>(null) }
    var lastDownloadInterceptUrl by remember { mutableStateOf("") }
    var lastDownloadInterceptMs by remember { mutableStateOf(0L) }

    fun launchSystemChromePasskeyFlow(targetUrl: String) {
        val uri = runCatching { Uri.parse(targetUrl) }.getOrNull() ?: return
        val customTabIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtras(
                android.os.Bundle().apply {
                    putBinder("android.support.customtabs.extra.SESSION", null)
                }
            )
            putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1)
            setPackage("com.android.chrome")
        }
        val launchedChrome = runCatching {
            context.startActivity(customTabIntent)
            true
        }.getOrDefault(false)
        if (!launchedChrome) {
            runCatching {
                val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtras(
                        android.os.Bundle().apply {
                            putBinder("android.support.customtabs.extra.SESSION", null)
                        }
                    )
                }
                context.startActivity(fallbackIntent)
            }.onFailure {
                Toast.makeText(context, "No external browser found to complete passkey sign-in", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun handleBrowserDownloadIntercepted(
        mediaInfo: MediaInfo,
        format: MediaFormatOption,
        bypassSecurityCheck: Boolean = false
    ) {
        val normUrl = format.streamUrl.trim()
        val now = android.os.SystemClock.elapsedRealtime()
        if (!bypassSecurityCheck && normUrl.isNotEmpty() && normUrl == lastDownloadInterceptUrl && (now - lastDownloadInterceptMs) < 3500L) {
            val current = pendingPopupDownload
            if (current != null && format.contentLengthBytes > 0L && current.second.contentLengthBytes <= 0L) {
                pendingPopupDownload = mediaInfo to format
            }
            return
        }
        val rawFileCandidate = buildString {
            append(mediaInfo.title.ifBlank { "download" })
            if (format.container.isNotBlank() && !mediaInfo.title.endsWith(".${format.container}", ignoreCase = true)) {
                append(".${format.container}")
            }
        }
        val verdict = MalwareProtectionEngine.inspectDownload(
            url = normUrl,
            rawFilename = rawFileCandidate,
            mimeType = format.mimeType,
            context = context
        )
        val safeTitle = verdict.sanitizedFileName.substringBeforeLast('.').ifBlank { verdict.sanitizedFileName }
        val sanitizedInfo = if (safeTitle != mediaInfo.title) mediaInfo.copy(title = safeTitle) else mediaInfo

        if (!bypassSecurityCheck && verdict.requiresUserConfirmation) {
            haptics.warning()
            MalwareProtectionEngine.recordThreatEvent(
                context = context,
                category = verdict.category,
                severity = verdict.riskLevel,
                target = verdict.sanitizedFileName,
                reason = verdict.reason,
                blocked = verdict.isCriticalBlock
            )
            pendingDownloadSecurityPrompt = Triple(sanitizedInfo, format, verdict)
            return
        }

        lastDownloadInterceptUrl = normUrl
        lastDownloadInterceptMs = now
        pendingPopupDownload = sanitizedInfo to format
    }

    // Fullscreen HTML5 Video CustomView state (for YouTube, Bilibili, Vimeo, JWPlayer, HLS/DASH players, etc.)
    var fullscreenCustomView by remember { mutableStateOf<View?>(null) }
    var fullscreenCustomViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    // WebView registry keyed by tab.id — seamlessly adopts any retained live background WebViews
    val adoptedRetainedFromBackground = remember {
        com.example.data.browser.BackgroundMediaWebViewStore.hasRetainedWebViews()
    }
    val webViews = remember {
        mutableStateMapOf<String, WebView>().apply {
            val retained = com.example.data.browser.BackgroundMediaWebViewStore.takeRetainedWebViews(context)
            putAll(retained)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val callbacksOwnerToken = remember { Any() }

    fun installChromeWebStoreExtensionAsync(rawInput: String, titleHint: String? = null) {
        if (isInstallingCwsExtension) return
        isInstallingCwsExtension = true
        cwsInstallError = null
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                browserRepo.extensionEngine.installFromChromeWebStore(rawInput, titleHint)
            }
            isInstallingCwsExtension = false
            result.onSuccess { imported ->
                cwsInstallError = null
                haptics.confirm()
                repository.showActionFeedback("Installed extension: ${imported.name} (v${imported.version})")
                Toast.makeText(
                    context,
                    "Extension installed & active: ${imported.name}",
                    Toast.LENGTH_SHORT
                ).show()
                webViews[activeTab.id]?.reload()
            }.onFailure { err ->
                haptics.warning()
                cwsInstallError = err.message ?: "Chrome Web Store installation failed"
                Toast.makeText(
                    context,
                    cwsInstallError,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // SAF File Picker for importing extensions (.zip, .crx, .xpi, .user.js, .js, .css, manifest.json) from local storage
    val extensionFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null && !isImportingLocalExtension) {
            isImportingLocalExtension = true
            localImportStatusLabel = "Importing extension package..."
            cwsInstallError = null
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    browserRepo.extensionEngine.importExtensionFromUri(uri)
                }
                isImportingLocalExtension = false
                localImportStatusLabel = ""
                result.onSuccess { imported ->
                    cwsInstallError = null
                    haptics.confirm()
                    repository.showActionFeedback("Installed extension: ${imported.name} (v${imported.version})")
                    Toast.makeText(
                        context,
                        "Extension installed & active: ${imported.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                    webViews[activeTab.id]?.reload()
                }.onFailure { err ->
                    haptics.warning()
                    cwsInstallError = err.message ?: "Unsupported extension format"
                    Toast.makeText(
                        context,
                        "Extension import failed: ${err.message ?: "Unsupported format"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // SAF Directory Picker for importing unpacked extension folders (manifest.json + scripts/locales/assets)
    val extensionFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null && !isImportingLocalExtension) {
            isImportingLocalExtension = true
            localImportStatusLabel = "Importing unpacked extension folder..."
            cwsInstallError = null
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    browserRepo.extensionEngine.importUnpackedExtensionFromTreeUri(treeUri)
                }
                isImportingLocalExtension = false
                localImportStatusLabel = ""
                result.onSuccess { imported ->
                    cwsInstallError = null
                    haptics.confirm()
                    repository.showActionFeedback("Installed unpacked extension: ${imported.name} (v${imported.version})")
                    Toast.makeText(
                        context,
                        "Unpacked extension installed: ${imported.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                    webViews[activeTab.id]?.reload()
                }.onFailure { err ->
                    haptics.warning()
                    cwsInstallError = err.message ?: "Unable to import unpacked folder"
                    Toast.makeText(
                        context,
                        "Unpacked import failed: ${err.message ?: "Invalid extension folder"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // SAF File Picker for Importing Passwords (Chrome/Edge/Brave/Firefox CSV or Bitwarden/JSON)
    val passwordFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { docUri: Uri? ->
        if (docUri != null) {
            coroutineScope.launch {
                val (fileName, rawText) = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(docUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: docUri.lastPathSegment ?: "passwords.csv"
                    val content = runCatching {
                        context.contentResolver.openInputStream(docUri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    }.getOrNull().orEmpty()
                    name to content
                }
                val (added, skipped) = browserRepo.importPasswordsFromText(rawText, fileName)
                if (added > 0) {
                    haptics.confirm()
                    val msg = if (skipped > 0) "Imported $added passwords ($skipped duplicates skipped)" else "Imported $added passwords"
                    repository.showActionFeedback(msg)
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    activeSheet = BrowserOverlaySheet.PASSWORDS
                } else if (skipped > 0) {
                    haptics.tap()
                    Toast.makeText(context, "All $skipped passwords already saved", Toast.LENGTH_SHORT).show()
                    activeSheet = BrowserOverlaySheet.PASSWORDS
                } else {
                    haptics.warning()
                    Toast.makeText(context, "No valid credentials found in $fileName (expected CSV or JSON)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // SAF File Picker for Importing Bookmarks from other browsers (Chrome/Firefox/Edge/Brave HTML or JSON)
    val bookmarkFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { docUri: Uri? ->
        if (docUri != null) {
            coroutineScope.launch {
                val (fileName, rawText) = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(docUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: docUri.lastPathSegment ?: "bookmarks.html"
                    val content = runCatching {
                        context.contentResolver.openInputStream(docUri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    }.getOrNull().orEmpty()
                    name to content
                }
                val (added, skipped) = browserRepo.importBookmarksFromText(rawText, fileName)
                if (added > 0) {
                    haptics.confirm()
                    val msg = if (skipped > 0) "Imported $added bookmarks ($skipped duplicates skipped)" else "Imported $added bookmarks"
                    repository.showActionFeedback(msg)
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    activeSheet = BrowserOverlaySheet.BOOKMARKS
                } else if (skipped > 0) {
                    haptics.tap()
                    Toast.makeText(context, "All $skipped bookmarks already saved", Toast.LENGTH_SHORT).show()
                    activeSheet = BrowserOverlaySheet.BOOKMARKS
                } else {
                    haptics.warning()
                    Toast.makeText(context, "No bookmarks found in $fileName (expected HTML or JSON)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Omnibox text input state synced to active tab when unfocused
    var omniboxValue by remember(activeTab.id) {
        mutableStateOf(TextFieldValue(activeTab.url, TextRange.Zero))
    }
    var isOmniboxFocused by remember { mutableStateOf(false) }
    var hasEditedOmnibox by remember { mutableStateOf(false) }
    var selectAllOnFocus by remember { mutableStateOf(false) }
    val omniboxFocusRequester = remember { FocusRequester() }
    LaunchedEffect(activeTab.id, activeTab.url, isOmniboxFocused) {
        if (!isOmniboxFocused) {
            omniboxValue = TextFieldValue(activeTab.url, TextRange.Zero)
            hasEditedOmnibox = false
        }
    }
    LaunchedEffect(isOmniboxFocused) {
        if (isOmniboxFocused && selectAllOnFocus) {
            kotlinx.coroutines.delay(220)
            selectAllOnFocus = false
        } else if (!isOmniboxFocused) {
            selectAllOnFocus = false
            hasEditedOmnibox = false
        }
    }
    val omniboxSuggestions = remember(
        omniboxValue.text,
        bookmarks,
        history,
        isOmniboxFocused,
        hasEditedOmnibox
    ) {
        if (isOmniboxFocused && (hasEditedOmnibox || omniboxValue.text.isEmpty())) {
            BrowserStateRepository.findOmniboxSuggestions(
                query = omniboxValue.text,
                bookmarks = bookmarks,
                history = history,
                maxResults = 8
            )
        } else {
            emptyList()
        }
    }

    val isBookmarked = remember(bookmarks, activeTab.url) {
        browserRepo.isBookmarked(activeTab.url)
    }

    // Silently hydrate Google Sync via Android Credential Manager + WebView cookies on screen launch & sheet open
    LaunchedEffect(Unit) {
        browserRepo.googleSyncManager.inspectActiveWebViewGoogleCookiesAsync(
            webViews[activeTab.id]?.settings?.userAgentString
        )
        browserRepo.googleSyncManager.trySilentGoogleSignInIfNeeded(context)
        if (browserRepo.googleSyncManager.syncStateFlow.value.isBrowserGoogleConnected) {
            browserRepo.syncAccountVaultNow()
            if (browserRepo.googleSyncManager.syncStateFlow.value.autoSyncEnabled) {
                browserRepo.googleSyncManager.triggerManualSyncNow()
            }
        }
    }

    LaunchedEffect(activeSheet) {
        if (activeSheet == BrowserOverlaySheet.GOOGLE_SYNC ||
            activeSheet == BrowserOverlaySheet.PASSWORDS
        ) {
            browserRepo.googleSyncManager.inspectActiveWebViewGoogleCookiesAsync(
                webViews[activeTab.id]?.settings?.userAgentString
            )
            browserRepo.googleSyncManager.trySilentGoogleSignInIfNeeded(context)
            if (browserRepo.googleSyncManager.syncStateFlow.value.isBrowserGoogleConnected) {
                browserRepo.syncAccountVaultNow()
                if (browserRepo.googleSyncManager.syncStateFlow.value.autoSyncEnabled) {
                    browserRepo.googleSyncManager.triggerManualSyncNow()
                }
            }
        }
    }

    // Keep active tab AND any in-app floating PiP tab unpaused across tab switches
    LaunchedEffect(activeTab.id, webViews.size, floatingPipTabId) {
        webViews.forEach { (id, wv) ->
            runCatching {
                if (id == activeTab.id || id == floatingPipTabId) {
                    wv.resumeTimers()
                    wv.onResume()
                } else {
                    wv.onPause()
                }
            }
        }
    }

    val currentActiveTabId by rememberUpdatedState(activeTab.id)
    val currentFloatingPipTabId by rememberUpdatedState(floatingPipTabId)

    LaunchedEffect(tabs, floatingPipTabId) {
        val currentFloat = floatingPipTabId
        if (currentFloat != null && tabs.none { it.id == currentFloat }) {
            floatingPipTabId = null
        }
    }
    LaunchedEffect(activeTab.id) {
        BrowserPipController.updateActiveTab(activeTab.id)
    }
    LaunchedEffect(floatingPipTabId) {
        BrowserPipController.updateFloatingPipTab(floatingPipTabId)
        context.findActivity()?.let { BrowserPipController.syncActivityPipParams(it) }
    }
    LaunchedEffect(fullscreenCustomView) {
        BrowserPipController.updateFullscreenVideo(fullscreenCustomView != null)
    }
    LaunchedEffect(activeInlineStreamPlayer) {
        BrowserPipController.updateStreamPlayerOpen(
            isOpen = activeInlineStreamPlayer != null,
            streamTitle = activeInlineStreamPlayer?.second.orEmpty(),
            streamUrl = activeInlineStreamPlayer?.first.orEmpty()
        )
    }
    DisposableEffect(Unit) {
        BrowserPipController.updateBrowserOpen(true)
        onDispose {
            val retainBackgroundMedia = !BrowserPipController.isExplicitBrowserClose && (
                BrowserPipController.stateFlow.value.shouldKeepBackgroundMediaAlive ||
                    BrowserPipController.isBackgroundMinimizedFlow.value ||
                    BrowserPipController.isInPipModeFlow.value
                )
            if (!retainBackgroundMedia) {
                BrowserPipController.updateFloatingPipTab(null)
                BrowserPipController.updateFullscreenVideo(false)
                BrowserPipController.updateStreamPlayerOpen(false)
                BrowserPipController.updateBrowserOpen(false)
            }
        }
    }

    LaunchedEffect(Unit) {
        BrowserPipController.togglePlayEvents.collect {
            if (activeInlineStreamPlayer == null) {
                val targetTabId = currentFloatingPipTabId ?: currentActiveTabId
                webViews[targetTabId]?.evaluateJavascript(PIP_TOGGLE_ACTIVE_VIDEO_JS, null)
            }
        }
    }

    LaunchedEffect(Unit) {
        BrowserPipController.stopPlaybackEvents.collect {
            if (activeInlineStreamPlayer == null) {
                webViews.values.forEach { wv ->
                    wv.evaluateJavascript(PIP_STOP_ALL_MEDIA_JS, null)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        BrowserPipController.stateFlow.collect { state ->
            val keepBg = state.shouldKeepBackgroundMediaAlive
            webViews.forEach { (id, wv) ->
                if (wv is BackgroundMediaWebView) {
                    wv.keepMediaAliveInBackground =
                        keepBg && (id == state.activeTabId || id == state.floatingPipTabId)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        BrowserPipController.pipPermissionMissingEvents.collect {
            showPipPermissionPrompt = true
        }
    }

    var wasInPipOrCrossTabSpotlight by remember { mutableStateOf(adoptedRetainedFromBackground) }
    LaunchedEffect(
        isInPipMode,
        isActivityResumed,
        isBackgroundMinimized,
        fullscreenCustomView,
        activeInlineStreamPlayer,
        activeTab.id,
        floatingPipTabId
    ) {
        val activeWv = webViews[activeTab.id]
        val floatWv = floatingPipTabId?.let { webViews[it] }
        if (isInPipMode && fullscreenCustomView == null && activeInlineStreamPlayer == null) {
            wasInPipOrCrossTabSpotlight = true
            val targetWv = floatWv ?: activeWv
            if (targetWv != null) {
                runCatching {
                    if (targetWv is BackgroundMediaWebView) {
                        targetWv.keepMediaAliveInBackground = true
                    }
                    targetWv.resumeTimers()
                    targetWv.onResume()
                    targetWv.requestLayout()
                    targetWv.invalidate()
                }
                targetWv.evaluateJavascript(PIP_ENTER_INLINE_SPOTLIGHT_JS, null)
                mainHandler.postDelayed({
                    if (BrowserPipController.isInPipModeFlow.value) {
                        runCatching {
                            targetWv.resumeTimers()
                            targetWv.onResume()
                            targetWv.invalidate()
                        }
                        targetWv.evaluateJavascript(PIP_ENTER_INLINE_SPOTLIGHT_JS, null)
                    }
                }, 160L)
            }
        } else if (!isActivityResumed || isBackgroundMinimized) {
            // User tapped the System PiP cross (X) button or minimized to background:
            // keep the video playing in the background instead of closing or tearing down PiP guards.
            val targetWv = floatWv ?: activeWv
            if (targetWv != null && (BrowserPipController.stateFlow.value.shouldKeepBackgroundMediaAlive || isBackgroundMinimized || wasInPipOrCrossTabSpotlight)) {
                wasInPipOrCrossTabSpotlight = true
                runCatching {
                    if (targetWv is BackgroundMediaWebView) {
                        targetWv.keepMediaAliveInBackground = true
                    }
                    targetWv.resumeTimers()
                    targetWv.onResume()
                }
                targetWv.evaluateJavascript(PIP_ENTER_BACKGROUND_AUDIO_MODE_JS, null)
                mainHandler.postDelayed({
                    if (!BrowserPipController.isInPipModeFlow.value && !BrowserPipController.isActivityResumedFlow.value) {
                        runCatching {
                            targetWv.resumeTimers()
                            targetWv.onResume()
                        }
                        targetWv.evaluateJavascript(PIP_ENTER_BACKGROUND_AUDIO_MODE_JS, null)
                    }
                }, 180L)
            }
        } else {
            val fId = floatingPipTabId
            if (activeWv != null) {
                runCatching {
                    activeWv.resumeTimers()
                    activeWv.onResume()
                    activeWv.requestLayout()
                    activeWv.invalidate()
                }
            }
            if (floatWv != null && floatWv !== activeWv) {
                runCatching {
                    floatWv.resumeTimers()
                    floatWv.onResume()
                    floatWv.requestLayout()
                    floatWv.invalidate()
                }
            }
            if (fId == activeTab.id) {
                wasInPipOrCrossTabSpotlight = true
                activeWv?.evaluateJavascript(PIP_RESTORE_FLOATING_PIP_JS, null)
            } else if (fId != null && floatWv != null) {
                wasInPipOrCrossTabSpotlight = true
                activeWv?.evaluateJavascript(PIP_EXIT_INLINE_SPOTLIGHT_JS, null)
                floatWv.evaluateJavascript(PIP_ENTER_INLINE_SPOTLIGHT_JS, null)
            } else if (wasInPipOrCrossTabSpotlight) {
                wasInPipOrCrossTabSpotlight = false
                activeWv?.evaluateJavascript(PIP_EXIT_INLINE_SPOTLIGHT_JS, null)
                mainHandler.postDelayed({
                    if (!BrowserPipController.isInPipModeFlow.value && activeWv != null) {
                        runCatching {
                            activeWv.resumeTimers()
                            activeWv.onResume()
                            activeWv.requestLayout()
                            activeWv.invalidate()
                        }
                    }
                }, 180L)
            }
            if (activeWv != null && activeTab.desktopMode) {
                mainHandler.postDelayed({
                    if (!BrowserPipController.isInPipModeFlow.value) {
                        DesktopViewportController.syncWebViewViewport(
                            webView = activeWv,
                            desktopMode = true,
                            forceRefit = false,
                            measureOverflow = false
                        )
                    }
                }, 120L)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    val activity = context.findActivity()
                    val pipState = BrowserPipController.stateFlow.value
                    val keepMediaAlive = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        activity?.isInPictureInPictureMode == true) ||
                        BrowserPipController.isInPipModeFlow.value ||
                        pipState.shouldKeepBackgroundMediaAlive
                    if (keepMediaAlive) {
                        webViews.forEach { (id, wv) ->
                            runCatching {
                                if (id == currentActiveTabId || id == currentFloatingPipTabId) {
                                    if (wv is BackgroundMediaWebView) {
                                        wv.keepMediaAliveInBackground = true
                                    }
                                    wv.evaluateJavascript(PIP_ARM_TRANSITION_GUARD_JS, null)
                                    wv.resumeTimers()
                                    wv.onResume()
                                } else {
                                    wv.onPause()
                                }
                            }
                        }
                    } else {
                        webViews.values.forEach { wv ->
                            runCatching {
                                if (wv is BackgroundMediaWebView) {
                                    wv.keepMediaAliveInBackground = false
                                }
                                wv.onPause()
                                wv.pauseTimers()
                            }
                        }
                        oauthPopupWebView?.let { popup ->
                            runCatching { popup.onPause() }
                        }
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    val activity = context.findActivity()
                    val pipState = BrowserPipController.stateFlow.value
                    val keepMediaAlive = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        activity?.isInPictureInPictureMode == true) ||
                        BrowserPipController.isInPipModeFlow.value ||
                        BrowserPipController.isBackgroundMinimizedFlow.value ||
                        pipState.shouldKeepBackgroundMediaAlive
                    if (keepMediaAlive) {
                        webViews.forEach { (id, wv) ->
                            runCatching {
                                if (id == currentActiveTabId || id == currentFloatingPipTabId) {
                                    if (wv is BackgroundMediaWebView) {
                                        wv.keepMediaAliveInBackground = true
                                    }
                                    if (!BrowserPipController.isInPipModeFlow.value) {
                                        wv.evaluateJavascript(PIP_ENTER_BACKGROUND_AUDIO_MODE_JS, null)
                                    }
                                    wv.resumeTimers()
                                    wv.onResume()
                                }
                            }
                        }
                    } else {
                        webViews.values.forEach { wv ->
                            runCatching {
                                if (wv is BackgroundMediaWebView) {
                                    wv.keepMediaAliveInBackground = false
                                }
                                wv.onPause()
                                wv.pauseTimers()
                            }
                        }
                        oauthPopupWebView?.let { popup ->
                            runCatching { popup.onPause() }
                        }
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    webViews.values.forEach { wv ->
                        runCatching { wv.resumeTimers() }
                    }
                    webViews[currentActiveTabId]?.let { activeWv ->
                        runCatching {
                            activeWv.onResume()
                            activeWv.requestLayout()
                            activeWv.invalidate()
                        }
                    }
                    currentFloatingPipTabId?.let { fId ->
                        webViews[fId]?.let { floatWv ->
                            runCatching {
                                floatWv.onResume()
                                floatWv.requestLayout()
                                floatWv.invalidate()
                            }
                        }
                    }
                    oauthPopupWebView?.let { popup ->
                        runCatching { popup.onResume() }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Clean up or retain playing WebViews when leaving Screen (retain across PiP X dismiss Activity destruction)
    DisposableEffect(Unit) {
        onDispose {
            ActiveBrowserScreenCallbacks.clearIfMatches(callbacksOwnerToken)
            subPopupWebView?.let { sub ->
                runCatching {
                    sub.stopLoading()
                    (sub.parent as? ViewGroup)?.removeView(sub)
                    sub.destroy()
                }
                subPopupWebView = null
            }
            oauthPopupWebView?.let { popup ->
                runCatching {
                    popup.stopLoading()
                    (popup.parent as? ViewGroup)?.removeView(popup)
                    popup.destroy()
                }
                oauthPopupWebView = null
            }
            val retainBackgroundMedia = !BrowserPipController.isExplicitBrowserClose && (
                BrowserPipController.stateFlow.value.shouldKeepBackgroundMediaAlive ||
                    BrowserPipController.isBackgroundMinimizedFlow.value ||
                    BrowserPipController.isInPipModeFlow.value
                )
            if (retainBackgroundMedia && webViews.isNotEmpty()) {
                webViews.forEach { (id, wv) ->
                    if ((id == currentActiveTabId || id == currentFloatingPipTabId) && wv is BackgroundMediaWebView) {
                        wv.keepMediaAliveInBackground = true
                    }
                }
                com.example.data.browser.BackgroundMediaWebViewStore.retainForBackgroundPlayback(
                    appContext = context.applicationContext,
                    activeTabId = currentActiveTabId,
                    floatingTabId = currentFloatingPipTabId,
                    webViews = HashMap(webViews),
                    toggleJs = PIP_TOGGLE_ACTIVE_VIDEO_JS,
                    stopJs = PIP_STOP_ALL_MEDIA_JS,
                    bgAudioJs = PIP_ENTER_BACKGROUND_AUDIO_MODE_JS
                )
                webViews.clear()
            } else {
                com.example.data.browser.BackgroundMediaWebViewStore.stopAndReleaseAll()
                webViews.values.forEach { wv ->
                    try {
                        wv.resumeTimers()
                        wv.stopLoading()
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv.destroy()
                    } catch (_: Exception) {}
                }
                webViews.clear()
            }
        }
    }

    fun closeBrowserTab(tabId: String) {
        if (floatingPipTabId == tabId) {
            floatingPipTabId = null
        }
        val wasLastIncognito = browserRepo.closeTab(tabId)
        webViews.remove(tabId)?.let { wv ->
            try {
                wv.stopLoading()
                (wv.parent as? ViewGroup)?.removeView(wv)
                if (wasLastIncognito) {
                    wv.clearCache(true)
                    wv.clearHistory()
                    CookieManager.getInstance().removeSessionCookies(null)
                }
                wv.destroy()
            } catch (_: Exception) {}
        }
    }

    fun navigateActiveTab(rawInput: String) {
        val resolved = BrowserStateRepository.resolveBrowserInputToUrl(rawInput)
        val verdict = MalwareProtectionEngine.inspectUrl(resolved, context)
        if (verdict.isBlocked) {
            haptics.warning()
            MalwareProtectionEngine.recordThreatEvent(
                context = context,
                category = verdict.category,
                severity = verdict.severity,
                target = resolved,
                reason = verdict.reason,
                blocked = true
            )
            pendingThreatPrompt = resolved to verdict
            focusManager.clearFocus()
            return
        }
        val targetUrl = verdict.upgradedHttpsUrl ?: resolved
        if (verdict.upgradedHttpsUrl != null) {
            MalwareProtectionEngine.recordAutoHttpsUpgrade(verdict.upgradedHttpsUrl, resolved)
        }
        selectAllOnFocus = false
        hasEditedOmnibox = false
        omniboxValue = TextFieldValue(targetUrl, TextRange.Zero)
        focusManager.clearFocus()
        browserRepo.updateTabState(activeTab.id) { it.copy(url = targetUrl, isLoading = true, progress = 12) }
        webViews[activeTab.id]?.loadUrl(targetUrl)
    }

    fun openUrlInTab(url: String, inNewTab: Boolean = false, incognito: Boolean = activeTab.isIncognito) {
        val resolved = BrowserStateRepository.resolveBrowserInputToUrl(url)
        val verdict = MalwareProtectionEngine.inspectUrl(resolved, context)
        if (verdict.isBlocked) {
            haptics.warning()
            MalwareProtectionEngine.recordThreatEvent(
                context = context,
                category = verdict.category,
                severity = verdict.severity,
                target = resolved,
                reason = verdict.reason,
                blocked = true
            )
            pendingThreatPrompt = resolved to verdict
            activeSheet = BrowserOverlaySheet.NONE
            return
        }
        val targetUrl = verdict.upgradedHttpsUrl ?: resolved
        if (verdict.upgradedHttpsUrl != null) {
            MalwareProtectionEngine.recordAutoHttpsUpgrade(verdict.upgradedHttpsUrl, resolved)
        }
        if (inNewTab) {
            browserRepo.openNewTab(initialUrl = targetUrl, isIncognito = incognito)
        } else {
            browserRepo.updateTabState(activeTab.id) { it.copy(url = targetUrl, isLoading = true, progress = 12) }
            webViews[activeTab.id]?.loadUrl(targetUrl)
        }
        activeSheet = BrowserOverlaySheet.NONE
    }

    fun triggerGrabPopup(candidate: GrabbedMediaCandidate) {
        val platform = MediaPlatform.detectFromUrl(candidate.url).let { detected ->
            if (detected == MediaPlatform.UNSUPPORTED) MediaPlatform.DIRECT else detected
        }
        val isGeneric = candidate.kind == GrabbedMediaKind.FILE
        val isAudio = candidate.kind == GrabbedMediaKind.AUDIO
        val mime = when {
            isAudio -> "audio/${candidate.containerExt}"
            isGeneric -> "application/octet-stream"
            else -> "video/${candidate.containerExt}"
        }
        val opt = MediaFormatOption(
            id = "grab_${candidate.url.hashCode()}",
            qualityLabel = candidate.kind.label,
            resolutionHeight = if (!isAudio && !isGeneric) 1080 else 0,
            container = candidate.containerExt,
            mimeType = mime,
            isAudioOnly = isAudio,
            streamUrl = candidate.url,
            requestHeaders = mapOf(
                "Referer" to activeTab.url,
                "User-Agent" to resolveBrowserUserAgent(context, activeTab.desktopMode)
            ),
            isGenericFile = isGeneric,
            fileCategoryLabel = candidate.sourceLabel
        )
        val info = MediaInfo(
            originalUrl = activeTab.url,
            canonicalUrl = candidate.url,
            title = candidate.title.ifBlank { activeTab.title },
            creator = Uri.parse(activeTab.url).host?.removePrefix("www.") ?: "Browser",
            durationSeconds = 0L,
            durationFormatted = if (isGeneric) "File" else "Live Stream",
            thumbnailUrl = "",
            platform = platform,
            videoFormats = if (!isAudio && !isGeneric) listOf(opt) else emptyList(),
            audioFormats = if (isAudio) listOf(opt) else emptyList(),
            extractorSource = "Browser Media Grabber",
            fileFormats = if (isGeneric) listOf(opt) else emptyList()
        )
        pendingPopupDownload = info to opt
    }

    // Predictive & Clean Back Handling (exits fullscreen video first before navigating back)
    BackHandler {
        when {
            fullscreenCustomView != null -> {
                runCatching { fullscreenCustomViewCallback?.onCustomViewHidden() }
                fullscreenCustomView = null
                fullscreenCustomViewCallback = null
            }
            isElementPickerActive -> {
                isElementPickerActive = false
                webViews[activeTab.id]?.evaluateJavascript(buildElementPickerJs(false), null)
                isFloatingInspectorOpen = true
            }
            activeInlineStreamPlayer != null -> activeInlineStreamPlayer = null
            activeSheet != BrowserOverlaySheet.NONE -> activeSheet = BrowserOverlaySheet.NONE
            isFloatingInspectorOpen -> isFloatingInspectorOpen = false
            showOverflowMenu -> {
                showOverflowMenu = false
                showMoreSubmenu = false
            }
            isOmniboxFocused -> {
                hasEditedOmnibox = false
                focusManager.clearFocus()
            }
            webViews[activeTab.id]?.canGoBack() == true -> webViews[activeTab.id]?.goBack()
            else -> onCloseBrowser()
        }
    }

    val isIncognito = activeTab.isIncognito
    val chromeBgColor = if (isIncognito) Color(0xFF14111D) else MaterialTheme.colorScheme.surfaceContainer
    val omniboxBgColor = if (isIncognito) Color(0xFF231E31) else MaterialTheme.colorScheme.surfaceContainerHigh
    val accentTint = if (isIncognito) Color(0xFFC084FC) else MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .let {
                if (isInPipMode) it
                else it.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            }
            .testTag("dragon_browser_screen"),
        color = if (isInPipMode) Color.Black else if (isIncognito) Color(0xFF0E0C15) else MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Sleek Top Omnibox & Quick Controls (hidden in Picture-in-Picture mode)
            if (!isInPipMode) {
            Surface(
                color = chromeBgColor,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isIncognito) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF6B21A8).copy(alpha = 0.35f))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VisibilityOff,
                                    contentDescription = "Incognito Mode Active",
                                    tint = Color(0xFFE9D5FF),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "INCOGNITO MODE • HISTORY NOT SAVED",
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE9D5FF)
                                )
                            }
                            Text(
                                text = "Exit",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        haptics.toggle(false)
                                        browserRepo.toggleCurrentTabIncognito()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Back to Dragon Tools Dashboard
                        IconButton(
                            onClick = {
                                haptics.tap()
                                onCloseBrowser()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("browser_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Return to Dragon Tools",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Rounded Omnibox Search / Address Pill
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp),
                            shape = RoundedCornerShape(24.dp),
                            color = omniboxBgColor,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isOmniboxFocused) accentTint.copy(alpha = 0.85f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isIncognito) {
                                    Icon(
                                        imageVector = Icons.Default.VisibilityOff,
                                        contentDescription = "Incognito Tab",
                                        tint = Color(0xFFD8B4FE),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                if (proxyEnabled && activeProxyProfile != null) {
                                    val proxyBadgeColor = when (activeProxyProfile.type) {
                                        BrowserProxyType.SOCKS5 -> NeonCyan
                                        BrowserProxyType.HTTPS -> SuccessGreen
                                        else -> WarningOrange
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = proxyBadgeColor.copy(alpha = 0.18f),
                                        border = BorderStroke(0.8.dp, proxyBadgeColor.copy(alpha = 0.65f)),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { activeSheet = BrowserOverlaySheet.PROXY }
                                            .testTag("browser_omnibox_proxy_badge")
                                    ) {
                                        Text(
                                            text = activeProxyProfile.type.label,
                                            fontSize = 8.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = proxyBadgeColor,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(
                                            enabled = !isOmniboxFocused,
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            omniboxFocusRequester.requestFocus()
                                        },
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (omniboxValue.text.isEmpty()) {
                                        Text(
                                            text = "Search Google or type URL",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    BasicTextField(
                                        value = omniboxValue,
                                        onValueChange = { incoming ->
                                            if (selectAllOnFocus && incoming.text == omniboxValue.text && incoming.selection.collapsed) {
                                                // Preserve automatic full-URL selection when the initial tap or IME attempts to collapse cursor
                                                omniboxValue = incoming.copy(selection = TextRange(0, incoming.text.length))
                                            } else {
                                                selectAllOnFocus = false
                                                if (incoming.text != omniboxValue.text) {
                                                    hasEditedOmnibox = true
                                                }
                                                omniboxValue = incoming
                                            }
                                        },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = if (isIncognito) Color.White else MaterialTheme.colorScheme.onSurface,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        cursorBrush = SolidColor(accentTint),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                        keyboardActions = KeyboardActions(
                                            onGo = { navigateActiveTab(omniboxValue.text) },
                                            onDone = { navigateActiveTab(omniboxValue.text) },
                                            onSearch = { navigateActiveTab(omniboxValue.text) },
                                            onSend = { navigateActiveTab(omniboxValue.text) }
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp)
                                            .focusRequester(omniboxFocusRequester)
                                            .onFocusChanged { focusState ->
                                                val wasFocused = isOmniboxFocused
                                                isOmniboxFocused = focusState.isFocused
                                                if (!wasFocused && focusState.isFocused) {
                                                    val currentText = omniboxValue.text
                                                    if (currentText.isNotEmpty()) {
                                                        selectAllOnFocus = true
                                                        omniboxValue = omniboxValue.copy(
                                                            selection = TextRange(0, currentText.length)
                                                        )
                                                    }
                                                }
                                            }
                                            .testTag("browser_omnibox_input")
                                    )
                                }

                                if (isOmniboxFocused && omniboxValue.text.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            haptics.confirm()
                                            navigateActiveTab(omniboxValue.text)
                                        },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .testTag("browser_omnibox_go_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = "Go to URL",
                                            tint = accentTint,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            haptics.tap()
                                            selectAllOnFocus = false
                                            hasEditedOmnibox = true
                                            omniboxValue = TextFieldValue("")
                                        },
                                        modifier = Modifier
                                            .size(32.dp)
                                            .testTag("browser_omnibox_clear_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Cancel,
                                            contentDescription = "Clear Address",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else {
                                    // Quick Bookmark Toggle Star
                                    IconButton(
                                        onClick = {
                                            val added = browserRepo.toggleBookmark(activeTab.url, activeTab.title)
                                            haptics.toggle(added)
                                            repository.showActionFeedback(
                                                if (added) "Bookmarked: ${activeTab.title}" else "Bookmark removed"
                                            )
                                        },
                                        modifier = Modifier
                                            .size(34.dp)
                                            .testTag("browser_bookmark_button")
                                    ) {
                                        Icon(
                                            imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarOutline,
                                            contentDescription = if (isBookmarked) "Remove Bookmark" else "Bookmark Page",
                                            tint = if (isBookmarked) Color(0xFFFACC15) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Stop / Reload Button
                        IconButton(
                            onClick = {
                                haptics.tap()
                                val wv = webViews[activeTab.id]
                                if (activeTab.isLoading) {
                                    wv?.stopLoading()
                                } else {
                                    if (wv != null && (wv.url.isNullOrBlank() || wv.url == "about:blank")) {
                                        wv.loadUrl(activeTab.url)
                                    } else {
                                        wv?.reload()
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("browser_reload_button")
                        ) {
                            Icon(
                                imageVector = if (activeTab.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = if (activeTab.isLoading) "Stop Loading" else "Reload Page",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Slim Animated Page Load Progress Bar
                    if (activeTab.isLoading && activeTab.progress in 1..99) {
                        LinearProgressIndicator(
                            progress = { activeTab.progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp),
                            color = accentTint,
                            trackColor = Color.Transparent
                        )
                    } else {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            thickness = 0.7.dp
                        )
                    }
                }
            }
            }

            // 2. WebView Viewport with Floating Media Grabber Chip & In-App Floating PiP
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val viewportMaxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
                val viewportMaxHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
                val viewportDipWidth = maxWidth.value.roundToInt()
                LaunchedEffect(activeTab.id, activeTab.desktopMode, viewportDipWidth, isInPipMode) {
                    if (!isInPipMode && activeTab.desktopMode && viewportDipWidth >= 240) {
                        val wv = webViews[activeTab.id] ?: return@LaunchedEffect
                        DesktopViewportController.syncWebViewViewport(
                            webView = wv,
                            desktopMode = true,
                            forceRefit = false,
                            measureOverflow = false
                        )
                    }
                }
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coords ->
                            if (fullscreenCustomView == null && activeInlineStreamPlayer == null) {
                                BrowserPipController.updateContainerRect(
                                    coords.boundsInWindow().toAndroidRectF().toRect()
                                )
                            }
                        },
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { container ->
                        ActiveBrowserScreenCallbacks.token = callbacksOwnerToken
                        ActiveBrowserScreenCallbacks.onDownloadIntercepted = { mediaInfo, format ->
                            handleBrowserDownloadIntercepted(mediaInfo, format)
                        }
                        ActiveBrowserScreenCallbacks.onRendererCrashed = { crashedTabId, crashedView ->
                            try {
                                crashedView?.stopLoading()
                                (crashedView?.parent as? ViewGroup)?.removeView(crashedView)
                                crashedView?.destroy()
                            } catch (_: Throwable) {}
                            webViews.remove(crashedTabId)
                            browserRepo.updateTabState(crashedTabId) {
                                it.copy(isLoading = false, progress = 100)
                            }
                        }
                        ActiveBrowserScreenCallbacks.onLongPressLink = { url ->
                            longPressedLinkUrl = url
                        }
                        ActiveBrowserScreenCallbacks.onShowFullscreenVideo = { view, callback ->
                            fullscreenCustomView = view
                            fullscreenCustomViewCallback = callback
                        }
                        ActiveBrowserScreenCallbacks.onHideFullscreenVideo = {
                            fullscreenCustomView = null
                            fullscreenCustomViewCallback = null
                        }
                        ActiveBrowserScreenCallbacks.onOpenInlineStreamPlayer = { streamUrl, title ->
                            activeInlineStreamPlayer = streamUrl to title
                        }
                        ActiveBrowserScreenCallbacks.onInstallChromeWebStoreExtension = { urlOrId, titleHint ->
                            installChromeWebStoreExtensionAsync(urlOrId, titleHint)
                        }
                        ActiveBrowserScreenCallbacks.onUpdateTabUrl = { nextUrl ->
                            openUrlInTab(nextUrl, inNewTab = false)
                        }
                        ActiveBrowserScreenCallbacks.onOpenWindowPopup = { popupWv ->
                            if (subPopupWebView === popupWv) {
                                subPopupWebView = null
                            }
                            oauthPopupWebView = popupWv
                        }
                        ActiveBrowserScreenCallbacks.onAttachSubPopup = { childWv ->
                            subPopupWebView?.let { prev ->
                                if (prev !== childWv) {
                                    runCatching {
                                        prev.stopLoading()
                                        (prev.parent as? ViewGroup)?.removeView(prev)
                                        prev.destroy()
                                    }
                                }
                            }
                            subPopupWebView = childWv
                        }
                        ActiveBrowserScreenCallbacks.onCloseWindowPopup = { closingWv ->
                            if (subPopupWebView === closingWv) {
                                runCatching {
                                    closingWv.stopLoading()
                                    (closingWv.parent as? ViewGroup)?.removeView(closingWv)
                                    closingWv.destroy()
                                }
                                subPopupWebView = null
                            }
                            if (oauthPopupWebView === closingWv) {
                                runCatching {
                                    closingWv.stopLoading()
                                    (closingWv.parent as? ViewGroup)?.removeView(closingWv)
                                    closingWv.destroy()
                                }
                                oauthPopupWebView = null
                            }
                        }
                        ActiveBrowserScreenCallbacks.onInspectorElementPicked = { selector ->
                            isElementPickerActive = false
                            inspectorSelectedSelector = selector
                            inspectorInitialTab = 1
                            isFloatingInspectorOpen = true
                        }
                        ActiveBrowserScreenCallbacks.onPasskeyFallbackRequested = { loginUrl, rpId ->
                            passkeyFallbackPrompt = loginUrl to rpId
                        }
                        ActiveBrowserScreenCallbacks.onSecurityThreatBlocked = { threatUrl, verdict ->
                            pendingThreatPrompt = threatUrl to verdict
                        }
                        ActiveBrowserScreenCallbacks.onOriginPermissionRequested = { permReq ->
                            pendingOriginPermissionRequest = permReq
                        }
                        ActiveBrowserScreenCallbacks.onWebPipRequested = { tabId, isFloating, vw, vh, lFrac, tFrac, rFrac, bFrac ->
                            if (isFloating) {
                                floatingPipTabId = tabId
                                BrowserPipController.updateFloatingPipTab(tabId)
                                BrowserPipController.updateWebVideoState(
                                    tabId = tabId,
                                    isPlaying = true,
                                    videoWidth = vw,
                                    videoHeight = vh,
                                    leftFrac = lFrac,
                                    topFrac = tFrac,
                                    rightFrac = rFrac,
                                    bottomFrac = bFrac
                                )
                                context.findActivity()?.let { BrowserPipController.syncActivityPipParams(it) }
                            } else {
                                if (floatingPipTabId == tabId) {
                                    floatingPipTabId = null
                                    BrowserPipController.updateFloatingPipTab(null)
                                }
                                context.findActivity()?.let { BrowserPipController.syncActivityPipParams(it) }
                            }
                        }
                        ActiveBrowserScreenCallbacks.onSystemPipPopOutRequested = { tabId, vw, vh, lFrac, tFrac, rFrac, bFrac ->
                            context.findActivity()?.let { act ->
                                BrowserPipController.enterPipFromWebRequest(
                                    activity = act,
                                    tabId = tabId,
                                    videoWidth = vw,
                                    videoHeight = vh,
                                    leftFrac = lFrac,
                                    topFrac = tFrac,
                                    rightFrac = rFrac,
                                    bottomFrac = bFrac
                                )
                            }
                        }
                        val targetId = activeTab.id
                        val existingWv = webViews[targetId]
                        val activeWebView = existingWv ?: createConfiguredBrowserWebView(
                            context = MutableContextWrapper(context),
                            tab = activeTab,
                            desktopMode = activeTab.desktopMode,
                            warpDns = downloadManager.warpDns,
                            browserRepo = browserRepo,
                            mainHandler = mainHandler,
                            onDownloadIntercepted = { mediaInfo, format ->
                                ActiveBrowserScreenCallbacks.onDownloadIntercepted?.invoke(mediaInfo, format)
                            },
                            onRendererCrashed = { crashedTabId, crashedView ->
                                ActiveBrowserScreenCallbacks.onRendererCrashed?.invoke(crashedTabId, crashedView)
                            },
                            onLongPressLink = { url ->
                                ActiveBrowserScreenCallbacks.onLongPressLink?.invoke(url)
                            },
                            onShowFullscreenVideo = { view, callback ->
                                ActiveBrowserScreenCallbacks.onShowFullscreenVideo?.invoke(view, callback)
                            },
                            onHideFullscreenVideo = {
                                ActiveBrowserScreenCallbacks.onHideFullscreenVideo?.invoke()
                            },
                            onOpenInlineStreamPlayer = { streamUrl, title ->
                                ActiveBrowserScreenCallbacks.onOpenInlineStreamPlayer?.invoke(streamUrl, title)
                            },
                            onInstallChromeWebStoreExtension = { urlOrId, titleHint ->
                                ActiveBrowserScreenCallbacks.onInstallChromeWebStoreExtension?.invoke(urlOrId, titleHint)
                            },
                            onUpdateTabUrl = { nextUrl ->
                                ActiveBrowserScreenCallbacks.onUpdateTabUrl?.invoke(nextUrl)
                            },
                            onOpenWindowPopup = { popupWv ->
                                ActiveBrowserScreenCallbacks.onOpenWindowPopup?.invoke(popupWv)
                            },
                            onAttachSubPopup = { childWv ->
                                ActiveBrowserScreenCallbacks.onAttachSubPopup?.invoke(childWv)
                            },
                            onCloseWindowPopup = { closingWv ->
                                ActiveBrowserScreenCallbacks.onCloseWindowPopup?.invoke(closingWv)
                            },
                            onInspectorElementPicked = { selector ->
                                ActiveBrowserScreenCallbacks.onInspectorElementPicked?.invoke(selector)
                            },
                            onPasskeyFallbackRequested = { loginUrl, rpId ->
                                ActiveBrowserScreenCallbacks.onPasskeyFallbackRequested?.invoke(loginUrl, rpId)
                            },
                            onSecurityThreatBlocked = { threatUrl, verdict ->
                                ActiveBrowserScreenCallbacks.onSecurityThreatBlocked?.invoke(threatUrl, verdict)
                            },
                            onOriginPermissionRequested = { permReq ->
                                ActiveBrowserScreenCallbacks.onOriginPermissionRequested?.invoke(permReq)
                            },
                            onMediaPlaybackChanged = { tabId, isVideoPlaying, isAudioPlaying, title, pageUrl, vw, vh, lFrac, tFrac, rFrac, bFrac ->
                                BrowserPipController.updateMediaPlaybackState(
                                    tabId = tabId,
                                    isVideoPlaying = isVideoPlaying,
                                    isAudioPlaying = isAudioPlaying,
                                    mediaTitle = title,
                                    mediaUrl = pageUrl,
                                    videoWidth = vw,
                                    videoHeight = vh,
                                    leftFrac = lFrac,
                                    topFrac = tFrac,
                                    rightFrac = rFrac,
                                    bottomFrac = bFrac
                                )
                            },
                            onWebPipRequested = { tabId, isFloating, vw, vh, lFrac, tFrac, rFrac, bFrac ->
                                ActiveBrowserScreenCallbacks.onWebPipRequested?.invoke(
                                    tabId, isFloating, vw, vh, lFrac, tFrac, rFrac, bFrac
                                )
                            },
                            onSystemPipPopOutRequested = { tabId, vw, vh, lFrac, tFrac, rFrac, bFrac ->
                                ActiveBrowserScreenCallbacks.onSystemPipPopOutRequested?.invoke(
                                    tabId, vw, vh, lFrac, tFrac, rFrac, bFrac
                                )
                            }
                        ).also { created ->
                            webViews[targetId] = created
                            created.loadUrl(activeTab.url)
                        }

                        // Sync Desktop UA, Client Hints, LayoutAlgorithm & viewport ONLY when Desktop Mode actually toggles
                        // (Never call setInitialScale/WebSettings on routine recompositions, which disrupts pinch-zoom and stalls video)
                        val desiredUa = resolveBrowserUserAgent(context, activeTab.desktopMode)
                        val desktopModeChanged = activeWebView.settings.userAgentString != desiredUa
                        if (desktopModeChanged) {
                            DesktopViewportController.applyWebSettings(
                                webView = activeWebView,
                                userAgent = desiredUa,
                                desktopMode = activeTab.desktopMode,
                                resetInitialScale = true
                            )
                            if (activeTab.desktopMode) {
                                val upgradedDesktopUrl = DesktopViewportController.rewriteUrlForDesktopToggle(
                                    rawUrl = activeWebView.url ?: activeTab.url,
                                    enablingDesktop = true
                                )
                                if (upgradedDesktopUrl != null) {
                                    activeWebView.loadUrl(upgradedDesktopUrl)
                                } else {
                                    activeWebView.reload()
                                }
                            } else {
                                DesktopViewportController.syncWebViewViewport(
                                    webView = activeWebView,
                                    desktopMode = false,
                                    forceRefit = true
                                )
                                activeWebView.reload()
                            }
                        }

                        if (container.childCount != 1 || container.getChildAt(0) !== activeWebView) {
                            container.removeAllViews()
                            (activeWebView.parent as? ViewGroup)?.removeView(activeWebView)
                            container.addView(
                                activeWebView,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                            runCatching {
                                activeWebView.resumeTimers()
                                activeWebView.onResume()
                                activeWebView.requestLayout()
                                activeWebView.invalidate()
                            }
                        }
                    }
                )

                // Hardware-Accelerated Fullscreen Video Overlay for HTML5 / MSE / HLS / DASH Web Players
                fullscreenCustomView?.let { customVideoView ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("browser_fullscreen_video_container"),
                        color = Color.Black
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { coords ->
                                    if (activeInlineStreamPlayer == null) {
                                        BrowserPipController.updateContainerRect(
                                            coords.boundsInWindow().toAndroidRectF().toRect()
                                        )
                                    }
                                }
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    FrameLayout(ctx).apply {
                                        setBackgroundColor(android.graphics.Color.BLACK)
                                        layoutParams = ViewGroup.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    }
                                },
                                update = { fsContainer ->
                                    if (fsContainer.childCount != 1 || fsContainer.getChildAt(0) !== customVideoView) {
                                        fsContainer.removeAllViews()
                                        (customVideoView.parent as? ViewGroup)?.removeView(customVideoView)
                                        fsContainer.addView(
                                            customVideoView,
                                            FrameLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        )
                                    }
                                }
                            )
                            if (!isInPipMode) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            haptics.tap()
                                            runCatching { fullscreenCustomViewCallback?.onCustomViewHidden() }
                                            fullscreenCustomView = null
                                            fullscreenCustomViewCallback = null
                                            val targetTabId = activeTab.id
                                            mainHandler.postDelayed({
                                                webViews[targetTabId]?.evaluateJavascript(PIP_TRIGGER_FIRST_VIDEO_JS, null)
                                            }, 140L)
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                                            .testTag("browser_fullscreen_pip_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PictureInPictureAlt,
                                            contentDescription = "Float Video in Picture-in-Picture",
                                            tint = Color.White
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            haptics.tap()
                                            runCatching { fullscreenCustomViewCallback?.onCustomViewHidden() }
                                            fullscreenCustomView = null
                                            fullscreenCustomViewCallback = null
                                        },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                                            .testTag("browser_fullscreen_exit_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FullscreenExit,
                                            contentDescription = "Exit Fullscreen Video",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Matched Bookmarks & History Dropdown directly below the Address Bar
                androidx.compose.animation.AnimatedVisibility(
                    visible = omniboxSuggestions.isNotEmpty() && fullscreenCustomView == null && !isInPipMode,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (isIncognito) Color(0xFF1D1829) else MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 6.dp,
                        shadowElevation = 10.dp,
                        border = BorderStroke(1.dp, accentTint.copy(alpha = 0.30f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .testTag("browser_omnibox_suggestions_card")
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (omniboxValue.text.isBlank()) {
                                        "RECENT HISTORY & BOOKMARKS"
                                    } else {
                                        "MATCHED (${omniboxSuggestions.size})"
                                    },
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = accentTint
                                )
                                Text(
                                    text = "Tap to open",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider(
                                thickness = 0.6.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            )
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                itemsIndexed(
                                    items = omniboxSuggestions,
                                    key = { idx, item -> "${item.source.name}_${item.url}_$idx" }
                                ) { index, suggestion ->
                                    val isBookmarkMatch = suggestion.source == OmniboxSuggestionSource.BOOKMARK
                                    val badgeColor = if (isBookmarkMatch) Color(0xFFFACC15) else accentTint
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                haptics.confirm()
                                                navigateActiveTab(suggestion.url)
                                            }
                                            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
                                            .testTag("omnibox_suggestion_item_$index"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(badgeColor.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isBookmarkMatch) Icons.Default.Star else Icons.Default.History,
                                                contentDescription = suggestion.source.label,
                                                tint = badgeColor,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = suggestion.title,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (isIncognito) Color.White else MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = badgeColor.copy(alpha = 0.16f)
                                                ) {
                                                    Text(
                                                        text = suggestion.source.label,
                                                        fontSize = 8.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold,
                                                        color = badgeColor,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = suggestion.url,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                haptics.tap()
                                                selectAllOnFocus = false
                                                hasEditedOmnibox = true
                                                omniboxValue = TextFieldValue(
                                                    text = suggestion.url,
                                                    selection = TextRange(suggestion.url.length)
                                                )
                                            },
                                            modifier = Modifier
                                                .size(36.dp)
                                                .testTag("omnibox_suggestion_fill_$index")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.NorthWest,
                                                contentDescription = "Fill address",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    if (index < omniboxSuggestions.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 56.dp),
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Visual Element Picker Mode floating banner when picking an element on live page
                androidx.compose.animation.AnimatedVisibility(
                    visible = isElementPickerActive && fullscreenCustomView == null && !isInPipMode,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 14.dp, end = 14.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF04131B).copy(alpha = 0.94f),
                        border = BorderStroke(1.2.dp, NeonCyan),
                        shadowElevation = 6.dp,
                        modifier = Modifier.testTag("browser_element_picker_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AdsClick,
                                contentDescription = "Element Picker Active",
                                tint = NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Tap any page element to inspect",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            TextButton(
                                onClick = {
                                    isElementPickerActive = false
                                    webViews[activeTab.id]?.evaluateJavascript(buildElementPickerJs(false), null)
                                    isFloatingInspectorOpen = true
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("btn_cancel_element_picker")
                            ) {
                                Text("Cancel", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = NeonCyan)
                            }
                        }
                    }
                }

                // Floating Media Grabber Pill when enabled and video/audio/files are detected on page
                androidx.compose.animation.AnimatedVisibility(
                    visible = mediaGrabberEnabled && currentGrabbedMedia.isNotEmpty() && fullscreenCustomView == null && !isInPipMode,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 14.dp)
                ) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            haptics.confirm()
                            activeSheet = BrowserOverlaySheet.MEDIA_GRABBER
                        },
                        containerColor = NeonCyan,
                        contentColor = Color(0xFF04131B),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.testTag("browser_floating_grab_pill"),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.DownloadForOffline,
                                contentDescription = "Open Media Grabber"
                            )
                        },
                        text = {
                            Text(
                                text = "Media (${currentGrabbedMedia.size})",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp
                            )
                        }
                    )
                }

                // Interactive Chrome Web Store In-Page Install Banner
                val activeCwsStoreId = remember(activeTab.url) {
                    BrowserExtensionEngine.extractChromeWebStoreId(activeTab.url)
                }
                val isCwsDetailPage = remember(activeTab.url) {
                    BrowserExtensionEngine.isChromeWebStoreDetailUrl(activeTab.url)
                }
                val isCwsInstalled = remember(activeCwsStoreId, extensions) {
                    browserRepo.extensionEngine.isInstalledFromStoreId(activeCwsStoreId)
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = isCwsDetailPage && fullscreenCustomView == null && !isInPipMode,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = 14.dp,
                            end = if (currentGrabbedMedia.isNotEmpty()) 136.dp else 14.dp,
                            bottom = 14.dp
                        )
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.55f)),
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier.testTag("browser_cws_install_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Extension,
                                contentDescription = "Chrome Web Store Extension",
                                tint = NeonCyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = if (isCwsInstalled) "Extension Installed" else "Chrome Web Store",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isCwsInstalled) SuccessGreen else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = activeTab.title.replace(
                                        Regex("""\s*[-–|]\s*Chrome Web Store.*$""", RegexOption.IGNORE_CASE),
                                        ""
                                    ).ifBlank { activeCwsStoreId.orEmpty() },
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Button(
                                onClick = {
                                    haptics.confirm()
                                    installChromeWebStoreExtensionAsync(activeTab.url, activeTab.title)
                                },
                                enabled = !isInstallingCwsExtension,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isCwsInstalled) SuccessGreen else NeonCyan,
                                    contentColor = if (isCwsInstalled) Color.White else Color(0xFF04131B)
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 40.dp)
                                    .testTag("btn_cws_add_extension")
                            ) {
                                if (isInstallingCwsExtension) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF04131B)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Installing...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Text(
                                        text = if (isCwsInstalled) "Reinstall" else "Add to Browser",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }
                }

                // Freely Resizable Floating Web Page Inspector Window Overlay
                if (isFloatingInspectorOpen && fullscreenCustomView == null && !isInPipMode) {
                    BrowserInspectorDialog(
                        webView = webViews[activeTab.id],
                        pageUrl = activeTab.url,
                        pageTitle = activeTab.title,
                        dnsMode = dnsMode,
                        warpTelemetry = warpDnsTelemetry,
                        extensions = extensions,
                        consoleLogs = currentConsoleLogs,
                        networkLogs = currentNetworkLogs,
                        isInspectorEnabled = pageInspectorEnabled,
                        onToggleInspectorEnabled = { enabled ->
                            haptics.toggle(enabled)
                            browserRepo.setPageInspectorEnabled(enabled)
                            if (!enabled) {
                                isElementPickerActive = false
                                webViews[activeTab.id]?.evaluateJavascript(buildElementPickerJs(false), null)
                            } else {
                                webViews[activeTab.id]?.evaluateJavascript(INSPECTOR_ERROR_HOOK_JS, null)
                            }
                        },
                        initialSelectedSelector = inspectorSelectedSelector,
                        initialTab = inspectorInitialTab,
                        onStartVisualElementPicker = {
                            haptics.confirm()
                            isFloatingInspectorOpen = false
                            isElementPickerActive = true
                            webViews[activeTab.id]?.evaluateJavascript(buildElementPickerJs(true), null)
                        },
                        onClearConsoleLogs = {
                            haptics.warning()
                            browserRepo.clearConsoleLogs(activeTab.id)
                        },
                        onRecordEvalConsoleMessage = { level, msg ->
                            browserRepo.recordConsoleMessage(
                                tabId = activeTab.id,
                                level = level,
                                message = msg,
                                source = "REPL"
                            )
                        },
                        onClearNetworkLogs = {
                            haptics.warning()
                            browserRepo.clearNetworkLogs(activeTab.id)
                        },
                        onClearSiteCookies = {
                            haptics.warning()
                            clearAllBrowserCookies {
                                mainHandler.post {
                                    Toast.makeText(context, "Site cookies cleared", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onOpenUrlInNewTab = { targetUrl ->
                            haptics.tap()
                            openUrlInTab(targetUrl, inNewTab = true, incognito = activeTab.isIncognito)
                        },
                        onDismiss = { isFloatingInspectorOpen = false }
                    )
                }

                // Persistent Cross-Tab Floating Video Window when switching tabs without closing video tab or minimizing browser
                val crossTabId = floatingPipTabId
                val crossTabWv = if (crossTabId != null && crossTabId != activeTab.id) webViews[crossTabId] else null
                if (crossTabId != null && crossTabId != activeTab.id && crossTabWv != null && fullscreenCustomView == null && activeInlineStreamPlayer == null) {
                    if (isInPipMode) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                FrameLayout(ctx).apply {
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                            },
                            update = { container ->
                                if (container.childCount != 1 || container.getChildAt(0) !== crossTabWv) {
                                    container.removeAllViews()
                                    (crossTabWv.parent as? ViewGroup)?.removeView(crossTabWv)
                                    container.addView(
                                        crossTabWv,
                                        FrameLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.MATCH_PARENT
                                        )
                                    )
                                }
                            }
                        )
                    } else {
                        val density = LocalDensity.current
                        val minWidthPx = with(density) { 204.dp.toPx() }
                        val minHeightPx = with(density) { 178.dp.toPx() }
                        val defaultWidthPx = with(density) { 260.dp.toPx() }
                        val defaultHeightPx = with(density) { 236.dp.toPx() }
                        val maxAllowedW = (viewportMaxWidthPx - 8f).coerceAtLeast(minWidthPx)
                        val maxAllowedH = (viewportMaxHeightPx - 8f).coerceAtLeast(minHeightPx)
                        var cardWidthPx by remember(crossTabId) {
                            mutableStateOf(defaultWidthPx.coerceIn(minWidthPx, maxAllowedW))
                        }
                        var cardHeightPx by remember(crossTabId) {
                            mutableStateOf(defaultHeightPx.coerceIn(minHeightPx, maxAllowedH))
                        }
                        val effectiveW = cardWidthPx.coerceIn(minWidthPx, maxAllowedW)
                        val effectiveH = cardHeightPx.coerceIn(minHeightPx, maxAllowedH)
                        val cardWidthDp = with(density) { effectiveW.toDp() }
                        val cardHeightDp = with(density) { effectiveH.toDp() }
                        val marginPx = with(density) { 12.dp.toPx() }
                        var offsetX by remember(crossTabId) {
                            mutableStateOf((viewportMaxWidthPx - effectiveW - marginPx).coerceAtLeast(marginPx))
                        }
                        var offsetY by remember(crossTabId) {
                            mutableStateOf((viewportMaxHeightPx - effectiveH - marginPx).coerceAtLeast(marginPx))
                        }
                        val clampedX = offsetX.coerceIn(0f, (viewportMaxWidthPx - effectiveW).coerceAtLeast(0f))
                        val clampedY = offsetY.coerceIn(0f, (viewportMaxHeightPx - effectiveH).coerceAtLeast(0f))
                        val floatTabTitle = remember(tabs, crossTabId) {
                            tabs.find { it.id == crossTabId }?.title?.ifBlank { "Floating Video" } ?: "Floating Video"
                        }
                        var mediaState by remember(crossTabId) { mutableStateOf(FloatingPipMediaState()) }

                        LaunchedEffect(crossTabId, crossTabWv) {
                            while (true) {
                                crossTabWv.evaluateJavascript(PIP_QUERY_MEDIA_STATE_JS) { raw ->
                                    parseFloatingPipMediaStateJson(raw)?.let { parsed ->
                                        mediaState = parsed
                                    }
                                }
                                delay(450L)
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
                                .size(width = cardWidthDp, height = cardHeightDp)
                                .pointerInput(crossTabId, viewportMaxWidthPx, viewportMaxHeightPx) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        if (zoom != 1f) {
                                            val nextW = (cardWidthPx * zoom).coerceIn(minWidthPx, maxAllowedW)
                                            val nextH = (cardHeightPx * zoom).coerceIn(minHeightPx, maxAllowedH)
                                            cardWidthPx = nextW
                                            cardHeightPx = nextH
                                            offsetX = (offsetX + pan.x).coerceIn(0f, (viewportMaxWidthPx - nextW).coerceAtLeast(0f))
                                            offsetY = (offsetY + pan.y).coerceIn(0f, (viewportMaxHeightPx - nextH).coerceAtLeast(0f))
                                        }
                                    }
                                }
                                .testTag("browser_cross_tab_floating_pip_card"),
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF0B0F17),
                            border = BorderStroke(1.5.dp, NeonCyan.copy(alpha = 0.72f)),
                            shadowElevation = 12.dp,
                            tonalElevation = 8.dp
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(32.dp)
                                        .background(Color(0xFF121927))
                                        .pointerInput(viewportMaxWidthPx, viewportMaxHeightPx, effectiveW, effectiveH) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                offsetX = (offsetX + dragAmount.x)
                                                    .coerceIn(0f, (viewportMaxWidthPx - effectiveW).coerceAtLeast(0f))
                                                offsetY = (offsetY + dragAmount.y)
                                                    .coerceIn(0f, (viewportMaxHeightPx - effectiveH).coerceAtLeast(0f))
                                            }
                                        }
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragIndicator,
                                        contentDescription = "Drag floating video",
                                        tint = NeonCyan,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = floatTabTitle,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            haptics.tap()
                                            val isCurrentlyLarge = effectiveW > defaultWidthPx * 1.18f
                                            val nextW = if (isCurrentlyLarge) defaultWidthPx else (viewportMaxWidthPx * 0.88f)
                                            val nextH = if (isCurrentlyLarge) defaultHeightPx else (viewportMaxHeightPx * 0.56f)
                                            cardWidthPx = nextW.coerceIn(minWidthPx, maxAllowedW)
                                            cardHeightPx = nextH.coerceIn(minHeightPx, maxAllowedH)
                                            offsetX = offsetX.coerceIn(0f, (viewportMaxWidthPx - cardWidthPx).coerceAtLeast(0f))
                                            offsetY = offsetY.coerceIn(0f, (viewportMaxHeightPx - cardHeightPx).coerceAtLeast(0f))
                                        },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .testTag("btn_cross_tab_pip_resize")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AspectRatio,
                                            contentDescription = "Toggle floating video size",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            haptics.tap()
                                            browserRepo.selectTab(crossTabId)
                                        },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .testTag("btn_cross_tab_pip_return")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = "Return to video tab",
                                            tint = NeonCyan,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            haptics.tap()
                                            crossTabWv.evaluateJavascript(PIP_CLOSE_ALL_JS, null)
                                            floatingPipTabId = null
                                            BrowserPipController.updateFloatingPipTab(null)
                                        },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .testTag("btn_cross_tab_pip_close")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close floating video",
                                            tint = Color.White,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .background(Color.Black)
                                ) {
                                    AndroidView(
                                        modifier = Modifier.fillMaxSize(),
                                        factory = { ctx ->
                                            FrameLayout(ctx).apply {
                                                setBackgroundColor(android.graphics.Color.BLACK)
                                                layoutParams = ViewGroup.LayoutParams(
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.MATCH_PARENT
                                                )
                                            }
                                        },
                                        update = { container ->
                                            if (container.childCount != 1 || container.getChildAt(0) !== crossTabWv) {
                                                container.removeAllViews()
                                                (crossTabWv.parent as? ViewGroup)?.removeView(crossTabWv)
                                                container.addView(
                                                    crossTabWv,
                                                    FrameLayout.LayoutParams(
                                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                                        ViewGroup.LayoutParams.MATCH_PARENT
                                                    )
                                                )
                                                runCatching {
                                                    crossTabWv.resumeTimers()
                                                    crossTabWv.onResume()
                                                    crossTabWv.requestLayout()
                                                    crossTabWv.invalidate()
                                                }
                                            }
                                        }
                                    )
                                }
                                FloatingPipControlsFooter(
                                    mediaState = mediaState,
                                    tagPrefix = "btn_cross_tab_pip",
                                    onSeekFraction = { frac ->
                                        val target = (mediaState.durationSec * frac).coerceAtLeast(0f)
                                        mediaState = mediaState.copy(currentTimeSec = target)
                                        crossTabWv.evaluateJavascript(buildPipSeekToJs(target), null)
                                    },
                                    onRewind10 = {
                                        haptics.tap()
                                        crossTabWv.evaluateJavascript(buildPipSeekRelativeJs(-10), null)
                                    },
                                    onTogglePlayPause = {
                                        haptics.tap()
                                        mediaState = mediaState.copy(isPlaying = !mediaState.isPlaying)
                                        crossTabWv.evaluateJavascript(PIP_TOGGLE_ACTIVE_VIDEO_JS, null)
                                    },
                                    onForward10 = {
                                        haptics.tap()
                                        crossTabWv.evaluateJavascript(buildPipSeekRelativeJs(10), null)
                                    },
                                    onToggleMute = {
                                        haptics.tap()
                                        mediaState = mediaState.copy(isMuted = !mediaState.isMuted)
                                        crossTabWv.evaluateJavascript(PIP_TOGGLE_MUTE_JS, null)
                                    },
                                    onCycleSpeed = {
                                        haptics.tap()
                                        val nextRate = nextPipPlaybackRate(mediaState.playbackRate)
                                        mediaState = mediaState.copy(playbackRate = nextRate)
                                        crossTabWv.evaluateJavascript(buildPipSetSpeedJs(nextRate), null)
                                    },
                                    onBottomLeftResizeDrag = { dx, dy ->
                                        val desiredW = (cardWidthPx - dx).coerceIn(minWidthPx, maxAllowedW)
                                        val actualDeltaW = desiredW - cardWidthPx
                                        val nextX = (offsetX - actualDeltaW).coerceIn(0f, (viewportMaxWidthPx - desiredW).coerceAtLeast(0f))
                                        cardWidthPx = desiredW
                                        offsetX = nextX
                                        cardHeightPx = (cardHeightPx + dy).coerceIn(minHeightPx, maxAllowedH)
                                        offsetY = offsetY.coerceIn(0f, (viewportMaxHeightPx - cardHeightPx).coerceAtLeast(0f))
                                    },
                                    onBottomRightResizeDrag = { dx, dy ->
                                        val nextW = (cardWidthPx + dx).coerceIn(minWidthPx, maxAllowedW)
                                        val nextH = (cardHeightPx + dy).coerceIn(minHeightPx, maxAllowedH)
                                        cardWidthPx = nextW
                                        cardHeightPx = nextH
                                        offsetX = offsetX.coerceIn(0f, (viewportMaxWidthPx - nextW).coerceAtLeast(0f))
                                        offsetY = offsetY.coerceIn(0f, (viewportMaxHeightPx - nextH).coerceAtLeast(0f))
                                    }
                                )
                            }
                        }
                    }
                }

            }

            // 3. Ergonomic Bottom Browser Toolbar with New Tab & 3-Dot Menu (hidden in PiP mode)
            if (!isInPipMode) {
            Surface(
                color = chromeBgColor,
                tonalElevation = 4.dp,
                shadowElevation = 6.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        thickness = 0.8.dp
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .padding(horizontal = 4.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Side (3 equal slots): Back, Right Arrow (Forward), Refresh
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Back
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(
                                    onClick = {
                                        haptics.tap()
                                        webViews[activeTab.id]?.goBack()
                                    },
                                    enabled = activeTab.canGoBack,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("browser_back_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = if (activeTab.canGoBack) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                    )
                                }
                            }

                            // Forward (Right Arrow)
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(
                                    onClick = {
                                        haptics.tap()
                                        webViews[activeTab.id]?.goForward()
                                    },
                                    enabled = activeTab.canGoForward,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("browser_forward_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Forward",
                                        tint = if (activeTab.canGoForward) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                                    )
                                }
                            }

                            // Bottom Refresh / Stop Button (between right arrow and Home)
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(
                                    onClick = {
                                        haptics.tap()
                                        val wv = webViews[activeTab.id]
                                        if (activeTab.isLoading) {
                                            wv?.stopLoading()
                                        } else {
                                            if (wv != null && (wv.url.isNullOrBlank() || wv.url == "about:blank")) {
                                                wv.loadUrl(activeTab.url)
                                            } else {
                                                wv?.reload()
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("browser_bottom_reload_button")
                                ) {
                                    Icon(
                                        imageVector = if (activeTab.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                        contentDescription = if (activeTab.isLoading) "Stop Loading" else "Refresh Page",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        // Center: Home (google.com) — anchored at exact horizontal center
                        IconButton(
                            onClick = {
                                haptics.tap()
                                navigateActiveTab(BrowserStateRepository.DEFAULT_HOME_URL)
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("browser_home_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = "Home (Google)",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Right Side (3 equal slots): New Tab (+), Tabs ([N]), 3-Dot Overflow Menu
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // New Tab (+)
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(
                                    onClick = {
                                        haptics.confirm()
                                        browserRepo.openNewTab(
                                            initialUrl = BrowserStateRepository.DEFAULT_HOME_URL,
                                            isIncognito = false
                                        )
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("browser_new_tab_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AddCircleOutline,
                                        contentDescription = "New Tab",
                                        tint = accentTint
                                    )
                                }
                            }

                            // Tab Switcher Counter Box
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            haptics.tap()
                                            activeSheet = BrowserOverlaySheet.TABS
                                        }
                                        .testTag("browser_tabs_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .border(
                                                width = 1.8.dp,
                                                color = if (isIncognito) Color(0xFFD8B4FE) else MaterialTheme.colorScheme.onSurface,
                                                shape = RoundedCornerShape(6.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = tabs.size.coerceAtMost(99).toString(),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isIncognito) Color(0xFFD8B4FE) else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }

                            // 3-Dot Overflow Menu
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(
                                    onClick = {
                                        haptics.tap()
                                        showMoreSubmenu = false
                                        showOverflowMenu = true
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("browser_menu_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Browser Menu",
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                onDismissRequest = {
                                    showOverflowMenu = false
                                    showMoreSubmenu = false
                                },
                                modifier = Modifier
                                    .widthIn(min = 258.dp)
                                    .testTag("browser_overflow_menu")
                            ) {
                                if (showMoreSubmenu) {
                                    // More Submenu Header (Back to Main Menu)
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "More Tools",
                                                fontWeight = FontWeight.Black,
                                                color = NeonCyan
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Back to Main Menu",
                                                tint = NeonCyan
                                            )
                                        },
                                        onClick = {
                                            haptics.tap()
                                            showMoreSubmenu = false
                                        },
                                        modifier = Modifier.testTag("menu_more_back")
                                    )

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                    // Security & Malware Shield
                                    val shieldArmed = securityShieldState.malwareShieldEnabled
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("Malware & Security Shield", fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    text = if (shieldArmed) {
                                                        "Protected • ${securityShieldState.blockedThreatsCount} threat(s) blocked"
                                                    } else {
                                                        "Paused • Tap to enable anti-malware guards"
                                                    },
                                                    fontSize = 10.sp,
                                                    color = if (shieldArmed) SuccessGreen else WarningOrange
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (shieldArmed) Icons.Default.VerifiedUser else Icons.Default.Security,
                                                contentDescription = "Malware & Security Shield",
                                                tint = if (shieldArmed) SuccessGreen else WarningOrange
                                            )
                                        },
                                        trailingIcon = {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (shieldArmed) SuccessGreen.copy(alpha = 0.18f) else WarningOrange.copy(alpha = 0.18f)
                                            ) {
                                                Text(
                                                    text = if (shieldArmed) "ON" else "OFF",
                                                    fontSize = 9.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (shieldArmed) SuccessGreen else WarningOrange,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        },
                                        onClick = {
                                            haptics.tap()
                                            showOverflowMenu = false
                                            showMoreSubmenu = false
                                            activeSheet = BrowserOverlaySheet.SECURITY_SHIELD
                                        },
                                        modifier = Modifier.testTag("menu_security_shield")
                                    )

                                    // Floating Page Inspection Mode (with On/Off Switch)
                                    val inspectorErrCount = currentConsoleLogs.count { it.level == "ERROR" }
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("Page Inspector", fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    text = when {
                                                        !pageInspectorEnabled -> "Off • Inspection mode paused"
                                                        inspectorErrCount > 0 -> "On • $inspectorErrCount console error(s)"
                                                        else -> "On • Floating DOM, console & network"
                                                    },
                                                    fontSize = 10.sp,
                                                    color = if (pageInspectorEnabled && inspectorErrCount > 0) DangerRed
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Code,
                                                contentDescription = "Page Inspector",
                                                tint = if (pageInspectorEnabled) NeonCyan
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        trailingIcon = {
                                            Switch(
                                                checked = pageInspectorEnabled,
                                                onCheckedChange = { enabled ->
                                                    haptics.toggle(enabled)
                                                    browserRepo.setPageInspectorEnabled(enabled)
                                                    if (!enabled) {
                                                        isFloatingInspectorOpen = false
                                                        isElementPickerActive = false
                                                        webViews[activeTab.id]?.evaluateJavascript(buildElementPickerJs(false), null)
                                                    } else {
                                                        webViews[activeTab.id]?.evaluateJavascript(INSPECTOR_ERROR_HOOK_JS, null)
                                                    }
                                                },
                                                modifier = Modifier
                                                    .scale(0.78f)
                                                    .height(28.dp)
                                                    .testTag("switch_page_inspector")
                                            )
                                        },
                                        onClick = {
                                            haptics.tap()
                                            showOverflowMenu = false
                                            showMoreSubmenu = false
                                            if (!pageInspectorEnabled) {
                                                browserRepo.setPageInspectorEnabled(true)
                                                webViews[activeTab.id]?.evaluateJavascript(INSPECTOR_ERROR_HOOK_JS, null)
                                            }
                                            inspectorInitialTab = 0
                                            isFloatingInspectorOpen = true
                                        },
                                        modifier = Modifier.testTag("menu_page_inspector")
                                    )

                                    // Proxy (HTTP, HTTPS, SOCKS5, SOCKS4) directly below Page Inspector
                                    val isProxyOn = proxyEnabled && activeProxyProfile != null
                                    val proxyMenuColor = when {
                                        !isProxyOn -> MaterialTheme.colorScheme.onSurfaceVariant
                                        activeProxyProfile?.type == BrowserProxyType.SOCKS5 -> NeonCyan
                                        activeProxyProfile?.type == BrowserProxyType.HTTPS -> SuccessGreen
                                        else -> WarningOrange
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("Proxy", fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    text = if (isProxyOn && activeProxyProfile != null) {
                                                        "${activeProxyProfile.type.label} • ${activeProxyProfile.toDisplayEndpoint()}"
                                                    } else {
                                                        "Configure HTTP & SOCKS5 proxies"
                                                    },
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.VpnLock,
                                                contentDescription = "Proxy Settings",
                                                tint = if (isProxyOn) proxyMenuColor else NeonCyan
                                            )
                                        },
                                        trailingIcon = {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isProxyOn) proxyMenuColor.copy(alpha = 0.20f)
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Text(
                                                    text = if (isProxyOn && activeProxyProfile != null) activeProxyProfile.type.label else "OFF",
                                                    fontSize = 9.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = proxyMenuColor,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        },
                                        onClick = {
                                            haptics.tap()
                                            showOverflowMenu = false
                                            showMoreSubmenu = false
                                            activeSheet = BrowserOverlaySheet.PROXY
                                        },
                                        modifier = Modifier.testTag("menu_browser_proxy")
                                    )

                                    // Clear Cookies
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("Clear Cookies", fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    text = "Remove browser site cookies & sessions",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Cookie,
                                                contentDescription = "Clear Cookies",
                                                tint = WarningOrange
                                            )
                                        },
                                        onClick = {
                                            haptics.confirm()
                                            showOverflowMenu = false
                                            showMoreSubmenu = false
                                            clearAllBrowserCookies()
                                            browserRepo.googleSyncManager.onBrowserGoogleCookiesCleared()
                                            Toast.makeText(context, "Browser cookies cleared", Toast.LENGTH_SHORT).show()
                                            repository.showActionFeedback("Browser cookies cleared")
                                        },
                                        modifier = Modifier.testTag("menu_clear_cookies")
                                    )

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                    // Google Account Auto-Sync (Bookmarks & Passwords)
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("Google Account Sync", fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    text = if (googleSyncState.isBrowserGoogleConnected) {
                                                        "${googleSyncState.effectiveAccountEmail ?: "Browser Google Session"} • Auto-Sync ${if (googleSyncState.autoSyncEnabled) "ON" else "OFF"}"
                                                    } else {
                                                        "Auto-sync passwords & passkeys with Google"
                                                    },
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.CloudSync,
                                                contentDescription = "Google Account Sync",
                                                tint = if (googleSyncState.isBrowserGoogleConnected && googleSyncState.autoSyncEnabled) SuccessGreen else NeonCyan
                                            )
                                        },
                                        trailingIcon = {
                                            Switch(
                                                checked = googleSyncState.autoSyncEnabled,
                                                onCheckedChange = { enabled ->
                                                    haptics.toggle(enabled)
                                                    browserRepo.googleSyncManager.setAutoSyncEnabled(enabled)
                                                },
                                                modifier = Modifier
                                                    .scale(0.78f)
                                                    .height(28.dp)
                                                    .testTag("switch_more_google_sync")
                                            )
                                        },
                                        onClick = {
                                            haptics.tap()
                                            showOverflowMenu = false
                                            showMoreSubmenu = false
                                            activeSheet = BrowserOverlaySheet.GOOGLE_SYNC
                                        },
                                        modifier = Modifier.testTag("menu_more_google_sync")
                                    )

                                    // Synced Passwords & Import from Storage
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("Import Passwords", fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    text = if (googleSyncState.isBrowserGoogleConnected && googleSyncState.autoSyncPasswords) {
                                                        "Synced (${savedPasswords.size}) • Import CSV / JSON"
                                                    } else {
                                                        "CSV / JSON & Synced (${savedPasswords.size})"
                                                    },
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Password,
                                                contentDescription = "Import Passwords",
                                                tint = SuccessGreen
                                            )
                                        },
                                        trailingIcon = {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (savedPasswords.isNotEmpty()) SuccessGreen.copy(alpha = 0.18f)
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            ) {
                                                Text(
                                                    text = if (savedPasswords.isNotEmpty()) "View (${savedPasswords.size})" else "Open",
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (savedPasswords.isNotEmpty()) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        },
                                        onClick = {
                                            haptics.tap()
                                            showOverflowMenu = false
                                            showMoreSubmenu = false
                                            browserRepo.googleSyncManager.inspectActiveWebViewGoogleCookiesAsync(
                                                webViews[activeTab.id]?.settings?.userAgentString
                                            )
                                            browserRepo.syncAccountVaultNow()
                                            if (googleSyncState.isBrowserGoogleConnected && googleSyncState.autoSyncEnabled) {
                                                browserRepo.googleSyncManager.triggerManualSyncNow()
                                            }
                                            activeSheet = BrowserOverlaySheet.PASSWORDS
                                        },
                                        modifier = Modifier.testTag("menu_import_passwords")
                                    )

                                    // Import Bookmarks from Other Browsers
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text("Import Bookmarks", fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    text = "Chrome, Firefox, Edge (.html / .json)",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.BookmarkAdd,
                                                contentDescription = "Import Bookmarks",
                                                tint = Color(0xFFFACC15)
                                            )
                                        },
                                        onClick = {
                                            haptics.tap()
                                            showOverflowMenu = false
                                            showMoreSubmenu = false
                                            bookmarkFilePickerLauncher.launch(arrayOf("*/*"))
                                        },
                                        modifier = Modifier.testTag("menu_import_bookmarks")
                                    )
                                } else {
                                // New Tab
                                DropdownMenuItem(
                                    text = { Text("New Tab", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Icon(Icons.Default.AddBox, contentDescription = null, tint = accentTint)
                                    },
                                    trailingIcon = {
                                        Text("google.com", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    },
                                    onClick = {
                                        haptics.confirm()
                                        showOverflowMenu = false
                                        browserRepo.openNewTab(BrowserStateRepository.DEFAULT_HOME_URL, isIncognito = false)
                                    },
                                    modifier = Modifier.testTag("menu_new_tab")
                                )

                                // Incognito Mode
                                DropdownMenuItem(
                                    text = { Text("Incognito Mode", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.VisibilityOff,
                                            contentDescription = null,
                                            tint = if (isIncognito) Color(0xFFC084FC) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    trailingIcon = {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isIncognito) Color(0xFF7E22CE).copy(alpha = 0.25f)
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = if (isIncognito) "ACTIVE" else "OPEN",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isIncognito) Color(0xFFD8B4FE) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        haptics.heavyAction()
                                        showOverflowMenu = false
                                        browserRepo.openNewTab(BrowserStateRepository.DEFAULT_HOME_URL, isIncognito = true)
                                    },
                                    modifier = Modifier.testTag("menu_incognito_mode")
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // Browsing History
                                DropdownMenuItem(
                                    text = { Text("Browsing History", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Icon(Icons.Default.History, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        Text("${history.size}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    },
                                    onClick = {
                                        haptics.tap()
                                        showOverflowMenu = false
                                        activeSheet = BrowserOverlaySheet.HISTORY
                                    },
                                    modifier = Modifier.testTag("menu_browsing_history")
                                )

                                // Saved Bookmarks
                                DropdownMenuItem(
                                    text = { Text("Saved Bookmarks", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Bookmarks, contentDescription = null, tint = Color(0xFFFACC15))
                                    },
                                    trailingIcon = {
                                        Text("${bookmarks.size}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    },
                                    onClick = {
                                        haptics.tap()
                                        showOverflowMenu = false
                                        activeSheet = BrowserOverlaySheet.BOOKMARKS
                                    },
                                    modifier = Modifier.testTag("menu_saved_bookmarks")
                                )

                                 // Dedicated DNS Option
                                DropdownMenuItem(
                                    text = { Text("DNS", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Dns,
                                            contentDescription = "DNS Settings",
                                            tint = NeonCyan
                                        )
                                    },
                                    onClick = {
                                        haptics.tap()
                                        showOverflowMenu = false
                                        activeSheet = BrowserOverlaySheet.DNS
                                    },
                                    modifier = Modifier.testTag("menu_browser_dns")
                                )

                                // Extensions
                                DropdownMenuItem(
                                    text = { Text("Extensions", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Extension,
                                            contentDescription = "Extensions",
                                            tint = NeonCyan
                                        )
                                    },
                                    onClick = {
                                        haptics.tap()
                                        showOverflowMenu = false
                                        activeSheet = BrowserOverlaySheet.EXTENSIONS
                                    },
                                    modifier = Modifier.testTag("menu_browser_extensions")
                                )

                                // Media Grabber
                                DropdownMenuItem(
                                    text = { Text("Media Grabber", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.VideoLibrary,
                                            contentDescription = "Media Grabber",
                                            tint = NeonCyan
                                        )
                                    },
                                    onClick = {
                                        haptics.tap()
                                        showOverflowMenu = false
                                        if (mediaGrabberEnabled) {
                                            webViews[activeTab.id]?.evaluateJavascript(DOM_MEDIA_SCANNER_JS, null)
                                        }
                                        activeSheet = BrowserOverlaySheet.MEDIA_GRABBER
                                    },
                                    modifier = Modifier.testTag("menu_media_grabber")
                                )

                                // More Options
                                DropdownMenuItem(
                                    text = { Text("More", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.MoreHoriz,
                                            contentDescription = "More Options",
                                            tint = NeonCyan
                                        )
                                    },
                                    onClick = {
                                        haptics.tap()
                                        showMoreSubmenu = true
                                    },
                                    modifier = Modifier.testTag("menu_browser_more")
                                )

                                // Picture-in-Picture
                                DropdownMenuItem(
                                    text = { Text("Picture-in-Picture", fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.PictureInPictureAlt,
                                            contentDescription = "Picture-in-Picture",
                                            tint = NeonCyan
                                        )
                                    },
                                    onClick = {
                                        haptics.tap()
                                        showOverflowMenu = false
                                        webViews[activeTab.id]?.evaluateJavascript(PIP_TRIGGER_FIRST_VIDEO_JS) { result ->
                                            if (result == "false" || result == "null") {
                                                Toast.makeText(
                                                    context,
                                                    "Start playing a video to open Picture-in-Picture",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("menu_picture_in_picture")
                                )

                                // Desktop Site Toggle
                                DropdownMenuItem(
                                    text = { Text("Desktop Site", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(Icons.Default.DesktopWindows, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        Checkbox(
                                            checked = activeTab.desktopMode,
                                            onCheckedChange = null
                                        )
                                    },
                                    onClick = {
                                        haptics.toggle(!activeTab.desktopMode)
                                        showOverflowMenu = false
                                        browserRepo.updateTabState(activeTab.id) {
                                            it.copy(desktopMode = !it.desktopMode)
                                        }
                                    }
                                )

                                // Shortcut to Downloads Tab
                                DropdownMenuItem(
                                    text = { Text("Downloads (Download/DDM)", fontWeight = FontWeight.Medium) },
                                    leadingIcon = {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, tint = SuccessGreen)
                                    },
                                    onClick = {
                                        haptics.tap()
                                        showOverflowMenu = false
                                        onOpenDownloadsTab()
                                    }
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // Exit Browser Option (closes all open tabs automatically on exit)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Exit Browser",
                                            fontWeight = FontWeight.Bold,
                                            color = DangerRed
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                            contentDescription = "Exit Browser",
                                            tint = DangerRed
                                        )
                                    },
                                    onClick = {
                                        haptics.warning()
                                        showOverflowMenu = false
                                        val hadIncognito = tabs.any { it.isIncognito }
                                        webViews.values.forEach { wv ->
                                            runCatching {
                                                wv.stopLoading()
                                                (wv.parent as? ViewGroup)?.removeView(wv)
                                                if (hadIncognito) {
                                                    wv.clearCache(true)
                                                    wv.clearHistory()
                                                }
                                                wv.destroy()
                                            }
                                        }
                                        webViews.clear()
                                        if (hadIncognito) {
                                            runCatching { CookieManager.getInstance().removeSessionCookies(null) }
                                        }
                                        browserRepo.closeAllTabs()
                                        onCloseBrowser()
                                    },
                                    modifier = Modifier.testTag("menu_exit_browser")
                                )
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

    // Modal Sheets for Tabs / History / Bookmarks / Media Grabber
    when (activeSheet) {
        BrowserOverlaySheet.TABS -> {
            BrowserTabsDialog(
                tabs = tabs,
                activeTabId = activeTab.id,
                onSelectTab = { id ->
                    haptics.tabSelect()
                    browserRepo.selectTab(id)
                    activeSheet = BrowserOverlaySheet.NONE
                },
                onCloseTab = { id ->
                    haptics.warning()
                    closeBrowserTab(id)
                },
                onNewNormalTab = {
                    haptics.confirm()
                    browserRepo.openNewTab(BrowserStateRepository.DEFAULT_HOME_URL, isIncognito = false)
                    activeSheet = BrowserOverlaySheet.NONE
                },
                onNewIncognitoTab = {
                    haptics.heavyAction()
                    browserRepo.openNewTab(BrowserStateRepository.DEFAULT_HOME_URL, isIncognito = true)
                    activeSheet = BrowserOverlaySheet.NONE
                },
                onCloseAllTabs = {
                    haptics.warning()
                    tabs.map { it.id }.forEach { id ->
                        webViews.remove(id)?.destroy()
                    }
                    browserRepo.closeAllTabs()
                    activeSheet = BrowserOverlaySheet.NONE
                },
                onDismiss = { activeSheet = BrowserOverlaySheet.NONE }
            )
        }

        BrowserOverlaySheet.HISTORY -> {
            BrowserHistoryDialog(
                history = history,
                onOpenUrl = { url ->
                    haptics.tap()
                    openUrlInTab(url, inNewTab = false)
                },
                onDeleteItem = { id ->
                    haptics.warning()
                    browserRepo.deleteHistoryEntry(id)
                },
                onClearAll = {
                    haptics.warning()
                    browserRepo.clearHistory()
                },
                onDismiss = { activeSheet = BrowserOverlaySheet.NONE }
            )
        }

        BrowserOverlaySheet.BOOKMARKS -> {
            BrowserBookmarksDialog(
                bookmarks = bookmarks,
                currentUrl = activeTab.url,
                currentTitle = activeTab.title,
                isCurrentBookmarked = isBookmarked,
                onAddManualBookmark = { url, title ->
                    haptics.confirm()
                    val saved = browserRepo.addOrUpdateBookmark(url, title)
                    if (saved != null) {
                        Toast.makeText(context, "Bookmark saved: ${saved.title}", Toast.LENGTH_SHORT).show()
                        repository.showActionFeedback("Bookmark saved: ${saved.title}")
                    }
                },
                onToggleCurrentBookmark = {
                    val added = browserRepo.toggleBookmark(activeTab.url, activeTab.title)
                    haptics.toggle(added)
                },
                onImportBookmarksClick = {
                    haptics.tap()
                    bookmarkFilePickerLauncher.launch(arrayOf("*/*"))
                },
                onOpenUrl = { url ->
                    haptics.tap()
                    openUrlInTab(url, inNewTab = false)
                },
                onDeleteBookmark = { id ->
                    haptics.warning()
                    browserRepo.removeBookmark(id)
                },
                onDismiss = { activeSheet = BrowserOverlaySheet.NONE }
            )
        }

        BrowserOverlaySheet.DNS -> {
            BrowserDnsSettingsDialog(
                currentMode = dnsMode,
                customDnsList = customDnsList,
                activeCustomDns = activeCustomDns,
                telemetry = warpDnsTelemetry,
                onSelectDnsMode = { mode, customEntry ->
                    haptics.confirm()
                    downloadManager.setDnsMode(mode, customEntry)
                    browserRepo.extensionEngine.setHttpDns(
                        if (mode != DnsProviderMode.SYSTEM_DEFAULT) downloadManager.warpDns else okhttp3.Dns.SYSTEM
                    )
                    val curHost = runCatching { Uri.parse(activeTab.url).host }.getOrNull()
                    if (mode != DnsProviderMode.SYSTEM_DEFAULT && !curHost.isNullOrBlank()) {
                        downloadManager.warpDns.prewarmHostAsync(curHost)
                    }
                    BrowserProxyManager.configureWebViewNetworkRouting(
                        context = context,
                        proxyEnabled = browserRepo.proxyEnabledFlow.value,
                        proxyProfile = browserRepo.getActiveProxyProfile(),
                        warpDns = downloadManager.warpDns
                    ) { _, msg ->
                        repository.showActionFeedback(msg)
                        webViews[activeTab.id]?.let { wv ->
                            wv.clearCache(true)
                            wv.reload()
                        }
                    }
                },
                onAddCustomDns = { name, primary, secondary ->
                    haptics.confirm()
                    downloadManager.addCustomDnsServer(
                        name = name,
                        primaryServer = primary,
                        secondaryServer = secondary,
                        activateImmediately = true
                    )
                    browserRepo.extensionEngine.setHttpDns(
                        if (downloadManager.warpDns.isAnyCustomDnsActive) downloadManager.warpDns else okhttp3.Dns.SYSTEM
                    )
                    BrowserProxyManager.configureWebViewNetworkRouting(
                        context = context,
                        proxyEnabled = browserRepo.proxyEnabledFlow.value,
                        proxyProfile = browserRepo.getActiveProxyProfile(),
                        warpDns = downloadManager.warpDns
                    ) { _, msg ->
                        repository.showActionFeedback(msg)
                        webViews[activeTab.id]?.let { wv ->
                            wv.clearCache(true)
                            wv.reload()
                        }
                    }
                },
                onDeleteCustomDns = { id ->
                    haptics.warning()
                    downloadManager.removeCustomDnsServer(id)
                    BrowserProxyManager.configureWebViewNetworkRouting(
                        context = context,
                        proxyEnabled = browserRepo.proxyEnabledFlow.value,
                        proxyProfile = browserRepo.getActiveProxyProfile(),
                        warpDns = downloadManager.warpDns
                    )
                },
                onDismiss = { activeSheet = BrowserOverlaySheet.NONE }
            )
        }

        BrowserOverlaySheet.EXTENSIONS -> {
            BrowserExtensionsDialog(
                extensions = extensions,
                activePageUrl = activeTab.url,
                isInstallingFromStore = isInstallingCwsExtension,
                isImportingLocalExtension = isImportingLocalExtension,
                importingStatusLabel = localImportStatusLabel,
                storeInstallError = cwsInstallError,
                onOpenChromeWebStoreClick = {
                    haptics.confirm()
                    cwsInstallError = null
                    activeSheet = BrowserOverlaySheet.NONE
                    openUrlInTab(BrowserExtensionEngine.CHROME_WEB_STORE_HOME_URL, inNewTab = false)
                },
                onInstallFromStoreInput = { input ->
                    haptics.confirm()
                    installChromeWebStoreExtensionAsync(input, titleHint = null)
                },
                onImportFromStorageClick = {
                    haptics.tap()
                    cwsInstallError = null
                    extensionFilePickerLauncher.launch(arrayOf("*/*"))
                },
                onImportUnpackedFolderClick = {
                    haptics.tap()
                    cwsInstallError = null
                    extensionFolderPickerLauncher.launch(null)
                },
                onToggleExtension = { ext, enabled ->
                    haptics.toggle(enabled)
                    browserRepo.extensionEngine.setExtensionEnabled(ext.id, enabled)
                    webViews[activeTab.id]?.reload()
                },
                onRemoveExtension = { ext ->
                    haptics.warning()
                    browserRepo.extensionEngine.removeExtension(ext.id)
                    webViews[activeTab.id]?.reload()
                },
                onOpenExtensionPopup = { ext ->
                    haptics.tap()
                    activeSheet = BrowserOverlaySheet.NONE
                    activeExtensionPopup = ext to 0
                },
                onOpenExtensionSettings = { ext ->
                    haptics.tap()
                    activeSheet = BrowserOverlaySheet.NONE
                    if (ext.optionsOpenInTab && !ext.optionsPath.isNullOrBlank()) {
                        val settingsUrl = browserRepo.extensionEngine.buildExtensionVirtualUrl(ext, ext.optionsPath)
                        openUrlInTab(settingsUrl, inNewTab = true, incognito = activeTab.isIncognito)
                    } else {
                        activeExtensionPopup = ext to 1
                    }
                },
                onOpenExtensionInfo = { ext ->
                    haptics.tap()
                    activeSheet = BrowserOverlaySheet.NONE
                    activeExtensionPopup = ext to 3
                },
                onCreateCustomExtension = { name, desc, matchPattern, jsCode, cssCode ->
                    haptics.confirm()
                    val created = browserRepo.extensionEngine.createCustomExtension(
                        name = name,
                        description = desc,
                        matchPattern = matchPattern,
                        jsCode = jsCode,
                        cssCode = cssCode
                    )
                    repository.showActionFeedback("Extension enabled: ${created.name}")
                    webViews[activeTab.id]?.reload()
                },
                onDismiss = {
                    cwsInstallError = null
                    activeSheet = BrowserOverlaySheet.NONE
                }
            )
        }

        BrowserOverlaySheet.MEDIA_GRABBER -> {
            BrowserMediaGrabberDialog(
                pageTitle = activeTab.title,
                pageUrl = activeTab.url,
                candidates = currentGrabbedMedia,
                isGrabberEnabled = mediaGrabberEnabled,
                onToggleGrabberEnabled = { enabled ->
                    haptics.toggle(enabled)
                    browserRepo.setMediaGrabberEnabled(enabled)
                    if (enabled) {
                        webViews[activeTab.id]?.evaluateJavascript(DOM_MEDIA_SCANNER_JS, null)
                    }
                },
                downloadManager = downloadManager,
                onRefreshScan = {
                    haptics.tap()
                    if (mediaGrabberEnabled) {
                        webViews[activeTab.id]?.evaluateJavascript(DOM_MEDIA_SCANNER_JS, null)
                    }
                },
                onSelectCandidateStream = { candidate ->
                    haptics.confirm()
                    activeSheet = BrowserOverlaySheet.NONE
                    activeInlineStreamPlayer = candidate.url to candidate.title.ifBlank { activeTab.title }
                },
                onSelectCandidateDownload = { candidate ->
                    haptics.confirm()
                    activeSheet = BrowserOverlaySheet.NONE
                    triggerGrabPopup(candidate)
                },
                onSelectExtractedStream = { info, format ->
                    haptics.confirm()
                    activeSheet = BrowserOverlaySheet.NONE
                    activeInlineStreamPlayer = format.streamUrl to "${info.title} (${format.qualityLabel})"
                },
                onSelectExtractedDownload = { info, format ->
                    haptics.confirm()
                    activeSheet = BrowserOverlaySheet.NONE
                    pendingPopupDownload = info to format
                },
                onDismiss = { activeSheet = BrowserOverlaySheet.NONE }
            )
        }

        BrowserOverlaySheet.PASSWORDS -> {
            BrowserPasswordsDialog(
                passwords = savedPasswords,
                activePageUrl = activeTab.url,
                googleSyncState = googleSyncState,
                onToggleAutoSyncPasswords = { enabled ->
                    haptics.toggle(enabled)
                    if (enabled && !googleSyncState.autoSyncEnabled) {
                        browserRepo.googleSyncManager.setAutoSyncEnabled(true)
                    }
                    browserRepo.googleSyncManager.setAutoSyncPasswords(enabled)
                    if (enabled) {
                        browserRepo.syncAccountVaultNow()
                        browserRepo.googleSyncManager.triggerManualSyncNow()
                    }
                },
                onSyncNowClick = {
                    haptics.confirm()
                    coroutineScope.launch {
                        browserRepo.googleSyncManager.inspectActiveWebViewGoogleCookiesAsync(
                            webViews[activeTab.id]?.settings?.userAgentString
                        )
                        val result = browserRepo.googleSyncManager.triggerManualSyncAndAwait()
                        val pTotal = browserRepo.savedPasswordsFlow.value.size
                        val errMsg = browserRepo.googleSyncStateFlow.value.syncErrorMessage
                        result.onSuccess {
                            val msg = "Synced $pTotal password(s) & passkey(s) with ${browserRepo.googleSyncStateFlow.value.effectiveAccountEmail ?: "Google Account"}"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            repository.showActionFeedback(msg)
                        }.onFailure { err ->
                            val msg = errMsg ?: err.localizedMessage ?: "Password sync failed"
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            repository.showActionFeedback(msg)
                        }
                    }
                },
                onOpenGoogleSyncDialog = {
                    haptics.tap()
                    activeSheet = BrowserOverlaySheet.GOOGLE_SYNC
                },
                onAddManualPassword = { url, username, password, title ->
                    haptics.confirm()
                    val saved = browserRepo.addOrUpdateSavedPassword(
                        url = url,
                        username = username,
                        password = password,
                        title = title,
                        source = "Google Sync"
                    )
                    if (saved != null) {
                        val syncNote = if (googleSyncState.autoSyncEnabled && googleSyncState.autoSyncPasswords) {
                            " & synced to ${googleSyncState.effectiveAccountEmail ?: "Google Account"}"
                        } else ""
                        Toast.makeText(context, "Password saved for ${saved.domain.ifBlank { saved.title }}$syncNote", Toast.LENGTH_SHORT).show()
                        repository.showActionFeedback("Password saved & synced: ${saved.domain.ifBlank { saved.title }}")
                    }
                },
                onImportFromStorageClick = {
                    haptics.tap()
                    passwordFilePickerLauncher.launch(arrayOf("*/*"))
                },
                onOpenGooglePasswordExportClick = {
                    haptics.tap()
                    activeSheet = BrowserOverlaySheet.NONE
                    openUrlInTab("https://passwords.google.com/options", inNewTab = false)
                },
                onFillOnPage = { entry ->
                    haptics.confirm()
                    activeSheet = BrowserOverlaySheet.NONE
                    if (BrowserPasskeyManager.isPasskeySecret(entry.password)) {
                        webViews[activeTab.id]?.evaluateJavascript(
                            buildAutofillPasswordJs(entry.username, "")
                        ) {
                            Toast.makeText(
                                context,
                                "Passkey ready for ${entry.username.ifBlank { entry.domain }}. Tap 'Sign in with a passkey' on the page.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        webViews[activeTab.id]?.evaluateJavascript(
                            buildAutofillPasswordJs(entry.username, entry.password)
                        ) { msg ->
                            val clean = msg?.trim('"') ?: "Login filled"
                            Toast.makeText(context, clean, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onDeleteEntry = { id ->
                    haptics.warning()
                    browserRepo.deleteSavedPassword(id)
                },
                onRemoveLocalPasswords = {
                    haptics.warning()
                    val removed = browserRepo.removeLocalPasswords()
                    Toast.makeText(
                        context,
                        "Removed $removed local password(s) from device",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onClearAll = {
                    haptics.warning()
                    browserRepo.clearSavedPasswords()
                },
                onDismiss = { activeSheet = BrowserOverlaySheet.NONE }
            )
        }

        BrowserOverlaySheet.GOOGLE_SYNC -> {
            BrowserGoogleSyncDialog(
                syncState = googleSyncState,
                passwordsCount = savedPasswords.size,
                onSignInWithGoogleClick = {
                    haptics.confirm()
                    coroutineScope.launch {
                        browserRepo.googleSyncManager.inspectActiveWebViewGoogleCookiesAsync(
                            webViews[activeTab.id]?.settings?.userAgentString
                        )
                        val result = browserRepo.googleSyncManager.signInWithGoogleCredentialManager(
                            activityContext = context,
                            silentOnly = false
                        )
                        result.onSuccess { email ->
                            browserRepo.syncAccountVaultNow()
                            Toast.makeText(context, "Google Auto-Sync active for $email", Toast.LENGTH_SHORT).show()
                            repository.showActionFeedback("Google Auto-Sync active for $email")
                        }.onFailure { err ->
                            // If no Android OS Google credential is present, open Browser Google Sign-In directly
                            activeSheet = BrowserOverlaySheet.NONE
                            openUrlInTab(
                                "https://accounts.google.com/ServiceLogin?continue=https://myaccount.google.com",
                                inNewTab = false
                            )
                            val msg = err.localizedMessage ?: "Sign in to your Google account in the browser tab to auto-sync"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onSignInViaBrowserTabClick = {
                    haptics.confirm()
                    activeSheet = BrowserOverlaySheet.NONE
                    openUrlInTab(
                        "https://accounts.google.com/AccountChooser?continue=https://myaccount.google.com",
                        inNewTab = false
                    )
                },
                onSyncNowClick = {
                    haptics.confirm()
                    coroutineScope.launch {
                        browserRepo.googleSyncManager.inspectActiveWebViewGoogleCookiesAsync(
                            webViews[activeTab.id]?.settings?.userAgentString
                        )
                        val result = browserRepo.googleSyncManager.triggerManualSyncAndAwait()
                        val pTotal = browserRepo.savedPasswordsFlow.value.size
                        val errMsg = browserRepo.googleSyncStateFlow.value.syncErrorMessage
                        result.onSuccess {
                            val msg = "Synced $pTotal password(s)"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            repository.showActionFeedback(msg)
                        }.onFailure { err ->
                            val msg = errMsg ?: err.localizedMessage ?: "Cloud sync failed"
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            repository.showActionFeedback(msg)
                        }
                    }
                },
                onSignOutClick = {
                    haptics.warning()
                    coroutineScope.launch {
                        browserRepo.googleSyncManager.signOut(context)
                        Toast.makeText(context, "Signed out of Google Account Sync", Toast.LENGTH_SHORT).show()
                    }
                },
                onToggleMasterAutoSync = { enabled ->
                    haptics.toggle(enabled)
                    browserRepo.googleSyncManager.setAutoSyncEnabled(enabled)
                    if (enabled) browserRepo.syncAccountVaultNow()
                },
                onToggleAutoSyncPasswords = { enabled ->
                    haptics.toggle(enabled)
                    browserRepo.googleSyncManager.setAutoSyncPasswords(enabled)
                    if (enabled) browserRepo.syncAccountVaultNow()
                },
                onToggleAutoCapturePasswords = { enabled ->
                    haptics.toggle(enabled)
                    browserRepo.googleSyncManager.setAutoCaptureWebPasswords(enabled)
                },
                onOpenGooglePasswordManagerClick = {
                    haptics.tap()
                    activeSheet = BrowserOverlaySheet.NONE
                    openUrlInTab("https://passwords.google.com/options", inNewTab = false)
                },
                onDismiss = { activeSheet = BrowserOverlaySheet.NONE }
            )
        }

        BrowserOverlaySheet.PROXY -> {
            BrowserProxyDialog(
                proxyEnabled = proxyEnabled,
                profiles = proxyProfiles,
                activeProfileId = activeProxyId,
                onToggleProxyEnabled = { enabled ->
                    haptics.toggle(enabled)
                    browserRepo.setProxyEnabled(enabled)
                    val curProfile = browserRepo.getActiveProxyProfile()
                    BrowserProxyManager.configureWebViewNetworkRouting(
                        context = context,
                        proxyEnabled = enabled,
                        proxyProfile = curProfile,
                        warpDns = downloadManager.warpDns
                    ) { _, msg ->
                        repository.showActionFeedback(msg)
                        webViews[activeTab.id]?.let { wv ->
                            wv.clearCache(true)
                            wv.reload()
                        }
                    }
                },
                onSelectProfile = { profile ->
                    haptics.confirm()
                    browserRepo.selectProxyProfile(profile.id)
                    BrowserProxyManager.configureWebViewNetworkRouting(
                        context = context,
                        proxyEnabled = true,
                        proxyProfile = profile,
                        warpDns = downloadManager.warpDns
                    ) { _, msg ->
                        repository.showActionFeedback(msg)
                        webViews[activeTab.id]?.let { wv ->
                            wv.clearCache(true)
                            wv.reload()
                        }
                    }
                },
                onSaveProfile = { profile, activateImmediately ->
                    haptics.confirm()
                    val saved = browserRepo.saveProxyProfile(profile, activateImmediately)
                    val shouldEnable = browserRepo.proxyEnabledFlow.value
                    val activeNow = browserRepo.getActiveProxyProfile()
                    Toast.makeText(
                        context,
                        "Proxy saved: ${saved.type.label} (${saved.toDisplayEndpoint()})",
                        Toast.LENGTH_SHORT
                    ).show()
                    BrowserProxyManager.configureWebViewNetworkRouting(
                        context = context,
                        proxyEnabled = shouldEnable,
                        proxyProfile = activeNow,
                        warpDns = downloadManager.warpDns
                    ) { _, msg ->
                        repository.showActionFeedback(msg)
                        if (shouldEnable) {
                            webViews[activeTab.id]?.let { wv ->
                                wv.clearCache(true)
                                wv.reload()
                            }
                        }
                    }
                },
                onDeleteProfile = { id ->
                    haptics.warning()
                    browserRepo.deleteProxyProfile(id)
                    val shouldEnable = browserRepo.proxyEnabledFlow.value
                    val activeNow = browserRepo.getActiveProxyProfile()
                    BrowserProxyManager.configureWebViewNetworkRouting(
                        context = context,
                        proxyEnabled = shouldEnable,
                        proxyProfile = activeNow,
                        warpDns = downloadManager.warpDns
                    )
                },
                onDismiss = { activeSheet = BrowserOverlaySheet.NONE }
            )
        }

        BrowserOverlaySheet.SECURITY_SHIELD -> {
            BrowserSecurityShieldDialog(
                state = securityShieldState,
                activeUrl = activeTab.url,
                onToggleMalwareShield = { enabled ->
                    haptics.toggle(enabled)
                    MalwareProtectionEngine.updateSettings(context, malwareShieldEnabled = enabled)
                },
                onToggleHttpsUpgrade = { enabled ->
                    haptics.toggle(enabled)
                    MalwareProtectionEngine.updateSettings(context, httpsUpgradeEnabled = enabled)
                },
                onToggleDownloadGuard = { enabled ->
                    haptics.toggle(enabled)
                    MalwareProtectionEngine.updateSettings(context, downloadGuardEnabled = enabled)
                },
                onToggleExtensionScanner = { enabled ->
                    haptics.toggle(enabled)
                    MalwareProtectionEngine.updateSettings(context, extensionScannerEnabled = enabled)
                },
                onToggleStrictMixedContent = { enabled ->
                    haptics.toggle(enabled)
                    MalwareProtectionEngine.updateSettings(context, strictMixedContentEnabled = enabled)
                    val mode = if (enabled) WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    else WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    webViews.values.forEach { wv ->
                        runCatching { wv.settings.mixedContentMode = mode }
                    }
                },
                onClearThreatLog = {
                    haptics.warning()
                    MalwareProtectionEngine.clearThreatLog(context)
                },
                onDismiss = { activeSheet = BrowserOverlaySheet.NONE }
            )
        }

        BrowserOverlaySheet.NONE -> Unit
    }

    // Extension Popup / Settings / Info Modal Dialog
    activeExtensionPopup?.let { (popupExt, initialPanelTab) ->
        val liveExt = extensions.find { it.id == popupExt.id } ?: popupExt
        BrowserExtensionPopupDialog(
            extension = liveExt,
            extensionEngine = browserRepo.extensionEngine,
            activeTabUrl = activeTab.url,
            activeTabTitle = activeTab.title,
            initialTab = initialPanelTab,
            onOpenTabFromExtension = { url ->
                openUrlInTab(url, inNewTab = true, incognito = activeTab.isIncognito)
            },
            onUpdateActiveTabUrl = { nextUrl ->
                openUrlInTab(nextUrl, inNewTab = false)
            },
            onReloadActiveTab = {
                webViews[activeTab.id]?.reload()
            },
            onEvalInActiveTab = { jsCode ->
                webViews[activeTab.id]?.evaluateJavascript(jsCode, null)
            },
            onInsertCssInActiveTab = { cssCode ->
                val safeCss = JSONObject.quote(cssCode)
                webViews[activeTab.id]?.evaluateJavascript(
                    "(function(){try{var s=document.createElement('style');s.textContent=$safeCss;(document.head||document.documentElement).appendChild(s);}catch(e){}})();",
                    null
                )
            },
            onToggleExtension = { enabled ->
                haptics.toggle(enabled)
                browserRepo.extensionEngine.setExtensionEnabled(liveExt.id, enabled)
                webViews[activeTab.id]?.reload()
            },
            onRemoveExtension = {
                haptics.warning()
                browserRepo.extensionEngine.removeExtension(liveExt.id)
                activeExtensionPopup = null
                webViews[activeTab.id]?.reload()
            },
            onDismiss = { activeExtensionPopup = null }
        )
    }

    // Google OAuth / Multi-Window Sign-In Popup Modal preserving window.opener & cookies
    // Rendered before stream/link/download popups so downloads triggered inside Sign-In Window appear on top
    oauthPopupWebView?.let { popupWv ->
        BrowserOAuthPopupDialog(
            popupWebView = popupWv,
            subPopupWebView = subPopupWebView,
            onOpenInTab = {
                haptics.confirm()
                val targetUrl = popupWv.url?.takeIf { it.isNotBlank() && !it.startsWith("about:", true) }
                    ?: popupWv.originalUrl?.takeIf { it.isNotBlank() && !it.startsWith("about:", true) }
                    ?: subPopupWebView?.url?.takeIf { it.isNotBlank() && !it.startsWith("about:", true) }
                if (!activeTab.isIncognito) {
                    runCatching { CookieManager.getInstance().flush() }
                }
                runCatching {
                    subPopupWebView?.stopLoading()
                    (subPopupWebView?.parent as? ViewGroup)?.removeView(subPopupWebView)
                    subPopupWebView?.destroy()
                }
                subPopupWebView = null
                runCatching {
                    popupWv.stopLoading()
                    (popupWv.parent as? ViewGroup)?.removeView(popupWv)
                    popupWv.destroy()
                }
                oauthPopupWebView = null
                if (!targetUrl.isNullOrBlank()) {
                    openUrlInTab(targetUrl, inNewTab = true)
                }
            },
            onDismiss = {
                runCatching {
                    subPopupWebView?.stopLoading()
                    (subPopupWebView?.parent as? ViewGroup)?.removeView(subPopupWebView)
                    subPopupWebView?.destroy()
                }
                subPopupWebView = null
                runCatching {
                    popupWv.stopLoading()
                    (popupWv.parent as? ViewGroup)?.removeView(popupWv)
                    popupWv.destroy()
                }
                oauthPopupWebView = null
            }
        )
    }

    // Built-in Universal Online Video & Audio Stream Player Modal (HLS .m3u8, DASH .mpd, MP4, WebM, MKV, Audio)
    activeInlineStreamPlayer?.let { (streamUrl, streamTitle) ->
        BrowserUniversalStreamPlayerDialog(
            streamUrl = streamUrl,
            streamTitle = streamTitle,
            pageRefererUrl = oauthPopupWebView?.url?.takeIf { it.isNotBlank() } ?: activeTab.url,
            isInPipMode = isInPipMode,
            onEnterPip = {
                context.findActivity()?.let { BrowserPipController.enterPipNow(it) }
            },
            onDownloadStream = {
                activeInlineStreamPlayer = null
                val referer = oauthPopupWebView?.url?.takeIf { it.isNotBlank() } ?: activeTab.url
                val ext = streamUrl.substringBefore('?').substringAfterLast('.', "")
                    .lowercase(Locale.US).takeIf { it.length in 2..5 } ?: "mp4"
                val isAudio = WebViewMediaExtractor.isLikelyAudioStreamUrl(streamUrl.lowercase(Locale.US))
                val format = MediaFormatOption(
                    id = "stream_dl_${streamUrl.hashCode()}",
                    qualityLabel = if (ext == "m3u8") "HLS Stream" else ext.uppercase(Locale.US),
                    resolutionHeight = if (isAudio) 0 else 1080,
                    container = if (ext == "m3u8" || ext == "mpd") "mp4" else ext,
                    mimeType = if (isAudio) "audio/$ext" else "video/mp4",
                    isAudioOnly = isAudio,
                    streamUrl = streamUrl,
                    requestHeaders = buildMap {
                        put("Referer", referer)
                        put("User-Agent", resolveBrowserUserAgent(context, activeTab.desktopMode))
                        downloadManager.webViewExtractor.getCookiesForUrl(streamUrl)?.let { put("Cookie", it) }
                    }
                )
                val info = MediaInfo(
                    originalUrl = referer,
                    canonicalUrl = streamUrl,
                    title = streamTitle.ifBlank { "Stream_Video" },
                    creator = Uri.parse(referer).host?.removePrefix("www.") ?: "Web Stream",
                    durationSeconds = 0L,
                    durationFormatted = "Live Stream",
                    thumbnailUrl = "",
                    platform = MediaPlatform.detectFromUrl(streamUrl).let {
                        if (it == MediaPlatform.UNSUPPORTED) MediaPlatform.DIRECT else it
                    },
                    videoFormats = if (isAudio) emptyList() else listOf(format),
                    audioFormats = if (isAudio) listOf(format) else emptyList(),
                    extractorSource = "Universal Stream Player"
                )
                handleBrowserDownloadIntercepted(info, format)
            },
            onDismiss = { activeInlineStreamPlayer = null }
        )
    }

    // Link Long-Press Dialog
    longPressedLinkUrl?.let { linkToOpen ->
        val isStreamableLink = remember(linkToOpen) {
            val l = linkToOpen.lowercase(Locale.US)
            WebViewMediaExtractor.isLikelyVideoStreamUrl(l) ||
                WebViewMediaExtractor.isLikelyAudioStreamUrl(l) ||
                com.example.data.downloader.CloudflareWarpDns.isLikelyMediaStreamAssetUrl(l)
        }
        BrowserLinkLongPressDialog(
            url = linkToOpen,
            isStreamableMedia = isStreamableLink,
            onStreamMediaNow = {
                val target = linkToOpen
                longPressedLinkUrl = null
                activeInlineStreamPlayer = target to (target.substringAfterLast('/').substringBefore('?').ifBlank { activeTab.title })
            },
            onOpenInNewTab = {
                longPressedLinkUrl = null
                openUrlInTab(linkToOpen, inNewTab = true, incognito = activeTab.isIncognito)
            },
            onOpenInIncognito = {
                longPressedLinkUrl = null
                openUrlInTab(linkToOpen, inNewTab = true, incognito = true)
            },
            onDownloadLink = {
                longPressedLinkUrl = null
                val referer = oauthPopupWebView?.url?.takeIf { it.isNotBlank() } ?: activeTab.url
                val guessedName = resolveWebViewDownloadFileName(linkToOpen, null, null)
                val ext = guessedName.substringAfterLast('.', "").lowercase(Locale.US).takeIf { it.length in 1..6 }
                    ?: linkToOpen.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.US).takeIf { it.length in 2..5 }
                    ?: "bin"
                val format = MediaFormatOption(
                    id = "direct_${linkToOpen.hashCode()}",
                    qualityLabel = ext.uppercase(Locale.US),
                    resolutionHeight = 0,
                    container = ext,
                    mimeType = "application/octet-stream",
                    isAudioOnly = false,
                    streamUrl = linkToOpen,
                    requestHeaders = buildMap {
                        put("Referer", referer)
                        put("User-Agent", resolveBrowserUserAgent(context, activeTab.desktopMode))
                        collectBrowserDownloadCookies(linkToOpen, referer, null)?.let { put("Cookie", it) }
                    },
                    isGenericFile = true,
                    fileCategoryLabel = "Browser Download"
                )
                val mediaInfo = MediaInfo(
                    originalUrl = linkToOpen,
                    canonicalUrl = linkToOpen,
                    title = guessedName.substringBeforeLast('.').ifBlank { "Download File" },
                    creator = Uri.parse(linkToOpen).host?.removePrefix("www.") ?: "Web",
                    durationSeconds = 0L,
                    durationFormatted = "File",
                    thumbnailUrl = "",
                    platform = MediaPlatform.detectFromUrl(linkToOpen).let {
                        if (it == MediaPlatform.UNSUPPORTED) MediaPlatform.DIRECT else it
                    },
                    videoFormats = emptyList(),
                    audioFormats = emptyList(),
                    fileFormats = listOf(format)
                )
                handleBrowserDownloadIntercepted(mediaInfo, format)
            },
            onDismiss = { longPressedLinkUrl = null }
        )
    }

    if (showPipPermissionPrompt) {
        AlertDialog(
            onDismissRequest = { showPipPermissionPrompt = false },
            modifier = Modifier.testTag("pip_permission_dialog"),
            icon = {
                Icon(
                    imageVector = Icons.Default.PictureInPictureAlt,
                    contentDescription = "Picture-in-Picture Permission",
                    tint = NeonCyan
                )
            },
            title = {
                Text(
                    text = "Allow Picture-in-Picture",
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "Picture-in-picture is turned off for this app in Android Settings. Open Settings -> Picture-in-picture, turn Allow picture-in-picture ON, then try again.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        haptics.confirm()
                        showPipPermissionPrompt = false
                        BrowserPipController.openPipSettings(context)
                    },
                    modifier = Modifier.testTag("btn_open_pip_settings")
                ) {
                    Text("Open PiP Settings", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPipPermissionPrompt = false },
                    modifier = Modifier.testTag("btn_cancel_pip_settings")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Passkey Sign-In Helper Dialog when an external hardware/Chrome OS passkey requires Chrome Custom Tab
    passkeyFallbackPrompt?.let { (loginUrl, rpId) ->
        val matchingPasswords = remember(loginUrl, savedPasswords) {
            browserRepo.findSavedPasswordsForUrl(loginUrl)
                .filterNot { BrowserPasskeyManager.isPasskeySecret(it.password) }
        }
        AlertDialog(
            onDismissRequest = { passkeyFallbackPrompt = null },
            modifier = Modifier.testTag("passkey_fallback_dialog"),
            icon = {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = "Passkey Sign-In",
                    tint = NeonCyan
                )
            },
            title = {
                Text(
                    text = "Passkey Sign-In Help",
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val hostLabel = rpId.ifBlank {
                        runCatching { Uri.parse(loginUrl).host?.removePrefix("www.") }.getOrNull() ?: "this site"
                    }
                    Text(
                        text = "Android blocks external Google/Chrome passkeys inside third-party embedded WebViews on $hostLabel unless the site authorizes the app package.",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "To sign in with your device Passkey, continue in Chrome Custom Tab or fill your synced password instead.",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        haptics.confirm()
                        passkeyFallbackPrompt = null
                        launchSystemChromePasskeyFlow(loginUrl)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                    modifier = Modifier.testTag("btn_passkey_open_chrome")
                ) {
                    Text("Open in Chrome", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (matchingPasswords.isNotEmpty()) {
                        val firstCred = matchingPasswords.first()
                        TextButton(
                            onClick = {
                                haptics.confirm()
                                passkeyFallbackPrompt = null
                                webViews[activeTab.id]?.evaluateJavascript(
                                    buildAutofillPasswordJs(firstCred.username, firstCred.password)
                                ) { msg ->
                                    Toast.makeText(context, msg?.trim('"') ?: "Credentials filled", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.testTag("btn_passkey_fill_password")
                        ) {
                            Text("Fill Password", color = SuccessGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(onClick = { passkeyFallbackPrompt = null }) {
                        Text("Dismiss")
                    }
                }
            }
        )
    }

    // Threat Blocked Warning Dialog (Phishing, Homograph, Malware Host, Untrusted SSL)
    pendingThreatPrompt?.let { (blockedUrl, verdict) ->
        BrowserThreatWarningDialog(
            threatUrl = blockedUrl,
            category = verdict.category,
            severity = verdict.severity,
            reason = verdict.reason,
            onBackToSafety = {
                haptics.confirm()
                pendingThreatPrompt = null
                val wv = webViews[activeTab.id]
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                }
            },
            onProceedAnyway = {
                haptics.warning()
                pendingThreatPrompt = null
                MalwareProtectionEngine.allowThreatUrlForSession(blockedUrl)
                browserRepo.updateTabState(activeTab.id) { it.copy(url = blockedUrl, isLoading = true, progress = 12) }
                webViews[activeTab.id]?.loadUrl(blockedUrl)
            }
        )
    }

    // High-Risk / Malicious Download Guard Warning Dialog
    pendingDownloadSecurityPrompt?.let { (mediaInfo, format, verdict) ->
        BrowserDownloadSecurityWarningDialog(
            fileName = verdict.sanitizedFileName,
            sourceUrl = format.streamUrl,
            category = verdict.category,
            reason = verdict.reason,
            isCriticalBlock = verdict.isCriticalBlock,
            onCancelDownload = {
                haptics.confirm()
                pendingDownloadSecurityPrompt = null
            },
            onAllowDownload = {
                haptics.warning()
                pendingDownloadSecurityPrompt = null
                handleBrowserDownloadIntercepted(mediaInfo, format, bypassSecurityCheck = true)
            }
        )
    }

    // Explicit Per-Origin Camera / Microphone Permission Confirmation Dialog
    pendingOriginPermissionRequest?.let { req ->
        val res = req.resources
        val wantsCam = res.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val wantsMic = res.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        BrowserOriginPermissionDialog(
            originUrl = req.origin?.toString().orEmpty().ifBlank { activeTab.url },
            wantsCamera = wantsCam,
            wantsMic = wantsMic,
            onDeny = {
                haptics.tap()
                pendingOriginPermissionRequest = null
                runCatching { req.deny() }
            },
            onGrant = {
                haptics.confirm()
                pendingOriginPermissionRequest = null
                val filtered = res.filter {
                    it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID ||
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
                }.toTypedArray()
                runCatching {
                    if (filtered.isNotEmpty()) req.grant(filtered) else req.deny()
                }
            }
        )
    }

    // Start Download Pop-up Window (always rendered on top of Sign-In Window and other modals)
    pendingPopupDownload?.let { (info, format) ->
        StartDownloadPopupDialog(
            mediaInfo = info,
            formatOption = format,
            downloadManager = downloadManager,
            maxConnections = maxConnections,
            onDismiss = { pendingPopupDownload = null },
            onDownloadStarted = { _, savedName ->
                pendingPopupDownload = null
                repository.showActionFeedback("Downloading to Download/DDM: $savedName")
            }
        )
    }
}

internal fun resolveWebViewDownloadFileName(
    url: String,
    contentDisposition: String?,
    mimetype: String?,
    explicitFilename: String? = null
): String {
    val cleanExplicit = explicitFilename?.trim()?.takeIf { it.isNotBlank() && it != "download" && it != "true" }
    if (cleanExplicit != null) {
        return runCatching { Uri.decode(cleanExplicit) }.getOrDefault(cleanExplicit)
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
    }
    if (!contentDisposition.isNullOrBlank()) {
        val utf8Match = Regex("filename\\*\\s*=\\s*(?:UTF-8''|utf-8'')([^;]+)", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1)?.trim()?.trim('"', '\'')
        if (!utf8Match.isNullOrBlank()) {
            val decoded = runCatching { Uri.decode(utf8Match) }.getOrDefault(utf8Match)
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
            if (decoded.isNotBlank()) return decoded
        }
        val quotedMatch = Regex("filename\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1)?.trim()
            ?: Regex("filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
                .find(contentDisposition)?.groupValues?.getOrNull(1)?.trim()?.trim('"', '\'')
        if (!quotedMatch.isNullOrBlank()) {
            val decoded = runCatching { Uri.decode(quotedMatch) }.getOrDefault(quotedMatch)
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
            if (decoded.isNotBlank()) return decoded
        }
    }
    val pathSegment = runCatching {
        Uri.parse(url).lastPathSegment?.let { Uri.decode(it) }?.trim()
    }.getOrNull()
    if (!pathSegment.isNullOrBlank() && pathSegment.contains('.')) {
        val extCandidate = pathSegment.substringAfterLast('.', "").lowercase(Locale.US)
        if (extCandidate.length in 2..6 && extCandidate !in setOf("html", "htm", "php", "asp", "aspx", "jsp")) {
            return pathSegment
        }
    }
    val guessed = runCatching { URLUtil.guessFileName(url, contentDisposition, mimetype) }.getOrNull()
    return guessed?.takeIf { it.isNotBlank() } ?: "download_${System.currentTimeMillis()}.bin"
}

internal fun collectBrowserDownloadCookies(
    downloadUrl: String,
    refererUrl: String?,
    explicitPageCookies: String?
): String? {
    val cm = runCatching { CookieManager.getInstance() }.getOrNull()
    val merged = LinkedHashMap<String, String>()
    fun absorb(raw: String?) {
        if (raw.isNullOrBlank()) return
        raw.split(";").forEach { piece ->
            val trimmed = piece.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) {
                val k = trimmed.substring(0, eq).trim()
                val v = trimmed.substring(eq + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty() &&
                    !k.equals("path", true) &&
                    !k.equals("domain", true) &&
                    !k.equals("samesite", true) &&
                    !k.equals("expires", true) &&
                    !k.equals("max-age", true)
                ) {
                    merged[k] = v
                }
            }
        }
    }
    fun absorbForUrl(targetUrl: String?) {
        if (targetUrl.isNullOrBlank() || !targetUrl.startsWith("http", true)) return
        absorb(runCatching { cm?.getCookie(targetUrl) }.getOrNull())
        val host = runCatching { Uri.parse(targetUrl).host?.lowercase(Locale.US)?.removePrefix("www.") }.getOrNull()
        if (!host.isNullOrBlank()) {
            val parts = host.split('.').filter { it.isNotBlank() }
            if (parts.size >= 2) {
                val baseDomain = parts.takeLast(2).joinToString(".")
                absorb(runCatching { cm?.getCookie("https://$baseDomain") }.getOrNull())
                absorb(runCatching { cm?.getCookie("https://www.$baseDomain") }.getOrNull())
            }
        }
    }
    absorbForUrl(refererUrl)
    absorbForUrl(downloadUrl)
    absorb(explicitPageCookies)

    if (!explicitPageCookies.isNullOrBlank() && cm != null) {
        val baseDomains = LinkedHashSet<String>()
        listOfNotNull(refererUrl, downloadUrl).forEach { u ->
            val h = runCatching { Uri.parse(u).host?.lowercase(Locale.US)?.removePrefix("www.") }.getOrNull()
            if (!h.isNullOrBlank()) {
                val parts = h.split('.').filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    baseDomains.add(parts.takeLast(2).joinToString("."))
                }
            }
        }
        merged.forEach { (k, v) ->
            baseDomains.forEach { dom ->
                runCatching {
                    cm.setCookie("https://$dom", "$k=$v; Domain=.$dom; Path=/; Secure")
                }
            }
        }
        runCatching { cm.flush() }
    }

    return if (merged.isEmpty()) null else merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

internal fun isLikelyDirectCloudDownloadUrl(url: String): Boolean {
    val lower = url.lowercase(Locale.US).trim()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    val withoutQuery = lower.substringBefore('?').substringBefore('#')
    if (lower.contains(".gofile.io/download/") || lower.contains("gofile.io/download/")) return true
    if (lower.contains("pixeldrain.com/api/file/") && lower.contains("download")) return true
    if (lower.contains("downloads.sourceforge.net/")) return true
    if (lower.contains("objects.githubusercontent.com/") && lower.contains("response-content-disposition=attachment")) return true
    val directBinaryExts = setOf(
        ".zip", ".rar", ".7z", ".apk", ".xapk", ".apks", ".tar", ".gz", ".tgz", ".xz", ".bz2",
        ".iso", ".img", ".bin", ".exe", ".msi", ".dmg", ".deb", ".rpm", ".cab", ".zst"
    )
    return directBinaryExts.any { withoutQuery.endsWith(it) }
}

private class BackgroundMediaWebView(context: Context) : WebView(context) {
    @Volatile
    var keepMediaAliveInBackground: Boolean = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.VISIBLE && keepMediaAliveInBackground) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus && keepMediaAliveInBackground && (
                BrowserPipController.isInPipModeFlow.value ||
                    BrowserPipController.isBackgroundMinimizedFlow.value
                )
        ) {
            return
        }
        super.onWindowFocusChanged(hasWindowFocus)
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createConfiguredBrowserWebView(
    context: android.content.Context,
    tab: BrowserTab,
    desktopMode: Boolean,
    warpDns: com.example.data.downloader.CloudflareWarpDns,
    browserRepo: BrowserStateRepository,
    mainHandler: Handler,
    onDownloadIntercepted: (MediaInfo, MediaFormatOption) -> Unit,
    onRendererCrashed: (tabId: String, crashedView: WebView?) -> Unit = { _, _ -> },
    onLongPressLink: (String) -> Unit = {},
    onShowFullscreenVideo: (View, WebChromeClient.CustomViewCallback?) -> Unit = { _, _ -> },
    onHideFullscreenVideo: () -> Unit = {},
    onOpenInlineStreamPlayer: (streamUrl: String, title: String) -> Unit = { _, _ -> },
    onInstallChromeWebStoreExtension: (urlOrId: String, titleHint: String?) -> Unit = { _, _ -> },
    onUpdateTabUrl: (String) -> Unit = {},
    onOpenWindowPopup: (WebView) -> Unit = {},
    onAttachSubPopup: (WebView) -> Unit = {},
    onCloseWindowPopup: (WebView) -> Unit = {},
    onInspectorElementPicked: (String) -> Unit = {},
    onPasskeyFallbackRequested: (loginUrl: String, rpId: String) -> Unit = { _, _ -> },
    onSecurityThreatBlocked: (url: String, verdict: UrlSecurityVerdict) -> Unit = { _, _ -> },
    onOriginPermissionRequested: (PermissionRequest) -> Unit = { runCatching { it.deny() } },
    onMediaPlaybackChanged: (
        tabId: String,
        isVideoPlaying: Boolean,
        isAudioPlaying: Boolean,
        mediaTitle: String,
        mediaUrl: String,
        videoWidth: Int,
        videoHeight: Int,
        leftFrac: Float,
        topFrac: Float,
        rightFrac: Float,
        bottomFrac: Float
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _ -> },
    onWebPipRequested: (
        tabId: String,
        isFloating: Boolean,
        videoWidth: Int,
        videoHeight: Int,
        leftFrac: Float,
        topFrac: Float,
        rightFrac: Float,
        bottomFrac: Float
    ) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onSystemPipPopOutRequested: (
        tabId: String,
        videoWidth: Int,
        videoHeight: Int,
        leftFrac: Float,
        topFrac: Float,
        rightFrac: Float,
        bottomFrac: Float
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    isPopupWindow: Boolean = false,
    isHiddenSubPopup: Boolean = false,
    parentPageUrlProvider: () -> String? = { null }
): WebView {
    return BackgroundMediaWebView(context).apply {
        var hasCommittedHtmlPage = false
        var downloadTriggeredOnThisPopup = false
        val isCurrentTabDesktopMode: () -> Boolean = {
            browserRepo.tabsFlow.value.find { it.id == tab.id }?.desktopMode ?: desktopMode
        }

        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Use direct GPU window compositing (LAYER_TYPE_NONE) instead of an extra offscreen HWUI FBO
        // so HTML5 hardware video frames never freeze or stall when pinch-zooming in Desktop Mode
        setLayerType(View.LAYER_TYPE_NONE, null)
        isFocusable = true
        isFocusableInTouchMode = true

        // Refit Desktop Mode viewport immediately whenever WebView width changes (Portrait <-> Landscape / split-screen)
        var lastLayoutWidthPx = -1
        var pendingLayoutRefit: Runnable? = null
        addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w >= 200 && h >= 200 && (lastLayoutWidthPx < 0 || kotlin.math.abs(w - lastLayoutWidthPx) >= 24)) {
                val hadPrevious = lastLayoutWidthPx > 0
                lastLayoutWidthPx = w
                if (hadPrevious && isCurrentTabDesktopMode() && !BrowserPipController.isInPipModeFlow.value) {
                    pendingLayoutRefit?.let { mainHandler.removeCallbacks(it) }
                    val refitTask = Runnable {
                        val wv = v as? WebView ?: return@Runnable
                        if (isCurrentTabDesktopMode() && !BrowserPipController.isInPipModeFlow.value) {
                            DesktopViewportController.syncWebViewViewport(
                                webView = wv,
                                desktopMode = true,
                                forceRefit = true,
                                measureOverflow = false
                            )
                        }
                    }
                    pendingLayoutRefit = refitTask
                    mainHandler.postDelayed(refitTask, 60L)
                }
            }
        }

        fun resolveCurrentRefererUrl(explicitReferer: String? = null): String {
            return explicitReferer?.takeIf { it.isNotBlank() && !it.startsWith("about:", true) }
                ?: this.url?.takeIf { it.isNotBlank() && !it.startsWith("about:", true) }
                ?: parentPageUrlProvider()?.takeIf { it.isNotBlank() && !it.startsWith("about:", true) }
                ?: tab.url
        }

        fun dispatchWebViewDownload(
            rawUrl: String,
            userAgent: String?,
            contentDisposition: String?,
            mimetype: String?,
            contentLength: Long,
            explicitFilename: String? = null,
            explicitCookies: String? = null,
            explicitReferer: String? = null
        ) {
            val cleanUrl = rawUrl.trim()
            if (cleanUrl.isBlank()) return
            if (cleanUrl.startsWith("blob:", ignoreCase = true) || cleanUrl.startsWith("data:", ignoreCase = true)) {
                val safeBlobUrl = JSONObject.quote(cleanUrl)
                val safeName = JSONObject.quote(explicitFilename?.takeIf { it.isNotBlank() } ?: resolveWebViewDownloadFileName(cleanUrl, contentDisposition, mimetype))
                val safeMime = JSONObject.quote(mimetype ?: "application/octet-stream")
                mainHandler.post {
                    evaluateJavascript(
                        """
                        (function(){
                          try {
                            fetch($safeBlobUrl).then(function(r){ return r.blob(); }).then(function(b){
                              var reader = new FileReader();
                              reader.onloadend = function(){
                                if (window.DragonBrowserBridge && reader.result) {
                                  window.DragonBrowserBridge.onBlobDownloadBase64(reader.result, $safeName, b.type || $safeMime);
                                }
                              };
                              reader.readAsDataURL(b);
                            });
                          } catch(e) {}
                        })();
                        """.trimIndent(),
                        null
                    )
                }
                return
            }

            val guessedName = resolveWebViewDownloadFileName(
                url = cleanUrl,
                contentDisposition = contentDisposition,
                mimetype = mimetype,
                explicitFilename = explicitFilename
            ).ifBlank { "download_${System.currentTimeMillis()}" }
            val ext = guessedName.substringAfterLast('.', "")
                .lowercase(Locale.US).takeIf { it.length in 1..6 }
                ?: cleanUrl.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.US).takeIf { it.length in 2..5 }
                ?: "bin"
            val mime = mimetype?.takeIf { it.isNotBlank() } ?: "application/octet-stream"

            // Install Chrome Web Store / .crx extension packages directly when triggered as a download
            if (ext == "crx" ||
                mime.equals("application/x-chrome-extension", ignoreCase = true) ||
                BrowserExtensionEngine.isChromeWebStoreCrxUrl(cleanUrl)
            ) {
                downloadTriggeredOnThisPopup = true
                mainHandler.post {
                    onInstallChromeWebStoreExtension(cleanUrl, guessedName.substringBeforeLast('.'))
                    if (isPopupWindow && (!hasCommittedHtmlPage || isHiddenSubPopup)) {
                        onCloseWindowPopup(this@apply)
                    }
                }
                return
            }

            val isHlsOrDash = ext == "m3u8" || ext == "mpd" ||
                mime.contains("mpegurl", ignoreCase = true) ||
                mime.contains("dash+xml", ignoreCase = true)
            val isVideo = isHlsOrDash || mime.startsWith("video/", true) || MediaPlatform.VIDEO_EXTENSIONS.any { ".$ext" == it }
            val isAudio = mime.startsWith("audio/", true) || MediaPlatform.AUDIO_EXTENSIONS.any { ".$ext" == it }

            val isAttachment = contentDisposition?.contains("attachment", ignoreCase = true) == true
            if (isHlsOrDash && !isAttachment) {
                mainHandler.post {
                    onOpenInlineStreamPlayer(cleanUrl, guessedName.substringBeforeLast('.'))
                }
                return
            }

            val isGeneric = !isVideo && !isAudio
            val refererUrl = resolveCurrentRefererUrl(explicitReferer)
            val resolvedUa = userAgent?.takeIf { it.isNotBlank() }
                ?: runCatching { settings.userAgentString }.getOrNull()
                ?: resolveBrowserUserAgent(context, isCurrentTabDesktopMode())
            val cookieHeader = collectBrowserDownloadCookies(
                downloadUrl = cleanUrl,
                refererUrl = refererUrl,
                explicitPageCookies = explicitCookies
            )

            val headersMap = LinkedHashMap<String, String>().apply {
                put("Referer", refererUrl)
                put("User-Agent", resolvedUa)
                if (!cookieHeader.isNullOrBlank()) {
                    put("Cookie", cookieHeader)
                }
            }

            val format = MediaFormatOption(
                id = "webview_dl_${cleanUrl.hashCode()}",
                qualityLabel = if (isGeneric) ext.uppercase(Locale.US) else if (isAudio) "Audio" else "Video",
                container = ext,
                mimeType = mime,
                contentLengthBytes = contentLength.coerceAtLeast(-1L),
                isAudioOnly = isAudio,
                streamUrl = cleanUrl,
                requestHeaders = headersMap,
                isGenericFile = isGeneric,
                fileCategoryLabel = "Browser Download"
            )
            val info = MediaInfo(
                originalUrl = cleanUrl,
                canonicalUrl = cleanUrl,
                title = guessedName.substringBeforeLast('.').ifBlank { guessedName },
                creator = Uri.parse(cleanUrl).host?.removePrefix("www.") ?: "Web",
                durationSeconds = 0L,
                durationFormatted = if (isGeneric) "File" else "Media",
                thumbnailUrl = "",
                platform = MediaPlatform.detectFromUrl(cleanUrl).let {
                    if (it == MediaPlatform.UNSUPPORTED) MediaPlatform.DIRECT else it
                },
                videoFormats = if (isVideo) listOf(format) else emptyList(),
                audioFormats = if (isAudio) listOf(format) else emptyList(),
                extractorSource = "Browser Download Interceptor",
                fileFormats = if (isGeneric) listOf(format) else emptyList()
            )
            downloadTriggeredOnThisPopup = true
            mainHandler.post {
                onDownloadIntercepted(info, format)
                if (isPopupWindow && (!hasCommittedHtmlPage || isHiddenSubPopup)) {
                    onCloseWindowPopup(this@apply)
                }
            }
        }

        setOnLongClickListener {
            val hitTest = hitTestResult
            if (hitTest.type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                hitTest.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            ) {
                val url = hitTest.extra
                if (!url.isNullOrBlank()) {
                    onLongPressLink(url)
                    return@setOnLongClickListener true
                }
            }
            false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }
        }

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = !tab.isIncognito
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            allowContentAccess = true
            mixedContentMode = if (MalwareProtectionEngine.stateFlow.value.strictMixedContentEnabled) {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            } else {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            cacheMode = if (tab.isIncognito) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        }
        DesktopViewportController.applyWebSettings(
            webView = this,
            userAgent = resolveBrowserUserAgent(context, isCurrentTabDesktopMode()),
            desktopMode = isCurrentTabDesktopMode()
        )
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
            }
        }
        applyGoogleSignInCompatibleWebSettings(this)

        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, DesktopViewportController.DESKTOP_VIEWPORT_CONTROLLER_JS, setOf("*"))
                WebViewCompat.addDocumentStartJavaScript(this, PASSKEY_WEBAUTHN_HOOK_JS, setOf("*"))
                WebViewCompat.addDocumentStartJavaScript(this, WEB_PIP_POLYFILL_JS, setOf("*"))
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)

        addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onDomMediaFound(jsonArrayStr: String) {
                    try {
                        val arr = JSONArray(jsonArrayStr)
                        mainHandler.post {
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                val mediaUrl = obj.optString("url", "").trim()
                                if (mediaUrl.isBlank() || mediaUrl.startsWith("blob:") || mediaUrl.startsWith("data:")) {
                                    continue
                                }
                                val lower = mediaUrl.lowercase(Locale.US)
                                val ext = mediaUrl.substringBefore("?").substringAfterLast('.', "")
                                    .lowercase(Locale.US).takeIf { it.length in 2..5 } ?: "mp4"
                                val kind = when {
                                    WebViewMediaExtractor.isLikelyAudioStreamUrl(lower) -> GrabbedMediaKind.AUDIO
                                    WebViewMediaExtractor.isLikelyDownloadableFileUrl(lower) -> GrabbedMediaKind.FILE
                                    else -> GrabbedMediaKind.VIDEO
                                }
                                browserRepo.recordGrabbedMedia(
                                    tabId = tab.id,
                                    candidate = GrabbedMediaCandidate(
                                        url = mediaUrl,
                                        title = obj.optString("label", "").ifBlank { tab.title },
                                        kind = kind,
                                        containerExt = ext,
                                        sourceLabel = "Page DOM"
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {}
                }

                @JavascriptInterface
                fun openUniversalStreamPlayer(streamUrl: String, label: String) {
                    if (streamUrl.isBlank() || !streamUrl.startsWith("http", ignoreCase = true)) return
                    mainHandler.post {
                        onOpenInlineStreamPlayer(streamUrl.trim(), label.ifBlank { tab.title })
                    }
                }

                @JavascriptInterface
                fun onJsDownloadTriggered(
                    url: String,
                    suggestedFilename: String?,
                    pageCookies: String?,
                    pageUrl: String?
                ) {
                    val clean = url.trim()
                    if (clean.isBlank()) return
                    mainHandler.post {
                        dispatchWebViewDownload(
                            rawUrl = clean,
                            userAgent = runCatching { settings.userAgentString }.getOrNull(),
                            contentDisposition = null,
                            mimetype = null,
                            contentLength = -1L,
                            explicitFilename = suggestedFilename?.trim()?.takeIf { it.isNotBlank() },
                            explicitCookies = pageCookies?.trim()?.takeIf { it.isNotBlank() },
                            explicitReferer = pageUrl?.trim()?.takeIf { it.isNotBlank() }
                        )
                    }
                }

                @JavascriptInterface
                fun onBlobDownloadBase64(dataUrlOrBase64: String, fileName: String?, mimeType: String?) {
                    if (dataUrlOrBase64.isBlank()) return
                    Thread {
                        try {
                            val rawBase64 = dataUrlOrBase64.substringAfter("base64,", dataUrlOrBase64)
                            val bytes = android.util.Base64.decode(rawBase64, android.util.Base64.DEFAULT)
                            if (bytes.isEmpty()) return@Thread
                            val cleanName = MalwareProtectionEngine.sanitizeDownloadFilename(
                                fileName?.trim()?.takeIf { it.isNotBlank() }
                                    ?: "browser_download_${System.currentTimeMillis()}.bin"
                            )
                            val headerSlice = bytes.copyOf(bytes.size.coerceAtMost(128))
                            val disguisedErr = MalwareProtectionEngine.detectDisguisedBinaryError(cleanName, headerSlice)
                            if (disguisedErr != null) {
                                MalwareProtectionEngine.recordThreatEvent(
                                    context = context,
                                    category = "Disguised Blob Malware",
                                    severity = SecuritySeverity.CRITICAL,
                                    target = cleanName,
                                    reason = disguisedErr,
                                    blocked = true
                                )
                                mainHandler.post {
                                    Toast.makeText(context, disguisedErr, Toast.LENGTH_LONG).show()
                                }
                                return@Thread
                            }
                            val dlVerdict = MalwareProtectionEngine.inspectDownload(
                                url = resolveCurrentRefererUrl(),
                                rawFilename = cleanName,
                                mimeType = mimeType,
                                context = context
                            )
                            if (dlVerdict.isCriticalBlock) {
                                MalwareProtectionEngine.recordThreatEvent(
                                    context = context,
                                    category = dlVerdict.category,
                                    severity = dlVerdict.riskLevel,
                                    target = cleanName,
                                    reason = dlVerdict.reason,
                                    blocked = true
                                )
                                mainHandler.post {
                                    Toast.makeText(context, "Blocked malicious download: ${dlVerdict.reason}", Toast.LENGTH_LONG).show()
                                }
                                return@Thread
                            }
                            val subFolder = DragonDownloadManager.subFolderForType(
                                extension = cleanName.substringAfterLast('.', "bin"),
                                mimeType = mimeType ?: "application/octet-stream",
                                isAudioOnly = false,
                                isGenericFile = true
                            )
                            val resolvedMime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                            var exportedToPublic = false
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                try {
                                    val resolver = context.contentResolver
                                    val values = android.content.ContentValues().apply {
                                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, cleanName)
                                        put(android.provider.MediaStore.Downloads.MIME_TYPE, resolvedMime)
                                        put(
                                            android.provider.MediaStore.Downloads.RELATIVE_PATH,
                                            "${android.os.Environment.DIRECTORY_DOWNLOADS}/$subFolder"
                                        )
                                        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                                    }
                                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                    if (uri != null) {
                                        resolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                                        values.clear()
                                        values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                                        resolver.update(uri, values, null, null)
                                        exportedToPublic = true
                                    }
                                } catch (_: Throwable) {}
                            }
                            if (!exportedToPublic) {
                                // Fallback to cacheDir so permanent App User Data does not bloat
                                val cacheDir = java.io.File(context.cacheDir, "ddm_exports/$subFolder").apply { mkdirs() }
                                java.io.File(cacheDir, cleanName).writeBytes(bytes)
                            }
                            val textCandidate = if (bytes.size <= 5 * 1024 * 1024) {
                                val extLower = cleanName.substringAfterLast('.', "").lowercase(Locale.US)
                                val mimeLower = resolvedMime.lowercase(Locale.US)
                                if (extLower in setOf("csv", "html", "htm", "json", "txt") ||
                                    mimeLower.startsWith("text/") ||
                                    mimeLower.contains("csv") ||
                                    mimeLower.contains("json") ||
                                    mimeLower.contains("html")
                                ) {
                                    runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
                                } else null
                            } else null

                            mainHandler.post {
                                val importResult = if (!textCandidate.isNullOrBlank()) {
                                    browserRepo.tryAutoImportFromDownloadedText(
                                        fileName = cleanName,
                                        mimeType = resolvedMime,
                                        rawText = textCandidate
                                    )
                                } else null

                                if (importResult != null) {
                                    val msg = if (importResult.kind == com.example.data.browser.DownloadedImportKind.PASSWORDS) {
                                        if (importResult.added > 0) {
                                            "Auto-imported & synced ${importResult.added} password(s) from $cleanName"
                                        } else {
                                            "All ${importResult.skipped} password(s) from $cleanName already synced"
                                        }
                                    } else {
                                        if (importResult.added > 0) {
                                            "Imported ${importResult.added} bookmark(s) from $cleanName"
                                        } else {
                                            "All ${importResult.skipped} bookmark(s) from $cleanName already saved"
                                        }
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "Downloaded to Download/$subFolder: $cleanName", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (_: Throwable) {}
                    }.start()
                }

                @JavascriptInterface
                fun onGoogleAccountDetected(email: String?, displayName: String?) {
                    if (tab.isIncognito) return
                    val cleanEmail = com.example.data.browser.BrowserGoogleSyncManager.sanitizeEmailCandidate(email)
                    val cleanName = displayName?.trim()?.takeIf { it.isNotBlank() }?.take(120)
                    if (cleanEmail.isNullOrBlank() && cleanName.isNullOrBlank()) return
                    mainHandler.post {
                        val prevEmail = browserRepo.googleSyncStateFlow.value.detectedWebViewEmail
                        browserRepo.onWebViewGoogleAccountDetected(
                            email = cleanEmail,
                            displayName = cleanName,
                            hasSessionCookies = true
                        )
                        if (!cleanEmail.isNullOrBlank() && cleanEmail != prevEmail) {
                            Toast.makeText(
                                context,
                                "Google Account connected ($cleanEmail) • Auto-syncing passwords",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                @JavascriptInterface
                fun onPasswordFormSubmitted(
                    pageUrl: String?,
                    username: String?,
                    password: String?,
                    pageTitle: String?
                ) {
                    if (tab.isIncognito) return
                    val cleanUser = username?.trim().orEmpty()
                    val cleanPass = password.orEmpty()
                    val targetUrl = pageUrl?.trim()?.takeIf { it.isNotBlank() } ?: tab.url
                    if (cleanUser.isBlank() && cleanPass.isBlank()) return
                    mainHandler.post {
                        val host = runCatching { Uri.parse(targetUrl).host?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
                        if ((host == "accounts.google.com" || host.endsWith(".google.com")) && cleanUser.contains('@')) {
                            browserRepo.onWebViewGoogleAccountDetected(
                                email = cleanUser,
                                displayName = null,
                                hasSessionCookies = true
                            )
                        }
                        if (cleanPass.isNotBlank() && browserRepo.googleSyncStateFlow.value.autoCaptureWebPasswords) {
                            val saved = browserRepo.addOrUpdateSavedPassword(
                                url = targetUrl,
                                username = cleanUser,
                                password = cleanPass,
                                title = pageTitle?.trim().orEmpty(),
                                source = "Google Sync"
                            )
                            if (saved != null) {
                                val syncAccount = browserRepo.googleSyncStateFlow.value.effectiveAccountEmail
                                val msg = if (!syncAccount.isNullOrBlank() && browserRepo.googleSyncStateFlow.value.autoSyncEnabled) {
                                    "Saved & synced password for ${saved.domain.ifBlank { saved.title }} to $syncAccount"
                                } else {
                                    "Password saved for ${saved.domain.ifBlank { saved.title }}"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                @JavascriptInterface
                fun onMediaPlaybackChanged(
                    isVideoPlaying: Boolean,
                    isAudioPlaying: Boolean,
                    title: String?,
                    pageUrl: String?,
                    videoWidth: Int,
                    videoHeight: Int,
                    leftFrac: Float,
                    topFrac: Float,
                    rightFrac: Float,
                    bottomFrac: Float
                ) {
                    if (isPopupWindow) return
                    mainHandler.post {
                        onMediaPlaybackChanged(
                            tab.id,
                            isVideoPlaying,
                            isAudioPlaying,
                            title.orEmpty(),
                            pageUrl.orEmpty(),
                            videoWidth,
                            videoHeight,
                            leftFrac,
                            topFrac,
                            rightFrac,
                            bottomFrac
                        )
                    }
                }

                @JavascriptInterface
                fun onVideoPlaybackChanged(
                    isPlaying: Boolean,
                    videoWidth: Int,
                    videoHeight: Int,
                    leftFrac: Float,
                    topFrac: Float,
                    rightFrac: Float,
                    bottomFrac: Float
                ) {
                    if (isPopupWindow) return
                    mainHandler.post {
                        onMediaPlaybackChanged(
                            tab.id,
                            isPlaying,
                            false,
                            "",
                            "",
                            videoWidth,
                            videoHeight,
                            leftFrac,
                            topFrac,
                            rightFrac,
                            bottomFrac
                        )
                    }
                }

                @JavascriptInterface
                fun onWebFloatingPipChanged(
                    isFloating: Boolean,
                    isDocumentPip: Boolean,
                    videoWidth: Int,
                    videoHeight: Int,
                    leftFrac: Float,
                    topFrac: Float,
                    rightFrac: Float,
                    bottomFrac: Float
                ) {
                    if (isPopupWindow) return
                    mainHandler.post {
                        onWebPipRequested(
                            tab.id,
                            isFloating,
                            videoWidth,
                            videoHeight,
                            leftFrac,
                            topFrac,
                            rightFrac,
                            bottomFrac
                        )
                    }
                }

                @JavascriptInterface
                fun requestWebPictureInPicture(
                    videoWidth: Int,
                    videoHeight: Int,
                    leftFrac: Float,
                    topFrac: Float,
                    rightFrac: Float,
                    bottomFrac: Float
                ) {
                    if (isPopupWindow) return
                    mainHandler.post {
                        onWebPipRequested(
                            tab.id,
                            true,
                            videoWidth,
                            videoHeight,
                            leftFrac,
                            topFrac,
                            rightFrac,
                            bottomFrac
                        )
                    }
                }

                @JavascriptInterface
                fun requestSystemPipNow(
                    videoWidth: Int,
                    videoHeight: Int,
                    leftFrac: Float,
                    topFrac: Float,
                    rightFrac: Float,
                    bottomFrac: Float
                ) {
                    if (isPopupWindow) return
                    mainHandler.post {
                        onSystemPipPopOutRequested(
                            tab.id,
                            videoWidth,
                            videoHeight,
                            leftFrac,
                            topFrac,
                            rightFrac,
                            bottomFrac
                        )
                    }
                }
            },
            "DragonBrowserBridge"
        )

        // Native Bridge for WebExtensions (chrome.* / browser.*) & Userscripts (GM_*)
        addJavascriptInterface(
            object {
                @JavascriptInterface
                fun storageGet(extId: String): String {
                    return browserRepo.extensionEngine.getExtensionStorageJson(extId)
                }

                @JavascriptInterface
                fun storageSet(extId: String, jsonPatch: String): String {
                    return browserRepo.extensionEngine.mergeExtensionStorageJson(extId, jsonPatch)
                }

                @JavascriptInterface
                fun storageRemove(extId: String, keysArrayJson: String): String {
                    return browserRepo.extensionEngine.removeExtensionStorageKeys(extId, keysArrayJson)
                }

                @JavascriptInterface
                fun storageClear(extId: String) {
                    browserRepo.extensionEngine.clearExtensionStorage(extId)
                }

                @JavascriptInterface
                fun gmXhr(requestJson: String): String {
                    return browserRepo.extensionEngine.executeCrossOriginXhr(requestJson)
                }

                @JavascriptInterface
                fun openTab(url: String) {
                    val target = url.trim()
                    if (target.isBlank()) return
                    mainHandler.post {
                        val resolved = BrowserStateRepository.resolveBrowserInputToUrl(target)
                        browserRepo.openNewTab(initialUrl = resolved, isIncognito = tab.isIncognito)
                    }
                }

                @JavascriptInterface
                fun copyClipboard(text: String) {
                    mainHandler.post {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Extension", text))
                    }
                }

                @JavascriptInterface
                fun showToast(message: String) {
                    if (message.isBlank()) return
                    mainHandler.post {
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }

                @JavascriptInterface
                fun installFromChromeWebStore(urlOrId: String, titleHint: String?) {
                    if (urlOrId.isBlank()) return
                    mainHandler.post {
                        onInstallChromeWebStoreExtension(urlOrId.trim(), titleHint?.trim())
                    }
                }

                @JavascriptInterface
                fun updateActiveTabUrl(url: String) {
                    if (url.isBlank()) return
                    mainHandler.post {
                        onUpdateTabUrl(url.trim())
                    }
                }

                @JavascriptInterface
                fun reloadActiveTab() {
                    mainHandler.post {
                        reload()
                    }
                }

                @JavascriptInterface
                fun evalInActiveTab(jsCode: String) {
                    if (jsCode.isBlank()) return
                    mainHandler.post {
                        evaluateJavascript(jsCode, null)
                    }
                }

                @JavascriptInterface
                fun evalFilesInActiveTab(extId: String, filesArrayJson: String) {
                    val arr = runCatching { JSONArray(filesArrayJson) }.getOrNull() ?: return
                    val combined = StringBuilder()
                    for (i in 0 until arr.length()) {
                        val filePath = arr.optString(i, "").trim()
                        val code = browserRepo.extensionEngine.readExtensionAssetText(extId, filePath)
                        if (!code.isNullOrBlank()) {
                            combined.append(code).append("\n")
                        }
                    }
                    val script = combined.toString()
                    if (script.isNotBlank()) {
                        mainHandler.post {
                            evaluateJavascript(script, null)
                        }
                    }
                }

                @JavascriptInterface
                fun insertCssInActiveTab(cssCode: String) {
                    if (cssCode.isBlank()) return
                    val safeCss = JSONObject.quote(cssCode)
                    mainHandler.post {
                        evaluateJavascript(
                            "(function(){try{var s=document.createElement('style');s.textContent=$safeCss;(document.head||document.documentElement).appendChild(s);}catch(e){}})();",
                            null
                        )
                    }
                }

                @JavascriptInterface
                fun insertCssFilesInActiveTab(extId: String, filesArrayJson: String) {
                    val arr = runCatching { JSONArray(filesArrayJson) }.getOrNull() ?: return
                    val combined = StringBuilder()
                    for (i in 0 until arr.length()) {
                        val filePath = arr.optString(i, "").trim()
                        val css = browserRepo.extensionEngine.readExtensionAssetText(extId, filePath)
                        if (!css.isNullOrBlank()) {
                            combined.append(css).append("\n")
                        }
                    }
                    val fullCss = combined.toString()
                    if (fullCss.isNotBlank()) {
                        val safeCss = JSONObject.quote(fullCss)
                        mainHandler.post {
                            evaluateJavascript(
                                "(function(){try{var s=document.createElement('style');s.textContent=$safeCss;(document.head||document.documentElement).appendChild(s);}catch(e){}})();",
                                null
                            )
                        }
                    }
                }
            },
            "DragonExtensionBridge"
        )

        // Native Bridge for Visual Element Picker inside Page Inspector
        addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onInspectorElementSelected(selector: String) {
                    val clean = selector.trim()
                    if (clean.isBlank()) return
                    mainHandler.post {
                        onInspectorElementPicked(clean)
                    }
                }
            },
            "DragonInspectorBridge"
        )

        // Native W3C WebAuthn / FIDO2 Passkey Bridge
        addJavascriptInterface(
            object {
                @JavascriptInterface
                fun hasLocalPasskeyForRp(rpId: String?): Boolean {
                    return browserRepo.passkeyManager.hasPasskeyForRp(rpId)
                }

                @JavascriptInterface
                fun createPasskey(optionsJson: String, originUrl: String): String {
                    return browserRepo.passkeyManager.createPasskeyFromWebView(
                        optionsJsonStr = optionsJson,
                        originUrl = originUrl.ifBlank { tab.url },
                        activityContext = context
                    )
                }

                @JavascriptInterface
                fun getPasskeyAssertion(optionsJson: String, originUrl: String): String {
                    return browserRepo.passkeyManager.getPasskeyAssertionFromWebView(
                        optionsJsonStr = optionsJson,
                        originUrl = originUrl.ifBlank { tab.url },
                        activityContext = context
                    )
                }

                @JavascriptInterface
                fun notifyPasskeyFallback(pageUrl: String?, rpId: String?) {
                    val resolvedUrl = pageUrl?.trim()?.takeIf { it.isNotBlank() } ?: tab.url
                    val resolvedRpId = rpId?.trim().orEmpty()
                    mainHandler.post {
                        onPasskeyFallbackRequested(resolvedUrl, resolvedRpId)
                    }
                }
            },
            "DragonPasskeyBridge"
        )

        setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            if (url.isNullOrBlank()) return@setDownloadListener
            dispatchWebViewDownload(
                rawUrl = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimetype = mimetype,
                contentLength = contentLength
            )
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null && !isPopupWindow) {
                    val lvl = when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                        ConsoleMessage.MessageLevel.WARNING -> "WARN"
                        ConsoleMessage.MessageLevel.TIP -> "INFO"
                        else -> "LOG"
                    }
                    browserRepo.recordConsoleMessage(
                        tabId = tab.id,
                        level = lvl,
                        message = consoleMessage.message().orEmpty(),
                        source = consoleMessage.sourceId().orEmpty(),
                        lineNumber = consoleMessage.lineNumber()
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (!isPopupWindow) {
                    browserRepo.updateTabState(tab.id) {
                        it.copy(
                            progress = newProgress,
                            isLoading = newProgress in 1..99,
                            canGoBack = view?.canGoBack() ?: it.canGoBack,
                            canGoForward = view?.canGoForward() ?: it.canGoForward
                        )
                    }
                    val currentUrl = view?.url.orEmpty()
                    if (newProgress >= 15 && currentUrl.isNotBlank() && !BrowserExtensionEngine.isProtectedAuthUrl(currentUrl)) {
                        view?.evaluateJavascript(WEB_PIP_POLYFILL_JS, null)
                        view?.evaluateJavascript(UNIVERSAL_VIDEO_STREAM_ENHANCER_JS, null)
                    }
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!isPopupWindow && !title.isNullOrBlank()) {
                    browserRepo.updateTabState(tab.id) { it.copy(title = title) }
                }
            }

            // Fullscreen HTML5 Video Player support across all streaming websites
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                mainHandler.post {
                    onShowFullscreenVideo(view, callback)
                }
            }

            override fun onHideCustomView() {
                mainHandler.post {
                    onHideFullscreenVideo()
                }
            }

            // Provide a clean transparent poster so <video> tags without poster attribute never flash a grey broken icon
            override fun getDefaultVideoPoster(): Bitmap? {
                return runCatching {
                    Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                }.getOrNull()
            }

            // Grant Protected Media ID (DRM) automatically, but NEVER silently grant Camera/Microphone!
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val originStr = request.origin?.toString().orEmpty().lowercase(Locale.US)
                val resources = request.resources
                val onlyDrm = resources.all { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (onlyDrm) {
                    runCatching { request.grant(resources) }
                    return
                }
                // Reject Camera/Microphone capture requests from non-HTTPS origins immediately
                if (!originStr.startsWith("https://") && !originStr.startsWith("http://localhost")) {
                    MalwareProtectionEngine.recordThreatEvent(
                        context = context,
                        category = "Insecure Hardware Capture Blocked",
                        severity = SecuritySeverity.HIGH,
                        target = originStr.ifBlank { tab.url },
                        reason = "Denied Camera/Microphone request from non-HTTPS page.",
                        blocked = true
                    )
                    runCatching { request.deny() }
                    return
                }
                mainHandler.post {
                    onOriginPermissionRequested(request)
                }
            }

            // Preserve window.opener, OAuth popups, and child download windows via fully configured child WebView
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val parentCtx = view?.context ?: context
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val hitExtra = runCatching { view?.hitTestResult?.extra?.trim() }.getOrNull()
                if (!hitExtra.isNullOrBlank() && warpDns.shouldBlockBrowserAdNavigation(hitExtra, runCatching { Uri.parse(view?.url ?: "").host }.getOrNull())) {
                    return false
                }
                if (!hitExtra.isNullOrBlank() && isLikelyDirectCloudDownloadUrl(hitExtra)) {
                    dispatchWebViewDownload(
                        rawUrl = hitExtra,
                        userAgent = runCatching { view?.settings?.userAgentString }.getOrNull(),
                        contentDisposition = null,
                        mimetype = null,
                        contentLength = -1L,
                        explicitReferer = view?.url
                    )
                    return false
                }

                val childIsHiddenSubPopup = isPopupWindow
                val popupWv = createConfiguredBrowserWebView(
                    context = parentCtx,
                    tab = tab,
                    desktopMode = isCurrentTabDesktopMode(),
                    warpDns = warpDns,
                    browserRepo = browserRepo,
                    mainHandler = mainHandler,
                    onDownloadIntercepted = onDownloadIntercepted,
                    onRendererCrashed = onRendererCrashed,
                    onLongPressLink = onLongPressLink,
                    onShowFullscreenVideo = onShowFullscreenVideo,
                    onHideFullscreenVideo = onHideFullscreenVideo,
                    onOpenInlineStreamPlayer = onOpenInlineStreamPlayer,
                    onInstallChromeWebStoreExtension = onInstallChromeWebStoreExtension,
                    onUpdateTabUrl = onUpdateTabUrl,
                    onOpenWindowPopup = onOpenWindowPopup,
                    onAttachSubPopup = onAttachSubPopup,
                    onCloseWindowPopup = onCloseWindowPopup,
                    onInspectorElementPicked = onInspectorElementPicked,
                    onPasskeyFallbackRequested = onPasskeyFallbackRequested,
                    onSecurityThreatBlocked = onSecurityThreatBlocked,
                    onOriginPermissionRequested = onOriginPermissionRequested,
                    onMediaPlaybackChanged = onMediaPlaybackChanged,
                    onWebPipRequested = onWebPipRequested,
                    isPopupWindow = true,
                    isHiddenSubPopup = childIsHiddenSubPopup,
                    parentPageUrlProvider = {
                        view?.url?.takeIf { it.isNotBlank() && !it.startsWith("about:", true) }
                            ?: this@apply.url?.takeIf { it.isNotBlank() && !it.startsWith("about:", true) }
                            ?: parentPageUrlProvider()
                            ?: tab.url
                    }
                )
                transport.webView = popupWv
                resultMsg.sendToTarget()
                mainHandler.post {
                    if (childIsHiddenSubPopup) {
                        onAttachSubPopup(popupWv)
                    } else {
                        onOpenWindowPopup(popupWv)
                    }
                }
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                if (window != null) {
                    mainHandler.post { onCloseWindowPopup(window) }
                }
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return false
                if (target.startsWith("magnet:", ignoreCase = true) ||
                    target.substringBefore("?").lowercase(Locale.US).endsWith(".torrent")
                ) {
                    val isMagnet = target.startsWith("magnet:", ignoreCase = true)
                    val title = if (isMagnet) {
                        runCatching { com.example.data.downloader.TorrentEngine.parseMagnetUri(target).displayName }.getOrNull()
                            ?: "Magnet_Torrent"
                    } else {
                        target.substringBefore("?").substringAfterLast("/").substringBeforeLast(".").ifBlank { "Torrent" }
                    }
                    val format = MediaFormatOption(
                        id = "torrent_p2p_payload",
                        qualityLabel = if (isMagnet) "Download Torrent Content (Magnet P2P)" else "Download Torrent (.torrent)",
                        container = "torrent",
                        mimeType = "application/x-bittorrent",
                        isAudioOnly = false,
                        isOriginalBest = true,
                        streamUrl = target,
                        isGenericFile = true,
                        fileCategoryLabel = "Torrent"
                    )
                    val info = MediaInfo(
                        originalUrl = target,
                        canonicalUrl = target,
                        title = title,
                        creator = "BitTorrent P2P",
                        durationSeconds = 0L,
                        durationFormatted = "Torrent",
                        thumbnailUrl = "",
                        platform = MediaPlatform.TORRENT,
                        videoFormats = emptyList(),
                        audioFormats = emptyList(),
                        extractorSource = "Browser Torrent Interceptor",
                        fileFormats = listOf(format)
                    )
                    downloadTriggeredOnThisPopup = true
                    mainHandler.post {
                        onDownloadIntercepted(info, format)
                        if (isPopupWindow && (!hasCommittedHtmlPage || isHiddenSubPopup)) {
                            onCloseWindowPopup(this@apply)
                        }
                    }
                    return true
                }

                if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
                    val urlVerdict = MalwareProtectionEngine.inspectUrl(target, context)
                    if (urlVerdict.isBlocked) {
                        MalwareProtectionEngine.recordThreatEvent(
                            context = context,
                            category = urlVerdict.category,
                            severity = urlVerdict.severity,
                            target = target,
                            reason = urlVerdict.reason,
                            blocked = true
                        )
                        mainHandler.post {
                            if (isPopupWindow && !hasCommittedHtmlPage) {
                                onCloseWindowPopup(this@apply)
                            } else {
                                onSecurityThreatBlocked(target, urlVerdict)
                            }
                        }
                        return true
                    }
                    if (warpDns.shouldBlockBrowserAdNavigation(target, runCatching { Uri.parse(view?.url ?: "").host }.getOrNull())) {
                        if (isPopupWindow && !hasCommittedHtmlPage) {
                            mainHandler.post { onCloseWindowPopup(this@apply) }
                        }
                        return true
                    }
                    if (BrowserExtensionEngine.isChromeWebStoreCrxUrl(target)) {
                        downloadTriggeredOnThisPopup = true
                        mainHandler.post {
                            onInstallChromeWebStoreExtension(target, null)
                            if (isPopupWindow && (!hasCommittedHtmlPage || isHiddenSubPopup)) {
                                onCloseWindowPopup(this@apply)
                            }
                        }
                        return true
                    }
                    if (isLikelyDirectCloudDownloadUrl(target)) {
                        dispatchWebViewDownload(
                            rawUrl = target,
                            userAgent = runCatching { view?.settings?.userAgentString ?: settings.userAgentString }.getOrNull(),
                            contentDisposition = null,
                            mimetype = null,
                            contentLength = -1L,
                            explicitReferer = view?.url
                        )
                        return true
                    }
                    val targetHost = request.url?.host
                    if (warpDns.isAnyCustomDnsActive && !targetHost.isNullOrBlank()) {
                        warpDns.prewarmHostAsync(targetHost)
                    }
                    return false
                }

                // Allow extension internal URLs (chrome-extension://, moz-extension://) to load inside tabs via virtual origin
                val translatedExtUrl = browserRepo.extensionEngine.translateExtensionSchemeToVirtualUrl(target)
                if (translatedExtUrl != null) {
                    mainHandler.post {
                        view?.loadUrl(translatedExtUrl)
                    }
                    return true
                }

                // Block file://, intent://, and unsafe external schemes via MalwareProtectionEngine
                val nonHttpVerdict = MalwareProtectionEngine.inspectUrl(target, context)
                if (nonHttpVerdict.isBlocked) {
                    MalwareProtectionEngine.recordThreatEvent(
                        context = context,
                        category = nonHttpVerdict.category,
                        severity = nonHttpVerdict.severity,
                        target = target,
                        reason = nonHttpVerdict.reason,
                        blocked = true
                    )
                    return true
                }

                // Allow only internal WebView schemes (about:, blob:) normally
                if (target.startsWith("about:", ignoreCase = true) ||
                    target.startsWith("blob:", ignoreCase = true) ||
                    target.startsWith("data:", ignoreCase = true) ||
                    target.startsWith("javascript:", ignoreCase = true)
                ) {
                    return false
                }
                return true
            }

            private fun inferNetworkResourceType(reqUrl: String, headers: Map<String, String>?, isMainFrame: Boolean): String {
                if (isMainFrame) return "Document"
                val clean = reqUrl.substringBefore('?').substringBefore('#').lowercase(Locale.US)
                val accept = headers?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value.orEmpty().lowercase(Locale.US)
                val fetchDest = headers?.entries?.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value.orEmpty().lowercase(Locale.US)
                return when {
                    clean.endsWith(".css") || accept.contains("text/css") || fetchDest == "style" -> "Stylesheet"
                    clean.endsWith(".js") || clean.endsWith(".mjs") || fetchDest == "script" -> "Script"
                    clean.endsWith(".png") || clean.endsWith(".jpg") || clean.endsWith(".jpeg") ||
                        clean.endsWith(".webp") || clean.endsWith(".gif") || clean.endsWith(".svg") ||
                        clean.endsWith(".ico") || fetchDest == "image" -> "Image"
                    clean.endsWith(".mp4") || clean.endsWith(".m3u8") || clean.endsWith(".mpd") ||
                        clean.endsWith(".webm") || clean.endsWith(".mp3") || clean.endsWith(".m4a") ||
                        fetchDest in setOf("video", "audio") -> "Media"
                    clean.endsWith(".woff") || clean.endsWith(".woff2") || clean.endsWith(".ttf") ||
                        clean.endsWith(".otf") || fetchDest == "font" -> "Font"
                    fetchDest in setOf("empty", "fetch") || accept.contains("application/json") ||
                        headers?.keys?.any { it.equals("X-Requested-With", true) } == true -> "XHR/Fetch"
                    else -> "Other"
                }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                val lower = reqUrl.lowercase(Locale.US)
                val reqHost = request.url?.host
                val isHttp = lower.startsWith("http://") || lower.startsWith("https://")
                val method = request.method ?: "GET"
                val resType = inferNetworkResourceType(reqUrl, request.requestHeaders, request.isForMainFrame)
                val activePageHost = runCatching {
                    val activeTabUrl = browserRepo.tabsFlow.value.find { it.id == tab.id }?.url ?: tab.url
                    Uri.parse(activeTabUrl).host
                }.getOrNull()

                if (!isPopupWindow && isHttp && request.isForMainFrame) {
                    browserRepo.clearNetworkLogs(tab.id)
                }

                // Malware / Phishing / Cryptojacker Sub-resource & Navigation Interception
                if (isHttp && !lower.contains("dragon-ext.local")) {
                    val reqVerdict = MalwareProtectionEngine.inspectUrl(reqUrl, context)
                    if (reqVerdict.isBlocked) {
                        MalwareProtectionEngine.recordThreatEvent(
                            context = context,
                            category = reqVerdict.category,
                            severity = reqVerdict.severity,
                            target = reqUrl,
                            reason = reqVerdict.reason,
                            blocked = true
                        )
                        if (!isPopupWindow) {
                            browserRepo.recordNetworkRequest(tab.id, reqUrl, method, resType, "MalwareBlocked")
                        }
                        if (request.isForMainFrame) {
                            mainHandler.post { onSecurityThreatBlocked(reqUrl, reqVerdict) }
                        }
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }

                // 0. Serve extension web_accessible_resources (`chrome-extension://`, `https://dragon-ext.local/`)
                // and enforce active extensions' declarative_net_request rules
                val isExtTabContext = activePageHost?.endsWith(".dragon-ext.local", ignoreCase = true) == true ||
                    activePageHost.equals("dragon-ext.local", ignoreCase = true)
                val extIntercepted = browserRepo.extensionEngine.interceptExtensionRequest(
                    requestUrl = reqUrl,
                    pageHost = activePageHost,
                    isMainFrame = request.isForMainFrame,
                    requestMethod = method,
                    requestHeaders = request.requestHeaders,
                    isExtensionPageContext = isExtTabContext
                )
                if (extIntercepted != null) {
                    if (!isPopupWindow && isHttp && !lower.contains("dragon-ext.local")) {
                        browserRepo.recordNetworkRequest(tab.id, reqUrl, method, resType, "Ext DNR")
                    }
                    return extIntercepted
                }

                // Capture media streams (.m3u8, .mpd, .mp4, .webm, .mkv, .mp3, .m4a, etc.) when Media Grabber is enabled
                if (browserRepo.mediaGrabberEnabledFlow.value &&
                    isHttp &&
                    !WebViewMediaExtractor.isPreviewUrl(reqUrl) &&
                    !WebViewMediaExtractor.isHtmlEmbedPageUrl(reqUrl)
                ) {
                    val normalized = WebViewMediaExtractor.normalizeInterceptedStreamUrl(reqUrl)
                    val detectedKind = when {
                        WebViewMediaExtractor.isLikelyVideoStreamUrl(lower) -> GrabbedMediaKind.VIDEO
                        WebViewMediaExtractor.isLikelyAudioStreamUrl(lower) -> GrabbedMediaKind.AUDIO
                        WebViewMediaExtractor.isLikelyDownloadableFileUrl(lower) -> GrabbedMediaKind.FILE
                        else -> null
                    }
                    if (detectedKind != null) {
                        val ext = normalized.substringBefore("?").substringAfterLast('.', "")
                            .lowercase(Locale.US).takeIf { it.length in 2..5 } ?: when (detectedKind) {
                            GrabbedMediaKind.VIDEO -> "mp4"
                            GrabbedMediaKind.AUDIO -> "m4a"
                            GrabbedMediaKind.FILE -> "bin"
                        }
                        val hostLabel = reqHost?.removePrefix("www.") ?: "Stream"
                        mainHandler.post {
                            val activeTitle = browserRepo.tabsFlow.value.find { it.id == tab.id }?.title ?: "Media"
                            browserRepo.recordGrabbedMedia(
                                tabId = tab.id,
                                candidate = GrabbedMediaCandidate(
                                    url = normalized,
                                    title = activeTitle,
                                    kind = detectedKind,
                                    containerExt = ext,
                                    sourceLabel = hostLabel
                                )
                            )
                        }
                    }
                }

                val activeProxy = BrowserProxyManager.activeNetworkProxy
                val localBridgeActive = BrowserProxyManager.isLocalProxyActiveForWebView
                if (warpDns.isAnyCustomDnsActive && !reqHost.isNullOrBlank() && isHttp) {
                    // 1. If AdBlocker DNS (or sinkholing Custom DNS) is active, return clean empty cosmetic response for blocked ads
                    if (warpDns.shouldBlockBrowserAdRequest(reqHost, reqUrl, request.isForMainFrame)) {
                        if (!isPopupWindow) {
                            browserRepo.recordNetworkRequest(tab.id, reqUrl, method, resType, "AdBlocked")
                        }
                        return warpDns.buildAdBlockedWebResourceResponse(reqUrl)
                    }

                    // 2. Fallback OkHttp DNS tunnel ONLY on devices where WebView lacks PROXY_OVERRIDE
                    // (when localBridgeActive == true, 100% of WebView GET/POST/PUT/WS/HTTP2/TLS traffic
                    // already flows natively through LocalBrowserProxyServer using active DNS & Proxy!)
                    if (!localBridgeActive && activeProxy == null &&
                        warpDns.shouldTunnelBrowserRequest(reqHost, method, request.requestHeaders)
                    ) {
                        val tunneledResponse = warpDns.executeBrowserTunnelRequest(
                            requestUrl = reqUrl,
                            method = method,
                            requestHeaders = request.requestHeaders,
                            isMainFrame = request.isForMainFrame,
                            defaultUserAgent = resolveBrowserUserAgent(context, isCurrentTabDesktopMode()),
                            onMainFrameDownloadDetected = { dl ->
                                dispatchWebViewDownload(
                                    rawUrl = dl.url,
                                    userAgent = dl.userAgent,
                                    contentDisposition = dl.contentDisposition,
                                    mimetype = dl.mimeType,
                                    contentLength = dl.contentLength,
                                    explicitReferer = activeTabUrlForReferer()
                                )
                            }
                        )
                        if (tunneledResponse != null) {
                            if (!isPopupWindow) {
                                browserRepo.recordNetworkRequest(tab.id, reqUrl, method, resType, "DNS Tunnel")
                            }
                            return tunneledResponse
                        }
                    }
                }

                if (!isPopupWindow && isHttp && !lower.contains("dragon-ext.local")) {
                    val routedViaProxy = activeProxy != null && activeProxy.shouldProxyHost(reqHost)
                    val statusTag = when {
                        routedViaProxy && warpDns.isAnyCustomDnsActive ->
                            "Proxy ${activeProxy!!.type.id} + ${warpDns.dnsMode.shortBadge}"
                        routedViaProxy ->
                            "Proxy ${activeProxy!!.type.id}"
                        localBridgeActive && warpDns.isAnyCustomDnsActive ->
                            "DNS ${warpDns.dnsMode.shortBadge}"
                        else -> "Allowed"
                    }
                    browserRepo.recordNetworkRequest(tab.id, reqUrl, method, resType, statusTag)
                }

                return super.shouldInterceptRequest(view, request)
            }

            private var lastProxyAuthAttemptKey: String? = null
            private var lastProxyAuthAttemptMs: Long = 0L

            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?
            ) {
                val activeProxy = BrowserProxyManager.activeNetworkProxy
                val isProxyHostChallenge = activeProxy != null &&
                    !BrowserProxyManager.isLocalProxyActiveForWebView &&
                    host?.trim().equals(activeProxy.host.trim(), ignoreCase = true)
                if (isProxyHostChallenge && activeProxy!!.hasAuth() && handler != null) {
                    val key = "${activeProxy.id}|${host.orEmpty()}|${realm.orEmpty()}"
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (lastProxyAuthAttemptKey != key || (now - lastProxyAuthAttemptMs) > 4000L) {
                        lastProxyAuthAttemptKey = key
                        lastProxyAuthAttemptMs = now
                        handler.proceed(activeProxy.username, activeProxy.password)
                        return
                    }
                }
                super.onReceivedHttpAuthRequest(view, handler, host, realm)
            }

            private fun activeTabUrlForReferer(): String? {
                return browserRepo.tabsFlow.value.find { it.id == tab.id }?.url ?: parentPageUrlProvider() ?: tab.url
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!url.isNullOrBlank()) {
                    if (warpDns.shouldBlockBrowserAdNavigation(url, parentPageUrlProvider())) {
                        runCatching { view?.stopLoading() }
                        if (isPopupWindow && !hasCommittedHtmlPage) {
                            mainHandler.post { onCloseWindowPopup(this@apply) }
                        }
                        return
                    }
                    if (isLikelyDirectCloudDownloadUrl(url)) {
                        dispatchWebViewDownload(
                            rawUrl = url,
                            userAgent = runCatching { view?.settings?.userAgentString ?: settings.userAgentString }.getOrNull(),
                            contentDisposition = null,
                            mimetype = null,
                            contentLength = -1L,
                            explicitReferer = parentPageUrlProvider()
                        )
                        runCatching { view?.stopLoading() }
                        return
                    }
                    val host = runCatching { Uri.parse(url).host }.getOrNull()
                    if (warpDns.isAnyCustomDnsActive && !host.isNullOrBlank()) {
                        warpDns.prewarmHostAsync(host)
                    }
                    if (!isPopupWindow) {
                        if (BrowserPipController.stateFlow.value.floatingPipTabId != tab.id) {
                            onMediaPlaybackChanged(tab.id, false, false, "", "", 16, 9, 0f, 0f, 1f, 1f)
                        }
                        browserRepo.clearGrabbedMediaForTab(tab.id)
                        browserRepo.updateTabState(tab.id) {
                            it.copy(
                                url = url,
                                isLoading = true,
                                progress = 15,
                                canGoBack = view?.canGoBack() ?: false,
                                canGoForward = view?.canGoForward() ?: false
                            )
                        }
                    }
                    if (view != null && isCurrentTabDesktopMode()) {
                        DesktopViewportController.syncWebViewViewport(
                            webView = view,
                            desktopMode = true,
                            forceRefit = true,
                            measureOverflow = false
                        )
                    }
                    // Inject WebAuthn / Passkey Polyfill & Wrapper across all pages (including accounts/auth origins)
                    view?.evaluateJavascript(PASSKEY_WEBAUTHN_HOOK_JS, null)
                    // Inject active extensions' document_start content scripts & CSS userstyles
                    val startPayload = browserRepo.extensionEngine.buildInjectionPayloadForUrl(url, "document_start")
                    if (startPayload.isNotBlank()) {
                        view?.evaluateJavascript(startPayload, null)
                    }
                    if (warpDns.dnsMode == DnsProviderMode.ADBLOCKER_DNS) {
                        view?.evaluateJavascript(com.example.data.downloader.CloudflareWarpDns.ADBLOCKER_COSMETIC_CLEANUP_JS, null)
                    }
                    if (!BrowserExtensionEngine.isProtectedAuthUrl(url)) {
                        view?.evaluateJavascript(UNIVERSAL_DOWNLOAD_HOOK_JS, null)
                        view?.evaluateJavascript(WEB_PIP_POLYFILL_JS, null)
                        view?.evaluateJavascript(UNIVERSAL_VIDEO_STREAM_ENHANCER_JS, null)
                    }
                }
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                if (view != null && isCurrentTabDesktopMode()) {
                    DesktopViewportController.syncWebViewViewport(
                        webView = view,
                        desktopMode = true,
                        forceRefit = false,
                        measureOverflow = false
                    )
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!isPopupWindow && !url.isNullOrBlank()) {
                    browserRepo.updateTabState(tab.id) {
                        it.copy(
                            url = url,
                            canGoBack = view?.canGoBack() ?: it.canGoBack,
                            canGoForward = view?.canGoForward() ?: it.canGoForward
                        )
                    }
                    if (view != null && isCurrentTabDesktopMode()) {
                        DesktopViewportController.syncWebViewViewport(
                            webView = view,
                            desktopMode = true,
                            forceRefit = false,
                            measureOverflow = false
                        )
                    }
                    if (!BrowserExtensionEngine.isProtectedAuthUrl(url)) {
                        view?.evaluateJavascript(WEB_PIP_POLYFILL_JS, null)
                        view?.evaluateJavascript(UNIVERSAL_VIDEO_STREAM_ENHANCER_JS, null)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val finalUrl = url ?: return
                val pageTitle = view?.title
                if (!isPopupWindow) {
                    browserRepo.updateTabState(tab.id) {
                        it.copy(
                            url = finalUrl,
                            title = pageTitle?.takeIf { t -> t.isNotBlank() } ?: it.title,
                            isLoading = false,
                            progress = 100,
                            canGoBack = view?.canGoBack() ?: false,
                            canGoForward = view?.canGoForward() ?: false
                        )
                    }
                    browserRepo.recordVisit(finalUrl, pageTitle, tab.isIncognito)
                } else if (finalUrl.startsWith("http", ignoreCase = true) && !isLikelyDirectCloudDownloadUrl(finalUrl)) {
                    hasCommittedHtmlPage = true
                    if (isHiddenSubPopup && !downloadTriggeredOnThisPopup) {
                        mainHandler.post {
                            onOpenWindowPopup(this@apply)
                        }
                    }
                }
                if (!tab.isIncognito) {
                    CookieManager.getInstance().flush()
                    browserRepo.googleSyncManager.inspectActiveWebViewGoogleCookiesAsync(
                        view?.settings?.userAgentString
                    )
                    view?.evaluateJavascript(GOOGLE_ACCOUNT_AND_PASSWORD_SYNC_HOOK_JS, null)
                    view?.evaluateJavascript(PASSKEY_WEBAUTHN_HOOK_JS, null)
                    // Auto-fill saved credentials on matching domains when login fields are empty
                    val host = runCatching { Uri.parse(finalUrl).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")
                    if (host.isNotBlank()) {
                        val match = browserRepo.savedPasswordsFlow.value.firstOrNull { entry ->
                            if (BrowserPasskeyManager.isPasskeySecret(entry.password)) return@firstOrNull false
                            val savedDomain = entry.domain.lowercase().removePrefix("www.")
                            savedDomain.isNotBlank() && (host == savedDomain || host.endsWith(".$savedDomain") || savedDomain.endsWith(".$host"))
                        }
                        if (match != null && match.password.isNotEmpty()) {
                            val escapedUser = JSONObject.quote(match.username)
                            val escapedPass = JSONObject.quote(match.password)
                            val autoFillJs = """
                                (function() {
                                  try {
                                    var u = $escapedUser;
                                    var p = $escapedPass;
                                    var passInputs = document.querySelectorAll('input[type="password"]');
                                    if (!passInputs || passInputs.length === 0) return;
                                    for (var i = 0; i < passInputs.length; i++) {
                                      var pi = passInputs[i];
                                      if (!pi.value) {
                                        pi.value = p;
                                        pi.dispatchEvent(new Event('input', { bubbles: true }));
                                        pi.dispatchEvent(new Event('change', { bubbles: true }));
                                      }
                                      var scope = pi.form || document;
                                      var uInputs = scope.querySelectorAll('input[type="email"], input[autocomplete="username"], input[name*="user" i], input[name*="email" i], input[name*="login" i], input[id*="user" i], input[id*="email" i], input[type="text"]');
                                      for (var j = 0; j < uInputs.length; j++) {
                                        var ui = uInputs[j];
                                        if (ui !== pi && ui.type !== 'password' && ui.type !== 'hidden' && !ui.value && u) {
                                          ui.value = u;
                                          ui.dispatchEvent(new Event('input', { bubbles: true }));
                                          ui.dispatchEvent(new Event('change', { bubbles: true }));
                                          break;
                                        }
                                      }
                                    }
                                  } catch (e) {}
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(autoFillJs, null)
                        }
                    }
                }
                if (warpDns.dnsMode == DnsProviderMode.ADBLOCKER_DNS) {
                    view?.evaluateJavascript(com.example.data.downloader.CloudflareWarpDns.ADBLOCKER_COSMETIC_CLEANUP_JS, null)
                }
                // Inject active extensions' document_end & document_idle content scripts, userscripts, and CSS
                val endPayload = browserRepo.extensionEngine.buildInjectionPayloadForUrl(finalUrl, "document_end")
                if (endPayload.isNotBlank()) {
                    view?.evaluateJavascript(endPayload, null)
                }
                if (view != null && isCurrentTabDesktopMode()) {
                    DesktopViewportController.syncWebViewViewport(
                        webView = view,
                        desktopMode = true,
                        forceRefit = false,
                        measureOverflow = true
                    )
                    mainHandler.postDelayed({
                        if (isCurrentTabDesktopMode() && !BrowserPipController.isInPipModeFlow.value) {
                            DesktopViewportController.syncWebViewViewport(
                                webView = view,
                                desktopMode = true,
                                forceRefit = false,
                                measureOverflow = true
                            )
                        }
                    }, 400L)
                }
                view?.evaluateJavascript(UNIVERSAL_DOWNLOAD_HOOK_JS, null)
                if (!BrowserExtensionEngine.isProtectedAuthUrl(finalUrl)) {
                    // Capture unhandled page errors/rejections cleanly when Page Inspector is enabled
                    if (browserRepo.pageInspectorEnabledFlow.value) {
                        view?.evaluateJavascript(INSPECTOR_ERROR_HOOK_JS, null)
                    }
                    // Inject W3C Picture-in-Picture Polyfill & Universal Video Stream Enhancer (HLS.js / DASH.js + capturing playback observer)
                    view?.evaluateJavascript(WEB_PIP_POLYFILL_JS, null)
                    view?.evaluateJavascript(UNIVERSAL_VIDEO_STREAM_ENHANCER_JS, null)
                    // When browsing Chrome Web Store, hook store "Add to Chrome" CTA buttons to install directly
                    if (finalUrl.contains("chromewebstore.google.com", ignoreCase = true) ||
                        finalUrl.contains("chrome.google.com/webstore", ignoreCase = true)
                    ) {
                        view?.evaluateJavascript(BrowserExtensionEngine.buildChromeWebStorePageHookJs(), null)
                    }
                    if (browserRepo.mediaGrabberEnabledFlow.value) {
                        view?.evaluateJavascript(DOM_MEDIA_SCANNER_JS, null)
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val failedUrl = request.url?.toString().orEmpty()
                    val fallbackHttpUrl = MalwareProtectionEngine.consumeAutoHttpsUpgradeFallback(failedUrl)
                    if (fallbackHttpUrl != null) {
                        mainHandler.post {
                            view?.loadUrl(fallbackHttpUrl)
                        }
                    }
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                val errUrl = error?.url?.takeIf { it.isNotBlank() } ?: view?.url.orEmpty()

                // 1. If this HTTPS navigation was auto-upgraded from an HTTP-only site, seamlessly fall back to HTTP
                val fallbackHttpUrl = MalwareProtectionEngine.consumeAutoHttpsUpgradeFallback(errUrl)
                if (fallbackHttpUrl != null) {
                    handler?.cancel()
                    mainHandler.post {
                        view?.loadUrl(fallbackHttpUrl)
                    }
                    return
                }

                // 2. If user explicitly clicked "Proceed Anyway" for this site in the current session, honor it
                if (MalwareProtectionEngine.isUrlAllowedForSession(errUrl)) {
                    handler?.proceed()
                    return
                }

                // 3. Cancel third-party sub-resource SSL errors silently so they never block the main website
                val errHost = runCatching { Uri.parse(errUrl).host?.lowercase(Locale.US)?.removePrefix("www.") }.getOrNull().orEmpty()
                val pageHost = runCatching { Uri.parse(view?.url.orEmpty()).host?.lowercase(Locale.US)?.removePrefix("www.") }.getOrNull().orEmpty()
                if (errHost.isNotBlank() && pageHost.isNotBlank() && errHost != pageHost) {
                    handler?.cancel()
                    return
                }

                handler?.cancel()
                val verdict = UrlSecurityVerdict(
                    isBlocked = true,
                    severity = SecuritySeverity.CRITICAL,
                    category = "Untrusted SSL Certificate",
                    reason = "Blocked insecure connection: invalid or tampered TLS/SSL certificate on $errUrl.",
                    host = errHost
                )
                MalwareProtectionEngine.recordThreatEvent(
                    context = context,
                    category = verdict.category,
                    severity = verdict.severity,
                    target = errUrl,
                    reason = verdict.reason,
                    blocked = true
                )
                mainHandler.post {
                    onSecurityThreatBlocked(errUrl, verdict)
                }
            }

            override fun onSafeBrowsingHit(
                view: WebView?,
                request: WebResourceRequest?,
                threatType: Int,
                callback: SafeBrowsingResponse?
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    runCatching { callback?.backToSafety(true) }
                }
                val threatUrl = request?.url?.toString().orEmpty()
                val verdict = UrlSecurityVerdict(
                    isBlocked = true,
                    severity = SecuritySeverity.CRITICAL,
                    category = "Google Safe Browsing Threat",
                    reason = "Android WebView Safe Browsing flagged dangerous malware/phishing content ($threatType).",
                    host = request?.url?.host.orEmpty()
                )
                MalwareProtectionEngine.recordThreatEvent(
                    context = context,
                    category = verdict.category,
                    severity = verdict.severity,
                    target = threatUrl,
                    reason = verdict.reason,
                    blocked = true
                )
                mainHandler.post {
                    onSecurityThreatBlocked(threatUrl, verdict)
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                mainHandler.post {
                    if (isPopupWindow) {
                        onCloseWindowPopup(this@apply)
                    } else {
                        onRendererCrashed(tab.id, view)
                    }
                }
                return true
            }
        }
    }
}

@Composable
private fun BrowserTabsDialog(
    tabs: List<BrowserTab>,
    activeTabId: String,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewNormalTab: () -> Unit,
    onNewIncognitoTab: () -> Unit,
    onCloseAllTabs: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("browser_tabs_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Open Tabs (${tabs.size})", fontWeight = FontWeight.Black, fontSize = 17.sp)
                TextButton(onClick = onCloseAllTabs) {
                    Text("Close All", color = DangerRed, fontSize = 12.sp)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onNewNormalTab,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Tab", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onNewIncognitoTab,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Incognito", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        val isSelected = tab.id == activeTabId
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                tab.isIncognito -> Color(0xFF2B1E3D)
                                isSelected -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            },
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onSelectTab(tab.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (tab.isIncognito) Icons.Default.VisibilityOff else Icons.Default.Public,
                                    contentDescription = null,
                                    tint = if (tab.isIncognito) Color(0xFFD8B4FE) else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tab.title.ifBlank { "Google" },
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (tab.isIncognito) Color.White else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = tab.url,
                                        fontSize = 10.sp,
                                        color = if (tab.isIncognito) Color(0xFFD8B4FE).copy(alpha = 0.8f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onCloseTab(tab.id) },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Tab",
                                        tint = if (tab.isIncognito) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

@Composable
private fun BrowserHistoryDialog(
    history: List<BrowserHistoryEntry>,
    onOpenUrl: (String) -> Unit,
    onDeleteItem: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(history, searchQuery) {
        if (searchQuery.isBlank()) history
        else history.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.url.contains(searchQuery, ignoreCase = true)
        }
    }
    val formatter = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.US) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("browser_history_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Browsing History", fontWeight = FontWeight.Black, fontSize = 17.sp)
                if (history.isNotEmpty()) {
                    TextButton(onClick = onClearAll) {
                        Text("Clear All", color = DangerRed, fontSize = 12.sp)
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search history...", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (filtered.isEmpty()) {
                    Text(
                        text = if (history.isEmpty()) "No browsing history yet. Incognito visits are never recorded."
                        else "No matching history entries.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filtered, key = { it.id }) { item ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onOpenUrl(item.url) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${formatter.format(Date(item.visitedAtMs))} • ${item.url}",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteItem(item.id) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete History Entry",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun BrowserBookmarksDialog(
    bookmarks: List<BrowserBookmark>,
    currentUrl: String,
    currentTitle: String,
    isCurrentBookmarked: Boolean,
    onAddManualBookmark: (url: String, title: String) -> Unit = { _, _ -> },
    onToggleCurrentBookmark: () -> Unit,
    onImportBookmarksClick: () -> Unit = {},
    onOpenUrl: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showAddBookmarkForm by remember { mutableStateOf(false) }
    var manualBookmarkTitle by remember(currentTitle) { mutableStateOf(currentTitle) }
    var manualBookmarkUrl by remember(currentUrl) { mutableStateOf(currentUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("browser_bookmarks_dialog"),
        title = {
            Text("Saved Bookmarks (${bookmarks.size})", fontWeight = FontWeight.Black, fontSize = 17.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onToggleCurrentBookmark,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (isCurrentBookmarked) Icons.Default.Star else Icons.Default.StarOutline,
                            contentDescription = null,
                            tint = Color(0xFFFACC15),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isCurrentBookmarked) "Remove Current" else "Bookmark Page",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = { showAddBookmarkForm = !showAddBookmarkForm },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_toggle_add_bookmark_form"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (showAddBookmarkForm) Icons.Default.Close else Icons.Default.BookmarkAdd,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showAddBookmarkForm) "Cancel" else "Add Bookmark",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen,
                            maxLines = 1
                        )
                    }
                }

                if (showAddBookmarkForm) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_bookmark_form_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "New Bookmark",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen
                            )
                            OutlinedTextField(
                                value = manualBookmarkTitle,
                                onValueChange = { manualBookmarkTitle = it },
                                placeholder = { Text("Bookmark Title", fontSize = 11.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_new_bookmark_title")
                            )
                            OutlinedTextField(
                                value = manualBookmarkUrl,
                                onValueChange = { manualBookmarkUrl = it },
                                placeholder = { Text("https://example.com", fontSize = 11.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_new_bookmark_url")
                            )
                            Button(
                                onClick = {
                                    if (manualBookmarkUrl.isNotBlank()) {
                                        onAddManualBookmark(manualBookmarkUrl.trim(), manualBookmarkTitle.trim())
                                        showAddBookmarkForm = false
                                    }
                                },
                                enabled = manualBookmarkUrl.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.White),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("btn_save_new_bookmark")
                            ) {
                                Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save Bookmark", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = onImportBookmarksClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_import_bookmarks_dialog"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.45f))
                ) {
                    Icon(
                        imageVector = Icons.Default.FileOpen,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Import Bookmarks (.html / .json)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (bookmarks.isEmpty()) {
                    Text(
                        text = "No saved bookmarks yet.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(bookmarks, key = { it.id }) { bm ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onOpenUrl(bm.url) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFFACC15),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = bm.title,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = bm.url,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteBookmark(bm.id) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete Bookmark",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun BrowserMediaGrabberDialog(
    pageTitle: String,
    pageUrl: String,
    candidates: List<GrabbedMediaCandidate>,
    isGrabberEnabled: Boolean = true,
    onToggleGrabberEnabled: (Boolean) -> Unit = {},
    downloadManager: DragonDownloadManager,
    onRefreshScan: () -> Unit,
    onSelectCandidateStream: (GrabbedMediaCandidate) -> Unit,
    onSelectCandidateDownload: (GrabbedMediaCandidate) -> Unit,
    onSelectExtractedStream: (MediaInfo, MediaFormatOption) -> Unit,
    onSelectExtractedDownload: (MediaInfo, MediaFormatOption) -> Unit,
    onDismiss: () -> Unit
) {
    var isDeepScanning by remember { mutableStateOf(false) }
    var deepScanError by remember { mutableStateOf<String?>(null) }
    var deepExtractedInfo by remember { mutableStateOf<MediaInfo?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("browser_media_grabber_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = if (isGrabberEnabled) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Media Grabber & Streamer", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Text(
                        text = pageTitle.ifBlank { pageUrl },
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = isGrabberEnabled,
                    onCheckedChange = onToggleGrabberEnabled,
                    modifier = Modifier.testTag("switch_media_grabber_dialog")
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isGrabberEnabled) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = WarningOrange.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Media Grabber is OFF. Auto-sniffing and the floating media button are paused.",
                            fontSize = 11.sp,
                            color = WarningOrange,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRefreshScan,
                        enabled = isGrabberEnabled,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Rescan Page", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            isDeepScanning = true
                            deepScanError = null
                            downloadManager.fetchMediaInfo(
                                url = pageUrl,
                                onStageChanged = {},
                                onResult = { info, _, err ->
                                    isDeepScanning = false
                                    if (info != null) {
                                        deepExtractedInfo = info
                                    } else {
                                        deepScanError = err ?: "No deep stream extracted on this URL"
                                    }
                                }
                            )
                        },
                        enabled = !isDeepScanning,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isDeepScanning) "Extracting..." else "Deep Extract",
                            color = Color(0xFF03131B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                if (deepScanError != null) {
                    Text(
                        text = deepScanError.orEmpty(),
                        fontSize = 11.sp,
                        color = WarningOrange
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 310.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    deepExtractedInfo?.let { info ->
                        items(info.allFormats.take(8), key = { "deep_${it.id}_${it.streamUrl}" }) { fmt ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = SuccessGreen.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${fmt.qualityLabel} • ${fmt.container.uppercase(Locale.US)}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = SuccessGreen
                                        )
                                        Text(
                                            text = "${info.title} • ${fmt.formattedSize(info.durationSeconds)}",
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (!fmt.isGenericFile) {
                                        OutlinedButton(
                                            onClick = { onSelectExtractedStream(info, fmt) },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                            modifier = Modifier.padding(end = 6.dp)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp), tint = NeonCyan)
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text("Stream", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Button(
                                        onClick = { onSelectExtractedDownload(info, fmt) },
                                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("Save", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    if (candidates.isEmpty() && deepExtractedInfo == null) {
                        item {
                            Text(
                                text = "Play a video on the page or tap 'Deep Extract' to stream or grab video, audio, or downloadable files.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 14.dp)
                            )
                        }
                    } else {
                        items(candidates, key = { it.url }) { item ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = NeonCyan.copy(alpha = 0.2f)
                                            ) {
                                                Text(
                                                    text = "${item.kind.label} • ${item.containerExt.uppercase(Locale.US)}",
                                                    fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NeonCyan,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                            Text(
                                                text = item.sourceLabel,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = item.url,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    if (item.kind != GrabbedMediaKind.FILE) {
                                        OutlinedButton(
                                            onClick = { onSelectCandidateStream(item) },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                            modifier = Modifier.padding(end = 4.dp)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = "Stream", modifier = Modifier.size(14.dp), tint = NeonCyan)
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text("Stream", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    FilledTonalButton(
                                        onClick = { onSelectCandidateDownload(item) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Get", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/**
 * Built-in Universal Online Video & Audio Stream Player Overlay.
 * Supports HLS (.m3u8 via hls.js + native MSE), DASH (.mpd via dash.js), MP4, WebM, MKV, OGG, MP3, M4A
 * and stays inside MainActivity's Compose tree so Picture-in-Picture works seamlessly without recreating WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserUniversalStreamPlayerDialog(
    streamUrl: String,
    streamTitle: String,
    pageRefererUrl: String,
    isInPipMode: Boolean,
    onEnterPip: () -> Unit,
    onDownloadStream: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalDragonHaptics.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val escapedUrl = remember(streamUrl) {
        JSONObject.quote(streamUrl)
    }
    var streamWebView by remember { mutableStateOf<WebView?>(null) }
    var isFloatingInApp by remember { mutableStateOf(false) }
    val streamDensity = LocalDensity.current
    var streamFloatX by remember { mutableStateOf(Float.NaN) }
    var streamFloatY by remember { mutableStateOf(Float.NaN) }
    var streamFloatWidthPx by remember { mutableStateOf(Float.NaN) }
    var streamFloatHeightPx by remember { mutableStateOf(Float.NaN) }
    var streamMediaState by remember { mutableStateOf(FloatingPipMediaState()) }

    LaunchedEffect(isFloatingInApp, isInPipMode, streamWebView) {
        if (isFloatingInApp && !isInPipMode) {
            while (true) {
                streamWebView?.evaluateJavascript(PIP_QUERY_MEDIA_STATE_JS) { raw ->
                    parseFloatingPipMediaStateJson(raw)?.let { parsed ->
                        streamMediaState = parsed
                    }
                }
                delay(450L)
            }
        }
    }

    LaunchedEffect(Unit) {
        BrowserPipController.togglePlayEvents.collect {
            streamWebView?.evaluateJavascript(
                "(function(){var v=document.getElementById('v');if(!v)return;if(v.paused){v.play().catch(function(){});}else{v.pause();}})();",
                null
            )
        }
    }

    LaunchedEffect(Unit) {
        BrowserPipController.stopPlaybackEvents.collect {
            streamWebView?.evaluateJavascript(
                "(function(){var v=document.getElementById('v');if(v&&!v.paused){v.pause();}})();",
                null
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val activity = context.findActivity()
            val pipState = BrowserPipController.stateFlow.value
            val keepAlive = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                activity?.isInPictureInPictureMode == true) ||
                pipState.shouldKeepBackgroundMediaAlive
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    if (keepAlive) {
                        streamWebView?.let { wv ->
                            runCatching {
                                if (wv is BackgroundMediaWebView) {
                                    wv.keepMediaAliveInBackground = true
                                }
                                wv.resumeTimers()
                                wv.onResume()
                            }
                        }
                    } else {
                        streamWebView?.let { wv ->
                            runCatching {
                                if (wv is BackgroundMediaWebView) {
                                    wv.keepMediaAliveInBackground = false
                                }
                                wv.onPause()
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    streamWebView?.let { wv ->
                        runCatching {
                            if (wv is BackgroundMediaWebView) {
                                wv.keepMediaAliveInBackground = true
                            }
                            wv.resumeTimers()
                            wv.onResume()
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isInPipMode) {
                    Modifier.background(Color.Black)
                } else if (isFloatingInApp) {
                    Modifier.windowInsetsPadding(WindowInsets.systemBars)
                } else {
                    Modifier
                        .background(Color.Black.copy(alpha = 0.72f))
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(16.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onDismiss() }
                }
            )
            .testTag("browser_universal_stream_player_dialog"),
        contentAlignment = if (isFloatingInApp && !isInPipMode) Alignment.TopStart else Alignment.Center
    ) {
        val minFloatWidthPx = with(streamDensity) { 204.dp.toPx() }
        val minFloatHeightPx = with(streamDensity) { 178.dp.toPx() }
        val defaultFloatWidthPx = with(streamDensity) { 260.dp.toPx() }
        val defaultFloatHeightPx = with(streamDensity) { 236.dp.toPx() }
        val maxW = with(streamDensity) { maxWidth.toPx() }
        val maxH = with(streamDensity) { maxHeight.toPx() }
        val maxAllowedFloatW = (maxW - 8f).coerceAtLeast(minFloatWidthPx)
        val maxAllowedFloatH = (maxH - 8f).coerceAtLeast(minFloatHeightPx)
        val floatWidthPx = (if (streamFloatWidthPx.isNaN()) defaultFloatWidthPx else streamFloatWidthPx)
            .coerceIn(minFloatWidthPx, maxAllowedFloatW)
        val floatHeightPx = (if (streamFloatHeightPx.isNaN()) defaultFloatHeightPx else streamFloatHeightPx)
            .coerceIn(minFloatHeightPx, maxAllowedFloatH)
        val floatWidthDp = with(streamDensity) { floatWidthPx.toDp() }
        val floatHeightDp = with(streamDensity) { floatHeightPx.toDp() }
        val marginPx = with(streamDensity) { 12.dp.toPx() }
        val resolvedX = if (streamFloatX.isNaN()) (maxW - floatWidthPx - marginPx).coerceAtLeast(marginPx) else streamFloatX.coerceIn(0f, (maxW - floatWidthPx).coerceAtLeast(0f))
        val resolvedY = if (streamFloatY.isNaN()) (maxH - floatHeightPx - marginPx - with(streamDensity) { 56.dp.toPx() }).coerceAtLeast(marginPx) else streamFloatY.coerceIn(0f, (maxH - floatHeightPx).coerceAtLeast(0f))

        Surface(
            modifier = when {
                isInPipMode -> Modifier.fillMaxSize()
                isFloatingInApp -> Modifier
                    .offset { IntOffset(resolvedX.roundToInt(), resolvedY.roundToInt()) }
                    .size(width = floatWidthDp, height = floatHeightDp)
                    .pointerInput(maxW, maxH) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (zoom != 1f) {
                                val nextW = (floatWidthPx * zoom).coerceIn(minFloatWidthPx, maxAllowedFloatW)
                                val nextH = (floatHeightPx * zoom).coerceIn(minFloatHeightPx, maxAllowedFloatH)
                                streamFloatWidthPx = nextW
                                streamFloatHeightPx = nextH
                                val baseX = if (streamFloatX.isNaN()) resolvedX else streamFloatX
                                val baseY = if (streamFloatY.isNaN()) resolvedY else streamFloatY
                                streamFloatX = (baseX + pan.x).coerceIn(0f, (maxW - nextW).coerceAtLeast(0f))
                                streamFloatY = (baseY + pan.y).coerceIn(0f, (maxH - nextH).coerceAtLeast(0f))
                            }
                        }
                    }
                    .testTag("browser_stream_floating_pip_card")
                else -> Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
            },
            shape = when {
                isInPipMode -> RoundedCornerShape(0.dp)
                isFloatingInApp -> RoundedCornerShape(14.dp)
                else -> RoundedCornerShape(22.dp)
            },
            border = if (isFloatingInApp && !isInPipMode) BorderStroke(1.5.dp, NeonCyan.copy(alpha = 0.75f)) else null,
            color = if (isInPipMode || isFloatingInApp) Color(0xFF0B0F17) else MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = if (isInPipMode) 0.dp else 8.dp,
            shadowElevation = if (isInPipMode) 0.dp else 12.dp
        ) {
            Column(
                modifier = when {
                    isInPipMode -> Modifier.fillMaxSize()
                    isFloatingInApp -> Modifier.fillMaxSize()
                    else -> Modifier.padding(18.dp)
                },
                verticalArrangement = if (isInPipMode || isFloatingInApp) Arrangement.Top else Arrangement.spacedBy(12.dp)
            ) {
                if (!isInPipMode && isFloatingInApp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .background(Color(0xFF121927))
                            .pointerInput(maxW, maxH, floatWidthPx, floatHeightPx) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val baseX = if (streamFloatX.isNaN()) resolvedX else streamFloatX
                                    val baseY = if (streamFloatY.isNaN()) resolvedY else streamFloatY
                                    streamFloatX = (baseX + dragAmount.x).coerceIn(0f, (maxW - floatWidthPx).coerceAtLeast(0f))
                                    streamFloatY = (baseY + dragAmount.y).coerceIn(0f, (maxH - floatHeightPx).coerceAtLeast(0f))
                                }
                            }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.DragIndicator, contentDescription = "Drag player", tint = NeonCyan, modifier = Modifier.size(14.dp))
                        Text(
                            text = streamTitle.ifBlank { "Stream" },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                haptics.tap()
                                val isLarge = floatWidthPx > defaultFloatWidthPx * 1.18f
                                val nextW = (if (isLarge) defaultFloatWidthPx else maxW * 0.88f)
                                    .coerceIn(minFloatWidthPx, maxAllowedFloatW)
                                val nextH = (if (isLarge) defaultFloatHeightPx else maxH * 0.56f)
                                    .coerceIn(minFloatHeightPx, maxAllowedFloatH)
                                streamFloatWidthPx = nextW
                                streamFloatHeightPx = nextH
                                val baseX = if (streamFloatX.isNaN()) resolvedX else streamFloatX
                                val baseY = if (streamFloatY.isNaN()) resolvedY else streamFloatY
                                streamFloatX = baseX.coerceIn(0f, (maxW - nextW).coerceAtLeast(0f))
                                streamFloatY = baseY.coerceIn(0f, (maxH - nextH).coerceAtLeast(0f))
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("btn_stream_float_resize")
                        ) {
                            Icon(Icons.Default.AspectRatio, contentDescription = "Toggle floating player size", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                        IconButton(
                            onClick = {
                                haptics.tap()
                                isFloatingInApp = false
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.OpenInFull, contentDescription = "Expand player", tint = NeonCyan, modifier = Modifier.size(14.dp))
                        }
                        IconButton(
                            onClick = {
                                haptics.tap()
                                onDismiss()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close player", tint = Color.White, modifier = Modifier.size(15.dp))
                        }
                    }
                } else if (!isInPipMode) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.PlayCircleFilled, contentDescription = null, tint = NeonCyan)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = streamTitle.ifBlank { "Universal Stream Player" },
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = streamUrl,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = {
                                haptics.tap()
                                isFloatingInApp = true
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("btn_stream_player_pip")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPictureAlt,
                                contentDescription = "Float Stream Player",
                                tint = NeonCyan
                            )
                        }
                    }
                }

                Surface(
                    modifier = when {
                        isInPipMode -> Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coords ->
                                BrowserPipController.updateContainerRect(
                                    coords.boundsInWindow().toAndroidRectF().toRect()
                                )
                            }
                        isFloatingInApp -> Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                BrowserPipController.updateContainerRect(
                                    coords.boundsInWindow().toAndroidRectF().toRect()
                                )
                            }
                        else -> Modifier
                            .fillMaxWidth()
                            .height(235.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .onGloballyPositioned { coords ->
                                BrowserPipController.updateContainerRect(
                                    coords.boundsInWindow().toAndroidRectF().toRect()
                                )
                            }
                    },
                    color = Color.Black,
                    border = if (isInPipMode || isFloatingInApp) null else BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f))
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            BackgroundMediaWebView(ctx).apply {
                                keepMediaAliveInBackground = true
                                setBackgroundColor(android.graphics.Color.BLACK)
                                setLayerType(View.LAYER_TYPE_NONE, null)
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    allowFileAccess = false
                                    allowContentAccess = true
                                    mediaPlaybackRequiresUserGesture = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    userAgentString = resolveBrowserUserAgent(ctx, false)
                                }
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                addJavascriptInterface(
                                    object {
                                        @JavascriptInterface
                                        fun onStreamMeta(isPlaying: Boolean, width: Int, height: Int) {
                                            post {
                                                BrowserPipController.updateWebVideoState(
                                                    tabId = BrowserPipController.stateFlow.value.activeTabId,
                                                    isPlaying = isPlaying,
                                                    videoWidth = width,
                                                    videoHeight = height
                                                )
                                            }
                                        }
                                    },
                                    "DragonStreamPipBridge"
                                )
                                val html = """
                                    <!DOCTYPE html>
                                    <html>
                                    <head>
                                      <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no"/>
                                      <style>
                                        html, body { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; display:flex; flex-direction:column; justify-content:center; align-items:center; font-family:monospace; }
                                        video { width:100%; height:100%; max-height:100%; object-fit:contain; background:#000; }
                                        #status { position:absolute; top:8px; left:10px; color:#00E5FF; font-size:10px; background:rgba(0,0,0,0.65); padding:2px 6px; border-radius:4px; pointer-events:none; }
                                      </style>
                                      <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
                                      <script src="https://cdn.dashjs.org/latest/dash.all.min.js"></script>
                                    </head>
                                    <body>
                                      <div id="status">STREAM READY</div>
                                      <video id="v" controls autoplay playsinline webkit-playsinline preload="auto"></video>
                                      <script>
                                        (function(){
                                          var url = $escapedUrl;
                                          var v = document.getElementById('v');
                                          var st = document.getElementById('status');
                                          var lower = url.split('?')[0].toLowerCase();
                                          function reportMeta() {
                                            try {
                                              if (window.DragonStreamPipBridge) {
                                                window.DragonStreamPipBridge.onStreamMeta(!v.paused && !v.ended, v.videoWidth || 16, v.videoHeight || 9);
                                              }
                                            } catch (e) {}
                                          }
                                          function hideStatusLater() { setTimeout(function(){ if(st) st.style.display='none'; }, 2800); }
                                          v.addEventListener('playing', function(){ st.innerText = 'LIVE PLAYBACK'; hideStatusLater(); reportMeta(); });
                                          v.addEventListener('pause', reportMeta);
                                          v.addEventListener('ended', reportMeta);
                                          v.addEventListener('loadedmetadata', reportMeta);
                                          if (lower.indexOf('.m3u8') !== -1 || url.toLowerCase().indexOf('m3u8') !== -1) {
                                            if (window.Hls && Hls.isSupported()) {
                                              st.innerText = 'HLS ENGINE ACTIVE';
                                              var hls = new Hls({ enableWorker: true, lowLatencyMode: true });
                                              hls.loadSource(url);
                                              hls.attachMedia(v);
                                              hls.on(Hls.Events.MANIFEST_PARSED, function(){ v.play().catch(function(){}); });
                                            } else {
                                              v.src = url;
                                              v.play().catch(function(){});
                                            }
                                          } else if (lower.indexOf('.mpd') !== -1 && window.dashjs) {
                                            st.innerText = 'DASH ENGINE ACTIVE';
                                            var player = dashjs.MediaPlayer().create();
                                            player.initialize(v, url, true);
                                          } else {
                                            v.src = url;
                                            v.play().catch(function(){});
                                          }
                                        })();
                                      </script>
                                    </body>
                                    </html>
                                """.trimIndent()
                                loadDataWithBaseURL(
                                    pageRefererUrl.ifBlank { "https://www.google.com" },
                                    html,
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                                streamWebView = this
                            }
                        },
                        onRelease = { wv ->
                            if (streamWebView === wv) {
                                streamWebView = null
                            }
                            runCatching {
                                wv.stopLoading()
                                wv.loadUrl("about:blank")
                                wv.destroy()
                            }
                        }
                    )
                }

                if (!isInPipMode && isFloatingInApp) {
                    FloatingPipControlsFooter(
                        mediaState = streamMediaState,
                        tagPrefix = "btn_stream_float",
                        onSeekFraction = { frac ->
                            val target = (streamMediaState.durationSec * frac).coerceAtLeast(0f)
                            streamMediaState = streamMediaState.copy(currentTimeSec = target)
                            streamWebView?.evaluateJavascript(buildPipSeekToJs(target), null)
                        },
                        onRewind10 = {
                            haptics.tap()
                            streamWebView?.evaluateJavascript(buildPipSeekRelativeJs(-10), null)
                        },
                        onTogglePlayPause = {
                            haptics.tap()
                            streamMediaState = streamMediaState.copy(isPlaying = !streamMediaState.isPlaying)
                            streamWebView?.evaluateJavascript(PIP_TOGGLE_ACTIVE_VIDEO_JS, null)
                        },
                        onForward10 = {
                            haptics.tap()
                            streamWebView?.evaluateJavascript(buildPipSeekRelativeJs(10), null)
                        },
                        onToggleMute = {
                            haptics.tap()
                            streamMediaState = streamMediaState.copy(isMuted = !streamMediaState.isMuted)
                            streamWebView?.evaluateJavascript(PIP_TOGGLE_MUTE_JS, null)
                        },
                        onCycleSpeed = {
                            haptics.tap()
                            val nextRate = nextPipPlaybackRate(streamMediaState.playbackRate)
                            streamMediaState = streamMediaState.copy(playbackRate = nextRate)
                            streamWebView?.evaluateJavascript(buildPipSetSpeedJs(nextRate), null)
                        },
                        onBottomLeftResizeDrag = { dx, dy ->
                            val desiredW = (floatWidthPx - dx).coerceIn(minFloatWidthPx, maxAllowedFloatW)
                            val actualDeltaW = desiredW - floatWidthPx
                            val nextX = (resolvedX - actualDeltaW).coerceIn(0f, (maxW - desiredW).coerceAtLeast(0f))
                            streamFloatWidthPx = desiredW
                            streamFloatX = nextX
                            val nextH = (floatHeightPx + dy).coerceIn(minFloatHeightPx, maxAllowedFloatH)
                            streamFloatHeightPx = nextH
                            streamFloatY = resolvedY.coerceIn(0f, (maxH - nextH).coerceAtLeast(0f))
                        },
                        onBottomRightResizeDrag = { dx, dy ->
                            val nextW = (floatWidthPx + dx).coerceIn(minFloatWidthPx, maxAllowedFloatW)
                            val nextH = (floatHeightPx + dy).coerceIn(minFloatHeightPx, maxAllowedFloatH)
                            streamFloatWidthPx = nextW
                            streamFloatHeightPx = nextH
                            streamFloatX = resolvedX.coerceIn(0f, (maxW - nextW).coerceAtLeast(0f))
                            streamFloatY = resolvedY.coerceIn(0f, (maxH - nextH).coerceAtLeast(0f))
                        }
                    )
                } else if (!isInPipMode && !isFloatingInApp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                haptics.confirm()
                                onDownloadStream()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download (DDM)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                        }
                        OutlinedButton(
                            onClick = {
                                haptics.tap()
                                onDismiss()
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Close", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private data class FloatingPipMediaState(
    val isPlaying: Boolean = true,
    val isMuted: Boolean = false,
    val playbackRate: Float = 1.0f,
    val currentTimeSec: Float = 0f,
    val durationSec: Float = 0f
)

private val PIP_PLAYBACK_RATES = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.75f)

internal fun nextPipPlaybackRate(current: Float): Float {
    val idx = PIP_PLAYBACK_RATES.indexOfFirst { kotlin.math.abs(it - current) < 0.08f }
    return if (idx == -1) 1.25f else PIP_PLAYBACK_RATES[(idx + 1) % PIP_PLAYBACK_RATES.size]
}

internal fun formatPipTimeSeconds(seconds: Float): String {
    if (!seconds.isFinite() || seconds < 0f) return "0:00"
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

internal fun formatPipRateBadge(rate: Float): String {
    val clean = ((rate * 100f).roundToInt() / 100f)
    return if (clean % 1f == 0f) "${clean.toInt()}x" else "${clean.toString().trimEnd('0').trimEnd('.')}x"
}

private fun parseFloatingPipMediaStateJson(rawResult: String?): FloatingPipMediaState? {
    if (rawResult.isNullOrBlank() || rawResult == "null") return null
    val unwrapped = runCatching { JSONObject(rawResult) }.getOrNull()
        ?: runCatching {
            val str = JSONObject("{\"v\":$rawResult}").getString("v")
            JSONObject(str)
        }.getOrNull()
        ?: return null
    return FloatingPipMediaState(
        isPlaying = unwrapped.optBoolean("playing", true),
        isMuted = unwrapped.optBoolean("muted", false),
        playbackRate = unwrapped.optDouble("rate", 1.0).toFloat().coerceIn(0.25f, 4.0f),
        currentTimeSec = unwrapped.optDouble("current", 0.0).toFloat().coerceAtLeast(0f),
        durationSec = unwrapped.optDouble("duration", 0.0).toFloat().coerceAtLeast(0f)
    )
}

@Composable
private fun FloatingPipControlsFooter(
    mediaState: FloatingPipMediaState,
    tagPrefix: String,
    onSeekFraction: (Float) -> Unit,
    onRewind10: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onForward10: () -> Unit,
    onToggleMute: () -> Unit,
    onCycleSpeed: () -> Unit,
    onBottomLeftResizeDrag: (Float, Float) -> Unit,
    onBottomRightResizeDrag: (Float, Float) -> Unit
) {
    val progress = if (mediaState.durationSec > 0.5f) {
        (mediaState.currentTimeSec / mediaState.durationSec).coerceIn(0f, 1f)
    } else {
        0f
    }
    var trackWidthPx by remember { mutableStateOf(1) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF101724))
            .padding(top = 2.dp, bottom = 3.dp)
    ) {
        // Row 1: Timecodes + interactive seek scrubber
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = formatPipTimeSeconds(mediaState.currentTimeSec),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.85f)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
                    .onSizeChanged { trackWidthPx = it.width.coerceAtLeast(1) }
                    .pointerInput(mediaState.durationSec) {
                        detectTapGestures { offset ->
                            if (mediaState.durationSec > 0.5f) {
                                onSeekFraction((offset.x / trackWidthPx.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                    .pointerInput(mediaState.durationSec) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            if (mediaState.durationSec > 0.5f) {
                                onSeekFraction((change.position.x / trackWidthPx.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                    .testTag("${tagPrefix}_scrubber"),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.5.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(1.5.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(NeonCyan)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0.01f, 1f)),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(1.dp, NeonCyan, CircleShape)
                    )
                }
            }
            Text(
                text = if (mediaState.durationSec > 0.5f && mediaState.durationSec.isFinite()) {
                    formatPipTimeSeconds(mediaState.durationSec)
                } else {
                    "LIVE"
                },
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = if (mediaState.durationSec > 0.5f) Color.White.copy(alpha = 0.7f) else NeonCyan
            )
        }

        // Row 2: Bottom-left resize grip + Playback Controls + Bottom-right resize grip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Bottom-left free resize corner grip
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onBottomLeftResizeDrag(dragAmount.x, dragAmount.y)
                        }
                    }
                    .testTag("${tagPrefix}_resize_bl"),
                contentAlignment = Alignment.BottomStart
            ) {
                Canvas(
                    modifier = Modifier
                        .size(14.dp)
                        .padding(start = 4.dp, bottom = 3.dp)
                ) {
                    val stroke = 1.6.dp.toPx()
                    val color = NeonCyan.copy(alpha = 0.85f)
                    drawLine(color, Offset(0f, size.height * 0.2f), Offset(size.width * 0.8f, size.height), strokeWidth = stroke)
                    drawLine(color, Offset(0f, size.height * 0.58f), Offset(size.width * 0.42f, size.height), strokeWidth = stroke)
                }
            }

            IconButton(
                onClick = onRewind10,
                modifier = Modifier
                    .size(28.dp)
                    .testTag("${tagPrefix}_rewind")
            ) {
                Icon(
                    imageVector = Icons.Default.Replay10,
                    contentDescription = "Rewind 10 seconds",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(NeonCyan.copy(alpha = 0.18f))
                    .border(1.dp, NeonCyan.copy(alpha = 0.65f), CircleShape)
                    .clickable(onClick = onTogglePlayPause)
                    .testTag("${tagPrefix}_play_pause"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (mediaState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (mediaState.isPlaying) "Pause video" else "Play video",
                    tint = NeonCyan,
                    modifier = Modifier.size(17.dp)
                )
            }

            IconButton(
                onClick = onForward10,
                modifier = Modifier
                    .size(28.dp)
                    .testTag("${tagPrefix}_forward")
            ) {
                Icon(
                    imageVector = Icons.Default.Forward10,
                    contentDescription = "Forward 10 seconds",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(
                onClick = onToggleMute,
                modifier = Modifier
                    .size(28.dp)
                    .testTag("${tagPrefix}_mute")
            ) {
                Icon(
                    imageVector = if (mediaState.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (mediaState.isMuted) "Unmute video" else "Mute video",
                    tint = if (mediaState.isMuted) WarningOrange else Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }

            Surface(
                onClick = onCycleSpeed,
                shape = RoundedCornerShape(6.dp),
                color = Color.White.copy(alpha = 0.12f),
                modifier = Modifier
                    .height(22.dp)
                    .testTag("${tagPrefix}_speed")
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = formatPipRateBadge(mediaState.playbackRate),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                }
            }

            // Bottom-right free resize corner grip
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onBottomRightResizeDrag(dragAmount.x, dragAmount.y)
                        }
                    }
                    .testTag("${tagPrefix}_resize_br"),
                contentAlignment = Alignment.BottomEnd
            ) {
                Canvas(
                    modifier = Modifier
                        .size(14.dp)
                        .padding(end = 4.dp, bottom = 3.dp)
                ) {
                    val stroke = 1.6.dp.toPx()
                    val color = NeonCyan.copy(alpha = 0.85f)
                    drawLine(color, Offset(size.width * 0.2f, size.height), Offset(size.width, size.height * 0.2f), strokeWidth = stroke)
                    drawLine(color, Offset(size.width * 0.58f, size.height), Offset(size.width, size.height * 0.58f), strokeWidth = stroke)
                }
            }
        }
    }
}

private fun buildPipSeekRelativeJs(deltaSeconds: Int): String = """
(function(){
  try {
    var v = (window.__dragonPipActiveElement && document.contains(window.__dragonPipActiveElement)) ? window.__dragonPipActiveElement
      : (window.__dragonLastActiveVideo && document.contains(window.__dragonLastActiveVideo)) ? window.__dragonLastActiveVideo
      : document.querySelector('#__dragon_doc_pip_body video, video#v, video');
    if (!v) return;
    var dur = (typeof v.duration === 'number' && isFinite(v.duration) && v.duration > 0) ? v.duration : 1e9;
    v.currentTime = Math.max(0, Math.min(dur, (v.currentTime || 0) + ($deltaSeconds)));
  } catch(e) {}
})();
""".trimIndent()

private fun buildPipSeekToJs(targetSeconds: Float): String = """
(function(){
  try {
    var v = (window.__dragonPipActiveElement && document.contains(window.__dragonPipActiveElement)) ? window.__dragonPipActiveElement
      : (window.__dragonLastActiveVideo && document.contains(window.__dragonLastActiveVideo)) ? window.__dragonLastActiveVideo
      : document.querySelector('#__dragon_doc_pip_body video, video#v, video');
    if (!v) return;
    var dur = (typeof v.duration === 'number' && isFinite(v.duration) && v.duration > 0) ? v.duration : 1e9;
    v.currentTime = Math.max(0, Math.min(dur, $targetSeconds));
  } catch(e) {}
})();
""".trimIndent()

private fun buildPipSetSpeedJs(rate: Float): String = """
(function(){
  try {
    var v = (window.__dragonPipActiveElement && document.contains(window.__dragonPipActiveElement)) ? window.__dragonPipActiveElement
      : (window.__dragonLastActiveVideo && document.contains(window.__dragonLastActiveVideo)) ? window.__dragonLastActiveVideo
      : document.querySelector('#__dragon_doc_pip_body video, video#v, video');
    if (!v) return;
    v.playbackRate = $rate;
  } catch(e) {}
})();
""".trimIndent()

private const val PIP_TOGGLE_MUTE_JS = """
(function(){
  try {
    var v = (window.__dragonPipActiveElement && document.contains(window.__dragonPipActiveElement)) ? window.__dragonPipActiveElement
      : (window.__dragonLastActiveVideo && document.contains(window.__dragonLastActiveVideo)) ? window.__dragonLastActiveVideo
      : document.querySelector('#__dragon_doc_pip_body video, video#v, video');
    if (!v) return;
    v.muted = !v.muted;
    if (!v.muted && v.volume === 0) v.volume = 1.0;
  } catch(e) {}
})();
"""

private const val PIP_QUERY_MEDIA_STATE_JS = """
(function(){
  try {
    var v = document.querySelector('#__dragon_doc_pip_body video')
      || (window.__dragonPipActiveElement && document.contains(window.__dragonPipActiveElement) ? window.__dragonPipActiveElement : null)
      || (window.__dragonLastActiveVideo && document.contains(window.__dragonLastActiveVideo) ? window.__dragonLastActiveVideo : null)
      || document.getElementById('v')
      || document.querySelector('video, audio');
    if (!v) return JSON.stringify({playing:false,muted:false,rate:1,current:0,duration:0});
    var dur = (typeof v.duration === 'number' && isFinite(v.duration) && v.duration > 0) ? v.duration : 0;
    return JSON.stringify({
      playing: !v.paused && !v.ended,
      muted: !!(v.muted || v.volume === 0),
      rate: Number(v.playbackRate) || 1.0,
      current: Number(v.currentTime) || 0,
      duration: dur
    });
  } catch(e) {
    return JSON.stringify({playing:false,muted:false,rate:1,current:0,duration:0});
  }
})();
"""

private val WEB_PIP_POLYFILL_JS: String by lazy {
    buildString {
        append("""
(function() {
  try {
    var hostLower = (window.location && window.location.hostname ? window.location.hostname : '').toLowerCase();
    if (hostLower === 'accounts.google.com' || hostLower === 'myaccount.google.com') return;
    if (window.__dragonWebPipPolyfillInstalled) return;
    window.__dragonWebPipPolyfillInstalled = true;
    window.__dragonInPipMode = window.__dragonInPipMode || false;
    window.__dragonFloatingActive = window.__dragonFloatingActive || false;
    window.__dragonPipModeKind = window.__dragonPipModeKind || null;
    window.__dragonPipActiveElement = window.__dragonPipActiveElement || null;
    window.__dragonDocPipWindow = window.__dragonDocPipWindow || null;
    window.__dragonPipUserPaused = false;
    window.__dragonPipMediaSessionHandler = window.__dragonPipMediaSessionHandler || null;

    function createSimpleEventTarget(targetObj) {
      var listeners = {};
      return {
        addEventListener: function(type, fn) {
          if (typeof fn !== 'function') return;
          if (!listeners[type]) listeners[type] = [];
          if (listeners[type].indexOf(fn) === -1) listeners[type].push(fn);
        },
        removeEventListener: function(type, fn) {
          var arr = listeners[type];
          if (!arr) return;
          var idx = arr.indexOf(fn);
          if (idx !== -1) arr.splice(idx, 1);
        },
        dispatchEvent: function(ev) {
          if (!ev || !ev.type) return true;
          var handler = targetObj['on' + ev.type];
          if (typeof handler === 'function') {
            try { handler.call(targetObj, ev); } catch (e) {}
          }
          var arr = (listeners[ev.type] || []).slice();
          for (var i = 0; i < arr.length; i++) {
            try { arr[i].call(targetObj, ev); } catch (e) {}
          }
          return !ev.defaultPrevented;
        }
      };
    }

    function measureVideoForBridge(videoEl) {
      var rect = videoEl && videoEl.getBoundingClientRect
        ? videoEl.getBoundingClientRect()
        : { left: 0, top: 0, right: 300, bottom: 170, width: 16, height: 9 };
      var vw = Math.max(1, window.innerWidth || (document.documentElement ? document.documentElement.clientWidth : 1) || 1);
      var vh = Math.max(1, window.innerHeight || (document.documentElement ? document.documentElement.clientHeight : 1) || 1);
      var w = (videoEl && videoEl.videoWidth) || Math.round(rect.width) || 16;
      var h = (videoEl && videoEl.videoHeight) || Math.round(rect.height) || 9;
      var lf = Math.max(0.0, Math.min(1.0, rect.left / vw));
      var tf = Math.max(0.0, Math.min(1.0, rect.top / vh));
      var rf = Math.max(0.0, Math.min(1.0, rect.right / vw));
      var bf = Math.max(0.0, Math.min(1.0, rect.bottom / vh));
      return { w: w, h: h, lf: lf, tf: tf, rf: rf, bf: bf };
    }

    function notifyNativeFloatingPip(isFloating, isDocPip, refEl) {
      try {
        var m = measureVideoForBridge(refEl);
        if (window.DragonBrowserBridge && window.DragonBrowserBridge.onWebFloatingPipChanged) {
          window.DragonBrowserBridge.onWebFloatingPipChanged(!!isFloating, !!isDocPip, m.w, m.h, m.lf, m.tf, m.rf, m.bf);
        } else if (isFloating && window.DragonBrowserBridge && window.DragonBrowserBridge.requestWebPictureInPicture) {
          window.DragonBrowserBridge.requestWebPictureInPicture(m.w, m.h, m.lf, m.tf, m.rf, m.bf);
        }
      } catch (e) {}
    }

    function findPrimaryVideo(rootDoc) {
      var d = rootDoc || document;
      var vids = d.querySelectorAll ? d.querySelectorAll('video') : [];
      var best = window.__dragonLastActiveVideo;
      if (best && d.contains && !d.contains(best)) best = null;
      if (!best || (best.paused && vids.length > 0)) {
        for (var i = 0; i < vids.length; i++) {
          if (!vids[i].paused && !vids[i].ended) return vids[i];
          if (!best && (vids[i].readyState >= 1 || vids[i].src || vids[i].currentSrc)) best = vids[i];
        }
      }
      return best || (vids.length > 0 ? vids[0] : null);
    }

    function scopeDocPipCss(cssText) {
      try {
        if (!cssText) return '';
        return String(cssText).replace(/(^|[,}\s])(html|body)(?=[\s{,:.\[#])/gi, '$1#__dragon_doc_pip_body');
      } catch (e) {
        return cssText;
      }
    }

    function filterStyleNodeForHost(node) {
      if (!node || !node.tagName) return node;
      var tag = String(node.tagName).toUpperCase();
      if (tag === 'STYLE') {
        try {
          node.textContent = scopeDocPipCss(node.textContent);
        } catch (e) {}
      } else if (tag === 'LINK') {
        var rel = (node.getAttribute && node.getAttribute('rel') || '').toLowerCase();
        var href = node.href || (node.getAttribute && node.getAttribute('href')) || '';
        if (rel === 'stylesheet' && href) {
          var existing = document.querySelectorAll('head link[rel="stylesheet"]');
          for (var i = 0; i < existing.length; i++) {
            if ((existing[i].href || existing[i].getAttribute('href')) === href) {
              node.disabled = true;
              node.setAttribute('data-dragon-dup-link', '1');
              break;
            }
          }
        }
      }
      return node;
    }

    function formatMediaClock(sec) {
      if (!isFinite(sec) || sec < 0) return '0:00';
      var total = Math.floor(sec);
      var h = Math.floor(total / 3600);
      var m = Math.floor((total % 3600) / 60);
      var s = total % 60;
      var ss = s < 10 ? ('0' + s) : String(s);
      if (h > 0) {
        var mm = m < 10 ? ('0' + m) : String(m);
        return h + ':' + mm + ':' + ss;
      }
      return m + ':' + ss;
    }

    function findControlledHostMedia(hostEl) {
      if (hostEl) {
        var inside = hostEl.querySelector('video, audio');
        if (inside) return inside;
      }
      if (window.__dragonPipActiveElement && document.contains(window.__dragonPipActiveElement)) {
        return window.__dragonPipActiveElement;
      }
      if (window.__dragonLastActiveVideo && document.contains(window.__dragonLastActiveVideo)) {
        return window.__dragonLastActiveVideo;
      }
      return findPrimaryVideo(document) || document.querySelector('audio');
    }

    function computePipUiScale() {
      try {
        var ds = window.__dragonDesktopViewportState;
        var layoutW = window.innerWidth || (document.documentElement ? document.documentElement.clientWidth : 0) || 390;
        if (ds && ds.enabled && !ds.pipViewportLocked && ds.dipW >= 240 && layoutW > ds.dipW * 1.12) {
          return Math.max(1.0, Math.min(3.5, layoutW / ds.dipW));
        }
        if (window.visualViewport && window.visualViewport.scale && window.visualViewport.scale < 0.92) {
          return Math.max(1.0, Math.min(3.5, 1.0 / window.visualViewport.scale));
        }
      } catch (e) {}
      return 1.0;
    }

    function ensureFloatingPipHost(modeKind, reqW, reqH, onCloseClick) {
      var mountRoot = document.body || document.documentElement;
      if (!mountRoot) return null;

      var prev = document.getElementById('__dragon_doc_pip_host');
      if (prev) {
        if (prev.__dragonSyncTimer) {
          clearInterval(prev.__dragonSyncTimer);
          prev.__dragonSyncTimer = null;
        }
        if (prev.__dragonResizeListener) {
          try { window.removeEventListener('resize', prev.__dragonResizeListener); } catch (e) {}
          prev.__dragonResizeListener = null;
        }
        if (prev.parentNode) prev.parentNode.removeChild(prev);
      }

      var uiScale = computePipUiScale();
      var vw = Math.max(240, window.innerWidth || mountRoot.clientWidth || 360);
      var vh = Math.max(240, window.innerHeight || mountRoot.clientHeight || 640);
      var dipVw = Math.max(240, vw / uiScale);
      var dipVh = Math.max(240, vh / uiScale);
      var minW = 204;
      var minBodyH = 90;
      var chromeOverheadH = 89;
      var defaultCardW = Math.min(260, Math.round(dipVw * 0.76));
      var targetW = reqW && reqW > 140 ? Math.min(Math.round(dipVw * 0.94), Math.max(minW, Math.round(reqW))) : defaultCardW;
      var targetBodyH = reqH && reqH > 100
        ? Math.min(Math.round(dipVh * 0.62), Math.max(minBodyH, Math.round(reqH)))
        : Math.max(147, Math.round(targetW * 9 / 16));
      if (modeKind === 'document' && targetBodyH < 180) targetBodyH = 180;

      var visualW = Math.round(targetW * uiScale);
      var visualH = Math.round((targetBodyH + chromeOverheadH) * uiScale);
      var marginPx = Math.round(12 * uiScale);

      var host = document.createElement('div');
      host.id = '__dragon_doc_pip_host';
      host.setAttribute('data-dragon-pip-mode', modeKind || 'video');
      host.style.cssText =
        'position:fixed !important;z-index:2147483647 !important;' +
        'width:' + targetW + 'px !important;' +
        'left:' + Math.max(Math.round(8 * uiScale), vw - visualW - marginPx) + 'px !important;' +
        'top:' + Math.max(Math.round(8 * uiScale), vh - visualH - marginPx) + 'px !important;' +
        'transform:scale(' + uiScale.toFixed(4) + ') !important;transform-origin:top left !important;' +
        'background:#0B0F17 !important;color:#fff !important;' +
        'border:1.5px solid rgba(0,229,255,0.72) !important;' +
        'border-radius:14px !important;' +
        'box-shadow:0 12px 32px rgba(0,0,0,0.76) !important;' +
        'overflow:hidden !important;display:flex !important;flex-direction:column !important;' +
        'margin:0 !important;padding:0 !important;box-sizing:border-box !important;' +
        'font-family:system-ui,-apple-system,sans-serif !important;';

      // Newly designed slim top bar matching Compose cross-tab floating PiP card
      var bar = document.createElement('div');
      bar.id = '__dragon_doc_pip_bar';
      bar.style.cssText =
        'height:32px !important;min-height:32px !important;background:#121927 !important;' +
        'display:flex !important;align-items:center !important;gap:4px !important;' +
        'padding:0 4px 0 8px !important;cursor:move !important;user-select:none !important;' +
        'touch-action:none !important;box-sizing:border-box !important;';

      var dragIcon = document.createElement('span');
      dragIcon.style.cssText = 'display:flex !important;align-items:center !important;justify-content:center !important;width:14px !important;height:14px !important;flex-shrink:0 !important;pointer-events:none !important;';
      dragIcon.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="#00E5FF"><path d="M11 18c0 1.1-.9 2-2 2s-2-.9-2-2 .9-2 2-2 2 .9 2 2zm-2-8c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0-6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm6 4c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg>';

      var title = document.createElement('span');
      title.id = '__dragon_doc_pip_title';
      var pageTitleText = (document.title || '').trim();
      title.textContent = pageTitleText || (modeKind === 'document' ? 'Advanced PiP' : 'Floating Video');
      title.style.cssText = 'flex:1 !important;font-size:10px !important;font-weight:700 !important;color:#FFFFFF !important;white-space:nowrap !important;overflow:hidden !important;text-overflow:ellipsis !important;pointer-events:none !important;line-height:1.2 !important;';

      var actions = document.createElement('div');
      actions.id = '__dragon_doc_pip_actions';
      actions.style.cssText = 'display:flex !important;align-items:center !important;gap:0px !important;flex-shrink:0 !important;';

      var headerBtnCss = 'background:transparent !important;color:#FFFFFF !important;border:none !important;border-radius:999px !important;width:28px !important;height:28px !important;padding:0 !important;margin:0 !important;cursor:pointer !important;display:flex !important;align-items:center !important;justify-content:center !important;';

      var sizeBtn = document.createElement('button');
      sizeBtn.id = '__dragon_doc_pip_size';
      sizeBtn.type = 'button';
      sizeBtn.setAttribute('aria-label', 'Toggle floating video size');
      sizeBtn.style.cssText = headerBtnCss;
      sizeBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="#FFFFFF"><path d="M19 12h-2v3h-3v2h5v-5zM7 9h3V7H5v5h2V9zm14-6H3c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16.01H3V4.99h18v14.02z"/></svg>';

      var closeBtn = document.createElement('button');
      closeBtn.id = '__dragon_doc_pip_close';
      closeBtn.type = 'button';
      closeBtn.setAttribute('aria-label', 'Close floating video');
      closeBtn.style.cssText = headerBtnCss;
      closeBtn.innerHTML = '<svg width="15" height="15" viewBox="0 0 24 24" fill="#FFFFFF"><path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>';

      actions.appendChild(sizeBtn);
      actions.appendChild(closeBtn);
      bar.appendChild(dragIcon);
      bar.appendChild(title);
      bar.appendChild(actions);

      var bodyWrap = document.createElement('div');
      bodyWrap.id = '__dragon_doc_pip_body_wrap';
      bodyWrap.style.cssText =
        'position:relative !important;width:100% !important;height:' + targetBodyH + 'px !important;' +
        'background:#000 !important;overflow:hidden !important;display:flex !important;flex-direction:column !important;';

      var pipHead = document.createElement('div');
      pipHead.id = '__dragon_doc_pip_head';
      pipHead.style.cssText = 'display:none !important;';

      var wrapAppend = function(origFn) {
        return function() {
          for (var k = 0; k < arguments.length; k++) {
            filterStyleNodeForHost(arguments[k]);
          }
          return origFn.apply(this, arguments);
        };
      };
      pipHead.appendChild = wrapAppend(Element.prototype.appendChild);
      pipHead.append = wrapAppend(Element.prototype.append);
      pipHead.insertBefore = function(newNode, refNode) {
        filterStyleNodeForHost(newNode);
        return Element.prototype.insertBefore.call(this, newNode, refNode);
      };

      var pipBody = document.createElement('div');
      pipBody.id = '__dragon_doc_pip_body';
      pipBody.style.cssText =
        'position:relative !important;width:100% !important;height:100% !important;flex:1 !important;' +
        'margin:0 !important;padding:0 !important;background:#000 !important;overflow:hidden !important;' +
        'display:flex !important;align-items:center !important;justify-content:center !important;';

      bodyWrap.appendChild(pipHead);
      bodyWrap.appendChild(pipBody);

      // Newly designed slim playback footer matching FloatingPipControlsFooter (1.5px seek bar + crisp icons)
      var controlsBar = document.createElement('div');
      controlsBar.id = '__dragon_doc_pip_controls';
      controlsBar.style.cssText =
        'background:#101724 !important;' +
        'padding:2px 0 3px 0 !important;display:flex !important;flex-direction:column !important;' +
        'user-select:none !important;box-sizing:border-box !important;';

      var seekRow = document.createElement('div');
      seekRow.style.cssText = 'display:flex !important;align-items:center !important;gap:6px !important;padding:0 8px !important;height:20px !important;box-sizing:border-box !important;';

      var curTimeEl = document.createElement('span');
      curTimeEl.id = '__dragon_doc_pip_time_cur';
      curTimeEl.textContent = '0:00';
      curTimeEl.style.cssText = 'font-size:9px !important;font-family:monospace !important;color:rgba(255,255,255,0.85) !important;flex-shrink:0 !important;';

      // Custom ultra-thin 1.5px seek scrubber with 7px white/cyan thumb (replaces bulky native range input)
      var seekTrack = document.createElement('div');
      seekTrack.id = '__dragon_doc_pip_seek';
      seekTrack.setAttribute('role', 'slider');
      seekTrack.setAttribute('aria-label', 'Seek video position');
      seekTrack.style.cssText =
        'position:relative !important;flex:1 !important;height:20px !important;' +
        'display:flex !important;align-items:center !important;cursor:pointer !important;touch-action:none !important;';

      var seekBgRail = document.createElement('div');
      seekBgRail.style.cssText =
        'position:absolute !important;left:0 !important;right:0 !important;top:50% !important;transform:translateY(-50%) !important;' +
        'height:1.5px !important;border-radius:1px !important;background:rgba(255,255,255,0.18) !important;pointer-events:none !important;';

      var seekFillRail = document.createElement('div');
      seekFillRail.id = '__dragon_doc_pip_seek_fill';
      seekFillRail.style.cssText =
        'position:absolute !important;left:0 !important;top:50% !important;transform:translateY(-50%) !important;' +
        'width:0% !important;height:1.5px !important;border-radius:1px !important;background:#00E5FF !important;pointer-events:none !important;';

      var seekThumb = document.createElement('div');
      seekThumb.id = '__dragon_doc_pip_seek_thumb';
      seekThumb.style.cssText =
        'position:absolute !important;left:0% !important;top:50% !important;transform:translate(-50%,-50%) !important;' +
        'width:7px !important;height:7px !important;border-radius:999px !important;' +
        'background:#FFFFFF !important;border:1px solid #00E5FF !important;box-sizing:border-box !important;pointer-events:none !important;';

      seekTrack.appendChild(seekBgRail);
      seekTrack.appendChild(seekFillRail);
      seekTrack.appendChild(seekThumb);

      var durTimeEl = document.createElement('span');
      durTimeEl.id = '__dragon_doc_pip_time_dur';
      durTimeEl.textContent = '0:00';
      durTimeEl.style.cssText = 'font-size:9px !important;font-family:monospace !important;color:rgba(255,255,255,0.70) !important;flex-shrink:0 !important;text-align:right !important;';

      seekRow.appendChild(curTimeEl);
      seekRow.appendChild(seekTrack);
      seekRow.appendChild(durTimeEl);

      var btnRow = document.createElement('div');
      btnRow.style.cssText = 'display:flex !important;align-items:center !important;justify-content:space-between !important;height:32px !important;padding:0 2px !important;box-sizing:border-box !important;';

      var resizeLeft = document.createElement('div');
      resizeLeft.id = '__dragon_doc_pip_resize_left';
      resizeLeft.setAttribute('aria-label', 'Resize floating window from bottom left');
      resizeLeft.style.cssText = 'width:22px !important;height:22px !important;display:flex !important;align-items:flex-end !important;justify-content:flex-start !important;cursor:nesw-resize !important;touch-action:none !important;padding:0 0 3px 4px !important;box-sizing:border-box !important;';
      resizeLeft.innerHTML = '<svg width="11" height="11" viewBox="0 0 12 12"><line x1="1" y1="2.4" x2="9.6" y2="11" stroke="rgba(0,229,255,0.85)" stroke-width="1.6" stroke-linecap="round"/><line x1="1" y1="7" x2="5" y2="11" stroke="rgba(0,229,255,0.85)" stroke-width="1.6" stroke-linecap="round"/></svg>';

      var ctrlBtnCss = 'background:transparent !important;color:#FFFFFF !important;border:none !important;border-radius:999px !important;width:28px !important;height:28px !important;padding:0 !important;margin:0 !important;cursor:pointer !important;display:flex !important;align-items:center !important;justify-content:center !important;';

      var rewBtn = document.createElement('button');
      rewBtn.id = '__dragon_doc_pip_rew';
      rewBtn.type = 'button';
      rewBtn.setAttribute('aria-label', 'Rewind 10 seconds');
      rewBtn.style.cssText = ctrlBtnCss;
      rewBtn.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="#FFFFFF"><path d="M11.99 5V1l-5 5 5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6h-2c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8zm-1.1 11h-.85v-3.26l-1.01.31v-.69l1.77-.63h.09V16zm4.28-1.76c0 .32-.03.6-.1.82s-.17.42-.29.57-.28.26-.45.33-.37.1-.59.1-.41-.03-.59-.1-.33-.18-.46-.33-.23-.34-.3-.57-.11-.5-.11-.82v-.74c0-.32.03-.6.1-.82s.17-.42.29-.57.28-.26.45-.33.37-.1.59-.1.41.03.59.1.33.18.46.33.23.34.3.57.11.5.11.82v.74zm-.85-.86c0-.19-.01-.35-.04-.48s-.07-.23-.12-.31-.11-.14-.19-.17-.16-.05-.25-.05-.18.02-.25.05-.14.09-.19.17-.09.18-.12.31-.04.29-.04.48v.97c0 .19.01.35.04.48s.07.24.12.32.11.14.19.17.16.05.25.05.18-.02.25-.05.14-.09.19-.17.09-.19.12-.32.04-.29.04-.48v-.97z"/></svg>';

      var playBtn = document.createElement('button');
      playBtn.id = '__dragon_doc_pip_play';
      playBtn.type = 'button';
      playBtn.setAttribute('aria-label', 'Play or pause video');
      playBtn.style.cssText = 'background:rgba(0,229,255,0.18) !important;color:#00E5FF !important;border:1px solid rgba(0,229,255,0.65) !important;border-radius:999px !important;width:28px !important;height:28px !important;padding:0 !important;margin:0 !important;cursor:pointer !important;display:flex !important;align-items:center !important;justify-content:center !important;box-sizing:border-box !important;';

      var fwdBtn = document.createElement('button');
      fwdBtn.id = '__dragon_doc_pip_fwd';
      fwdBtn.type = 'button';
      fwdBtn.setAttribute('aria-label', 'Forward 10 seconds');
      fwdBtn.style.cssText = ctrlBtnCss;
      fwdBtn.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" fill="#FFFFFF"><path d="M18 13c0 3.31-2.69 6-6 6s-6-2.69-6-6 2.69-6 6-6v4l5-5-5-5v4c-4.42 0-8 3.58-8 8s3.58 8 8 8 8-3.58 8-8h-2zm-7.14 3h-.85v-3.26l-1.01.31v-.69l1.77-.63h.09V16zm4.31-1.76c0 .32-.03.6-.1.82s-.17.42-.29.57-.28.26-.45.33-.37.1-.59.1-.41-.03-.59-.1-.33-.18-.46-.33-.23-.34-.3-.57-.11-.5-.11-.82v-.74c0-.32.03-.6.1-.82s.17-.42.29-.57.28-.26.45-.33.37-.1.59-.1.41.03.59.1.33.18.46.33.23.34.3.57.11.5.11.82v.74zm-.85-.86c0-.19-.01-.35-.04-.48s-.07-.23-.12-.31-.11-.14-.19-.17-.16-.05-.25-.05-.18.02-.25.05-.14.09-.19.17-.09.18-.12.31-.04.29-.04.48v.97c0 .19.01.35.04.48s.07.24.12.32.11.14.19.17.16.05.25.05.18-.02.25-.05.14-.09.19-.17.09-.19.12-.32.04-.29.04-.48v-.97z"/></svg>';

      var muteBtn = document.createElement('button');
      muteBtn.id = '__dragon_doc_pip_mute';
      muteBtn.type = 'button';
      muteBtn.setAttribute('aria-label', 'Mute or unmute video');
      muteBtn.style.cssText = ctrlBtnCss;

      var speedBtn = document.createElement('button');
      speedBtn.id = '__dragon_doc_pip_speed';
      speedBtn.type = 'button';
      speedBtn.textContent = '1x';
      speedBtn.setAttribute('aria-label', 'Cycle playback speed');
      speedBtn.style.cssText = 'background:rgba(255,255,255,0.12) !important;color:#00E5FF !important;border:none !important;border-radius:6px !important;height:22px !important;padding:0 5px !important;margin:0 !important;font-size:9px !important;font-weight:700 !important;cursor:pointer !important;display:flex !important;align-items:center !important;justify-content:center !important;';

      var resizeRight = document.createElement('div');
      resizeRight.id = '__dragon_doc_pip_resize_handle';
      resizeRight.setAttribute('aria-label', 'Resize floating window from bottom right');
      resizeRight.style.cssText = 'width:22px !important;height:22px !important;display:flex !important;align-items:flex-end !important;justify-content:flex-end !important;cursor:nwse-resize !important;touch-action:none !important;padding:0 4px 3px 0 !important;box-sizing:border-box !important;';
      resizeRight.innerHTML = '<svg width="11" height="11" viewBox="0 0 12 12"><line x1="2.4" y1="11" x2="11" y2="2.4" stroke="rgba(0,229,255,0.85)" stroke-width="1.6" stroke-linecap="round"/><line x1="7" y1="11" x2="11" y2="7" stroke="rgba(0,229,255,0.85)" stroke-width="1.6" stroke-linecap="round"/></svg>';

      btnRow.appendChild(resizeLeft);
      btnRow.appendChild(rewBtn);
      btnRow.appendChild(playBtn);
      btnRow.appendChild(fwdBtn);
      btnRow.appendChild(muteBtn);
      btnRow.appendChild(speedBtn);
      btnRow.appendChild(resizeRight);

      controlsBar.appendChild(seekRow);
      controlsBar.appendChild(btnRow);

      host.appendChild(bar);
      host.appendChild(bodyWrap);
      host.appendChild(controlsBar);
      mountRoot.appendChild(host);

      var svgPlay = '<svg width="17" height="17" viewBox="0 0 24 24" fill="#00E5FF"><path d="M8 5v14l11-7z"/></svg>';
      var svgPause = '<svg width="17" height="17" viewBox="0 0 24 24" fill="#00E5FF"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>';
      var svgVolOn = '<svg width="15" height="15" viewBox="0 0 24 24" fill="#FFFFFF"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z"/></svg>';
      var svgVolOff = '<svg width="15" height="15" viewBox="0 0 24 24" fill="#FF9800"><path d="M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71zM4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"/></svg>';

      function setSeekVisualFraction(frac) {
        var clamped = Math.max(0, Math.min(1, frac || 0));
        var pct = (clamped * 100).toFixed(2) + '%';
        seekFillRail.style.setProperty('width', pct, 'important');
        seekThumb.style.setProperty('left', pct, 'important');
      }

      var isScrubbing = false;
      function syncControlUi() {
        if (!document.contains(host)) return;
        var m = findControlledHostMedia(host);
        if (!m) return;
        var isPlaying = !m.paused && !m.ended;
        playBtn.innerHTML = isPlaying ? svgPause : svgPlay;
        var isMuted = !!(m.muted || m.volume === 0);
        muteBtn.innerHTML = isMuted ? svgVolOff : svgVolOn;
        var rate = Number(m.playbackRate) || 1.0;
        speedBtn.textContent = (rate % 1 === 0 ? String(rate) : String(rate)) + 'x';
        var dur = (typeof m.duration === 'number' && isFinite(m.duration) && m.duration > 0.5) ? m.duration : 0;
        var cur = Number(m.currentTime) || 0;
        curTimeEl.textContent = formatMediaClock(cur);
        durTimeEl.textContent = dur > 0 ? formatMediaClock(dur) : 'LIVE';
        durTimeEl.style.setProperty('color', dur > 0 ? 'rgba(255,255,255,0.70)' : '#00E5FF', 'important');
        if (!isScrubbing) {
          setSeekVisualFraction(dur > 0 ? (cur / dur) : 0);
        }
      }
      syncControlUi();
      host.__dragonSyncTimer = setInterval(syncControlUi, 350);

      function seekFromClientX(clientX) {
        var rect = seekTrack.getBoundingClientRect();
        if (!rect || rect.width <= 0) return;
        var frac = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
        setSeekVisualFraction(frac);
        var m = findControlledHostMedia(host);
        if (!m) return;
        var dur = (typeof m.duration === 'number' && isFinite(m.duration) && m.duration > 0.5) ? m.duration : 0;
        if (dur > 0) {
          var targetSec = frac * dur;
          m.currentTime = targetSec;
          curTimeEl.textContent = formatMediaClock(targetSec);
        }
      }

      seekTrack.addEventListener('touchstart', function(ev) {
        ev.stopPropagation();
        isScrubbing = true;
        if (ev.touches && ev.touches[0]) seekFromClientX(ev.touches[0].clientX);
      }, { passive: true });
      seekTrack.addEventListener('touchmove', function(ev) {
        if (isScrubbing && ev.touches && ev.touches[0]) {
          ev.preventDefault();
          ev.stopPropagation();
          seekFromClientX(ev.touches[0].clientX);
        }
      }, { passive: false });
      seekTrack.addEventListener('touchend', function() {
        isScrubbing = false;
        syncControlUi();
      }, { passive: true });
      seekTrack.addEventListener('mousedown', function(ev) {
        ev.stopPropagation();
        isScrubbing = true;
        seekFromClientX(ev.clientX);
      });

      rewBtn.addEventListener('click', function(ev) {
        ev.stopPropagation();
        var m = findControlledHostMedia(host);
        if (!m) return;
        var dur = (typeof m.duration === 'number' && isFinite(m.duration) && m.duration > 0) ? m.duration : 1e9;
        m.currentTime = Math.max(0, Math.min(dur, (m.currentTime || 0) - 10));
        syncControlUi();
      });

      fwdBtn.addEventListener('click', function(ev) {
        ev.stopPropagation();
        var m = findControlledHostMedia(host);
        if (!m) return;
        var dur = (typeof m.duration === 'number' && isFinite(m.duration) && m.duration > 0) ? m.duration : 1e9;
        m.currentTime = Math.max(0, Math.min(dur, (m.currentTime || 0) + 10));
        syncControlUi();
      });

      playBtn.addEventListener('click', function(ev) {
        ev.stopPropagation();
        var m = findControlledHostMedia(host);
        if (!m) return;
        if (m.paused || m.ended) {
          window.__dragonPipUserPaused = false;
          m.play().catch(function(){});
        } else {
          window.__dragonPipUserPaused = true;
          var nativePauseBtn = window.__dragonNativePause || HTMLMediaElement.prototype.pause;
          nativePauseBtn.call(m);
        }
        syncControlUi();
      });

      muteBtn.addEventListener('click', function(ev) {
        ev.stopPropagation();
        var m = findControlledHostMedia(host);
        if (!m) return;
        m.muted = !m.muted;
        if (!m.muted && m.volume === 0) m.volume = 1.0;
        syncControlUi();
      });

      var pipSpeedSteps = [1.0, 1.25, 1.5, 2.0, 0.75];
      speedBtn.addEventListener('click', function(ev) {
        ev.stopPropagation();
        var m = findControlledHostMedia(host);
        if (!m) return;
        var curRate = Number(m.playbackRate) || 1.0;
        var nextIdx = 1;
        for (var i = 0; i < pipSpeedSteps.length; i++) {
          if (Math.abs(pipSpeedSteps[i] - curRate) < 0.08) {
            nextIdx = (i + 1) % pipSpeedSteps.length;
            break;
          }
        }
        m.playbackRate = pipSpeedSteps[nextIdx];
        syncControlUi();
      });

      function applyHostDimensions(nextW, nextBodyH, optLeftPx) {
        var curScale = computePipUiScale();
        var curVw = Math.max(240, window.innerWidth || 360);
        var curVh = Math.max(240, window.innerHeight || 640);
        var maxLogicalW = Math.max(minW, Math.round((curVw * 0.96) / curScale));
        var maxLogicalH = Math.max(minBodyH, Math.round((curVh * 0.72) / curScale));
        var clampedW = Math.max(minW, Math.min(maxLogicalW, Math.round(nextW)));
        var clampedH = Math.max(minBodyH, Math.min(maxLogicalH, Math.round(nextBodyH)));
        host.style.setProperty('width', clampedW + 'px', 'important');
        host.style.setProperty('transform', 'scale(' + curScale.toFixed(4) + ')', 'important');
        host.style.setProperty('transform-origin', 'top left', 'important');
        bodyWrap.style.setProperty('height', clampedH + 'px', 'important');
        var visualWNow = Math.round(clampedW * curScale);
        var visualHNow = Math.round((clampedH + chromeOverheadH) * curScale);
        var padPx = Math.round(4 * curScale);
        var curRect = host.getBoundingClientRect();
        var rawL = typeof optLeftPx === 'number' ? optLeftPx : curRect.left;
        var clampedL = Math.max(padPx, Math.min(curVw - visualWNow - padPx, Math.round(rawL)));
        var clampedT = Math.max(padPx, Math.min(curVh - visualHNow - padPx, Math.round(curRect.top)));
        host.style.setProperty('left', clampedL + 'px', 'important');
        host.style.setProperty('top', clampedT + 'px', 'important');
        if (window.__dragonDocPipWindow && window.__dragonDocPipWindow.__fireResize) {
          window.__dragonDocPipWindow.__fireResize();
        }
      }

      var hostResizeListener = function() {
        if (window.__dragonInPipMode || host.hasAttribute('data-dragon-host-saved')) return;
        var curScale = computePipUiScale();
        var curLogicalW = Math.max(minW, Math.round(host.getBoundingClientRect().width / Math.max(0.5, curScale)));
        var curLogicalH = Math.max(minBodyH, Math.round((bodyWrap.getBoundingClientRect().height || compactH) / Math.max(0.5, curScale)));
        applyHostDimensions(curLogicalW, curLogicalH);
      };
      host.__dragonResizeListener = hostResizeListener;
      host.__dragonApplyHostDimensions = hostResizeListener;
      window.addEventListener('resize', hostResizeListener);

      var compactW = targetW;
      var compactH = targetBodyH;
      sizeBtn.addEventListener('click', function(ev) {
        ev.stopPropagation();
        var curScale = computePipUiScale();
        var curVw = Math.max(240, window.innerWidth || 360);
        var curVh = Math.max(240, window.innerHeight || 640);
        var curLogicalW = host.getBoundingClientRect().width / curScale;
        var expand = curLogicalW <= defaultCardW * 1.18;
        applyHostDimensions(
          expand ? Math.round((curVw * 0.88) / curScale) : compactW,
          expand ? Math.round((curVh * 0.44) / curScale) : compactH
        );
      });

      closeBtn.addEventListener('click', function(ev) {
        ev.stopPropagation();
        if (typeof onCloseClick === 'function') onCloseClick();
      });

      // Corner Drag Free Resizing (Bottom-Right & Bottom-Left)
      var resizeState = null;
      function beginCornerResize(corner, clientX, clientY) {
        if (window.__dragonInPipMode) return;
        var curScale = computePipUiScale();
        var r = host.getBoundingClientRect();
        var bh = bodyWrap.getBoundingClientRect().height || (compactH * curScale);
        resizeState = {
          corner: corner,
          sx: clientX,
          sy: clientY,
          scale: curScale,
          sw: r.width / curScale,
          sh: bh / curScale,
          sl: r.left,
          sr: r.right
        };
      }
      function moveCornerResize(clientX, clientY) {
        if (!resizeState) return;
        var sc = resizeState.scale || 1.0;
        var dx = (clientX - resizeState.sx) / sc;
        var dy = (clientY - resizeState.sy) / sc;
        var nextH = resizeState.sh + dy;
        var curVw = Math.max(240, window.innerWidth || 360);
        var maxLogicalW = Math.max(minW, Math.round((curVw * 0.96) / sc));
        if (resizeState.corner === 'br') {
          var nextWBr = resizeState.sw + dx;
          var clampedW = Math.max(minW, Math.min(maxLogicalW, Math.round(nextWBr)));
          var nextL = Math.min(resizeState.sl, Math.max(Math.round(4 * sc), curVw - Math.round(clampedW * sc) - Math.round(4 * sc)));
          applyHostDimensions(clampedW, nextH, nextL);
        } else {
          var nextWBl = resizeState.sw - dx;
          var clampedWBl = Math.max(minW, Math.min(maxLogicalW, Math.round(nextWBl)));
          var anchoredLeft = resizeState.sr - Math.round(clampedWBl * sc);
          applyHostDimensions(clampedWBl, nextH, anchoredLeft);
        }
      }
      function endCornerResize() { resizeState = null; }

      resizeRight.addEventListener('touchstart', function(ev) {
        ev.stopPropagation();
        if (ev.touches && ev.touches[0]) beginCornerResize('br', ev.touches[0].clientX, ev.touches[0].clientY);
      }, { passive: true });
      resizeRight.addEventListener('touchmove', function(ev) {
        if (resizeState && ev.touches && ev.touches[0]) {
          ev.preventDefault();
          ev.stopPropagation();
          moveCornerResize(ev.touches[0].clientX, ev.touches[0].clientY);
        }
      }, { passive: false });
      resizeRight.addEventListener('touchend', endCornerResize, { passive: true });
      resizeRight.addEventListener('mousedown', function(ev) {
        ev.stopPropagation();
        beginCornerResize('br', ev.clientX, ev.clientY);
      });

      resizeLeft.addEventListener('touchstart', function(ev) {
        ev.stopPropagation();
        if (ev.touches && ev.touches[0]) beginCornerResize('bl', ev.touches[0].clientX, ev.touches[0].clientY);
      }, { passive: true });
      resizeLeft.addEventListener('touchmove', function(ev) {
        if (resizeState && ev.touches && ev.touches[0]) {
          ev.preventDefault();
          ev.stopPropagation();
          moveCornerResize(ev.touches[0].clientX, ev.touches[0].clientY);
        }
      }, { passive: false });
      resizeLeft.addEventListener('touchend', endCornerResize, { passive: true });
      resizeLeft.addEventListener('mousedown', function(ev) {
        ev.stopPropagation();
        beginCornerResize('bl', ev.clientX, ev.clientY);
      });

      // Two-Finger Pinch-to-Resize on Floating Host
      var pinchState = null;
      host.addEventListener('touchstart', function(ev) {
        if (window.__dragonInPipMode) return;
        if (ev.touches && ev.touches.length === 2) {
          var dx = ev.touches[0].clientX - ev.touches[1].clientX;
          var dy = ev.touches[0].clientY - ev.touches[1].clientY;
          var dist = Math.hypot(dx, dy);
          if (dist > 10) {
            var curScale = computePipUiScale();
            pinchState = {
              dist: dist,
              sw: host.getBoundingClientRect().width / curScale,
              sh: (bodyWrap.getBoundingClientRect().height || (compactH * curScale)) / curScale
            };
          }
        }
      }, { passive: true });
      host.addEventListener('touchmove', function(ev) {
        if (pinchState && ev.touches && ev.touches.length === 2) {
          ev.preventDefault();
          var dx = ev.touches[0].clientX - ev.touches[1].clientX;
          var dy = ev.touches[0].clientY - ev.touches[1].clientY;
          var dist = Math.hypot(dx, dy);
          var scale = dist / pinchState.dist;
          applyHostDimensions(pinchState.sw * scale, pinchState.sh * scale);
        }
      }, { passive: false });
      host.addEventListener('touchend', function(ev) {
        if (!ev.touches || ev.touches.length < 2) pinchState = null;
      }, { passive: true });

      // Touch + Pointer Dragging on header bar
      var dragState = null;
      function startDrag(clientX, clientY) {
        if (window.__dragonInPipMode) return;
        var r = host.getBoundingClientRect();
        dragState = { sx: clientX, sy: clientY, ox: r.left, oy: r.top, w: r.width, h: r.height };
      }
      function moveDrag(clientX, clientY) {
        if (!dragState) return;
        var curVw = Math.max(240, window.innerWidth || 360);
        var curVh = Math.max(240, window.innerHeight || 640);
        var nl = Math.max(4, Math.min(curVw - dragState.w - 4, dragState.ox + (clientX - dragState.sx)));
        var nt = Math.max(4, Math.min(curVh - dragState.h - 4, dragState.oy + (clientY - dragState.sy)));
        host.style.setProperty('left', Math.round(nl) + 'px', 'important');
        host.style.setProperty('top', Math.round(nt) + 'px', 'important');
      }
      function endDrag() { dragState = null; }

      bar.addEventListener('touchstart', function(ev) {
        if (sizeBtn.contains(ev.target) || closeBtn.contains(ev.target)) return;
        if (ev.touches && ev.touches[0]) {
          startDrag(ev.touches[0].clientX, ev.touches[0].clientY);
        }
      }, { passive: true });
      bar.addEventListener('touchmove', function(ev) {
        if (dragState && ev.touches && ev.touches[0]) {
          ev.preventDefault();
          moveDrag(ev.touches[0].clientX, ev.touches[0].clientY);
        }
      }, { passive: false });
      bar.addEventListener('touchend', endDrag, { passive: true });
      bar.addEventListener('mousedown', function(ev) {
        if (sizeBtn.contains(ev.target) || closeBtn.contains(ev.target)) return;
        startDrag(ev.clientX, ev.clientY);
      });
      window.addEventListener('mousemove', function(ev) {
        if (dragState) moveDrag(ev.clientX, ev.clientY);
        if (resizeState) moveCornerResize(ev.clientX, ev.clientY);
        if (isScrubbing) seekFromClientX(ev.clientX);
      });
      window.addEventListener('mouseup', function() {
        endDrag();
        endCornerResize();
        if (isScrubbing) {
          isScrubbing = false;
          syncControlUi();
        }
      });

      return { host: host, bar: bar, bodyWrap: bodyWrap, head: pipHead, body: pipBody, controlsBar: controlsBar };
    }

    function restoreSavedVideoSlot() {
      var slot = window.__dragonPipSavedSlot;
      window.__dragonPipSavedSlot = null;
      if (!slot || !slot.video) return;
      var v = slot.video;
      var wasPaused = v.paused;
      window.__dragonSuppressPauseBridgeUntil = Date.now() + 900;
      try {
        if (slot.placeholder && slot.placeholder.parentNode) {
          slot.placeholder.parentNode.insertBefore(v, slot.placeholder);
          slot.placeholder.parentNode.removeChild(slot.placeholder);
        } else if (slot.parent) {
          slot.parent.insertBefore(v, slot.nextSibling && slot.nextSibling.parentNode === slot.parent ? slot.nextSibling : null);
        }
      } catch (e) {}
      try {
        if (slot.savedCss) v.setAttribute('style', slot.savedCss);
        else v.removeAttribute('style');
      } catch (e) {}
      if (!wasPaused) {
        try { v.play().catch(function(){}); } catch (e) {}
        setTimeout(function() {
          try { if (v.paused && !v.ended && !window.__dragonPipUserPaused) v.play().catch(function(){}); } catch (e) {}
        }, 120);
      }
      try {
        v.dispatchEvent(new Event('leavepictureinpicture', { bubbles: false }));
      } catch (e) {}
      setTimeout(function() {
        try { window.dispatchEvent(new Event('resize')); } catch (e) {}
      }, 90);
    }

    function teardownAllFloatingPip() {
      if (window.__dragonDocPipWindow && !window.__dragonDocPipWindow.closed) {
        try { window.__dragonDocPipWindow.close(); } catch (e) {}
      }
      restoreSavedVideoSlot();
      var host = document.getElementById('__dragon_doc_pip_host');
      if (host) {
        if (host.__dragonSyncTimer) {
          clearInterval(host.__dragonSyncTimer);
          host.__dragonSyncTimer = null;
        }
        if (host.__dragonResizeListener) {
          try { window.removeEventListener('resize', host.__dragonResizeListener); } catch (e) {}
          host.__dragonResizeListener = null;
        }
        if (host.parentNode) host.parentNode.removeChild(host);
      }
      var spotStyle = document.getElementById('__dragon_pip_spotlight_style');
      if (spotStyle && spotStyle.parentNode) spotStyle.parentNode.removeChild(spotStyle);
      if (document.documentElement) document.documentElement.classList.remove('__dragon-pip-active');
      if (document.body) document.body.classList.remove('__dragon-pip-active');
      var staleAnc = document.querySelectorAll('.__dragon-pip-ancestor');
      for (var sa = 0; sa < staleAnc.length; sa++) staleAnc[sa].classList.remove('__dragon-pip-ancestor');
      var staleSib = document.querySelectorAll('.__dragon-pip-hidden-sibling');
      for (var ss = 0; ss < staleSib.length; ss++) staleSib[ss].classList.remove('__dragon-pip-hidden-sibling');
      var staleVid = document.querySelectorAll('.__dragon-pip-video');
      for (var sv = 0; sv < staleVid.length; sv++) staleVid[sv].classList.remove('__dragon-pip-video');
      if (window.__dragonPipSavedAncestors && window.__dragonPipSavedAncestors.length) {
        for (var sa2 = 0; sa2 < window.__dragonPipSavedAncestors.length; sa2++) {
          try {
            var rec = window.__dragonPipSavedAncestors[sa2];
            if (rec && rec.el) {
              if (rec.origCss) rec.el.setAttribute('style', rec.origCss);
              else rec.el.removeAttribute('style');
            }
          } catch (e) {}
        }
        window.__dragonPipSavedAncestors = [];
      }
      if (window.__dragonPipShadowStyles && window.__dragonPipShadowStyles.length) {
        for (var sh = 0; sh < window.__dragonPipShadowStyles.length; sh++) {
          try {
            var sNode = window.__dragonPipShadowStyles[sh];
            if (sNode && sNode.parentNode) sNode.parentNode.removeChild(sNode);
          } catch (e) {}
        }
        window.__dragonPipShadowStyles = [];
      }
      if (typeof window.__dragonSetPipViewportLock === 'function') {
        window.__dragonSetPipViewportLock(false);
      }
      window.__dragonPipActiveElement = null;
      window.__dragonFloatingActive = false;
      window.__dragonPipModeKind = null;
      window.__dragonInPipMode = false;
      window.__dragonSpotlightActive = false;
      notifyNativeFloatingPip(false, false, null);
    }
""")
        append("""

    window.__dragonApplyHostSpotlight = function(enable) {
      var host = document.getElementById('__dragon_doc_pip_host');
      var bar = document.getElementById('__dragon_doc_pip_bar');
      var bodyWrap = document.getElementById('__dragon_doc_pip_body_wrap');
      var controlsBar = document.getElementById('__dragon_doc_pip_controls');
      if (!host) return false;
      var styleId = '__dragon_pip_spotlight_style';
      if (enable) {
        if (typeof window.__dragonSetPipViewportLock === 'function') {
          window.__dragonSetPipViewportLock(true);
        }
        var existingStyle = document.getElementById(styleId);
        if (!existingStyle) {
          existingStyle = document.createElement('style');
          existingStyle.id = styleId;
          existingStyle.textContent =
            'html.__dragon-pip-active, body.__dragon-pip-active { background:#000 !important; overflow:hidden !important; width:100% !important; height:100% !important; margin:0 !important; padding:0 !important; transform:none !important; filter:none !important; contain:none !important; } ' +
            '.__dragon-pip-hidden-sibling { visibility:hidden !important; opacity:0 !important; pointer-events:none !important; z-index:-1 !important; } ' +
            '#__dragon_doc_pip_host, #__dragon_doc_pip_host * { visibility:visible !important; pointer-events:auto !important; } ' +
            '#__dragon_doc_pip_host video { position:relative !important; inset:auto !important; top:0 !important; left:0 !important; width:100% !important; height:100% !important; max-width:100% !important; max-height:100% !important; transform:none !important; object-fit:contain !important; background:#000 !important; display:block !important; opacity:1 !important; visibility:visible !important; }';
          (document.head || document.documentElement).appendChild(existingStyle);
        }
        if (document.documentElement) document.documentElement.classList.add('__dragon-pip-active');
        if (document.body) document.body.classList.add('__dragon-pip-active');
        // Hide siblings of host in-place (NEVER reparent host between body & html, which tears down <video> GPU surface)
        var hostParent = host.parentNode;
        if (hostParent && hostParent.children) {
          for (var s = 0; s < hostParent.children.length; s++) {
            var sib = hostParent.children[s];
            if (sib !== host && sib.tagName !== 'STYLE' && sib.tagName !== 'SCRIPT') {
              sib.classList.add('__dragon-pip-hidden-sibling');
            }
          }
        }
        if (!host.hasAttribute('data-dragon-host-saved')) {
          host.setAttribute('data-dragon-host-saved', '1');
          host.__dragonSavedHostCss = host.style.cssText;
          if (bodyWrap) host.__dragonSavedWrapCss = bodyWrap.style.cssText;
        }
        var hv = host.querySelector('video');
        if (bar) bar.style.setProperty('display', 'none', 'important');
        if (controlsBar) controlsBar.style.setProperty('display', 'none', 'important');
        host.style.cssText =
          'position:fixed !important;inset:0 !important;top:0 !important;left:0 !important;right:0 !important;bottom:0 !important;' +
          'width:100vw !important;height:100vh !important;max-width:none !important;max-height:none !important;' +
          'transform:none !important;border-radius:0 !important;border:none !important;' +
          'background:#000 !important;z-index:2147483647 !important;display:flex !important;flex-direction:column !important;margin:0 !important;padding:0 !important;';
        if (bodyWrap) {
          bodyWrap.style.cssText = 'position:relative !important;width:100% !important;height:100% !important;flex:1 !important;background:#000 !important;overflow:hidden !important;display:flex !important;flex-direction:column !important;';
        }
        if (hv && hv.paused && !hv.ended && !window.__dragonPipUserPaused && hv.readyState >= 2) {
          try { hv.play().catch(function(){}); } catch (e) {}
        }
      } else {
        var st = document.getElementById(styleId);
        if (st && st.parentNode) st.parentNode.removeChild(st);
        if (document.documentElement) document.documentElement.classList.remove('__dragon-pip-active');
        if (document.body) document.body.classList.remove('__dragon-pip-active');
        var hiddenEls = document.querySelectorAll('.__dragon-pip-hidden-sibling');
        for (var h = 0; h < hiddenEls.length; h++) {
          hiddenEls[h].classList.remove('__dragon-pip-hidden-sibling');
        }
        if (bar) bar.style.setProperty('display', 'flex', 'important');
        if (controlsBar) controlsBar.style.setProperty('display', 'flex', 'important');
        if (host.hasAttribute('data-dragon-host-saved')) {
          if (host.__dragonSavedHostCss) host.style.cssText = host.__dragonSavedHostCss;
          if (bodyWrap && host.__dragonSavedWrapCss) bodyWrap.style.cssText = host.__dragonSavedWrapCss;
          host.removeAttribute('data-dragon-host-saved');
        }
        if (typeof window.__dragonSetPipViewportLock === 'function') {
          window.__dragonSetPipViewportLock(false);
        }
        var hvOut = host.querySelector('video');
        if (hvOut && hvOut.paused && !hvOut.ended && !window.__dragonPipUserPaused && hvOut.readyState >= 2) {
          try { hvOut.play().catch(function(){}); } catch (e) {}
        }
        setTimeout(function() {
          try {
            if (host.__dragonApplyHostDimensions && !window.__dragonInPipMode) {
              host.__dragonApplyHostDimensions();
            }
          } catch (e) {}
        }, 120);
      }
      if (window.__dragonDocPipWindow && window.__dragonDocPipWindow.__fireResize) {
        window.__dragonDocPipWindow.__fireResize();
      }
      return true;
    };

    try {
      Object.defineProperty(Document.prototype, 'pictureInPictureEnabled', {
        get: function() { return true; },
        configurable: true
      });
    } catch (e) {}
    try {
      Object.defineProperty(document, 'pictureInPictureEnabled', {
        get: function() { return true; },
        configurable: true
      });
    } catch (e) {}

    try {
      Object.defineProperty(Document.prototype, 'pictureInPictureElement', {
        get: function() { return window.__dragonPipActiveElement || null; },
        configurable: true
      });
    } catch (e) {}

    Document.prototype.exitPictureInPicture = function() {
      teardownAllFloatingPip();
      return Promise.resolve();
    };
    try { document.exitPictureInPicture = Document.prototype.exitPictureInPicture; } catch (e) {}

    try {
      Object.defineProperty(HTMLVideoElement.prototype, 'disablePictureInPicture', {
        get: function() { return !!this.__dragonDisablePip; },
        set: function(val) { this.__dragonDisablePip = !!val; },
        configurable: true
      });
    } catch (e) {}

    HTMLVideoElement.prototype.requestPictureInPicture = function() {
      var self = this;
      try {
        if (window.__dragonDocPipWindow && !window.__dragonDocPipWindow.closed) {
          window.__dragonDocPipWindow.close();
        }
        if (window.__dragonPipSavedSlot && window.__dragonPipSavedSlot.video === self) {
          var alreadyM = measureVideoForBridge(self);
          return Promise.resolve({
            width: Math.max(240, alreadyM.w),
            height: Math.max(135, alreadyM.h),
            onresize: null,
            addEventListener: function() {},
            removeEventListener: function() {},
            dispatchEvent: function() { return true; }
          });
        }
        restoreSavedVideoSlot();

        window.__dragonLastActiveVideo = self;
        window.__dragonPipVideoEl = self;
        window.__dragonPipActiveElement = self;
        window.__dragonPipUserPaused = false;
        self.setAttribute('playsinline', '');
        self.setAttribute('webkit-playsinline', '');
        var wasPlaying = !self.paused && !self.ended;

        var m = measureVideoForBridge(self);
        var hostEls = ensureFloatingPipHost('video', 0, 0, function() {
          teardownAllFloatingPip();
        });
        if (hostEls && self.parentNode) {
          var rect = self.getBoundingClientRect();
          var placeholder = document.createElement('div');
          placeholder.setAttribute('data-dragon-pip-placeholder', '1');
          placeholder.style.cssText =
            'width:' + Math.max(1, Math.round(rect.width)) + 'px;' +
            'height:' + Math.max(1, Math.round(rect.height)) + 'px;' +
            'background:#0b0f17;display:flex;align-items:center;justify-content:center;' +
            'color:#00E5FF;font:600 12px system-ui,sans-serif;border-radius:8px;';
          placeholder.textContent = 'Playing in floating window';
          window.__dragonPipSavedSlot = {
            video: self,
            parent: self.parentNode,
            nextSibling: self.nextSibling,
            savedCss: self.getAttribute('style') || '',
            placeholder: placeholder
          };
          window.__dragonSuppressPauseBridgeUntil = Date.now() + 900;
          self.parentNode.insertBefore(placeholder, self);
          hostEls.body.appendChild(self);
          self.style.cssText = 'position:relative !important;width:100% !important;height:100% !important;max-width:100% !important;max-height:100% !important;object-fit:contain !important;background:#000 !important;display:block !important;';
          if (wasPlaying || self.readyState >= 1) {
            self.play().catch(function(){});
            setTimeout(function() {
              try { if (self.paused && !self.ended && !window.__dragonPipUserPaused) self.play().catch(function(){}); } catch (e) {}
            }, 140);
          }
        } else if (self.paused && self.readyState >= 1) {
          self.play().catch(function(){});
        }

        window.__dragonFloatingActive = true;
        window.__dragonPipModeKind = 'video';
        notifyNativeFloatingPip(true, false, self);

        var pipWin = {
          width: Math.max(240, m.w),
          height: Math.max(135, m.h),
          onresize: null,
          addEventListener: function() {},
          removeEventListener: function() {},
          dispatchEvent: function() { return true; }
        };
        setTimeout(function() {
          try {
            var ev = new Event('enterpictureinpicture', { bubbles: false });
            try { ev.pictureInPictureWindow = pipWin; } catch (e2) {}
            self.dispatchEvent(ev);
          } catch (e) {}
        }, 16);
        return Promise.resolve(pipWin);
      } catch (err) {
        return Promise.reject(err);
      }
    };

    // W3C Document Picture-in-Picture API (required by YouTube Advanced Floating Window)
    var docPipTargetObj = { onenter: null };
    var docPipEmitter = createSimpleEventTarget(docPipTargetObj);

    var docPipApi = {
      get window() {
        var cur = window.__dragonDocPipWindow;
        return cur && !cur.closed ? cur : null;
      },
      get onenter() { return docPipTargetObj.onenter; },
      set onenter(fn) { docPipTargetObj.onenter = fn; },
      addEventListener: function(t, f) { docPipEmitter.addEventListener(t, f); },
      removeEventListener: function(t, f) { docPipEmitter.removeEventListener(t, f); },
      dispatchEvent: function(ev) { return docPipEmitter.dispatchEvent(ev); },
      requestWindow: function(options) {
        try {
          if (window.__dragonDocPipWindow && !window.__dragonDocPipWindow.closed) {
            try { window.__dragonDocPipWindow.close(); } catch (e) {}
          }
          restoreSavedVideoSlot();

          var opts = options || {};
          var hostEls = ensureFloatingPipHost(
            'document',
            Number(opts.width) || 0,
            Number(opts.height) || 0,
            function() {
              if (window.__dragonDocPipWindow && !window.__dragonDocPipWindow.closed) {
                window.__dragonDocPipWindow.close();
              } else {
                teardownAllFloatingPip();
              }
            }
          );
          if (!hostEls) {
            return Promise.reject(new Error('Document body not ready'));
          }

          var isClosed = false;
          var pipWinCore = {
            closed: false,
            onpagehide: null,
            onunload: null,
            onresize: null,
            onfocus: null,
            onblur: null,
            focus: function() {},
            blur: function() {}
          };
          var pipWinEmitter = createSimpleEventTarget(pipWinCore);

          var pipDocCore = {
            get head() { return hostEls.head; },
            get body() { return hostEls.body; },
            get documentElement() { return hostEls.bodyWrap; },
            get defaultView() { return pipWindowProxy; },
            get hidden() { return false; },
            get visibilityState() { return 'visible'; },
            get activeElement() { return document.activeElement; },
            get title() {
              var tEl = document.getElementById('__dragon_doc_pip_title');
              return tEl ? tEl.textContent : 'Floating Player';
            },
            set title(val) {
              var tEl = document.getElementById('__dragon_doc_pip_title');
              if (tEl && val) tEl.textContent = String(val);
            },
            createElement: function(tag, o) { return document.createElement(tag, o); },
            createElementNS: function(ns, tag, o) { return document.createElementNS(ns, tag, o); },
            createTextNode: function(text) { return document.createTextNode(text); },
            createComment: function(text) { return document.createComment(text); },
            createDocumentFragment: function() { return document.createDocumentFragment(); },
            importNode: function(node, deep) {
              var imported = document.importNode(node, deep);
              return filterStyleNodeForHost(imported);
            },
            adoptNode: function(node) {
              return filterStyleNodeForHost(node);
            },
            getElementById: function(id) {
              try {
                return hostEls.host.querySelector('[id="' + String(id).replace(/"/g, '\\"') + '"]');
              } catch (e) { return null; }
            },
            querySelector: function(sel) { return hostEls.host.querySelector(sel); },
            querySelectorAll: function(sel) { return hostEls.host.querySelectorAll(sel); },
            getElementsByClassName: function(cls) { return hostEls.host.getElementsByClassName(cls); },
            getElementsByTagName: function(tag) { return hostEls.host.getElementsByTagName(tag); },
            hasFocus: function() { return true; }
          };
          var pipDocEmitter = createSimpleEventTarget(pipDocCore);
          pipDocCore.addEventListener = function(t, f) { pipDocEmitter.addEventListener(t, f); };
          pipDocCore.removeEventListener = function(t, f) { pipDocEmitter.removeEventListener(t, f); };
          pipDocCore.dispatchEvent = function(ev) { return pipDocEmitter.dispatchEvent(ev); };

          var pipDocProxy = new Proxy(pipDocCore, {
            get: function(target, prop) {
              if (prop in target) {
                var val = target[prop];
                return typeof val === 'function' ? val.bind(target) : val;
              }
              var fallback = document[prop];
              return typeof fallback === 'function' ? fallback.bind(document) : fallback;
            },
            set: function(target, prop, value) {
              target[prop] = value;
              return true;
            }
          });

          Object.defineProperty(pipWinCore, 'document', { get: function() { return pipDocProxy; } });
          Object.defineProperty(pipWinCore, 'innerWidth', {
            get: function() { return Math.max(160, hostEls.body.clientWidth || 260); }
          });
          Object.defineProperty(pipWinCore, 'innerHeight', {
            get: function() { return Math.max(120, hostEls.body.clientHeight || 200); }
          });
          Object.defineProperty(pipWinCore, 'outerWidth', {
            get: function() { return Math.max(160, hostEls.host.clientWidth || 260); }
          });
          Object.defineProperty(pipWinCore, 'outerHeight', {
            get: function() { return Math.max(120, hostEls.host.clientHeight || 230); }
          });
          Object.defineProperty(pipWinCore, 'closed', { get: function() { return isClosed; } });
          Object.defineProperty(pipWinCore, 'devicePixelRatio', { get: function() { return window.devicePixelRatio || 1; } });
          Object.defineProperty(pipWinCore, 'opener', { get: function() { return window; } });
          pipWinCore.addEventListener = function(t, f) { pipWinEmitter.addEventListener(t, f); };
          pipWinCore.removeEventListener = function(t, f) { pipWinEmitter.removeEventListener(t, f); };
          pipWinCore.dispatchEvent = function(ev) { return pipWinEmitter.dispatchEvent(ev); };
          pipWinCore.__fireResize = function() {
            try { pipWinEmitter.dispatchEvent(new Event('resize')); } catch (e) {}
          };
          pipWinCore.close = function() {
            if (isClosed) return;
            try {
              var hideEv = new Event('pagehide');
              try { hideEv.persisted = false; } catch (e2) {}
              pipWinEmitter.dispatchEvent(hideEv);
              pipDocEmitter.dispatchEvent(hideEv);
            } catch (e) {}
            try {
              var unloadEv = new Event('unload');
              pipWinEmitter.dispatchEvent(unloadEv);
              pipDocEmitter.dispatchEvent(unloadEv);
            } catch (e) {}
            isClosed = true;
            if (window.__dragonDocPipWindow === pipWindowProxy) {
              window.__dragonDocPipWindow = null;
            }
            if (hostEls.host && hostEls.host.parentNode) {
              hostEls.host.parentNode.removeChild(hostEls.host);
            }
            window.__dragonFloatingActive = false;
            window.__dragonPipModeKind = null;
            window.__dragonInPipMode = false;
            notifyNativeFloatingPip(false, true, null);
          };

          var pipWindowProxy = new Proxy(pipWinCore, {
            get: function(target, prop) {
              if (prop === 'window' || prop === 'self') return pipWindowProxy;
              if (prop in target) {
                var val = target[prop];
                return typeof val === 'function' ? val.bind(target) : val;
              }
              var globalVal = window[prop];
              if (typeof globalVal === 'function' && !/^[A-Z]/.test(String(prop))) {
                return globalVal.bind(window);
              }
              return globalVal;
            },
            set: function(target, prop, value) {
              target[prop] = value;
              return true;
            }
          });

          window.__dragonDocPipWindow = pipWindowProxy;
          window.__dragonFloatingActive = true;
          window.__dragonPipModeKind = 'document';

          var activeVid = findPrimaryVideo(document);
          if (activeVid) {
            window.__dragonLastActiveVideo = activeVid;
            window.__dragonPipActiveElement = activeVid;
          }
          notifyNativeFloatingPip(true, true, activeVid || hostEls.host);

          setTimeout(function() {
            try {
              var enterEv = new Event('enter');
              try { enterEv.window = pipWindowProxy; } catch (e2) {}
              docPipEmitter.dispatchEvent(enterEv);
            } catch (e) {}
          }, 0);

          return Promise.resolve(pipWindowProxy);
        } catch (err) {
          return Promise.reject(err);
        }
      }
    };

    try {
      window.DocumentPictureInPicture = function DocumentPictureInPicture() {};
      Object.defineProperty(window, 'documentPictureInPicture', {
        get: function() { return docPipApi; },
        configurable: true
      });
    } catch (e) {
      window.documentPictureInPicture = docPipApi;
    }

    // Wrap navigator.mediaSession.setActionHandler('enterpictureinpicture', ...) safely for Android WebView
    try {
      if (navigator.mediaSession && typeof navigator.mediaSession.setActionHandler === 'function') {
        var origSetAction = navigator.mediaSession.setActionHandler.bind(navigator.mediaSession);
        navigator.mediaSession.setActionHandler = function(action, handler) {
          if (action === 'enterpictureinpicture') {
            window.__dragonPipMediaSessionHandler = typeof handler === 'function' ? handler : null;
          }
          try {
            return origSetAction(action, handler);
          } catch (err) {
            if (action === 'enterpictureinpicture') return undefined;
            throw err;
          }
        };
      }
    } catch (e) {}

    try {
      window.__dragonAnyMediaPlaying = window.__dragonAnyMediaPlaying || false;
      window.__dragonLastPlayingMs = window.__dragonLastPlayingMs || 0;
      window.__dragonLastTrustedGestureMs = window.__dragonLastTrustedGestureMs || 0;
      window.__dragonPipTransitionUntil = window.__dragonPipTransitionUntil || 0;

      // Track genuine user taps/clicks (ignore touchstart/touchcancel so Android swipe-home never looks like a pause tap)
      ['click', 'pointerup', 'touchend', 'keydown'].forEach(function(evtName) {
        document.addEventListener(evtName, function(evt) {
          if (evt && evt.isTrusted && !window.__dragonInPipMode) {
            window.__dragonLastTrustedGestureMs = Date.now();
          }
        }, true);
      });
      document.addEventListener('touchcancel', function() {
        window.__dragonLastTrustedGestureMs = 0;
      }, true);

      var origHiddenDesc = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden');
      var origWebkitHiddenDesc = Object.getOwnPropertyDescriptor(Document.prototype, 'webkitHidden');
      var origVisDesc = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
      var origWebkitVisDesc = Object.getOwnPropertyDescriptor(Document.prototype, 'webkitVisibilityState');
      var origHasFocus = typeof Document.prototype.hasFocus === 'function' ? Document.prototype.hasFocus : null;

      function isRealDocumentHidden(doc) {
        try {
          if (origHiddenDesc && origHiddenDesc.get) return !!origHiddenDesc.get.call(doc || document);
        } catch (e) {}
        return false;
      }

      function isRealDocumentFocused(doc) {
        try {
          if (origHasFocus) return !!origHasFocus.call(doc || document);
        } catch (e) {}
        return true;
      }

      function hasLivePlayingMediaInDom() {
        try {
          var list = document.querySelectorAll('video, audio');
          for (var i = 0; i < list.length; i++) {
            if (!list[i].paused && !list[i].ended && list[i].readyState >= 1) return true;
          }
        } catch (e) {}
        return false;
      }

      function shouldMaskPageHidden() {
        var now = Date.now();
        if (window.__dragonInPipMode || window.__dragonBackgroundPlayActive || window.__dragonFloatingActive || window.__dragonAnyMediaPlaying) return true;
        if (window.__dragonPipTransitionUntil && now < window.__dragonPipTransitionUntil) return true;
        if (!window.__dragonPipUserPaused && window.__dragonLastPlayingMs && (now - window.__dragonLastPlayingMs) < 4000) return true;
        return hasLivePlayingMediaInDom();
      }

      if (origHiddenDesc && origHiddenDesc.configurable) {
        Object.defineProperty(Document.prototype, 'hidden', {
          get: function() {
            if (shouldMaskPageHidden()) return false;
            return origHiddenDesc.get ? origHiddenDesc.get.call(this) : false;
          },
          configurable: true
        });
      }
      if (origWebkitHiddenDesc && origWebkitHiddenDesc.configurable) {
        Object.defineProperty(Document.prototype, 'webkitHidden', {
          get: function() {
            if (shouldMaskPageHidden()) return false;
            return origWebkitHiddenDesc.get ? origWebkitHiddenDesc.get.call(this) : false;
          },
          configurable: true
        });
      }
      if (origVisDesc && origVisDesc.configurable) {
        Object.defineProperty(Document.prototype, 'visibilityState', {
          get: function() {
            if (shouldMaskPageHidden()) return 'visible';
            return origVisDesc.get ? origVisDesc.get.call(this) : 'visible';
          },
          configurable: true
        });
      }
      if (origWebkitVisDesc && origWebkitVisDesc.configurable) {
        Object.defineProperty(Document.prototype, 'webkitVisibilityState', {
          get: function() {
            if (shouldMaskPageHidden()) return 'visible';
            return origWebkitVisDesc.get ? origWebkitVisDesc.get.call(this) : 'visible';
          },
          configurable: true
        });
      }
      if (origHasFocus) {
        Document.prototype.hasFocus = function() {
          if (shouldMaskPageHidden()) return true;
          return origHasFocus.call(this);
        };
      }

      ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'freeze'].forEach(function(visEvtName) {
        document.addEventListener(visEvtName, function(evt) {
          if (shouldMaskPageHidden()) {
            evt.stopImmediatePropagation();
          }
        }, true);
        window.addEventListener(visEvtName, function(evt) {
          if (shouldMaskPageHidden()) {
            evt.stopImmediatePropagation();
          }
        }, true);
      });
      // Block window/document blur while media is playing or entering PiP (mobile YouTube pauses on window blur)
      window.addEventListener('blur', function(evt) {
        if (evt && (evt.target === window || evt.target === document) && shouldMaskPageHidden()) {
          evt.stopImmediatePropagation();
        }
      }, true);
      document.addEventListener('blur', function(evt) {
        if (evt && (evt.target === window || evt.target === document) && shouldMaskPageHidden()) {
          evt.stopImmediatePropagation();
        }
      }, true);

      // Intercept HTMLMediaElement.prototype.play / pause at document_start so mobile YouTube
      // cannot programmatically pause the active video when entering or playing in PiP.
      if (typeof HTMLMediaElement !== 'undefined' && HTMLMediaElement.prototype) {
        var origMediaPlay = HTMLMediaElement.prototype.play;
        var origMediaPause = HTMLMediaElement.prototype.pause;
        window.__dragonNativePause = origMediaPause;
        if (typeof origMediaPlay === 'function') {
          HTMLMediaElement.prototype.play = function() {
            try {
              window.__dragonPipUserPaused = false;
              window.__dragonLastPlayingMs = Date.now();
              window.__dragonAnyMediaPlaying = true;
              if (this.tagName === 'VIDEO') {
                window.__dragonLastActiveVideo = this;
              } else if (this.tagName === 'AUDIO') {
                window.__dragonLastActiveAudio = this;
              }
            } catch (e) {}
            return origMediaPlay.apply(this, arguments);
          };
        }
        if (typeof origMediaPause === 'function') {
          HTMLMediaElement.prototype.pause = function() {
            try {
              if (!window.__dragonPipUserPaused && !this.ended && (this.tagName === 'VIDEO' || this.tagName === 'AUDIO')) {
                var now = Date.now();
                var inPip = !!(window.__dragonInPipMode || window.__dragonBackgroundPlayActive);
                var inTransition = !!(window.__dragonPipTransitionUntil && now < window.__dragonPipTransitionUntil);
                var hiddenOrUnfocused = isRealDocumentHidden(document) || !isRealDocumentFocused(document);
                var recentUserTap = (now - (window.__dragonLastTrustedGestureMs || 0)) < 500;
                if (inPip || (inTransition && !recentUserTap) || (hiddenOrUnfocused && !recentUserTap && !this.paused && this.readyState >= 1)) {
                  return;
                }
              }
            } catch (e) {}
            return origMediaPause.apply(this, arguments);
          };
        }
      }
    } catch (e) {}
  } catch (e) {}
})();
""")
    }
}

private const val PIP_TRIGGER_FIRST_VIDEO_JS = """
(function() {
  try {
    if (window.__dragonFloatingActive) {
      if (document.exitPictureInPicture) document.exitPictureInPicture();
      return 'true';
    }
    var vids = document.querySelectorAll('video');
    var target = null;
    var bestScore = -1;
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      var rect = v.getBoundingClientRect();
      var area = Math.max(0, rect.width * rect.height);
      var hasFrames = (v.videoWidth > 0 && v.videoHeight > 0);
      var playing = (!v.paused && !v.ended);
      var score = (playing ? 100000000 : 0) + (hasFrames ? 10000000 : 0) + (v.readyState >= 2 ? 1000000 : 0) + Math.min(area, 500000);
      if ((playing || hasFrames || v.readyState >= 1) && score > bestScore) {
        bestScore = score;
        target = v;
      }
    }
    if (!target && window.__dragonLastActiveVideo && document.contains(window.__dragonLastActiveVideo)) {
      target = window.__dragonLastActiveVideo;
    }
    if (!target && vids.length > 0) target = vids[0];
    if (!target) return 'false';
    if (target.paused) { target.play().catch(function(){}); }
    target.requestPictureInPicture();
    return 'true';
  } catch (e) {
    return 'false';
  }
})();
"""

private const val PIP_CLOSE_ALL_JS = """
(function() {
  try {
    if (document.exitPictureInPicture) {
      document.exitPictureInPicture();
    }
  } catch (e) {}
})();
"""

private const val PIP_TOGGLE_ACTIVE_VIDEO_JS = """
(function() {
  try {
    function findTargetMedia(doc) {
      var list = doc.querySelectorAll('video, audio');
      var fallback = null;
      for (var i = 0; i < list.length; i++) {
        var el = list[i];
        if (!el.paused && !el.ended) return el;
        if (!fallback && (el.readyState > 0 || el.currentSrc || el.src)) fallback = el;
      }
      var frames = doc.querySelectorAll('iframe');
      for (var j = 0; j < frames.length; j++) {
        try {
          if (frames[j].contentDocument) {
            var nested = findTargetMedia(frames[j].contentDocument);
            if (nested) return nested;
          }
        } catch (e) {}
      }
      return fallback;
    }
    var target = window.__dragonLastActiveVideo || window.__dragonLastActiveAudio || findTargetMedia(document);
    if (!target) return;
    if (target.paused || target.ended) {
      window.__dragonPipUserPaused = false;
      window.__dragonWasPlayingBeforePip = true;
      window.__dragonLastPlayingMs = Date.now();
      target.play().catch(function(){});
    } else {
      window.__dragonPipUserPaused = true;
      window.__dragonBackgroundPlayActive = false;
      window.__dragonWasPlayingBeforePip = false;
      var nativePauseToggle = window.__dragonNativePause || HTMLMediaElement.prototype.pause;
      nativePauseToggle.call(target);
    }
  } catch (e) {}
})();
"""

private const val PIP_STOP_ALL_MEDIA_JS = """
(function() {
  try {
    window.__dragonAnyMediaPlaying = false;
    window.__dragonPipUserPaused = true;
    window.__dragonBackgroundPlayActive = false;
    window.__dragonWasPlayingBeforePip = false;
    var nativePauseStop = window.__dragonNativePause || HTMLMediaElement.prototype.pause;
    function stopDocMedia(doc) {
      var list = doc.querySelectorAll('video, audio');
      for (var i = 0; i < list.length; i++) {
        try { if (!list[i].paused) nativePauseStop.call(list[i]); } catch (e) {}
      }
      var frames = doc.querySelectorAll('iframe');
      for (var j = 0; j < frames.length; j++) {
        try {
          if (frames[j].contentDocument) stopDocMedia(frames[j].contentDocument);
        } catch (e) {}
      }
    }
    stopDocMedia(document);
  } catch (e) {}
})();
"""

private const val PIP_ARM_TRANSITION_GUARD_JS = """
(function() {
  try {
    var now = Date.now();
    window.__dragonPipTransitionUntil = now + 2600;
    window.__dragonSuppressPauseBridgeUntil = now + 2200;
    var v = window.__dragonPipVideoEl || window.__dragonLastActiveVideo;
    if (!v || !document.contains(v)) {
      var vids = document.querySelectorAll('video');
      for (var i = 0; i < vids.length; i++) {
        if (!vids[i].paused && !vids[i].ended) { v = vids[i]; break; }
      }
    }
    if (v && !v.paused && !v.ended) {
      window.__dragonWasPlayingBeforePip = true;
      window.__dragonLastPlayingMs = now;
      window.__dragonAnyMediaPlaying = true;
      window.__dragonLastActiveVideo = v;
    }
  } catch (e) {}
})();
"""

private const val PIP_ENTER_BACKGROUND_AUDIO_MODE_JS = """
(function() {
  try {
    var now = Date.now();
    window.__dragonInPipMode = false;
    window.__dragonBackgroundPlayActive = true;
    window.__dragonPipTransitionUntil = now + 4500;
    window.__dragonSuppressPauseBridgeUntil = now + 3500;
    var target = window.__dragonPipVideoEl || window.__dragonLastActiveVideo;
    if (!target || !document.contains(target)) {
      var vids = document.querySelectorAll('video');
      for (var i = 0; i < vids.length; i++) {
        if (!vids[i].paused && !vids[i].ended) { target = vids[i]; break; }
        if (!target && vids[i].readyState >= 1) target = vids[i];
      }
    }
    if (!target) return;
    window.__dragonLastActiveVideo = target;
    if (!target.paused && !target.ended) {
      window.__dragonWasPlayingBeforePip = true;
      window.__dragonLastPlayingMs = now;
      window.__dragonAnyMediaPlaying = true;
    }
    if (window.__dragonPipPauseGuardEl && window.__dragonPipPauseGuardFn) {
      try { window.__dragonPipPauseGuardEl.removeEventListener('pause', window.__dragonPipPauseGuardFn, true); } catch (e) {}
      window.__dragonPipPauseGuardEl = null;
      window.__dragonPipPauseGuardFn = null;
    }
    var bgRecoverCount = 0;
    var bgGuardFn = function() {
      try {
        if (!window.__dragonBackgroundPlayActive || window.__dragonPipUserPaused || target.ended) return;
        if (!window.__dragonWasPlayingBeforePip || bgRecoverCount >= 14) return;
        bgRecoverCount++;
        setTimeout(function() {
          try {
            if (!window.__dragonBackgroundPlayActive || window.__dragonPipUserPaused || target.ended) return;
            if (target.paused) target.play().catch(function(){});
          } catch (e) {}
        }, 70);
      } catch (e) {}
    };
    try {
      target.addEventListener('pause', bgGuardFn, true);
      window.__dragonPipPauseGuardEl = target;
      window.__dragonPipPauseGuardFn = bgGuardFn;
    } catch (e) {}
    if (!target.ended && !window.__dragonPipUserPaused && window.__dragonWasPlayingBeforePip) {
      if (target.paused && target.readyState >= 1) {
        target.play().catch(function(){});
      }
      setTimeout(function() {
        try {
          if (!window.__dragonBackgroundPlayActive || window.__dragonPipUserPaused || target.ended) return;
          if (target.paused) target.play().catch(function(){});
        } catch (e) {}
      }, 90);
      setTimeout(function() {
        try {
          if (!window.__dragonBackgroundPlayActive || window.__dragonPipUserPaused || target.ended) return;
          if (target.paused) target.play().catch(function(){});
        } catch (e) {}
      }, 320);
      setTimeout(function() {
        try {
          if (!window.__dragonBackgroundPlayActive || window.__dragonPipUserPaused || target.ended) return;
          if (target.paused) target.play().catch(function(){});
        } catch (e) {}
      }, 850);
    }
  } catch (e) {}
})();
"""

private const val PIP_ENTER_INLINE_SPOTLIGHT_JS = """
(function() {
  try {
    window.__dragonInPipMode = true;
    window.__dragonPipTransitionUntil = Date.now() + 2600;
    window.__dragonSuppressPauseBridgeUntil = Date.now() + 2200;
    var host = document.getElementById('__dragon_doc_pip_host');
    if (host && window.__dragonApplyHostSpotlight) {
      window.__dragonApplyHostSpotlight(true);
      var hv = host.querySelector('video');
      if (hv && hv.paused && !hv.ended && !window.__dragonPipUserPaused && hv.readyState >= 2) {
        hv.play().catch(function(){});
      }
      return;
    }

    function pickBestVideo(doc) {
      var vids = doc.querySelectorAll('video');
      var best = null;
      var bestScore = -1;
      for (var i = 0; i < vids.length; i++) {
        var v = vids[i];
        var rect = v.getBoundingClientRect();
        var area = Math.max(0, rect.width * rect.height);
        var hasFrames = (v.videoWidth > 0 && v.videoHeight > 0);
        var playing = (!v.paused && !v.ended);
        var isLastActive = (v === window.__dragonLastActiveVideo);
        var score =
          (playing ? 100000000 : 0) +
          (hasFrames ? 10000000 : 0) +
          (v.readyState >= 2 ? 1000000 : 0) +
          (isLastActive ? 500000 : 0) +
          Math.min(area, 400000);
        if ((playing || hasFrames || v.readyState >= 1) && score > bestScore) {
          best = v;
          bestScore = score;
        }
      }
      return best;
    }

    var target = pickBestVideo(document);
    if (!target && window.__dragonLastActiveVideo && document.contains(window.__dragonLastActiveVideo)) {
      target = window.__dragonLastActiveVideo;
    }
    if (!target) return;

    if (!window.__dragonSpotlightActive) {
      window.__dragonPipSavedScrollX = window.scrollX || 0;
      window.__dragonPipSavedScrollY = window.scrollY || 0;
    }
    window.__dragonSpotlightActive = true;

    if (typeof window.__dragonSetPipViewportLock === 'function') {
      window.__dragonSetPipViewportLock(true);
    }

    window.__dragonPipVideoEl = target;
    window.__dragonPipActiveElement = target;

    var styleId = '__dragon_pip_spotlight_style';
    var spotlightCssText =
      'html.__dragon-pip-active, body.__dragon-pip-active { background:#000 !important; overflow:hidden !important; width:100% !important; height:100% !important; min-width:0 !important; min-height:0 !important; max-width:100% !important; max-height:100% !important; margin:0 !important; padding:0 !important; border:0 !important; transform:none !important; translate:none !important; scale:none !important; rotate:none !important; filter:none !important; backdrop-filter:none !important; contain:none !important; container-type:normal !important; container:none !important; content-visibility:visible !important; perspective:none !important; will-change:auto !important; } ' +
      '.__dragon-pip-ancestor { position:static !important; visibility:visible !important; opacity:1 !important; background:transparent !important; margin:0 !important; padding:0 !important; border:0 !important; min-width:0 !important; min-height:0 !important; max-width:none !important; max-height:none !important; transform:none !important; translate:none !important; scale:none !important; rotate:none !important; filter:none !important; backdrop-filter:none !important; perspective:none !important; contain:none !important; container-type:normal !important; container:none !important; content-visibility:visible !important; clip:auto !important; clip-path:none !important; mask:none !important; -webkit-mask:none !important; overflow:visible !important; overflow-x:visible !important; overflow-y:visible !important; z-index:auto !important; isolation:auto !important; mix-blend-mode:normal !important; will-change:auto !important; } ' +
      '.__dragon-pip-ancestor::before, .__dragon-pip-ancestor::after { display:none !important; content:none !important; } ' +
      '.__dragon-pip-hidden-sibling { visibility:hidden !important; opacity:0 !important; pointer-events:none !important; z-index:-1 !important; max-width:100vw !important; max-height:100vh !important; overflow:hidden !important; } ' +
      'video.__dragon-pip-video { position:fixed !important; inset:0 !important; top:0 !important; left:0 !important; right:0 !important; bottom:0 !important; width:100% !important; height:100% !important; min-width:100% !important; min-height:100% !important; max-width:100% !important; max-height:100% !important; margin:0 !important; padding:0 !important; transform:none !important; translate:none !important; scale:none !important; rotate:none !important; object-fit:contain !important; background:#000 !important; display:block !important; opacity:1 !important; visibility:visible !important; z-index:2147483647 !important; }';

    // Always disconnect any prior style observer & rAF BEFORE mutating target.style
    if (window.__dragonSpotlightStyleRaf) {
      try { cancelAnimationFrame(window.__dragonSpotlightStyleRaf); } catch (e) {}
      window.__dragonSpotlightStyleRaf = 0;
    }
    if (window.__dragonSpotlightStyleObserver) {
      try { window.__dragonSpotlightStyleObserver.disconnect(); } catch (e) {}
      window.__dragonSpotlightStyleObserver = null;
    }

    var spotStyle = document.getElementById(styleId);
    if (!spotStyle) {
      spotStyle = document.createElement('style');
      spotStyle.id = styleId;
      spotStyle.textContent = spotlightCssText;
      (document.head || document.documentElement).appendChild(spotStyle);
    }

    if (document.documentElement) document.documentElement.classList.add('__dragon-pip-active');
    if (document.body) document.body.classList.add('__dragon-pip-active');

    var wasPlayingNow = (!target.paused && !target.ended) ||
      (!window.__dragonPipUserPaused && (
        window.__dragonAnyMediaPlaying ||
        (window.__dragonLastPlayingMs && (Date.now() - window.__dragonLastPlayingMs) < 4000)
      ));
    if (wasPlayingNow) {
      window.__dragonWasPlayingBeforePip = true;
    }

    if (!target.hasAttribute('data-dragon-pip-saved')) {
      target.setAttribute('data-dragon-pip-saved', '1');
      target.__dragonSavedCss = target.getAttribute('style') || '';
    }
    target.classList.add('__dragon-pip-video');

    if (!window.__dragonPipSavedAncestors) window.__dragonPipSavedAncestors = [];
    if (!window.__dragonPipShadowStyles) window.__dragonPipShadowStyles = [];

    function forceNeutralizeAncestorInline(el) {
      if (!el || !el.style) return;
      if (!el.hasAttribute('data-dragon-anc-saved')) {
        el.setAttribute('data-dragon-anc-saved', '1');
        window.__dragonPipSavedAncestors.push({ el: el, origCss: el.getAttribute('style') || '' });
      }
      el.style.setProperty('position', 'static', 'important');
      el.style.setProperty('transform', 'none', 'important');
      el.style.setProperty('translate', 'none', 'important');
      el.style.setProperty('scale', 'none', 'important');
      el.style.setProperty('rotate', 'none', 'important');
      el.style.setProperty('filter', 'none', 'important');
      el.style.setProperty('backdrop-filter', 'none', 'important');
      el.style.setProperty('perspective', 'none', 'important');
      el.style.setProperty('contain', 'none', 'important');
      el.style.setProperty('container-type', 'normal', 'important');
      el.style.setProperty('container', 'none', 'important');
      el.style.setProperty('content-visibility', 'visible', 'important');
      el.style.setProperty('will-change', 'auto', 'important');
      el.style.setProperty('overflow', 'visible', 'important');
      el.style.setProperty('z-index', 'auto', 'important');
      el.style.setProperty('isolation', 'auto', 'important');
      el.style.setProperty('margin', '0', 'important');
      el.style.setProperty('padding', '0', 'important');
      el.style.setProperty('background', 'transparent', 'important');
    }

    // Pure non-destructive ancestor-path spotlight: keep <video> in its exact DOM parent,
    // neutralize all CSS containing blocks (including container-type & ShadowRoot), and hide siblings without display:none.
    var ancestorChain = [];
    var cur = target;
    while (cur && cur !== document.documentElement) {
      var curRoot = cur.getRootNode ? cur.getRootNode() : null;
      if (curRoot && curRoot !== document && curRoot.nodeType === 11 && !curRoot.querySelector('#' + styleId)) {
        try {
          var srStyle = document.createElement('style');
          srStyle.id = styleId;
          srStyle.textContent = spotlightCssText;
          curRoot.appendChild(srStyle);
          window.__dragonPipShadowStyles.push(srStyle);
        } catch (e) {}
      }
      var parent = cur.parentElement;
      if (!parent && curRoot && curRoot.host) {
        parent = curRoot.host;
      }
      if (!parent || parent === document.documentElement) break;
      if (parent !== document.body) {
        parent.classList.add('__dragon-pip-ancestor');
        ancestorChain.push(parent);
      }
      if (cur.parentElement && cur.parentElement.children) {
        var siblings = cur.parentElement.children;
        for (var s = 0; s < siblings.length; s++) {
          var sib = siblings[s];
          if (sib !== cur && sib.tagName !== 'STYLE' && sib.tagName !== 'SCRIPT' && sib.tagName !== 'LINK') {
            sib.classList.add('__dragon-pip-hidden-sibling');
          }
        }
      }
      cur = parent;
    }

    // Check computed styles on each ancestor and inline-override any surviving containing block (e.g. YouTube container-type / !important)
    for (var ai = 0; ai < ancestorChain.length; ai++) {
      try {
        var anc = ancestorChain[ai];
        var cs = window.getComputedStyle(anc);
        if (
          (cs.containerType && cs.containerType !== 'normal') ||
          (cs.transform && cs.transform !== 'none') ||
          (cs.translate && cs.translate !== 'none') ||
          (cs.scale && cs.scale !== 'none') ||
          (cs.rotate && cs.rotate !== 'none') ||
          (cs.filter && cs.filter !== 'none') ||
          (cs.backdropFilter && cs.backdropFilter !== 'none') ||
          (cs.perspective && cs.perspective !== 'none') ||
          (cs.contain && cs.contain !== 'none') ||
          (cs.contentVisibility && cs.contentVisibility !== 'visible') ||
          (cs.position && cs.position !== 'static') ||
          (cs.overflow && cs.overflow !== 'visible')
        ) {
          forceNeutralizeAncestorInline(anc);
        }
      } catch (e) {}
    }

    var PINNED_VIDEO_PROPS = [
      ['position', 'fixed'],
      ['top', '0px'],
      ['left', '0px'],
      ['right', '0px'],
      ['bottom', '0px'],
      ['width', '100%'],
      ['height', '100%'],
      ['min-width', '100%'],
      ['min-height', '100%'],
      ['max-width', '100%'],
      ['max-height', '100%'],
      ['margin-top', '0px'],
      ['margin-right', '0px'],
      ['margin-bottom', '0px'],
      ['margin-left', '0px'],
      ['padding-top', '0px'],
      ['padding-right', '0px'],
      ['padding-bottom', '0px'],
      ['padding-left', '0px'],
      ['transform', 'none'],
      ['translate', 'none'],
      ['scale', 'none'],
      ['rotate', 'none'],
      ['object-fit', 'contain'],
      ['background-color', 'rgb(0, 0, 0)'],
      ['display', 'block'],
      ['opacity', '1'],
      ['visibility', 'visible'],
      ['z-index', '2147483647']
    ];

    function pinSpotlightVideoStyle(v) {
      if (!v || !window.__dragonInPipMode) return;
      var needsWrite = false;
      for (var pi = 0; pi < PINNED_VIDEO_PROPS.length; pi++) {
        var pName = PINNED_VIDEO_PROPS[pi][0];
        var pVal = PINNED_VIDEO_PROPS[pi][1];
        if (v.style.getPropertyPriority(pName) !== 'important' || v.style.getPropertyValue(pName) !== pVal) {
          needsWrite = true;
          break;
        }
      }
      if (!needsWrite) return;
      var obs = window.__dragonSpotlightStyleObserver;
      if (obs) {
        try { obs.disconnect(); } catch (e) {}
      }
      try {
        for (var pj = 0; pj < PINNED_VIDEO_PROPS.length; pj++) {
          var k = PINNED_VIDEO_PROPS[pj][0];
          var val = PINNED_VIDEO_PROPS[pj][1];
          if (v.style.getPropertyPriority(k) !== 'important' || v.style.getPropertyValue(k) !== val) {
            v.style.setProperty(k, val, 'important');
          }
        }
      } finally {
        if (obs && window.__dragonInPipMode) {
          try {
            obs.takeRecords();
            obs.observe(v, { attributes: true, attributeFilter: ['style'] });
          } catch (e) {}
        }
      }
    }
    pinSpotlightVideoStyle(target);

    // Final geometry guard: if target rect is still offset or taller than viewport, force-neutralize all ancestors inline
    try {
      var vr = target.getBoundingClientRect();
      var vpH = (window.visualViewport && window.visualViewport.height) || window.innerHeight || 0;
      if (Math.abs(vr.top) > 4 || Math.abs(vr.left) > 4 || (vpH > 40 && (vr.height > vpH * 1.2 || vr.height < vpH * 0.5))) {
        for (var aj = 0; aj < ancestorChain.length; aj++) {
          forceNeutralizeAncestorInline(ancestorChain[aj]);
        }
        pinSpotlightVideoStyle(target);
      }
    } catch (e) {}

    // Observe target style mutations safely via rAF coalescing (NEVER synchronous microtask recursion)
    if (typeof MutationObserver === 'function') {
      try {
        var repinBudget = 24;
        var mo = new MutationObserver(function() {
          if (!window.__dragonInPipMode || repinBudget <= 0 || window.__dragonSpotlightStyleRaf) return;
          repinBudget--;
          var rafFn = function() {
            window.__dragonSpotlightStyleRaf = 0;
            if (!window.__dragonInPipMode) return;
            pinSpotlightVideoStyle(target);
          };
          if (typeof requestAnimationFrame === 'function') {
            window.__dragonSpotlightStyleRaf = requestAnimationFrame(rafFn);
          } else {
            setTimeout(rafFn, 16);
          }
        });
        mo.observe(target, { attributes: true, attributeFilter: ['style'] });
        window.__dragonSpotlightStyleObserver = mo;
      } catch (e) {}
    }

    // Attach a bounded pause-recovery listener in PiP so mobile YouTube / WebView surface resize cannot keep the video paused
    if (window.__dragonPipPauseGuardEl && window.__dragonPipPauseGuardFn) {
      try { window.__dragonPipPauseGuardEl.removeEventListener('pause', window.__dragonPipPauseGuardFn, true); } catch (e) {}
      window.__dragonPipPauseGuardEl = null;
      window.__dragonPipPauseGuardFn = null;
    }
    var pauseRecoverCount = 0;
    var pauseGuardFn = function() {
      try {
        if (!window.__dragonInPipMode || window.__dragonPipUserPaused || target.ended) return;
        if (!window.__dragonWasPlayingBeforePip || pauseRecoverCount >= 12) return;
        pauseRecoverCount++;
        setTimeout(function() {
          try {
            if (!window.__dragonInPipMode || window.__dragonPipUserPaused || target.ended) return;
            if (target.paused) {
              target.play().catch(function(){});
            }
          } catch (e) {}
        }, 65);
      } catch (e) {}
    };
    try {
      target.addEventListener('pause', pauseGuardFn, true);
      window.__dragonPipPauseGuardEl = target;
      window.__dragonPipPauseGuardFn = pauseGuardFn;
    } catch (e) {}

    if (!target.ended && !window.__dragonPipUserPaused && target.readyState >= 1 && target.paused && window.__dragonWasPlayingBeforePip) {
      target.play().catch(function(){});
    }
    // Kick-start video surface & recover if mobile YouTube or Android PiP resize stalled MediaCodec / playback
    if (!target.ended && !window.__dragonPipUserPaused && window.__dragonWasPlayingBeforePip) {
      var enterT0 = target.currentTime;
      setTimeout(function() {
        try {
          if (!window.__dragonInPipMode || window.__dragonPipUserPaused || target.ended) return;
          if (target.paused) target.play().catch(function(){});
        } catch (e) {}
      }, 120);
      setTimeout(function() {
        try {
          if (!window.__dragonInPipMode || window.__dragonPipUserPaused || target.ended) return;
          if (target.paused) {
            target.play().catch(function(){});
          } else if (target.readyState >= 2 && Math.abs(target.currentTime - enterT0) < 0.01) {
            target.currentTime = Math.max(0, target.currentTime + 0.001);
            target.play().catch(function(){});
          }
        } catch (e) {}
      }, 380);
      setTimeout(function() {
        try {
          if (!window.__dragonInPipMode || window.__dragonPipUserPaused || target.ended) return;
          if (target.paused) target.play().catch(function(){});
        } catch (e) {}
      }, 900);
    }
  } catch (e) {}
})();
"""

private const val PIP_RESTORE_FLOATING_PIP_JS = """
(function() {
  try {
    window.__dragonInPipMode = false;
    window.__dragonBackgroundPlayActive = false;
    window.__dragonPipUserPaused = false;
    window.__dragonSpotlightActive = false;
    window.__dragonPipTransitionUntil = 0;
    if (window.__dragonPipPauseGuardEl && window.__dragonPipPauseGuardFn) {
      try { window.__dragonPipPauseGuardEl.removeEventListener('pause', window.__dragonPipPauseGuardFn, true); } catch (e) {}
      window.__dragonPipPauseGuardEl = null;
      window.__dragonPipPauseGuardFn = null;
    }
    if (window.__dragonSpotlightStyleRaf) {
      try { cancelAnimationFrame(window.__dragonSpotlightStyleRaf); } catch (e) {}
      window.__dragonSpotlightStyleRaf = 0;
    }
    if (window.__dragonSpotlightStyleObserver) {
      try { window.__dragonSpotlightStyleObserver.disconnect(); } catch (e) {}
      window.__dragonSpotlightStyleObserver = null;
    }
    var bd = document.getElementById('__dragon_pip_backdrop');
    if (bd && bd.parentNode) bd.parentNode.removeChild(bd);
    if (window.__dragonApplyHostSpotlight) {
      window.__dragonApplyHostSpotlight(false);
    } else if (typeof window.__dragonSetPipViewportLock === 'function') {
      window.__dragonSetPipViewportLock(false);
    }
  } catch (e) {}
})();
"""

private const val PIP_EXIT_INLINE_SPOTLIGHT_JS = """
(function() {
  try {
    var wasActive = !!(window.__dragonInPipMode || window.__dragonBackgroundPlayActive || window.__dragonSpotlightActive || document.getElementById('__dragon_pip_spotlight_style'));
    var shouldKeepPlaying = !!window.__dragonWasPlayingBeforePip;
    window.__dragonInPipMode = false;
    window.__dragonBackgroundPlayActive = false;
    window.__dragonPipUserPaused = false;
    window.__dragonSpotlightActive = false;
    window.__dragonWasPlayingBeforePip = false;
    window.__dragonPipTransitionUntil = 0;
    if (window.__dragonPipPauseGuardEl && window.__dragonPipPauseGuardFn) {
      try { window.__dragonPipPauseGuardEl.removeEventListener('pause', window.__dragonPipPauseGuardFn, true); } catch (e) {}
      window.__dragonPipPauseGuardEl = null;
      window.__dragonPipPauseGuardFn = null;
    }
    if (window.__dragonSpotlightStyleRaf) {
      try { cancelAnimationFrame(window.__dragonSpotlightStyleRaf); } catch (e) {}
      window.__dragonSpotlightStyleRaf = 0;
    }
    if (window.__dragonSpotlightStyleObserver) {
      try { window.__dragonSpotlightStyleObserver.disconnect(); } catch (e) {}
      window.__dragonSpotlightStyleObserver = null;
    }
    var bd = document.getElementById('__dragon_pip_backdrop');
    if (bd && bd.parentNode) bd.parentNode.removeChild(bd);
    var spotStyle = document.getElementById('__dragon_pip_spotlight_style');
    if (spotStyle && spotStyle.parentNode) spotStyle.parentNode.removeChild(spotStyle);
    if (window.__dragonApplyHostSpotlight) {
      window.__dragonApplyHostSpotlight(false);
    }

    if (document.documentElement) document.documentElement.classList.remove('__dragon-pip-active');
    if (document.body) document.body.classList.remove('__dragon-pip-active');
    var ancestors = document.querySelectorAll('.__dragon-pip-ancestor');
    for (var a = 0; a < ancestors.length; a++) {
      ancestors[a].classList.remove('__dragon-pip-ancestor');
    }
    var hiddenEls = document.querySelectorAll('.__dragon-pip-hidden-sibling');
    for (var h = 0; h < hiddenEls.length; h++) {
      hiddenEls[h].classList.remove('__dragon-pip-hidden-sibling');
    }
    if (window.__dragonPipSavedAncestors && window.__dragonPipSavedAncestors.length) {
      for (var ra = 0; ra < window.__dragonPipSavedAncestors.length; ra++) {
        try {
          var rec = window.__dragonPipSavedAncestors[ra];
          if (rec && rec.el) {
            rec.el.classList.remove('__dragon-pip-ancestor');
            rec.el.removeAttribute('data-dragon-anc-saved');
            if (rec.origCss) rec.el.setAttribute('style', rec.origCss);
            else rec.el.removeAttribute('style');
          }
        } catch (e) {}
      }
      window.__dragonPipSavedAncestors = [];
    }
    if (window.__dragonPipShadowStyles && window.__dragonPipShadowStyles.length) {
      for (var rs = 0; rs < window.__dragonPipShadowStyles.length; rs++) {
        try {
          var sNode = window.__dragonPipShadowStyles[rs];
          if (sNode && sNode.parentNode) sNode.parentNode.removeChild(sNode);
        } catch (e) {}
      }
      window.__dragonPipShadowStyles = [];
    }

    var v = window.__dragonPipVideoEl;
    if (v) {
      var wasPlaying = (!v.paused && !v.ended) || shouldKeepPlaying;
      v.classList.remove('__dragon-pip-video');
      if (v.hasAttribute('data-dragon-pip-saved')) {
        var orig = v.__dragonSavedCss || '';
        if (orig) v.setAttribute('style', orig);
        else v.removeAttribute('style');
        v.removeAttribute('data-dragon-pip-saved');
      }
      if (wasPlaying && !v.ended && v.paused) {
        try { v.play().catch(function(){}); } catch (e) {}
      }
      // Recover if Chromium MediaCodec / YouTube player stalled after PiP -> fullscreen surface resize
      if (wasPlaying && !v.ended) {
        var exitVid = v;
        var exitT0 = exitVid.currentTime;
        setTimeout(function() {
          try {
            if (window.__dragonInPipMode || exitVid.ended) return;
            if (exitVid.paused && !window.__dragonPipUserPaused) {
              exitVid.play().catch(function(){});
            }
          } catch (e) {}
        }, 120);
        setTimeout(function() {
          try {
            if (window.__dragonInPipMode || exitVid.ended || window.__dragonPipUserPaused) return;
            if (exitVid.paused) {
              exitVid.play().catch(function(){});
            } else if (exitVid.readyState >= 2 && Math.abs(exitVid.currentTime - exitT0) < 0.01) {
              exitVid.currentTime = Math.max(0, exitVid.currentTime + 0.001);
              exitVid.play().catch(function(){});
            }
          } catch (e) {}
        }, 420);
      }
      delete window.__dragonPipVideoEl;
      if (!window.__dragonFloatingActive) {
        window.__dragonPipActiveElement = null;
        try {
          v.dispatchEvent(new Event('leavepictureinpicture', { bubbles: false }));
        } catch (e2) {}
      }
    }

    if (typeof window.__dragonSetPipViewportLock === 'function') {
      window.__dragonSetPipViewportLock(false);
    }

    if (wasActive) {
      var restoreX = typeof window.__dragonPipSavedScrollX === 'number' ? window.__dragonPipSavedScrollX : null;
      var restoreY = typeof window.__dragonPipSavedScrollY === 'number' ? window.__dragonPipSavedScrollY : null;
      setTimeout(function() {
        try {
          if (restoreX !== null && restoreY !== null) {
            window.scrollTo(restoreX, restoreY);
          }
          window.dispatchEvent(new Event('resize'));
        } catch (e) {}
      }, 100);
      setTimeout(function() {
        try { window.dispatchEvent(new Event('resize')); } catch (e) {}
      }, 360);
    }
  } catch (e) {}
})();
"""

/**
 * Universal HTML5 Video, HLS (.m3u8), DASH (.mpd), & Fullscreen Enhancer injected into webpages:
 * 1. Ensures all <video> elements support inline + fullscreen playback (`playsinline`, `webkit-playsinline`, `preload="metadata"`).
 * 2. Ensures all embedded video <iframe> players have `allowfullscreen` and `allow="autoplay; fullscreen; picture-in-picture; encrypted-media"`.
 * 3. Automatically recovers <video> elements whose `.m3u8` HLS or `.mpd` DASH stream triggers MEDIA_ERR_SRC_NOT_SUPPORTED
 *    on Android WebView by dynamically loading `hls.js` / `dash.js` on the fly.
 * 4. Reports live <video> playback state, dimensions, and viewport bounds to Android Picture-in-Picture controller.
 */
private const val UNIVERSAL_VIDEO_STREAM_ENHANCER_JS = """
(function() {
  try {
    function reportVideoStateToNative(mediaEl) {
      try {
        var activeVideo = null;
        var activeAudio = null;
        var allVids = document.querySelectorAll('video');
        for (var i = 0; i < allVids.length; i++) {
          var cand = allVids[i];
          if (!cand.paused && !cand.ended) {
            activeVideo = cand;
            break;
          }
        }
        var allAuds = document.querySelectorAll('audio');
        for (var k = 0; k < allAuds.length; k++) {
          var acand = allAuds[k];
          if (!acand.paused && !acand.ended) {
            activeAudio = acand;
            break;
          }
        }
        var refVid = activeVideo || (mediaEl && mediaEl.tagName === 'VIDEO' && document.contains(mediaEl) ? mediaEl : null);
        var isVidPlaying = !!(activeVideo && !activeVideo.paused && !activeVideo.ended);
        var isAudPlaying = !!(activeAudio && !activeAudio.paused && !activeAudio.ended);
        if (!isVidPlaying && !isAudPlaying && (
          (window.__dragonSuppressPauseBridgeUntil && Date.now() < window.__dragonSuppressPauseBridgeUntil) ||
          ((window.__dragonInPipMode || window.__dragonBackgroundPlayActive) && !window.__dragonPipUserPaused && window.__dragonWasPlayingBeforePip)
        )) {
          return;
        }
        window.__dragonAnyMediaPlaying = isVidPlaying || isAudPlaying;
        if (isVidPlaying) {
          window.__dragonLastActiveVideo = activeVideo;
          window.__dragonLastPlayingMs = Date.now();
          window.__dragonPipUserPaused = false;
        }
        if (isAudPlaying) {
          window.__dragonLastActiveAudio = activeAudio;
          window.__dragonLastPlayingMs = Date.now();
          window.__dragonPipUserPaused = false;
        }

        if (!window.DragonBrowserBridge) return;
        var rect = refVid && refVid.getBoundingClientRect
          ? refVid.getBoundingClientRect()
          : { left: 0, top: 0, right: 320, bottom: 180, width: 16, height: 9 };
        var vw = Math.max(1, window.innerWidth || (document.documentElement ? document.documentElement.clientWidth : 1) || 1);
        var vh = Math.max(1, window.innerHeight || (document.documentElement ? document.documentElement.clientHeight : 1) || 1);
        var w = (refVid && refVid.videoWidth) || Math.round(rect.width) || 16;
        var h = (refVid && refVid.videoHeight) || Math.round(rect.height) || 9;
        var lf = Math.max(0.0, Math.min(1.0, rect.left / vw));
        var tf = Math.max(0.0, Math.min(1.0, rect.top / vh));
        var rf = Math.max(0.0, Math.min(1.0, rect.right / vw));
        var bf = Math.max(0.0, Math.min(1.0, rect.bottom / vh));
        var docTitle = (document.title || '').trim();
        var pageUrl = (window.location && window.location.href) || '';
        var sig = [
          isVidPlaying ? 1 : 0,
          isAudPlaying ? 1 : 0,
          w,
          h,
          Math.round(lf * 20),
          Math.round(tf * 20),
          Math.round(rf * 20),
          Math.round(bf * 20),
          docTitle,
          pageUrl
        ].join('|');
        if (window.__dragonLastMediaSig === sig) return;
        window.__dragonLastMediaSig = sig;
        if (window.DragonBrowserBridge.onMediaPlaybackChanged) {
          window.DragonBrowserBridge.onMediaPlaybackChanged(isVidPlaying, isAudPlaying, docTitle, pageUrl, w, h, lf, tf, rf, bf);
        } else if (window.DragonBrowserBridge.onVideoPlaybackChanged) {
          window.DragonBrowserBridge.onVideoPlaybackChanged(isVidPlaying, w, h, lf, tf, rf, bf);
        }
      } catch (e) {}
    }

    function enhanceVideosAndFrames() {
      var frames = document.querySelectorAll('iframe');
      for (var i = 0; i < frames.length; i++) {
        var f = frames[i];
        if (f.__dragonFrameEnhanced) continue;
        f.__dragonFrameEnhanced = true;
        if (!f.hasAttribute('allowfullscreen')) f.setAttribute('allowfullscreen', 'true');
        if (!f.hasAttribute('webkitallowfullscreen')) f.setAttribute('webkitallowfullscreen', 'true');
        var allowAttr = f.getAttribute('allow') || '';
        if (allowAttr.indexOf('fullscreen') === -1) {
          f.setAttribute('allow', (allowAttr ? allowAttr + '; ' : '') + 'autoplay; fullscreen; picture-in-picture; encrypted-media');
        }
      }

      var audios = document.querySelectorAll('audio');
      for (var a = 0; a < audios.length; a++) {
        var aud = audios[a];
        if (!aud.paused && !aud.ended) {
          reportVideoStateToNative(aud);
        }
      }

      var videos = document.querySelectorAll('video');
      for (var j = 0; j < videos.length; j++) {
        var v = videos[j];
        if (!v.hasAttribute('playsinline')) v.setAttribute('playsinline', '');
        if (!v.hasAttribute('webkit-playsinline')) v.setAttribute('webkit-playsinline', '');
        if (!v.__dragonEnhanced) {
          v.__dragonEnhanced = true;
          if (!v.getAttribute('preload') || v.getAttribute('preload') === 'none') {
            v.setAttribute('preload', 'metadata');
          }
          (function(videoEl) {
            function tryHlsRecovery() {
              var src = videoEl.currentSrc || videoEl.src || '';
              if (!src) {
                var sourceTag = videoEl.querySelector('source');
                if (sourceTag) src = sourceTag.src || '';
              }
              if (!src) return;
              var lower = src.toLowerCase();
              if (lower.indexOf('.m3u8') !== -1 || lower.indexOf('mpegurl') !== -1) {
                if (videoEl.__dragonHlsAttached) return;
                videoEl.__dragonHlsAttached = true;
                function bindHls() {
                  if (window.Hls && window.Hls.isSupported()) {
                    var hls = new window.Hls({ enableWorker: true });
                    hls.loadSource(src);
                    hls.attachMedia(videoEl);
                  }
                }
                if (window.Hls) {
                  bindHls();
                } else {
                  var s = document.createElement('script');
                  s.src = 'https://cdn.jsdelivr.net/npm/hls.js@latest';
                  s.onload = bindHls;
                  document.head.appendChild(s);
                }
              }
            }
            videoEl.addEventListener('error', function() {
              tryHlsRecovery();
            }, true);
            var initialSrc = (videoEl.currentSrc || videoEl.src || '').toLowerCase();
            if (initialSrc.indexOf('.m3u8') !== -1 && !videoEl.canPlayType('application/vnd.apple.mpegurl')) {
              tryHlsRecovery();
            }
          })(v);
        }
        if (!v.paused && !v.ended) {
          reportVideoStateToNative(v);
        }
      }
    }

    if (window.__dragonVideoEnhancerInstalled) {
      enhanceVideosAndFrames();
      return;
    }
    window.__dragonVideoEnhancerInstalled = true;

    ['play', 'playing', 'pause', 'ended', 'emptied', 'loadedmetadata', 'resize'].forEach(function(evtName) {
      document.addEventListener(evtName, function(e) {
        if (e && e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
          reportVideoStateToNative(e.target);
        }
      }, true);
    });

    enhanceVideosAndFrames();
    var enhanceTimer = null;
    var obs = new MutationObserver(function() {
      if (enhanceTimer) return;
      enhanceTimer = setTimeout(function() {
        enhanceTimer = null;
        enhanceVideosAndFrames();
      }, 300);
    });
    if (document.documentElement) {
      obs.observe(document.documentElement, { childList: true, subtree: true });
    }
  } catch (e) {}
})();
"""

private const val DOM_MEDIA_SCANNER_JS = """
(function() {
  try {
    var out = [];
    var seen = {};
    function pushItem(url, label) {
      if (!url || seen[url] || url.indexOf('http') !== 0) return;
      seen[url] = true;
      out.push({ url: url, label: label || document.title || '' });
    }
    var mediaEls = document.querySelectorAll('video, audio, source');
    for (var i = 0; i < mediaEls.length; i++) {
      var el = mediaEls[i];
      if (el.currentSrc) pushItem(el.currentSrc, 'Video Player');
      if (el.src) pushItem(el.src, 'Media Element');
    }
    var links = document.querySelectorAll('a[href]');
    var extRe = /\.(mp4|m4v|webm|mkv|m3u8|mpd|mov|mp3|m4a|flac|wav|zip|rar|7z|apk|apks|xapk|pdf|epub)(\?|$)/i;
    for (var j = 0; j < links.length; j++) {
      var href = links[j].href;
      if (href && extRe.test(href)) {
        pushItem(href, (links[j].innerText || '').trim().slice(0, 48));
      }
    }
    if (out.length > 0 && window.DragonBrowserBridge) {
      window.DragonBrowserBridge.onDomMediaFound(JSON.stringify(out.slice(0, 30)));
    }
  } catch (e) {}
})();
"""

@Composable
private fun BrowserLinkLongPressDialog(
    url: String,
    isStreamableMedia: Boolean = false,
    onStreamMediaNow: () -> Unit = {},
    onOpenInNewTab: () -> Unit,
    onOpenInIncognito: () -> Unit,
    onDownloadLink: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val haptics = LocalDragonHaptics.current

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("browser_link_long_press_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Link, contentDescription = null, tint = NeonCyan)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Link Options", fontWeight = FontWeight.Black, fontSize = 16.sp)
                    Text(
                        text = url,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isStreamableMedia) {
                    Button(
                        onClick = {
                            haptics.confirm()
                            onStreamMediaNow()
                        },
                        modifier = Modifier.fillMaxWidth().testTag("link_option_stream_now"),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF03131B))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Stream Video / Audio Now", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF03131B))
                    }
                }

                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        onOpenInNewTab()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("link_option_new_tab"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open in New Tab", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        onOpenInIncognito()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("link_option_incognito_tab"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open in Incognito Tab", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        clipboardManager.setText(AnnotatedString(url))
                        Toast.makeText(context, "Link URL copied to clipboard", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("link_option_copy_url"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Link URL", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        haptics.confirm()
                        onDownloadLink()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("link_option_download"),
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Download Link", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private const val UNIVERSAL_DOWNLOAD_HOOK_JS = """
(function() {
  try {
    if (window.__dragonDownloadHookInstalled) return;
    window.__dragonDownloadHookInstalled = true;

    // Force web file managers (Gofile, Mega, Filen, PixelDrain) to use standard <a download> links instead of unsupported desktop File System Access API
    try {
      delete window.showSaveFilePicker;
      delete window.showDirectoryPicker;
    } catch (e) {}

    function syncCloudHostCookies() {
      try {
        var host = (location.hostname || '').toLowerCase();
        if (host === 'gofile.io' || host.endsWith('.gofile.io')) {
          var token = '';
          try { token = localStorage.getItem('accountToken') || sessionStorage.getItem('accountToken') || ''; } catch (e) {}
          if (!token && window.appdata && window.appdata.accountsManager && typeof window.appdata.accountsManager.getAccountToken === 'function') {
            try { token = window.appdata.accountsManager.getAccountToken() || ''; } catch (e) {}
          }
          if (token) {
            document.cookie = 'accountToken=' + token + '; domain=.gofile.io; path=/; SameSite=Lax';
            document.cookie = 'accountToken=' + token + '; path=/; SameSite=Lax';
          }
        }
      } catch (e) {}
    }

    function getMergedPageCookies() {
      syncCloudHostCookies();
      var c = '';
      try { c = document.cookie || ''; } catch (e) {}
      try {
        var host = (location.hostname || '').toLowerCase();
        if ((host === 'gofile.io' || host.endsWith('.gofile.io')) && c.indexOf('accountToken=') === -1) {
          var t = localStorage.getItem('accountToken') || sessionStorage.getItem('accountToken') || '';
          if (t) c = (c ? c + '; ' : '') + 'accountToken=' + t;
        }
      } catch (e) {}
      return c;
    }

    function isLikelyDirectFileDownloadUrl(rawUrl) {
      if (!rawUrl || typeof rawUrl !== 'string') return false;
      var lower = rawUrl.trim().toLowerCase();
      if (lower.indexOf('http://') !== 0 && lower.indexOf('https://') !== 0) return false;
      if (lower.indexOf('.gofile.io/download/') !== -1 || lower.indexOf('gofile.io/download/') !== -1) return true;
      if (lower.indexOf('pixeldrain.com/api/file/') !== -1 && lower.indexOf('download') !== -1) return true;
      if (lower.indexOf('downloads.sourceforge.net/') !== -1) return true;
      if (lower.indexOf('response-content-disposition=attachment') !== -1) return true;
      var pathOnly = lower.split('?')[0].split('#')[0];
      return /\.(zip|rar|7z|apk|xapk|apks|tar|gz|tgz|xz|bz2|iso|img|bin|exe|msi|dmg|deb|rpm|zst|torrent)$/i.test(pathOnly);
    }

    function handleBlobDownload(blobUrl, fileName) {
      try {
        fetch(blobUrl).then(function(r) { return r.blob(); }).then(function(b) {
          var reader = new FileReader();
          reader.onloadend = function() {
            if (window.DragonBrowserBridge && reader.result) {
              window.DragonBrowserBridge.onBlobDownloadBase64(reader.result, fileName || 'download.bin', b.type || 'application/octet-stream');
            }
          };
          reader.readAsDataURL(b);
        });
      } catch (e) {}
    }

    syncCloudHostCookies();

    var origAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function() {
      try {
        syncCloudHostCookies();
        var href = this.href || this.getAttribute('href') || '';
        var dlAttr = this.getAttribute('download');
        var hasDl = this.hasAttribute('download') || (dlAttr !== null && dlAttr !== undefined);
        if (href) {
          if (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0) {
            handleBlobDownload(href, dlAttr || '');
            return;
          }
          if ((hasDl || isLikelyDirectFileDownloadUrl(href)) && window.DragonBrowserBridge && window.DragonBrowserBridge.onJsDownloadTriggered) {
            if (this.target === '_blank') {
              this.removeAttribute('target');
            }
            window.DragonBrowserBridge.onJsDownloadTriggered(href, dlAttr || '', getMergedPageCookies(), location.href || '');
            return;
          }
        }
      } catch (e) {}
      return origAnchorClick.apply(this, arguments);
    };

    document.addEventListener('click', function(ev) {
      try {
        var el = ev.target && ev.target.closest ? ev.target.closest('a[href]') : null;
        if (!el) return;
        syncCloudHostCookies();
        var href = el.href || el.getAttribute('href') || '';
        var dlAttr = el.getAttribute('download');
        var hasDl = el.hasAttribute('download') || (dlAttr !== null && dlAttr !== undefined);
        if (href && (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0) && hasDl) {
          ev.preventDefault();
          handleBlobDownload(href, dlAttr || '');
          return;
        }
        if (href && (hasDl || isLikelyDirectFileDownloadUrl(href)) && window.DragonBrowserBridge && window.DragonBrowserBridge.onJsDownloadTriggered) {
          if (el.target === '_blank') {
            el.removeAttribute('target');
          }
          window.DragonBrowserBridge.onJsDownloadTriggered(href, dlAttr || '', getMergedPageCookies(), location.href || '');
        }
      } catch (e) {}
    }, true);

    var origWindowOpen = window.open;
    window.open = function(url, target, features) {
      try {
        syncCloudHostCookies();
        if (url && typeof url === 'string') {
          var resolved = new URL(url, location.href).href;
          if (resolved.indexOf('blob:') === 0 || resolved.indexOf('data:') === 0) {
            handleBlobDownload(resolved, '');
            return window;
          }
          if (isLikelyDirectFileDownloadUrl(resolved) && window.DragonBrowserBridge && window.DragonBrowserBridge.onJsDownloadTriggered) {
            window.DragonBrowserBridge.onJsDownloadTriggered(resolved, '', getMergedPageCookies(), location.href || '');
            return window;
          }
        }
      } catch (e) {}
      return origWindowOpen ? origWindowOpen.apply(this, arguments) : null;
    };
  } catch (e) {}
})();
"""

@Composable
private fun BrowserOAuthPopupDialog(
    popupWebView: WebView,
    subPopupWebView: WebView? = null,
    onOpenInTab: () -> Unit = {},
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .widthIn(max = 560.dp)
                .testTag("browser_oauth_popup_dialog")
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Sign-In Window",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onOpenInTab,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("browser_oauth_popup_open_in_tab")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Open in New Tab",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Sign-In Window",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(520.dp)
                        .background(Color.White)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        update = { container ->
                            container.removeAllViews()
                            if (subPopupWebView != null && subPopupWebView !== popupWebView) {
                                (subPopupWebView.parent as? ViewGroup)?.removeView(subPopupWebView)
                                container.addView(
                                    subPopupWebView,
                                    FrameLayout.LayoutParams(1, 1)
                                )
                            }
                            (popupWebView.parent as? ViewGroup)?.removeView(popupWebView)
                            container.addView(
                                popupWebView,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

private const val GOOGLE_ACCOUNT_AND_PASSWORD_SYNC_HOOK_JS = """
(function() {
  try {
    if (!window.DragonBrowserBridge) return;

    function unescapeGoogleStr(s) {
      if (!s || typeof s !== 'string') return '';
      return s
        .replace(/\\u0022|\\x22/gi, '"')
        .replace(/\\u0027|\\x27/gi, "'")
        .replace(/\\u003c|\\x3c/gi, '<')
        .replace(/\\u003e|\\x3e/gi, '>')
        .replace(/\\u003d|\\x3d/gi, '=')
        .replace(/\\u0040|\\x40/gi, '@')
        .replace(/&quot;|&#34;/gi, '"')
        .replace(/&#39;|&apos;/gi, "'");
    }

    function isLikelyUserEmail(candidate) {
      if (!candidate || typeof candidate !== 'string') return false;
      var c = unescapeGoogleStr(candidate).trim().replace(/^["'<]+|["'>]+$/g, '').toLowerCase();
      c = c.replace(/^(?:x22|x27|u0022|u0027)+(?=[a-z0-9._%+\-]{3,}@)/, '');
      if (c.indexOf('@') === -1 || c.length < 5 || c.length > 120) return false;
      if (c.indexOf('noreply') !== -1 || c.indexOf('no-reply') !== -1 ||
          c.indexOf('example.com') !== -1 || c.indexOf('googleusercontent.com') !== -1 ||
          c.indexOf('gserviceaccount.com') !== -1 || c.indexOf('sentry.io') !== -1 ||
          c.indexOf('w3.org') !== -1 || c.indexOf('schema.org') !== -1 ||
          c.endsWith('.png') || c.endsWith('.jpg') || c.endsWith('.svg') || c.endsWith('.js')) {
        return false;
      }
      return /^[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,}$/.test(c);
    }

    function reportGoogleAccount(email, name) {
      if (!isLikelyUserEmail(email)) return;
      var cleanEmail = unescapeGoogleStr(email).trim().replace(/^["'<]+|["'>]+$/g, '').toLowerCase();
      cleanEmail = cleanEmail.replace(/^(?:x22|x27|u0022|u0027)+(?=[a-z0-9._%+\-]{3,}@)/, '');
      if (window.__dragonLastReportedGoogleEmail === cleanEmail) return;
      window.__dragonLastReportedGoogleEmail = cleanEmail;
      try {
        sessionStorage.setItem('__dragon_google_email', cleanEmail);
      } catch (e) {}
      if (window.DragonBrowserBridge && window.DragonBrowserBridge.onGoogleAccountDetected) {
        window.DragonBrowserBridge.onGoogleAccountDetected(cleanEmail, (name || '').trim());
      }
    }

    function detectSignedInGoogleAccount() {
      try {
        var host = (location.hostname || '').toLowerCase();
        var isGoogleHost = host.indexOf('google.') !== -1 || host.indexOf('youtube.com') !== -1 || host.indexOf('gmail.com') !== -1;
        if (!isGoogleHost) return;

        var foundEmail = '';
        var foundName = '';

        var emailAttrEl = document.querySelector('[data-email*="@"], [data-identifier*="@"], [data-account-id*="@"]');
        if (emailAttrEl) {
          foundEmail = emailAttrEl.getAttribute('data-email') ||
                       emailAttrEl.getAttribute('data-identifier') ||
                       emailAttrEl.getAttribute('data-account-id') || '';
        }

        if (!isLikelyUserEmail(foundEmail)) {
          var ariaEls = document.querySelectorAll('[aria-label*="@"], [title*="@"]');
          for (var i = 0; i < ariaEls.length; i++) {
            var lbl = unescapeGoogleStr(ariaEls[i].getAttribute('aria-label') || ariaEls[i].getAttribute('title') || '');
            var m = lbl.match(/([a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,})/);
            if (m && m[1] && isLikelyUserEmail(m[1])) {
              foundEmail = m[1];
              var nameMatch = lbl.match(/Google Account:\s*([^(\n]+)/i);
              if (nameMatch && nameMatch[1]) {
                foundName = nameMatch[1].trim();
              }
              break;
            }
          }
        }

        if (!isLikelyUserEmail(foundEmail) && window.WIZ_global_data) {
          try {
            var wizStr = unescapeGoogleStr(JSON.stringify(window.WIZ_global_data));
            var wm = wizStr.match(/["']([a-zA-Z0-9._%+\-]+@(?:gmail\.com|googlemail\.com|[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}))["']/);
            if (wm && wm[1] && isLikelyUserEmail(wm[1])) foundEmail = wm[1];
          } catch (e) {}
        }

        if (!isLikelyUserEmail(foundEmail)) {
          var scripts = document.querySelectorAll('script:not([src])');
          for (var s = 0; s < scripts.length && s < 45; s++) {
            var txt = unescapeGoogleStr(scripts[s].textContent || '');
            if (txt.indexOf('@') === -1) continue;
            var gm = txt.match(/["']([a-zA-Z0-9._%+\-]+@(?:gmail\.com|googlemail\.com))["']/i);
            if (gm && gm[1] && isLikelyUserEmail(gm[1])) {
              foundEmail = gm[1];
              break;
            }
          }
        }

        if (isLikelyUserEmail(foundEmail)) {
          reportGoogleAccount(foundEmail, foundName);
          return;
        }

        if (!window.__dragonListAccountsFetched && (host === 'accounts.google.com' || host === 'myaccount.google.com' || host === 'www.google.com' || host === 'mail.google.com')) {
          window.__dragonListAccountsFetched = true;
          fetch('https://accounts.google.com/ListAccounts?gpsia=1&source=ogb&mo=1&origin=' + encodeURIComponent(location.origin), {
            credentials: 'include'
          }).then(function(r) { return r.text(); }).then(function(body) {
            var decodedBody = unescapeGoogleStr(body);
            var m = decodedBody.match(/["']([a-zA-Z0-9._%+\-]+@(?:gmail\.com|googlemail\.com|[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}))["']/i);
            if (m && m[1] && isLikelyUserEmail(m[1])) {
              reportGoogleAccount(m[1], '');
            }
          }).catch(function() {});
        }
      } catch (e) {}
    }

    detectSignedInGoogleAccount();
    setTimeout(detectSignedInGoogleAccount, 1200);
    setTimeout(detectSignedInGoogleAccount, 3500);

    if (window.__dragonPasswordSyncHookInstalled) return;
    window.__dragonPasswordSyncHookInstalled = true;
    var lastCapturedKey = '';
    var trackedUserVal = '';
    var trackedPassVal = '';

    try {
      trackedUserVal = sessionStorage.getItem('__dragon_pending_login_user') || '';
    } catch (e) {}

    function rememberUsernameCandidate(val) {
      if (!val || typeof val !== 'string') return;
      var v = val.trim();
      if (!v || v.length > 128) return;
      var host = (location.hostname || '').toLowerCase();
      if (host.indexOf('accounts.google.') !== -1 && v.indexOf('@') === -1 && /^[a-zA-Z0-9._%+\-]{3,64}$/.test(v) && !/^\d+$/.test(v)) {
        v = v + '@gmail.com';
      }
      trackedUserVal = v;
      try {
        sessionStorage.setItem('__dragon_pending_login_user', v);
      } catch (e) {}
      if (host.indexOf('accounts.google.') !== -1 && isLikelyUserEmail(v)) {
        reportGoogleAccount(v, '');
      }
    }

    function scanInputsAndExtract(root) {
      var scope = root || document;
      var passInputs = scope.querySelectorAll('input[type="password"], input[name="Passwd"], input[name*="pass" i][type="text"]');
      var passVal = '';
      var passEl = null;
      for (var i = 0; i < passInputs.length; i++) {
        var pi = passInputs[i];
        if (pi.type === 'password' || pi.name === 'Passwd') {
          if (pi.value && pi.value.length > 0) {
            passVal = pi.value;
            passEl = pi;
            break;
          }
        }
      }
      if (!passVal && trackedPassVal) {
        passVal = trackedPassVal;
      }

      var userVal = '';
      var searchScope = (passEl && passEl.form) ? passEl.form : document;
      var userCandidates = searchScope.querySelectorAll(
        'input[type="email"], input[name="identifier"], input[autocomplete="username"], input[autocomplete*="email" i], input[name*="user" i], input[name*="email" i], input[name*="login" i], input[id*="identifier" i], input[id*="user" i], input[id*="email" i], input[type="text"], input[type="tel"]'
      );
      for (var j = 0; j < userCandidates.length; j++) {
        var cand = userCandidates[j];
        if (cand !== passEl && cand.type !== 'password' && cand.type !== 'hidden' && cand.value && cand.value.trim().length > 0) {
          var n = ((cand.name || '') + ' ' + (cand.id || '') + ' ' + (cand.placeholder || '')).toLowerCase();
          if (n.indexOf('search') !== -1 || n.indexOf('query') !== -1 || n.indexOf('otp') !== -1 || n.indexOf('code') !== -1 || n.indexOf('totp') !== -1) {
            continue;
          }
          userVal = cand.value.trim();
          break;
        }
      }
      if (!userVal) {
        var idBadge = document.querySelector('[data-identifier], [data-email], #profileIdentifier');
        if (idBadge) {
          userVal = (idBadge.getAttribute('data-identifier') || idBadge.getAttribute('data-email') || idBadge.textContent || '').trim();
        }
      }
      if (!userVal && trackedUserVal) {
        userVal = trackedUserVal;
      }
      if (userVal) {
        rememberUsernameCandidate(userVal);
        userVal = trackedUserVal || userVal;
      }
      return { userVal: userVal, passVal: passVal };
    }

    function captureCredentialsFromContainer(root) {
      try {
        var creds = scanInputsAndExtract(root);
        var userVal = creds.userVal;
        var passVal = creds.passVal;
        var host = (location.hostname || '').toLowerCase();

        if (!passVal) {
          if (userVal && host.indexOf('google.') !== -1 && isLikelyUserEmail(userVal)) {
            reportGoogleAccount(userVal, '');
          }
          return;
        }

        var key = (location.origin || host) + '|' + userVal + '|' + passVal;
        if (key === lastCapturedKey) return;
        lastCapturedKey = key;

        if (window.DragonBrowserBridge && window.DragonBrowserBridge.onPasswordFormSubmitted) {
          window.DragonBrowserBridge.onPasswordFormSubmitted(
            location.href || '',
            userVal || '',
            passVal,
            document.title || host || ''
          );
        }
      } catch (e) {}
    }

    document.addEventListener('input', function(ev) {
      try {
        var t = ev.target;
        if (!t || t.tagName !== 'INPUT') return;
        if (t.type === 'password' || t.name === 'Passwd') {
          if (t.value) trackedPassVal = t.value;
        } else if (t.type === 'email' || t.name === 'identifier' || t.autocomplete === 'username' ||
                   /user|email|login|identifier/i.test((t.name || '') + ' ' + (t.id || ''))) {
          if (t.value && t.value.trim().length >= 2) {
            rememberUsernameCandidate(t.value.trim());
          }
        }
      } catch (e) {}
    }, true);

    document.addEventListener('change', function(ev) {
      try {
        var t = ev.target;
        if (!t || t.tagName !== 'INPUT') return;
        if (t.type === 'password' || t.name === 'Passwd') {
          if (t.value) trackedPassVal = t.value;
        } else if (t.value && t.value.trim().length >= 2) {
          var attr = ((t.name || '') + ' ' + (t.id || '') + ' ' + (t.type || '') + ' ' + (t.autocomplete || '')).toLowerCase();
          if (attr.indexOf('email') !== -1 || attr.indexOf('user') !== -1 || attr.indexOf('login') !== -1 || attr.indexOf('identifier') !== -1) {
            rememberUsernameCandidate(t.value.trim());
          }
        }
      } catch (e) {}
    }, true);

    document.addEventListener('submit', function(ev) {
      captureCredentialsFromContainer(ev.target);
    }, true);

    document.addEventListener('keydown', function(ev) {
      if (ev.key === 'Enter' || ev.keyCode === 13) {
        captureCredentialsFromContainer(document);
      }
    }, true);

    document.addEventListener('click', function(ev) {
      try {
        var t = ev.target;
        if (!t || !t.closest) return;
        var btn = t.closest('button, input[type="submit"], input[type="button"], [role="button"], #identifierNext, #passwordNext');
        if (btn) {
          captureCredentialsFromContainer(btn.form || document);
        }
      } catch (e) {}
    }, true);

    window.addEventListener('pagehide', function() {
      if (trackedPassVal) {
        captureCredentialsFromContainer(document);
      }
    }, true);

    window.addEventListener('beforeunload', function() {
      if (trackedPassVal) {
        captureCredentialsFromContainer(document);
      }
    }, true);
  } catch (e) {}
})();
"""

private val PASSKEY_WEBAUTHN_HOOK_JS = """
(function() {
  if (window.__dragonPasskeyHookInstalled) return;
  window.__dragonPasskeyHookInstalled = true;

  function bufToB64Url(buf) {
    if (!buf) return '';
    var bytes = buf instanceof Uint8Array
      ? buf
      : (ArrayBuffer.isView(buf) ? new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength) : new Uint8Array(buf));
    var bin = '';
    for (var i = 0; i < bytes.length; i++) {
      bin += String.fromCharCode(bytes[i]);
    }
    return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+${'$'}/, '');
  }

  function b64UrlToBuf(b64u) {
    if (!b64u || typeof b64u !== 'string') return new Uint8Array(0).buffer;
    var norm = b64u.replace(/-/g, '+').replace(/_/g, '/');
    while (norm.length % 4 !== 0) norm += '=';
    var bin = atob(norm);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) {
      bytes[i] = bin.charCodeAt(i);
    }
    return bytes.buffer;
  }

  function isBufLike(v) {
    return v && (v instanceof ArrayBuffer || ArrayBuffer.isView(v));
  }

  function serializeCreateOptions(pk) {
    if (!pk) return '{}';
    var copy = {};
    if (pk.rp) copy.rp = { id: pk.rp.id || location.hostname, name: pk.rp.name || location.hostname };
    if (pk.user) {
      copy.user = {
        id: isBufLike(pk.user.id) ? bufToB64Url(pk.user.id) : String(pk.user.id || ''),
        name: pk.user.name || '',
        displayName: pk.user.displayName || pk.user.name || ''
      };
    }
    copy.challenge = isBufLike(pk.challenge) ? bufToB64Url(pk.challenge) : String(pk.challenge || '');
    if (Array.isArray(pk.pubKeyCredParams)) copy.pubKeyCredParams = pk.pubKeyCredParams;
    if (pk.timeout) copy.timeout = pk.timeout;
    if (pk.attestation) copy.attestation = pk.attestation;
    if (pk.authenticatorSelection) copy.authenticatorSelection = pk.authenticatorSelection;
    if (Array.isArray(pk.excludeCredentials)) {
      copy.excludeCredentials = pk.excludeCredentials.map(function(c) {
        return {
          type: c.type || 'public-key',
          id: isBufLike(c.id) ? bufToB64Url(c.id) : String(c.id || ''),
          transports: c.transports || ['internal']
        };
      });
    }
    if (pk.extensions) copy.extensions = pk.extensions;
    return JSON.stringify(copy);
  }

  function serializeGetOptions(pk) {
    if (!pk) return '{}';
    var copy = {};
    copy.challenge = isBufLike(pk.challenge) ? bufToB64Url(pk.challenge) : String(pk.challenge || '');
    copy.rpId = pk.rpId || location.hostname;
    if (pk.timeout) copy.timeout = pk.timeout;
    if (pk.userVerification) copy.userVerification = pk.userVerification;
    if (Array.isArray(pk.allowCredentials)) {
      copy.allowCredentials = pk.allowCredentials.map(function(c) {
        return {
          type: c.type || 'public-key',
          id: isBufLike(c.id) ? bufToB64Url(c.id) : String(c.id || ''),
          transports: c.transports || ['internal']
        };
      });
    }
    if (pk.extensions) copy.extensions = pk.extensions;
    return JSON.stringify(copy);
  }

  function buildCredentialObject(credJson, isCreate) {
    var resp = credJson.response || {};
    var idStr = credJson.id || '';
    var rawIdBuf = b64UrlToBuf(credJson.rawId || idStr);
    var clientDataBuf = b64UrlToBuf(resp.clientDataJSON || '');
    var extResults = credJson.clientExtensionResults || {};

    var responseObj;
    if (isCreate) {
      var attObjBuf = b64UrlToBuf(resp.attestationObject || '');
      var authDataBuf = resp.authenticatorData ? b64UrlToBuf(resp.authenticatorData) : new ArrayBuffer(0);
      var pubKeyBuf = resp.publicKey ? b64UrlToBuf(resp.publicKey) : null;
      var pubKeyAlg = typeof resp.publicKeyAlgorithm === 'number' ? resp.publicKeyAlgorithm : -7;
      var transports = Array.isArray(resp.transports) ? resp.transports : ['internal', 'hybrid'];
      responseObj = {
        clientDataJSON: clientDataBuf,
        attestationObject: attObjBuf,
        getAuthenticatorData: function() { return authDataBuf; },
        getPublicKey: function() { return pubKeyBuf; },
        getPublicKeyAlgorithm: function() { return pubKeyAlg; },
        getTransports: function() { return transports.slice(); }
      };
    } else {
      var assertAuthBuf = b64UrlToBuf(resp.authenticatorData || '');
      var sigBuf = b64UrlToBuf(resp.signature || '');
      var userHandleBuf = resp.userHandle ? b64UrlToBuf(resp.userHandle) : null;
      responseObj = {
        clientDataJSON: clientDataBuf,
        authenticatorData: assertAuthBuf,
        signature: sigBuf,
        userHandle: userHandleBuf
      };
    }

    var cred = {
      id: idStr,
      rawId: rawIdBuf,
      type: credJson.type || 'public-key',
      authenticatorAttachment: credJson.authenticatorAttachment || 'platform',
      response: responseObj,
      getClientExtensionResults: function() { return extResults; },
      toJSON: function() { return credJson; }
    };
    try {
      if (typeof window.PublicKeyCredential === 'function') {
        Object.setPrototypeOf(cred, window.PublicKeyCredential.prototype);
      }
    } catch (e) {}
    return cred;
  }

  if (typeof window.PublicKeyCredential === 'undefined') {
    window.PublicKeyCredential = function PublicKeyCredential() {};
  }
  try {
    window.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable = function() {
      return Promise.resolve(true);
    };
    window.PublicKeyCredential.isConditionalMediationAvailable = function() {
      return Promise.resolve(true);
    };
    window.PublicKeyCredential.getClientCapabilities = function() {
      return Promise.resolve({
        conditionalCreate: true,
        conditionalGet: true,
        hybridTransport: true,
        passkeyPlatformAuthenticator: true,
        userVerifyingPlatformAuthenticator: true
      });
    };
  } catch (e) {}

  if (!navigator.credentials) {
    try {
      Object.defineProperty(navigator, 'credentials', {
        value: {},
        configurable: true,
        writable: true
      });
    } catch (e) {}
  }

  var creds = navigator.credentials;
  if (!creds) return;

  var origCreate = typeof creds.create === 'function' ? creds.create.bind(creds) : null;
  var origGet = typeof creds.get === 'function' ? creds.get.bind(creds) : null;

  creds.create = function(options) {
    if (!options || !options.publicKey) {
      return origCreate ? origCreate(options) : Promise.reject(new DOMException('Unsupported credential type', 'NotSupportedError'));
    }
    return new Promise(function(resolve, reject) {
      setTimeout(function() {
        try {
          if (window.DragonPasskeyBridge && window.DragonPasskeyBridge.createPasskey) {
            var reqJson = serializeCreateOptions(options.publicKey);
            var rawRes = window.DragonPasskeyBridge.createPasskey(reqJson, location.href || '');
            var parsed = JSON.parse(rawRes || '{}');
            if (parsed.ok && parsed.credential) {
              resolve(buildCredentialObject(parsed.credential, true));
              return;
            }
          }
          if (origCreate) {
            origCreate(options).then(resolve, reject);
          } else {
            reject(new DOMException('Passkey creation failed', 'NotAllowedError'));
          }
        } catch (err) {
          if (origCreate) {
            origCreate(options).then(resolve, reject);
          } else {
            reject(new DOMException(String(err || 'Passkey creation failed'), 'NotAllowedError'));
          }
        }
      }, 0);
    });
  };

  creds.get = function(options) {
    if (!options || !options.publicKey) {
      return origGet ? origGet(options) : Promise.reject(new DOMException('Unsupported credential type', 'NotSupportedError'));
    }
    var rpId = (options.publicKey && options.publicKey.rpId) || location.hostname || '';
    var isConditional = options.mediation === 'conditional';
    var hasLocal = false;
    try {
      if (window.DragonPasskeyBridge && window.DragonPasskeyBridge.hasLocalPasskeyForRp) {
        hasLocal = !!window.DragonPasskeyBridge.hasLocalPasskeyForRp(rpId);
      }
    } catch (e) {}

    // Passive conditional UI request on page load: only resolve from local vault or let browser handle natively without fallback popup
    if (isConditional && !hasLocal) {
      if (origGet) {
        return origGet(options).catch(function() {
          return new Promise(function() {});
        });
      }
      return new Promise(function() {});
    }

    if (hasLocal && window.DragonPasskeyBridge && window.DragonPasskeyBridge.getPasskeyAssertion) {
      return new Promise(function(resolve, reject) {
        setTimeout(function() {
          try {
            var reqJson = serializeGetOptions(options.publicKey);
            var rawRes = window.DragonPasskeyBridge.getPasskeyAssertion(reqJson, location.href || '');
            var parsed = JSON.parse(rawRes || '{}');
            if (parsed.ok && parsed.credential) {
              resolve(buildCredentialObject(parsed.credential, false));
              return;
            }
            reject(new DOMException(parsed.error || 'No matching passkey available', 'NotAllowedError'));
          } catch (err) {
            reject(new DOMException(String(err || 'Passkey assertion failed'), 'NotAllowedError'));
          }
        }, 0);
      });
    }

    // No local passkey in vault: try native WebView WebAuthn (without conditional mediation), then Android CredentialManager, else surface System Chrome Passkey helper
    var modalOptions = options;
    if (isConditional) {
      modalOptions = Object.assign({}, options, { mediation: 'optional' });
    }
    var nativePromise = origGet
      ? origGet(modalOptions)
      : Promise.reject(new DOMException('Native WebAuthn unavailable', 'NotSupportedError'));

    return nativePromise.catch(function(nativeErr) {
      return new Promise(function(resolve, reject) {
        setTimeout(function() {
          try {
            if (window.DragonPasskeyBridge && window.DragonPasskeyBridge.getPasskeyAssertion) {
              var reqJson = serializeGetOptions(options.publicKey);
              var rawRes = window.DragonPasskeyBridge.getPasskeyAssertion(reqJson, location.href || '');
              var parsed = JSON.parse(rawRes || '{}');
              if (parsed.ok && parsed.credential) {
                resolve(buildCredentialObject(parsed.credential, false));
                return;
              }
            }
            if (window.DragonPasskeyBridge && window.DragonPasskeyBridge.notifyPasskeyFallback) {
              window.DragonPasskeyBridge.notifyPasskeyFallback(location.href || '', rpId);
            }
            reject(nativeErr instanceof Error ? nativeErr : new DOMException('Passkey sign-in unavailable in embedded WebView', 'NotAllowedError'));
          } catch (err) {
            reject(new DOMException(String(err || 'Passkey sign-in unavailable'), 'NotAllowedError'));
          }
        }, 0);
      });
    });
  };
})();
"""





