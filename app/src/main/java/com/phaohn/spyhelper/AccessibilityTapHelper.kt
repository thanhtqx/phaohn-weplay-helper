package com.phaohn.spyhelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object AccessibilityTapHelper {

    private const val TAP_DURATION_MS = 50L

    fun tapNode(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled) return false
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        if (tapBounds(service, node)) return true
        var parent = node.parent
        while (parent != null) {
            if (parent.isEnabled && parent.isClickable &&
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                parent.recycle()
                return true
            }
            if (tapBounds(service, parent)) {
                parent.recycle()
                return true
            }
            val next = parent.parent
            parent.recycle()
            parent = next
        }
        return false
    }

    fun tapBounds(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        return tapAt(service, rect.exactCenterX(), rect.exactCenterY())
    }

    /** Tap dải «bỏ phiếu» phía dưới avatar (vote_btn thường nằm sát đáy khung ghế). */
    fun tapBoundsBottomStrip(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val x = rect.exactCenterX()
        val y = rect.bottom - rect.height() * 0.15f
        return tapAt(service, x, y)
    }

    fun tapAt(service: AccessibilityService, x: Float, y: Float): Boolean =
        dispatchTap(service, x, y)

    private fun dispatchTap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }
}