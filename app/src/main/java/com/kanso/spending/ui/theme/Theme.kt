package com.kanso.spending.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.kanso.spending.data.repository.LedgerThemeMode

@Immutable
data class KansoExtendedColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val elevatedSurface: Color,
    val hairlineBorder: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val positiveAccent: Color,
    val warningAccent: Color,
    val alertAccent: Color,
    val fjordAccent: Color,
    val ochreAccent: Color
)

val LocalKansoColors = staticCompositionLocalOf {
    KansoExtendedColors(
        isDark = false,
        background = KansoPalette.PaperBackground,
        surface = KansoPalette.PaperSurface,
        elevatedSurface = KansoPalette.PaperElevated,
        hairlineBorder = KansoPalette.PaperBorder,
        inkPrimary = KansoPalette.InkPrimary,
        inkSecondary = KansoPalette.InkSecondary,
        inkMuted = KansoPalette.InkMuted,
        positiveAccent = KansoPalette.BotanicalPine,
        warningAccent = KansoPalette.OchreWarning,
        alertAccent = KansoPalette.TerracottaAlert,
        fjordAccent = KansoPalette.FjordSlate,
        ochreAccent = KansoPalette.OchreWarning
    )
}

private val LightColorScheme = lightColorScheme(
    primary = KansoPalette.InkPrimary,
    onPrimary = KansoPalette.PaperSurface,
    secondary = KansoPalette.BotanicalPine,
    onSecondary = Color.White,
    tertiary = KansoPalette.TerracottaAlert,
    background = KansoPalette.PaperBackground,
    onBackground = KansoPalette.InkPrimary,
    surface = KansoPalette.PaperSurface,
    onSurface = KansoPalette.InkPrimary,
    surfaceVariant = KansoPalette.PaperElevated,
    onSurfaceVariant = KansoPalette.InkSecondary,
    outline = KansoPalette.PaperBorder,
    error = KansoPalette.TerracottaAlert
)

private val DarkColorScheme = darkColorScheme(
    primary = KansoPalette.ChalkPrimary,
    onPrimary = KansoPalette.ObsidianBackground,
    secondary = KansoPalette.BotanicalSage,
    onSecondary = KansoPalette.ObsidianBackground,
    tertiary = KansoPalette.CoralEmber,
    background = KansoPalette.ObsidianBackground,
    onBackground = KansoPalette.ChalkPrimary,
    surface = KansoPalette.ObsidianSurface,
    onSurface = KansoPalette.ChalkPrimary,
    surfaceVariant = KansoPalette.ObsidianElevated,
    onSurfaceVariant = KansoPalette.ChalkSecondary,
    outline = KansoPalette.ObsidianBorder,
    error = KansoPalette.CoralEmber
)

@Composable
fun KansoTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    KansoSpendingTheme(
        themeMode = if (darkTheme) LedgerThemeMode.OBSIDIAN_DARK else LedgerThemeMode.PAPER_LIGHT,
        content = content
    )
}

@Composable
fun KansoSpendingTheme(
    themeMode: LedgerThemeMode = LedgerThemeMode.PAPER_LIGHT,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        LedgerThemeMode.PAPER_LIGHT -> false
        LedgerThemeMode.OBSIDIAN_DARK -> true
        LedgerThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val extendedColors = if (useDark) {
        KansoExtendedColors(
            isDark = true,
            background = KansoPalette.ObsidianBackground,
            surface = KansoPalette.ObsidianSurface,
            elevatedSurface = KansoPalette.ObsidianElevated,
            hairlineBorder = KansoPalette.ObsidianBorder,
            inkPrimary = KansoPalette.ChalkPrimary,
            inkSecondary = KansoPalette.ChalkSecondary,
            inkMuted = KansoPalette.ChalkMuted,
            positiveAccent = KansoPalette.BotanicalSage,
            warningAccent = KansoPalette.OchreWarning,
            alertAccent = KansoPalette.CoralEmber,
            fjordAccent = KansoPalette.FjordSlate,
            ochreAccent = KansoPalette.OchreWarning
        )
    } else {
        KansoExtendedColors(
            isDark = false,
            background = KansoPalette.PaperBackground,
            surface = KansoPalette.PaperSurface,
            elevatedSurface = KansoPalette.PaperElevated,
            hairlineBorder = KansoPalette.PaperBorder,
            inkPrimary = KansoPalette.InkPrimary,
            inkSecondary = KansoPalette.InkSecondary,
            inkMuted = KansoPalette.InkMuted,
            positiveAccent = KansoPalette.BotanicalPine,
            warningAccent = KansoPalette.OchreWarning,
            alertAccent = KansoPalette.TerracottaAlert,
            fjordAccent = KansoPalette.FjordSlate,
            ochreAccent = KansoPalette.OchreWarning
        )
    }

    CompositionLocalProvider(LocalKansoColors provides extendedColors) {
        MaterialTheme(
            colorScheme = if (useDark) DarkColorScheme else LightColorScheme,
            typography = KansoTypography,
            content = content
        )
    }
}
