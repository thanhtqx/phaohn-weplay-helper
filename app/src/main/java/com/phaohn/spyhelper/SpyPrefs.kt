package com.phaohn.spyhelper

import android.content.Context

object SpyPrefs {
    private const val PREFS = "spy_helper_prefs"
    private const val KEY_AUTO_READY = "auto_ready"
    private const val KEY_AUTO_SIT = "auto_sit"
    private const val KEY_AUTO_VOTE = "auto_vote"
    private const val KEY_VOTE_SEAT = "vote_seat"
    private const val KEY_VOTE_TAP_AT = "vote_tap_at"
    private const val KEY_VOTE_SEAT_CHOSEN = "vote_seat_chosen"
    private const val KEY_VOTE_SETTINGS_LOCKED = "vote_settings_locked"
    private const val KEY_VOTE_SETTINGS_UNLOCKED_LEGACY = "vote_settings_unlocked"
    private const val KEY_ROOM_SEAT_COUNT = "room_seat_count"
    private const val KEY_SYNC_BASE_URL = "sync_base_url"
    private const val KEY_LAST_PUSH_USERNAME = "last_push_username"

    const val DEFAULT_SYNC_BASE_URL = "http://152.42.223.79"
    /** Khóa đẩy từ tự bắt lên server khi chưa đăng nhập (khớp PHAOHN_OPEN_PUSH_KEY trên VPS). */
    const val OPEN_PUSH_KEY = "PhaoHN@Capture2026"

    const val DEFAULT_VOTE_SEAT = 1
    const val VOTE_TAP_AT_MIN = 0
    const val VOTE_TAP_AT_MAX = 11
    const val DEFAULT_VOTE_TAP_AT = 1

    fun isAutoReadyEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_READY, false)

    fun setAutoReadyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_READY, enabled)
            .commit()
    }

    fun isAutoSitEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SIT, false)

    fun setAutoSitEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_SIT, enabled)
            .commit()
    }

    fun isAutoVoteEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_VOTE, false)

    fun setAutoVoteEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_VOTE, enabled)
            .commit()
    }

    fun voteTargetSeat(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VOTE_SEAT, DEFAULT_VOTE_SEAT)
            .coerceIn(1, WePlayIds.SEAT_COUNT)

    fun setVoteTargetSeat(context: Context, seat: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VOTE_SEAT, seat.coerceIn(1, WePlayIds.SEAT_COUNT))
            .apply()
    }

    fun isVoteSeatChosen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_VOTE_SEAT_CHOSEN, false)

    /** Sau cài mới: mặc định ghế 1 để Đột tử bật được ngay (vẫn đổi ghế trước khi chơi). */
    fun ensureDefaultVoteSeat(context: Context) {
        if (isVoteSeatChosen(context)) return
        setVoteTargetSeat(context, DEFAULT_VOTE_SEAT)
        setVoteSeatChosen(context, true)
    }

    fun setVoteSeatChosen(context: Context, chosen: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VOTE_SEAT_CHOSEN, chosen)
            .apply()
    }

    fun voteTapAtSeconds(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_VOTE_TAP_AT, DEFAULT_VOTE_TAP_AT)
            .coerceIn(VOTE_TAP_AT_MIN, VOTE_TAP_AT_MAX)

    fun setVoteTapAtSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VOTE_TAP_AT, seconds.coerceIn(VOTE_TAP_AT_MIN, VOTE_TAP_AT_MAX))
            .apply()
    }

    /** Đã khoá ghế + giây — Đột tử chỉ chạy khi khoá bật. */
    fun isVoteSettingsLocked(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_VOTE_SETTINGS_LOCKED)) {
            return prefs.getBoolean(KEY_VOTE_SETTINGS_LOCKED, false)
        }
        if (prefs.contains(KEY_VOTE_SETTINGS_UNLOCKED_LEGACY)) {
            return !prefs.getBoolean(KEY_VOTE_SETTINGS_UNLOCKED_LEGACY, false)
        }
        return false
    }

    fun setVoteSettingsLocked(context: Context, locked: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_VOTE_SETTINGS_LOCKED, locked)
            .remove(KEY_VOTE_SETTINGS_UNLOCKED_LEGACY)
            .commit()
    }

    /** Khoá chọn = bật Đột tử; mở chọn = tắt. */
    fun setVoteLockWithAutoVote(context: Context, locked: Boolean) {
        setVoteSettingsLocked(context, locked)
        setAutoVoteEnabled(context, locked)
    }

    fun roomSeatCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_ROOM_SEAT_COUNT, 0)
            .coerceIn(0, WePlayIds.SEAT_COUNT)

    fun setRoomSeatCount(context: Context, count: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ROOM_SEAT_COUNT, count.coerceIn(0, WePlayIds.SEAT_COUNT))
            .apply()
    }

    fun syncBaseUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SYNC_BASE_URL, DEFAULT_SYNC_BASE_URL)
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()
            .ifEmpty { DEFAULT_SYNC_BASE_URL }

    fun setSyncBaseUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SYNC_BASE_URL, url.trim().trimEnd('/'))
            .apply()
    }

    /** Tài khoản đích khi đẩy từ tự bắt lúc chưa đăng nhập (lần login gần nhất). */
    fun lastPushUsername(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PUSH_USERNAME, null)
            ?.trim()
            .orEmpty()

    fun setLastPushUsername(context: Context, username: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PUSH_USERNAME, username.trim())
            .apply()
    }
}