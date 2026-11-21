package com.example.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.data.model.SoothingAppTheme
import com.example.data.model.ThemeCustomizationConfig
import com.example.ui.haptics.LocalDragonHaptics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemePickerSheet(
    themeConfig: ThemeCustomizationConfig,
    onSelectTheme: (SoothingAppTheme) -> Unit,
    onToggleDynamicWallpaper: (Boolean) -> Unit,
    onSelectCornerRadius: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val haptics = LocalDragonHaptics.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val flatThemes = SoothingAppTheme.entries.filter { it.isFlatMinimal }
    val darkThemes = SoothingAppTheme.entries.filter { !it.isLight && !it.isFlatMinimal }
    val lightThemes = SoothingAppTheme.entries.filter { it.isLight && !it.isFlatMinimal }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.testTag("theme_picker_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "UI Theme Studio",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Eye-soothing, low-glare themes for the whole app",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = {
                        haptics.tap()
                        onDismiss()
                    },
                    modifier = Modifier.testTag("close_theme_picker_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Theme Studio",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                // Wallpaper Dynamic Color Toggle (Android 12+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (themeConfig.soothingTheme == SoothingAppTheme.EXPRESSIVE_MATERIAL_YOU ||
                        themeConfig.soothingTheme == SoothingAppTheme.WARM_ALABASTER_LIGHT)
                ) {
                    item(key = "dynamic_wallpaper_toggle") {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "Wallpaper Dynamic Colors",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Softly adapt accents to your Android wallpaper",
                                            fontSize = 10.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Switch(
                                    checked = themeConfig.useDynamicColor,
                                    onCheckedChange = { enabled ->
                                        haptics.toggle(enabled)
                                        onToggleDynamicWallpaper(enabled)
                                    },
                                    modifier = Modifier.testTag("theme_dynamic_wallpaper_switch")
                                )
                            }
                        }
                    }
                }

                // Simple, Minimalist & Flat UI Themes Section
                item(key = "flat_minimal_themes_header") {
                    ThemeSectionLabel(
                        icon = Icons.Default.CropSquare,
                        title = "MINIMALIST & FLAT UI"
                    )
                }

                item(key = "flat_minimal_themes_grid") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        flatThemes.forEach { theme ->
                            SoothingThemeOptionCard(
                                theme = theme,
                                selected = themeConfig.soothingTheme == theme,
                                onClick = {
                                    haptics.confirm()
                                    onSelectTheme(theme)
                                }
                            )
                        }
                    }
                }

                // Dark Eye-Soothing Themes Section
                item(key = "dark_themes_header") {
                    ThemeSectionLabel(
                        icon = Icons.Default.DarkMode,
                        title = "CALM DARK THEMES"
                    )
                }

                item(key = "dark_themes_grid") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        darkThemes.forEach { theme ->
                            SoothingThemeOptionCard(
                                theme = theme,
                                selected = themeConfig.soothingTheme == theme,
                                onClick = {
                                    haptics.confirm()
                                    onSelectTheme(theme)
                                }
                            )
                        }
                    }
                }

                // Soft Light Themes Section
                item(key = "light_themes_header") {
                    ThemeSectionLabel(
                        icon = Icons.Default.LightMode,
                        title = "SOFT PAPER LIGHT THEMES"
                    )
                }

                item(key = "light_themes_grid") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        lightThemes.forEach { theme ->
                            SoothingThemeOptionCard(
                                theme = theme,
                                selected = themeConfig.soothingTheme == theme,
                                onClick = {
                                    haptics.confirm()
                                    onSelectTheme(theme)
                                }
                            )
                        }
                    }
                }

                // Card Corner Geometry Selector
                item(key = "corner_geometry_section") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeSectionLabel(
                            icon = Icons.Default.RoundedCorner,
                            title = "UI SHAPE STYLE"
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                Triple(8, "Flat", "8dp"),
                                Triple(14, "Crisp", "14dp"),
                                Triple(20, "M3", "20dp"),
                                Triple(24, "Expressive", "24dp")
                            ).forEach { (dpValue, label, sub) ->
                                val active = themeConfig.cornerRadiusDp == dpValue
                                Surface(
                                    shape = RoundedCornerShape(dpValue.dp.coerceAtMost(18.dp)),
                                    color = if (active) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    border = BorderStroke(
                                        width = if (active) 1.4.dp else 0.8.dp,
                                        color = if (active) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            haptics.tap()
                                            onSelectCornerRadius(dpValue)
                                        }
                                        .testTag("theme_corner_${dpValue}dp")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = sub,
                                            fontSize = 9.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeSectionLabel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = title,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SoothingThemeOptionCard(
    theme: SoothingAppTheme,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        label = "theme_card_border_${theme.name}"
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "theme_card_bg_${theme.name}"
    )

    val cardRadius = if (theme.isFlatMinimal) 10.dp else 16.dp
    val swatchOuterRadius = if (theme.isFlatMinimal) 6.dp else 12.dp
    val swatchInnerRadius = if (theme.isFlatMinimal) 3.dp else 5.dp
    val swatchAccentShape = if (theme.isFlatMinimal) RoundedCornerShape(3.dp) else CircleShape

    Surface(
        shape = RoundedCornerShape(cardRadius),
        color = containerColor,
        border = BorderStroke(width = if (selected) 1.4.dp else 0.85.dp, color = borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("theme_option_${theme.name.lowercase()}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Mini UI Palette Preview Swatch
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(swatchOuterRadius))
                    .background(Color(theme.previewBgHex))
                    .border(
                        width = 1.dp,
                        color = if (theme.isFlatMinimal) Color(theme.previewPrimaryHex).copy(alpha = 0.28f)
                        else Color(theme.previewPrimaryHex).copy(alpha = 0.45f),
                        shape = RoundedCornerShape(swatchOuterRadius)
                    )
                    .padding(5.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .clip(RoundedCornerShape(swatchInnerRadius))
                            .background(Color(theme.previewSurfaceHex))
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(if (theme.isFlatMinimal) 12.dp else 14.dp)
                                .clip(swatchAccentShape)
                                .background(Color(theme.previewPrimaryHex))
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(if (theme.isFlatMinimal) 12.dp else 14.dp)
                                .clip(swatchAccentShape)
                                .background(Color(theme.previewSecondaryHex))
                        )
                    }
                }
            }

            // Theme Name, Tag & Description
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = theme.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(theme.previewPrimaryHex).copy(alpha = if (theme.isLight) 0.16f else 0.20f)
                    ) {
                        Text(
                            text = theme.badge,
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = theme.description,
                    fontSize = 11.sp,
                    lineHeight = 14.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Active Theme",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
