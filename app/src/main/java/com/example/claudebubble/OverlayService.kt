package com.example.claudebubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val COLOR_BG = Color.parseColor("#F8CFAE")      // пастельно-абрикосовый фон
private val COLOR_TRACK = Color.parseColor("#EDB993")   // фон полоски
private val COLOR_FILL = Color.parseColor("#D2693F")    // заполнение полоски
private val COLOR_TEXT = Color.parseColor("#4A2E1F")    // текст

/** Горизонтальная полоска использования. */
class BarView(context: Context) : View(context) {
    var fraction = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_TRACK }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_FILL }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
        if (fraction > 0f) {
            canvas.drawRoundRect(0f, 0f, maxOf(width * fraction, height.toFloat()), height.toFloat(), r, r, fill)
        }
    }
}

class OverlayService : Service() {

    companion object {
        const val AUTO_COLLAPSE_MS = 3000L   // сворачивание при бездействии
        const val PEEK_DP = 14               // сколько кнопки видно у края
        const val SWIPE_DP = 40              // минимальная длина горизонтального свайпа
    }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var icon: ImageView
    private lateinit var panel: LinearLayout
    private lateinit var message: TextView
    private lateinit var row5: UsageRow
    private lateinit var row7: UsageRow
    private lateinit var params: WindowManager.LayoutParams

    private val ui = Handler(Looper.getMainLooper())
    private val collapseRunnable = Runnable { collapse() }

    private var expanded = false
    private var loading = false
    private var docked = false
    private var side = 0 // -1 слева, 1 справа, 0 не определено

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildViews()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (::root.isInitialized) runCatching { wm.removeView(root) }
        super.onDestroy()
    }

    private fun startAsForeground() {
        val channelId = "bubble"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Claude Bubble", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Claude Bubble работает")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun screenWidth() = resources.displayMetrics.widthPixels

    // ---------------------------------------------------------------- views

    private inner class UsageRow(label: String) {
        val view = LinearLayout(this@OverlayService).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        private val pct = text("", 14f, bold = true)
        private val bar = BarView(this@OverlayService)
        private val reset = text("", 11f)

        init {
            val head = LinearLayout(this@OverlayService).apply { orientation = LinearLayout.HORIZONTAL }
            val title = text(label, 13f).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            head.addView(title)
            head.addView(pct)
            view.addView(head)
            view.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply {
                topMargin = dp(4)
            })
            view.addView(reset)
        }

        fun update(util: Double, resetSec: Long) {
            pct.text = "${(util * 100).toInt()}%"
            bar.fraction = util.toFloat()
            reset.text = if (resetSec > 0) {
                "сброс " + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(resetSec * 1000))
            } else ""
        }

        fun clear() {
            pct.text = ""
            bar.fraction = 0f
            reset.text = ""
        }
    }

    private fun text(s: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(COLOR_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun buildViews() {
        icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        }

        row5 = UsageRow("5 часов")
        row7 = UsageRow("7 дней")
        message = text("", 13f).apply { visibility = View.GONE }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(190)
            visibility = View.GONE
            addView(row5.view)
            addView(row7.view)
            addView(message)
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(COLOR_BG)
                cornerRadius = dp(22).toFloat()
            }
            elevation = dp(4).toFloat()
            addView(icon)
            addView(panel)
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(200)
        }

        root.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        touchX = e.rawX; touchY = e.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                        if (moved) {
                            if (!docked) params.x = startX + dx
                            params.y = startY + dy
                            wm.updateViewLayout(root, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            onTap()
                        } else {
                            val dx = e.rawX - touchX
                            val dy = e.rawY - touchY
                            if (abs(dx) > dp(SWIPE_DP) && abs(dx) > abs(dy)) {
                                dock(if (dx < 0) -1 else 1)
                            } else if (!docked) {
                                clampToScreen()
                            }
                        }
                    }
                }
                return true
            }
        })

        wm.addView(root, params)
    }

    // ------------------------------------------------------------- behaviour

    private fun onTap() {
        ui.removeCallbacks(collapseRunnable)

        if (docked) {
            undock()
        } else if (expanded) {
            collapse()
            return
        }
        if (loading) return

        val token = getSharedPreferences("cfg", MODE_PRIVATE).getString("token", "").orEmpty()
        if (token.isBlank()) {
            showMessage("Нет токена.\nОткройте приложение.")
            return
        }
        loading = true
        showMessage("…")
        Thread {
            val result = try {
                Result.success(UsageClient.fetch(token))
            } catch (e: Exception) {
                Result.failure(e)
            }
            ui.post {
                loading = false
                result.fold(
                    onSuccess = { showUsage(it) },
                    onFailure = { showMessage("Ошибка: ${it.message}") },
                )
            }
        }.start()
    }

    private fun showUsage(u: UsageClient.Usage) {
        message.visibility = View.GONE
        row5.view.visibility = View.VISIBLE
        row7.view.visibility = View.VISIBLE
        row5.update(u.util5h, u.reset5h)
        row7.update(u.util7d, u.reset7d)
        expand()
    }

    private fun showMessage(text: String) {
        row5.view.visibility = View.GONE
        row7.view.visibility = View.GONE
        message.text = text
        message.visibility = View.VISIBLE
        expand()
    }

    private fun expand() {
        expanded = true
        icon.visibility = View.GONE
        panel.visibility = View.VISIBLE
        root.setPadding(dp(14), dp(12), dp(14), dp(6))
        wm.updateViewLayout(root, params)
        root.post { clampToScreen() }
        ui.removeCallbacks(collapseRunnable)
        if (!loading) ui.postDelayed(collapseRunnable, AUTO_COLLAPSE_MS)
    }

    private fun collapse() {
        ui.removeCallbacks(collapseRunnable)
        if (!expanded) return
        expanded = false
        panel.visibility = View.GONE
        icon.visibility = View.VISIBLE
        root.setPadding(dp(10), dp(10), dp(10), dp(10))
        wm.updateViewLayout(root, params)
        // вернуть к краю, у которого кнопка была
        root.post {
            if (!docked && side != 0) {
                params.x = if (side < 0) 0 else screenWidth() - root.width
                wm.updateViewLayout(root, params)
            }
        }
    }

    /** Прячет кнопку у левого (-1) или правого (1) края, оставляя видимой узкую полоску. */
    private fun dock(s: Int) {
        ui.removeCallbacks(collapseRunnable)
        if (expanded) {
            expanded = false
            panel.visibility = View.GONE
            icon.visibility = View.VISIBLE
            root.setPadding(dp(10), dp(10), dp(10), dp(10))
            wm.updateViewLayout(root, params)
        }
        root.post {
            side = s
            docked = true
            val w = if (root.width > 0) root.width else dp(44)
            params.x = if (s < 0) -(w - dp(PEEK_DP)) else screenWidth() - dp(PEEK_DP)
            wm.updateViewLayout(root, params)
        }
    }

    private fun undock() {
        docked = false
        val w = if (root.width > 0) root.width else dp(44)
        params.x = if (side < 0) 0 else screenWidth() - w
        wm.updateViewLayout(root, params)
    }

    private fun clampToScreen() {
        val maxX = screenWidth() - root.width
        val nx = params.x.coerceIn(0, maxOf(maxX, 0))
        if (nx != params.x) {
            params.x = nx
            wm.updateViewLayout(root, params)
        }
    }
}
