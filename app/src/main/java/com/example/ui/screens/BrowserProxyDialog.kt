package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.webkit.WebViewFeature
import com.example.data.browser.BrowserProxyManager
import com.example.data.browser.BrowserProxyProfile
import com.example.data.browser.BrowserProxyType
import com.example.data.browser.ProxyConnectivityResult
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Detailed HTTP, HTTPS, SOCKS5 & SOCKS4 Proxy Manager Dialog for Dragon Browser & Downloader.
 * Supports multi-profile management, authentication (user/pass), bypass rules, reverse bypass,
 * downloader/extension routing toggle, URI quick-import, and live TCP/SOCKS5 handshake testing.
 */
@Composable
fun BrowserProxyDialog(
    proxyEnabled: Boolean,
    profiles: List<BrowserProxyProfile>,
    activeProfileId: String?,
    onToggleProxyEnabled: (Boolean) -> Unit,
    onSelectProfile: (BrowserProxyProfile) -> Unit,
    onSaveProfile: (BrowserProxyProfile, activateImmediately: Boolean) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val activeProfile = remember(profiles, activeProfileId) {
        profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
    }
    val webViewOverrideSupported = remember {
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }.getOrDefault(false)
    }

    var showEditorForm by remember { mutableStateOf(profiles.isEmpty()) }
    var showQuickPasteBar by remember { mutableStateOf(false) }
    var quickPasteText by remember { mutableStateOf("") }
    var quickPasteError by remember { mutableStateOf<String?>(null) }

    // Form state
    var editingProfileId by remember { mutableStateOf<String?>(null) }
    var draftName by remember { mutableStateOf("") }
    var draftType by remember { mutableStateOf(BrowserProxyType.SOCKS5) }
    var draftHost by remember { mutableStateOf("") }
    var draftPort by remember { mutableStateOf(BrowserProxyType.SOCKS5.defaultPort.toString()) }
    var draftUsername by remember { mutableStateOf("") }
    var draftPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var draftBypass by remember {
        mutableStateOf(BrowserProxyProfile.DEFAULT_BYPASS_RULES.joinToString(", "))
    }
    var draftReverseBypass by remember { mutableStateOf(false) }
    var draftApplyToDownloads by remember { mutableStateOf(true) }
    var showAdvancedSection by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var lastTestResult by remember { mutableStateOf<Pair<String, ProxyConnectivityResult>?>(null) }

    fun populateFormForEdit(profile: BrowserProxyProfile) {
        editingProfileId = profile.id
        draftName = profile.name
        draftType = profile.type
        draftHost = profile.host
        draftPort = profile.port.toString()
        draftUsername = profile.username
        draftPassword = profile.password
        draftBypass = profile.bypassRules.joinToString(", ")
        draftReverseBypass = profile.reverseBypass
        draftApplyToDownloads = profile.applyToDownloads
        showAdvancedSection = profile.reverseBypass ||
            profile.bypassRules != BrowserProxyProfile.DEFAULT_BYPASS_RULES ||
            !profile.applyToDownloads
        formError = null
        lastTestResult = null
        showEditorForm = true
    }

    fun resetFormForNew(defaultType: BrowserProxyType = BrowserProxyType.SOCKS5) {
        editingProfileId = null
        draftName = ""
        draftType = defaultType
        draftHost = ""
        draftPort = defaultType.defaultPort.toString()
        draftUsername = ""
        draftPassword = ""
        draftBypass = BrowserProxyProfile.DEFAULT_BYPASS_RULES.joinToString(", ")
        draftReverseBypass = false
        draftApplyToDownloads = true
        showAdvancedSection = false
        formError = null
        lastTestResult = null
        showEditorForm = true
    }

    fun buildDraftProfile(): BrowserProxyProfile? {
        val validationErr = BrowserProxyManager.validateProxyInput(draftHost, draftPort)
        if (validationErr != null) {
            formError = validationErr
            return null
        }
        val portNum = draftPort.trim().toInt()
        val bypassList = BrowserProxyProfile.parseBypassInput(draftBypass)
        return BrowserProxyProfile(
            id = editingProfileId ?: UUID.randomUUID().toString(),
            name = draftName.trim().ifBlank { "${draftType.label} • ${draftHost.trim()}:$portNum" },
            type = draftType,
            host = draftHost.trim(),
            port = portNum,
            username = draftUsername.trim(),
            password = draftPassword,
            bypassRules = bypassList,
            reverseBypass = draftReverseBypass,
            applyToDownloads = draftApplyToDownloads
        )
    }

    fun runConnectivityTest(target: BrowserProxyProfile) {
        if (isTestingConnection) return
        isTestingConnection = true
        lastTestResult = null
        scope.launch {
            val res = BrowserProxyManager.testProxyConnection(target)
            isTestingConnection = false
            lastTestResult = target.toDisplayEndpoint() to res
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("browser_proxy_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.VpnLock,
                    contentDescription = null,
                    tint = if (proxyEnabled && activeProfile != null) NeonCyan
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Browser & DDM Proxy",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.5.sp
                    )
                    Text(
                        text = "HTTP • HTTPS • SOCKS5 • SOCKS4",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = proxyEnabled && activeProfile != null,
                    onCheckedChange = { enabled ->
                        if (enabled && profiles.isEmpty()) {
                            formError = "Add a proxy profile below before enabling."
                            showEditorForm = true
                        } else {
                            onToggleProxyEnabled(enabled)
                        }
                    },
                    modifier = Modifier.testTag("switch_proxy_master")
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Live Status Card
                val isActive = proxyEnabled && activeProfile != null
                val statusAccent = when {
                    !isActive -> MaterialTheme.colorScheme.onSurfaceVariant
                    activeProfile?.type == BrowserProxyType.SOCKS5 -> NeonCyan
                    activeProfile?.type == BrowserProxyType.HTTPS -> SuccessGreen
                    else -> WarningOrange
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isActive) statusAccent.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, statusAccent.copy(alpha = if (isActive) 0.55f else 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("proxy_live_status_card")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isActive && activeProfile != null) {
                                    "ACTIVE: ${activeProfile.type.label} • ${activeProfile.toDisplayEndpoint()}"
                                } else {
                                    "DIRECT CONNECTION (PROXY OFF)"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = if (isActive) statusAccent else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (activeProfile != null) {
                                TextButton(
                                    onClick = { runConnectivityTest(activeProfile) },
                                    enabled = !isTestingConnection,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier
                                        .height(24.dp)
                                        .testTag("btn_test_active_proxy")
                                ) {
                                    Text(
                                        text = if (isTestingConnection) "Testing..." else "Test Ping",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonCyan
                                    )
                                }
                            }
                        }
                        if (isActive && activeProfile != null) {
                            val scopeLabel = if (activeProfile.applyToDownloads) "WebView + Downloads" else "WebView Only"
                            val authLabel = if (activeProfile.hasAuth()) "Auth: ${activeProfile.username}" else "No Auth"
                            Text(
                                text = "$scopeLabel • $authLabel • Bypass: ${activeProfile.bypassRules.size} rule(s)",
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!webViewOverrideSupported) {
                            Text(
                                text = "Note: WebView ProxyOverride unavailable on this Android WebView build; DDM downloads still use this proxy.",
                                fontSize = 9.5.sp,
                                color = WarningOrange
                            )
                        }
                    }
                }

                // Connectivity Test Feedback
                lastTestResult?.let { (endpoint, result) ->
                    val testColor = if (result.reachable) SuccessGreen else DangerRed
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = testColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, testColor.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("proxy_test_result_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (result.reachable) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = testColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "$endpoint • ${result.latencyMs}ms",
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = testColor
                                )
                                Text(
                                    text = result.protocolDetail,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Quick Action Row (+ Add Proxy / Quick Paste URI)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (showEditorForm && editingProfileId == null) {
                                showEditorForm = false
                            } else {
                                resetFormForNew()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_add_proxy")
                    ) {
                        Icon(
                            imageVector = if (showEditorForm && editingProfileId == null) Icons.Default.ExpandLess else Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (showEditorForm && editingProfileId == null) "Hide Form" else "Add Proxy",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            showQuickPasteBar = !showQuickPasteBar
                            quickPasteError = null
                            if (showQuickPasteBar && quickPasteText.isBlank()) {
                                val clip = clipboardManager.getText()?.text?.trim().orEmpty()
                                if (clip.contains(":") && !clip.contains(' ')) {
                                    quickPasteText = clip
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_quick_import_proxy")
                    ) {
                        Icon(Icons.Default.ContentPasteGo, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Quick URI", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Quick URI Import Bar
                AnimatedVisibility(visible = showQuickPasteBar) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Paste socks5://user:pass@host:port, http://host:port, or host:port:user:pass",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = quickPasteText,
                                onValueChange = {
                                    quickPasteText = it
                                    quickPasteError = null
                                },
                                placeholder = { Text("socks5://127.0.0.1:1080", fontSize = 11.sp) },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 11.5.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_quick_proxy_uri")
                            )
                            quickPasteError?.let { err ->
                                Text(text = err, fontSize = 10.sp, color = DangerRed)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { showQuickPasteBar = false }) {
                                    Text("Cancel", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = {
                                        BrowserProxyManager.parseProxyUri(quickPasteText, draftType)
                                            .onSuccess { parsed ->
                                                showQuickPasteBar = false
                                                quickPasteText = ""
                                                populateFormForEdit(parsed.copy(id = UUID.randomUUID().toString()))
                                                editingProfileId = null
                                            }
                                            .onFailure { err ->
                                                quickPasteError = err.message ?: "Invalid proxy string"
                                            }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.testTag("btn_parse_quick_proxy")
                                ) {
                                    Text("Load into Form", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF04131B))
                                }
                            }
                        }
                    }
                }

                // Detailed Add / Edit Proxy Form
                AnimatedVisibility(visible = showEditorForm) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("proxy_editor_form")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (editingProfileId != null) "Edit Proxy Profile" else "New Proxy Configuration",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                color = NeonCyan
                            )

                            // Protocol selector chips: HTTP, HTTPS, SOCKS5, SOCKS4
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BrowserProxyType.entries.forEach { pType ->
                                    val selected = draftType == pType
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            val oldDefault = draftType.defaultPort.toString()
                                            draftType = pType
                                            if (draftPort.isBlank() || draftPort == oldDefault) {
                                                draftPort = pType.defaultPort.toString()
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = pType.label,
                                                fontSize = 11.sp,
                                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
                                            )
                                        },
                                        modifier = Modifier.testTag("proxy_type_chip_${pType.id}")
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = draftName,
                                onValueChange = { draftName = it },
                                label = { Text("Profile Name (Optional)", fontSize = 10.5.sp) },
                                placeholder = { Text("e.g. Fast ${draftType.label} Node", fontSize = 11.sp) },
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("input_proxy_name")
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = draftHost,
                                    onValueChange = {
                                        draftHost = it
                                        formError = null
                                    },
                                    label = { Text("Host / IP *", fontSize = 10.5.sp) },
                                    placeholder = { Text("127.0.0.1", fontSize = 11.sp) },
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    modifier = Modifier
                                        .weight(1.7f)
                                        .testTag("input_proxy_host")
                                )
                                OutlinedTextField(
                                    value = draftPort,
                                    onValueChange = { next ->
                                        draftPort = next.filter { it.isDigit() }.take(5)
                                        formError = null
                                    },
                                    label = { Text("Port *", fontSize = 10.5.sp) },
                                    placeholder = { Text("${draftType.defaultPort}", fontSize = 11.sp) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    textStyle = LocalTextStyle.current.copy(
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("input_proxy_port")
                                )
                            }

                            // Optional Auth (Username & Password)
                            Text(
                                text = "Authentication (Optional)",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = draftUsername,
                                    onValueChange = { draftUsername = it },
                                    label = { Text("Username", fontSize = 10.5.sp) },
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(fontSize = 11.5.sp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("input_proxy_username")
                                )
                                OutlinedTextField(
                                    value = draftPassword,
                                    onValueChange = { draftPassword = it },
                                    label = { Text("Password", fontSize = 10.5.sp) },
                                    singleLine = true,
                                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { showPassword = !showPassword },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = if (showPassword) "Hide password" else "Show password",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    },
                                    textStyle = LocalTextStyle.current.copy(fontSize = 11.5.sp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("input_proxy_password")
                                )
                            }

                            // Advanced Routing Toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAdvancedSection = !showAdvancedSection }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Bypass Rules & Routing Options",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonCyan
                                )
                                Icon(
                                    imageVector = if (showAdvancedSection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Toggle Advanced Proxy Options",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            AnimatedVisibility(visible = showAdvancedSection) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = draftBypass,
                                        onValueChange = { draftBypass = it },
                                        label = { Text("Bypass Hosts / Patterns (comma-separated)", fontSize = 10.sp) },
                                        placeholder = { Text("localhost, 127.0.0.1, *.local", fontSize = 10.5.sp) },
                                        minLines = 1,
                                        maxLines = 3,
                                        textStyle = LocalTextStyle.current.copy(
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("input_proxy_bypass")
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Reverse Bypass", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                text = "Proxy ONLY domains in bypass list",
                                                fontSize = 9.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = draftReverseBypass,
                                            onCheckedChange = { draftReverseBypass = it },
                                            modifier = Modifier.testTag("switch_proxy_reverse_bypass")
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Route Downloads & Extensions", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                text = "Apply proxy to DDM downloads too",
                                                fontSize = 9.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = draftApplyToDownloads,
                                            onCheckedChange = { draftApplyToDownloads = it },
                                            modifier = Modifier.testTag("switch_proxy_downloads")
                                        )
                                    }
                                }
                            }

                            formError?.let { err ->
                                Text(
                                    text = err,
                                    fontSize = 10.5.sp,
                                    color = DangerRed,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val draft = buildDraftProfile() ?: return@OutlinedButton
                                        runConnectivityTest(draft)
                                    },
                                    enabled = !isTestingConnection,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.testTag("btn_test_proxy_form")
                                ) {
                                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isTestingConnection) "..." else "Test", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                TextButton(
                                    onClick = {
                                        showEditorForm = false
                                        editingProfileId = null
                                        formError = null
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text("Cancel", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = {
                                        val built = buildDraftProfile() ?: return@Button
                                        onSaveProfile(built, true)
                                        showEditorForm = false
                                        editingProfileId = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.testTag("btn_save_proxy")
                                ) {
                                    Text(
                                        text = if (editingProfileId != null) "Save & Apply" else "Save & Connect",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF04131B)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                Text(
                    text = "Saved Proxy Profiles (${profiles.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (profiles.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Router,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "No proxy profiles configured yet.",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Add an HTTP, HTTPS, or SOCKS5 server above to tunnel traffic.",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    profiles.forEach { profile ->
                        val isSelected = profile.id == activeProfile?.id
                        val isCurrentlyActive = proxyEnabled && isSelected
                        val typeColor = when (profile.type) {
                            BrowserProxyType.SOCKS5 -> NeonCyan
                            BrowserProxyType.HTTPS -> SuccessGreen
                            BrowserProxyType.SOCKS4 -> Color(0xFFC084FC)
                            BrowserProxyType.HTTP -> WarningOrange
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isCurrentlyActive) typeColor.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                            border = BorderStroke(
                                width = if (isCurrentlyActive) 1.3.dp else 0.7.dp,
                                color = if (isCurrentlyActive) typeColor
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectProfile(profile) }
                                .testTag("proxy_profile_card_${profile.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isCurrentlyActive,
                                    onClick = { onSelectProfile(profile) },
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(5.dp),
                                            color = typeColor.copy(alpha = 0.22f)
                                        ) {
                                            Text(
                                                text = profile.type.label,
                                                fontSize = 8.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = typeColor,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                        if (profile.hasAuth()) {
                                            Surface(
                                                shape = RoundedCornerShape(5.dp),
                                                color = SuccessGreen.copy(alpha = 0.18f)
                                            ) {
                                                Text(
                                                    text = "AUTH",
                                                    fontSize = 8.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = SuccessGreen,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = profile.name,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = profile.toDisplayEndpoint(),
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { runConnectivityTest(profile) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.NetworkCheck,
                                        contentDescription = "Test Proxy Connection",
                                        tint = NeonCyan,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { populateFormForEdit(profile) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit Proxy Profile",
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteProfile(profile.id) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete Proxy Profile",
                                        tint = DangerRed.copy(alpha = 0.85f),
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
