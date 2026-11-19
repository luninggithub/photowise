package com.zerone.photowise.imu

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 姿态传感器读数（俯仰角 / 横滚角）。
 *
 * 这是本项目精度最高、成本最低的数据源：
 * 视觉测距误差 ±15%，而 IMU 角度误差只有 ±0.5°。
 * 所以「手机端平」「镜头抬高/蹲低」这类最高频提示应当优先依赖 IMU，而不是视觉。
 *
 * 坐标约定（竖屏握持）：
 * - rollDeg：横滚角，0 表示手机端正，正负表示顺时针/逆时针倾斜
 * - tiltDeg：屏幕俯仰，0 表示手机竖直、后摄水平；
 *            负值表示屏幕朝上、后摄朝下（俯拍）；正值反之（仰拍）
 *
 * **退化情况的处理（真机实测踩到的坑）**：
 * 手机接近水平（平放桌面、俯拍地面）时，重力几乎全部落在 z 轴，
 * gx、gy 都趋近 0，此时 atan2(-gx, gy) 在数学上无定义，
 * 实测会输出 -124° / +66° / +88° 这种乱跳的值，
 * 直接把「手机端平」误判成歪得离谱。
 * 因此当水平分量过小时，冻结 roll 并标记 rollValid=false，不再对外提供读数。
 */
class MotionMonitor(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravity: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var usingGravity = gravity != null

    @Volatile
    var rollDeg: Float = 0f
        private set

    @Volatile
    var tiltDeg: Float = 0f
        private set

    /** 重力水平分量大小（m/s²）。接近 0 说明手机接近水平，roll 不可信 */
    @Volatile
    var horizontalMag: Float = 0f
        private set

    /** roll 是否可信。手机接近水平时为 false */
    @Volatile
    var rollValid: Boolean = false
        private set

    @Volatile
    var valid: Boolean = false
        private set

    fun start() {
        val s = gravity ?: accel ?: return
        usingGravity = gravity != null
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        var gx = event.values[0]
        var gy = event.values[1]
        var gz = event.values[2]

        if (!usingGravity) {
            // 加速度计含运动噪声，做简单低通
            gx = lowPass(0, gx)
            gy = lowPass(1, gy)
            gz = lowPass(2, gz)
        }

        val horizontal = sqrt(gx * gx + gy * gy)
        horizontalMag = horizontal

        if (horizontal < 1e-3f && abs(gz) < 1e-3f) return

        // 水平分量太小 → roll 无定义，冻结上一次的有效值
        if (horizontal >= MIN_HORIZONTAL) {
            rollDeg = Math.toDegrees(atan2(-gx.toDouble(), gy.toDouble())).toFloat()
            rollValid = true
        } else {
            rollValid = false
        }

        tiltDeg = Math.toDegrees(atan2(gz.toDouble(), horizontal.toDouble())).toFloat()
        valid = true
    }

    private fun lowPass(i: Int, v: Float): Float {
        val prev = filtered[i]
        val next = prev + ALPHA * (v - prev)
        filtered[i] = next
        return next
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private val filtered = floatArrayOf(0f, 0f, 0f)

    companion object {
        private const val ALPHA = 0.2f

        /**
         * 重力水平分量阈值。重力约 9.8 m/s²，
         * 该阈值约对应「手机与水平面夹角 > 8°」，
         * 低于此角度时横滚角不可信。
         */
        private const val MIN_HORIZONTAL = 1.4f
    }
}
