package com.example.claudebubble

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Вставьте OAuth-токен Claude (получить: команда `claude setup-token` на компьютере)."
        })

        val tokenField = EditText(this).apply {
            hint = "sk-ant-oat…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("token", ""))
        }
        root.addView(tokenField)

        fun button(label: String, onClick: () -> Unit) = root.addView(Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        })

        button("1. Сохранить токен") {
            prefs.edit().putString("token", tokenField.text.toString().trim()).apply()
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        }

        button("2. Разрешить показ поверх других окон") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        button("3. Показать плавающую кнопку") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала выдайте разрешение (шаг 2)", Toast.LENGTH_LONG).show()
            } else {
                startForegroundService(Intent(this, OverlayService::class.java))
            }
        }

        button("Убрать кнопку") {
            stopService(Intent(this, OverlayService::class.java))
        }

        button("Чат с ИИ (бесплатный API)") {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        root.addView(TextView(this).apply {
            text = "\nНажмите на кнопку «C» — покажет % использования лимита за 5 часов и 7 дней. " +
                "Кнопку можно перетаскивать. Каждая проверка тратит ~1 токен."
        })

        setContentView(root)
    }
}
