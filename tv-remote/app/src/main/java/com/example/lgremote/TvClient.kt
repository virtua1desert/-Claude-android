package com.example.lgremote

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Клиент протокола LG webOS (SSAP): основной сокет + сокет указателя. */
class TvClient(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        /** state: connecting | pairing | ready | error */
        fun onState(state: String, text: String)
        fun onApps(json: String)
    }

    private val prefs = ctx.getSharedPreferences("tv", Context.MODE_PRIVATE)
    private val ui = Handler(Looper.getMainLooper())

    private val plain: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // Телевизор на порту 3001 использует самоподписанный сертификат.
    // Этот клиент используется ТОЛЬКО для адреса ТВ, который ввёл пользователь.
    private val insecure: OkHttpClient by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        plain.newBuilder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    @Volatile private var gen = 0
    @Volatile private var host = ""
    @Volatile private var main: WebSocket? = null
    @Volatile private var pointer: WebSocket? = null
    @Volatile private var wanted = false
    private var reqId = 0

    val savedHost: String get() = prefs.getString("host", "") ?: ""

    fun connect(h: String) {
        val clean = h.trim()
        if (clean.isEmpty()) return
        prefs.edit().putString("host", clean).apply()
        close()
        host = clean
        wanted = true
        val g = ++gen
        listener.onState("connecting", "Подключаюсь к $clean…")
        open(g, secure = false)
    }

    fun disconnect() {
        wanted = false
        gen++
        close()
    }

    private fun close() {
        main?.cancel(); main = null
        pointer?.cancel(); pointer = null
    }

    private fun retry(g: Int) {
        if (!wanted || g != gen) return
        ui.postDelayed({
            if (wanted && g == gen) open(g, secure = false)
        }, 3000)
    }

    private fun open(g: Int, secure: Boolean) {
        val url = if (secure) "wss://$host:3001" else "ws://$host:3000"
        val client = if (secure) insecure else plain
        main = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (g != gen) return
                val key = prefs.getString("key_$host", null)
                val msg = JSONObject(ctx.assets.open("register.json").bufferedReader().readText())
                if (key != null) msg.getJSONObject("payload").put("client-key", key)
                ws.send(msg.toString())
                if (key == null) listener.onState("pairing", "Подтвердите подключение на экране ТВ пультом (кнопка «Да»)")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (g != gen) return
                try {
                    handle(g, JSONObject(text))
                } catch (e: Exception) {
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (g != gen) return
                if (!secure) {
                    open(g, secure = true)
                } else {
                    listener.onState("error", "Нет связи с ТВ. Он включён? Телефон в той же сети?")
                    retry(g)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (g != gen) return
                listener.onState("error", "Соединение закрыто. Переподключаюсь…")
                retry(g)
            }
        })
    }

    private fun handle(g: Int, m: JSONObject) {
        when (m.optString("type")) {
            "registered" -> {
                val key = m.optJSONObject("payload")?.optString("client-key")
                if (!key.isNullOrEmpty()) prefs.edit().putString("key_$host", key).apply()
                listener.onState("ready", "Подключено")
                request("com.webos.service.networkinput/getPointerInputSocket", null, "pointer")
                request("com.webos.applicationManager/listLaunchPoints", null, "apps")
            }
            "response" -> {
                val payload = m.optJSONObject("payload") ?: return
                when (m.optString("id")) {
                    "pointer" -> payload.optString("socketPath").takeIf { it.isNotEmpty() }?.let { openPointer(g, it) }
                    "apps" -> payload.optJSONArray("launchPoints")?.let { listener.onApps(it.toString()) }
                }
            }
            "error" -> {
                if (m.optString("id") == "register_0") {
                    listener.onState("error", "Телевизор отклонил подключение: ${m.optString("error")}")
                }
            }
        }
    }

    private fun openPointer(g: Int, path: String) {
        val client = if (path.startsWith("wss")) insecure else plain
        pointer = client.newWebSocket(Request.Builder().url(path).build(), object : WebSocketListener() {
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (g == gen) pointer = null
            }
        })
    }

    fun request(uri: String, payload: JSONObject?, id: String? = null) {
        val m = JSONObject()
            .put("type", "request")
            .put("id", id ?: "r${reqId++}")
            .put("uri", "ssap://$uri")
        if (payload != null) m.put("payload", payload)
        main?.send(m.toString())
    }

    private fun input(s: String) {
        pointer?.send(s)
    }

    fun move(dx: Int, dy: Int) = input("type:move\ndx:$dx\ndy:$dy\ndown:0\n\n")
    fun click() = input("type:click\n\n")
    fun scroll(dy: Int) = input("type:scroll\ndx:0\ndy:$dy\n\n")

    private val buttons = setOf(
        "UP", "DOWN", "LEFT", "RIGHT", "ENTER", "BACK", "HOME", "MENU", "EXIT",
        "MUTE", "VOLUMEUP", "VOLUMEDOWN", "CHANNELUP", "CHANNELDOWN",
        "PLAY", "PAUSE", "REWIND", "FASTFORWARD"
    )

    fun button(name: String) {
        if (name in buttons) input("type:button\nname:$name\n\n")
    }

    fun text(s: String) = request("com.webos.service.ime/insertText", JSONObject().put("text", s).put("replace", 0))
    fun enter() = request("com.webos.service.ime/sendEnterKey", null)
    fun backspace() = request("com.webos.service.ime/deleteCharacters", JSONObject().put("count", 200))
    fun openUrl(url: String) = request("system.launcher/open", JSONObject().put("target", url))
    fun launch(id: String) = request("system.launcher/launch", JSONObject().put("id", id))
    fun powerOff() = request("system/turnOff", null)
    fun refreshApps() = request("com.webos.applicationManager/listLaunchPoints", null, "apps")
}
