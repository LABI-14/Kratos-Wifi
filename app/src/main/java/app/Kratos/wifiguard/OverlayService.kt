package app.kratos.wifiguard

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "app.ambaan.wifiguard.STOP"
        const val CHANNEL_ID = "kratos_guard"
        const val PREFS = "kratos"
        const val KEY_CORNER = "corner"

        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private var card: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var snapAnim: ValueAnimator? = null
    private var dragging = false

    // 0 = top-left, 1 = top-right, 2 = bottom-left, 3 = bottom-right
    private var corner = 3

    private val widgetWidth get() = dp(180)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (card == null) {
            if (!Settings.canDrawOverlays(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
            showWidget()
        }
        running = true
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        card?.post { placeAtCorner(corner) }
    }

    override fun onDestroy() {
        snapAnim?.cancel()
        card?.let { runCatching { wm.removeView(it) } }
        card = null
        running = false
        super.onDestroy()
    }

    // ---------- foreground notification ----------

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Kratos guard", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Stop", stopIntent
        ).build()

        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Kratos is guarding your Wi-Fi")
            .setContentText("Tap to open. Use Stop to close the widget.")
            .setContentIntent(openIntent)
            .addAction(stopAction)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- the widget ----------

    private fun showWidget() {
        corner = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_CORNER, 3)

        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE1B2230"))
                cornerRadius = dp(18).toFloat()
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = "\uD83D\uDEE1\uFE0F Kratos"
                setTextColor(Color.WHITE)
                textSize = 16f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            TextView(this).apply {
                text = "\u2715"
                setTextColor(Color.parseColor("#AAB4C4"))
                textSize = 18f
                setPadding(dp(12), dp(6), dp(6), dp(6))
                setOnClickListener { stopSelf() }
            }
        )
        view.addView(header)

        view.addView(TextView(this).apply {
            text = "Guard on duty"
            setTextColor(Color.parseColor("#7DE3A6"))
            textSize = 13f
        })
        view.addView(TextView(this).apply {
            text = "Drag me to any corner."
            setTextColor(Color.parseColor("#AAB4C4"))
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        })

        val lp = WindowManager.LayoutParams(
            widgetWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val (x, y) = targetFor(corner, measuredHeight(view))
        lp.x = x
        lp.y = y

        attachDrag(view, lp)

        view.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, oldTop, _, oldBottom ->
            if (!dragging && snapAnim?.isRunning != true && (bottom - top) != (oldBottom - oldTop)) {
                view.post { placeAtCorner(corner) }
            }
        }

        card = view
        params = lp
        wm.addView(view, lp)
    }

    private fun attachDrag(view: View, lp: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnim?.cancel()
                    dragging = true
                    moved = false
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    snapToNearestCorner()
                    true
                }
                else -> false
            }
        }
    }

    // ---------- corner maths ----------

    private fun safeBounds(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            val ins = m.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val b = m.bounds
            return Rect(b.left + ins.left, b.top + ins.top, b.right - ins.right, b.bottom - ins.bottom)
        }
        val dm = resources.displayMetrics
        val sbId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val sb = if (sbId > 0) resources.getDimensionPixelSize(sbId) else dp(24)
        return Rect(0, sb, dm.widthPixels, dm.heightPixels)
    }

    private fun measuredHeight(v: View): Int {
        if (v.height > 0) return v.height
        v.measure(
            View.MeasureSpec.makeMeasureSpec(widgetWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return v.measuredHeight
    }

    private fun targetFor(c: Int, height: Int): Pair<Int, Int> {
        val b = safeBounds()
        val margin = dp(12)
        val x = if (c % 2 == 0) b.left + margin else b.right - widgetWidth - margin
        val y = if (c < 2) b.top + margin else b.bottom - height - margin
        return x to y
    }

    private fun placeAtCorner(c: Int) {
        val v = card ?: return
        val lp = params ?: return
        val (x, y) = targetFor(c, measuredHeight(v))
        lp.x = x
        lp.y = y
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun snapToNearestCorner() {
        val v = card ?: return
        val lp = params ?: return
        val h = measuredHeight(v)
        val b = safeBounds()
        val cx = lp.x + widgetWidth / 2
        val cy = lp.y + h / 2
        val col = if (cx < b.centerX()) 0 else 1
        val row = if (cy < b.centerY()) 0 else 2
        corner = row + col
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_CORNER, corner).apply()

        val (tx, ty) = targetFor(corner, h)
        val sx = lp.x
        val sy = lp.y
        snapAnim?.cancel()
        snapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                lp.x = (sx + (tx - sx) * f).toInt()
                lp.y = (sy + (ty - sy) * f).toInt()
                runCatching { wm.updateViewLayout(v, lp) }
            }
            start()
        }
    }
}
