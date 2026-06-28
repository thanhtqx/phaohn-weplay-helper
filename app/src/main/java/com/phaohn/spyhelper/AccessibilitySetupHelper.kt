package com.phaohn.spyhelper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object AccessibilitySetupHelper {

    fun needsRestrictedSettingsUnlock(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !SpyAccessibilityService.isEnabled(context)
    }

    fun openAccessibilityFlow(fragment: Fragment) {
        val ctx = fragment.requireContext()
        if (SpyAccessibilityService.isEnabled(ctx)) {
            fragment.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (needsRestrictedSettingsUnlock(ctx)) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.accessibility_restricted_title)
                .setMessage(R.string.accessibility_restricted_message)
                .setPositiveButton(R.string.accessibility_open_app_settings) { _, _ ->
                    openAppDetails(ctx)
                }
                .setNegativeButton(R.string.accessibility_open_a11y_settings) { _, _ ->
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
            return
        }
        fragment.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openAppDetails(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}