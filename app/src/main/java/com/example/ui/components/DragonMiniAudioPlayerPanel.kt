package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.audio.AudioRepeatMode
import com.example.data.audio.DragonAudioPlayerEngine
import com.example.data.audio.DragonAudioPlayerState
import com.example.ui.haptics.LocalDragonHaptics
import java.util.Locale

/**
 * Minimalistic, floating compact audio player panel anchored cleanly above the system navigation bar.
 * Starts as a sleek single-row mini bar with an interactive top progress strip and springs open
 * into a compact control deck for scrubbing, queue skipping, speed, and repeat controls.
 */
@Composable
fun DragonMiniAudioPlayerHost(
    modifier: Modifier = Modifier
) {
    val state by DragonAudioPlayerEngine.stateFlow.collectAsState()

    AnimatedVisibility(
        visible = state.isVisible && state.currentTrack != null,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + fadeIn(animationSpec = tween(180)),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(180)
        ) + fadeOut(animationSpec = tween(150)),
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter
        ) {
            DragonMiniAudioPlayerCard(state = state)
        }
    }
}

@Composable
private fun DragonMiniAudioPlayerCard(
    state: DragonAudioPlayerState
) {
    val haptics = LocalDragonHaptics.current
    val track = state.currentTrack ?: return
    val primary = MaterialTheme.colorScheme.primary
    val surfaceBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    val liveFraction = if (state.durationMs > 0L) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    var isScrubbing by remember(track.id) { mutableStateOf(false) }
    var scrubFraction by remember(track.id) { mutableFloatStateOf(liveFraction) }
    val displayFraction = if (isScrubbing) scrubFraction else liveFraction
    val displayPositionMs = if (isScrubbing && state.durationMs > 0L) {
        (scrubFraction * state.durationMs).toLong()
    } else state.positionMs

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = surfaceBg.copy(alpha = 0.97f),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.horizontalGradient(
                listOf(
                    primary.copy(alpha = 0.42f),
                    outlineColor.copy(alpha = 0.45f),
                    primary.copy(alpha = 0.18f)
                )
            )
        ),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 500.dp)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
            .testTag("mini_audio_player_panel")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            primary.copy(alpha = 0.08f),
                            Color.Transparent,
                            Color.Transparent
                        )
                    )
                )
        ) {
            // Razor-thin live progress indicator along top edge when collapsed
            if (!state.isExpanded) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                ) {
                    drawRect(
                        color = outlineColor.copy(alpha = 0.25f),
                        size = size
                    )
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(primary.copy(alpha = 0.7f), primary)
                        ),
                        size = Size(size.width * displayFraction, size.height)
                    )
                }
            }

            // Ultra-minimal main compact row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.tap()
                        DragonAudioPlayerEngine.toggleExpanded()
                    }
                    .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Album artwork or animated equalizer badge
                MinimalAudioArtBadge(
                    albumArt = state.albumArt,
                    isPlaying = state.isPlaying,
                    isPreparing = state.isPreparing,
                    accentColor = primary
                )

                // Title & subtle metadata / timer
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = track.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitleText = buildString {
                        if (state.errorMessage != null) {
                            append(state.errorMessage)
                        } else {
                            if (track.artist.isNotBlank()) {
                                append(track.artist)
                                append(" • ")
                            } else if (state.qualityBadgeText.isNotBlank()) {
                                append(state.qualityBadgeText)
                                append(" • ")
                            }
                            append(DragonAudioPlayerEngine.formatDuration(displayPositionMs))
                            if (state.durationMs > 0L) {
                                append(" / ")
                                append(DragonAudioPlayerEngine.formatDuration(state.durationMs))
                            }
                        }
                    }
                    Text(
                        text = subtitleText,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (state.errorMessage != null) MaterialTheme.colorScheme.error else onSurfaceVar,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Compact action cluster: Play/Pause + Expand + Close
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Primary Play/Pause Circle
                    Surface(
                        shape = CircleShape,
                        color = primary,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(38.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptics.tap()
                                DragonAudioPlayerEngine.togglePlayPause()
                            }
                            .testTag("mini_audio_play_pause_btn")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (state.isPreparing) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (state.isPlaying) "Pause Audio" else "Play Audio",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                        }
                    }

                    // Expand / Collapse chevron
                    IconButton(
                        onClick = {
                            haptics.tap()
                            DragonAudioPlayerEngine.toggleExpanded()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("mini_audio_expand_btn")
                    ) {
                        Icon(
                            imageVector = if (state.isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = if (state.isExpanded) "Collapse Player" else "Expand Controls",
                            tint = onSurfaceVar,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // Close & Stop
                    IconButton(
                        onClick = {
                            haptics.tap()
                            DragonAudioPlayerEngine.stopAndClose()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("mini_audio_close_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Audio Player",
                            tint = onSurfaceVar,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Compact Expanded Deck (Interactive Scrubber + Transport + Speed/Repeat)
            AnimatedVisibility(
                visible = state.isExpanded,
                enter = expandVertically(animationSpec = tween(180)) + fadeIn(tween(140)),
                exit = shrinkVertically(animationSpec = tween(160)) + fadeOut(tween(110))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Sleek minimal interactive seek bar
                    MinimalSeekScrubber(
                        fraction = displayFraction,
                        accentColor = primary,
                        trackColor = outlineColor.copy(alpha = 0.35f),
                        onScrubStart = { frac ->
                            isScrubbing = true
                            scrubFraction = frac
                        },
                        onScrubMove = { frac ->
                            scrubFraction = frac
                        },
                        onScrubEnd = { frac ->
                            isScrubbing = false
                            if (state.durationMs > 0L) {
                                DragonAudioPlayerEngine.seekTo((frac * state.durationMs).toLong())
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mini_audio_seek_slider")
                    )

                    // Time stamps & queue counter
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = DragonAudioPlayerEngine.formatDuration(displayPositionMs),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = onSurfaceVar
                        )
                        if (state.queue.size > 1) {
                            val qualityInfo = if (state.qualityBadgeText.isNotBlank()) {
                                " • ${state.qualityBadgeText}"
                            } else ""
                            Text(
                                text = "Track ${state.currentIndex + 1}/${state.queue.size}$qualityInfo",
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                color = onSurfaceVar.copy(alpha = 0.85f)
                            )
                        } else if (state.qualityBadgeText.isNotBlank()) {
                            Text(
                                text = state.qualityBadgeText,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = onSurfaceVar.copy(alpha = 0.85f)
                            )
                        }
                        Text(
                            text = DragonAudioPlayerEngine.formatDuration(state.durationMs),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = onSurfaceVar
                        )
                    }

                    // Minimal transport row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Repeat mode button
                        IconButton(
                            onClick = {
                                haptics.tap()
                                DragonAudioPlayerEngine.cycleRepeatMode()
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("mini_audio_repeat_btn")
                        ) {
                            val repeatActive = state.repeatMode != AudioRepeatMode.OFF
                            Icon(
                                imageVector = if (state.repeatMode == AudioRepeatMode.REPEAT_ONE) {
                                    Icons.Default.RepeatOne
                                } else {
                                    Icons.Default.Repeat
                                },
                                contentDescription = "Repeat Mode",
                                tint = if (repeatActive) primary else onSurfaceVar.copy(alpha = 0.65f),
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // Previous / -10s
                        IconButton(
                            onClick = {
                                haptics.tap()
                                DragonAudioPlayerEngine.skipPrevious()
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("mini_audio_prev_btn")
                        ) {
                            Icon(
                                imageVector = if (state.queue.size > 1) Icons.Default.SkipPrevious else Icons.Default.Replay10,
                                contentDescription = if (state.queue.size > 1) "Previous Track" else "Rewind 10 seconds",
                                tint = onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Rewind 10s (when in queue mode)
                        if (state.queue.size > 1) {
                            IconButton(
                                onClick = {
                                    haptics.tap()
                                    DragonAudioPlayerEngine.seekBy(-10_000L)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Replay10,
                                    contentDescription = "Rewind 10 seconds",
                                    tint = onSurfaceVar,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    haptics.tap()
                                    DragonAudioPlayerEngine.seekBy(10_000L)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Forward10,
                                    contentDescription = "Forward 10 seconds",
                                    tint = onSurfaceVar,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Next / +10s
                        IconButton(
                            onClick = {
                                haptics.tap()
                                DragonAudioPlayerEngine.skipNext()
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("mini_audio_next_btn")
                        ) {
                            Icon(
                                imageVector = if (state.queue.size > 1) Icons.Default.SkipNext else Icons.Default.Forward10,
                                contentDescription = if (state.queue.size > 1) "Next Track" else "Forward 10 seconds",
                                tint = onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Minimal Hi-Fi DSP Preset & Speed Pill Cluster
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            val isDspActive = state.qualityPreset != com.example.data.audio.AudioQualityPreset.PURE_DIRECT
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isDspActive) {
                                    primary.copy(alpha = 0.16f)
                                } else {
                                    outlineColor.copy(alpha = 0.22f)
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        haptics.tap()
                                        DragonAudioPlayerEngine.cycleQualityPreset()
                                    }
                                    .testTag("mini_audio_dsp_preset_btn")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = "Audio Quality Profile",
                                        tint = if (isDspActive) primary else onSurfaceVar,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = state.qualityPreset.shortLabel,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isDspActive) primary else onSurfaceVar
                                    )
                                }
                            }

                            val speedLabel = if (state.playbackSpeed == 1.0f) {
                                "1x"
                            } else {
                                String.format(Locale.US, "%.2gx", state.playbackSpeed)
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (state.playbackSpeed != 1.0f) {
                                    primary.copy(alpha = 0.16f)
                                } else {
                                    outlineColor.copy(alpha = 0.22f)
                                },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        haptics.tap()
                                        DragonAudioPlayerEngine.cyclePlaybackSpeed()
                                    }
                                    .testTag("mini_audio_speed_btn")
                            ) {
                                Text(
                                    text = speedLabel,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (state.playbackSpeed != 1.0f) primary else onSurfaceVar,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimalAudioArtBadge(
    albumArt: android.graphics.Bitmap?,
    isPlaying: Boolean,
    isPreparing: Boolean,
    accentColor: Color
) {
    val imageBitmap = remember(albumArt) { albumArt?.asImageBitmap() }

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        accentColor.copy(alpha = 0.22f),
                        accentColor.copy(alpha = 0.08f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = "Audio Cover Art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.34f)),
                    contentAlignment = Alignment.Center
                ) {
                    MinimalEqualizerGlyph(color = Color.White, isAnimating = true)
                }
            }
        } else if (isPlaying && !isPreparing) {
            MinimalEqualizerGlyph(color = accentColor, isAnimating = true)
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = "Audio Track",
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun MinimalEqualizerGlyph(
    color: Color,
    isAnimating: Boolean
) {
    val transition = rememberInfiniteTransition(label = "mini_eq")
    val bar1 by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq1"
    )
    val bar2 by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(360, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq2"
    )
    val bar3 by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eq3"
    )

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        modifier = Modifier.height(16.dp)
    ) {
        val heights = if (isAnimating) listOf(bar1, bar2, bar3) else listOf(0.45f, 0.75f, 0.5f)
        heights.forEach { frac ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((16f * frac).dp.coerceAtLeast(4.dp))
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun MinimalSeekScrubber(
    fraction: Float,
    accentColor: Color,
    trackColor: Color,
    onScrubStart: (Float) -> Unit,
    onScrubMove: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var latestFraction by remember { mutableFloatStateOf(fraction) }
    Box(
        modifier = modifier
            .height(22.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    latestFraction = frac
                    onScrubStart(frac)
                    onScrubEnd(frac)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        latestFraction = frac
                        onScrubStart(frac)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val frac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        latestFraction = frac
                        onScrubMove(frac)
                    },
                    onDragEnd = {
                        onScrubEnd(latestFraction)
                    },
                    onDragCancel = {
                        onScrubEnd(latestFraction)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
        ) {
            val trackH = 3.5.dp.toPx()
            val centerY = size.height / 2f
            val corner = CornerRadius(trackH / 2f, trackH / 2f)
            val fillWidth = (size.width * fraction.coerceIn(0f, 1f)).coerceIn(0f, size.width)

            drawRoundRect(
                color = trackColor,
                topLeft = Offset(0f, centerY - trackH / 2f),
                size = Size(size.width, trackH),
                cornerRadius = corner
            )
            if (fillWidth > 0f) {
                drawRoundRect(
                    color = accentColor,
                    topLeft = Offset(0f, centerY - trackH / 2f),
                    size = Size(fillWidth, trackH),
                    cornerRadius = corner
                )
            }
            val thumbRadius = 5.5.dp.toPx()
            val thumbX = fillWidth.coerceIn(thumbRadius, (size.width - thumbRadius).coerceAtLeast(thumbRadius))
            drawCircle(
                color = accentColor,
                radius = thumbRadius,
                center = Offset(thumbX, centerY)
            )
        }
    }
}
