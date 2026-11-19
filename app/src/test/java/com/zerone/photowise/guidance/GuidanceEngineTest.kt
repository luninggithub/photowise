package com.zerone.photowise.guidance

import com.zerone.photowise.inference.Lm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GuidanceEngineTest {

    private lateinit var engine: GuidanceEngine

    @Before
    fun setUp() {
        engine = GuidanceEngine()
    }

    private fun pose(
        top: Float,
        bottom: Float,
        left: Float = 0.4f,
        right: Float = 0.6f,
        visibility: Float = 0.9f,
        profile: Boolean = false
    ): List<Lm> {
        val cx = (left + right) / 2f
        return (0 until 33).map { i ->
            val t = i / 32f
            val y = top + (bottom - top) * t
            val x = when {
                profile -> cx + if (i % 2 == 0) -0.02f else 0.02f
                i == 0 -> cx
                i % 2 == 0 -> left
                else -> right
            }
            Lm(x = x, y = y, visibility = visibility)
        }
    }

    private fun run(
        pose: List<Lm>?,
        roll: Float = 0f,
        tilt: Float = 12f,
        zoom: Float = 1f,
        face: Float? = 140f,
        bg: Float? = 140f,
        plan: ShotPlan? = ShotPlan.ENV,
        times: Int = 10
    ): GuidanceState {
        engine.setPlan(plan)
        var state: GuidanceState? = null
        repeat(times) {
            state = engine.update(pose, roll, tilt, zoom, 8f, null, face, bg)
        }
        return state!!
    }

    @Test
    fun `noPlan_doesNotSayReady`() {
        val s = run(pose(0.12f, 0.46f, 0.60f, 0.74f, profile = true), plan = null, tilt = 12f)
        assertFalse(s.allDone)
        assertTrue(s.headline.contains("拍法"))
        assertTrue(s.todos.isEmpty())
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
        val s = run(pose(top = 0.45f, bottom = 0.55f))
        val ratio = s.todos.first { it.id == "ratio" }
        assertFalse(ratio.done)
        assertTrue(ratio.text.contains("靠近"))
    }

    @Test
    fun `personCutOff_suppressesOtherAdvice`() {
        val s = run(pose(top = 0.10f, bottom = 1.05f))
        val cut = s.todos.first { it.id == "cut" }
        assertFalse(cut.done)
        assertNull(s.todos.firstOrNull { it.id == "ratio" })
        assertNull(s.todos.firstOrNull { it.id == "headroom" })
        assertNull(s.todos.firstOrNull { it.id == "pose" })
    }

    @Test
    fun `halfBody_allowsFeetOutOfFrame`() {
        val s = run(pose(top = 0.08f, bottom = 1.05f, left = 0.35f, right = 0.65f), plan = ShotPlan.HALF, tilt = 0f)
        assertTrue(s.todos.first { it.id == "cut" }.done)
    }

    @Test
    fun `centeredPerson_isAskedToMoveAside`() {
        val s = run(pose(top = 0.12f, bottom = 0.46f, left = 0.40f, right = 0.60f))
        val pos = s.todos.first { it.id == "pos" }
        assertFalse(pos.done)
        assertTrue(pos.text.contains("往右"))
    }

    @Test
    fun `goodFraming_marksRatioAndHeadroomDone`() {
        val s = run(pose(top = 0.12f, bottom = 0.46f, left = 0.60f, right = 0.74f, profile = true))
        assertTrue(s.todos.first { it.id == "cut" }.done)
        assertTrue(s.todos.first { it.id == "ratio" }.done)
        assertTrue(s.todos.first { it.id == "headroom" }.done)
    }

    @Test
    fun `tiltedPhone_asksToLevel`() {
        val s = run(pose(top = 0.12f, bottom = 0.46f, left = 0.60f, right = 0.74f, profile = true), roll = 6f)
        val level = s.todos.first { it.id == "level" }
        assertFalse(level.done)
        assertTrue(level.text.contains("端平"))
    }

    @Test
    fun `cameraTooHigh_asksToCrouch`() {
        val s = run(pose(top = 0.12f, bottom = 0.46f), tilt = -30f)
        val height = s.todos.first { it.id == "height" }
        assertFalse(height.done)
        assertTrue(height.text.contains("蹲"))
    }

    @Test
    fun `stability_doesNotFlipOnSingleGoodFrame`() {
        engine.setPlan(ShotPlan.ENV)
        engine.update(pose(0.45f, 0.55f), 0f, 12f, 1f, 8f, null, 140f, 140f)
        val s = engine.update(
            pose(0.12f, 0.46f, 0.60f, 0.74f, profile = true),
            0f, 12f, 1f, 8f, null, 140f, 140f
        )
        assertFalse(s.todos.first { it.id == "cut" }.done)
    }

    @Test
    fun `whenAllGood_headlineSaysReady`() {
        val s = run(
            pose(top = 0.12f, bottom = 0.46f, left = 0.60f, right = 0.74f, profile = true),
            roll = 0f,
            tilt = 12f
        )
        assertTrue(s.allDone)
        assertTrue(s.headline.contains("可以拍了"))
        assertEquals(Audience.SUBJECT, s.todos.first { it.id == "pose" }.audience)
    }

    @Test
    fun `suggestZoom_whenPersonSmall`() {
        val s = run(pose(top = 0.45f, bottom = 0.55f), zoom = 1f)
        assertNotNull(s.suggestedZoom)
        assertTrue(s.suggestedZoom!! > 1f)
        assertTrue(s.suggestedZoom!! <= 2f)
    }

    @Test
    fun `suggestZoom_nullWhenFramingGood`() {
        val s = run(pose(top = 0.12f, bottom = 0.46f, left = 0.60f, right = 0.74f, profile = true), zoom = 1f)
        assertNull(s.suggestedZoom)
    }

    @Test
    fun `headTopCompensation_reducesHeadroom`() {
        val visible = run(pose(top = 0.20f, bottom = 0.80f))
        assertTrue(visible.metrics.headroom < 0.20f)
    }

    @Test
    fun `exposureAdvice_fromFaceAndBackground`() {
        engine.setPlan(ShotPlan.ENV)
        val dark = engine.update(pose(0.12f, 0.46f), 0f, 12f, 1f, 8f, null, 40f, 50f)
        assertTrue((dark.suggestedEvIndex ?: 0) > 0)

        val bright = engine.update(pose(0.12f, 0.46f), 0f, 12f, 1f, 8f, null, 210f, 200f)
        assertTrue((bright.suggestedEvIndex ?: 0) < 0)

        val ok = engine.update(pose(0.12f, 0.46f), 0f, 12f, 1f, 8f, null, 140f, 140f)
        assertEquals(0, ok.suggestedEvIndex)

        val back = engine.update(pose(0.12f, 0.46f), 0f, 12f, 1f, 8f, null, 70f, 180f)
        assertTrue(back.todos.first { it.id == "exposure" }.text.contains("逆光"))
    }
}
