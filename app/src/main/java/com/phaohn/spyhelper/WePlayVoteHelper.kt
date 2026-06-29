package com.phaohn.spyhelper

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object WePlayVoteHelper {

    private val TIMER_PATTERN = Regex("""(\d+)s""")
    const val VOTE_TIMER_MAX_SEC = 20

    fun readTimerSeconds(root: AccessibilityNodeInfo): Int? {
        val nodes = root.findAccessibilityNodeInfosByViewId(WePlayIds.TIMER_TV)
        try {
            var best: Int? = null
            for (node in nodes) {
                val sec = parseTimerSeconds(node.text?.toString()) ?: continue
                if (best == null || sec < best) best = sec
            }
            return best
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

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
        val raw = timerText?.trim().orEmpty()
        if (raw.isEmpty() || raw == "--") return null
        val compact = raw.replace(" ", "")
        TIMER_PATTERN.find(compact)?.groupValues?.get(1)?.toIntOrNull()?.let { sec ->
            if (sec in 0..VOTE_TIMER_MAX_SEC) return sec
        }
        if (compact.endsWith("s", ignoreCase = true)) {
            compact.dropLast(1).toIntOrNull()?.takeIf { it in 0..VOTE_TIMER_MAX_SEC }?.let { return it }
        }
        return compact.toIntOrNull()?.takeIf { it in 0..VOTE_TIMER_MAX_SEC }
    }
}