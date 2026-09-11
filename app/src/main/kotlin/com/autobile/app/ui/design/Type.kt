package com.autobile.app.ui.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.autobile.app.R

/**
 * IBM Plex Sans, bundled rather than taken from the system.
 *
 * Roboto is the face any Android project reaches for by default and carries no opinion of
 * its own. Plex has a slightly technical, drawn-for-instrumentation character that suits an
 * interface whose job is reporting what a machine is doing, and its humanist proportions
 * sit comfortably beside the Korean face the device substitutes for Hangul — only the Latin
 * subset is bundled, because shipping a CJK family would cost several megabytes.
 */
private val PlexSans = FontFamily(
    Font(R.font.plex_sans_regular, FontWeight.Normal),
    Font(R.font.plex_sans_medium, FontWeight.Medium),
    Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
)

/**
 * Trims the extra leading Android adds above and below a line.
 *
 * Without this, a heading sits visually lower than its own box and no amount of padding
 * lines it up with the rule beside it.
 */
private val Trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * One scale, used everywhere.
 *
 * Sizes step by roughly a major second rather than Material's default ladder, which gives
 * a screen title enough presence to replace the app bar it stands in for. Display sizes
 * carry negative tracking because Plex sets slightly wide at large optical sizes.
 */
object TypeScale {
    val display = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.02).em,
        lineHeightStyle = Trim,
    )
    val title = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.01).em,
        lineHeightStyle = Trim,
    )
    val heading = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        lineHeightStyle = Trim,
    )
    val body = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        lineHeightStyle = Trim,
    )
    val label = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        lineHeightStyle = Trim,
    )
    val meta = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        lineHeightStyle = Trim,
    )
    /** Figures that are read as quantities: percentages, counts, durations. */
    val figure = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.01).em,
        lineHeightStyle = Trim,
    )
}

internal val AutobileTypography = Typography(
    displaySmall = TypeScale.display,
    headlineMedium = TypeScale.display,
    headlineSmall = TypeScale.title,
    titleLarge = TypeScale.title,
    titleMedium = TypeScale.heading,
    bodyLarge = TypeScale.body,
    bodyMedium = TypeScale.body,
    bodySmall = TypeScale.meta,
    labelLarge = TypeScale.label,
    labelMedium = TypeScale.label,
    labelSmall = TypeScale.meta,
)
