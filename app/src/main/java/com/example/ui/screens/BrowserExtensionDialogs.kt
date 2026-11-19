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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.data.browser.BrowserExtension
import com.example.data.browser.BrowserExtensionEngine
import com.example.data.browser.ExtensionKind
import com.example.data.downloader.CustomDnsServerEntry
import com.example.data.downloader.DnsProviderMode
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange

@Composable
fun BrowserExtensionsDialog(
    extensions: List<BrowserExtension>,
    activePageUrl: String,
    isInstallingFromStore: Boolean = false,
    isImportingLocalExtension: Boolean = false,
    importingStatusLabel: String = "",
    storeInstallError: String? = null,
    onOpenChromeWebStoreClick: () -> Unit = {},
    onInstallFromStoreInput: (String) -> Unit = {},
    onImportFromStorageClick: () -> Unit,
    onImportUnpackedFolderClick: () -> Unit = {},
    onToggleExtension: (BrowserExtension, Boolean) -> Unit,
    onRemoveExtension: (BrowserExtension) -> Unit,
    onOpenExtensionPopup: (BrowserExtension) -> Unit,
    onOpenExtensionSettings: (BrowserExtension) -> Unit = {},
    onOpenExtensionInfo: (BrowserExtension) -> Unit = {},
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

                // Secondary Row: Import Package (.crx/.zip/.js) + Import Unpacked Folder
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onImportFromStorageClick,
                        enabled = !isImportingLocalExtension && !isInstallingFromStore,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp)
                            .testTag("btn_import_extension_storage"),
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.UploadFile,
                            contentDescription = "Import Packaged Extension File",
                            modifier = Modifier.size(15.dp),
                            tint = NeonCyan
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Package (.crx/.zip)",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = onImportUnpackedFolderClick,
                        enabled = !isImportingLocalExtension && !isInstallingFromStore,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 44.dp)
                            .testTag("btn_import_unpacked_extension"),
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Import Unpacked Extension Folder",
                            modifier = Modifier.size(15.dp),
                            tint = SuccessGreen
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Unpacked Folder",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Inline Custom Userscript / UserStyle toggle row
                OutlinedButton(
                    onClick = { showCreateCustomForm = !showCreateCustomForm },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 40.dp)
                        .testTag("btn_new_custom_extension"),
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = if (showCreateCustomForm) Icons.Default.ExpandLess else Icons.Default.Code,
                        contentDescription = "Create Custom Script or Style",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (showCreateCustomForm) "Hide Custom Script / CSS" else "+ Create Custom Script / CSS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                AnimatedVisibility(visible = isImportingLocalExtension) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = NeonCyan
                        )
                        Text(
                            text = importingStatusLabel.ifBlank { "Importing extension files..." },
                            fontSize = 10.5.sp,
                            color = NeonCyan,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
                        .heightIn(max = 270.dp),
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
                                .clickable { onOpenExtensionPopup(ext) }
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
                                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    // Popup / Action Panel
                                    IconButton(
                                        onClick = { onOpenExtensionPopup(ext) },
                                        modifier = Modifier
                                            .size(26.dp)
                                            .testTag("btn_popup_ext_${ext.id}")
                                    ) {
                                        Icon(
                                            imageVector = if (!ext.popupPath.isNullOrBlank()) Icons.Default.OpenInNew else Icons.Default.Tune,
                                            contentDescription = "Open Extension Panel",
                                            tint = NeonCyan,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }

                                    // Extension Settings / Options Page
                                    IconButton(
                                        onClick = { onOpenExtensionSettings(ext) },
                                        modifier = Modifier
                                            .size(26.dp)
                                            .testTag("btn_settings_ext_${ext.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Extension Settings Page",
                                            tint = if (!ext.optionsPath.isNullOrBlank()) SuccessGreen
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }

                                    // Extension Info
                                    IconButton(
                                        onClick = { onOpenExtensionInfo(ext) },
                                        modifier = Modifier
                                            .size(26.dp)
                                            .testTag("btn_info_ext_${ext.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "Extension Info",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
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
                                            .scale(0.64f)
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
    initialTab: Int = 0,
    onOpenTabFromExtension: (String) -> Unit,
    onUpdateActiveTabUrl: (String) -> Unit = {},
    onReloadActiveTab: () -> Unit = {},
    onEvalInActiveTab: (String) -> Unit = {},
    onInsertCssInActiveTab: (String) -> Unit = {},
    onToggleExtension: (Boolean) -> Unit = {},
    onRemoveExtension: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var reloadNonce by remember { mutableStateOf(0) }
    var loadProgress by remember { mutableStateOf(10) }
    var isPopupLoading by remember { mutableStateOf(true) }
    var popupErrorMessage by remember { mutableStateOf<String?>(null) }
    var popupWebViewRef by remember { mutableStateOf<WebView?>(null) }
    var showRawManifest by remember(extension.id) { mutableStateOf(false) }

    // 0 = Popup/Panel, 1 = Settings Page (options_page/options_ui), 2 = Modify & Scripts, 3 = Extension Info
    var selectedPanelTab by remember(extension.id, initialTab) { mutableStateOf(initialTab.coerceIn(0, 3)) }

    // Live Modification Panel fields
    var modName by remember(extension.id) { mutableStateOf(extension.name) }
    var modMatches by remember(extension.id) {
        mutableStateOf(
            extension.contentScripts.firstOrNull()?.matches?.joinToString(", ")
                ?: extension.hostPermissions.ifEmpty { listOf("<all_urls>") }.joinToString(", ")
        )
    }
    var modRunAt by remember(extension.id) {
        mutableStateOf(extension.contentScripts.firstOrNull()?.runAt ?: "document_end")
    }
    var modInlineJs by remember(extension.id) {
        mutableStateOf(extension.contentScripts.firstOrNull()?.inlineJs.orEmpty())
    }
    var modInlineCss by remember(extension.id) {
        mutableStateOf(extension.contentScripts.firstOrNull()?.inlineCss.orEmpty())
    }
    var modStorageJson by remember(extension.id, selectedPanelTab) {
        mutableStateOf(extensionEngine.getExtensionStorageJson(extension.id))
    }

    val targetPageForTab = remember(selectedPanelTab, extension.optionsPath) {
        when (selectedPanelTab) {
            1 -> extension.optionsPath
            else -> null
        }
    }

    val popupPayload = remember(extension.id, activeTabUrl, activeTabTitle, reloadNonce, targetPageForTab) {
        if (selectedPanelTab == 1 && extension.optionsPath.isNullOrBlank()) {
            null
        } else {
            extensionEngine.loadExtensionPopupHtml(
                ext = extension,
                activeTabUrl = activeTabUrl,
                activeTabTitle = activeTabTitle,
                targetPagePath = targetPageForTab
            )
        }
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
                                text = modName.ifBlank { extension.name },
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${extension.kind.badge} • v${extension.version} • ${extension.popupPath ?: "Modification Panel"}",
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
                                .size(42.dp)
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
                                .size(42.dp)
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

                // 4-Tab Switcher: Panel | Settings | Modify | Info
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    FilterChip(
                        selected = selectedPanelTab == 0,
                        onClick = {
                            popupErrorMessage = null
                            selectedPanelTab = 0
                        },
                        label = { Text("Panel", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1) },
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .testTag("tab_extension_popup_ui")
                    )
                    FilterChip(
                        selected = selectedPanelTab == 1,
                        onClick = {
                            popupErrorMessage = null
                            modStorageJson = extensionEngine.getExtensionStorageJson(extension.id)
                            selectedPanelTab = 1
                        },
                        label = { Text("Settings", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1) },
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .testTag("tab_extension_settings_page")
                    )
                    FilterChip(
                        selected = selectedPanelTab == 2,
                        onClick = {
                            modStorageJson = extensionEngine.getExtensionStorageJson(extension.id)
                            selectedPanelTab = 2
                        },
                        label = { Text("Modify", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1) },
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .testTag("tab_extension_modify_panel")
                    )
                    FilterChip(
                        selected = selectedPanelTab == 3,
                        onClick = {
                            modStorageJson = extensionEngine.getExtensionStorageJson(extension.id)
                            selectedPanelTab = 3
                        },
                        label = { Text("Info", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1) },
                        modifier = Modifier
                            .weight(0.85f)
                            .height(30.dp)
                            .testTag("tab_extension_info")
                    )
                }

                if ((selectedPanelTab == 0 || (selectedPanelTab == 1 && !extension.optionsPath.isNullOrBlank())) &&
                    isPopupLoading && loadProgress in 1..99
                ) {
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

                if (selectedPanelTab == 3) {
                    // Detailed Extension Info Page
                    val diskStats = remember(extension.id, extension.installDirPath) {
                        extensionEngine.computeExtensionDiskStats(extension)
                    }
                    val manifestPretty = remember(extension.id, showRawManifest) {
                        if (showRawManifest) extensionEngine.readExtensionManifestPrettyJson(extension) else ""
                    }
                    val installedDateText = remember(extension.installedAtMs) {
                        runCatching {
                            java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.US)
                                .format(java.util.Date(extension.installedAtMs))
                        }.getOrDefault("Unknown")
                    }
                    val totalScriptsCount = remember(extension.contentScripts) {
                        extension.contentScripts.sumOf {
                            it.jsFiles.size + if (it.inlineJs.isNotBlank()) 1 else 0
                        }
                    }
                    val totalStylesCount = remember(extension.contentScripts) {
                        extension.contentScripts.sumOf {
                            it.cssFiles.size + if (it.inlineCss.isNotBlank()) 1 else 0
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 300.dp, max = 470.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                            .testTag("extension_info_panel"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Summary Hero Card
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.35f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = extension.name,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (extension.enabled) SuccessGreen.copy(alpha = 0.18f)
                                        else WarningOrange.copy(alpha = 0.18f)
                                    ) {
                                        Text(
                                            text = if (extension.enabled) "ENABLED" else "DISABLED",
                                            fontSize = 9.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = if (extension.enabled) SuccessGreen else WarningOrange,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = extension.description.ifBlank { "No description provided in manifest." },
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(shape = RoundedCornerShape(5.dp), color = NeonCyan.copy(alpha = 0.16f)) {
                                        Text(
                                            text = extension.kind.badge,
                                            fontSize = 9.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = NeonCyan,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.surface) {
                                        Text(
                                            text = "v${extension.version}",
                                            fontSize = 9.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.surface) {
                                        Text(
                                            text = when {
                                                extension.isBuiltIn -> "Built-in"
                                                !extension.storeId.isNullOrBlank() -> "Chrome Web Store"
                                                extension.sourceFileName.startsWith("Unpacked:", true) -> "Unpacked Folder"
                                                else -> "Local Package"
                                            },
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Metadata & Entry Points Card
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Extension Metadata", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = NeonCyan)
                                ExtensionInfoKeyValueRow("Extension ID", extension.id) {
                                    clipboard.setText(AnnotatedString(extension.id))
                                    Toast.makeText(context, "Extension ID copied", Toast.LENGTH_SHORT).show()
                                }
                                if (extension.author.isNotBlank()) {
                                    ExtensionInfoKeyValueRow("Author", extension.author)
                                }
                                if (!extension.storeId.isNullOrBlank()) {
                                    ExtensionInfoKeyValueRow("Store ID", extension.storeId) {
                                        onOpenTabFromExtension("https://chromewebstore.google.com/detail/${extension.storeId}")
                                        onDismiss()
                                    }
                                }
                                if (extension.homepageUrl.isNotBlank()) {
                                    ExtensionInfoKeyValueRow("Homepage", extension.homepageUrl) {
                                        onOpenTabFromExtension(extension.homepageUrl)
                                        onDismiss()
                                    }
                                }
                                ExtensionInfoKeyValueRow(
                                    "Virtual Origin",
                                    "https://${BrowserExtensionEngine.extensionVirtualHost(extension.id)}"
                                )
                                ExtensionInfoKeyValueRow("Popup Page", extension.popupPath ?: "Synthesized Panel")
                                ExtensionInfoKeyValueRow(
                                    "Settings Page",
                                    extension.optionsPath ?: "Built-in Config & Storage"
                                )
                                ExtensionInfoKeyValueRow(
                                    "Background Worker",
                                    extension.backgroundScriptPath ?: "None"
                                )
                                ExtensionInfoKeyValueRow(
                                    "Package Size",
                                    if (diskStats.first > 0) "${diskStats.first} files • ${com.example.data.model.formatBytes(diskStats.second)}"
                                    else "Inline / Memory"
                                )
                                ExtensionInfoKeyValueRow("Installed", installedDateText)
                            }
                        }

                        // Permissions & Host Access Card
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Permissions & Site Access", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = NeonCyan)
                                Text(
                                    text = "API Permissions: " + if (extension.permissions.isEmpty()) "None" else extension.permissions.joinToString(", "),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Site Access: " + extension.hostPermissions.ifEmpty { listOf("<all_urls>") }.joinToString(", "),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Rules: ${extension.contentScripts.size} Content Rules ($totalScriptsCount JS, $totalStylesCount CSS) • ${extension.netRules.size} DNR Rules",
                                    fontSize = 11.sp,
                                    color = SuccessGreen,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // Quick Actions Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { selectedPanelTab = 1 },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("btn_info_open_settings")
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Open Settings", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { onToggleExtension(!extension.enabled) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (extension.enabled) "Disable" else "Enable",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (!extension.isBuiltIn && onRemoveExtension != null) {
                            OutlinedButton(
                                onClick = {
                                    onRemoveExtension.invoke()
                                    onDismiss()
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                                border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Uninstall Extension", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Expandable manifest.json Inspector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { showRawManifest = !showRawManifest },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = if (showRawManifest) Icons.Default.ExpandLess else Icons.Default.Code,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (showRawManifest) "Hide manifest.json" else "Inspect manifest.json",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (showRawManifest && manifestPretty.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(manifestPretty))
                                        Toast.makeText(context, "manifest.json copied", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("Copy JSON", fontSize = 11.sp)
                                }
                            }
                        }
                        if (showRawManifest && manifestPretty.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF0A101B),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Text(
                                    text = manifestPretty,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF93C5FD),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp)
                                )
                            }
                        }
                    }
                } else if (selectedPanelTab == 2) {
                    // Native Modification & Configuration Panel
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 300.dp, max = 460.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = modName,
                            onValueChange = { modName = it },
                            label = { Text("Extension Display Name", fontSize = 11.sp) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_modify_ext_name")
                        )

                        OutlinedTextField(
                            value = modMatches,
                            onValueChange = { modMatches = it },
                            label = { Text("URL Match Patterns (comma-separated, e.g. <all_urls>)", fontSize = 11.sp) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.5.sp, fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_modify_ext_matches")
                        )

                        Text(
                            text = "Injection Timing (run_at)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("document_start", "document_end", "document_idle").forEach { phase ->
                                FilterChip(
                                    selected = modRunAt == phase,
                                    onClick = { modRunAt = phase },
                                    label = {
                                        Text(
                                            text = phase.removePrefix("document_"),
                                            fontSize = 10.5.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = modInlineJs,
                            onValueChange = { modInlineJs = it },
                            label = { Text("Custom / Override Content Script JS", fontSize = 11.sp) },
                            minLines = 3,
                            maxLines = 5,
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_modify_ext_js")
                        )

                        OutlinedTextField(
                            value = modInlineCss,
                            onValueChange = { modInlineCss = it },
                            label = { Text("Custom / Override UserStyle CSS", fontSize = 11.sp) },
                            minLines = 2,
                            maxLines = 4,
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_modify_ext_css")
                        )

                        OutlinedTextField(
                            value = modStorageJson,
                            onValueChange = { modStorageJson = it },
                            label = { Text("Extension Storage JSON (chrome.storage.local)", fontSize = 11.sp) },
                            minLines = 2,
                            maxLines = 4,
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_modify_ext_storage")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    extensionEngine.clearExtensionStorage(extension.id)
                                    modStorageJson = "{}"
                                    Toast.makeText(context, "Extension storage cleared", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Reset Storage", fontSize = 11.sp)
                            }
                            Button(
                                onClick = {
                                    extensionEngine.updateExtensionConfiguration(
                                        extensionId = extension.id,
                                        newName = modName,
                                        matchPatternsRaw = modMatches,
                                        runAt = modRunAt,
                                        customJs = modInlineJs,
                                        customCss = modInlineCss
                                    )
                                    runCatching {
                                        val parsedKv = org.json.JSONObject(modStorageJson.ifBlank { "{}" })
                                        extensionEngine.clearExtensionStorage(extension.id)
                                        extensionEngine.mergeExtensionStorageJson(extension.id, parsedKv.toString())
                                    }
                                    onReloadActiveTab()
                                    Toast.makeText(context, "Extension modifications saved & applied", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonCyan,
                                    contentColor = Color(0xFF04141C)
                                ),
                                modifier = Modifier
                                    .weight(1.3f)
                                    .testTag("btn_save_ext_modifications")
                            ) {
                                Text("Save & Apply", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    if (selectedPanelTab == 1 && !extension.optionsPath.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Options: ${extension.optionsPath}",
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = SuccessGreen,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    val fullUrl = extensionEngine.buildExtensionVirtualUrl(extension, extension.optionsPath)
                                    onOpenTabFromExtension(fullUrl)
                                    onDismiss()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("btn_open_settings_in_tab")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open in Tab", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (!popupErrorMessage.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(DangerRed.copy(alpha = 0.14f))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
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
                            IconButton(
                                onClick = { popupErrorMessage = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss warning",
                                    tint = DangerRed,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    if (popupPayload == null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .testTag("extension_settings_fallback_panel"),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Extension Enabled",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "No dedicated options.html bundled; manage runtime & storage here.",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = extension.enabled,
                                        onCheckedChange = { onToggleExtension(it) }
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Site Access & Rules",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonCyan
                                    )
                                    Text(
                                        text = extension.hostPermissions.ifEmpty { listOf("<all_urls>") }.joinToString(", "),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "Stored Settings Size: ${modStorageJson.length} chars",
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        extensionEngine.clearExtensionStorage(extension.id)
                                        modStorageJson = "{}"
                                        Toast.makeText(context, "Extension settings storage cleared", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Reset Data", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { selectedPanelTab = 2 },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NeonCyan,
                                        contentColor = Color(0xFF04141C)
                                    ),
                                    modifier = Modifier.weight(1.3f)
                                ) {
                                    Text("Edit Scripts & Storage", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        val (baseUrl, htmlContent) = popupPayload
                        // Unclipped Box prevents Chromium AwContents hardware-layer blank white/black frame bug inside Dialogs
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(440.dp)
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
                                                    val trimmed = message.trim()
                                                    if (trimmed.isNotBlank() &&
                                                        !trimmed.contains("Access to fetch", ignoreCase = true) &&
                                                        !trimmed.contains("CORS policy", ignoreCase = true) &&
                                                        !trimmed.contains("Failed to fetch", ignoreCase = true) &&
                                                        !trimmed.contains("NetworkError", ignoreCase = true) &&
                                                        !trimmed.contains("net::ERR_", ignoreCase = true) &&
                                                        !trimmed.contains("ResizeObserver", ignoreCase = true)
                                                    ) {
                                                        post {
                                                            if (popupErrorMessage.isNullOrBlank()) {
                                                                popupErrorMessage = trimmed.take(180)
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
                                                    val msg = consoleMessage.message().orEmpty().trim()
                                                    val isNoise = msg.contains("Access to fetch", ignoreCase = true) ||
                                                        msg.contains("CORS policy", ignoreCase = true) ||
                                                        msg.contains("Failed to fetch", ignoreCase = true) ||
                                                        msg.contains("NetworkError", ignoreCase = true) ||
                                                        msg.contains("net::ERR_", ignoreCase = true) ||
                                                        msg.contains("ResizeObserver", ignoreCase = true) ||
                                                        msg.contains("favicon.ico", ignoreCase = true)
                                                    if (msg.isNotBlank() && !isNoise && popupErrorMessage.isNullOrBlank()) {
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
                                                    activeTabTitle = activeTabTitle,
                                                    requestMethod = request.method ?: "GET",
                                                    requestHeaders = request.requestHeaders,
                                                    isExtensionPageContext = true
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
                                        loadDataWithBaseURL(baseUrl, htmlContent, "text/html", "UTF-8", baseUrl)
                                    }
                                },
                                update = { wv ->
                                    val loadKey = "${extension.id}|$baseUrl|$reloadNonce"
                                    if (wv.tag != loadKey) {
                                        wv.tag = loadKey
                                        wv.loadDataWithBaseURL(baseUrl, htmlContent, "text/html", "UTF-8", baseUrl)
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
}

@Composable
private fun ExtensionInfoKeyValueRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable { onClick() } else Modifier
            )
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 110.dp)
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = if (onClick != null) NeonCyan else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Dedicated DNS Configuration Dialog launched from the Browser 3-Dot Menu -> "DNS".
 * Includes:
 * - System Default DNS (Off)
 * - Cloudflare WARP DNS (1.1.1.1 / 1.0.0.1 • DoH)
 * - AdBlocker DNS (AdGuard 94.140.14.14 • Blocks Ads, Popups & Trackers)
 * - Unfiltered AdBlocker DNS (AdGuard Unfiltered 94.140.14.140 • Fast Non-Filtering DoH)
 * - Custom DNS (Add, Select & Delete user-defined DoH URLs or IPv4 DNS resolvers)
 */
@Composable
fun BrowserDnsSettingsDialog(
    currentMode: DnsProviderMode,
    customDnsList: List<CustomDnsServerEntry>,
    activeCustomDns: CustomDnsServerEntry?,
    telemetry: com.example.data.downloader.WarpDnsTelemetry = com.example.data.downloader.WarpDnsTelemetry(
        isEnabled = currentMode == DnsProviderMode.CLOUDFLARE_WARP,
        dnsMode = currentMode,
        activeCustomDns = activeCustomDns
    ),
    onSelectDnsMode: (DnsProviderMode, CustomDnsServerEntry?) -> Unit,
    onAddCustomDns: (name: String, primaryServer: String, secondaryServer: String) -> Unit,
    onDeleteCustomDns: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showAddCustomForm by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    var customPrimary by remember { mutableStateOf("") }
    var customSecondary by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("browser_dns_settings_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = "Browser & Downloader DNS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "WARP 1.1.1.1 • AdBlocker DNS • Unfiltered • Custom DNS",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Live Active DNS Status Banner
                val isDnsActive = currentMode != DnsProviderMode.SYSTEM_DEFAULT
                val statusTint = when (currentMode) {
                    DnsProviderMode.CLOUDFLARE_WARP -> NeonCyan
                    DnsProviderMode.ADBLOCKER_DNS -> SuccessGreen
                    DnsProviderMode.UNFILTERED_ADBLOCKER_DNS -> WarningOrange
                    DnsProviderMode.CUSTOM_DNS -> NeonCyan
                    DnsProviderMode.SYSTEM_DEFAULT -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isDnsActive) statusTint.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, statusTint.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dns_dialog_live_status_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isDnsActive) "ACTIVE: ${telemetry.activeResolverLabel}" else "ACTIVE: System Default DNS",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (isDnsActive) statusTint else MaterialTheme.colorScheme.onSurface
                            )
                            if (isDnsActive) {
                                Text(
                                    text = "${telemetry.lastLatencyMs}ms",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = statusTint
                                )
                            }
                        }
                        if (isDnsActive) {
                            Text(
                                text = "Resolved: ${telemetry.resolvedHostCount} • Cache Hits: ${telemetry.cacheHitCount} • Ads Blocked: ${telemetry.blockedAdHostCount}",
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                val builtInModes = listOf(
                    DnsProviderMode.SYSTEM_DEFAULT,
                    DnsProviderMode.CLOUDFLARE_WARP,
                    DnsProviderMode.ADBLOCKER_DNS,
                    DnsProviderMode.UNFILTERED_ADBLOCKER_DNS
                )

                builtInModes.forEach { mode ->
                    val selected = currentMode == mode
                    val accentColor = when (mode) {
                        DnsProviderMode.CLOUDFLARE_WARP -> NeonCyan
                        DnsProviderMode.ADBLOCKER_DNS -> SuccessGreen
                        DnsProviderMode.UNFILTERED_ADBLOCKER_DNS -> WarningOrange
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) accentColor.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(
                            width = if (selected) 1.2.dp else 0.7.dp,
                            color = if (selected) accentColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDnsMode(mode, null) }
                            .testTag("dns_mode_option_${mode.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { onSelectDnsMode(mode, null) },
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp,
                                    color = if (selected) accentColor else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = mode.subtitle,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Custom DNS Servers",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = NeonCyan
                    )
                    TextButton(
                        onClick = { showAddCustomForm = !showAddCustomForm },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("btn_toggle_add_custom_dns")
                    ) {
                        Icon(
                            imageVector = if (showAddCustomForm) Icons.Default.ExpandLess else Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (showAddCustomForm) "Hide" else "Add Custom DNS", fontSize = 11.sp)
                    }
                }

                AnimatedVisibility(visible = showAddCustomForm) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = customName,
                                onValueChange = { customName = it },
                                label = { Text("DNS Name (e.g. Quad9 / NextDNS)", fontSize = 10.5.sp) },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_custom_dns_name")
                            )
                            OutlinedTextField(
                                value = customPrimary,
                                onValueChange = { customPrimary = it },
                                label = { Text("Primary DoH URL (https://...) or IPv4 (e.g. 9.9.9.9)", fontSize = 10.5.sp) },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 11.5.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_custom_dns_primary")
                            )
                            OutlinedTextField(
                                value = customSecondary,
                                onValueChange = { customSecondary = it },
                                label = { Text("Secondary IPv4 (Optional, e.g. 149.112.112.112)", fontSize = 10.5.sp) },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 11.5.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_custom_dns_secondary")
                            )
                            Button(
                                onClick = {
                                    if (customPrimary.isNotBlank()) {
                                        onAddCustomDns(
                                            customName.ifBlank { "Custom DNS" },
                                            customPrimary.trim(),
                                            customSecondary.trim()
                                        )
                                        customName = ""
                                        customPrimary = ""
                                        customSecondary = ""
                                        showAddCustomForm = false
                                    }
                                },
                                enabled = customPrimary.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonCyan,
                                    contentColor = Color(0xFF04141C)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                                    .testTag("btn_save_custom_dns")
                            ) {
                                Text("Save & Activate Custom DNS", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (customDnsList.isEmpty()) {
                    Text(
                        text = "No custom DNS servers added yet. Tap 'Add Custom DNS' to configure a custom DoH endpoint or IPv4 DNS server.",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    customDnsList.forEach { entry ->
                        val isSelected = currentMode == DnsProviderMode.CUSTOM_DNS && activeCustomDns?.id == entry.id
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) NeonCyan.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(
                                width = if (isSelected) 1.2.dp else 0.7.dp,
                                color = if (isSelected) NeonCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectDnsMode(DnsProviderMode.CUSTOM_DNS, entry) }
                                .testTag("custom_dns_item_${entry.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { onSelectDnsMode(DnsProviderMode.CUSTOM_DNS, entry) },
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (isSelected) NeonCyan else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (entry.secondaryServer.isNotBlank()) {
                                            "${entry.primaryServer} • ${entry.secondaryServer}"
                                        } else {
                                            entry.primaryServer
                                        },
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteCustomDns(entry.id) },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("btn_delete_custom_dns_${entry.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete Custom DNS",
                                        tint = DangerRed,
                                        modifier = Modifier.size(15.dp)
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
                modifier = Modifier.testTag("btn_close_dns_dialog")
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}
