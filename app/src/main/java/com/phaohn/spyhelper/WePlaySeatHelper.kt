package com.phaohn.spyhelper

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

object WePlaySeatHelper {

    private val SAT_DOWN_HINTS = listOf(
        "đã ngồi",
        "ngồi vào",
        "ngồi xuống",
        "đã vào chỗ",
        "đã vào ghế",
    )

    fun isUserSeated(root: AccessibilityNodeInfo): Boolean {
        if (hasSatDownMessage(root)) return true
        return hasReadyControl(root)
    }

    fun tapSeatNumber(service: AccessibilityService, root: AccessibilityNodeInfo, seatNum: Int): Boolean {
        if (seatNum !in 1..WePlayIds.SEAT_COUNT) return false
        val layout = WePlayIds.detectSeatLayout(root) ?: return false
        if (seatNum > layout.count) return false
        val seats = root.findAccessibilityNodeInfosByViewId(layout.seatId(seatNum))
        try {
            val seat = seats.firstOrNull() ?: return false
            if (isSeatLocked(seat)) return false
            return tapSeat(service, seat)
        } finally {
            seats.forEach { it.recycle() }
        }
    }

    fun tapFirstEmptySeat(service: AccessibilityService, root: AccessibilityNodeInfo): Boolean {
        val layout = WePlayIds.detectSeatLayout(root)
        if (layout != null) {
            for (num in 1..layout.count) {
                val seats = root.findAccessibilityNodeInfosByViewId(layout.seatId(num))
                try {
                    val seat = seats.firstOrNull() ?: continue
                    if (isSeatEmpty(seat) && tapSeat(service, seat)) return true
                } finally {
                    seats.forEach { it.recycle() }
                }
            }
            return false
        }
        val names = root.findAccessibilityNodeInfosByViewId(WePlayIds.NAME_TV)
        try {
            for (node in names) {
                val label = node.text?.toString()
                if (WePlayIds.isLockedSeatLabel(label) || !WePlayIds.isEmptySeatLabel(label)) continue
                val seat = findSeatAncestor(node) ?: continue
                try {
                    if (tapSeat(service, seat)) return true
                } finally {
                    seat.recycle()
                }
            }
        } finally {
            names.forEach { it.recycle() }
        }
        return false
    }

    private fun findSeatAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent
        while (current != null) {
            val id = current.viewIdResourceName.orEmpty()
            if (WePlayIds.isSeatResourceId(id)) {
                return AccessibilityNodeInfo.obtain(current)
            }
            val next = current.parent
            current.recycle()
            current = next
        }
        return null
    }

    private fun isSeatEmpty(seat: AccessibilityNodeInfo): Boolean {
        if (hasReadyMarker(seat) || isSeatLocked(seat)) return false
        val nameNode = findDescendantById(seat, WePlayIds.NAME_TV)
        try {
            return WePlayIds.isEmptySeatLabel(nameNode?.text?.toString())
        } finally {
            nameNode?.recycle()
        }
    }

    private fun isSeatLocked(seat: AccessibilityNodeInfo): Boolean {
        val nameNode = findDescendantById(seat, WePlayIds.NAME_TV)
        try {
            if (WePlayIds.isLockedSeatLabel(nameNode?.text?.toString())) return true
        } finally {
            nameNode?.recycle()
        }
        return hasLobbyLockMarker(seat)
    }

    /** Ghế khóa trong phòng lock: có `vote_view` + tên `--`. */
    private fun hasLobbyLockMarker(seat: AccessibilityNodeInfo): Boolean {
        val voteMarker = findDescendantById(seat, WePlayIds.VOTE_VIEW) ?: return false
        try {
            val nameNode = findDescendantById(seat, WePlayIds.NAME_TV)
            try {
                return WePlayIds.isLockedSeatLabel(nameNode?.text?.toString())
            } finally {
                nameNode?.recycle()
            }
        } finally {
            voteMarker.recycle()
        }
    }

    private fun hasReadyMarker(seat: AccessibilityNodeInfo): Boolean {
        val marker = findDescendantById(seat, WePlayIds.READY_IMV)
        try {
            return marker != null
        } finally {
            marker?.recycle()
        }
    }

    private fun hasSatDownMessage(root: AccessibilityNodeInfo): Boolean {
        for (viewId in listOf(WePlayIds.ROOM_SYS_MSG, WePlayIds.ROOM_TEXT_MSG)) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            try {
                for (node in nodes) {
                    val text = node.text?.toString()?.lowercase().orEmpty()
                    if (SAT_DOWN_HINTS.any { text.contains(it) }) return true
                }
            } finally {
                nodes.forEach { it.recycle() }
            }
        }
        return false
    }

    private fun hasReadyControl(root: AccessibilityNodeInfo): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(WePlayIds.READY_BTN)
        try {
            val ready = nodes.firstOrNull() ?: return false
            return ready.isVisibleToUser && ready.isEnabled
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    private fun tapSeat(service: AccessibilityService, seat: AccessibilityNodeInfo): Boolean {
        for (viewId in listOf(WePlayIds.USER_FACE_VIEW, WePlayIds.HEAD_IV)) {
            val target = findDescendantById(seat, viewId)
            if (target != null) {
                try {
                    if (AccessibilityTapHelper.tapNode(service, target)) return true
                } finally {
                    target.recycle()
                }
            }
        }
        return AccessibilityTapHelper.tapNode(service, seat)
    }

    private fun findDescendantById(node: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        if (node.viewIdResourceName == viewId) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findDescendantById(child, viewId)
            child.recycle()
            if (found != null) return found
        }
        return null
    }
}