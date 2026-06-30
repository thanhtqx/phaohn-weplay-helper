package com.phaohn.spyhelper

import android.content.Context
import android.content.Intent

object AutoPrefsNotifier {

    fun notifyChanged(context: Context) {
        val app = context.applicationContext
        app.sendBroadcast(
            Intent(SpyAccessibilityService.ACTION_AUTO_PREFS_CHANGED)
                .setPackage(app.packageName),
        )
        OverlayService.refreshAutoUi()
    }
}