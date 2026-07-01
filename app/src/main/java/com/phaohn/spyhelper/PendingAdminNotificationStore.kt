package com.phaohn.spyhelper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PendingAdminNotificationStore {
    private const val PREFS = "spy_helper_prefs"
    private const val KEY = "pending_admin_notifications"

    fun save(context: Context, items: List<AdminInboxNotification>) {
        if (items.isEmpty()) return
        val existing = load(context).associateBy { it.id }.toMutableMap()
        items.forEach { existing[it.id] = it }
        val arr = JSONArray()
        existing.values.sortedBy { it.createdAt }.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("body", item.body)
                    .put("created_at", item.createdAt),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, arr.toString())
            .apply()
    }

    fun load(context: Context): List<AdminInboxNotification> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.trim()
            .orEmpty()
        if (raw.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        AdminInboxNotification(
                            id = obj.getInt("id"),
                            title = obj.getString("title"),
                            body = obj.getString("body"),
                            createdAt = obj.optLong("created_at"),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }
}