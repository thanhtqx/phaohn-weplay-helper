package com.phaohn.spyhelper

import android.view.accessibility.AccessibilityNodeInfo

object WePlayIds {
    const val PACKAGE = "com.wejoy.weplay"
    const val SELF_WORD = "$PACKAGE:id/self_word_tv"
    const val CIVILIAN = "$PACKAGE:id/spy_dialog_citizen_tv"
    const val SPY = "$PACKAGE:id/spy_dialog_spy_tv"
    const val READY_BTN = "$PACKAGE:id/ready_btn"
    const val READY_IMV = "$PACKAGE:id/ready_imv"
    const val NAME_TV = "$PACKAGE:id/name_tv"
    const val USER_FACE_VIEW = "$PACKAGE:id/user_face_view"
    const val HEAD_IV = "$PACKAGE:id/head_iv"
    const val ROOM_SYS_MSG = "$PACKAGE:id/room_sys_msg_content_tx"
    const val ROOM_TEXT_MSG = "$PACKAGE:id/room_text_msg_content_tx"
    const val TIMER_TV = "$PACKAGE:id/timer_tv"
    const val VOTE_POPUP = "$PACKAGE:id/spy_dialog_center_tip_tv"
    const val JUDGE_MSG = "$PACKAGE:id/room_text_msg_content_tx"
    const val VOTE_START_LABEL = "Bắt đầu bỏ phiếu"
    const val READY_LABEL = "sẵn sàng"
    const val EMPTY_SEAT_LABEL = "trống"
    const val LOCKED_SEAT_LABEL = "--"
    const val VOTE_VIEW = "$PACKAGE:id/vote_view"
    const val ROOM_LOCK_IV = "$PACKAGE:id/room_lock_iv"
    const val SEAT_COUNT = 8

    data class SeatLayout(val prefix: String, val count: Int) {
        fun seatId(number: Int): String = "$PACKAGE:id/$prefix$number"
    }

    private val SEAT_RESOURCE_PATTERN = Regex("""^$PACKAGE:id/([a-z]+_seat)_(\d+)$""")

    fun detectSeatLayout(root: AccessibilityNodeInfo): SeatLayout? {
        val counts = mutableMapOf<String, Int>()
        collectSeatCounts(root, counts)
        if (counts.isEmpty()) return null
        val (prefix, count) = counts.maxBy { it.value }.toPair()
        return SeatLayout(prefix, count)
    }

    fun isSeatResourceId(viewId: String?): Boolean {
        return viewId != null && SEAT_RESOURCE_PATTERN.matches(viewId)
    }

    fun isLockedSeatLabel(label: String?): Boolean {
        val text = label?.trim().orEmpty()
        return text == LOCKED_SEAT_LABEL || text == "-" || text == "—"
    }

    fun isEmptySeatLabel(label: String?): Boolean {
        val text = label?.trim().orEmpty()
        return text.isEmpty() || text.equals(EMPTY_SEAT_LABEL, ignoreCase = true)
    }

    private fun collectSeatCounts(node: AccessibilityNodeInfo, counts: MutableMap<String, Int>) {
        val id = node.viewIdResourceName
        if (id != null) {
            val match = SEAT_RESOURCE_PATTERN.matchEntire(id)
            if (match != null) {
                val prefix = "${match.groupValues[1]}_"
                val num = match.groupValues[2].toIntOrNull() ?: 0
                if (num > 0) {
                    counts[prefix] = maxOf(counts.getOrDefault(prefix, 0), num)
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectSeatCounts(child, counts)
            child.recycle()
        }
    }

    fun seatId(number: Int): String = SeatLayout("eight_seat_", 8).seatId(number)
}

object WordParser {
    private val SELF_PREFIX = Regex("""(?i)từ\s*khóa\s*của\s*tôi\s*[:：]\s*""")

    fun parseSelfWord(raw: String?): String? {
        if (raw == null || raw.isEmpty()) return null
        return raw.replace(SELF_PREFIX, "")
    }
}