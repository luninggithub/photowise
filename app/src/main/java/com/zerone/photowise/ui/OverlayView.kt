package com.zerone.photowise.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.zerone.photowise.inference.Lm
import com.zerone.photowise.inference.PoseEstimator
import kotlin.math.abs
import kotlin.math.max

/**
 * 取景框叠加层：九宫格 + 主体目标框 + 骨骼 + 水平仪。
 *
 * 坐标映射：分析帧经过旋转后为 imgW×imgH，PreviewView 用 FILL_CENTER（cover），
 * 因此叠加层使用同一个 cover 变换，才能保证骨骼和真实人物对齐。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var pose: List<Lm>? = null
    private var imgW = 0
    private var imgH = 0
    private var rollDeg = 0f
    private var allDone = false
    private var hasPerson = false

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 76, 175, 80)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 214, 79)
        style = Paint.Style.FILL
    }

    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    fun update(
        pose: List<Lm>?,
        imgW: Int,
        imgH: Int,
        rollDeg: Float,
        allDone: Boolean,
        hasPerson: Boolean
    ) {
        this.pose = pose
        this.imgW = imgW
        this.imgH = imgH
        this.rollDeg = rollDeg
        this.allDone = allDone
        this.hasPerson = hasPerson
        invalidate()
    }

    private fun coverScale(): Float = max(width / imgW.toFloat(), height / imgH.toFloat())

    private fun sx(nx: Float): Float {
        val s = coverScale()
        return (width - imgW * s) / 2f + nx * imgW * s
    }

    private fun sy(ny: Float): Float {
        val s = coverScale()
        return (height - imgH * s) / 2f + ny * imgH * s
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imgW <= 0 || imgH <= 0) return

        drawGrid(canvas)
        drawTargetBand(canvas)
        drawLevel(canvas)
        drawPose(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h, gridPaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, h * 2f / 3f, w, h * 2f / 3f, gridPaint)
    }

    /** 目标区间：人物高度应落在这条带子里（40%–70%） */
    private fun drawTargetBand(canvas: Canvas) {
        val top = sy(0.30f)
        val bottom = sy(0.70f)
        val left = sx(0.12f)
        val right = sx(0.88f)
        bandPaint.color = if (allDone) Color.argb(120, 76, 175, 80)
        else Color.argb(80, 255, 255, 255)
        val r = RectF(left, top, right, bottom)
        canvas.drawRoundRect(r, 10f, 10f, bandPaint)
    }

    /** 水平仪：随 roll 旋转，超过 2° 变红 */
    private fun drawLevel(canvas: Canvas) {
        val cy = height * 0.14f
        val half = width * 0.22f
        val cx = width / 2f
        levelPaint.color = if (abs(rollDeg) <= 2f) Color.argb(200, 76, 175, 80)
        else Color.argb(220, 244, 67, 54)
        canvas.save()
        canvas.rotate(-rollDeg, cx, cy)
        canvas.drawLine(cx - half, cy, cx + half, cy, levelPaint)
        canvas.drawLine(cx - half, cy - 8f, cx - half, cy + 8f, levelPaint)
        canvas.drawLine(cx + half, cy - 8f, cx + half, cy + 8f, levelPaint)
        canvas.restore()
    }

    private fun drawPose(canvas: Canvas) {
        val pts = pose ?: return
        if (pts.size < 33) return

        for ((a, b) in PoseEstimator.CONNECTIONS) {
            if (a >= pts.size || b >= pts.size) continue
            val pa = pts[a]
            val pb = pts[b]
            if (pa.visibility < 0.35f || pb.visibility < 0.35f) continue
            bonePaint.color = if (allDone) Color.argb(230, 76, 175, 80)
            else Color.argb(220, 255, 255, 255)
            canvas.drawLine(sx(pa.x), sy(pa.y), sx(pb.x), sy(pb.y), bonePaint)
        }

        for (i in 0 until minOf(pts.size, 33)) {
            val p = pts[i]
            if (p.visibility < 0.35f) continue
            canvas.drawCircle(sx(p.x), sy(p.y), 4.5f, jointPaint)
        }

        if (!hasPerson) return
    }
}
