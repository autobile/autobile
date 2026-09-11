package com.autobile.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.text.DateFormat
import java.util.Date

/**
 * Dates follow the reader's locale rather than a fixed pattern.
 *
 * `DateFormat` is asked for the format each time rather than cached, so switching the app
 * language updates timestamps already on screen instead of leaving the previous locale's
 * formatting behind until the process restarts.
 */
@Composable
fun formatTimestamp(millis: Long): String {
    LocalConfiguration.current
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}

@Composable
fun formatTime(millis: Long): String {
    LocalConfiguration.current
    return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
}

/** A fraction rendered as a whole percentage. */
fun Float.asPercent(): String = "${(this * 100).toInt()}%"

/** The readable part of a package name, for showing which app a run touched. */
fun appLabel(packageName: String): String =
    packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
