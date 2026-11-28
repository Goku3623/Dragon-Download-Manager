package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.filemanager.DragonFileManagerTaskEngine
import com.example.data.filemanager.FileConflictAction
import com.example.data.filemanager.FileConflictPromptState
import com.example.data.model.formatBytes
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.WarningOrange

/**
 * Full-screen Dialog host for `DragonFileManagerScreen` that listens to
 * [DragonFileManagerTaskEngine.pendingConflictFlow] and shows a compact popup with
 * "Copy and replace", "Keep both", and "Skip" options whenever duplicate files are encountered
 * during Move, Copy/Paste, Decompress, or Compress operations.
 */
@Composable
fun DragonFileConflictDialogHost(context: Context) {
    val conflict by DragonFileManagerTaskEngine.pendingConflictFlow.collectAsStateWithLifecycle()
    val active = conflict ?: return

    Dialog(
        onDismissRequest = {
            DragonFileManagerTaskEngine.submitFileConflictDecision(
                action = FileConflictAction.SKIP,
                applyToAll = false
            )
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        DragonFileConflictCompactCard(
            prompt = active,
            onChoose = { action, applyToAll ->
                DragonFileManagerTaskEngine.submitFileConflictDecision(action, applyToAll)
            },
            onCancelTask = {
                DragonFileManagerTaskEngine.cancelActiveTask(context)
            },
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .widthIn(max = 336.dp)
        )
    }
}

/**
 * In-window Compose overlay host for `FloatingDragonArchiverWindow` (zero `android.app.Dialog`,
 * safe inside `TYPE_APPLICATION_OVERLAY` windows without `BadTokenException`).
 */
@Composable
fun DragonFileConflictInWindowOverlay(context: Context) {
    val conflict by DragonFileManagerTaskEngine.pendingConflictFlow.collectAsStateWithLifecycle()
    val active = conflict ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        DragonFileConflictCompactCard(
            prompt = active,
            onChoose = { action, applyToAll ->
                DragonFileManagerTaskEngine.submitFileConflictDecision(action, applyToAll)
            },
            onCancelTask = {
                DragonFileManagerTaskEngine.cancelActiveTask(context)
            },
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 320.dp)
        )
    }
}

@Composable
private fun DragonFileConflictCompactCard(
    prompt: FileConflictPromptState,
    onChoose: (FileConflictAction, Boolean) -> Unit,
    onCancelTask: () -> Unit,
    modifier: Modifier = Modifier
) {
    var applyToAll by remember(prompt.taskId, prompt.fileName) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.55f)),
        modifier = modifier.testTag("file_conflict_compact_popup")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Compact Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(WarningOrange.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = "Duplicate file warning",
                        tint = WarningOrange,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (prompt.isDirectory) "Folder already exists" else "File already exists",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = NeonCyan.copy(alpha = 0.16f),
                            border = BorderStroke(0.7.dp, NeonCyan.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = prompt.operationLabel.uppercase(),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Text(
                        text = prompt.fileName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WarningOrange,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onCancelTask,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("conflict_cancel_task_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel operation",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Compact path & size metadata strip
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = prompt.destinationPath,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!prompt.isDirectory && (prompt.existingSizeBytes >= 0L || prompt.incomingSizeBytes >= 0L)) {
                        val sizeLine = buildList {
                            if (prompt.existingSizeBytes >= 0L) {
                                add("Existing: ${formatBytes(prompt.existingSizeBytes)}")
                            }
                            if (prompt.incomingSizeBytes >= 0L) {
                                add("New: ${formatBytes(prompt.incomingSizeBytes)}")
                            }
                        }.joinToString("  •  ")
                        if (sizeLine.isNotEmpty()) {
                            Text(
                                text = sizeLine,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Optional "Apply to all" toggle for multi-file operations
            if (prompt.allowApplyToAll) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { applyToAll = !applyToAll }
                        .padding(horizontal = 2.dp, vertical = 1.dp)
                        .testTag("conflict_apply_all_row"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = applyToAll,
                        onCheckedChange = { applyToAll = it },
                        colors = CheckboxDefaults.colors(checkedColor = NeonCyan),
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = "Apply to all conflicts in this task",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            // Compact Action Stack: Copy and replace / Keep both / Skip
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { onChoose(FileConflictAction.COPY_AND_REPLACE, applyToAll) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WarningOrange,
                        contentColor = Color(0xFF141218)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("conflict_copy_and_replace_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FindReplace,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = "Copy and replace",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onChoose(FileConflictAction.KEEP_BOTH, applyToAll) },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1.15f)
                            .heightIn(min = 48.dp)
                            .testTag("conflict_keep_both_button")
                    ) {
                        Icon(
                            imageVector = if (prompt.isDirectory) Icons.Default.FolderCopy else Icons.Default.FileCopy,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "Keep both",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = { onChoose(FileConflictAction.SKIP, applyToAll) },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(0.85f)
                            .heightIn(min = 48.dp)
                            .testTag("conflict_skip_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Skip",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
