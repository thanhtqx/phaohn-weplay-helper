package com.phaohn.spyhelper

import android.content.Context
import android.content.Intent

object AccountLockHandler {
    const val EXTRA_LOCK_MESSAGE = "lock_message"

    fun handle(context: Context, message: String) {
        AuthManager(context).clearSession()
        val intent = Intent(context, LoginActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_LOCK_MESSAGE, message)
        }
        context.startActivity(intent)
    }

    fun maybeHandle(context: Context?, httpCode: Int, body: String): Boolean {
        if (context == null) return false
        val message = ApiError.accountLockedMessage(httpCode, body) ?: return false
        handle(context, message)
        return true
    }
}