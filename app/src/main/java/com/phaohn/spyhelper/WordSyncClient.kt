package com.phaohn.spyhelper

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class RemoteWordPair(
    val civilianWord: String,
    val spyWord: String,
)

data class RemoteSyncPushResult(
    val added: Int,
    val skipped: Int,
    val total: Int,
)

class WordSyncClient(
    private val baseUrl: String,
    private val token: String = "",
) {
    private val root = baseUrl.trimEnd('/')

    fun pullPairs(): List<RemoteWordPair> {
        val json = request("GET", "$root/api/sync/pull")
        val arr = JSONArray(json)
        val out = ArrayList<RemoteWordPair>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            out += RemoteWordPair(
                civilianWord = obj.getString("civilian_word"),
                spyWord = obj.getString("spy_word"),
            )
        }
        return out
    }

    fun pushPairs(pairs: List<WordPair>): RemoteSyncPushResult {
        val body = JSONObject()
        val arr = JSONArray()
        pairs.forEach { pair ->
            arr.put(
                JSONObject()
                    .put("civilian_word", pair.civilianWord)
                    .put("spy_word", pair.spyWord),
            )
        }
        body.put("pairs", arr)
        val json = request("POST", "$root/api/sync/push", body.toString())
        val obj = JSONObject(json)
        return RemoteSyncPushResult(
            added = obj.optInt("added"),
            skipped = obj.optInt("skipped"),
            total = obj.optInt("total"),
        )
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
                throw SyncException("HTTP $code: $text")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}

class SyncException(message: String) : Exception(message)