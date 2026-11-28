package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SecurityUpdateWarning
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.downloader.ApkPackageInstaller
import com.example.ui.haptics.LocalDragonHaptics
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.WarningOrange

/**
 * Host composable that displays an actionable Material 3 resolution dialog whenever Android
 * blocks a package downgrade (`INSTALL_FAILED_VERSION_DOWNGRADE`) or rejects an incompatible
 * developer signature (`STATUS_FAILURE_CONFLICT`), allowing seamless 1-tap Uninstall -> Auto-Resume
 * Install/Downgrade, V1+V2 Auto-Sign retry, or Root `pm install -r -d` when rooted.
 */
@Composable
fun PackageInstallResolutionDialogHost() {
    val prompt by ApkPackageInstaller.pendingResolutionFlow.collectAsState()
    val currentPrompt = prompt ?: return
    val context = LocalContext.current
    val haptics = LocalDragonHaptics.current

    val accentColor = if (currentPrompt.isDowngrade) WarningOrange else NeonCyan
    val dialogTitle = when {
        currentPrompt.isDowngrade -> "Downgrade Package"
        currentPrompt.isSignatureConflict -> "Signature Conflict — Replace App?"
        else -> "Replace Existing Package?"
    }
    val installedVerLabel = buildString {
        append(currentPrompt.installedVersionName?.takeIf { it.isNotBlank() }?.let { "v$it" } ?: "Unknown")
        if (currentPrompt.installedVersionCode > 0L) {
            append(" (${currentPrompt.installedVersionCode})")
        }
    }
    val incomingVerLabel = buildString {
        append(currentPrompt.incomingVersionName?.takeIf { it.isNotBlank() }?.let { "v$it" } ?: "Unknown")
        if (currentPrompt.incomingVersionCode > 0L) {
            append(" (${currentPrompt.incomingVersionCode})")
        }
    }

    AlertDialog(
        onDismissRequest = { ApkPackageInstaller.dismissPackageResolution() },
        modifier = Modifier.testTag("package_install_resolution_dialog"),
        icon = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (currentPrompt.isDowngrade) {
                        Icons.Default.History
                    } else {
                        Icons.Default.SecurityUpdateWarning
                    },
                    contentDescription = dialogTitle,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = {
            Text(
                text = dialogTitle,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = currentPrompt.appLabel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentPrompt.packageName,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = accentColor.copy(alpha = 0.12f),
                    border = BorderStroke(0.9.dp, accentColor.copy(alpha = 0.42f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Installed: $installedVerLabel  →  Incoming: $incomingVerLabel",
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }

                Text(
                    text = currentPrompt.reasonMessage,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            haptics.confirm()
                            ApkPackageInstaller.launchUninstallAndQueueReinstall(context, currentPrompt)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("btn_uninstall_and_install")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (currentPrompt.isDowngrade) {
                                "Uninstall & Downgrade"
                            } else {
                                "Uninstall & Install"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (currentPrompt.rootAvailable) {
                        OutlinedButton(
                            onClick = {
                                haptics.confirm()
                                ApkPackageInstaller.tryRootInstallFromPrompt(context, currentPrompt)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("btn_root_force_install")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AdminPanelSettings,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Root Force Install (-r -d)",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (currentPrompt.isSignatureConflict && !currentPrompt.wasAutoSigned) {
                        OutlinedButton(
                            onClick = {
                                haptics.tap()
                                ApkPackageInstaller.retryResolutionWithAutoSign(context, currentPrompt)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("btn_autosign_retry")
                        ) {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Auto-Sign (V1+V2) & Retry",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        haptics.tap()
                        ApkPackageInstaller.dismissPackageResolution()
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("btn_cancel_pkg_resolution")
                ) {
                    Text("Cancel")
                }
            }
        }
    )
}
