package com.phaohn.spyhelper

import org.json.JSONObject

object ApiError {
    fun accountLockedMessage(httpCode: Int, body: String): String? {
        if (httpCode != 403) return null
        return try {
            val detail = JSONObject(body).opt("detail")
            when (detail) {
                is JSONObject -> {
                    if (detail.optString("code") == "account_locked") {
                        detail.optString("message").ifBlank { null }
                            ?: "Tài khoản đã bị Admin khóa."
                    } else {
                        null
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}

class AccountLockedException(val lockMessage: String) : Exception("account_locked")