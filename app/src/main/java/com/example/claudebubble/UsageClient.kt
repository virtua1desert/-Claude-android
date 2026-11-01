package com.example.claudebubble

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Читает лимиты подписки Claude (5 часов / 7 дней) из заголовков ответа API.
 * Делает минимальный запрос (max_tokens = 1), поэтому каждая проверка тратит ~1 токен.
 *
 * Это НЕОФИЦИАЛЬНЫЙ способ: заголовки anthropic-ratelimit-unified-* не задокументированы
 * и могут измениться. Токены в абсолютных числах Claude.ai не отдаёт — только проценты.
 */
object UsageClient {

    private const val URL_MESSAGES = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-haiku-4-5-20251001"

    data class Usage(
        val util5h: Double,
        val reset5h: Long,
        val util7d: Double,
        val reset7d: Long,
        val status: String,
    )

    @Throws(IOException::class)
    fun fetch(oauthToken: String): Usage {
        val c = URL(URL_MESSAGES).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $oauthToken")
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.setRequestProperty("anthropic-beta", "oauth-2025-04-20")
            c.setRequestProperty("content-type", "application/json")

            val body = """{"model":"$MODEL","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
            c.outputStream.use { it.write(body.toByteArray()) }

            val code = c.responseCode
            val u5 = c.getHeaderField("anthropic-ratelimit-unified-5h-utilization")
            val u7 = c.getHeaderField("anthropic-ratelimit-unified-7d-utilization")

            if (u5 == null || u7 == null) {
                val err = (if (code >= 400) c.errorStream else c.inputStream)
                    ?.bufferedReader()?.use { it.readText() }?.take(200).orEmpty()
                throw IOException("HTTP $code: нет заголовков лимитов. $err")
            }

            return Usage(
                util5h = u5.toDouble(),
                reset5h = c.getHeaderField("anthropic-ratelimit-unified-5h-reset")?.toLongOrNull() ?: 0L,
                util7d = u7.toDouble(),
                reset7d = c.getHeaderField("anthropic-ratelimit-unified-7d-reset")?.toLongOrNull() ?: 0L,
                status = c.getHeaderField("anthropic-ratelimit-unified-status") ?: "?",
            )
        } finally {
            c.disconnect()
        }
    }
}
