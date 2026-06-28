package com.phaohn.spyhelper

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object WePlayVoteHelper {

    private val TIMER_PATTERN = Regex("""(\d+)s""")
    const val VOTE_TIMER_MAX_SEC = 20

    fun readTimerSeconds(root: AccessibilityNodeInfo): Int? =
        parseTimerSeconds(textById(root, WePlayIds.TIMER_TV))

    /**
     * Đang bỏ phiếu: có «bỏ phiếu» trên avatar VÀ timer đếm 0–20s.
     * Tránh false positive lúc thảo luận (vote_btn có sẵn nhưng timer = «--»).
     */
    fun isVotePhaseActive(root: AccessibilityNodeInfo): Boolean {
        if (!WePlaySeatHelper.hasVoteUiReady(root)) return false
        val timerSec = readTimerSeconds(root) ?: return false
        return timerSec in 0..VOTE_TIMER_MAX_SEC
    }

    /** Phiên bỏ phiếu kết thúc: UI vote biến mất. */
    fun isVoteFinished(root: AccessibilityNodeInfo): Boolean =
        !WePlaySeatHelper.hasVoteUiReady(root)

    fun latestJudgeLine(root: AccessibilityNodeInfo): String {
        val nodes = root.findAccessibilityNodeInfosByViewId(WePlayIds.JUDGE_MSG)
        try {
            var best = ""
            var bestBottom = -1
            for (node in nodes) {
                val text = node.text?.toString().orEmpty()
                if (!text.contains("Thẩm phán", ignoreCase = true)) continue
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.bottom >= bestBottom) {
                    bestBottom = rect.bottom
                    best = text.replace("无效  ", "").trim()
                }
            }
            return best
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    fun parseTimerSeconds(timerText: String?): Int? {
        val match = TIMER_PATTERN.find(timerText.orEmpty()) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun textById(root: AccessibilityNodeInfo, viewId: String): String? {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        try {
            return nodes.firstOrNull()?.text?.toString()
        } finally {
            nodes.forEach { it.recycle() }
        }
    }
}