package com.phaohn.spyhelper

import android.view.accessibility.AccessibilityNodeInfo

object WePlayVoteHelper {

    private val TIMER_PATTERN = Regex("""(\d+)s""")

    fun isVotingPhase(root: AccessibilityNodeInfo, judgeLine: String): Boolean {
        val popup = textById(root, WePlayIds.VOTE_POPUP)
        if (popup.equals(WePlayIds.VOTE_START_LABEL, ignoreCase = true)) return true
        val judge = judgeLine.lowercase()
        if (judge.contains("bắt đầu bỏ phiếu")) {
            return !judge.contains("phiên bỏ phiếu kết thúc")
        }
        return false
    }

    fun voteEnded(judgeLine: String): Boolean =
        judgeLine.contains("Phiên bỏ phiếu kết thúc", ignoreCase = true)

    fun latestJudgeLine(root: AccessibilityNodeInfo): String {
        val nodes = root.findAccessibilityNodeInfosByViewId(WePlayIds.JUDGE_MSG)
        try {
            var last = ""
            for (node in nodes) {
                val text = node.text?.toString().orEmpty()
                if (text.contains("Thẩm phán", ignoreCase = true)) {
                    last = text.replace("无效  ", "").trim()
                }
            }
            return last
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