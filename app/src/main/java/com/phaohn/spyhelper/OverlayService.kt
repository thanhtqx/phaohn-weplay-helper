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
import android.view.ScaleGestureDetector
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: android.view.View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var myWordView: TextView? = null
    private var otherWordView: TextView? = null
    private var scaleDetector: ScaleGestureDetector? = null

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var paramStartX = 0
    private var paramStartY = 0
    private var scaleFactor = 1f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        scaleFactor = overlayPrefs().getFloat(PREF_SCALE, 1f).coerceIn(MIN_SCALE, MAX_SCALE)
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
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
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
            x = 24
            y = 180
        }
        val root = LayoutInflater.from(applicationContext)
            .inflate(R.layout.overlay_spy_word, null)
            .apply {
                alpha = 0.72f
                pivotX = 0f
                pivotY = 0f
                scaleX = scaleFactor
                scaleY = scaleFactor
            }
        overlayView = root

        scaleDetector = ScaleGestureDetector(
            applicationContext,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                    root.scaleX = scaleFactor
                    root.scaleY = scaleFactor
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    overlayPrefs().edit().putFloat(PREF_SCALE, scaleFactor).apply()
                }
            }
        )

        root.setOnTouchListener { _, event ->
            scaleDetector?.onTouchEvent(event)
            event.pointerCount > 1
        }
        root.findViewById<android.view.View>(R.id.overlayDragHandle)
            .setOnTouchListener(::onOverlayTouch)
        myWordView = root.findViewById(R.id.overlayMyWord)
        otherWordView = root.findViewById(R.id.overlayOtherWord)
        windowManager?.addView(root, layoutParams)
    }

    private fun onOverlayTouch(_view: android.view.View, event: MotionEvent): Boolean {
        if (event.pointerCount > 1) return false
        val root = overlayView ?: return false
        val params = layoutParams ?: return false
        val wm = windowManager ?: return false
        when (event.action) {
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
        }
        return false
    }

    fun hideOverlay() {
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        layoutParams = null
        myWordView = null
        otherWordView = null
        scaleDetector = null
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
        if (overlayView != null &&
            myWordView?.text?.toString() == myText.toString() &&
            otherWordView?.text?.toString() == otherText.toString()
        ) {
            return
        }
        if (overlayView == null) showOverlay()
        myWordView?.text = myText
        otherWordView?.text = otherText
        overlayView?.findViewById<android.widget.ScrollView>(R.id.overlayScroll)?.scrollTo(0, 0)
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
        private const val PREF_SCALE = "scale"
        private const val MIN_SCALE = 0.75f
        private const val MAX_SCALE = 3f

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