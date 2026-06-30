package com.phaohn.spyhelper

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.view.isVisible
import kotlin.math.abs

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayWindowRoot: View? = null
    private var overlayContent: View? = null
    private var scrimView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var chipView: View? = null
    private var menuView: View? = null
    private var fabBadgeView: TextView? = null
    private var myWordView: TextView? = null
    private var otherWordView: TextView? = null

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var posStartX = 0
    private var posStartY = 0
    private var dragMoved = false
    private var isDragging = false
    private var menuOpen = false
    private var lastOtherCount = 0
    private var isHiding = false
    private var posX = DEFAULT_POS_X
    private var posY = DEFAULT_POS_Y

    private var autoBinder: OverlayAutoBinder? = null
    private var tabBinder: OverlayTabBinder? = null
    private var syncBinder: OverlaySyncBinder? = null

    private val boundsRunnable = Runnable { clampPositionIfNeeded(persist = false) }

    private var dragLayoutX = Int.MIN_VALUE
    private var dragLayoutY = Int.MIN_VALUE
    private var dragLayoutPending = false
    private val dragLayoutRunnable = Runnable {
        dragLayoutPending = false
        if (!isDragging || menuOpen) return@Runnable
        val wm = windowManager ?: return@Runnable
        val params = layoutParams ?: return@Runnable
        val root = overlayWindowRoot ?: return@Runnable
        val x = dragLayoutX
        val y = dragLayoutY
        if (params.x == x && params.y == y) return@Runnable
        params.x = x
        params.y = y
        try {
            wm.updateViewLayout(root, params)
        } catch (_: Exception) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideOverlay()
            ACTION_UPDATE -> {
                val my = intent.getStringExtra(EXTRA_MY_WORD)
                val myRole = WordRole.fromName(intent.getStringExtra(EXTRA_MY_ROLE))
                val others = RoleTextFormatter.decodeOthers(intent.getStringExtra(EXTRA_OTHERS_ROLES))
                val plain = intent.getStringExtra(EXTRA_PLAIN_MESSAGE)
                val animate = intent.getBooleanExtra(EXTRA_ANIMATE, true)
                updateOverlay(my, myRole, others, plain, animate)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        hideScrim(animate = false, immediate = true)
        overlayWindowRoot?.let { detachOverlayView(it) } ?: run { isHiding = false }
        super.onDestroy()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun baseOverlayFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun showOverlay() {
        if (overlayWindowRoot != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = overlayPrefs()
        posX = prefs.getInt(PREF_POS_X, DEFAULT_POS_X)
        posY = prefs.getInt(PREF_POS_Y, DEFAULT_POS_Y)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            baseOverlayFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posX
            y = posY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val root = LayoutInflater.from(applicationContext).inflate(R.layout.overlay_spy_word, null)
        overlayWindowRoot = root
        overlayContent = root.findViewById(R.id.overlayRoot)
        scrimView = root.findViewById(R.id.overlayScrim)
        isHiding = false
        menuOpen = false

        chipView = root.findViewById(R.id.overlayChip)
        menuView = root.findViewById(R.id.overlayMenu)
        fabBadgeView = root.findViewById(R.id.overlayFabBadge)
        myWordView = root.findViewById(R.id.overlayMyWord)
        otherWordView = root.findViewById(R.id.overlayOtherWord)

        scrimView?.setOnClickListener { setMenuOpen(false, animate = false) }

        setupChipTouch()
        val menu = menuView ?: root
        val onBounds: () -> Unit = { scheduleBoundsSync() }
        syncBinder = OverlaySyncBinder(
            menu.findViewById(R.id.overlaySyncIcon),
            applicationContext,
        ).also { it.bind() }
        tabBinder = OverlayTabBinder(menu, applicationContext, onBounds).also { it.bind() }
        autoBinder = OverlayAutoBinder(
            menu.findViewById(R.id.overlayAutoContent),
            applicationContext,
            onBounds,
        ).also { it.bind() }
        setMenuOpen(false, animate = false)
        applyResponsiveSize()

        overlayContent?.apply {
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
        }

        windowManager?.addView(root, layoutParams)
        root.post {
            applyWindowLayout()
            clampPositionIfNeeded(persist = false)
            ensureVisible(animateIn = true)
        }
    }

    private fun ensureVisible(animateIn: Boolean = false) {
        val content = overlayContent ?: return
        content.animate().cancel()
        if (animateIn && content.alpha < 0.5f) {
            content.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            content.alpha = 1f
            content.scaleX = 1f
            content.scaleY = 1f
        }
        overlayWindowRoot?.visibility = View.VISIBLE
    }

    fun isBubbleAttached(): Boolean = overlayWindowRoot != null && !isHiding

    private fun setupChipTouch() {
        chipView?.apply {
            isClickable = true
            isFocusable = false
            isLongClickable = false
            setOnTouchListener { _, event -> onChipTouch(event) }
        }
    }

    private fun updateFabState(open: Boolean) {
        chipView?.isSelected = open
        chipView?.refreshDrawableState()
    }

    private fun onChipTouch(event: MotionEvent): Boolean {
        if (overlayContent == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelHideAnimation()
                dragStartX = event.rawX
                dragStartY = event.rawY
                posStartX = posX
                posStartY = posY
                dragOffsetX = 0f
                dragOffsetY = 0f
                dragMoved = false
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartX
                val dy = event.rawY - dragStartY
                if (!dragMoved && (abs(dx) > DRAG_SLOP || abs(dy) > DRAG_SLOP)) {
                    dragMoved = true
                    isDragging = true
                    overlayWindowRoot?.removeCallbacks(boundsRunnable)
                    if (isMenuShowing()) setMenuOpen(false, animate = false)
                }
                if (dragMoved) {
                    dragOffsetX = dx
                    dragOffsetY = dy
                    applyDragVisual()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMoved) {
                    cancelDragLayout()
                    posX = posStartX + dragOffsetX.toInt()
                    posY = posStartY + dragOffsetY.toInt()
                    dragOffsetX = 0f
                    dragOffsetY = 0f
                    isDragging = false
                    applyPosition()
                    clampPositionIfNeeded(persist = true)
                    ensureVisible()
                } else {
                    toggleMenu()
                }
                return true
            }
        }
        return false
    }

    /** Menu đóng: dịch cửa sổ (1 lần/vsync). Menu mở: translation trên full màn. */
    private fun applyDragVisual() {
        val content = overlayContent ?: return
        val dragX = posStartX + dragOffsetX.toInt()
        val dragY = posStartY + dragOffsetY.toInt()

        if (menuOpen) {
            content.translationX = dragX.toFloat()
            content.translationY = dragY.toFloat()
        } else {
            content.translationX = 0f
            content.translationY = 0f
            scheduleDragLayout(dragX, dragY)
        }
    }

    private fun scheduleDragLayout(x: Int, y: Int) {
        dragLayoutX = x
        dragLayoutY = y
        if (dragLayoutPending) return
        dragLayoutPending = true
        overlayWindowRoot?.postOnAnimation(dragLayoutRunnable)
    }

    private fun cancelDragLayout() {
        overlayWindowRoot?.removeCallbacks(dragLayoutRunnable)
        dragLayoutPending = false
    }

    private fun isMenuShowing(): Boolean = menuOpen || menuView?.isVisible == true

    private fun toggleMenu() {
        val opening = !isMenuShowing()
        setMenuOpen(opening, animate = opening)
    }

    private fun setMenuOpen(open: Boolean, animate: Boolean) {
        val menu = menuView ?: return
        menu.animate().cancel()

        if (open) {
            if (isMenuShowing()) return
            menuOpen = true
            applyWindowLayout()
            updateFabState(true)
            tabBinder?.showWords()
            autoBinder?.refreshFromPrefs()
            showScrim(animate)
            menu.isVisible = true
            if (animate) {
                menu.alpha = 0f
                menu.animate()
                    .alpha(1f)
                    .setDuration(160)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                menu.alpha = 1f
            }
        } else {
            if (!isMenuShowing()) return
            hideScrim(animate = false)
            updateFabState(false)
            menu.animate().cancel()
            menu.isVisible = false
            menu.alpha = 1f
            menuOpen = false
            overlayContent?.translationX = 0f
            overlayContent?.translationY = 0f
            applyWindowLayout()
        }
    }

    private fun showScrim(animate: Boolean) {
        val scrim = scrimView ?: return
        scrim.animate().cancel()
        scrim.isVisible = true
        if (animate) {
            scrim.alpha = 0f
            scrim.animate()
                .alpha(1f)
                .setDuration(160)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            scrim.alpha = 1f
        }
    }

    private fun hideScrim(animate: Boolean, immediate: Boolean = false) {
        val scrim = scrimView ?: return
        scrim.animate().cancel()
        if (immediate) {
            scrim.isVisible = false
            scrim.alpha = 1f
            return
        }
        if (!scrim.isVisible) return
        if (animate) {
            scrim.animate()
                .alpha(0f)
                .setDuration(120)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    scrim.isVisible = false
                    scrim.alpha = 1f
                }
                .start()
        } else {
            scrim.isVisible = false
            scrim.alpha = 1f
        }
    }

    private fun scheduleBoundsSync() {
        if (!menuOpen || isDragging) return
        overlayWindowRoot?.removeCallbacks(boundsRunnable)
        overlayWindowRoot?.postDelayed(boundsRunnable, BOUNDS_DEBOUNCE_MS)
    }

    private fun applyResponsiveSize() {
        val root = overlayWindowRoot ?: return
        val size = OverlayMetrics.resolve(resources)

        menuView?.let { menu ->
            val lp = menu.layoutParams ?: ViewGroup.LayoutParams(
                size.menuWidthPx,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.width = size.menuWidthPx
            menu.layoutParams = lp
        }

        chipView?.let { chip ->
            val lp = chip.layoutParams ?: ViewGroup.LayoutParams(size.fabSizePx, size.fabSizePx)
            lp.width = size.fabSizePx
            lp.height = size.fabSizePx
            chip.layoutParams = lp
        }

        root.findViewById<View>(R.id.overlayPanelHost)?.let { panel ->
            val lp = panel.layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                size.panelMaxHeightPx,
            )
            lp.height = size.panelMaxHeightPx
            panel.layoutParams = lp
        }

        root.findViewById<View>(R.id.overlayFabIcon)?.let { icon ->
            val iconPx = (size.fabSizePx * 0.55f).toInt()
            val lp = icon.layoutParams ?: ViewGroup.LayoutParams(iconPx, iconPx)
            lp.width = iconPx
            lp.height = iconPx
            icon.layoutParams = lp
        }

        root.requestLayout()
    }

    private fun contentSize(): Pair<Int, Int> {
        val metrics = OverlayMetrics.resolve(resources)
        if (!menuOpen) {
            return metrics.fabSizePx to metrics.fabSizePx
        }
        val content = overlayContent
        val width = content?.width?.takeIf { it > 0 } ?: metrics.menuWidthPx
        val gap = (resources.displayMetrics.density * 8f).toInt()
        val height = content?.height?.takeIf { it > 0 } ?: run {
            metrics.fabSizePx + gap + metrics.panelMaxHeightPx +
                (resources.displayMetrics.density * 48f).toInt()
        }
        return width to height
    }

    private fun clampPosition(x: Int, y: Int, width: Int, height: Int): Pair<Int, Int> {
        val metrics: DisplayMetrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - height).coerceAtLeast(0)
        val safeTop = (metrics.density * 24f).toInt()
        val safeBottom = (metrics.density * 72f).toInt()
        val clampedX = x.coerceIn(0, maxX)
        val clampedY = y.coerceIn(safeTop, (maxY - safeBottom).coerceAtLeast(safeTop))
        return clampedX to clampedY
    }

    /** Menu đóng: cửa sổ WRAP_CONTENT (chạm xuyên qua WePlay). Menu mở: full màn + scrim. */
    private fun applyWindowLayout() {
        val wm = windowManager ?: return
        val params = layoutParams ?: return
        val root = overlayWindowRoot ?: return
        val content = overlayContent ?: return

        if (menuOpen) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.x = 0
            params.y = 0
            content.translationX = posX.toFloat()
            content.translationY = posY.toFloat()
        } else {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.x = posX
            params.y = posY
            content.translationX = 0f
            content.translationY = 0f
        }
        try {
            wm.updateViewLayout(root, params)
        } catch (_: Exception) {
        }
    }

    private fun applyPosition() {
        val content = overlayContent ?: return
        content.translationX = 0f
        content.translationY = 0f
        if (menuOpen) {
            content.translationX = posX.toFloat()
            content.translationY = posY.toFloat()
        } else {
            layoutParams?.x = posX
            layoutParams?.y = posY
            try {
                windowManager?.updateViewLayout(overlayWindowRoot, layoutParams)
            } catch (_: Exception) {
            }
        }
    }

    private fun clampPositionIfNeeded(persist: Boolean) {
        if (isDragging) return
        val (width, height) = contentSize()
        val clamped = clampPosition(posX, posY, width, height)
        if (clamped.first == posX && clamped.second == posY) return
        posX = clamped.first
        posY = clamped.second
        applyPosition()
        if (persist) persistPosition(posX, posY)
    }

    private fun persistPosition(x: Int, y: Int) {
        overlayPrefs().edit()
            .putInt(PREF_POS_X, x)
            .putInt(PREF_POS_Y, y)
            .apply()
    }

    private fun cancelHideAnimation() {
        val content = overlayContent ?: run {
            isHiding = false
            return
        }
        content.animate().cancel()
        isHiding = false
        content.alpha = 1f
        content.scaleX = 1f
        content.scaleY = 1f
    }

    fun hideOverlay() {
        val root = overlayWindowRoot ?: run {
            isHiding = false
            return
        }
        if (isHiding) return
        isHiding = true
        setMenuOpen(false, animate = false)
        hideScrim(animate = false, immediate = true)
        val content = overlayContent ?: root
        content.animate().cancel()
        content.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { detachOverlayView(root) }
            .start()
    }

    private fun detachOverlayView(view: View) {
        cancelDragLayout()
        overlayWindowRoot?.removeCallbacks(boundsRunnable)
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
        overlayWindowRoot = null
        overlayContent = null
        scrimView = null
        layoutParams = null
        chipView = null
        menuView = null
        fabBadgeView = null
        myWordView = null
        otherWordView = null
        menuOpen = false
        isHiding = false
        autoBinder = null
        tabBinder = null
        syncBinder?.destroy()
        syncBinder = null
    }

    fun updateOverlay(
        myWord: String?,
        myRole: WordRole?,
        others: List<LabeledWord>,
        plainMessage: String?,
        animate: Boolean = true,
    ) {
        if (isHiding) cancelHideAnimation()
        val my = myWord ?: "-"
        val myText = if (myRole != null && my != "-" && my != LOADING) {
            RoleTextFormatter.coloredWordBubble(my, myRole)
        } else {
            my
        }
        val otherText = when {
            !plainMessage.isNullOrEmpty() -> plainMessage
            others.isNotEmpty() -> RoleTextFormatter.formatOthersBubble(others)
            else -> getString(R.string.not_in_db)
        }
        val otherCount = when {
            !plainMessage.isNullOrEmpty() -> 0
            else -> others.size
        }
        val attached = isBubbleAttached()
        val unchanged = attached &&
            myWordView?.text?.toString() == myText.toString() &&
            otherWordView?.text?.toString() == otherText.toString() &&
            lastOtherCount == otherCount
        if (unchanged) {
            ensureVisible()
            return
        }

        if (!attached) showOverlay()
        lastOtherCount = otherCount

        val myView = myWordView ?: return
        val otherView = otherWordView ?: return

        val applyContent = {
            myView.text = myText
            otherView.text = otherText
            if (otherCount > 0) {
                fabBadgeView?.isVisible = true
                fabBadgeView?.text = otherCount.coerceAtMost(99).toString()
            } else {
                fabBadgeView?.isVisible = false
            }
            overlayWindowRoot?.findViewById<ScrollView>(R.id.overlayWordsPanel)?.scrollTo(0, 0)
            setupChipTouch()
        }

        applyContent()
        ensureVisible()
    }

    private fun overlayPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.copyright))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "spy_overlay"
        const val NOTIFICATION_ID = 1001
        const val ACTION_UPDATE = "com.phaohn.spyhelper.UPDATE"
        const val ACTION_HIDE = "com.phaohn.spyhelper.HIDE"
        const val EXTRA_MY_WORD = "my_word"
        const val EXTRA_MY_ROLE = "my_role"
        const val EXTRA_OTHERS_ROLES = "others_roles"
        const val EXTRA_PLAIN_MESSAGE = "plain_message"
        const val EXTRA_ANIMATE = "animate"

        private const val LOADING = "…"
        private const val PREFS_NAME = "phaohn_overlay"
        private const val PREF_POS_X = "pos_x"
        private const val PREF_POS_Y = "pos_y"
        private const val DEFAULT_POS_X = 24
        private const val DEFAULT_POS_Y = 180
        private const val DRAG_SLOP = 16f
        private const val BOUNDS_DEBOUNCE_MS = 120L

        @Volatile
        private var instance: OverlayService? = null

        fun update(
            context: Context,
            myWord: String,
            myRole: WordRole?,
            others: List<LabeledWord>,
            plainMessage: String? = null,
            animate: Boolean = true,
        ) {
            val running = instance
            if (running != null) {
                running.updateOverlay(myWord, myRole, others, plainMessage, animate)
                return
            }
            val i = Intent(context, OverlayService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_MY_WORD, myWord)
                myRole?.let { putExtra(EXTRA_MY_ROLE, it.name) }
                putExtra(EXTRA_OTHERS_ROLES, RoleTextFormatter.encodeOthers(others))
                plainMessage?.let { putExtra(EXTRA_PLAIN_MESSAGE, it) }
                putExtra(EXTRA_ANIMATE, animate)
            }
            context.startForegroundService(i)
        }

        fun start(context: Context) {
            if (instance != null) return
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun hide(context: Context) {
            val running = instance
            if (running != null) {
                running.hideOverlay()
                return
            }
            context.startService(
                Intent(context, OverlayService::class.java).apply { action = ACTION_HIDE }
            )
        }

        fun refreshAutoUi() {
            instance?.autoBinder?.refreshFromPrefs()
        }

        fun isBubbleOnScreen(): Boolean = instance?.isBubbleAttached() == true
    }
}