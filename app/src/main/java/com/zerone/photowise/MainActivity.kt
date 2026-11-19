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
import com.zerone.photowise.guidance.Audience
import com.zerone.photowise.guidance.GuidanceEngine
import com.zerone.photowise.guidance.GuidanceState
import com.zerone.photowise.guidance.ShotPlan
import com.zerone.photowise.imu.MotionMonitor
import com.zerone.photowise.inference.Lm
import com.zerone.photowise.inference.PersonSegmenter
import com.zerone.photowise.inference.PoseEstimator
import com.zerone.photowise.perf.PerfMonitor
import com.zerone.photowise.session.SessionStore
import com.zerone.photowise.ui.OverlayView
import java.util.Locale

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
    private lateinit var subjectView: TextView
    private lateinit var btnApply: Button
    private lateinit var btnReset: Button
    private lateinit var btnShutter: Button
    private lateinit var btnPlanEnv: Button
    private lateinit var btnPlanHalf: Button
    private lateinit var btnPlanSil: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val engine = GuidanceEngine()
    private var session: SessionStore? = null
    private var capturing = false

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
    private var faceLuma: Float? = null

    @Volatile
    private var bgLuma: Float? = null

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
        subjectView = findViewById(R.id.subjectView)
        btnApply = findViewById(R.id.btnApply)
        btnReset = findViewById(R.id.btnReset)
        btnShutter = findViewById(R.id.btnShutter)
        btnPlanEnv = findViewById(R.id.btnPlanEnv)
        btnPlanHalf = findViewById(R.id.btnPlanHalf)
        btnPlanSil = findViewById(R.id.btnPlanSil)

        btnPlanEnv.setOnClickListener { selectPlan(ShotPlan.ENV) }
        btnPlanHalf.setOnClickListener { selectPlan(ShotPlan.HALF) }
        btnPlanSil.setOnClickListener { selectPlan(ShotPlan.SILHOUETTE) }
        btnApply.setOnClickListener { applySuggestion() }
        btnShutter.setOnClickListener { capture() }
        btnReset.setOnClickListener {
            cameraController?.setZoom(1f)
            cameraController?.setExposureIndex(0)
            engine.reset()
            session?.event("reset", lastState)
            toast("已复位变焦与曝光")
        }
        stylePlanButtons()

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
        }.getOrNull()

        session = runCatching { SessionStore(this) }.onFailure {
            android.util.Log.e(TAG, "session init failed", it)
        }.getOrNull()
        session?.let { toast("记录在会话 ${it.sessionId}") }

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
            sampleLight(bitmap, lastPose)
        }
    }

    private fun sampleLight(src: Bitmap, pose: List<Lm>?) {
        runCatching {
            lumaCanvas.drawBitmap(src, null, android.graphics.Rect(0, 0, lumaW, lumaH), null)
            lumaBitmap.getPixels(lumaPixels, 0, lumaW, 0, 0, lumaW, lumaH)
            val face = boxOf(pose, intArrayOf(0, 2, 5, 7, 8), 0.04f)
            faceLuma = regionLuma(face, inside = true)
            val body = boxOf(pose, null, 0f)
            bgLuma = if (body == null) average(0, lumaPixels.size) else regionLuma(body, inside = false)
        }
    }

    private fun boxOf(pose: List<Lm>?, indices: IntArray?, pad: Float): FloatArray? {
        if (pose.isNullOrEmpty()) return null
        var minX = 1f
        var maxX = 0f
        var minY = 1f
        var maxY = 0f
        var n = 0
        val range = indices ?: IntArray(pose.size) { it }
        for (i in range) {
            val p = pose.getOrNull(i) ?: continue
            if (p.visibility < 0.3f) continue
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y
            if (p.y > maxY) maxY = p.y
            n++
        }
        if (n < 2) return null
        return floatArrayOf(
            (minX - pad).coerceIn(0f, 1f),
            (minY - pad).coerceIn(0f, 1f),
            (maxX + pad).coerceIn(0f, 1f),
            (maxY + pad).coerceIn(0f, 1f)
        )
    }

    /** box 为归一化 left,top,right,bottom。inside=false 时统计框外像素，当作背景。 */
    private fun regionLuma(box: FloatArray?, inside: Boolean): Float? {
        if (box == null) return null
        val l = (box[0] * lumaW).toInt().coerceIn(0, lumaW - 1)
        val t = (box[1] * lumaH).toInt().coerceIn(0, lumaH - 1)
        val r = (box[2] * lumaW).toInt().coerceIn(l + 1, lumaW)
        val b = (box[3] * lumaH).toInt().coerceIn(t + 1, lumaH)
        var sum = 0L
        var count = 0
        var y = 0
        while (y < lumaH) {
            var x = 0
            while (x < lumaW) {
                val hit = x in l until r && y in t until b
                if (hit == inside) {
                    sum += lumaOf(lumaPixels[y * lumaW + x])
                    count++
                }
                x += 2
            }
            y += 2
        }
        return if (count == 0) null else sum.toFloat() / count
    }

    private fun average(from: Int, until: Int): Float {
        var sum = 0L
        for (i in from until until) sum += lumaOf(lumaPixels[i])
        return sum.toFloat() / (until - from).coerceAtLeast(1)
    }

    private fun lumaOf(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
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
            faceLuma = faceLuma,
            bgLuma = bgLuma
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
            hasPerson = state.hasPerson,
            boxL = state.targetLeft,
            boxT = state.targetTop,
            boxR = state.targetRight,
            boxB = state.targetBottom
        )

        val planName = state.plan?.title ?: "未选拍法"
        hintView.text = "$planName · ${state.headline}"
        subjectView.text = if (state.subjectLine.isBlank()) "被拍的人：选好拍法后，这里告诉他怎么站"
        else "被拍的人：${state.subjectLine}"

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
            val who = if (t.audience == Audience.SUBJECT) "他 " else "你 "
            tv.text = (if (t.done) "✓ " else "● ") + who + t.text
            tv.setTextColor(if (t.done) 0xFF7BE07B.toInt() else if (t.audience == Audience.SUBJECT) 0xFFB3E5FC.toInt() else 0xFFFFE082.toInt())
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
        val msg = listOf(z, e).filter { it.isNotEmpty() }.joinToString(" / ")
        session?.event("apply", s, mapOf("zoom" to (zoom?.toString() ?: ""), "ev" to (ev?.toString() ?: "")))
        toast(msg)
    }

    private fun selectPlan(plan: ShotPlan) {
        engine.setPlan(plan)
        stylePlanButtons()
        session?.event("plan", lastState, mapOf("plan" to plan.name))
        toast("拍法：${plan.title}")
    }

    private fun stylePlanButtons() {
        val selected = 0xFF1B5E20.toInt()
        val normal = 0xFF424242.toInt()
        val current = engine.currentPlan()
        btnPlanEnv.setBackgroundColor(if (current == ShotPlan.ENV) selected else normal)
        btnPlanHalf.setBackgroundColor(if (current == ShotPlan.HALF) selected else normal)
        btnPlanSil.setBackgroundColor(if (current == ShotPlan.SILHOUETTE) selected else normal)
        btnPlanEnv.setTextColor(0xFFFFFFFF.toInt())
        btnPlanHalf.setTextColor(0xFFFFFFFF.toInt())
        btnPlanSil.setTextColor(0xFFFFFFFF.toInt())
    }

    private fun capture() {
        val cam = cameraController ?: return
        if (capturing) return
        if (!cam.canCapture()) {
            toast("这台手机暂时不能同时预览和拍照")
            return
        }
        capturing = true
        btnShutter.isEnabled = false
        val state = lastState
        cam.takePicture(
            onJpeg = { bytes ->
                session?.saveShot(bytes, state) { msg ->
                    capturing = false
                    btnShutter.isEnabled = true
                    toast(msg)
                } ?: runOnUiThread {
                    capturing = false
                    btnShutter.isEnabled = true
                    toast("会话没建起来，照片未保存")
                }
            },
            onError = { err ->
                runOnUiThread {
                    capturing = false
                    btnShutter.isEnabled = true
                    session?.event("shot_error", lastState, mapOf("error" to err))
                    toast(err)
                }
            }
        )
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
                session?.sample(lastState)
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
            session?.event("thermal_off", lastState)
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
                "脸 %s | 背景 %s | %s | %s\n",
                m?.faceLuma?.let { String.format(Locale.US, "%.0f", it) } ?: "-",
                m?.bgLuma?.let { String.format(Locale.US, "%.0f", it) } ?: "-",
                engine.currentPlan()?.title ?: "未选拍法",
                session?.sessionId ?: "-"
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
        runCatching { session?.close() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "POC"
        private const val REQ_CAMERA = 1001
        private const val SEG_INTERVAL = 4
        private const val LUMA_INTERVAL = 10
    }
}
