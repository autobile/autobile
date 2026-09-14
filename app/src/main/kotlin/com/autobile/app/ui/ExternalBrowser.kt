package com.autobile.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens a page in whatever browser the person already uses.
 *
 * Sign-in happens here rather than in a view inside the app so the address bar is the
 * real one. A password prompt drawn by this app would look exactly like a password
 * prompt drawn by the service, and the user would have no way to tell them apart.
 *
 * Returns whether a browser actually took it, so a phone with none installed reports
 * that instead of waiting for a redirect that will never arrive.
 */
fun Context.openUrlExternally(url: String): Boolean = runCatching {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)
