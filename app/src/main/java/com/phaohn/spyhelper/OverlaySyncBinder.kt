package com.phaohn.spyhelper

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlaySyncBinder(
    private val syncIcon: ImageView,
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val auth = AuthManager(context)
    private val repository = PhaoHNApp.repo(context.applicationContext as Application)

    private var syncing = false

    fun bind() {
        syncIcon.apply {
            isClickable = true
            isFocusable = false
            setColorFilter(ContextCompat.getColor(context, R.color.overlay_text_primary))
            setOnClickListener { runSync() }
        }
    }

    fun destroy() {
        scope.cancel()
    }

    private fun toast(message: String) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun toast(resId: Int) {
        toast(context.getString(resId))
    }

    private fun runSync() {
        if (syncing) return
        if (!auth.isLoggedIn()) {
            toast(R.string.overlay_sync_need_login)
            return
        }
        syncing = true
        syncIcon.isEnabled = false
        syncIcon.alpha = 0.45f
        scope.launch {
            try {
                val baseUrl = SpyPrefs.syncBaseUrl(context)
                val token = auth.getToken()
                withContext(Dispatchers.IO) {
                    repository.syncWithServer(
                        baseUrl,
                        token,
                        context,
                        isAdmin = auth.isAdmin(),
                    )
                }
                AdminNotificationHelper.pullAfterServerTouch(context, baseUrl, token)
                context.sendBroadcast(
                    Intent(SpyAccessibilityService.ACTION_PAIRS_UPDATED)
                        .setPackage(context.packageName),
                )
            } catch (e: Exception) {
                toast(context.getString(R.string.sync_fail, e.message ?: e.javaClass.simpleName))
            } finally {
                syncing = false
                syncIcon.isEnabled = true
                syncIcon.alpha = 1f
            }
        }
    }
}