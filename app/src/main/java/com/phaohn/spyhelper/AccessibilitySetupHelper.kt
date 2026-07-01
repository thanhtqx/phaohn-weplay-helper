package com.phaohn.spyhelper

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.fragment.app.Fragment

object AccessibilitySetupHelper {

    private const val OP_ACCESS_RESTRICTED_SETTINGS = "android:access_restricted_settings"

    fun isRestrictedSettingsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        if (OemSettingsNavigator.isPlayStoreInstall(context)) return true
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return try {
            @Suppress("DEPRECATION")
            val mode = appOps.unsafeCheckOpNoThrow(
                OP_ACCESS_RESTRICTED_SETTINGS,
                Process.myUid(),
                context.packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    fun needsRestrictedSettingsUnlock(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !SpyAccessibilityService.isEnabled(context) &&
            !OemSettingsNavigator.isPlayStoreInstall(context) &&
            !isRestrictedSettingsAllowed(context)
    }

    fun openAccessibilityFlow(fragment: Fragment) {
        val ctx = fragment.requireContext()
        if (SpyAccessibilityService.isEnabled(ctx)) {
            OemSettingsNavigator.openAccessibilitySettings(ctx)
            return
        }
        if (BuildConfig.A11Y_DIRECT) {
            openAccessibilityServiceDetails(ctx)
            return
        }
        fragment.startActivity(Intent(ctx, AccessibilityOnboardingActivity::class.java))
    }

    fun openAccessibilityServiceDetails(context: Context) {
        if (!OemSettingsNavigator.openAccessibilityServiceDetails(context)) {
            OemSettingsNavigator.openAccessibilitySettings(context)
        }
    }

    fun openRestrictedSettings(fragment: Fragment) {
        val ctx = fragment.requireContext()
        if (!OemSettingsNavigator.openRestrictedSettings(ctx)) {
            OemSettingsNavigator.openAppDetails(ctx)
        }
    }

    fun openAppDetails(fragment: Fragment) {
        OemSettingsNavigator.openAppDetails(fragment.requireContext())
    }
}