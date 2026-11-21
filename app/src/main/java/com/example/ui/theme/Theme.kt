package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.data.model.SoothingAppTheme
import com.example.data.model.SurfaceThemeMode
import com.example.data.model.ThemeCustomizationConfig
import com.example.ui.haptics.LocalDragonHaptics
import com.example.ui.haptics.rememberDragonHaptics

data class GamingAccentOption(
    val name: String,
    val color: Color,
    val hex: String,
    val tag: String
)

val GAMING_ACCENT_OPTIONS = listOf(
    GamingAccentOption("Dragon Red", DragonRed, "#FF2A4B", "ROG Crimson"),
    GamingAccentOption("Neon Cyan", NeonCyan, "#00F0FF", "Cyber Ice"),
    GamingAccentOption("Emerald Matrix", EmeraldGreen, "#00F57A", "Venom Green"),
    GamingAccentOption("Amber HyperGold", AmberGold, "#FFBC00", "Snapdragon Gold"),
    GamingAccentOption("Electric Violet", ElectricViolet, "#E040FB", "Ultraviolet"),
    GamingAccentOption("Plasma Pink", PlasmaPink, "#FF1770", "Cyber Magenta"),
    GamingAccentOption("Cobalt Laser", CyberBlue, "#2979FF", "Adreno Blue"),
    GamingAccentOption("Solar Lava", SolarLava, "#FF5722", "Magma Orange"),
    GamingAccentOption("Arctic Mint", ArcticMint, "#63D2BE", "Liquid Cryo"),
    GamingAccentOption("Titanium Frost", TitaniumFrost, "#D5DCE6", "Stealth Silver"),
    GamingAccentOption("M3 Periwinkle", M3SoftBlue, "#9BBDF9", "Material You"),
    GamingAccentOption("M3 Sage Mint", M3SoftSage, "#8CD3AC", "Calm Tonal")
)

// Backward-compatible pair list for any callers expecting (Name, Color)
val ACCENT_COLORS = GAMING_ACCENT_OPTIONS.map { Pair(it.name, it.color) }

val LocalThemeCustomization = staticCompositionLocalOf { ThemeCustomizationConfig() }

private fun buildSoothingColorScheme(
    theme: SoothingAppTheme,
    useDynamicColor: Boolean,
    dynamicDark: ColorScheme?,
    dynamicLight: ColorScheme?
): ColorScheme {
    return when (theme) {
        SoothingAppTheme.EXPRESSIVE_MATERIAL_YOU -> {
            if (useDynamicColor && dynamicDark != null) {
                // Soften dynamic dark surfaces slightly to guarantee calm, non-contrasty glare-free readability
                dynamicDark.copy(
                    background = Color(0xFF16171D),
                    surface = Color(0xFF20222B),
                    surfaceVariant = Color(0xFF2A2D38),
                    surfaceContainerLowest = Color(0xFF121318),
                    surfaceContainerLow = Color(0xFF1B1D24),
                    surfaceContainer = Color(0xFF20222B),
                    surfaceContainerHigh = Color(0xFF2A2D38),
                    surfaceContainerHighest = Color(0xFF343846),
                    onBackground = Color(0xFFDEDFE8),
                    onSurface = Color(0xFFDEDFE8),
                    onSurfaceVariant = Color(0xFFA2A6B8),
                    outline = Color(0xFF424659),
                    outlineVariant = Color(0xFF343849)
                )
            } else {
                darkColorScheme(
                    primary = Color(0xFFB4A7E8),
                    onPrimary = Color(0xFF1E1933),
                    primaryContainer = Color(0xFF322C4D),
                    onPrimaryContainer = Color(0xFFE3DDF9),
                    secondary = Color(0xFF8DC9B0),
                    onSecondary = Color(0xFF122B21),
                    secondaryContainer = Color(0xFF233B31),
                    onSecondaryContainer = Color(0xFFD2EFE2),
                    tertiary = Color(0xFFE0B49A),
                    onTertiary = Color(0xFF301F14),
                    tertiaryContainer = Color(0xFF433026),
                    onTertiaryContainer = Color(0xFFF6E1D4),
                    background = Color(0xFF16171D),
                    onBackground = Color(0xFFDEDFE8),
                    surface = Color(0xFF20222B),
                    onSurface = Color(0xFFDEDFE8),
                    surfaceVariant = Color(0xFF2A2D38),
                    onSurfaceVariant = Color(0xFFA2A6B8),
                    surfaceContainerLowest = Color(0xFF121318),
                    surfaceContainerLow = Color(0xFF1B1D24),
                    surfaceContainer = Color(0xFF20222B),
                    surfaceContainerHigh = Color(0xFF2A2D38),
                    surfaceContainerHighest = Color(0xFF343846),
                    outline = Color(0xFF424659),
                    outlineVariant = Color(0xFF333748),
                    error = Color(0xFFE28A91),
                    onError = Color(0xFF381418)
                )
            }
        }

        SoothingAppTheme.MATERIAL_3_CALM -> darkColorScheme(
            primary = Material3TonalPrimary,
            onPrimary = Color(0xFF132238),
            primaryContainer = Color(0xFF26354E),
            onPrimaryContainer = Color(0xFFDCE7FB),
            secondary = Material3TonalSecondary,
            onSecondary = Color(0xFF112A1F),
            secondaryContainer = Color(0xFF223A30),
            onSecondaryContainer = Color(0xFFD2EFE0),
            tertiary = Material3TonalTertiary,
            onTertiary = Color(0xFF211B36),
            tertiaryContainer = Color(0xFF342E4C),
            onTertiaryContainer = Color(0xFFE6E0FA),
            background = Material3Bg,
            onBackground = Material3OnSurface,
            surface = Material3Surface,
            onSurface = Material3OnSurface,
            surfaceVariant = Material3SurfaceVariant,
            onSurfaceVariant = Material3OnSurfaceVariant,
            surfaceContainerLowest = Material3SurfaceLowest,
            surfaceContainerLow = Material3SurfaceLow,
            surfaceContainer = Material3Surface,
            surfaceContainerHigh = Material3SurfaceVariant,
            surfaceContainerHighest = Material3SurfaceHighest,
            outline = Material3Border,
            outlineVariant = Material3Border.copy(alpha = 0.65f),
            error = Color(0xFFE28A91),
            onError = Color(0xFF381418)
        )

        SoothingAppTheme.NORDIC_MIST -> darkColorScheme(
            primary = Color(0xFF88B3C8),
            onPrimary = Color(0xFF132530),
            primaryContainer = Color(0xFF2A3E4D),
            onPrimaryContainer = Color(0xFFD6E8F1),
            secondary = Color(0xFF8FBCBB),
            onSecondary = Color(0xFF142828),
            secondaryContainer = Color(0xFF2A4041),
            onSecondaryContainer = Color(0xFFD7ECEB),
            tertiary = Color(0xFFB498B8),
            onTertiary = Color(0xFF271B2A),
            tertiaryContainer = Color(0xFF3D3042),
            onTertiaryContainer = Color(0xFFE9DCEB),
            background = Color(0xFF1E242E),
            onBackground = Color(0xFFD8DEE9),
            surface = Color(0xFF272E3B),
            onSurface = Color(0xFFD8DEE9),
            surfaceVariant = Color(0xFF313A4A),
            onSurfaceVariant = Color(0xFFA5B0C2),
            surfaceContainerLowest = Color(0xFF191F28),
            surfaceContainerLow = Color(0xFF222935),
            surfaceContainer = Color(0xFF272E3B),
            surfaceContainerHigh = Color(0xFF313A4A),
            surfaceContainerHighest = Color(0xFF3B4558),
            outline = Color(0xFF475368),
            outlineVariant = Color(0xFF3A4457),
            error = Color(0xFFD88B90),
            onError = Color(0xFF361619)
        )

        SoothingAppTheme.SAGE_FOREST -> darkColorScheme(
            primary = Color(0xFF8FC4A3),
            onPrimary = Color(0xFF12281B),
            primaryContainer = Color(0xFF263F30),
            onPrimaryContainer = Color(0xFFD6EEDF),
            secondary = Color(0xFFA5C4BA),
            onSecondary = Color(0xFF172924),
            secondaryContainer = Color(0xFF2B3E38),
            onSecondaryContainer = Color(0xFFDBEBE6),
            tertiary = Color(0xFFCBB996),
            onTertiary = Color(0xFF2B2415),
            tertiaryContainer = Color(0xFF3F3728),
            onTertiaryContainer = Color(0xFFEEE5D3),
            background = Color(0xFF151B18),
            onBackground = Color(0xFFDCE4DF),
            surface = Color(0xFF1E2622),
            onSurface = Color(0xFFDCE4DF),
            surfaceVariant = Color(0xFF28332E),
            onSurfaceVariant = Color(0xFF9FB0A6),
            surfaceContainerLowest = Color(0xFF101613),
            surfaceContainerLow = Color(0xFF19211D),
            surfaceContainer = Color(0xFF1E2622),
            surfaceContainerHigh = Color(0xFF28332E),
            surfaceContainerHighest = Color(0xFF324039),
            outline = Color(0xFF3F4F46),
            outlineVariant = Color(0xFF313F38),
            error = Color(0xFFDC9090),
            onError = Color(0xFF381616)
        )

        SoothingAppTheme.WARM_SEPIA_DUSK -> darkColorScheme(
            primary = Color(0xFFD9B08C),
            onPrimary = Color(0xFF2D1E12),
            primaryContainer = Color(0xFF443326),
            onPrimaryContainer = Color(0xFFF3E2D3),
            secondary = Color(0xFFB5BFA1),
            onSecondary = Color(0xFF222719),
            secondaryContainer = Color(0xFF353B2C),
            onSecondaryContainer = Color(0xFFE4E9DA),
            tertiary = Color(0xFFCFA29C),
            onTertiary = Color(0xFF2F1C1A),
            tertiaryContainer = Color(0xFF45302E),
            onTertiaryContainer = Color(0xFFF0DCDA),
            background = Color(0xFF1B1815),
            onBackground = Color(0xFFE4DDD4),
            surface = Color(0xFF25211D),
            onSurface = Color(0xFFE4DDD4),
            surfaceVariant = Color(0xFF302B25),
            onSurfaceVariant = Color(0xFFB3A99E),
            surfaceContainerLowest = Color(0xFF161311),
            surfaceContainerLow = Color(0xFF201C18),
            surfaceContainer = Color(0xFF25211D),
            surfaceContainerHigh = Color(0xFF302B25),
            surfaceContainerHighest = Color(0xFF3B352E),
            outline = Color(0xFF4D453D),
            outlineVariant = Color(0xFF3D3630),
            error = Color(0xFFD98E88),
            onError = Color(0xFF381715)
        )

        SoothingAppTheme.ROSE_LAVENDER_DUSK -> darkColorScheme(
            primary = Color(0xFFD6A4B8),
            onPrimary = Color(0xFF2E1822),
            primaryContainer = Color(0xFF462E39),
            onPrimaryContainer = Color(0xFFF3DCE5),
            secondary = Color(0xFFB8A9D9),
            onSecondary = Color(0xFF211A31),
            secondaryContainer = Color(0xFF362F4A),
            onSecondaryContainer = Color(0xFFE7E0F6),
            tertiary = Color(0xFF9EC2C0),
            onTertiary = Color(0xFF152928),
            tertiaryContainer = Color(0xFF2A3E3D),
            onTertiaryContainer = Color(0xFFDCECEB),
            background = Color(0xFF1A171E),
            onBackground = Color(0xFFE2DCE6),
            surface = Color(0xFF24202A),
            onSurface = Color(0xFFE2DCE6),
            surfaceVariant = Color(0xFF2F2A37),
            onSurfaceVariant = Color(0xFFAEA6B8),
            surfaceContainerLowest = Color(0xFF151218),
            surfaceContainerLow = Color(0xFF1F1B24),
            surfaceContainer = Color(0xFF24202A),
            surfaceContainerHigh = Color(0xFF2F2A37),
            surfaceContainerHighest = Color(0xFF3A3444),
            outline = Color(0xFF4B4457),
            outlineVariant = Color(0xFF3B3545),
            error = Color(0xFFDF8E99),
            onError = Color(0xFF38151B)
        )

        SoothingAppTheme.WARM_ALABASTER_LIGHT -> {
            if (useDynamicColor && dynamicLight != null) {
                dynamicLight.copy(
                    background = Color(0xFFF3F0E8),
                    surface = Color(0xFFFAF8F3),
                    surfaceVariant = Color(0xFFEAE5DA),
                    surfaceContainerLowest = Color(0xFFFCFBF7),
                    surfaceContainerLow = Color(0xFFF6F3EC),
                    surfaceContainer = Color(0xFFF1EDE4),
                    surfaceContainerHigh = Color(0xFFEAE5DA),
                    surfaceContainerHighest = Color(0xFFE2DCCF),
                    onBackground = Color(0xFF2E3238),
                    onSurface = Color(0xFF2E3238),
                    onSurfaceVariant = Color(0xFF606670),
                    outline = Color(0xFFB9B3A5),
                    outlineVariant = Color(0xFFD5CFC2)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF4A6B8A),
                    onPrimary = Color(0xFFF8F9FA),
                    primaryContainer = Color(0xFFD9E4EE),
                    onPrimaryContainer = Color(0xFF1E3347),
                    secondary = Color(0xFF4E7A65),
                    onSecondary = Color(0xFFF7FAF8),
                    secondaryContainer = Color(0xFFD9EAE1),
                    onSecondaryContainer = Color(0xFF1D382B),
                    tertiary = Color(0xFF7A688A),
                    onTertiary = Color(0xFFF9F7FA),
                    tertiaryContainer = Color(0xFFE8E1EE),
                    onTertiaryContainer = Color(0xFF31253B),
                    background = Color(0xFFF3F0E8),
                    onBackground = Color(0xFF2E3238),
                    surface = Color(0xFFFAF8F3),
                    onSurface = Color(0xFF2E3238),
                    surfaceVariant = Color(0xFFEAE5DA),
                    onSurfaceVariant = Color(0xFF606670),
                    surfaceContainerLowest = Color(0xFFFCFBF7),
                    surfaceContainerLow = Color(0xFFF6F3EC),
                    surfaceContainer = Color(0xFFF1EDE4),
                    surfaceContainerHigh = Color(0xFFEAE5DA),
                    surfaceContainerHighest = Color(0xFFE2DCCF),
                    outline = Color(0xFFB9B3A5),
                    outlineVariant = Color(0xFFD5CFC2),
                    error = Color(0xFFB5585F),
                    onError = Color(0xFFFFF8F8)
                )
            }
        }

        SoothingAppTheme.MATCHA_MIST_LIGHT -> lightColorScheme(
            primary = Color(0xFF46735C),
            onPrimary = Color(0xFFF6FAF7),
            primaryContainer = Color(0xFFD7E8DD),
            onPrimaryContainer = Color(0xFF193527),
            secondary = Color(0xFF567486),
            onSecondary = Color(0xFFF6F9FB),
            secondaryContainer = Color(0xFFDCE7EE),
            onSecondaryContainer = Color(0xFF1F3440),
            tertiary = Color(0xFF7D7458),
            onTertiary = Color(0xFFFAF9F5),
            tertiaryContainer = Color(0xFFEBE7D9),
            onTertiaryContainer = Color(0xFF342F1E),
            background = Color(0xFFEFF3ED),
            onBackground = Color(0xFF2B342E),
            surface = Color(0xFFF7FAF5),
            onSurface = Color(0xFF2B342E),
            surfaceVariant = Color(0xFFE2E9DF),
            onSurfaceVariant = Color(0xFF5B6960),
            surfaceContainerLowest = Color(0xFFFAFCF9),
            surfaceContainerLow = Color(0xFFF2F6F0),
            surfaceContainer = Color(0xFFECF1EA),
            surfaceContainerHigh = Color(0xFFE2E9DF),
            surfaceContainerHighest = Color(0xFFD9E2D5),
            outline = Color(0xFFAEBCB0),
            outlineVariant = Color(0xFFCCD7CD),
            error = Color(0xFFB45B60),
            onError = Color(0xFFFFF8F8)
        )

        SoothingAppTheme.MINIMAL_FLAT_GRAPHITE -> darkColorScheme(
            primary = Color(0xFF8FA8C8),
            onPrimary = Color(0xFF141E2B),
            primaryContainer = Color(0xFF29323E),
            onPrimaryContainer = Color(0xFFDCE6F2),
            secondary = Color(0xFF93A89E),
            onSecondary = Color(0xFF16241D),
            secondaryContainer = Color(0xFF28332E),
            onSecondaryContainer = Color(0xFFDCE8E2),
            tertiary = Color(0xFFAFA8BE),
            onTertiary = Color(0xFF221E2B),
            tertiaryContainer = Color(0xFF312E3A),
            onTertiaryContainer = Color(0xFFE7E3F0),
            background = Color(0xFF1A1B1E),
            onBackground = Color(0xFFDCDFE4),
            surface = Color(0xFF222428),
            onSurface = Color(0xFFDCDFE4),
            surfaceVariant = Color(0xFF2B2D32),
            onSurfaceVariant = Color(0xFF9BA0AA),
            surfaceContainerLowest = Color(0xFF16171A),
            surfaceContainerLow = Color(0xFF1E2024),
            surfaceContainer = Color(0xFF222428),
            surfaceContainerHigh = Color(0xFF2B2D32),
            surfaceContainerHighest = Color(0xFF33363C),
            outline = Color(0xFF3D4048),
            outlineVariant = Color(0xFF30333A),
            error = Color(0xFFD88B90),
            onError = Color(0xFF361619)
        )

        SoothingAppTheme.MINIMAL_FLAT_MONO_INK -> darkColorScheme(
            primary = Color(0xFFC4C7CE),
            onPrimary = Color(0xFF1A1B1E),
            primaryContainer = Color(0xFF2D2E33),
            onPrimaryContainer = Color(0xFFE6E8EC),
            secondary = Color(0xFF9A9EA8),
            onSecondary = Color(0xFF18191C),
            secondaryContainer = Color(0xFF292A2E),
            onSecondaryContainer = Color(0xFFDCDEE3),
            tertiary = Color(0xFFB0B2B8),
            onTertiary = Color(0xFF1C1C1F),
            tertiaryContainer = Color(0xFF2E2F33),
            onTertiaryContainer = Color(0xFFE1E2E6),
            background = Color(0xFF18181A),
            onBackground = Color(0xFFDEDFE3),
            surface = Color(0xFF212124),
            onSurface = Color(0xFFDEDFE3),
            surfaceVariant = Color(0xFF2A2A2E),
            onSurfaceVariant = Color(0xFF9899A1),
            surfaceContainerLowest = Color(0xFF141416),
            surfaceContainerLow = Color(0xFF1C1C1F),
            surfaceContainer = Color(0xFF212124),
            surfaceContainerHigh = Color(0xFF2A2A2E),
            surfaceContainerHighest = Color(0xFF333338),
            outline = Color(0xFF3C3C42),
            outlineVariant = Color(0xFF2F2F35),
            error = Color(0xFFD48D90),
            onError = Color(0xFF341618)
        )

        SoothingAppTheme.MINIMAL_FLAT_SLATE_BLUE -> darkColorScheme(
            primary = Color(0xFF8BAFC4),
            onPrimary = Color(0xFF12232D),
            primaryContainer = Color(0xFF283640),
            onPrimaryContainer = Color(0xFFD9E9F2),
            secondary = Color(0xFF8FB5AE),
            onSecondary = Color(0xFF132623),
            secondaryContainer = Color(0xFF273836),
            onSecondaryContainer = Color(0xFFDBEBE8),
            tertiary = Color(0xFFA7ACC2),
            onTertiary = Color(0xFF1D202E),
            tertiaryContainer = Color(0xFF2E3240),
            onTertiaryContainer = Color(0xFFE2E5F0),
            background = Color(0xFF1B1F24),
            onBackground = Color(0xFFDCE2E8),
            surface = Color(0xFF23282F),
            onSurface = Color(0xFFDCE2E8),
            surfaceVariant = Color(0xFF2C323B),
            onSurfaceVariant = Color(0xFF9CA6B2),
            surfaceContainerLowest = Color(0xFF161A1F),
            surfaceContainerLow = Color(0xFF1F242A),
            surfaceContainer = Color(0xFF23282F),
            surfaceContainerHigh = Color(0xFF2C323B),
            surfaceContainerHighest = Color(0xFF353C47),
            outline = Color(0xFF404956),
            outlineVariant = Color(0xFF323944),
            error = Color(0xFFD78C92),
            onError = Color(0xFF361619)
        )

        SoothingAppTheme.MINIMAL_FLAT_STONE -> lightColorScheme(
            primary = Color(0xFF4C5B6B),
            onPrimary = Color(0xFFF6F7F8),
            primaryContainer = Color(0xFFDCE2E8),
            onPrimaryContainer = Color(0xFF1E2730),
            secondary = Color(0xFF5D7066),
            onSecondary = Color(0xFFF6F8F7),
            secondaryContainer = Color(0xFFDCE5E0),
            onSecondaryContainer = Color(0xFF223029),
            tertiary = Color(0xFF6B6573),
            onTertiary = Color(0xFFF8F7F9),
            tertiaryContainer = Color(0xFFE5E2E8),
            onTertiaryContainer = Color(0xFF2B2730),
            background = Color(0xFFEEECE8),
            onBackground = Color(0xFF2B2D30),
            surface = Color(0xFFF6F5F2),
            onSurface = Color(0xFF2B2D30),
            surfaceVariant = Color(0xFFE4E2DC),
            onSurfaceVariant = Color(0xFF61656B),
            surfaceContainerLowest = Color(0xFFFAF9F7),
            surfaceContainerLow = Color(0xFFF2F0EC),
            surfaceContainer = Color(0xFFECEAE4),
            surfaceContainerHigh = Color(0xFFE4E2DC),
            surfaceContainerHighest = Color(0xFFDBD8D1),
            outline = Color(0xFFB9B5AC),
            outlineVariant = Color(0xFFD2CEC5),
            error = Color(0xFFB35B60),
            onError = Color(0xFFFFF8F8)
        )

        SoothingAppTheme.MINIMAL_FLAT_LINEN -> lightColorScheme(
            primary = Color(0xFF8E6354),
            onPrimary = Color(0xFFFAF7F5),
            primaryContainer = Color(0xFFEBDED8),
            onPrimaryContainer = Color(0xFF38221B),
            secondary = Color(0xFF5E746C),
            onSecondary = Color(0xFFF6F9F8),
            secondaryContainer = Color(0xFFDDE6E2),
            onSecondaryContainer = Color(0xFF1F302A),
            tertiary = Color(0xFF7B6F5C),
            onTertiary = Color(0xFFFAF8F5),
            tertiaryContainer = Color(0xFFE9E3D8),
            onTertiaryContainer = Color(0xFF332C20),
            background = Color(0xFFF2EFEA),
            onBackground = Color(0xFF2F2B28),
            surface = Color(0xFFF8F6F2),
            onSurface = Color(0xFF2F2B28),
            surfaceVariant = Color(0xFFE7E2DA),
            onSurfaceVariant = Color(0xFF68625C),
            surfaceContainerLowest = Color(0xFFFCFAF7),
            surfaceContainerLow = Color(0xFFF5F2EC),
            surfaceContainer = Color(0xFFEFECE6),
            surfaceContainerHigh = Color(0xFFE7E2DA),
            surfaceContainerHighest = Color(0xFFDFD9CF),
            outline = Color(0xFFBEB6AA),
            outlineVariant = Color(0xFFD6CFC4),
            error = Color(0xFFB45C5E),
            onError = Color(0xFFFFF8F8)
        )
    }
}

@Composable
fun DragonKernelTheme(
    accentIndex: Int = 0,
    isAmoledBlack: Boolean = true,
    themeConfig: ThemeCustomizationConfig? = null,
    content: @Composable () -> Unit
) {
    val resolvedConfig = themeConfig ?: ThemeCustomizationConfig(
        primaryAccentIndex = if (accentIndex == 0) 10 else accentIndex,
        surfaceMode = if (isAmoledBlack) SurfaceThemeMode.MATERIAL_3 else SurfaceThemeMode.CYBER_SLATE
    )

    val context = LocalContext.current
    val dynamicDark = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        null
    }
    val dynamicLight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(context)
    } else {
        null
    }

    val colorScheme = buildSoothingColorScheme(
        theme = resolvedConfig.soothingTheme,
        useDynamicColor = resolvedConfig.useDynamicColor,
        dynamicDark = dynamicDark,
        dynamicLight = dynamicLight
    )

    val radius = resolvedConfig.cornerRadiusDp.coerceIn(8, 26)
    val customShapes = Shapes(
        extraSmall = RoundedCornerShape((radius / 3).coerceAtLeast(4).dp),
        small = RoundedCornerShape((radius / 2).coerceAtLeast(6).dp),
        medium = RoundedCornerShape(radius.dp),
        large = RoundedCornerShape((radius + 3).dp),
        extraLarge = RoundedCornerShape((radius + 6).dp)
    )

    val dragonHaptics = rememberDragonHaptics(
        enabled = resolvedConfig.hapticFeedbackEnabled,
        intensity = resolvedConfig.hapticIntensity
    )

    CompositionLocalProvider(
        LocalThemeCustomization provides resolvedConfig,
        LocalDragonHaptics provides dragonHaptics
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = customShapes,
            content = content
        )
    }
}


