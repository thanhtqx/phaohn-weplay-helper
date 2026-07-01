package com.phaohn.spyhelper

import android.content.res.Resources
import android.util.DisplayMetrics
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Kích thước bubble/menu theo % màn hình — ổn từ máy nhỏ (~4.7") đến máy lớn (~6.8").
 * dp vẫn scale theo mật độ; thêm clamp theo width/height thật để không tràn viền.
 */
object OverlayMetrics {

    data class Size(
        val menuWidthPx: Int,
        val panelMaxHeightPx: Int,
        val fabSizePx: Int,
    )

    fun resolve(resources: Resources): Size {
        val metrics = resources.displayMetrics
        val density = metrics.density
        fun dp(value: Float): Int = (value * density).roundToInt()

        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val menuWidth = (screenW * MENU_WIDTH_RATIO).roundToInt()
            .coerceIn(dp(MENU_WIDTH_MIN_DP), min((screenW * MENU_WIDTH_MAX_RATIO).roundToInt(), dp(MENU_WIDTH_MAX_DP)))

        val panelHeight = (screenH * PANEL_HEIGHT_RATIO).roundToInt()
            .coerceIn(dp(PANEL_HEIGHT_MIN_DP), min((screenH * PANEL_HEIGHT_MAX_RATIO).roundToInt(), dp(PANEL_HEIGHT_MAX_DP)))

        val fabSize = dp(FAB_SIZE_DP).coerceIn(dp(40f), dp(48f))

        return Size(menuWidth, panelHeight, fabSize)
    }

    /** Mô tả nhanh cho debug / log */
    fun screenBucket(metrics: DisplayMetrics): String {
        val wDp = (metrics.widthPixels / metrics.density).roundToInt()
        return when {
            wDp < 340 -> "compact"
            wDp < 400 -> "normal"
            else -> "large"
        }
    }

    private const val MENU_WIDTH_RATIO = 0.68f
    private const val MENU_WIDTH_MAX_RATIO = 0.82f
    private const val MENU_WIDTH_MIN_DP = 220f
    private const val MENU_WIDTH_MAX_DP = 300f

    private const val PANEL_HEIGHT_RATIO = 0.48f
    private const val PANEL_HEIGHT_MAX_RATIO = 0.56f
    private const val PANEL_HEIGHT_MIN_DP = 260f
    private const val PANEL_HEIGHT_MAX_DP = 420f

    private const val FAB_SIZE_DP = 44f
}