package com.autobile.app.ui.design

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Colour carries one meaning in this interface: the system is doing something, or it needs
 * a decision.
 *
 * Everything at rest is neutral. There is no colour for success, because a task that
 * simply worked is not an event — it is the expected state, and colouring it drains the
 * signal from the cases that genuinely need a person's attention. In an app whose central
 * question is "what is my phone doing right now", spending the whole colour budget on that
 * question is the honest allocation.
 */
data class AutobileColors(
    val paper: Color,
    val raised: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    /** The agent is acting, and primary actions that start it. */
    val live: Color,
    val onLive: Color,
    val liveWash: Color,
    /** A decision is required before anything else happens. */
    val caution: Color,
    /** Something did not work. */
    val fault: Color,
    val isDark: Boolean,
)

private val LightPalette = AutobileColors(
    paper = Color(0xFFFCFCFD),
    raised = Color(0xFFFFFFFF),
    ink = Color(0xFF191C22),
    muted = Color(0xFF646B7A),
    line = Color(0xFFE4E7EC),
    live = Color(0xFF3D3AE0),
    onLive = Color(0xFFFFFFFF),
    liveWash = Color(0xFFEEEEFD),
    caution = Color(0xFFA8560A),
    fault = Color(0xFFB42318),
    isDark = false,
)

private val DarkPalette = AutobileColors(
    paper = Color(0xFF101218),
    raised = Color(0xFF181B23),
    ink = Color(0xFFF2F3F6),
    muted = Color(0xFF959CAC),
    line = Color(0xFF272B35),
    live = Color(0xFF9A97FF),
    onLive = Color(0xFF13122E),
    liveWash = Color(0xFF1E1F3A),
    caution = Color(0xFFE0A05A),
    fault = Color(0xFFF0837B),
    isDark = true,
)

val LocalAutobileColors = staticCompositionLocalOf { LightPalette }

/** Shorthand for the palette, used as `theme.ink` throughout the interface. */
val theme: AutobileColors
    @Composable get() = LocalAutobileColors.current

@Composable
fun AutobileTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    val view = LocalView.current

    if (!view.isInEditMode) {
        val window = LocalActivity.current?.window
        SideEffect {
            window?.let {
                // The bars are transparent and the content runs underneath, so the icon
                // tint has to follow the palette rather than the theme XML.
                WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }

    CompositionLocalProvider(LocalAutobileColors provides palette) {
        MaterialTheme(
            // Material components that survive in this interface — the switch, the text
            // field, the dialog — read their colours from here.
            colorScheme = if (dark) {
                darkColorScheme(
                    primary = palette.live,
                    onPrimary = palette.onLive,
                    background = palette.paper,
                    surface = palette.raised,
                    onSurface = palette.ink,
                    onSurfaceVariant = palette.muted,
                    outline = palette.line,
                    error = palette.fault,
                )
            } else {
                lightColorScheme(
                    primary = palette.live,
                    onPrimary = palette.onLive,
                    background = palette.paper,
                    surface = palette.raised,
                    onSurface = palette.ink,
                    onSurfaceVariant = palette.muted,
                    outline = palette.line,
                    error = palette.fault,
                )
            },
            typography = AutobileTypography,
            content = content,
        )
    }
}
