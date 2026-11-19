package com.zerone.photowise

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.zerone.photowise.camera.CameraController
import com.zerone.photowise.guidance.GuidanceEngine
import com.zerone.photowise.guidance.GuidanceState
import com.zerone.photowise.imu.MotionMonitor
import com.zerone.photowise.inference.Lm
import com.zerone.photowise.inference.PersonSegmenter
import com.zerone.photowise.inference.PoseEstimator
import com.zerone.photowise.perf.PerfMonitor
import com.zerone.photowise.ui.OverlayView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * POC 主界面：验证「实时构图指导」最小闭环 + 采集 M0 验收指标。
 *
 * 闭环：相机帧 → 姿态/分割 → 量化指标 → 待办清单 → 一键应用参数
 */
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var hudView: TextView
    private lateinit var todoContainer: LinearLayout
    private lateinit var hintView: TextView
    private lateinit var btnApply: Button
    private lateinit var btnSeg: Button
    private lateinit var btnReset: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val engine = GuidanceEngine()

    private var cameraController: CameraController? = null
    private var poseEstimator: PoseEstimator? = null
    private var segmenter: PersonSegmenter? = null
    private var motion: MotionMonitor? = null
    private var perf: PerfMonitor? = null

    private var poseInFlight = false
    private var poseT0 = 0L
    private var segInFlight = false
    private var segT0 = 0L
    private var segEnabled = true
    private var frameIndex = 0

    @Volatile
    private var lastSegFg: Float? = null

    @Volatile
    private var meanLuma: Float? = null

    private var lastPose: List<Lm>? = null
    private var lastState: GuidanceState? = null
    private var todoIds: List<String> = emptyList()
    private var running = false

    // 亮度采样用小图，避免整帧 getPixels
    private val lumaW = 64
    private val lumaH = 48
    private val lumaBitmap = Bitmap.createBitmap(lumaW, lumaH, Bitmap.Config.ARGB_8888)
    private val lumaCanvas = Canvas(lumaBitmap)
    private val lumaPixels = IntArray(lumaW * lumaH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        hudView = findViewById(R.id.hudView)
        todoContainer = findViewById(R.id.todoContainer)
        hintView = findViewById(R.id.hintView)
        btnApply = findViewById(R.id.btnApply)
        btnSeg = findViewById(R.id.btnSeg)
        btnReset = findViewById(R.id.btnReset)

        btnApply.setOnClickListener { applySuggestion() }
        btnSeg.setOnClickListener {
            segEnabled = !segEnabled
            btnSeg.text = if (segEnabled) "分割 开" else "分割 关"
            if (!segEnabled) lastSegFg = null
        }
        btnReset.setOnClickListener {
            cameraController?.setZoom(1f)
            cameraController?.setExposureIndex(0)
            engine.reset()
            toast("已复位变焦与曝光")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startPipeline()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startPipeline()
            } else {
                toast("需要相机权限才能使用")
                finish()
            }
        }
    }

    private fun startPipeline() {
        running = true

        perf = PerfMonitor(this).also { it.start() }
        motion = MotionMonitor(this).also { it.start() }

        poseEstimator = runCatching {
            PoseEstimator(this, ::onPose, ::onPoseError)
        }.onFailure { toast("姿态模型加载失败: ${it.message}") }.getOrNull()

        segmenter = runCatching {
            PersonSegmenter(this, ::onSeg, ::onSegError)
        }.onFailure {
            android.util.Log.e(TAG, "segmenter init failed", it)
            segEnabled = false
            btnSeg.text = "分割 关"
        }.getOrNull()

        cameraController = CameraController(this, this, previewView, ::onFrame).also { it.start() }

        startTicker()
    }

    // ---------------- 帧处理 ----------------

    private fun onFrame(bitmap: Bitmap) {
        if (!running) return
        frameIndex++
        perf?.onFrame()

        val now = SystemClock.elapsedRealtime()

        if (!poseInFlight) {
            poseInFlight = true
            poseT0 = now
            poseEstimator?.submit(bitmap, now)
        }

        if (segEnabled && !segInFlight && frameIndex % SEG_INTERVAL == 0) {
            val s = segmenter
            if (s != null) {
                segInFlight = true
                segT0 = now
                s.submit(bitmap, now)
            }
        }

        if (frameIndex % LUMA_INTERVAL == 0) {
            sampleLuma(bitmap)
        }
    }

    private fun sampleLuma(src: Bitmap) {
        runCatching {
            lumaCanvas.drawBitmap(src, null, android.graphics.Rect(0, 0, lumaW, lumaH), null)
            lumaBitmap.getPixels(lumaPixels, 0, lumaW, 0, 0, lumaW, lumaH)
            var sum = 0L
            for (p in lumaPixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                sum += (r * 299 + g * 587 + b * 114) / 1000
            }
            meanLuma = sum.toFloat() / lumaPixels.size
        }
    }

    private fun onPose(landmarks: List<Lm>?) {
        val latency = SystemClock.elapsedRealtime() - poseT0
        poseInFlight = false
        if (!running) return
        perf?.onPoseLatency(latency)
        lastPose = landmarks

        val motionRef = motion
        val cam = cameraController
        // roll 不可信时（手机接近水平）传 0，让规则不误判「手机歪了」
        val roll = if (motionRef?.rollValid == true) motionRef.rollDeg else 0f
        val state = engine.update(
            pose = landmarks,
            rollDeg = roll,
            tiltDeg = motionRef?.tiltDeg ?: 0f,
            zoom = cam?.liveZoom() ?: 1f,
            zoomMax = cam?.zoomMax ?: 1f,
            segForeground = lastSegFg,
            meanLuma = meanLuma
        )
        lastState = state
        mainHandler.post { render(state) }
    }

    private fun onSeg(fg: Float) {
        lastSegFg = fg
        perf?.onSegLatency(SystemClock.elapsedRealtime() - segT0)
        segInFlight = false
    }

    private fun onPoseError(msg: String) {
        poseInFlight = false
        android.util.Log.e(TAG, "pose error: $msg")
    }

    private fun onSegError(msg: String) {
        segInFlight = false
        android.util.Log.e(TAG, "seg error: $msg")
    }

    // ---------------- UI ----------------

    private fun render(state: GuidanceState) {
        if (isFinishing || isDestroyed) return

        overlayView.update(
            pose = lastPose,
            imgW = cameraController?.frameWidth ?: 0,
            imgH = cameraController?.frameHeight ?: 0,
            rollDeg = state.metrics.rollDeg,
            allDone = state.allDone,
            hasPerson = state.hasPerson
        )

        hintView.text = state.headline

        val ids = state.todos.map { it.id }
        if (ids != todoIds) {
            todoIds = ids
            todoContainer.removeAllViews()
            for (t in state.todos) {
                val tv = TextView(this).apply {
                    textSize = 12f
                    setPadding(24, 14, 24, 14)
                    maxWidth = resources.displayMetrics.widthPixels / 2
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 8
                tv.layoutParams = lp
                tv.gravity = Gravity.START
                todoContainer.addView(tv)
            }
        }

        for (i in state.todos.indices) {
            val tv = todoContainer.getChildAt(i) as? TextView ?: continue
            val t = state.todos[i]
            tv.text = (if (t.done) "✓ " else "● ") + t.text
            tv.setTextColor(if (t.done) 0xFF7BE07B.toInt() else 0xFFFFE082.toInt())
            tv.setBackgroundColor(if (t.done) 0x99002200.toInt() else 0x99000000.toInt())
        }
    }

    private fun applySuggestion() {
        val s = lastState ?: return
        val cam = cameraController ?: return

        val zoom = s.suggestedZoom
        val ev = s.suggestedEvIndex

        if (zoom == null && (ev == null || ev == 0)) {
            toast("当前不需要调整参数")
            return
        }

        zoom?.let { cam.setZoom(it) }
        ev?.let { cam.setExposureIndex(it) }

        val z = zoom?.let { String.format(Locale.US, "变焦 x%.1f", it) } ?: ""
        val e = if (ev != null && ev != 0)
            String.format(Locale.US, "曝光 %+.1fEV", ev / 3f) else ""
        toast(listOf(z, e).filter { it.isNotEmpty() }.joinToString(" / "))
    }

    private fun startTicker() {
        mainHandler.post(object : Runnable {
            override fun run() {
                if (!running) return
                perf?.tick()
                val hud = buildHud()
                hudView.text = hud
                // POC 数据采集：每秒把指标打到 logcat，便于脚本化汇总
                val head = lastState?.headline ?: "-"
                val done = lastState?.allDone == true
                android.util.Log.i(TAG, "$hud | HEAD[$head] done=$done".replace('\n', ' '))
                adaptToThermal()
                mainHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun adaptToThermal() {
        val t = perf?.thermalStatus ?: 0
        if (t >= 3 && segEnabled) {
            segEnabled = false
            btnSeg.text = "分割 关"
            toast("机身发热，已自动关闭分割以降低负载")
        }
    }

    private fun buildHud(): String {
        val p = perf
        val cam = cameraController
        val m = lastState?.metrics
        val sb = StringBuilder()
        sb.append(
            String.format(
                Locale.US,
                "预览 %5.1ffps | 姿态 %5.0fms | 分割 %5.0fms\n",
                p?.previewFps ?: 0f, p?.poseLatencyMs ?: 0f, p?.segLatencyMs ?: 0f
            )
        )
        sb.append(
            String.format(
                Locale.US,
                "PSS %5.0fMB | 电池 %4.1f°C | 热 %s\n",
                p?.pssMb ?: 0f, p?.batteryTempC ?: 0f, p?.thermalLabel() ?: "-"
            )
        )
        sb.append(
            String.format(
                Locale.US,
                "变焦 %.1fx (%.1f-%.1f) | EV范围 %d..%d\n",
                cam?.liveZoom() ?: 1f, cam?.zoomMin ?: 1f, cam?.zoomMax ?: 1f,
                cam?.exposureMin ?: 0, cam?.exposureMax ?: 0
            )
        )
        sb.append(
            String.format(
                Locale.US,
                "占比 %3.0f%% | 留白 %3.0f%% | roll %+5.1f° | tilt %+5.1f°\n",
                (m?.subjectRatio ?: 0f) * 100f,
                (m?.headroom ?: 0f) * 100f,
                m?.rollDeg ?: 0f, m?.tiltDeg ?: 0f
            )
        )
        sb.append(
            String.format(
                Locale.US,
                "分割前景 %s | 亮度 %s | 帧 %dx%d\n",
                m?.segForeground?.let { String.format(Locale.US, "%.0f%%", it * 100f) } ?: "off",
                m?.meanLuma?.let { String.format(Locale.US, "%.0f", it) } ?: "-",
                cam?.frameWidth ?: 0, cam?.frameHeight ?: 0
            )
        )
        sb.append(
            String.format(
                Locale.US,
                "seg=%s:%s | 重力水平 %.2f roll%s",
                if (segEnabled) "on" else "off",
                if (segmenter != null) "ok" else "null",
                motion?.horizontalMag ?: 0f,
                if (motion?.rollValid == true) "ok" else "冻结"
            )
        )
        return sb.toString()
    }

    private fun toast(msg: String) {
        if (msg.isBlank()) return
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { cameraController?.stop() }
        runCatching { poseEstimator?.close() }
        runCatching { segmenter?.close() }
        runCatching { motion?.stop() }
        runCatching { perf?.stop() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "POC"
        private const val REQ_CAMERA = 1001
        private const val SEG_INTERVAL = 4
        private const val LUMA_INTERVAL = 10
    }
}
