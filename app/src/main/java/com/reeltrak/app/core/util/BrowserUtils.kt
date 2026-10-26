package com.reeltrak.app.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Reusable utility for opening external web links in the device's default browser.
 * Uses standard ACTION_VIEW Intent with safe failure handling (no crashes if no browser is installed, no WebView).
 */
object BrowserUtils {
    private const val TAG = "BrowserUtils"

    fun openUrl(context: Context, url: String) {
        if (url.isBlank()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch external browser for URL: $url", e)
        }
    }
}
