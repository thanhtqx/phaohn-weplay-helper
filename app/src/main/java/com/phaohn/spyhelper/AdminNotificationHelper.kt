package com.phaohn.spyhelper

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AdminNotificationHelper {

    suspend fun pullAfterServerTouch(
        context: Context,
        baseUrl: String,
        token: String,
        activity: FragmentActivity? = null,
    ) {
        if (token.isEmpty()) return
        val remote = try {
            withContext(Dispatchers.IO) {
                AdminNotificationClient(baseUrl, token, context).fetchInbox()
            }
        } catch (_: Exception) {
            return
        }
        if (remote.isNotEmpty()) {
            PendingAdminNotificationStore.save(context, remote)
        }
        val pending = PendingAdminNotificationStore.load(context)
        if (pending.isEmpty()) return
        val host = activity?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (host != null) {
            withContext(Dispatchers.Main) {
                showInboxDialog(host, pending, baseUrl, token)
                PendingAdminNotificationStore.clear(context)
            }
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.admin_notif_toast_hint, pending.size),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showInboxDialog(
        activity: FragmentActivity,
        items: List<AdminInboxNotification>,
        baseUrl: String,
        token: String,
    ) {
        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_admin_notification, null)
        val titleView = content.findViewById<TextView>(R.id.notifDialogTitle)
        val listRoot = content.findViewById<android.widget.LinearLayout>(R.id.notifDialogList)
        val inflater = LayoutInflater.from(activity)
        titleView.text = if (items.size == 1) {
            activity.getString(R.string.admin_notif_title_one)
        } else {
            activity.getString(R.string.admin_notif_title_many, items.size)
        }
        items.forEach { item ->
            val card = inflater.inflate(R.layout.item_admin_notification, listRoot, false)
            card.findViewById<TextView>(R.id.notifItemTitle).text = item.title
            card.findViewById<TextView>(R.id.notifItemBody).text = item.body
            listRoot.addView(card)
        }
        val ids = items.map { it.id }
        MaterialAlertDialogBuilder(activity)
            .setView(content)
            .setCancelable(true)
            .setPositiveButton(R.string.admin_notif_dialog_ok) { _, _ ->
                ackQuietly(baseUrl, token, ids)
            }
            .setOnCancelListener {
                ackQuietly(baseUrl, token, ids)
            }
            .show()
    }

    private fun ackQuietly(baseUrl: String, token: String, ids: List<Int>) {
        Thread {
            try {
                AdminNotificationClient(baseUrl, token, null).ack(ids)
            } catch (_: Exception) {
            }
        }.start()
    }
}