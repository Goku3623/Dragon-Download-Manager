package com.example.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.formatBytes
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rich Viewing Modes for Dragon Archiver.
 */
enum class FileManagerViewStyle(
    val label: String,
    val description: String,
    val icon: ImageVector
) {
    DETAILED_LIST(
        label = "Detailed List",
        description = "Rich cards with icons, size, date & quick install/extract badges",
        icon = Icons.Default.ViewAgenda
    ),
    COMPACT_LIST(
        label = "Compact List",
        description = "High-density single-column rows to browse many files quickly",
        icon = Icons.Default.ViewHeadline
    ),
    GALLERY_CARDS(
        label = "Large Media Cards",
        description = "Spacious preview cards with larger image thumbnails & details",
        icon = Icons.Default.ViewStream
    ),
    GRID_2(
        label = "Grid (2 Columns)",
        description = "Balanced 2-column grid cards with thumbnails & metadata",
        icon = Icons.Default.GridView
    ),
    GRID_3(
        label = "Compact Grid (3 Columns)",
        description = "Dense 3-column media grid for photos, APKs & folders",
        icon = Icons.Default.Apps
    ),
    SPLIT_DUAL_PANE(
        label = "Dual-Pane Split View",
        description = "MT-style side-by-side split panes with independent tools & instant copy/move",
        icon = Icons.Default.VerticalSplit
    )
}

/**
 * Independent state snapshot for one split pane in Dual-Pane Split View.
 * Allows Left and Right panes to maintain completely separate directories, back stacks,
 * archive browsing sessions, multi-selections, search queries, filters, sort order, and hidden-file toggles.
 */
data class SplitPaneStateSnapshot(
    val currentDir: File,
    val navigationHistory: List<File> = emptyList(),
    val activeBrowsedArchive: File? = null,
    val activeArchiveVirtualPath: String = "",
    val selectedPaths: Set<String> = emptySet(),
    val searchQuery: String = "",
    val selectedCategory: FileManagerFilterCategory = FileManagerFilterCategory.ALL,
    val sortField: FileManagerSortField = FileManagerSortField.NAME,
    val sortAscending: Boolean = true,
    val showHiddenFiles: Boolean = false
)

/**
 * Material 3 View Style Picker Dialog with all 6 Dragon Archiver viewing options.
 */
@Composable
internal fun FileManagerViewStyleDialog(
    currentStyle: FileManagerViewStyle,
    onSelectStyle: (FileManagerViewStyle) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f)),
            modifier = Modifier
                .width(325.dp)
                .heightIn(max = 580.dp)
                .testTag("file_manager_view_style_dialog")
        ) {
            Column(
                modifier = Modifier
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "VIEW STYLE OPTIONS",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        color = NeonCyan
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                FileManagerViewStyle.entries.forEach { style ->
                    val isSelected = style == currentStyle
                    val isSplit = style == FileManagerViewStyle.SPLIT_DUAL_PANE
                    val accent = if (isSplit) SuccessGreen else NeonCyan

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) accent.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            width = if (isSelected) 1.2.dp else 0.8.dp,
                            color = if (isSelected) accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelectStyle(style) }
                            .testTag("view_style_option_${style.name.lowercase(Locale.US)}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(accent.copy(alpha = if (isSelected) 0.24f else 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = style.icon,
                                    contentDescription = style.label,
                                    tint = accent,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = style.label,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isSplit) {
                                        Surface(
                                            shape = RoundedCornerShape(5.dp),
                                            color = SuccessGreen.copy(alpha = 0.18f)
                                        ) {
                                            Text(
                                                text = "DUAL",
                                                fontSize = 8.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Black,
                                                color = SuccessGreen,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = style.description,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = accent,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact high-density single-column row (COMPACT_LIST view style).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CompactFileListRow(
    item: FileItemEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: () -> Unit,
    onOpenMenu: () -> Unit
) {
    val iconColor = resolveFileEntryColor(item)
    val shortTime = remember(item.lastModifiedMs) { formatCompactMtTimestamp(item.lastModifiedMs) }
    val isSymlink = remember(item.file.absolutePath, item.isDirectory, item.archiveEntryPath) {
        item.archiveEntryPath == null && isLikelySymlink(item.file)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) NeonCyan.copy(alpha = 0.14f)
                else Color.Transparent
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isSelected) NeonCyan else iconColor.copy(alpha = 0.18f))
                .clickable(onClick = onSelectToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.Check else resolveFileEntryIcon(item),
                contentDescription = null,
                tint = if (isSelected) Color.Black else iconColor,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = shortTime,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                if (!item.isDirectory) {
                    Text(
                        text = formatBytes(item.lengthBytes),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        if (isSymlink) {
            Text(
                text = "->",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp)
            )
        }
        IconButton(onClick = onOpenMenu, modifier = Modifier.size(30.dp)) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "File Actions",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/**
 * Spacious Large Media Card row (GALLERY_CARDS view style).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GalleryCardFileRow(
    item: FileItemEntry,
    isSelected: Boolean,
    context: Context,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: () -> Unit,
    onOpenMenu: () -> Unit
) {
    val iconColor = resolveFileEntryColor(item)
    val isImageThumb = remember(item.extension, item.archiveEntryPath) {
        !item.isDirectory && item.archiveEntryPath == null &&
            item.extension in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    }
    val dateStr = remember(item.lastModifiedMs) { formatFileTimestampFast(item.lastModifiedMs) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) NeonCyan.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (isSelected) 1.3.dp else 0.8.dp,
            color = if (isSelected) NeonCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconColor.copy(alpha = 0.15f))
                    .clickable(onClick = onSelectToggle),
                contentAlignment = Alignment.Center
            ) {
                if (isImageThumb) {
                    AsyncImage(
                        model = remember(item.file.absolutePath) {
                            ImageRequest.Builder(context)
                                .data(item.file)
                                .size(160, 160)
                                .crossfade(false)
                                .build()
                        },
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = resolveFileEntryIcon(item),
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(30.dp)
                    )
                }
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(NeonCyan),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.Black,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = item.name,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = iconColor.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = if (item.isDirectory) "FOLDER" else item.extension.uppercase(Locale.US).ifBlank { "FILE" },
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = iconColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = if (item.isDirectory) {
                            if (item.childCount >= 0) "${item.childCount} item(s)" else "Directory"
                        } else {
                            formatBytes(item.lengthBytes)
                        },
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = dateStr,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onOpenMenu, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Item Actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Dense 3-column grid tile (GRID_3 view style).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CompactGridFileTile(
    item: FileItemEntry,
    isSelected: Boolean,
    context: Context,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: () -> Unit
) {
    val iconColor = resolveFileEntryColor(item)
    val isImageThumb = remember(item.extension, item.archiveEntryPath) {
        !item.isDirectory && item.archiveEntryPath == null &&
            item.extension in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) NeonCyan.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (isSelected) 1.3.dp else 0.7.dp,
            color = if (isSelected) NeonCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(
            modifier = Modifier.padding(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconColor.copy(alpha = 0.15f))
                    .clickable(onClick = onSelectToggle),
                contentAlignment = Alignment.Center
            ) {
                if (isImageThumb) {
                    AsyncImage(
                        model = remember(item.file.absolutePath) {
                            ImageRequest.Builder(context)
                                .data(item.file)
                                .size(128, 128)
                                .crossfade(false)
                                .build()
                        },
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = resolveFileEntryIcon(item),
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(26.dp)
                    )
                }
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(NeonCyan),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.Black,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
            }
            Text(
                text = item.name,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 13.sp
            )
            Text(
                text = if (item.isDirectory) "Folder" else formatBytes(item.lengthBytes),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/**
 * MT-Manager-Style Side-by-Side Dual-Pane Split View.
 *
 * Left Pane (index 0) and Right Pane (index 1) operate completely independently:
 * separate paths, back stacks, archive browsing, multi-selection, sorting, hidden files,
 * creation, renaming, deletion, APK editing, document viewing, and instant 1-tap
 * cross-pane Copy/Move and per-pane Paste.
 */
@Composable
internal fun DragonDualPaneSplitView(
    activePaneIndex: Int,
    leftState: SplitPaneStateSnapshot,
    rightState: SplitPaneStateSnapshot,
    leftItems: List<FileItemEntry>,
    rightItems: List<FileItemEntry>,
    leftLoading: Boolean,
    rightLoading: Boolean,
    clipboard: FileManagerClipboardState?,
    internalRootDir: File,
    romRootDir: File,
    ddmRootDir: File,
    downloadsDir: File,
    androidDataDir: File,
    androidObbDir: File,
    onActivatePane: (Int) -> Unit,
    onSwapPanes: () -> Unit,
    onSyncActivePathToOpposite: () -> Unit,
    onPaneNavigateDir: (paneIndex: Int, targetDir: File) -> Unit,
    onPaneNavigateUp: (paneIndex: Int) -> Unit,
    onPaneExitArchive: (paneIndex: Int) -> Unit,
    onPaneItemClick: (paneIndex: Int, item: FileItemEntry) -> Unit,
    onPaneItemLongClick: (paneIndex: Int, item: FileItemEntry) -> Unit,
    onPaneToggleSelect: (paneIndex: Int, item: FileItemEntry) -> Unit,
    onPanePasteHere: (paneIndex: Int) -> Unit,
    onPaneCreateFolder: (paneIndex: Int) -> Unit,
    onPaneCreateFile: (paneIndex: Int) -> Unit,
    onPaneSelectAll: (paneIndex: Int) -> Unit,
    onPaneOpenSort: (paneIndex: Int) -> Unit,
    onPaneRefresh: (paneIndex: Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("file_manager_dual_split_view")
    ) {
        // Slim Split-Pane Sync & Swap Control Strip
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left Pane Focus Pill
                val leftActive = activePaneIndex == 0
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = if (leftActive) NeonCyan.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        0.9.dp,
                        if (leftActive) NeonCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { onActivatePane(0) }
                        .testTag("split_pane_left_tab")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "L",
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            color = if (leftActive) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = leftState.activeBrowsedArchive?.name
                                ?: leftState.currentDir.name.ifBlank { "/" },
                            fontSize = 10.sp,
                            fontWeight = if (leftActive) FontWeight.Bold else FontWeight.Medium,
                            color = if (leftActive) NeonCyan else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Center Quick Sync (=) and Swap (⇄) Controls
                Row(
                    modifier = Modifier.padding(horizontal = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .clickable(onClick = onSyncActivePathToOpposite)
                            .testTag("split_pane_sync_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SyncAlt,
                            contentDescription = "Sync Path to Other Pane",
                            tint = WarningOrange,
                            modifier = Modifier
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .size(15.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .clickable(onClick = onSwapPanes)
                            .testTag("split_pane_swap_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Swap Left & Right Panes",
                            tint = NeonCyan,
                            modifier = Modifier
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .size(15.dp)
                        )
                    }
                }

                // Right Pane Focus Pill
                val rightActive = activePaneIndex == 1
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = if (rightActive) NeonCyan.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        0.9.dp,
                        if (rightActive) NeonCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { onActivatePane(1) }
                        .testTag("split_pane_right_tab")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "R",
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            color = if (rightActive) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = rightState.activeBrowsedArchive?.name
                                ?: rightState.currentDir.name.ifBlank { "/" },
                            fontSize = 10.sp,
                            fontWeight = if (rightActive) FontWeight.Bold else FontWeight.Medium,
                            color = if (rightActive) NeonCyan else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

        // Side-by-Side Split Panes (MT Manager Style!)
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            DualSplitPaneColumn(
                paneIndex = 0,
                isActive = activePaneIndex == 0,
                state = leftState,
                items = leftItems,
                isLoading = leftLoading,
                clipboard = clipboard,
                internalRootDir = internalRootDir,
                romRootDir = romRootDir,
                ddmRootDir = ddmRootDir,
                downloadsDir = downloadsDir,
                androidDataDir = androidDataDir,
                androidObbDir = androidObbDir,
                onActivate = { onActivatePane(0) },
                onNavigateDir = { onPaneNavigateDir(0, it) },
                onNavigateUp = { onPaneNavigateUp(0) },
                onExitArchive = { onPaneExitArchive(0) },
                onItemClick = { onPaneItemClick(0, it) },
                onItemLongClick = { onPaneItemLongClick(0, it) },
                onToggleSelect = { onPaneToggleSelect(0, it) },
                onPasteHere = { onPanePasteHere(0) },
                onCreateFolder = { onPaneCreateFolder(0) },
                onCreateFile = { onPaneCreateFile(0) },
                onSelectAll = { onPaneSelectAll(0) },
                onOpenSort = { onPaneOpenSort(0) },
                onRefresh = { onPaneRefresh(0) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            // Crisp Vertical Center Divider
            Box(
                modifier = Modifier
                    .width(1.2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            )

            DualSplitPaneColumn(
                paneIndex = 1,
                isActive = activePaneIndex == 1,
                state = rightState,
                items = rightItems,
                isLoading = rightLoading,
                clipboard = clipboard,
                internalRootDir = internalRootDir,
                romRootDir = romRootDir,
                ddmRootDir = ddmRootDir,
                downloadsDir = downloadsDir,
                androidDataDir = androidDataDir,
                androidObbDir = androidObbDir,
                onActivate = { onActivatePane(1) },
                onNavigateDir = { onPaneNavigateDir(1, it) },
                onNavigateUp = { onPaneNavigateUp(1) },
                onExitArchive = { onPaneExitArchive(1) },
                onItemClick = { onPaneItemClick(1, it) },
                onItemLongClick = { onPaneItemLongClick(1, it) },
                onToggleSelect = { onPaneToggleSelect(1, it) },
                onPasteHere = { onPanePasteHere(1) },
                onCreateFolder = { onPaneCreateFolder(1) },
                onCreateFile = { onPaneCreateFile(1) },
                onSelectAll = { onPaneSelectAll(1) },
                onOpenSort = { onPaneOpenSort(1) },
                onRefresh = { onPaneRefresh(1) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DualSplitPaneColumn(
    paneIndex: Int,
    isActive: Boolean,
    state: SplitPaneStateSnapshot,
    items: List<FileItemEntry>,
    isLoading: Boolean,
    clipboard: FileManagerClipboardState?,
    internalRootDir: File,
    romRootDir: File,
    ddmRootDir: File,
    downloadsDir: File,
    androidDataDir: File,
    androidObbDir: File,
    onActivate: () -> Unit,
    onNavigateDir: (File) -> Unit,
    onNavigateUp: () -> Unit,
    onExitArchive: () -> Unit,
    onItemClick: (FileItemEntry) -> Unit,
    onItemLongClick: (FileItemEntry) -> Unit,
    onToggleSelect: (FileItemEntry) -> Unit,
    onPasteHere: () -> Unit,
    onCreateFolder: () -> Unit,
    onCreateFile: () -> Unit,
    onSelectAll: () -> Unit,
    onOpenSort: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showQuickRoots by remember { mutableStateOf(false) }
    val hasClipboard = clipboard != null && (clipboard.files.isNotEmpty() || clipboard.archiveEntryPaths.isNotEmpty())
    val canGoUp = state.activeBrowsedArchive != null || state.currentDir.parentFile != null

    Column(
        modifier = modifier
            .background(
                if (isActive) MaterialTheme.colorScheme.background
                else MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.85f)
            )
            .testTag(if (paneIndex == 0) "split_pane_left_container" else "split_pane_right_container")
    ) {
        // Active Pane Accent Top Strip
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .background(if (isActive) NeonCyan else Color.Transparent)
        )

        // Independent Per-Pane Path & Quick Root Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isActive) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
                    else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f)
                )
                .clickable {
                    if (!isActive) {
                        onActivate()
                    } else {
                        showQuickRoots = !showQuickRoots
                    }
                }
                .padding(horizontal = 5.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = if (state.activeBrowsedArchive != null) Icons.Default.FolderZip else Icons.Default.FolderOpen,
                contentDescription = null,
                tint = if (isActive) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = if (state.activeBrowsedArchive != null) {
                    "${state.activeBrowsedArchive.name}/${state.activeArchiveVirtualPath}"
                } else {
                    state.currentDir.absolutePath
                },
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (state.selectedPaths.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = NeonCyan.copy(alpha = 0.22f)
                ) {
                    Text(
                        text = "${state.selectedPaths.size}",
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Black,
                        color = NeonCyan,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            if (state.activeBrowsedArchive != null) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Exit Archive",
                    tint = WarningOrange,
                    modifier = Modifier
                        .size(15.dp)
                        .clickable { onExitArchive() }
                )
            } else {
                Icon(
                    imageVector = if (showQuickRoots) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Quick Storage Locations",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        // Collapsible Per-Pane Quick Storage Switcher (Internal / Root / DDM / Downloads / Android/data / Android/obb)
        AnimatedVisibility(visible = showQuickRoots) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 5.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val roots = listOf(
                    "Internal" to internalRootDir,
                    "Root /" to romRootDir,
                    "DDM" to ddmRootDir,
                    "Downloads" to downloadsDir,
                    "data" to androidDataDir,
                    "obb" to androidObbDir
                )
                roots.forEach { (label, dir) ->
                    val current = state.currentDir.absolutePath == dir.absolutePath
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (current) NeonCyan.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            0.7.dp,
                            if (current) NeonCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                showQuickRoots = false
                                onNavigateDir(dir)
                            }
                    ) {
                        Text(
                            text = label,
                            fontSize = 9.sp,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                            color = if (current) NeonCyan else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // Per-Pane Compact Independent Toolbar (Up, Paste, New Folder, New File, Select All, Sort, Refresh)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 3.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                IconButton(
                    onClick = onNavigateUp,
                    enabled = canGoUp,
                    modifier = Modifier.size(25.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SubdirectoryArrowLeft,
                        contentDescription = "Parent Folder",
                        tint = if (canGoUp) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.size(14.dp)
                    )
                }
                IconButton(onClick = onCreateFolder, modifier = Modifier.size(25.dp)) {
                    Icon(
                        imageVector = Icons.Default.CreateNewFolder,
                        contentDescription = "New Folder in Pane",
                        tint = WarningOrange,
                        modifier = Modifier.size(14.dp)
                    )
                }
                IconButton(onClick = onCreateFile, modifier = Modifier.size(25.dp)) {
                    Icon(
                        imageVector = Icons.Default.NoteAdd,
                        contentDescription = "New File in Pane",
                        tint = NeonCyan,
                        modifier = Modifier.size(14.dp)
                    )
                }
                IconButton(onClick = onSelectAll, modifier = Modifier.size(25.dp)) {
                    Icon(
                        imageVector = Icons.Default.SelectAll,
                        contentDescription = "Select All in Pane",
                        tint = if (state.selectedPaths.isNotEmpty()) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
                IconButton(onClick = onOpenSort, modifier = Modifier.size(25.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = "Sort Pane",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
                IconButton(onClick = onRefresh, modifier = Modifier.size(25.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Pane",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            if (hasClipboard && state.activeBrowsedArchive == null) {
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = SuccessGreen.copy(alpha = 0.2f),
                    border = BorderStroke(0.7.dp, SuccessGreen),
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable(onClick = onPasteHere)
                        .testTag(if (paneIndex == 0) "split_pane_left_paste" else "split_pane_right_paste")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste Here",
                            tint = SuccessGreen,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = "Paste",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = SuccessGreen
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        // MT-Style High-Clarity File List
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(enabled = !isActive) { onActivate() }
        ) {
            when {
                isLoading && items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = NeonCyan)
                    }
                }
                items.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { onActivate() },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.FolderOff,
                                contentDescription = "Empty",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Empty",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 2.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Classic MT-Manager ".." Parent Entry at top of list for instant 1-tap navigation
                        if (canGoUp) {
                            item(key = "__parent_up__") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (!isActive) onActivate() else onNavigateUp()
                                        }
                                        .padding(horizontal = 7.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = "Parent Folder",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(7.dp))
                                    Text(
                                        text = "..",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        items(items, key = { it.selectionKey }) { entry ->
                            val isSelected = entry.selectionKey in state.selectedPaths
                            MtSplitPaneItemRow(
                                item = entry,
                                isSelected = isSelected,
                                onClick = {
                                    if (!isActive) onActivate() else onItemClick(entry)
                                },
                                onLongClick = { onItemLongClick(entry) },
                                onToggleSelect = {
                                    if (!isActive) onActivate() else onToggleSelect(entry)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clean, high-density MT-Manager-style row for Dual-Pane Split View.
 * Matches the crisp icon + filename + yy-MM-dd HH:mm timestamp + symlink `->` indicator aesthetic.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MtSplitPaneItemRow(
    item: FileItemEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit
) {
    val iconColor = resolveFileEntryColor(item)
    val shortDate = remember(item.lastModifiedMs) { formatCompactMtTimestamp(item.lastModifiedMs) }
    val isSymlink = remember(item.file.absolutePath, item.isDirectory, item.archiveEntryPath) {
        item.archiveEntryPath == null && isLikelySymlink(item.file)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) NeonCyan.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 7.dp, vertical = 5.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tap icon directly to toggle multi-select cleanly in Split View!
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    if (isSelected) NeonCyan
                    else if (item.isDirectory) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
                    else iconColor.copy(alpha = 0.18f)
                )
                .clickable(onClick = onToggleSelect),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.Check else resolveFileEntryIcon(item),
                contentDescription = null,
                tint = if (isSelected) Color.Black else iconColor,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(7.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) NeonCyan else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (item.isDirectory) shortDate else "$shortDate  ${formatBytes(item.lengthBytes)}",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isSymlink) {
            Text(
                text = "->",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 3.dp)
            )
        }
    }
}

private val compactMtDateFormat = ThreadLocal.withInitial {
    SimpleDateFormat("yy-MM-dd HH:mm", Locale.US)
}

private fun formatCompactMtTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return "--"
    return runCatching { compactMtDateFormat.get()?.format(Date(epochMs)) ?: "--" }.getOrDefault("--")
}

private fun isLikelySymlink(file: File): Boolean {
    return runCatching {
        val parent = file.parentFile ?: return false
        val canonicalParent = parent.canonicalFile
        val fileInCanonicalParent = File(canonicalParent, file.name)
        fileInCanonicalParent.canonicalPath != fileInCanonicalParent.absolutePath
    }.getOrDefault(false)
}
