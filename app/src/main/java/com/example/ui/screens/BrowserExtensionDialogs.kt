package com.example.ui.screens

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.browser.BrowserExtension
import com.example.data.browser.BrowserExtensionEngine
import com.example.data.browser.ExtensionKind
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange

@Composable
fun BrowserExtensionsDialog(
    extensions: List<BrowserExtension>,
    activePageUrl: String,
    isInstallingFromStore: Boolean = false,
    storeInstallError: String? = null,
    onOpenChromeWebStoreClick: () -> Unit = {},
    onInstallFromStoreInput: (String) -> Unit = {},
    onImportFromStorageClick: () -> Unit,
    onToggleExtension: (BrowserExtension, Boolean) -> Unit,
    onRemoveExtension: (BrowserExtension) -> Unit,
    onOpenExtensionPopup: (BrowserExtension) -> Unit,
    onCreateCustomExtension: (name: String, description: String, matchPattern: String, jsCode: String, cssCode: String) -> Unit,
    onDismiss: () -> Unit
) {
    var showCreateCustomForm by remember { mutableStateOf(false) }
    var storeUrlOrIdInput by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var customDesc by remember { mutableStateOf("") }
    var customMatch by remember { mutableStateOf("<all_urls>") }
    var customJs by remember { mutableStateOf("") }
    var customCss by remember { mutableStateOf("") }

    val enabledCount = remember(extensions) { extensions.count { it.enabled } }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("browser_extensions_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Extensions",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (enabledCount > 0) SuccessGreen.copy(alpha = 0.18f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "$enabledCount/${extensions.size} ON",
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (enabledCount > 0) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Primary Chrome Web Store Browser Launcher
                Button(
                    onClick = onOpenChromeWebStoreClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 42.dp)
                        .testTag("btn_open_chrome_web_store"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = Color(0xFF04131B)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ShoppingBag,
                        contentDescription = "Browse Chrome Web Store",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Browse Chrome Web Store",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Direct Install by Chrome Web Store URL, 32-char ID, or .crx link
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = storeUrlOrIdInput,
                        onValueChange = { storeUrlOrIdInput = it },
                        placeholder = {
                            Text(
                                text = "Paste Chrome Store URL, ID, or .crx link",
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = true,
                        enabled = !isInstallingFromStore,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("input_cws_url_or_id")
                    )
                    FilledTonalButton(
                        onClick = {
                            if (storeUrlOrIdInput.isNotBlank() && !isInstallingFromStore) {
                                onInstallFromStoreInput(storeUrlOrIdInput.trim())
                            }
                        },
                        enabled = storeUrlOrIdInput.isNotBlank() && !isInstallingFromStore,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("btn_install_cws_url")
                    ) {
                        if (isInstallingFromStore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Get", fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }

                if (!storeInstallError.isNullOrBlank()) {
                    Text(
                        text = storeInstallError,
                        fontSize = 10.5.sp,
                        color = DangerRed,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Secondary Row: Import from Local Storage + Custom Script/CSS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onImportFromStorageClick,
                        modifier = Modifier
                            .weight(1.2f)
                            .defaultMinSize(minHeight = 40.dp)
                            .testTag("btn_import_extension_storage"),
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Import Extension from Local Storage",
                            modifier = Modifier.size(14.dp),
                            tint = NeonCyan
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Import (.crx/.zip/.js)",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = { showCreateCustomForm = !showCreateCustomForm },
                        modifier = Modifier
                            .weight(0.85f)
                            .defaultMinSize(minHeight = 40.dp)
                            .testTag("btn_new_custom_extension"),
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (showCreateCustomForm) Icons.Default.ExpandLess else Icons.Default.Code,
                            contentDescription = "Create Custom Script or Style",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showCreateCustomForm) "Hide" else "+ Script/CSS",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }

                // Inline Custom Userscript / UserStyle Creator Form
                AnimatedVisibility(visible = showCreateCustomForm) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        border = BorderStroke(0.8.dp, NeonCyan.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Custom Userscript / CSS",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = NeonCyan
                            )
                            OutlinedTextField(
                                value = customName,
                                onValueChange = { customName = it },
                                label = { Text("Name", fontSize = 10.sp) },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_custom_ext_name")
                            )
                            OutlinedTextField(
                                value = customMatch,
                                onValueChange = { customMatch = it },
                                label = { Text("Match URL (<all_urls>)", fontSize = 10.sp) },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = customJs,
                                onValueChange = { customJs = it },
                                label = { Text("JS Code (chrome.* / GM_*)", fontSize = 10.sp) },
                                minLines = 2,
                                maxLines = 3,
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_custom_ext_js")
                            )
                            OutlinedTextField(
                                value = customCss,
                                onValueChange = { customCss = it },
                                label = { Text("CSS Code (optional)", fontSize = 10.sp) },
                                minLines = 1,
                                maxLines = 2,
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_custom_ext_css")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { showCreateCustomForm = false },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("Cancel", fontSize = 11.sp)
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Button(
                                    onClick = {
                                        if (customJs.isNotBlank() || customCss.isNotBlank()) {
                                            onCreateCustomExtension(
                                                customName.ifBlank { "Custom Script" },
                                                customDesc.ifBlank { "User-authored browser extension" },
                                                customMatch.ifBlank { "<all_urls>" },
                                                customJs,
                                                customCss
                                            )
                                            customName = ""
                                            customDesc = ""
                                            customJs = ""
                                            customCss = ""
                                            showCreateCustomForm = false
                                        }
                                    },
                                    enabled = customJs.isNotBlank() || customCss.isNotBlank(),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    modifier = Modifier
                                        .height(30.dp)
                                        .testTag("btn_save_custom_extension")
                                ) {
                                    Text("Install", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Compact Installed Extensions List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    items(extensions, key = { it.id }) { ext ->
                        val badgeColor = when (ext.kind) {
                            ExtensionKind.WEB_EXTENSION_MV3 -> NeonCyan
                            ExtensionKind.WEB_EXTENSION_MV2 -> SuccessGreen
                            ExtensionKind.USERSCRIPT -> WarningOrange
                            ExtensionKind.USERSTYLE -> Color(0xFFD8B4FE)
                        }
                        val shortKindLabel = when (ext.kind) {
                            ExtensionKind.WEB_EXTENSION_MV3 -> "MV3"
                            ExtensionKind.WEB_EXTENSION_MV2 -> "MV2"
                            ExtensionKind.USERSCRIPT -> "JS"
                            ExtensionKind.USERSTYLE -> "CSS"
                        }

                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = if (ext.enabled) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
                            },
                            border = BorderStroke(
                                width = if (ext.enabled) 1.dp else 0.6.dp,
                                color = if (ext.enabled) badgeColor.copy(alpha = 0.50f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("extension_card_${ext.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = badgeColor.copy(alpha = 0.18f)
                                        ) {
                                            Text(
                                                text = shortKindLabel,
                                                fontSize = 8.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = badgeColor,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                        if (!ext.storeId.isNullOrBlank()) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = SuccessGreen.copy(alpha = 0.16f)
                                            ) {
                                                Text(
                                                    text = "CWS",
                                                    fontSize = 8.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = SuccessGreen,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = ext.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Text(
                                            text = "v${ext.version}",
                                            fontSize = 8.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                            maxLines = 1
                                        )
                                    }

                                    Text(
                                        text = ext.description.ifBlank {
                                            if (ext.isBuiltIn) "Built-in extension" else ext.sourceFileName
                                        },
                                        fontSize = 9.5.sp,
                                        lineHeight = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    if (!ext.popupPath.isNullOrBlank()) {
                                        IconButton(
                                            onClick = { onOpenExtensionPopup(ext) },
                                            modifier = Modifier
                                                .size(26.dp)
                                                .testTag("btn_popup_ext_${ext.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.OpenInNew,
                                                contentDescription = "Open Extension Popup",
                                                tint = NeonCyan,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }

                                    if (!ext.isBuiltIn) {
                                        IconButton(
                                            onClick = { onRemoveExtension(ext) },
                                            modifier = Modifier
                                                .size(26.dp)
                                                .testTag("btn_remove_ext_${ext.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Remove Extension",
                                                tint = DangerRed,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }

                                    Switch(
                                        checked = ext.enabled,
                                        onCheckedChange = { next ->
                                            onToggleExtension(ext, next)
                                        },
                                        modifier = Modifier
                                            .scale(0.66f)
                                            .height(28.dp)
                                            .testTag("switch_ext_${ext.id}")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Done", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    )
}

/**
 * Interactive Modal for running a WebExtension's `popup.html` action page with full `chrome.*` / `browser.*` API support.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserExtensionPopupDialog(
    extension: BrowserExtension,
    extensionEngine: BrowserExtensionEngine,
    activeTabUrl: String = "https://www.google.com",
    activeTabTitle: String = "Current Tab",
    onOpenTabFromExtension: (String) -> Unit,
    onUpdateActiveTabUrl: (String) -> Unit = {},
    onReloadActiveTab: () -> Unit = {},
    onEvalInActiveTab: (String) -> Unit = {},
    onInsertCssInActiveTab: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var reloadNonce by remember { mutableStateOf(0) }
    var loadProgress by remember { mutableStateOf(10) }
    var isPopupLoading by remember { mutableStateOf(true) }
    var popupErrorMessage by remember { mutableStateOf<String?>(null) }
    var popupWebViewRef by remember { mutableStateOf<WebView?>(null) }

    val popupPayload = remember(extension.id, activeTabUrl, activeTabTitle, reloadNonce) {
        extensionEngine.loadExtensionPopupHtml(
            ext = extension,
            activeTabUrl = activeTabUrl,
            activeTabTitle = activeTabTitle
        )
    }

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
                .testTag("browser_extension_popup_dialog")
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Extension,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = extension.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = extension.popupPath ?: "popup.html",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                popupErrorMessage = null
                                isPopupLoading = true
                                loadProgress = 15
                                val wv = popupWebViewRef
                                if (wv != null) {
                                    wv.clearCache(false)
                                }
                                reloadNonce++
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("btn_reload_extension_popup")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reload Extension Panel",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("btn_close_extension_popup")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close Popup",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                if (isPopupLoading && loadProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { loadProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = NeonCyan
                    )
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                if (!popupErrorMessage.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DangerRed.copy(alpha = 0.14f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = DangerRed,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = popupErrorMessage.orEmpty(),
                            fontSize = 11.sp,
                            color = DangerRed,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (popupPayload == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Extension popup or options page could not be found in this package.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val (baseUrl, htmlContent) = popupPayload
                    // Unclipped Box prevents Chromium AwContents hardware-layer blank white/black frame bug inside Dialogs
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(460.dp)
                            .background(Color.White)
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    popupWebViewRef = this
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    // Software layer prevents blank white/black Surface compositing inside Compose Dialogs
                                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                                    setBackgroundColor(android.graphics.Color.WHITE)
                                    isFocusable = true
                                    isFocusableInTouchMode = true

                                    val cm = CookieManager.getInstance()
                                    cm.setAcceptCookie(true)
                                    cm.setAcceptThirdPartyCookies(this, true)

                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        databaseEnabled = true
                                        useWideViewPort = true
                                        loadWithOverviewMode = true
                                        setSupportZoom(true)
                                        builtInZoomControls = true
                                        displayZoomControls = false
                                        allowFileAccess = true
                                        allowContentAccess = true
                                        @Suppress("DEPRECATION")
                                        allowFileAccessFromFileURLs = true
                                        @Suppress("DEPRECATION")
                                        allowUniversalAccessFromFileURLs = true
                                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                        javaScriptCanOpenWindowsAutomatically = true
                                        userAgentString = buildGoogleSignInCompatibleUserAgent(userAgentString, desktopMode = false)
                                    }

                                    addJavascriptInterface(
                                        object {
                                            @JavascriptInterface
                                            fun storageGet(extId: String): String =
                                                extensionEngine.getExtensionStorageJson(extId)

                                            @JavascriptInterface
                                            fun storageSet(extId: String, jsonPatch: String): String =
                                                extensionEngine.mergeExtensionStorageJson(extId, jsonPatch)

                                            @JavascriptInterface
                                            fun storageRemove(extId: String, keysArrayJson: String): String =
                                                extensionEngine.removeExtensionStorageKeys(extId, keysArrayJson)

                                            @JavascriptInterface
                                            fun storageClear(extId: String) {
                                                extensionEngine.clearExtensionStorage(extId)
                                            }

                                            @JavascriptInterface
                                            fun gmXhr(requestJson: String): String =
                                                extensionEngine.executeCrossOriginXhr(requestJson)

                                            @JavascriptInterface
                                            fun openTab(url: String) {
                                                if (url.isNotBlank()) {
                                                    post {
                                                        onOpenTabFromExtension(url.trim())
                                                        onDismiss()
                                                    }
                                                }
                                            }

                                            @JavascriptInterface
                                            fun closePopup() {
                                                post { onDismiss() }
                                            }

                                            @JavascriptInterface
                                            fun onPopupError(message: String) {
                                                if (message.isNotBlank()) {
                                                    post {
                                                        if (popupErrorMessage.isNullOrBlank()) {
                                                            popupErrorMessage = message.take(180)
                                                        }
                                                    }
                                                }
                                            }

                                            @JavascriptInterface
                                            fun updateActiveTabUrl(url: String) {
                                                if (url.isNotBlank()) {
                                                    post { onUpdateActiveTabUrl(url.trim()) }
                                                }
                                            }

                                            @JavascriptInterface
                                            fun reloadActiveTab() {
                                                post { onReloadActiveTab() }
                                            }

                                            @JavascriptInterface
                                            fun evalInActiveTab(jsCode: String) {
                                                if (jsCode.isNotBlank()) {
                                                    post { onEvalInActiveTab(jsCode) }
                                                }
                                            }

                                            @JavascriptInterface
                                            fun evalFilesInActiveTab(extId: String, filesArrayJson: String) {
                                                val arr = runCatching { org.json.JSONArray(filesArrayJson) }.getOrNull() ?: return
                                                val combined = StringBuilder()
                                                for (i in 0 until arr.length()) {
                                                    val filePath = arr.optString(i, "").trim()
                                                    val code = extensionEngine.readExtensionAssetText(extId, filePath)
                                                    if (!code.isNullOrBlank()) {
                                                        combined.append(code).append("\n")
                                                    }
                                                }
                                                val script = combined.toString()
                                                if (script.isNotBlank()) {
                                                    post { onEvalInActiveTab(script) }
                                                }
                                            }

                                            @JavascriptInterface
                                            fun insertCssInActiveTab(cssCode: String) {
                                                if (cssCode.isNotBlank()) {
                                                    post { onInsertCssInActiveTab(cssCode) }
                                                }
                                            }

                                            @JavascriptInterface
                                            fun insertCssFilesInActiveTab(extId: String, filesArrayJson: String) {
                                                val arr = runCatching { org.json.JSONArray(filesArrayJson) }.getOrNull() ?: return
                                                val combined = StringBuilder()
                                                for (i in 0 until arr.length()) {
                                                    val filePath = arr.optString(i, "").trim()
                                                    val css = extensionEngine.readExtensionAssetText(extId, filePath)
                                                    if (!css.isNullOrBlank()) {
                                                        combined.append(css).append("\n")
                                                    }
                                                }
                                                val fullCss = combined.toString()
                                                if (fullCss.isNotBlank()) {
                                                    post { onInsertCssInActiveTab(fullCss) }
                                                }
                                            }

                                            @JavascriptInterface
                                            fun copyClipboard(text: String) {
                                                post {
                                                    clipboard.setText(AnnotatedString(text))
                                                }
                                            }

                                            @JavascriptInterface
                                            fun showToast(message: String) {
                                                post {
                                                    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        "DragonExtensionBridge"
                                    )

                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            loadProgress = newProgress
                                            if (newProgress >= 100) {
                                                isPopupLoading = false
                                            }
                                        }

                                        override fun onJsAlert(
                                            view: WebView?,
                                            url: String?,
                                            message: String?,
                                            result: JsResult?
                                        ): Boolean {
                                            if (!message.isNullOrBlank()) {
                                                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                                            }
                                            result?.confirm()
                                            return true
                                        }

                                        override fun onJsConfirm(
                                            view: WebView?,
                                            url: String?,
                                            message: String?,
                                            result: JsResult?
                                        ): Boolean {
                                            result?.confirm()
                                            return true
                                        }

                                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                            if (consoleMessage?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                                val msg = consoleMessage.message().orEmpty()
                                                if (msg.isNotBlank() && popupErrorMessage.isNullOrBlank()) {
                                                    popupErrorMessage = msg.take(180)
                                                }
                                            }
                                            return super.onConsoleMessage(consoleMessage)
                                        }
                                    }

                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean {
                                            val target = request?.url?.toString() ?: return false
                                            val lower = target.lowercase(java.util.Locale.US)
                                            if (lower.contains("dragon-ext.local") ||
                                                lower.startsWith("chrome-extension://") ||
                                                lower.startsWith("moz-extension://") ||
                                                lower.startsWith("about:") ||
                                                lower.startsWith("data:") ||
                                                lower.startsWith("blob:")
                                            ) {
                                                return false
                                            }
                                            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                                                post {
                                                    onOpenTabFromExtension(target)
                                                    onDismiss()
                                                }
                                                return true
                                            }
                                            return true
                                        }

                                        override fun shouldInterceptRequest(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): WebResourceResponse? {
                                            val reqUrl = request?.url?.toString() ?: return null
                                            val intercepted = extensionEngine.interceptExtensionRequest(
                                                requestUrl = reqUrl,
                                                pageHost = request.url?.host,
                                                isMainFrame = request.isForMainFrame,
                                                fallbackExtId = extension.id,
                                                activeTabUrl = activeTabUrl,
                                                activeTabTitle = activeTabTitle
                                            )
                                            if (intercepted != null) return intercepted
                                            val hostLower = request.url?.host?.lowercase(java.util.Locale.US).orEmpty()
                                            if (hostLower == "dragon-ext.local" || hostLower.endsWith(".dragon-ext.local")) {
                                                return WebResourceResponse(
                                                    "text/plain",
                                                    "UTF-8",
                                                    404,
                                                    "Not Found",
                                                    mapOf("Access-Control-Allow-Origin" to "*"),
                                                    java.io.ByteArrayInputStream(ByteArray(0))
                                                )
                                            }
                                            return super.shouldInterceptRequest(view, request)
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            isPopupLoading = false
                                            loadProgress = 100
                                            super.onPageFinished(view, url)
                                        }

                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?
                                        ) {
                                            val reqUrl = request?.url?.toString().orEmpty()
                                            if (request?.isForMainFrame == true) {
                                                isPopupLoading = false
                                                popupErrorMessage = error?.description?.toString() ?: "Failed to render extension panel"
                                            } else if (reqUrl.contains("dragon-ext.local") &&
                                                (reqUrl.endsWith(".js", true) || reqUrl.endsWith(".mjs", true)) &&
                                                popupErrorMessage.isNullOrBlank()
                                            ) {
                                                popupErrorMessage = "Script load failed: ${request?.url?.lastPathSegment ?: reqUrl}"
                                            }
                                            super.onReceivedError(view, request, error)
                                        }

                                        override fun onReceivedHttpError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            errorResponse: WebResourceResponse?
                                        ) {
                                            val reqUrl = request?.url?.toString().orEmpty()
                                            if (request?.isForMainFrame == true && popupErrorMessage.isNullOrBlank()) {
                                                isPopupLoading = false
                                                popupErrorMessage = "HTTP ${errorResponse?.statusCode ?: 404} loading ${request.url?.lastPathSegment ?: "page"}"
                                            } else if (reqUrl.contains("dragon-ext.local") &&
                                                (reqUrl.endsWith(".js", true) || reqUrl.endsWith(".mjs", true)) &&
                                                popupErrorMessage.isNullOrBlank()
                                            ) {
                                                popupErrorMessage = "Missing extension script (${errorResponse?.statusCode ?: 404}): ${request?.url?.lastPathSegment ?: reqUrl}"
                                            }
                                            super.onReceivedHttpError(view, request, errorResponse)
                                        }
                                    }

                                    val loadKey = "${extension.id}|$baseUrl|$reloadNonce"
                                    tag = loadKey
                                    loadUrl(baseUrl)
                                }
                            },
                            update = { wv ->
                                val loadKey = "${extension.id}|$baseUrl|$reloadNonce"
                                if (wv.tag != loadKey) {
                                    wv.tag = loadKey
                                    wv.loadUrl(baseUrl)
                                }
                            },
                            onRelease = { wv ->
                                if (popupWebViewRef === wv) {
                                    popupWebViewRef = null
                                }
                                runCatching {
                                    wv.stopLoading()
                                    wv.loadUrl("about:blank")
                                    wv.destroy()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
