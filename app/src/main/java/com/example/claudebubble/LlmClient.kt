package com.example.claudebubble

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Клиент для любого OpenAI-совместимого API (POST {baseUrl}/chat/completions).
 * Подходит для бесплатных тарифов Groq, OpenRouter, Google Gemini и др.
 */
object LlmClient {

    data class Provider(val name: String, val baseUrl: String, val model: String, val keyUrl: String)

    /** Готовые бесплатные провайдеры. Модель можно поменять в настройках. */
    val PROVIDERS = listOf(
        Provider("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile",
            "https://console.groq.com/keys"),
        Provider("OpenRouter", "https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free",
            "https://openrouter.ai/keys"),
        Provider("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-2.5-flash", "https://aistudio.google.com/apikey"),
    )

    data class Message(val role: String, val content: String)

    @Throws(IOException::class)
    fun chat(baseUrl: String, apiKey: String, model: String, history: List<Message>): String {
        val c = URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 90_000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $apiKey")
            c.setRequestProperty("Content-Type", "application/json")

            val msgs = JSONArray()
            history.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.content)) }
            val body = JSONObject().put("model", model).put("messages", msgs)
            c.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) throw IOException("HTTP $code: ${text.take(300)}")

            return JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content").trim()
        } finally {
            c.disconnect()
        }
    }
}
