package com.example.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.BatchDownloadConfig
import com.example.data.model.BatchDownloadMode
import com.example.data.model.YouTubePlaylistInfo
import com.example.data.model.YouTubePlaylistItem
import com.example.ui.haptics.LocalDragonHaptics

@Composable
fun YouTubePlaylistCard(
    playlist: YouTubePlaylistInfo,
    isFlatMinimal: Boolean,
    cornerRadiusDp: Int,
    onDismiss: () -> Unit,
    onStartPlaylistDownload: (selectedItems: List<YouTubePlaylistItem>, config: BatchDownloadConfig) -> Unit
) {
    val haptics = LocalDragonHaptics.current
    var selectedIds by remember(playlist.playlistId, playlist.items.size) {
        mutableStateOf(playlist.items.map { it.videoId }.toSet())
    }
    var selectedMode by remember(playlist.playlistId) {
        mutableStateOf(BatchDownloadMode.BEST_VIDEO)
    }
    var maxConcurrent by remember(playlist.playlistId) {
        mutableIntStateOf(2)
    }
    var showAllItems by remember(playlist.playlistId) {
        mutableStateOf(false)
    }

    val cardRadius = cornerRadiusDp.coerceIn(8, 24).dp
    val innerRadius = if (isFlatMinimal) 8.dp else 12.dp
    val allSelected = selectedIds.size == playlist.items.size && playlist.items.isNotEmpty()
    val selectedItems = remember(playlist.items, selectedIds) {
        playlist.items.filter { it.videoId in selectedIds }
    }
    val saveFolderLabel = if (selectedMode.isAudioOnly) "Download/DDM/Audio" else "Download/DDM/Video"

    Card(
        shape = RoundedCornerShape(cardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (isFlatMinimal) MaterialTheme.colorScheme.outlineVariant
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("youtube_playlist_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Header: Thumbnail, Playlist Title, Count & Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 84.dp, height = 56.dp)
                        .clip(RoundedCornerShape(innerRadius))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    if (playlist.thumbnailUrl.isNotBlank()) {
                        AsyncImage(
                            model = playlist.thumbnailUrl,
                            contentDescription = playlist.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                    ) {
                        Text(
                            text = "${playlist.items.size}",
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "YOUTUBE PLAYLIST",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = playlist.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${playlist.creator} • ${playlist.items.size} videos",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = {
                        haptics.tap()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("dismiss_playlist_card_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close playlist",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // 2. Format & Quality Selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "FORMAT & QUALITY",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val modes = listOf(
                    BatchDownloadMode.BEST_VIDEO to "Best Video",
                    BatchDownloadMode.VIDEO_1080P to "1080p",
                    BatchDownloadMode.VIDEO_720P to "720p",
                    BatchDownloadMode.VIDEO_480P to "480p",
                    BatchDownloadMode.AUDIO_MP3 to "MP3 320k",
                    BatchDownloadMode.AUDIO_M4A to "M4A Audio"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    modes.forEach { (mode, shortLabel) ->
                        val active = selectedMode == mode
                        Surface(
                            shape = RoundedCornerShape(if (isFlatMinimal) 7.dp else 10.dp),
                            color = if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                width = if (active) 1.2.dp else 0.9.dp,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(if (isFlatMinimal) 7.dp else 10.dp))
                                .clickable {
                                    haptics.tap()
                                    selectedMode = mode
                                }
                                .testTag("playlist_mode_${mode.name.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = if (mode.isAudioOnly) Icons.Default.AudioFile else Icons.Default.Movie,
                                    contentDescription = null,
                                    tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = shortLabel,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // 3. Parallel Workers & Save Path Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = saveFolderLabel,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "Parallel:",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf(1, 2, 3).forEach { count ->
                        val active = maxConcurrent == count
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                width = 0.9.dp,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    haptics.tap()
                                    maxConcurrent = count
                                }
                                .testTag("playlist_parallel_$count")
                        ) {
                            Text(
                                text = "${count}x",
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                thickness = 0.8.dp
            )

            // 4. Select All / Video Checklist
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "VIDEOS (${selectedItems.size} / ${playlist.items.size} SELECTED)",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        haptics.tap()
                        selectedIds = if (allSelected) emptySet()
                        else playlist.items.map { it.videoId }.toSet()
                    },
                    modifier = Modifier.testTag("playlist_toggle_select_all")
                ) {
                    Text(
                        text = if (allSelected) "Deselect All" else "Select All",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            val visibleItems = if (showAllItems) playlist.items else playlist.items.take(6)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                visibleItems.forEach { item ->
                    val isChecked = item.videoId in selectedIds
                    Surface(
                        shape = RoundedCornerShape(if (isFlatMinimal) 7.dp else 10.dp),
                        color = if (isChecked) MaterialTheme.colorScheme.surfaceContainerLow
                        else MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                        border = BorderStroke(
                            width = 0.8.dp,
                            color = if (isChecked) MaterialTheme.colorScheme.outlineVariant
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(if (isFlatMinimal) 7.dp else 10.dp))
                            .clickable {
                                haptics.tap()
                                selectedIds = if (isChecked) selectedIds - item.videoId
                                else selectedIds + item.videoId
                            }
                            .testTag("playlist_video_item_${item.index}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                contentDescription = if (isChecked) "Selected" else "Not selected",
                                tint = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                            Text(
                                text = "#${item.index + 1}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(26.dp)
                            )
                            AsyncImage(
                                model = item.thumbnailUrl,
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 48.dp, height = 30.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isChecked) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitleParts = listOfNotNull(
                                    item.creator.takeIf { it.isNotBlank() },
                                    item.durationFormatted.takeIf { it.isNotBlank() }
                                ).joinToString(" • ")
                                if (subtitleParts.isNotBlank()) {
                                    Text(
                                        text = subtitleParts,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (playlist.items.size > 6) {
                TextButton(
                    onClick = {
                        haptics.tap()
                        showAllItems = !showAllItems
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist_expand_all_button")
                ) {
                    Icon(
                        imageVector = if (showAllItems) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (showAllItems) "Show Fewer Videos" else "Show All ${playlist.items.size} Videos",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 5. Primary Action: Download Whole Playlist
            Button(
                onClick = {
                    haptics.heavyAction()
                    val preferredRes = when (selectedMode) {
                        BatchDownloadMode.VIDEO_1080P -> 1080
                        BatchDownloadMode.VIDEO_720P -> 720
                        BatchDownloadMode.VIDEO_480P -> 480
                        else -> 2160
                    }
                    onStartPlaylistDownload(
                        selectedItems,
                        BatchDownloadConfig(
                            downloadMode = selectedMode,
                            preferredResolutionHeight = preferredRes,
                            preferredAudioBitrateKbps = 320,
                            maxConcurrentDownloads = maxConcurrent
                        )
                    )
                },
                enabled = selectedItems.isNotEmpty(),
                shape = RoundedCornerShape(innerRadius),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .testTag("download_playlist_button")
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (allSelected) {
                        "Download Entire Playlist (${selectedItems.size} Videos)"
                    } else {
                        "Download Selected (${selectedItems.size} / ${playlist.items.size})"
                    },
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
