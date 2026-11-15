package com.example.lgremote

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/** Указка: превращает вращение телефона в движение курсора на ТВ. */
class Gyro(ctx: Context, private val tv: TvClient, private val onSpeed: (Float) -> Unit) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile var gain = 700f
    @Volatile var mode = 0          // 0 — телефон лежит как пульт, 1 — телефон стоит экраном к вам
    @Volatile var invertX = false
    @Volatile var invertY = false

    private var last = 0L
    private var lastReport = 0L
    private var ax = 0f
    private var ay = 0f

    val available: Boolean get() = sensor != null

    fun start() {
        last = 0L
        ax = 0f
        ay = 0f
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sm.unregisterListener(this)
        onSpeed(0f)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (last == 0L) {
            last = e.timestamp
            return
        }
        val dt = (e.timestamp - last) / 1_000_000_000f
        last = e.timestamp

        val gx = e.values[0]
        val gy = e.values[1]
        val gz = e.values[2]
        var yaw = if (mode == 0) gz else gy
        var pitch = gx
        if (abs(yaw) < 0.03f) yaw = 0f      // отсекаем дрейф датчика
        if (abs(pitch) < 0.03f) pitch = 0f

        var dx = -yaw * dt * gain
        var dy = -pitch * dt * gain
        if (invertX) dx = -dx
        if (invertY) dy = -dy
        ax += dx
        ay += dy
        val ix = ax.toInt()
        val iy = ay.toInt()
        if (ix != 0 || iy != 0) {
            ax -= ix
            ay -= iy
            tv.move(ix, iy)
        }

        val now = e.timestamp
        if (now - lastReport > 50_000_000L) {
            lastReport = now
            onSpeed(sqrt(yaw * yaw + pitch * pitch))
        }
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
}
