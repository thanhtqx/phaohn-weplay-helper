package com.phaohn.spyhelper

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object WePlaySeatHelper {

    /** Đã ngồi ghế: có nút ready (Sẵn sàng hoặc Hủy sau khi ready). */
    fun isUserSeated(root: AccessibilityNodeInfo): Boolean = hasLobbyReadyButton(root)

    /** Có ghế trống trong phòng chờ và chưa vào ván (không liên quan vote). */
    fun canAutoSit(root: AccessibilityNodeInfo): Boolean {
        if (hasVisibleSelfWord(root)) return false
        if (WePlayIds.detectSeatLayout(root) != null) return true
        return hasEmptySeatInLobby(root)
    }

    fun hasRoomSeatUi(root: AccessibilityNodeInfo): Boolean =
        WePlayIds.detectSeatLayout(root) != null || hasEmptySeatInLobby(root)

    fun hasEmptySeatInLobby(root: AccessibilityNodeInfo): Boolean {
        val names = root.findAccessibilityNodeInfosByViewId(WePlayIds.NAME_TV)
        try {
            return names.any { node ->
                WePlayIds.isEmptySeatLabel(node.text?.toString())
            }
        } finally {
            names.forEach { it.recycle() }
        }
    }

    /** Trong phòng chờ, chưa vote (dùng cho nhánh vote / reset state). */
    fun isWaitingInRoom(root: AccessibilityNodeInfo): Boolean {
        if (!canAutoSit(root)) return false
        if (hasVoteUiReady(root)) return false
        return true
    }

    /** Đã ngồi và thấy nút Sẵn sàng. */
    fun isSeatedInLobby(root: AccessibilityNodeInfo): Boolean {
        return isWaitingInRoom(root) && hasActiveReadyPrompt(root)
    }

    fun hasVisibleSelfWord(root: AccessibilityNodeInfo): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(WePlayIds.SELF_WORD)
        try {
            for (node in nodes) {
                if (!node.isVisibleToUser) continue
                if (WordParser.parseSelfWord(node.text?.toString()) != null) return true
            }
            return false
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    fun hasLobbyReadyButton(root: AccessibilityNodeInfo): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(WePlayIds.READY_BTN)
        try {
            return nodes.any { node -> node.isVisibleToUser && node.isEnabled }
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    fun hasActiveReadyPrompt(root: AccessibilityNodeInfo): Boolean {
        val nodes = root.findAccessibilityNodeInfosByViewId(WePlayIds.READY_BTN)
        try {
            return nodes.any { node ->
                node.isVisibleToUser && node.isEnabled &&
                    node.text?.toString().orEmpty()
                        .contains(WePlayIds.READY_LABEL, ignoreCase = true)
            }
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    fun hasWePlayGameUi(root: AccessibilityNodeInfo): Boolean =
        hasRoomSeatUi(root) || hasVisibleSelfWord(root) || hasVoteUiReady(root)

    fun hasVoteUiReady(root: AccessibilityNodeInfo): Boolean {
        for (viewId in listOf(WePlayIds.VOTE_BTN, WePlayIds.VOTE_VIEW)) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            try {
                for (node in nodes) {
                    if (hasNonEmptyBounds(node)) return true
                }
            } finally {
                nodes.forEach { it.recycle() }
            }
        }
        for (num in 1..WePlayIds.SEAT_COUNT) {
            val seat = WePlayIds.obtainSeatNode(root, num) ?: continue
            try {
                if (isSeatVoteReady(seat)) return true
            } finally {
                seat.recycle()
            }
        }
        return false
    }

    fun tapSeatNumber(service: AccessibilityService, root: AccessibilityNodeInfo, seatNum: Int): Boolean {
        if (seatNum !in 1..WePlayIds.SEAT_COUNT) return false
        val seat = WePlayIds.obtainSeatNode(root, seatNum) ?: return false
        try {
            return tapVoteSeat(service, seat)
        } finally {
            seat.recycle()
        }
    }

    /** Ghế sẵn sàng bỏ phiếu: có `vote_view` / `vote_btn` (UI «bỏ phiếu»). */
    fun isSeatVoteReady(seat: AccessibilityNodeInfo): Boolean {
        if (containsVoteLabel(seat)) return true
        val voteControl = findVoteControl(seat)
        try {
            return voteControl != null
        } finally {
            voteControl?.recycle()
        }
    }

    fun tapFirstEmptySeat(service: AccessibilityService, root: AccessibilityNodeInfo): Boolean {
        for (num in 1..WePlayIds.SEAT_COUNT) {
            val seats = root.findAccessibilityNodeInfosByViewId(WePlayIds.seatId(num))
            try {
                val seat = seats.firstOrNull() ?: continue
                if (isSeatEmpty(seat) && tapEmptySeat(service, seat)) {
                    return true
                }
            } finally {
                seats.forEach { it.recycle() }
            }
        }
        val names = root.findAccessibilityNodeInfosByViewId(WePlayIds.NAME_TV)
        try {
            for (node in names) {
                val label = node.text?.toString()
                if (WePlayIds.isLockedSeatLabel(label) || !WePlayIds.isEmptySeatLabel(label)) continue
                if (tapEmptySeatNearName(service, node)) return true
            }
        } finally {
            names.forEach { it.recycle() }
        }
        return false
    }

    /** Từ nhãn «Trống» → ô ghế trống phía trên. */
    private fun tapEmptySeatNearName(service: AccessibilityService, nameNode: AccessibilityNodeInfo): Boolean {
        val seat = findSeatAncestor(nameNode)
        if (seat != null) {
            try {
                if (tapEmptySeat(service, seat)) return true
            } finally {
                seat.recycle()
            }
        }
        return tapEmptySeatSlot(service, nameNode)
    }

    /** Tap ô ghế trống (vùng tròn phía trên chữ «Trống»). */
    private fun tapEmptySeatSlot(service: AccessibilityService, nameNode: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        nameNode.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val x = rect.exactCenterX()
        val y = rect.top - rect.height() * 4f
        return AccessibilityTapHelper.tapAt(service, x, y)
    }

    private fun findVoteControl(seat: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (viewId in listOf(WePlayIds.VOTE_BTN, WePlayIds.VOTE_VIEW)) {
            val node = findDescendantById(seat, viewId) ?: continue
            if (!node.isEnabled || !hasNonEmptyBounds(node)) {
                node.recycle()
                continue
            }
            return node
        }
        return null
    }

    private fun hasNonEmptyBounds(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.width() > 0 && rect.height() > 0
    }

    private fun containsVoteLabel(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString().orEmpty()
        if (text.contains(WePlayIds.VOTE_LABEL, ignoreCase = true)) return true
        val desc = node.contentDescription?.toString().orEmpty()
        if (desc.contains(WePlayIds.VOTE_LABEL, ignoreCase = true)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = containsVoteLabel(child)
            child.recycle()
            if (found) return true
        }
        return false
    }

    /** Ngồi ghế trống: tap ô ghế (vùng clickable / phía trên «Trống»), không tap giữa khung ghế. */
    private fun tapEmptySeat(service: AccessibilityService, seat: AccessibilityNodeInfo): Boolean {
        val heads = mutableListOf<AccessibilityNodeInfo>()
        collectDescendantsById(seat, WePlayIds.HEAD_IV, heads)
        try {
            for (head in heads.filter { it.isClickable }) {
                if (AccessibilityTapHelper.tapBounds(service, head)) return true
            }
        } finally {
            heads.forEach { it.recycle() }
        }
        val nameNode = findDescendantById(seat, WePlayIds.NAME_TV)
        try {
            if (nameNode != null && WePlayIds.isEmptySeatLabel(nameNode.text?.toString())) {
                return tapEmptySeatSlot(service, nameNode)
            }
        } finally {
            nameNode?.recycle()
        }
        return false
    }

    /** Bỏ phiếu đột tử: tap vote_btn (dải dưới ghế) → vote_view → khung ghế. */
    private fun tapVoteSeat(service: AccessibilityService, seat: AccessibilityNodeInfo): Boolean {
        val voteBtn = findVoteControl(seat)
        try {
            if (voteBtn != null) {
                if (AccessibilityTapHelper.tapNode(service, voteBtn)) return true
                if (AccessibilityTapHelper.tapBoundsBottomStrip(service, voteBtn)) return true
                if (AccessibilityTapHelper.tapBounds(service, voteBtn)) return true
            }
        } finally {
            voteBtn?.recycle()
        }
        val voteView = findDescendantById(seat, WePlayIds.VOTE_VIEW)
        try {
            if (voteView != null) {
                if (AccessibilityTapHelper.tapNode(service, voteView)) return true
                if (AccessibilityTapHelper.tapBoundsBottomStrip(service, voteView)) return true
                if (AccessibilityTapHelper.tapBounds(service, voteView)) return true
            }
        } finally {
            voteView?.recycle()
        }
        if (seat.isClickable && seat.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        return AccessibilityTapHelper.tapBoundsBottomStrip(service, seat)
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
        if (hasReadyMarker(seat)) return false
        val nameNode = findDescendantById(seat, WePlayIds.NAME_TV)
        try {
            val label = nameNode?.text?.toString()
            if (WePlayIds.isLockedSeatLabel(label)) return false
            return WePlayIds.isEmptySeatLabel(label)
        } finally {
            nameNode?.recycle()
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

    private fun collectDescendantsById(
        node: AccessibilityNodeInfo,
        viewId: String,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node.viewIdResourceName == viewId) {
            out.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectDescendantsById(child, viewId, out)
            child.recycle()
        }
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