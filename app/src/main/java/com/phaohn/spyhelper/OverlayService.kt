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
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import kotlin.math.abs

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var scrimView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var scrimParams: WindowManager.LayoutParams? = null

    private var chipView: View? = null
    private var menuView: View? = null
    private var fabBadgeView: TextView? = null
    private var myWordView: TextView? = null
    private var otherWordView: TextView? = null

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var paramStartX = 0
    private var paramStartY = 0
    private var dragMoved = false
    private var menuOpen = false
    private var lastOtherCount = 0
    private var isHiding = false
    private var autoBinder: OverlayAutoBinder? = null
    private var tabBinder: OverlayTabBinder? = null
    private var syncBinder: OverlaySyncBinder? = null

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
        removeScrim()
        overlayView?.let { detachOverlayView(it) } ?: run { isHiding = false }
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
        if (overlayView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            baseOverlayFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            val prefs = overlayPrefs()
            val saved = clampPosition(
                prefs.getInt(PREF_POS_X, DEFAULT_POS_X),
                prefs.getInt(PREF_POS_Y, DEFAULT_POS_Y),
                estimateOverlayWidth(),
                estimateOverlayHeight(),
            )
            x = saved.first
            y = saved.second
        }

        val root = LayoutInflater.from(applicationContext).inflate(R.layout.overlay_spy_word, null)
        overlayView = root.apply {
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
        }
        isHiding = false
        menuOpen = false

        chipView = root.findViewById(R.id.overlayChip)
        menuView = root.findViewById(R.id.overlayMenu)
        fabBadgeView = root.findViewById(R.id.overlayFabBadge)
        myWordView = root.findViewById(R.id.overlayMyWord)
        otherWordView = root.findViewById(R.id.overlayOtherWord)

        setupChipTouch()
        val menu = menuView ?: root
        val onBounds: () -> Unit = { overlayView?.post { syncOverlayBounds() } }
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

        windowManager?.addView(root, layoutParams)
        root.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(0.6f))
            .start()
        root.post { syncOverlayBounds() }
    }

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
    }

    private fun onChipTouch(event: MotionEvent): Boolean {
        val root = overlayView ?: return false
        val params = layoutParams ?: return false
        val wm = windowManager ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.rawX
                dragStartY = event.rawY
                paramStartX = params.x
                paramStartY = params.y
                dragMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartX
                val dy = event.rawY - dragStartY
                if (!dragMoved && (abs(dx) > DRAG_SLOP || abs(dy) > DRAG_SLOP)) {
                    dragMoved = true
                    if (isMenuShowing()) setMenuOpen(false, animate = true)
                }
                if (dragMoved) {
                    params.x = paramStartX + dx.toInt()
                    params.y = paramStartY + dy.toInt()
                    try {
                        wm.updateViewLayout(root, params)
                    } catch (_: Exception) {
                        return false
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMoved) {
                    syncOverlayBounds()
                    persistPosition(params.x, params.y)
                } else {
                    toggleMenu()
                }
                return true
            }
        }
        return false
    }

    private fun isMenuShowing(): Boolean = menuOpen || menuView?.isVisible == true

    private fun toggleMenu() {
        setMenuOpen(!isMenuShowing(), animate = true)
    }

    private fun setMenuOpen(open: Boolean, animate: Boolean) {
        val menu = menuView ?: return
        menu.animate().cancel()

        if (open) {
            if (isMenuShowing()) return
            menuOpen = true
            updateFabState(true)
            tabBinder?.showWords()
            autoBinder?.refreshFromPrefs()
            showScrim()
            menu.isVisible = true
            menu.alpha = 1f
            menu.translationY = 0f
            if (animate) {
                menu.alpha = 0f
                menu.translationY = -8f
                menu.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        } else {
            if (!isMenuShowing()) return
            removeScrim()
            updateFabState(false)
            val finishClose = {
                menu.isVisible = false
                menu.alpha = 1f
                menu.translationY = 0f
                menuOpen = false
                overlayView?.post { syncOverlayBounds() }
            }
            if (animate) {
                menu.animate()
                    .alpha(0f)
                    .translationY(-6f)
                    .setDuration(120)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { finishClose() }
                    .start()
            } else {
                finishClose()
            }
            overlayView?.post { syncOverlayBounds() }
            return
        }
        overlayView?.post { syncOverlayBounds() }
    }

    private fun showScrim() {
        if (scrimView != null) return
        val wm = windowManager ?: return
        val scrim = View(applicationContext).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.overlay_scrim))
            isClickable = true
            isFocusable = false
            alpha = 0f
            setOnClickListener { setMenuOpen(false, animate = true) }
        }
        scrimParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            baseOverlayFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        wm.addView(scrim, scrimParams)
        scrim.animate()
            .alpha(1f)
            .setDuration(140)
            .start()
        scrimView = scrim
        bringOverlayToFront()
    }

    private fun bringOverlayToFront() {
        val root = overlayView ?: return
        val params = layoutParams ?: return
        val wm = windowManager ?: return
        try {
            wm.removeView(root)
            wm.addView(root, params)
        } catch (_: Exception) {
        }
    }

    private fun removeScrim() {
        val scrim = scrimView ?: return
        scrim.animate().cancel()
        try {
            windowManager?.removeView(scrim)
        } catch (_: Exception) {
        }
        scrimView = null
        scrimParams = null
    }

    private fun estimateOverlayHeight(): Int {
        val fab = resources.getDimensionPixelSize(R.dimen.overlay_fab_size)
        val menuBlock = resources.getDimensionPixelSize(R.dimen.overlay_menu_max_height) +
            (resources.displayMetrics.density * 40f).toInt()
        val gap = (resources.displayMetrics.density * 8f).toInt()
        return if (menuOpen) fab + gap + menuBlock else fab
    }

    private fun estimateOverlayWidth(): Int {
        val fab = resources.getDimensionPixelSize(R.dimen.overlay_fab_size)
        val menu = resources.getDimensionPixelSize(R.dimen.overlay_menu_width)
        return if (menuOpen) maxOf(fab, menu) else fab
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

    private fun syncOverlayBounds() {
        val root = overlayView ?: return
        val params = layoutParams ?: return
        val wm = windowManager ?: return
        val width = root.width.takeIf { it > 0 } ?: estimateOverlayWidth()
        val height = root.height.takeIf { it > 0 } ?: estimateOverlayHeight()
        val clamped = clampPosition(params.x, params.y, width, height)
        if (clamped.first == params.x && clamped.second == params.y) return
        params.x = clamped.first
        params.y = clamped.second
        try {
            wm.updateViewLayout(root, params)
            persistPosition(params.x, params.y)
        } catch (_: Exception) {
        }
    }

    private fun persistPosition(x: Int, y: Int) {
        overlayPrefs().edit()
            .putInt(PREF_POS_X, x)
            .putInt(PREF_POS_Y, y)
            .apply()
    }

    private fun cancelHideAnimation() {
        val view = overlayView ?: run {
            isHiding = false
            return
        }
        view.animate().cancel()
        isHiding = false
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    fun hideOverlay() {
        val view = overlayView ?: return
        if (isHiding) return
        isHiding = true
        setMenuOpen(false, animate = false)
        removeScrim()
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { detachOverlayView(view) }
            .start()
    }

    private fun detachOverlayView(view: View) {
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
        overlayView = null
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
        val unchanged = overlayView != null &&
            myWordView?.text?.toString() == myText.toString() &&
            otherWordView?.text?.toString() == otherText.toString() &&
            lastOtherCount == otherCount
        if (unchanged) return

        if (overlayView == null) showOverlay()
        lastOtherCount = otherCount

        val root = overlayView ?: return
        val applyContent = {
            myWordView?.text = myText
            otherWordView?.text = otherText
            if (otherCount > 0) {
                fabBadgeView?.isVisible = true
                fabBadgeView?.text = otherCount.coerceAtMost(99).toString()
            } else {
                fabBadgeView?.isVisible = false
            }
            root.findViewById<ScrollView>(R.id.overlayWordsPanel)?.scrollTo(0, 0)
            setupChipTouch()
            autoBinder?.refreshFromPrefs()
            if (isMenuShowing()) {
                root.post { syncOverlayBounds() }
            }
        }

        if (!animate || root.alpha < 1f) {
            applyContent()
            if (root.alpha < 1f) {
                root.alpha = 1f
                root.scaleX = 1f
                root.scaleY = 1f
            }
            return
        }

        root.animate().cancel()
        root.animate()
            .alpha(0.82f)
            .setDuration(70)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                applyContent()
                root.animate()
                    .alpha(1f)
                    .setDuration(120)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
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
        private const val DRAG_SLOP = 10f

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
    }
}