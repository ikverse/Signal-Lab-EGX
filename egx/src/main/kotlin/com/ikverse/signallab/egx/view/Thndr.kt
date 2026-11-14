package com.ikverse.signallab.egx.view

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * "Copy amount and open Thndr" (item 31): the number of shares goes to the clipboard, then Thndr opens, for the owner to
 * pick the stock and paste. Thndr's package name is not published; the known candidates are tried, and without the app the
 * phone's browser opens Thndr's site. Nothing is ever placed by Signal Lab.
 */
object Thndr {
    val PACKAGES = listOf("com.thndr.app", "com.thndr", "app.thndr")
    const val SITE = "https://thndr.app"

    fun copyAndOpen(context: Context, amount: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Signal Lab EGX", amount))
        val launch = PACKAGES.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(SITE))
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
