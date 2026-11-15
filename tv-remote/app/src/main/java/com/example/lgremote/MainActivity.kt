package com.example.lgremote

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity(), TvClient.Listener {

    private lateinit var web: WebView
    private lateinit var tv: TvClient
    private lateinit var gyro: Gyro
    private var lastState = JSONObject().put("state", "connecting").put("text", "")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TvClient(this, this)
        gyro = Gyro(this, tv) { m -> emit("gyro", JSONObject().put("m", m.toDouble())) }

        web = WebView(this)
        web.setBackgroundColor(0xFF0B0B10.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onStart() {
        super.onStart()
        if (tv.savedHost.isNotEmpty()) tv.connect(tv.savedHost)
    }

    override fun onStop() {
        super.onStop()
        gyro.stop()
        tv.disconnect()
    }

    // ----- события от ТВ → интерфейс -----
    override fun onState(state: String, text: String) {
        lastState = JSONObject().put("state", state).put("text", text)
        emit("state", lastState)
    }

    override fun onApps(json: String) {
        emit("apps", JSONObject().put("list", JSONArray(json)))
    }

    private fun emit(type: String, data: JSONObject) {
        val js = "window.onNative && window.onNative(${JSONObject.quote(type)},$data)"
        web.post { web.evaluateJavascript(js, null) }
    }

    private fun vibrate(ms: Long) {
        @Suppress("DEPRECATION")
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(ms, 90))
    }

    /** Ищет в локальной сети устройства с открытым портом 3000 (так отвечает ТВ LG). */
    private fun scan() {
        Thread {
            val found = JSONArray()
            try {
                val prefixes = mutableSetOf<String>()
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses) {
                        if (a is Inet4Address && a.isSiteLocalAddress) {
                            prefixes.add(a.hostAddress!!.substringBeforeLast('.'))
                        }
                    }
                }
                val pool = Executors.newFixedThreadPool(64)
                val hits = java.util.Collections.synchronizedList(mutableListOf<String>())
                for (p in prefixes) for (i in 1..254) {
                    val ip = "$p.$i"
                    pool.execute {
                        try {
                            Socket().use { s ->
                                s.connect(InetSocketAddress(ip, 3000), 400)
                                hits.add(ip)
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
                pool.shutdown()
                pool.awaitTermination(20, TimeUnit.SECONDS)
                hits.sorted().forEach { found.put(it) }
            } catch (e: Exception) {
            }
            emit("scan", JSONObject().put("hosts", found))
        }.start()
    }

    // ----- команды интерфейс → Kotlin -----
    inner class Bridge {
        @JavascriptInterface
        fun cmd(json: String) {
            try {
                handle(JSONObject(json))
            } catch (e: Exception) {
            }
        }
    }

    private fun handle(m: JSONObject) {
        when (m.optString("t")) {
            "ready" -> {
                emit("init", JSONObject().put("host", tv.savedHost).put("gyro", gyro.available))
                emit("state", lastState)
            }
            "connect" -> tv.connect(m.getString("host"))
            "scan" -> scan()
            "move" -> tv.move(m.getInt("dx"), m.getInt("dy"))
            "click" -> tv.click()
            "scroll" -> tv.scroll(m.getInt("dy"))
            "btn" -> tv.button(m.getString("n"))
            "text" -> tv.text(m.getString("s"))
            "enter" -> tv.enter()
            "del" -> tv.backspace()
            "open" -> tv.openUrl(m.getString("url"))
            "launch" -> tv.launch(m.getString("id"))
            "apps" -> tv.refreshApps()
            "power" -> tv.powerOff()
            "gyro" -> if (m.getBoolean("on")) gyro.start() else gyro.stop()
            "cfg" -> {
                gyro.gain = m.getDouble("gain").toFloat()
                gyro.mode = m.getInt("mode")
                gyro.invertX = m.getBoolean("ix")
                gyro.invertY = m.getBoolean("iy")
            }
            "haptic" -> vibrate(m.optLong("ms", 12))
        }
    }
}
