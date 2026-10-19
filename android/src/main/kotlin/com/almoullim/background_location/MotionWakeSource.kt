package com.almoullim.background_location

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.Looper

internal interface MotionWakeSource {
    fun arm(onMovement: () -> Unit): Boolean
    fun cancel()
}
internal class SignificantMotionWakeSource(context: Context) : MotionWakeSource {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: TriggerEventListener? = null
    private var generation = 0
    override fun arm(onMovement: () -> Unit): Boolean {
        cancel()
        val token = generation
        val candidate = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                handler.post { if (generation == token) onMovement() }
            }
        }
        listener = candidate
        return try { sensor != null && manager?.requestTriggerSensor(candidate, sensor) == true }
            catch (_: RuntimeException) { false }
    }
    override fun cancel() {
        generation++
        try { listener?.let { manager?.cancelTriggerSensor(it, sensor) } } catch (_: RuntimeException) { }
        listener = null
    }
}
