package com.zerone.photowise.guidance

import com.zerone.photowise.inference.Lm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 引导规则单测：不依赖 Android，验证「一次只给一件事」与抖动抑制。
 */
class GuidanceEngineTest {

    private lateinit var engine: GuidanceEngine

    @Before
    fun setUp() {
        engine = GuidanceEngine()
    }

    /**
     * 造一个 33 点人体：head 在 top，脚踝在 bottom，其余点均匀铺在框内。
     */
    private fun pose(
        top: Float,
        bottom: Float,
        left: Float = 0.4f,
        right: Float = 0.6f,
        visibility: Float = 0.9f
    ): List<Lm> {
        val cx = (left + right) / 2f
        return (0 until 33).map { i ->
            val t = i / 32f
            val y = top + (bottom - top) * t
            val x = if (i % 2 == 0) left else right
            Lm(x = if (i == 0) cx else x, y = y, visibility = visibility)
        }
    }

    private fun run(
        pose: List<Lm>?,
        roll: Float = 0f,
        tilt: Float = 0f,
        zoom: Float = 1f,
        times: Int = 10
    ): GuidanceState {
        var state: GuidanceState? = null
        repeat(times) {
            state = engine.update(
                pose = pose,
                rollDeg = roll,
                tiltDeg = tilt,
                zoom = zoom,
                zoomMax = 8f,
                segForeground = null,
                meanLuma = 128f
            )
        }
        return state!!
    }

    @Test
    fun `noPerson_asksToPutPersonInFrame`() {
        val s = run(pose = null)
        assertFalse(s.hasPerson)
        assertTrue(s.todos.isEmpty())
        assertTrue(s.headline.contains("放进画面"))
    }

    @Test
    fun `personTooSmall_suggestsMovingCloser`() {
        // 人只占画面 10%
        val s = run(pose(top = 0.45f, bottom = 0.55f))
        val ratio = s.todos.first { it.id == "ratio" }
        assertFalse(ratio.done)
        assertTrue(ratio.text.contains("靠近"))
    }

    @Test
    fun `personCutOff_suppressesOtherAdvice`() {
        // 脚出画：bottom > 1.0
        val s = run(pose(top = 0.10f, bottom = 1.05f))
        val cut = s.todos.first { it.id == "cut" }
        assertFalse(cut.done)
        // 「一次只给一件事」：裁切没解决前，不给占比/留白建议
        assertNull(s.todos.firstOrNull { it.id == "ratio" })
        assertNull(s.todos.firstOrNull { it.id == "headroom" })
    }

    @Test
    fun `goodFraming_marksRatioAndHeadroomDone`() {
        // 眼睛 0.06、脚踝 0.62 → 颅顶补偿后约 0.0225，占比约 60%
        val s = run(pose(top = 0.06f, bottom = 0.62f))
        assertTrue(s.todos.first { it.id == "cut" }.done)
        assertTrue(s.todos.first { it.id == "ratio" }.done)
        assertTrue(s.todos.first { it.id == "headroom" }.done)
    }

    @Test
    fun `tiltedPhone_asksToLevel`() {
        val s = run(pose(top = 0.05f, bottom = 0.60f), roll = 6f)
        val level = s.todos.first { it.id == "level" }
        assertFalse(level.done)
        assertTrue(level.text.contains("端平"))
    }

    @Test
    fun `cameraTooHigh_asksToCrouch`() {
        // tilt 明显为负 = 俯拍
        val s = run(pose(top = 0.05f, bottom = 0.60f), tilt = -30f)
        val height = s.todos.first { it.id == "height" }
        assertFalse(height.done)
        assertTrue(height.text.contains("蹲"))
    }

    @Test
    fun `stability_doesNotFlipOnSingleGoodFrame`() {
        // 先给一帧不达标，再给一帧达标：不应立刻打勾
        engine.update(pose(0.45f, 0.55f), 0f, 0f, 1f, 8f, null, 128f)
        val s = engine.update(pose(0.05f, 0.60f), 0f, 0f, 1f, 8f, null, 128f)
        val cut = s.todos.first { it.id == "cut" }
        assertFalse("单帧达标不应立即判定完成（需连续 4 帧）", cut.done)
    }

    @Test
    fun `whenAllGood_headlineSaysReady`() {
        val s = run(pose(top = 0.06f, bottom = 0.62f), roll = 0f, tilt = 0f)
        assertTrue(s.allDone)
        assertTrue(s.headline.contains("准备拍"))
    }

    @Test
    fun `suggestZoom_whenPersonSmall`() {
        val s = run(pose(top = 0.45f, bottom = 0.55f), zoom = 1f)
        assertNotNull("人明显偏小时应建议拉近", s.suggestedZoom)
        assertTrue(s.suggestedZoom!! > 1f)
        assertTrue("数码变焦上限 3x", s.suggestedZoom!! <= 3f)
    }

    @Test
    fun `suggestZoom_nullWhenFramingGood`() {
        val s = run(pose(top = 0.05f, bottom = 0.60f), zoom = 1f)
        assertNull(s.suggestedZoom)
    }

    @Test
    fun `headTopCompensation_reducesHeadroom`() {
        // 同一组关键点，脚踝可见时颅顶补偿生效，留白比「直接用眼睛位置」小
        val visible = run(pose(top = 0.20f, bottom = 0.80f))
        val expectNoComp = 0.20f
        assertTrue(
            "颅顶补偿后留白应小于关键点最高点（${visible.metrics.headroom} < $expectNoComp）",
            visible.metrics.headroom < expectNoComp
        )
    }

    @Test
    fun `exposureAdvice_fromLuma`() {
        val dark = engine.update(pose(0.05f, 0.60f), 0f, 0f, 1f, 8f, null, 40f)
        assertTrue("偏暗应建议加曝光", (dark.suggestedEvIndex ?: 0) > 0)

        val bright = engine.update(pose(0.05f, 0.60f), 0f, 0f, 1f, 8f, null, 230f)
        assertTrue("过亮应建议减曝光", (bright.suggestedEvIndex ?: 0) < 0)

        assertEquals(0, engine.update(pose(0.05f, 0.60f), 0f, 0f, 1f, 8f, null, 140f).suggestedEvIndex)
    }
}
