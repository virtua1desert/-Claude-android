package com.example.claudebubble

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Простой чат с бесплатным LLM API (OpenAI-совместимым). */
class ChatActivity : Activity() {

    private val history = mutableListOf<LlmClient.Message>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("llm", MODE_PRIVATE)
        val pad = (12 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        // ---------- настройки провайдера
        val names = LlmClient.PROVIDERS.map { it.name } + "Свой"
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ChatActivity, android.R.layout.simple_spinner_dropdown_item, names)
        }
        val urlField = EditText(this).apply {
            hint = "Base URL"
            setSingleLine()
            setText(prefs.getString("url", LlmClient.PROVIDERS[0].baseUrl))
        }
        val modelField = EditText(this).apply {
            hint = "Модель"
            setSingleLine()
            setText(prefs.getString("model", LlmClient.PROVIDERS[0].model))
        }
        val keyField = EditText(this).apply {
            hint = "API-ключ (бесплатный)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }

        var restoring = true
        spinner.setSelection(prefs.getInt("provider", 0).coerceIn(0, names.size - 1))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (restoring) { restoring = false; return }
                LlmClient.PROVIDERS.getOrNull(pos)?.let {
                    urlField.setText(it.baseUrl)
                    modelField.setText(it.model)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(spinner)
            addView(urlField)
            addView(modelField)
            addView(keyField)
            addView(Button(this@ChatActivity).apply {
                text = "Получить бесплатный ключ"
                setOnClickListener {
                    val p = LlmClient.PROVIDERS.getOrNull(spinner.selectedItemPosition) ?: return@setOnClickListener
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.keyUrl)))
                }
            })
            addView(Button(this@ChatActivity).apply {
                text = "Сохранить настройки"
                setOnClickListener {
                    prefs.edit()
                        .putInt("provider", spinner.selectedItemPosition)
                        .putString("url", urlField.text.toString().trim())
                        .putString("model", modelField.text.toString().trim())
                        .putString("key", keyField.text.toString().trim())
                        .apply()
                    Toast.makeText(this@ChatActivity, "Сохранено", Toast.LENGTH_SHORT).show()
                }
            })
        }
        root.addView(Button(this).apply {
            text = "Настройки API"
            setOnClickListener {
                settings.visibility = if (settings.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        })
        root.addView(settings)
        if (prefs.getString("key", "").orEmpty().isNotBlank()) settings.visibility = View.GONE

        // ---------- переписка
        val log = TextView(this).apply { setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val input = EditText(this).apply {
            hint = "Сообщение…"
            maxLines = 5
        }
        val send = Button(this).apply { text = "➤" }
        val clear = Button(this).apply { text = "✕" }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send)
            addView(clear)
        }
        root.addView(bottom)

        fun render(extra: String = "") {
            log.text = history.joinToString("\n\n") {
                (if (it.role == "user") "Вы: " else "ИИ: ") + it.content
            } + extra
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        clear.setOnClickListener {
            if (!busy) { history.clear(); render() }
        }

        send.setOnClickListener {
            val textMsg = input.text.toString().trim()
            if (textMsg.isEmpty() || busy) return@setOnClickListener
            val url = prefs.getString("url", "").orEmpty()
            val model = prefs.getString("model", "").orEmpty()
            val key = prefs.getString("key", "").orEmpty()
            if (url.isBlank() || model.isBlank() || key.isBlank()) {
                settings.visibility = View.VISIBLE
                Toast.makeText(this, "Заполните и сохраните настройки API", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            input.setText("")
            history.add(LlmClient.Message("user", textMsg))
            busy = true
            render("\n\nИИ: …")
            val snapshot = history.toList()
            Thread {
                val result = runCatching { LlmClient.chat(url, key, model, snapshot) }
                runOnUiThread {
                    busy = false
                    result.fold(
                        onSuccess = { history.add(LlmClient.Message("assistant", it)); render() },
                        onFailure = {
                            history.removeAt(history.lastIndex)
                            input.setText(textMsg)
                            render("\n\nОшибка: ${it.message}")
                        },
                    )
                }
            }.start()
        }

        setContentView(root)
    }
}
