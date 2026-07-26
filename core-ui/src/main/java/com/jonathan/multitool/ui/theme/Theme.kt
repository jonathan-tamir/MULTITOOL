package com.jonathan.multitool.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Surface/line/text tokens straight out of the design comp's theme(). Two elevations only.
 * Exposed as a CompositionLocal so shell chrome can use them literally while the migrated
 * tool screens keep reading MaterialTheme.colorScheme (which is mapped onto the same values).
 */
@Immutable
data class ShellTokens(
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val cardHover: Color,
    val drawerBg: Color,
    val scrim: Color,
    val btnInk: Color,
    val fg: Color,
    val fg80: Color,
    val fg60: Color,
    val fg50: Color,
    val fg40: Color,
    val fg30: Color,
    val fg20: Color,
    val soft: Color,
    val line: Color,
    val line2: Color,
    val line3: Color,
    val line4: Color
)

val LocalShell = staticCompositionLocalOf<ShellTokens> { shellTokens(true) }
val LocalAccent = staticCompositionLocalOf { accentFor(195f, true) }

fun shellTokens(dark: Boolean): ShellTokens {
    fun fg(a: Float) = if (dark) Color(0.910f, 0.902f, 0.890f, a) else Color(0.078f, 0.086f, 0.102f, a)
    fun w(a: Float) = if (dark) Color(1f, 1f, 1f, a) else Color(0f, 0f, 0f, a)
    return ShellTokens(
        dark = dark,
        bg = if (dark) Color(0xFF0A0B0D) else Color(0xFFF6F5F2),
        card = if (dark) Color(0xFF131519) else Color(0xFFFFFFFF),
        cardHover = if (dark) Color(0xFF191C21) else Color(0xFFFBFAF8),
        drawerBg = if (dark) Color(0xFF101215) else Color(0xFFFFFFFF),
        scrim = if (dark) Color(0.02f, 0.024f, 0.027f, 0.62f) else Color(0.118f, 0.125f, 0.141f, 0.35f),
        btnInk = if (dark) Color(0xFF0A0B0D) else Color(0xFFFFFFFF),
        fg = if (dark) Color(0xFFE8E6E3) else Color(0xFF14161A),
        fg80 = fg(0.80f), fg60 = fg(0.60f), fg50 = fg(0.50f),
        fg40 = fg(0.42f), fg30 = fg(0.32f), fg20 = fg(0.20f),
        soft = w(if (dark) 0.05f else 0.04f),
        line = w(if (dark) 0.08f else 0.10f),
        line2 = w(if (dark) 0.13f else 0.16f),
        line3 = w(if (dark) 0.20f else 0.26f),
        line4 = w(if (dark) 0.28f else 0.34f)
    )
}

@Composable
fun MultitoolTheme(
    themeMode: String,
    accent: Color,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val t = shellTokens(dark)
    // The tool screens were written against Material colours; map the shell tokens onto the
    // scheme so they inherit the shell's look with no per-screen changes.
    val scheme = if (dark) {
        darkColorScheme(
            primary = accent, onPrimary = t.btnInk,
            secondary = accent, onSecondary = t.btnInk,
            background = t.bg, onBackground = t.fg,
            surface = t.card, onSurface = t.fg,
            surfaceVariant = Color(0xFF1A1D22), onSurfaceVariant = t.fg50,
            outline = Color(0xFF3A4048), error = oklch(0.70f, 0.16f, 25f)
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = t.btnInk,
            secondary = accent, onSecondary = t.btnInk,
            background = t.bg, onBackground = t.fg,
            surface = t.card, onSurface = t.fg,
            surfaceVariant = Color(0xFFEAE8E3), onSurfaceVariant = t.fg50,
            outline = Color(0xFFC6C2BA), error = oklch(0.50f, 0.19f, 25f)
        )
    }
    CompositionLocalProvider(LocalShell provides t, LocalAccent provides accent) {
        MaterialTheme(colorScheme = scheme, typography = ShellTypography, content = content)
    }
}
