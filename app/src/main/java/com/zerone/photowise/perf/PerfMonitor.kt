package com.zerone.photowise.perf

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock

/**
 * POC 验收所需指标采集。
 *
 * 对应 PRD「M0 出口条件」：预览帧率、推理耗时、PSS、电池温度、热状态。
 */
class PerfMonitor(private val context: Context) {

    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile
    var previewFps: Float = 0f
        private set

    @Volatile
    var poseLatencyMs: Float = 0f
        private set

    @Volatile
    var segLatencyMs: Float = 0f
        private set

    @Volatile
    var pssMb: Float = 0f
        private set

    @Volatile
    var batteryTempC: Float = 0f
        private set

    @Volatile
    var thermalStatus: Int = 0
        private set

    private var frameCount = 0
    private var windowStart = SystemClock.elapsedRealtime()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val t = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            if (t > 0) batteryTempC = t / 10f
        }
    }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = context.registerReceiver(null, filter)
        val t = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        if (t > 0) batteryTempC = t / 10f
        runCatching { context.registerReceiver(batteryReceiver, filter) }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(batteryReceiver) }
    }

    fun onFrame() {
        frameCount++
        val now = SystemClock.elapsedRealtime()
        val dt = now - windowStart
        if (dt >= 1000) {
            previewFps = frameCount * 1000f / dt
            frameCount = 0
            windowStart = now
        }
    }

    fun onPoseLatency(ms: Long) {
        poseLatencyMs = ms.toFloat()
    }

    fun onSegLatency(ms: Long) {
        segLatencyMs = ms.toFloat()
    }

    fun tick() {
        runCatching {
            val info = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(info)
            pssMb = info.totalPss / 1024f
        }
        thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { pm.currentThermalStatus }.getOrDefault(0)
        } else 0
    }

    fun thermalLabel(): String = when (thermalStatus) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> thermalStatus.toString()
    }
}
