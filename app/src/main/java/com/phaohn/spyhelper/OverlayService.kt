package com.phaohn.spyhelper

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.view.ContextThemeWrapper
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayWindowRoot: View? = null
    private var overlayContent: View? = null
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
    private var menuAnimating = false
    private var lastChipTapMs = 0L
    private var lastOtherCount = 0
    private var isHiding = false
    private var posX = DEFAULT_POS_X
    private var posY = DEFAULT_POS_Y

    private var autoBinder: OverlayAutoBinder? = null
    private var tabBinder: OverlayTabBinder? = null
    private var syncBinder: OverlaySyncBinder? = null

    private val boundsRunnable = Runnable { clampPositionIfNeeded(persist = false) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideOverlay(force = true)
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
        val existing = overlayWindowRoot
        if (existing != null) {
            if (!isHiding) return
            cancelHideAnimation()
            ensureVisible(animateIn = true)
            return
        }
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

        val themedCtx = ContextThemeWrapper(applicationContext, R.style.Theme_PhaoHN)
        val root = LayoutInflater.from(themedCtx).inflate(R.layout.overlay_spy_word, null)
        overlayWindowRoot = root
        overlayContent = root.findViewById(R.id.overlayRoot)
        isHiding = false
        menuOpen = false

        chipView = root.findViewById(R.id.overlayChip)
        menuView = root.findViewById(R.id.overlayMenu)
        fabBadgeView = root.findViewById(R.id.overlayFabBadge)
        myWordView = root.findViewById(R.id.overlayMyWord)
        otherWordView = root.findViewById(R.id.overlayOtherWord)

        root.findViewById<View>(R.id.overlayScrim)?.apply {
            isVisible = false
            isClickable = false
        }

        setupChipTouch()
        val menu = menuView ?: root
        menu.findViewById<ImageView>(R.id.overlayMenuClose)?.setOnClickListener {
            closeMenu(animate = true)
        }
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
            clampPositionIfNeeded(persist = false)
            applyWindowPosition()
            ensureVisible(animateIn = true)
        }
    }

    private fun applyWindowPosition() {
        val params = layoutParams ?: return
        params.x = posX
        params.y = posY
        overlayContent?.translationX = 0f
        overlayContent?.translationY = 0f
        try {
            windowManager?.updateViewLayout(overlayWindowRoot, params)
        } catch (_: Exception) {
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
                    overlayContent?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                }
                if (dragMoved) {
                    dragOffsetX = dx
                    dragOffsetY = dy
                    applyDragVisual()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragMoved) {
                    finishChipDrag()
                } else {
                    onChipTap()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragMoved) {
                    finishChipDrag()
                }
                return true
            }
        }
        return false
    }

    private fun onChipTap() {
        val now = SystemClock.elapsedRealtime()
        if (menuAnimating || now - lastChipTapMs < CHIP_TAP_DEBOUNCE_MS) return
        lastChipTapMs = now
        if (menuOpen) {
            closeMenu(animate = true)
        } else {
            openMenu(animate = true)
        }
    }

    private fun finishChipDrag() {
        posX = posStartX + dragOffsetX.roundToInt()
        posY = posStartY + dragOffsetY.roundToInt()
        dragOffsetX = 0f
        dragOffsetY = 0f
        isDragging = false
        dragMoved = false
        overlayContent?.setLayerType(View.LAYER_TYPE_NONE, null)
        overlayContent?.translationX = 0f
        overlayContent?.translationY = 0f
        applyWindowPosition()
        clampPositionIfNeeded(persist = true)
        ensureVisible()
    }

    private fun applyDragVisual() {
        val content = overlayContent ?: return
        content.translationX = dragOffsetX
        content.translationY = dragOffsetY
    }

    private fun isMenuShowing(): Boolean =
        menuOpen || menuAnimating || menuView?.isVisible == true

    private fun openMenu(animate: Boolean) {
        if (menuOpen || menuAnimating) return
        setMenuOpen(true, animate)
    }

    private fun closeMenu(animate: Boolean) {
        if (!isMenuShowing()) return
        setMenuOpen(false, animate)
    }

    private fun setMenuOpen(open: Boolean, animate: Boolean) {
        val menu = menuView ?: return
        menu.animate().cancel()

        if (open) {
            if (menuOpen || menuAnimating) return
            menuOpen = true
            updateFabState(true)
            tabBinder?.showWords()
            autoBinder?.refreshFromPrefs()
            menu.isVisible = true
            overlayWindowRoot?.requestLayout()
            overlayWindowRoot?.post { clampPositionIfNeeded(persist = false) }
            if (animate) {
                menuAnimating = true
                menu.alpha = 0f
                menu.animate()
                    .alpha(1f)
                    .setDuration(MENU_ANIM_OPEN_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { menuAnimating = false }
                    .start()
            } else {
                menu.alpha = 1f
                menuAnimating = false
            }
        } else {
            if (!menuOpen && !menuAnimating && !menu.isVisible) return
            updateFabState(false)
            menu.animate().cancel()

            fun finishClose() {
                menu.isVisible = false
                menu.alpha = 1f
                menuOpen = false
                menuAnimating = false
                overlayWindowRoot?.requestLayout()
                overlayWindowRoot?.post { clampPositionIfNeeded(persist = false) }
            }

            if (animate && menu.isVisible) {
                menuAnimating = true
                menu.animate()
                    .alpha(0f)
                    .setDuration(MENU_ANIM_CLOSE_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { finishClose() }
                    .start()
            } else {
                finishClose()
            }
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

    private fun clampPositionIfNeeded(persist: Boolean) {
        if (isDragging) return
        val (width, height) = contentSize()
        val clamped = clampPosition(posX, posY, width, height)
        if (clamped.first == posX && clamped.second == posY) return
        posX = clamped.first
        posY = clamped.second
        applyWindowPosition()
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

    fun hideOverlay(force: Boolean = false) {
        if (!force && isMenuShowing()) return
        val root = overlayWindowRoot ?: run {
            isHiding = false
            return
        }
        if (isHiding) return
        isHiding = true
        setMenuOpen(false, animate = false)
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
        overlayWindowRoot?.removeCallbacks(boundsRunnable)
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
        overlayWindowRoot = null
        overlayContent = null
        layoutParams = null
        chipView = null
        menuView = null
        fabBadgeView = null
        myWordView = null
        otherWordView = null
        menuOpen = false
        menuAnimating = false
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
        @Suppress("UNUSED_PARAMETER") animate: Boolean = true,
    ) {
        if (isHiding) cancelHideAnimation()
        val placeholder = getString(R.string.word_placeholder)
        val my = myWord?.trim().orEmpty()
        val myText = when {
            my.isEmpty() || my == "-" -> placeholder
            myRole != null -> RoleTextFormatter.coloredWordBubble(my, myRole)
            else -> my
        }
        val otherText = when {
            !plainMessage.isNullOrEmpty() -> plainMessage
            others.isNotEmpty() -> RoleTextFormatter.formatOthersBubble(others)
            myRole != null -> placeholder
            my.isNotEmpty() && my != "-" -> getString(R.string.word_searching)
            else -> getString(R.string.word_searching)
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

        val hintColor = ContextCompat.getColor(this, R.color.overlay_text_hint)
        val civilianColor = ContextCompat.getColor(this, R.color.overlay_accent_civilian)
        myView.text = myText
        myView.setTextColor(
            when {
                myText == placeholder -> hintColor
                myText is android.text.Spanned -> civilianColor
                else -> civilianColor
            },
        )
        otherView.text = otherText
        otherView.setTextColor(hintColor)
        if (otherCount > 0) {
            fabBadgeView?.isVisible = true
            fabBadgeView?.text = otherCount.coerceAtMost(99).toString()
        } else {
            fabBadgeView?.isVisible = false
        }
        overlayWindowRoot?.findViewById<ScrollView>(R.id.overlayWordsPanel)?.scrollTo(0, 0)
        overlayWindowRoot?.findViewById<ScrollView>(R.id.overlayOtherWordScroll)?.scrollTo(0, 0)
        setupChipTouch()
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

        private const val PREFS_NAME = "phaohn_overlay"
        private const val PREF_POS_X = "pos_x"
        private const val PREF_POS_Y = "pos_y"
        private const val DEFAULT_POS_X = 24
        private const val DEFAULT_POS_Y = 180
        private const val DRAG_SLOP = 10f
        private const val BOUNDS_DEBOUNCE_MS = 120L
        private const val CHIP_TAP_DEBOUNCE_MS = 300L
        private const val MENU_ANIM_OPEN_MS = 160L
        private const val MENU_ANIM_CLOSE_MS = 120L

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
            val running = instance
            if (running != null) {
                if (!running.isBubbleAttached()) running.showOverlay()
                return
            }
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun hide(context: Context) {
            val running = instance
            if (running != null) {
                running.hideOverlay(force = false)
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

        fun isMenuExpanded(): Boolean {
            val running = instance ?: return false
            return running.menuOpen || running.menuAnimating || running.menuView?.isVisible == true
        }
    }
}