package com.example.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.downloader.ApkPackageInstaller
import com.example.data.filemanager.DragonTrashBinManager
import com.example.data.filemanager.DragonTrashEntry
import com.example.ui.haptics.LocalDragonHaptics
import com.example.ui.theme.DangerRed
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-featured Dragon Archiver Trash Bin modal.
 *
 * Allows inspecting deleted files & folders with their exact original storage paths,
 * selecting single or multiple items, restoring them directly back to their previous
 * original locations, or permanently deleting them / emptying the entire Trash Bin.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DragonTrashBinDialog(
    context: Context,
    onTrashMutated: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalDragonHaptics.current
    val trashItems by DragonTrashBinManager.trashItemsFlow.collectAsState()

    var isLoading by remember { mutableStateOf(true) }
    var isBusy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchOpen by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var pendingPermanentDelete by remember { mutableStateOf<List<DragonTrashEntry>?>(null) }
    var confirmEmptyAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DragonTrashBinManager.refreshTrash(context)
        }
        isLoading = false
    }

    // Keep selection pruned to existing items
    LaunchedEffect(trashItems) {
        val validIds = trashItems.map { it.id }.toSet()
        selectedIds = selectedIds.intersect(validIds)
    }

    val filteredItems = remember(trashItems, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) trashItems
        else trashItems.filter {
            it.originalName.lowercase(Locale.ROOT).contains(q) ||
                it.originalPath.lowercase(Locale.ROOT).contains(q)
        }
    }

    val totalTrashBytes = remember(trashItems) { trashItems.sumOf { it.sizeBytes } }
    val selectedItems = remember(trashItems, selectedIds) {
        trashItems.filter { it.id in selectedIds }
    }
    val selectedBytes = remember(selectedItems) { selectedItems.sumOf { it.sizeBytes } }
    val isSelectionMode = selectedIds.isNotEmpty()
    val allVisibleSelected = filteredItems.isNotEmpty() && filteredItems.all { it.id in selectedIds }

    fun runRestore(targets: List<DragonTrashEntry>) {
        if (targets.isEmpty() || isBusy) return
        haptics.confirm()
        isBusy = true
        busyLabel = if (targets.size == 1) "Restoring ${targets.first().originalName}..."
        else "Restoring ${targets.size} items to original locations..."
        scope.launch {
            val (restored, failed) = withContext(Dispatchers.IO) {
                DragonTrashBinManager.restoreItems(context, targets)
            }
            selectedIds = selectedIds - targets.map { it.id }.toSet()
            isBusy = false
            busyLabel = ""
            if (restored > 0) {
                onTrashMutated()
            }
            val msg = when {
                restored > 0 && failed == 0 ->
                    if (restored == 1) "Restored to original location"
                    else "Restored $restored items to original locations"
                restored > 0 && failed > 0 -> "Restored $restored item(s), $failed failed"
                else -> "Could not restore selected item(s)"
            }
            ApkPackageInstaller.showToastOnMain(context, msg)
        }
    }

    fun runPermanentDelete(targets: List<DragonTrashEntry>) {
        if (targets.isEmpty() || isBusy) return
        haptics.warning()
        isBusy = true
        busyLabel = "Permanently deleting ${targets.size} item(s)..."
        scope.launch {
            val removed = withContext(Dispatchers.IO) {
                DragonTrashBinManager.deletePermanently(context, targets)
            }
            selectedIds = selectedIds - targets.map { it.id }.toSet()
            isBusy = false
            busyLabel = ""
            onTrashMutated()
            ApkPackageInstaller.showToastOnMain(
                context,
                "Permanently deleted $removed item(s)"
            )
        }
    }

    fun runEmptyTrash() {
        if (isBusy) return
        haptics.warning()
        isBusy = true
        busyLabel = "Emptying Trash Bin..."
        scope.launch {
            val removed = withContext(Dispatchers.IO) {
                DragonTrashBinManager.emptyTrash(context)
            }
            selectedIds = emptySet()
            isBusy = false
            busyLabel = ""
            onTrashMutated()
            ApkPackageInstaller.showToastOnMain(
                context,
                if (removed > 0) "Emptied Trash Bin ($removed item(s) deleted)" else "Trash Bin is already empty"
            )
        }
    }

    Dialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .widthIn(max = 560.dp)
                .testTag("dragon_trash_bin_dialog"),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(WarningOrange.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Trash Bin",
                            tint = WarningOrange,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isSelectionMode) {
                                "${selectedIds.size} SELECTED"
                            } else {
                                "TRASH BIN"
                            },
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.6.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isSelectionMode) {
                                "${formatTrashBytes(selectedBytes)} selected • Restore or delete forever"
                            } else if (trashItems.isEmpty()) {
                                "0 items • Safe recovery vault"
                            } else {
                                "${trashItems.size} item(s) • ${formatTrashBytes(totalTrashBytes)} recoverable"
                            },
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (trashItems.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                haptics.tap()
                                isSearchOpen = !isSearchOpen
                                if (!isSearchOpen) searchQuery = ""
                            },
                            modifier = Modifier.testTag("trash_search_toggle_btn")
                        ) {
                            Icon(
                                imageVector = if (isSearchOpen) Icons.Default.SearchOff else Icons.Default.Search,
                                contentDescription = "Search Trash Bin",
                                tint = if (isSearchOpen) NeonCyan else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = {
                                haptics.tap()
                                selectedIds = if (allVisibleSelected) {
                                    emptySet()
                                } else {
                                    filteredItems.map { it.id }.toSet()
                                }
                            },
                            modifier = Modifier.testTag("trash_select_all_btn")
                        ) {
                            Icon(
                                imageVector = if (allVisibleSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                                contentDescription = if (allVisibleSelected) "Deselect All" else "Select All",
                                tint = if (allVisibleSelected) NeonCyan else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    IconButton(
                        onClick = { if (!isBusy) onDismiss() },
                        modifier = Modifier.testTag("trash_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Trash Bin",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Optional Search Bar
                AnimatedVisibility(visible = isSearchOpen && trashItems.isNotEmpty()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        placeholder = { Text("Search deleted files or paths...", fontSize = 12.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("trash_search_input")
                    )
                }

                // Quick Info & Empty Trash Strip
                if (trashItems.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isSelectionMode) {
                                "Tap items to select multiple"
                            } else {
                                "Tap checkbox or long-press to multi-select"
                            },
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DangerRed.copy(alpha = 0.14f),
                            border = BorderStroke(0.8.dp, DangerRed.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !isBusy) {
                                    haptics.tap()
                                    confirmEmptyAll = true
                                }
                                .testTag("trash_empty_all_btn")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteForever,
                                    contentDescription = "Empty Trash",
                                    tint = DangerRed,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Empty Trash",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = DangerRed
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                // Busy indicator banner
                if (isBusy) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = NeonCyan
                    )
                    if (busyLabel.isNotBlank()) {
                        Text(
                            text = busyLabel,
                            fontSize = 11.sp,
                            color = NeonCyan,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }

                // Main List or Empty State
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when {
                        isLoading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = NeonCyan)
                            }
                        }
                        filteredItems.isEmpty() -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoDelete,
                                        contentDescription = "Trash Bin Empty",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(34.dp)
                                    )
                                }
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    text = if (searchQuery.isNotBlank()) "No matching deleted files" else "Trash Bin is Empty",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = if (searchQuery.isNotBlank()) {
                                        "Try another file name or folder path."
                                    } else {
                                        "Deleted files and folders from Dragon Archiver are kept safely here so you can restore them to their exact original location."
                                    },
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(10.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                items(filteredItems, key = { it.id }) { entry ->
                                    val isSelected = entry.id in selectedIds
                                    TrashBinItemCard(
                                        entry = entry,
                                        isSelected = isSelected,
                                        isSelectionMode = isSelectionMode,
                                        enabled = !isBusy,
                                        onToggleSelect = {
                                            haptics.tap()
                                            selectedIds = if (isSelected) {
                                                selectedIds - entry.id
                                            } else {
                                                selectedIds + entry.id
                                            }
                                        },
                                        onRestore = { runRestore(listOf(entry)) },
                                        onDeleteForever = {
                                            haptics.tap()
                                            pendingPermanentDelete = listOf(entry)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Multi-Selection Batch Action Bar
                AnimatedVisibility(visible = isSelectionMode) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            HorizontalDivider(color = NeonCyan.copy(alpha = 0.4f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { runRestore(selectedItems) },
                                    enabled = !isBusy && selectedItems.isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SuccessGreen,
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("trash_batch_restore_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.RestoreFromTrash,
                                        contentDescription = "Restore Selected",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Restore (${selectedItems.size})",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1
                                    )
                                }

                                Button(
                                    onClick = {
                                        haptics.tap()
                                        pendingPermanentDelete = selectedItems
                                    },
                                    enabled = !isBusy && selectedItems.isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = DangerRed,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("trash_batch_delete_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteForever,
                                        contentDescription = "Delete Permanently",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Delete (${selectedItems.size})",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Permanent Delete Confirmation Dialog (Single or Multi-Select)
    pendingPermanentDelete?.let { targets ->
        val bytes = targets.sumOf { it.sizeBytes }
        AlertDialog(
            onDismissRequest = { pendingPermanentDelete = null },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = DangerRed) },
            title = {
                Text(
                    text = if (targets.size == 1) "Delete Permanently?" else "Delete ${targets.size} Items Permanently?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (targets.size == 1) {
                        "\"${targets.first().originalName}\" (${formatTrashBytes(bytes)}) will be permanently erased and cannot be recovered."
                    } else {
                        "${targets.size} selected items (${formatTrashBytes(bytes)}) will be permanently erased from storage and cannot be recovered."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toDelete = targets
                        pendingPermanentDelete = null
                        runPermanentDelete(toDelete)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) {
                    Text("Delete Forever", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPermanentDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Empty Entire Trash Bin Confirmation Dialog
    if (confirmEmptyAll) {
        AlertDialog(
            onDismissRequest = { confirmEmptyAll = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = DangerRed) },
            title = { Text("Empty Trash Bin?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "All ${trashItems.size} item(s) (${formatTrashBytes(totalTrashBytes)}) in Trash Bin will be permanently deleted to free storage space. This cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmEmptyAll = false
                        runEmptyTrash()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) {
                    Text("Empty Trash", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmptyAll = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashBinItemCard(
    entry: DragonTrashEntry,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    enabled: Boolean,
    onToggleSelect: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit
) {
    val (icon, tint) = remember(entry.originalName, entry.isDirectory) {
        resolveTrashItemIconAndColor(entry)
    }
    val deletedLabel = remember(entry.deletedAtMs) { formatTrashTimestamp(entry.deletedAtMs) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) NeonCyan.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (isSelected) 1.2.dp else 0.7.dp,
            color = if (isSelected) NeonCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                enabled = enabled,
                onClick = { onToggleSelect() },
                onLongClick = { onToggleSelect() }
            )
            .testTag("trash_item_${entry.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tappable selection checkbox / icon badge
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) NeonCyan else tint.copy(alpha = 0.16f))
                    .clickable(enabled = enabled, onClick = onToggleSelect),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isSelected) Icons.Default.Check else icon,
                    contentDescription = if (isSelected) "Selected" else entry.originalName,
                    tint = if (isSelected) Color.Black else tint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.originalName,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "From: ${entry.originalParentPath}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val sizeLabel = if (entry.isDirectory) {
                    "${entry.childCount} item(s) • ${formatTrashBytes(entry.sizeBytes)}"
                } else {
                    formatTrashBytes(entry.sizeBytes)
                }
                Text(
                    text = "$sizeLabel • $deletedLabel",
                    fontSize = 9.8.sp,
                    color = NeonCyan.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!isSelectionMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = onRestore,
                        enabled = enabled,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("trash_restore_${entry.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = "Restore ${entry.originalName}",
                            tint = SuccessGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = onDeleteForever,
                        enabled = enabled,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("trash_delete_${entry.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = "Delete ${entry.originalName} permanently",
                            tint = DangerRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    enabled = enabled
                )
            }
        }
    }
}

private fun resolveTrashItemIconAndColor(entry: DragonTrashEntry): Pair<ImageVector, Color> {
    if (entry.isDirectory) return Icons.Default.Folder to WarningOrange
    val ext = entry.originalName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (ext) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> Icons.Default.Image to Color(0xFF4FC3F7)
        "mp4", "mkv", "avi", "mov", "webm", "3gp", "ts" -> Icons.Default.Movie to Color(0xFFFF7043)
        "mp3", "m4a", "flac", "wav", "ogg", "opus", "aac" -> Icons.Default.MusicNote to Color(0xFFBA68C8)
        "zip", "rar", "7z", "tar", "gz", "xz", "bz2", "zst" -> Icons.Default.FolderZip to WarningOrange
        "apk", "apks", "xapk", "apkm" -> Icons.Default.Android to SuccessGreen
        "pdf", "epub", "doc", "docx", "txt", "md", "json", "xml" -> Icons.Default.Description to NeonCyan
        else -> Icons.Default.InsertDriveFile to Color(0xFF90A4AE)
    }
}

internal fun formatTrashBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1.0) return "$bytes B"
    val mb = kb / 1024.0
    if (mb < 1.0) return String.format(Locale.US, "%.1f KB", kb)
    val gb = mb / 1024.0
    if (gb < 1.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", gb)
}

private fun formatTrashTimestamp(deletedAtMs: Long): String {
    val diffMs = (System.currentTimeMillis() - deletedAtMs).coerceAtLeast(0L)
    val mins = diffMs / 60_000L
    return when {
        mins < 1L -> "Just now"
        mins < 60L -> "${mins}m ago"
        mins < 24L * 60L -> "${mins / 60L}h ago"
        else -> SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(deletedAtMs))
    }
}
