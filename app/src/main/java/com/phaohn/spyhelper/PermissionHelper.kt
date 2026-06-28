package com.phaohn.spyhelper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionHelper {
    data class Status(
        val accessibility: Boolean,
        val overlay: Boolean,
        val notification: Boolean,
    ) {
        val allGranted: Boolean get() = accessibility && overlay && notification
    }

    fun check(context: Context): Status = Status(
        accessibility = SpyAccessibilityService.isEnabled(context),
        overlay = Settings.canDrawOverlays(context),
        notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        },
    )
}