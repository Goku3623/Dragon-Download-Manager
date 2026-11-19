package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.security.SecuritySeverity
import com.example.data.security.SecurityShieldState
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BrowserSecurityShieldDialog(
    state: SecurityShieldState,
    activeUrl: String,
    onToggleMalwareShield: (Boolean) -> Unit,
    onToggleHttpsUpgrade: (Boolean) -> Unit,
    onToggleDownloadGuard: (Boolean) -> Unit,
    onToggleExtensionScanner: (Boolean) -> Unit,
    onToggleStrictMixedContent: (Boolean) -> Unit,
    onClearThreatLog: () -> Unit,
    onDismiss: () -> Unit
) {
    val allCoreOn = state.malwareShieldEnabled &&
        state.httpsUpgradeEnabled &&
        state.downloadGuardEnabled &&
        state.extensionScannerEnabled

    val isHttps = activeUrl.startsWith("https://", ignoreCase = true)
    val isHttp = activeUrl.startsWith("http://", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("browser_security_shield_dialog"),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (allCoreOn) Icons.Default.VerifiedUser else Icons.Default.Security,
                    contentDescription = "Malware & Privacy Shield",
                    tint = if (allCoreOn) SuccessGreen else WarningOrange,
                    modifier = Modifier.size(26.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Malware & Security Shield",
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp
                    )
                    Text(
                        text = if (allCoreOn) {
                            "All active protections armed • ${state.blockedThreatsCount} threat(s) blocked"
                        } else {
                            "Custom protection • ${state.blockedThreatsCount} threat(s) blocked"
                        },
                        fontSize = 11.sp,
                        color = if (allCoreOn) SuccessGreen else WarningOrange
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Current Site Transport Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isHttps) SuccessGreen.copy(alpha = 0.12f) else WarningOrange.copy(alpha = 0.12f),
                    border = BorderStroke(
                        1.dp,
                        if (isHttps) SuccessGreen.copy(alpha = 0.45f) else WarningOrange.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isHttps) Icons.Default.Lock else Icons.Default.GppMaybe,
                            contentDescription = "Site Connection Status",
                            tint = if (isHttps) SuccessGreen else WarningOrange,
                            modifier = Modifier.size(18.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    isHttps -> "Encrypted HTTPS Connection"
                                    isHttp -> "Unencrypted HTTP Site"
                                    else -> "Local / Internal Session"
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isHttps) SuccessGreen else WarningOrange
                            )
                            Text(
                                text = activeUrl.ifBlank { "No active page" },
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Protection Toggles
                SecurityToggleRow(
                    title = "Malware & Phishing Guard",
                    subtitle = "Blocks malware droppers, cryptojackers, punycode & fake login sites",
                    checked = state.malwareShieldEnabled,
                    testTag = "switch_shield_malware",
                    onCheckedChange = onToggleMalwareShield
                )
                SecurityToggleRow(
                    title = "HTTPS-First Auto Upgrade",
                    subtitle = "Automatically upgrades insecure http:// navigation to https://",
                    checked = state.httpsUpgradeEnabled,
                    testTag = "switch_shield_https",
                    onCheckedChange = onToggleHttpsUpgrade
                )
                SecurityToggleRow(
                    title = "Download Payload Guard",
                    subtitle = "Scans APKs, scripts, double extensions & disguised binary headers",
                    checked = state.downloadGuardEnabled,
                    testTag = "switch_shield_download",
                    onCheckedChange = onToggleDownloadGuard
                )
                SecurityToggleRow(
                    title = "Extension Malware Auditor",
                    subtitle = "Blocks native DCL binaries (.dex/.so/.exe), miners & webhook stealers",
                    checked = state.extensionScannerEnabled,
                    testTag = "switch_shield_extensions",
                    onCheckedChange = onToggleExtensionScanner
                )
                SecurityToggleRow(
                    title = "Strict Mixed-Content Block",
                    subtitle = "Prevents insecure HTTP subresources from loading inside HTTPS pages",
                    checked = state.strictMixedContentEnabled,
                    testTag = "switch_shield_mixed",
                    onCheckedChange = onToggleStrictMixedContent
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "RECENT SECURITY EVENTS (${state.recentThreats.size})",
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = NeonCyan
                    )
                    if (state.recentThreats.isNotEmpty() || state.blockedThreatsCount > 0) {
                        TextButton(
                            onClick = onClearThreatLog,
                            modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                        ) {
                            Text("Clear Log", fontSize = 11.sp, color = DangerRed)
                        }
                    }
                }

                if (state.recentThreats.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No malware, phishing, or disguised executable threats intercepted yet.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 170.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(state.recentThreats, key = { it.id }) { ev ->
                            val sevColor = when (ev.severity) {
                                SecuritySeverity.CRITICAL, SecuritySeverity.HIGH -> DangerRed
                                SecuritySeverity.MEDIUM -> WarningOrange
                                else -> NeonCyan
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = sevColor.copy(alpha = 0.09f),
                                border = BorderStroke(0.8.dp, sevColor.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${ev.category} (${if (ev.blocked) "BLOCKED" else "WARNED"})",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = sevColor
                                        )
                                        Text(
                                            text = fmt.format(Date(ev.timestampMs)),
                                            fontSize = 9.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = ev.target,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = ev.reason,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("btn_shield_done")
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun SecurityToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(testTag)
            )
        }
    }
}

@Composable
fun BrowserThreatWarningDialog(
    threatUrl: String,
    category: String,
    severity: SecuritySeverity,
    reason: String,
    onBackToSafety: () -> Unit,
    onProceedAnyway: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onBackToSafety,
        modifier = Modifier.testTag("browser_threat_warning_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Default.GppBad,
                contentDescription = "Blocked Malware or Phishing Threat",
                tint = DangerRed,
                modifier = Modifier.size(34.dp)
            )
        },
        title = {
            Text(
                text = "Security Threat Blocked",
                fontWeight = FontWeight.Black,
                fontSize = 17.sp,
                color = DangerRed
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DangerRed.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = "${severity.label.uppercase(Locale.US)} • $category",
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        color = DangerRed,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = reason,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = threatUrl,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onBackToSafety,
                colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Color.White),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("btn_threat_back_to_safety")
            ) {
                Text("Back to Safety", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onProceedAnyway,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("btn_threat_proceed_anyway")
            ) {
                Text("Proceed Anyway", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    )
}

@Composable
fun BrowserDownloadSecurityWarningDialog(
    fileName: String,
    sourceUrl: String,
    category: String,
    reason: String,
    isCriticalBlock: Boolean,
    onCancelDownload: () -> Unit,
    onAllowDownload: () -> Unit
) {
    val accent = if (isCriticalBlock) DangerRed else WarningOrange
    AlertDialog(
        onDismissRequest = onCancelDownload,
        modifier = Modifier.testTag("browser_download_security_dialog"),
        icon = {
            Icon(
                imageVector = if (isCriticalBlock) Icons.Default.GppBad else Icons.Default.Warning,
                contentDescription = "Potentially Dangerous Download",
                tint = accent,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = if (isCriticalBlock) "Malicious File Blocked" else "Harmful File Warning",
                fontWeight = FontWeight.Black,
                fontSize = 17.sp,
                color = accent
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = accent.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
                ) {
                    Text(
                        text = category,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = reason,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "File: $fileName",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Source: $sourceUrl",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onCancelDownload,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("btn_dl_security_cancel")
            ) {
                Text("Cancel Download", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onAllowDownload,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("btn_dl_security_allow")
            ) {
                Text("Download Anyway", fontSize = 12.sp)
            }
        }
    )
}

@Composable
fun BrowserOriginPermissionDialog(
    originUrl: String,
    wantsCamera: Boolean,
    wantsMic: Boolean,
    onDeny: () -> Unit,
    onGrant: () -> Unit
) {
    val label = when {
        wantsCamera && wantsMic -> "Camera and Microphone"
        wantsCamera -> "Camera"
        else -> "Microphone"
    }
    AlertDialog(
        onDismissRequest = onDeny,
        modifier = Modifier.testTag("browser_origin_permission_dialog"),
        icon = {
            Icon(
                imageVector = if (wantsCamera) Icons.Default.Videocam else Icons.Default.Mic,
                contentDescription = "$label Access Request",
                tint = WarningOrange,
                modifier = Modifier.size(30.dp)
            )
        },
        title = {
            Text(
                text = "Allow $label Access?",
                fontWeight = FontWeight.Black,
                fontSize = 16.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "A website is requesting permission to access your $label:",
                    fontSize = 12.5.sp
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = originUrl,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onGrant,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("btn_perm_grant")
            ) {
                Text("Allow", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDeny,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("btn_perm_deny")
            ) {
                Text("Deny", color = DangerRed, fontWeight = FontWeight.Bold)
            }
        }
    )
}
