package com.example.claudebubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
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
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var bubble: TextView
    private lateinit var params: WindowManager.LayoutParams
    private val ui = Handler(Looper.getMainLooper())
    private var expanded = false
    private var loading = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildBubble()
    }

    override fun onDestroy() {
        if (::bubble.isInitialized) runCatching { wm.removeView(bubble) }
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

    private fun buildBubble() {
        bubble = TextView(this).apply {
            text = "C"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D97757"))
                cornerRadius = dp(22).toFloat()
            }
            minWidth = dp(44)
            minHeight = dp(44)
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(200)
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
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
                            params.x = startX + dx
                            params.y = startY + dy
                            wm.updateViewLayout(bubble, params)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) onTap()
                }
                return true
            }
        })

        wm.addView(bubble, params)
    }

    private fun onTap() {
        if (expanded) {
            collapse()
            return
        }
        if (loading) return
        val token = getSharedPreferences("cfg", MODE_PRIVATE).getString("token", "").orEmpty()
        if (token.isBlank()) {
            show("Нет токена.\nОткройте приложение.")
            return
        }
        loading = true
        show("…")
        Thread {
            val text = try {
                format(UsageClient.fetch(token))
            } catch (e: Exception) {
                "Ошибка: ${e.message}"
            }
            ui.post {
                loading = false
                show(text)
            }
        }.start()
    }

    private fun show(text: String) {
        expanded = true
        bubble.text = text
        bubble.textSize = 13f
        bubble.gravity = Gravity.START
        wm.updateViewLayout(bubble, params)
    }

    private fun collapse() {
        expanded = false
        bubble.text = "C"
        bubble.textSize = 16f
        bubble.gravity = Gravity.CENTER
        wm.updateViewLayout(bubble, params)
    }

    private fun format(u: UsageClient.Usage): String {
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        fun at(sec: Long) = if (sec > 0) fmt.format(Date(sec * 1000)) else "—"
        return "5 ч: ${(u.util5h * 100).toInt()}%  (сброс ${at(u.reset5h)})\n" +
            "7 дн: ${(u.util7d * 100).toInt()}%  (сброс ${at(u.reset7d)})\n" +
            "Статус: ${u.status}"
    }
}
