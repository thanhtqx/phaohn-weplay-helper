package com.phaohn.spyhelper

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class AdminInboxNotification(
    val id: Int,
    val title: String,
    val body: String,
    val createdAt: Long,
)

class AdminNotificationClient(
    private val baseUrl: String,
    private val token: String,
    private val lockContext: android.content.Context? = null,
) {
    private val root = baseUrl.trimEnd('/')

    fun fetchInbox(): List<AdminInboxNotification> {
        val json = request("GET", "$root/api/notifications/inbox")
        val arr = JSONArray(json)
        val out = ArrayList<AdminInboxNotification>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            out += AdminInboxNotification(
                id = obj.getInt("id"),
                title = obj.getString("title"),
                body = obj.getString("body"),
                createdAt = obj.optLong("created_at"),
            )
        }
        return out
    }

    fun ack(notificationIds: List<Int>): Int {
        if (notificationIds.isEmpty()) return 0
        val body = JSONObject()
        val arr = JSONArray()
        notificationIds.forEach { arr.put(it) }
        body.put("notification_ids", arr)
        val json = request("POST", "$root/api/notifications/ack", body.toString())
        return JSONObject(json).optInt("acked")
    }

    private fun request(method: String, url: String, body: String? = null): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            if (token.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                if (AccountLockHandler.maybeHandle(lockContext, code, text)) {
                    throw AccountLockedException(
                        ApiError.accountLockedMessage(code, text)
                            ?: "Tài khoản đã bị Admin khóa.",
                    )
                }
                throw SyncException("HTTP $code: $text")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}