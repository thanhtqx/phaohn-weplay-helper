package com.phaohn.spyhelper

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.view.isVisible

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var myWordView: TextView? = null
    private var myWordCompactView: TextView? = null
    private var compactHintView: TextView? = null
    private var otherWordView: TextView? = null
    private var maximizeBtn: ImageView? = null

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var paramStartX = 0
    private var paramStartY = 0
    private var isExpanded = false
    private var lastOtherCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isExpanded = overlayPrefs().getBoolean(PREF_EXPANDED, false)
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
                updateOverlay(my, myRole, others, plain)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        hideOverlay()
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val overlayWidth = resources.getDimensionPixelSize(R.dimen.overlay_width)
        layoutParams = WindowManager.LayoutParams(
            overlayWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val prefs = overlayPrefs()
            x = prefs.getInt(PREF_POS_X, DEFAULT_POS_X)
            y = prefs.getInt(PREF_POS_Y, DEFAULT_POS_Y)
        }
        val root = LayoutInflater.from(applicationContext).inflate(R.layout.overlay_spy_word, null)
        overlayView = root.apply { alpha = 1f }

        myWordView = root.findViewById(R.id.overlayMyWord)
        myWordCompactView = root.findViewById(R.id.overlayMyWordCompact)
        compactHintView = root.findViewById(R.id.overlayCompactHint)
        otherWordView = root.findViewById(R.id.overlayOtherWord)
        maximizeBtn = root.findViewById(R.id.overlayMaximizeBtn)

        setupOverlayTouch(root)
        applyExpandedState()
        windowManager?.addView(root, layoutParams)
    }

    private fun setupOverlayTouch(root: View) {
        root.findViewById<View>(R.id.overlayDragArea).setOnTouchListener { _, event ->
            onDragTouch(event)
        }
        maximizeBtn?.setOnClickListener { toggleMaximized() }
    }

    private fun toggleMaximized() {
        isExpanded = !isExpanded
        overlayPrefs().edit().putBoolean(PREF_EXPANDED, isExpanded).apply()
        applyExpandedState()
        layoutParams?.let { persistPosition(it.x, it.y) }
    }

    private fun applyExpandedState() {
        val root = overlayView ?: return
        val compact = root.findViewById<View>(R.id.overlayCompactBody)
        val expanded = root.findViewById<View>(R.id.overlayExpandedBody)
        compact.isVisible = !isExpanded
        expanded.isVisible = isExpanded
        maximizeBtn?.setImageResource(
            if (isExpanded) R.drawable.ic_overlay_restore else R.drawable.ic_overlay_maximize
        )
        maximizeBtn?.contentDescription = getString(
            if (isExpanded) R.string.overlay_restore else R.string.overlay_maximize
        )
        refreshWindowSize()
    }

    private fun refreshWindowSize() {
        val root = overlayView ?: return
        val params = layoutParams ?: return
        val wm = windowManager ?: return
        val widthPx = resources.getDimensionPixelSize(R.dimen.overlay_width)
        params.width = widthPx
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        root.requestLayout()
        try {
            wm.updateViewLayout(root, params)
        } catch (_: Exception) {
        }
    }

    private fun onDragTouch(event: MotionEvent): Boolean {
        val root = overlayView ?: return false
        val params = layoutParams ?: return false
        val wm = windowManager ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.rawX
                dragStartY = event.rawY
                paramStartX = params.x
                paramStartY = params.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = paramStartX + (event.rawX - dragStartX).toInt()
                params.y = paramStartY + (event.rawY - dragStartY).toInt()
                try {
                    wm.updateViewLayout(root, params)
                } catch (_: Exception) {
                    return false
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                persistPosition(params.x, params.y)
                return true
            }
        }
        return false
    }

    private fun persistPosition(x: Int, y: Int) {
        overlayPrefs().edit()
            .putInt(PREF_POS_X, x)
            .putInt(PREF_POS_Y, y)
            .apply()
    }

    fun hideOverlay() {
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        layoutParams = null
        myWordView = null
        myWordCompactView = null
        compactHintView = null
        otherWordView = null
        maximizeBtn = null
    }

    fun updateOverlay(
        myWord: String?,
        myRole: WordRole?,
        others: List<LabeledWord>,
        plainMessage: String?,
    ) {
        val my = myWord ?: "-"
        val myText = if (myRole != null && my != "-" && my != LOADING) {
            RoleTextFormatter.coloredWordBubble(my, myRole)
        } else {
            my
        }
        val otherText = when {
            !plainMessage.isNullOrEmpty() -> plainMessage
            others.isNotEmpty() -> RoleTextFormatter.formatOthersBubble(others)
            else -> ""
        }
        val otherCount = when {
            !plainMessage.isNullOrEmpty() -> 0
            else -> others.size
        }
        if (overlayView != null &&
            myWordView?.text?.toString() == myText.toString() &&
            otherWordView?.text?.toString() == otherText.toString() &&
            lastOtherCount == otherCount
        ) {
            return
        }
        if (overlayView == null) showOverlay()
        lastOtherCount = otherCount

        myWordView?.text = myText
        myWordCompactView?.text = myText
        otherWordView?.text = otherText
        compactHintView?.text = when {
            otherCount > 0 -> getString(R.string.overlay_word_count, otherCount)
            !plainMessage.isNullOrEmpty() -> plainMessage
            else -> getString(R.string.not_in_db)
        }
        overlayView?.findViewById<ScrollView>(R.id.overlayScroll)?.scrollTo(0, 0)
        applyExpandedState()
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

        private const val LOADING = "…"
        private const val PREFS_NAME = "phaohn_overlay"
        private const val PREF_EXPANDED = "expanded"
        private const val PREF_POS_X = "pos_x"
        private const val PREF_POS_Y = "pos_y"
        private const val DEFAULT_POS_X = 24
        private const val DEFAULT_POS_Y = 180

        @Volatile
        private var instance: OverlayService? = null

        fun update(
            context: Context,
            myWord: String,
            myRole: WordRole?,
            others: List<LabeledWord>,
            plainMessage: String? = null,
        ) {
            val running = instance
            if (running != null) {
                running.updateOverlay(myWord, myRole, others, plainMessage)
                return
            }
            val i = Intent(context, OverlayService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_MY_WORD, myWord)
                myRole?.let { putExtra(EXTRA_MY_ROLE, it.name) }
                putExtra(EXTRA_OTHERS_ROLES, RoleTextFormatter.encodeOthers(others))
                plainMessage?.let { putExtra(EXTRA_PLAIN_MESSAGE, it) }
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
    }
}