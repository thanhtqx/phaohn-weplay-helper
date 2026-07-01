package com.phaohn.spyhelper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class AuthUser(
    val id: Int,
    val username: String,
    val role: String,
)

class AuthManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLoggedIn(): Boolean = getToken().isNotEmpty()

    fun getToken(): String = prefs.getString(KEY_TOKEN, "").orEmpty()

    fun getUser(): AuthUser? {
        val id = prefs.getInt(KEY_USER_ID, -1)
        val name = prefs.getString(KEY_USERNAME, null) ?: return null
        val role = prefs.getString(KEY_ROLE, ROLE_USER) ?: ROLE_USER
        if (id < 0) return null
        return AuthUser(id, name, role)
    }

    fun isStaff(): Boolean {
        val role = getUser()?.role ?: return false
        return role == ROLE_ADMIN || role == ROLE_SUPERADMIN
    }

    fun isAdmin(): Boolean = isStaff()

    fun isSuperAdmin(): Boolean = getUser()?.role == ROLE_SUPERADMIN

    fun saveSession(token: String, user: AuthUser) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putInt(KEY_USER_ID, user.id)
            .putString(KEY_USERNAME, user.username)
            .putString(KEY_ROLE, user.role)
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USERNAME)
            .remove(KEY_ROLE)
            .apply()
    }

    suspend fun verifySession() = withContext(Dispatchers.IO) {
        val token = getToken()
        if (token.isEmpty()) throw AuthException("unauthorized")
        request(
            "${SpyPrefs.syncBaseUrl(context)}/api/auth/me",
            "GET",
            null,
            token,
        )
    }

    suspend fun login(username: String, password: String): AuthUser = withContext(Dispatchers.IO) {
        val base = SpyPrefs.syncBaseUrl(context)
        val json = request(
            "$base/api/auth/login",
            "POST",
            JSONObject()
                .put("username", username)
                .put("password", password)
                .toString(),
            null,
        )
        val obj = JSONObject(json)
        val userObj = obj.getJSONObject("user")
        val user = AuthUser(
            id = userObj.getInt("id"),
            username = userObj.getString("username"),
            role = userObj.getString("role"),
        )
        val token = obj.getString("token")
        withContext(Dispatchers.Main) {
            saveSession(token, user)
        }
        user
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val token = getToken()
        if (token.isNotEmpty()) {
            try {
                request(
                    "${SpyPrefs.syncBaseUrl(context)}/api/auth/logout",
                    "POST",
                    null,
                    token,
                )
            } catch (_: Exception) {
            }
        }
        withContext(Dispatchers.Main) {
            clearSession()
        }
    }

    suspend fun changePassword(oldPassword: String, newPassword: String) = withContext(Dispatchers.IO) {
        request(
            "${SpyPrefs.syncBaseUrl(context)}/api/auth/change-password",
            "POST",
            JSONObject()
                .put("old_password", oldPassword)
                .put("new_password", newPassword)
                .toString(),
            getToken(),
        )
    }

    suspend fun reportPair(
        civilian: String,
        spy: String,
        reportType: String,
        message: String,
        suggestedCivilian: String,
        suggestedSpy: String,
    ) = withContext(Dispatchers.IO) {
        request(
            "${SpyPrefs.syncBaseUrl(context)}/api/pairs/report",
            "POST",
            JSONObject()
                .put("civilian_word", civilian)
                .put("spy_word", spy)
                .put("report_type", reportType)
                .put("message", message)
                .put("suggested_civilian", suggestedCivilian)
                .put("suggested_spy", suggestedSpy)
                .toString(),
            getToken(),
        )
    }

    private fun request(
        url: String,
        method: String,
        body: String?,
        token: String?,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
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
                ApiError.accountLockedMessage(code, text)?.let { throw AccountLockedException(it) }
                throw AuthException(parseError(text, code))
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parseError(text: String, code: Int): String {
        return try {
            val obj = JSONObject(text)
            when (val detail = obj.opt("detail")) {
                is String -> detail
                else -> "HTTP $code"
            }
        } catch (_: Exception) {
            "HTTP $code"
        }
    }

    companion object {
        private const val PREFS = "spy_auth_prefs"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_ROLE = "role"
        const val ROLE_SUPERADMIN = "superadmin"
        const val ROLE_ADMIN = "admin"
        const val ROLE_USER = "user"
    }
}

class AuthException(message: String) : Exception(message)