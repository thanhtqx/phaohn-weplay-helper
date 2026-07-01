package com.phaohn.spyhelper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PendingSyncStore {

    private const val PREFS = "spy_pending_sync"
    private const val KEY_ITEMS = "items"

    data class Item(val civilian: String, val spy: String)

    fun enqueue(context: Context, civilian: String, spy: String) {
        val items = load(context).toMutableList()
        if (items.any { WordMatcher.isDuplicatePair(it.civilian, it.spy, civilian, spy) }) return
        items.add(Item(civilian, spy))
        save(context, items)
    }

    fun load(context: Context): List<Item> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(Item(obj.getString("c"), obj.getString("s")))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ITEMS)
            .apply()
    }

    private fun save(context: Context, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("c", item.civilian)
                    .put("s", item.spy),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, arr.toString())
            .apply()
    }
}