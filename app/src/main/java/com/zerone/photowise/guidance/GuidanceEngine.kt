package com.zerone.photowise.guidance

import com.zerone.photowise.inference.Lm
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 引导规则引擎。
 *
 * 三条设计原则（对应 PRD 4.2 / 4.3）：
 * 1. **一次只给一件事**：人物没完整入镜时，不谈占比、不谈留白，避免指令自相矛盾。
 * 2. **抖动抑制**：状态连续 4 帧稳定才翻转，防止提示闪烁。
 * 3. **说人话 + 给动作**：不输出「主体占比不足」，输出「靠近一点，让人物占画面一半」。
 */
class GuidanceEngine {

    private val counters = HashMap<String, Int>()

    fun reset() = counters.clear()

    /**
     * @param pose 33 个归一化关键点，可能落在 [0,1] 之外（说明该部位已出画）
     */
    fun update(
        pose: List<Lm>?,
        rollDeg: Float,
        tiltDeg: Float,
        zoom: Float,
        zoomMax: Float,
        segForeground: Float?,
        meanLuma: Float?
    ): GuidanceState {
        if (pose.isNullOrEmpty()) {
            counters.clear()
            val m = FrameMetrics(
                rollDeg = rollDeg, tiltDeg = tiltDeg, zoom = zoom,
                segForeground = segForeground, meanLuma = meanLuma
            )
            return GuidanceState(
                hasPerson = false,
                metrics = m,
                todos = emptyList(),
                allDone = false,
                headline = "把人物放进画面中间，尽量拍到全身",
                suggestedZoom = null,
                suggestedEvIndex = evFromLuma(meanLuma)
            )
        }

        // ---- 人体框（不过滤上限，用于判断是否出画）----
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var rawTop = Float.MAX_VALUE
        var rawBottom = -Float.MAX_VALUE
        var counted = 0

        for (p in pose) {
            if (p.visibility < 0.3f) continue
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < rawTop) rawTop = p.y
            if (p.y > rawBottom) rawBottom = p.y
            counted++
        }

        if (counted < 4) {
            val m = FrameMetrics(rollDeg = rollDeg, tiltDeg = tiltDeg, zoom = zoom,
                segForeground = segForeground, meanLuma = meanLuma)
            return GuidanceState(
                hasPerson = true,
                metrics = m,
                todos = emptyList(),
                allDone = false,
                headline = "人物不够清晰，走近一点或调整角度",
                suggestedZoom = null,
                suggestedEvIndex = evFromLuma(meanLuma)
            )
        }

        /*
         * 关键修正：MediaPipe 最高只到眼睛/耳朵，不含颅顶。
         * 直接用关键点算「头顶留白」会系统性偏大、算「占比」会偏小，
         * 导致手机明明端平了却一直提示「头顶空太多」。
         *
         * 用人体比例补偿：眼睛约在 0.94 身高处、脚踝约 0.04 身高处，
         * 眼睛→脚踝 ≈ 0.90 身高，颅顶比眼睛再高约 0.06 身高。
         * 故 颅顶 ≈ 眼高 - 0.067 × (眼到脚踝的像素跨度)。
         */
        val ankleVisible = maxOf(
            pose.getOrNull(27)?.let { if (it.visibility > 0.3f) 1 else 0 } ?: 0,
            pose.getOrNull(28)?.let { if (it.visibility > 0.3f) 1 else 0 } ?: 0
        ) == 1
        val span = rawBottom - rawTop
        val headOffset = if (ankleVisible && span > 0.05f) span * HEAD_OFFSET_RATIO else 0f
        val top = rawTop - headOffset
        val bottom = rawBottom
        val cutTop = top <= 0.002f
        val cutBottom = bottom >= 0.998f

        val visibleTop = top.coerceIn(0f, 1f)
        val visibleBottom = bottom.coerceIn(0f, 1f)
        val ratio = (visibleBottom - visibleTop).coerceIn(0f, 1f)
        val headroom = visibleTop
        val centerX = ((minX + maxX) / 2f).coerceIn(0f, 1f)

        val metrics = FrameMetrics(
            subjectRatio = ratio,
            headroom = headroom,
            centerX = centerX,
            cutTop = cutTop,
            cutBottom = cutBottom,
            rollDeg = rollDeg,
            tiltDeg = tiltDeg,
            zoom = zoom,
            segForeground = segForeground,
            meanLuma = meanLuma
        )

        val todos = ArrayList<TodoItem>(4)

        // ---- 1. 是否完整入镜（最高优先级，未解决前不谈其他）----
        val cutFixed = settle("cut", !cutTop && !cutBottom)
        val cutText = when {
            cutTop && cutBottom -> "退后一点，头和脚都要留在画面里"
            cutBottom -> "退后一点，别把脚切掉"
            cutTop -> "退后一点，头顶留点空"
            else -> "人物已完整入镜"
        }
        todos += TodoItem("cut", cutText, cutFixed)

        if (cutFixed) {
            // ---- 2. 主体占比 ----
            val ratioOk = ratio in RATIO_MIN..RATIO_MAX
            val ratioFixed = settle("ratio", ratioOk)
            val ratioText = when {
                ratio < 0.25f -> "靠近一点，让人物占画面一半"
                ratio < RATIO_MIN -> "再靠近一点点"
                ratio > 0.88f -> "退后一点，现在是半身了"
                ratio > RATIO_MAX -> "再退一点点，留出背景"
                else -> "人物大小刚好"
            }
            todos += TodoItem("ratio", ratioText, ratioFixed)

            // ---- 3. 头顶留白 ----
            val headroomOk = headroom in HEADROOM_MIN..HEADROOM_MAX
            val hrFixed = settle("headroom", headroomOk)
            val hrText = when {
                headroom < HEADROOM_MIN -> "头顶贴边了，镜头稍微抬高"
                headroom > 0.20f -> "头顶空太多了，镜头压低一点"
                headroom > HEADROOM_MAX -> "头顶留白略多，压低一点"
                else -> "头顶留白刚好"
            }
            todos += TodoItem("headroom", hrText, hrFixed)
        }

        // ---- 4. 手机是否端平（IMU，精度 ±0.5°）----
        val levelOk = abs(rollDeg) <= ROLL_MAX
        val levelFixed = settle("level", levelOk)
        val levelText = if (levelOk) "手机是平的" else "手机端平，地平线歪了"
        todos += TodoItem("level", levelText, levelFixed)

        // ---- 5. 机位高度（IMU 俯仰）----
        val heightOk = tiltDeg in TILT_MIN..TILT_MAX
        val heightFixed = settle("height", heightOk)
        val heightText = when {
            tiltDeg < TILT_MIN -> "镜头太高了，蹲到胸口高度"
            tiltDeg > TILT_MAX -> "镜头太低了，抬到眼睛高度"
            else -> "机位高度合适"
        }
        todos += TodoItem("height", heightText, heightFixed)

        val allDone = todos.all { it.done }
        val headline = if (allDone) "好，就这样，准备拍" else
            (todos.firstOrNull { !it.done }?.text ?: "准备拍")

        // ---- 可自动调节的建议 ----
        val suggestedZoom = suggestZoom(ratio, zoom, zoomMax, cutFixed && !cutBottom)
        val suggestedEv = evFromLuma(meanLuma)

        return GuidanceState(
            hasPerson = true,
            metrics = metrics,
            todos = todos,
            allDone = allDone,
            headline = headline,
            suggestedZoom = suggestedZoom,
            suggestedEvIndex = suggestedEv
        )
    }

    /**
     * 只在「人明显偏小」时建议拉近，且上限 3x ——
     * 因为多数 Android 机 3x 以上是数码裁切，画质会明显下降（PRD 风险项）。
     */
    private fun suggestZoom(ratio: Float, currentZoom: Float, zoomMax: Float, allow: Boolean): Float? {
        if (!allow || ratio < 0.05f) return null
        val ideal = currentZoom * (0.5f / ratio)
        val capped = ideal.coerceAtMost(minOf(3f, zoomMax)).coerceAtLeast(1f)
        val snapped = (capped * 2f).roundToInt() / 2f
        if (snapped >= currentZoom * 1.2f || snapped <= currentZoom * 0.8f) return snapped
        return null
    }

    private fun evFromLuma(luma: Float?): Int? {
        if (luma == null) return null
        return when {
            luma < 70f -> 3      // +1.0 EV
            luma < 100f -> 2     // +0.67 EV
            luma < 120f -> 1     // +0.33 EV
            luma > 210f -> -2    // -0.67 EV
            luma > 185f -> -1    // -0.33 EV
            else -> 0
        }
    }

    /** 连续 4 帧一致才翻转，避免提示闪烁 */
    private fun settle(id: String, ok: Boolean): Boolean {
        val c = counters[id] ?: 0
        val n = if (ok) (c + 1).coerceAtMost(STABLE_FRAMES) else (c - 1).coerceAtLeast(0)
        counters[id] = n
        return n >= STABLE_FRAMES
    }

    companion object {
        private const val STABLE_FRAMES = 4

        /** 颅顶相对「眼睛→脚踝」跨度的补偿比例，见 update() 内注释 */
        private const val HEAD_OFFSET_RATIO = 0.067f

        // 环境人像目标（PRD 3.2）
        private const val RATIO_MIN = 0.40f
        private const val RATIO_MAX = 0.70f
        private const val HEADROOM_MIN = 0.015f
        private const val HEADROOM_MAX = 0.12f
        private const val ROLL_MAX = 2.0f
        private const val TILT_MIN = -12f
        private const val TILT_MAX = 12f
    }
}
