package com.zerone.photowise.guidance

import com.zerone.photowise.inference.Lm
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 按已选定的拍法给出下一步动作。
 *
 * 未选拍法时不宣布「可以拍了」。选了以后，人的大小、站位、手机高度、
 * 曝光都跟这张方案走。一次只把第一条未完成的动作放到 [GuidanceState.headline]。
 */
class GuidanceEngine {

    private val counters = HashMap<String, Int>()
    private var plan: ShotPlan? = null

    /** 人该站的竖线。第一次看清人之后锁定，避免人走到中间时目标来回跳。 */
    private var lockedTargetX: Float? = null

    fun reset() {
        counters.clear()
        lockedTargetX = null
    }

    fun setPlan(next: ShotPlan?) {
        if (next == plan) return
        plan = next
        counters.clear()
        lockedTargetX = null
    }

    fun currentPlan(): ShotPlan? = plan

    fun update(
        pose: List<Lm>?,
        rollDeg: Float,
        tiltDeg: Float,
        zoom: Float,
        zoomMax: Float,
        segForeground: Float?,
        faceLuma: Float?,
        bgLuma: Float?
    ): GuidanceState {
        val active = plan
        if (active == null) {
            return GuidanceState(
                plan = null,
                hasPerson = !pose.isNullOrEmpty(),
                metrics = FrameMetrics(
                    rollDeg = rollDeg, tiltDeg = tiltDeg, zoom = zoom,
                    segForeground = segForeground, faceLuma = faceLuma, bgLuma = bgLuma
                ),
                todos = emptyList(),
                allDone = false,
                headline = "先选一种拍法：人小景大、半身或剪影",
                subjectLine = "",
                suggestedZoom = null,
                suggestedEvIndex = null
            )
        }

        if (pose.isNullOrEmpty()) {
            counters.clear()
            return baseState(
                active = active,
                hasPerson = false,
                metrics = metricsOf(0f, 0f, 0.5f, false, false, rollDeg, tiltDeg, zoom, segForeground, faceLuma, bgLuma),
                todos = emptyList(),
                subjectLine = "先让人进入画面",
                headline = "把人物放进画面里",
                zoomSuggest = null,
                ev = evFor(active, faceLuma, bgLuma)
            )
        }

        val body = measureBody(pose)
        if (body == null) {
            return baseState(
                active = active,
                hasPerson = true,
                metrics = metricsOf(0f, 0f, 0.5f, false, false, rollDeg, tiltDeg, zoom, segForeground, faceLuma, bgLuma),
                todos = emptyList(),
                subjectLine = "",
                headline = "人物不够清晰，走近一点或调整角度",
                zoomSuggest = null,
                ev = evFor(active, faceLuma, bgLuma)
            )
        }

        val needFeet = active != ShotPlan.HALF
        val cutTop = body.top <= 0.002f
        val cutBottom = needFeet && body.bottom >= 0.998f
        val metrics = metricsOf(
            body.ratio, body.headroom, body.centerX, cutTop, cutBottom,
            rollDeg, tiltDeg, zoom, segForeground, faceLuma, bgLuma
        )

        if (lockedTargetX == null && active != ShotPlan.HALF) {
            lockedTargetX = when {
                body.centerX < 0.42f -> 0.33f
                body.centerX > 0.58f -> 0.67f
                else -> 0.67f
            }
        }
        val targetX = if (active == ShotPlan.HALF) 0.5f else (lockedTargetX ?: 0.67f)

        val todos = ArrayList<TodoItem>(8)

        val cutFixed = settle("cut", !cutTop && !cutBottom)
        todos += TodoItem("cut", cutText(active, cutTop, cutBottom, cutFixed), cutFixed)

        if (cutFixed) {
            val posOk = positionOk(active, body.centerX, targetX)
            val posFixed = settle("pos", posOk)
            todos += TodoItem("pos", positionText(active, body.centerX, targetX, posFixed), posFixed)

            val ratioOk = body.ratio in ratioRange(active)
            val ratioFixed = settle("ratio", ratioOk)
            todos += TodoItem("ratio", ratioText(active, body.ratio, ratioFixed), ratioFixed)

            val hrOk = body.headroom in headroomRange(active)
            val hrFixed = settle("headroom", hrOk)
            todos += TodoItem("headroom", headroomText(body.headroom, headroomRange(active), hrFixed), hrFixed)

            val turn = turnRatio(pose, body.ratio)
            val poseOk = turn == null || turn <= turnLimit(active)
            val poseFixed = settle("pose", poseOk)
            todos += TodoItem(
                "pose",
                poseText(active, poseFixed),
                poseFixed,
                Audience.SUBJECT
            )
        }

        val levelOk = abs(rollDeg) <= ROLL_MAX
        val levelFixed = settle("level", levelOk)
        todos += TodoItem("level", if (levelFixed) "手机是平的" else "你把手机端平", levelFixed)

        val tiltRange = tiltRange(active)
        val heightOk = tiltDeg in tiltRange
        val heightFixed = settle("height", heightOk)
        todos += TodoItem("height", heightText(active, tiltDeg, heightFixed), heightFixed)

        val exposureOk = exposureOk(active, faceLuma, bgLuma)
        val exposureFixed = if (faceLuma == null && active != ShotPlan.SILHOUETTE) true
        else settle("exposure", exposureOk)
        todos += TodoItem("exposure", exposureText(active, faceLuma, bgLuma, exposureFixed), exposureFixed)

        val subject = todos.firstOrNull { it.id == "pose" }?.text ?: "等画面完整后，再让他摆姿势"
        val pending = todos.firstOrNull { !it.done }
        val headline = if (pending == null) "可以拍了" else pending.text
        val zoomSuggest = suggestZoom(
            active, body.ratio, zoom, zoomMax, cutFixed && !cutBottom
        )

        return baseState(
            active, true, metrics, todos, subject, headline, zoomSuggest,
            evFor(active, faceLuma, bgLuma), targetX
        )
    }

    private fun baseState(
        active: ShotPlan,
        hasPerson: Boolean,
        metrics: FrameMetrics,
        todos: List<TodoItem>,
        subjectLine: String,
        headline: String,
        zoomSuggest: Float?,
        ev: Int?,
        targetX: Float = lockedTargetX ?: if (active == ShotPlan.HALF) 0.5f else 0.67f
    ): GuidanceState {
        val box = targetBox(active, targetX)
        val ready = todos.isNotEmpty() && todos.all { it.done }
        return GuidanceState(
            plan = active,
            hasPerson = hasPerson,
            metrics = metrics,
            todos = todos,
            allDone = ready,
            headline = if (ready) "可以拍了" else headline,
            subjectLine = subjectLine,
            suggestedZoom = zoomSuggest,
            suggestedEvIndex = ev,
            targetLeft = box[0],
            targetTop = box[1],
            targetRight = box[2],
            targetBottom = box[3]
        )
    }

    private fun measureBody(pose: List<Lm>): Body? {
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
        if (counted < 4) return null

        val ankleVisible = listOf(27, 28).any { i ->
            pose.getOrNull(i)?.let { it.visibility > 0.3f } == true
        }
        val span = rawBottom - rawTop
        val headOffset = if (ankleVisible && span > 0.05f) span * HEAD_OFFSET_RATIO else 0f
        val top = rawTop - headOffset
        val bottom = rawBottom
        val visibleTop = top.coerceIn(0f, 1f)
        val visibleBottom = bottom.coerceIn(0f, 1f)
        return Body(
            top = top,
            bottom = bottom,
            ratio = (visibleBottom - visibleTop).coerceIn(0f, 1f),
            headroom = visibleTop,
            centerX = ((minX + maxX) / 2f).coerceIn(0f, 1f)
        )
    }

    /** 肩宽 / 身高。越大越正对镜头。肩膀没看清时返回 null，不卡住拍摄。 */
    private fun turnRatio(pose: List<Lm>, bodyRatio: Float): Float? {
        if (bodyRatio < 0.05f) return null
        val l = pose.getOrNull(11) ?: return null
        val r = pose.getOrNull(12) ?: return null
        if (l.visibility < 0.4f || r.visibility < 0.4f) return null
        return abs(l.x - r.x) / bodyRatio
    }

    private fun suggestZoom(
        plan: ShotPlan,
        ratio: Float,
        currentZoom: Float,
        zoomMax: Float,
        allow: Boolean
    ): Float? {
        if (!allow || ratio < 0.05f) return null
        if (plan == ShotPlan.SILHOUETTE) {
            return if (currentZoom > 1.15f) 1f else null
        }
        val range = ratioRange(plan)
        val minR = range.start
        val maxR = range.endInclusive
        if (ratio in minR..maxR && currentZoom > 1.3f) return 1f
        if (ratio >= minR) return null
        val cap = minOf(if (plan == ShotPlan.ENV) 2f else 2.5f, zoomMax)
        val ideal = currentZoom * (((minR + maxR) / 2f) / ratio)
        val snapped = ((ideal.coerceIn(1f, cap)) * 2f).roundToInt() / 2f
        if (snapped >= currentZoom * 1.2f) return snapped
        return null
    }

    private fun evFor(plan: ShotPlan, face: Float?, bg: Float?): Int? {
        if (plan == ShotPlan.SILHOUETTE) {
            val sky = bg ?: face ?: return null
            return when {
                sky < 130f -> 2
                sky < 160f -> 1
                sky > 220f -> -1
                else -> 0
            }
        }
        val f = face ?: return null
        val b = bg
        if (b != null && f < 95f && b > f * 1.45f) {
            return if (b > 190f) 1 else 2
        }
        return when {
            f < 75f -> 3
            f < 100f -> 2
            f < 120f -> 1
            f > 200f -> -2
            f > 175f -> -1
            else -> 0
        }
    }

    private fun exposureOk(plan: ShotPlan, face: Float?, bg: Float?): Boolean {
        if (plan == ShotPlan.SILHOUETTE) {
            val sky = bg ?: return false
            val faceDark = face == null || face < sky * 0.85f
            return sky in 150f..230f && faceDark
        }
        val f = face ?: return true
        val b = bg
        if (b != null && f < 95f && b > f * 1.45f) return false
        return f in 110f..175f
    }

    private fun exposureText(plan: ShotPlan, face: Float?, bg: Float?, done: Boolean): String {
        if (done) return if (plan == ShotPlan.SILHOUETTE) "天空和人的明暗合适" else "脸的亮度合适"
        if (plan == ShotPlan.SILHOUETTE) return "你把天空拍亮，人保持暗，然后点应用"
        val f = face
        val b = bg
        if (f != null && b != null && f < 95f && b > f * 1.45f) {
            return "他站在逆光里，换到侧面让光照到脸上"
        }
        if (f != null && f > 175f) return "你点应用，把脸的曝光减一点"
        return "你点应用，把脸的曝光加一点"
    }

    private fun cutText(plan: ShotPlan, cutTop: Boolean, cutBottom: Boolean, done: Boolean): String {
        if (done) return if (plan == ShotPlan.HALF) "头在画面里" else "人物已完整入镜"
        return when {
            cutTop && cutBottom -> "你退后一点，头和脚都留在画面里"
            cutBottom -> "你退后一点，别把他的脚切掉"
            else -> "你退后一点，头顶留点空"
        }
    }

    private fun positionOk(plan: ShotPlan, centerX: Float, targetX: Float): Boolean {
        if (plan == ShotPlan.HALF) return centerX in 0.30f..0.70f
        return abs(centerX - targetX) <= 0.09f
    }

    private fun positionText(plan: ShotPlan, centerX: Float, targetX: Float, done: Boolean): String {
        if (done) return "人的左右位置合适"
        if (plan == ShotPlan.HALF) {
            return if (centerX < 0.30f) "你让他往中间靠一点，现在太贴左边"
            else "你让他往中间靠一点，现在太贴右边"
        }
        val side = if (targetX < 0.5f) "左边" else "右边"
        val other = if (targetX < 0.5f) "右边" else "左边"
        val move = if (centerX < targetX) "往右" else "往左"
        return "你让他${move}站一点，站到${side}那条线上，风景留在${other}"
    }

    private fun ratioText(plan: ShotPlan, ratio: Float, done: Boolean): String {
        if (done) return "人物大小刚好"
        val tooSmall = ratio < ratioRange(plan).start
        return when (plan) {
            ShotPlan.ENV -> if (tooSmall) "你靠近一点，让他大约占画面三分之一，先别拉变焦"
            else "你退后一点，人只要占画面三分之一"
            ShotPlan.HALF -> if (tooSmall) "你靠近一点，拍到他胸口以上"
            else "你稍退一点，头顶和腰都留在画面里"
            ShotPlan.SILHOUETTE -> if (tooSmall) "你靠近一点，让全身都在画面里"
            else "你退后一点，给天空留出位置"
        }
    }

    private fun headroomText(headroom: Float, range: ClosedFloatingPointRange<Float>, done: Boolean): String {
        if (done) return "头顶留白刚好"
        return if (headroom < range.start) "你把镜头抬高一点，头顶别贴边"
        else "你把镜头压低一点，头顶空太多了"
    }

    private fun poseText(plan: ShotPlan, done: Boolean): String = when (plan) {
        ShotPlan.ENV -> if (done) "侧身看向风景了" else "让他侧过身，看向风景"
        ShotPlan.HALF -> if (done) "肩膀已经转开" else "让他肩膀转开，别正对镜头"
        ShotPlan.SILHOUETTE -> if (done) "侧脸轮廓出来了" else "让他侧过脸，手脚和身体分开"
    }

    private fun heightText(plan: ShotPlan, tilt: Float, done: Boolean): String {
        if (done) return when (plan) {
            ShotPlan.ENV -> "机位在他腰附近"
            ShotPlan.HALF -> "机位和眼睛齐平"
            ShotPlan.SILHOUETTE -> "低机位合适"
        }
        val range = tiltRange(plan)
        return when (plan) {
            ShotPlan.ENV -> if (tilt < range.start) "你再蹲一点，镜头对着他的腰"
            else "你把手机抬高一点，别仰得太过"
            ShotPlan.HALF -> if (tilt < range.start) "你蹲下来一点，别从上往下拍"
            else "你把手机抬到他眼睛的高度"
            ShotPlan.SILHOUETTE -> if (tilt < range.start) "你把手机放低，对着天空"
            else "你再抬高一点，别贴着地面"
        }
    }

    private fun ratioRange(plan: ShotPlan): ClosedFloatingPointRange<Float> = when (plan) {
        ShotPlan.ENV -> 0.28f..0.45f
        ShotPlan.HALF -> 0.55f..0.85f
        ShotPlan.SILHOUETTE -> 0.32f..0.62f
    }

    private fun headroomRange(plan: ShotPlan): ClosedFloatingPointRange<Float> = when (plan) {
        ShotPlan.ENV -> 0.03f..0.14f
        ShotPlan.HALF -> 0.02f..0.10f
        ShotPlan.SILHOUETTE -> 0.04f..0.16f
    }

    private fun tiltRange(plan: ShotPlan): ClosedFloatingPointRange<Float> = when (plan) {
        ShotPlan.ENV -> 6f..20f
        ShotPlan.HALF -> -6f..8f
        ShotPlan.SILHOUETTE -> 8f..28f
    }

    private fun turnLimit(plan: ShotPlan): Float = when (plan) {
        ShotPlan.ENV -> 0.28f
        ShotPlan.HALF -> 0.42f
        ShotPlan.SILHOUETTE -> 0.30f
    }

    private fun targetBox(plan: ShotPlan, targetX: Float): FloatArray = when (plan) {
        ShotPlan.ENV -> floatArrayOf(targetX - 0.12f, 0.08f, targetX + 0.12f, 0.48f)
        ShotPlan.HALF -> floatArrayOf(0.28f, 0.04f, 0.72f, 0.82f)
        ShotPlan.SILHOUETTE -> floatArrayOf(targetX - 0.14f, 0.05f, targetX + 0.14f, 0.78f)
    }

    private fun metricsOf(
        ratio: Float,
        headroom: Float,
        centerX: Float,
        cutTop: Boolean,
        cutBottom: Boolean,
        rollDeg: Float,
        tiltDeg: Float,
        zoom: Float,
        seg: Float?,
        face: Float?,
        bg: Float?
    ) = FrameMetrics(
        subjectRatio = ratio,
        headroom = headroom,
        centerX = centerX,
        cutTop = cutTop,
        cutBottom = cutBottom,
        rollDeg = rollDeg,
        tiltDeg = tiltDeg,
        zoom = zoom,
        segForeground = seg,
        faceLuma = face,
        bgLuma = bg
    )

    /** 连续 4 帧一致才翻转，避免提示闪烁 */
    private fun settle(id: String, ok: Boolean): Boolean {
        val c = counters[id] ?: 0
        val n = if (ok) (c + 1).coerceAtMost(STABLE_FRAMES) else (c - 1).coerceAtLeast(0)
        counters[id] = n
        return n >= STABLE_FRAMES
    }

    private data class Body(
        val top: Float,
        val bottom: Float,
        val ratio: Float,
        val headroom: Float,
        val centerX: Float
    )

    companion object {
        private const val STABLE_FRAMES = 4
        private const val HEAD_OFFSET_RATIO = 0.067f
        private const val ROLL_MAX = 2.0f
    }
}
