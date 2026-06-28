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
    private const val KEY_ROOM_SEAT_COUNT = "room_seat_count"

    const val DEFAULT_VOTE_SEAT = 1
    const val VOTE_TAP_AT_MIN = 1
    const val VOTE_TAP_AT_MAX = 6
    const val DEFAULT_VOTE_TAP_AT = VOTE_TAP_AT_MIN

    fun isAutoReadyEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_READY, true)

    fun setAutoReadyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_READY, enabled)
            .apply()
    }

    fun isAutoSitEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SIT, true)

    fun setAutoSitEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_SIT, enabled)
            .apply()
    }

    fun isAutoVoteEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_VOTE, false)

    fun setAutoVoteEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_VOTE, enabled)
            .apply()
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
}