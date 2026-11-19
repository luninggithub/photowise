package com.zerone.photowise.guidance

/** 一张事先定好的拍法。阈值和文案都从这里分叉，不再共用一套及格线。 */
enum class ShotPlan(val title: String) {
    /** 人大约占画面三分之一，站在一侧，风景留在另一侧 */
    ENV("人小景大"),
    /** 胸口以上，机位与眼睛齐平 */
    HALF("半身"),
    /** 全身、低机位、天空亮、人保持暗 */
    SILHOUETTE("剪影")
}

enum class Audience { PHOTOGRAPHER, SUBJECT }

/** 单帧量化指标，供 HUD、规则和会话日志共用 */
data class FrameMetrics(
    val subjectRatio: Float = 0f,
    val headroom: Float = 0f,
    val centerX: Float = 0.5f,
    val cutTop: Boolean = false,
    val cutBottom: Boolean = false,
    val rollDeg: Float = 0f,
    val tiltDeg: Float = 0f,
    val zoom: Float = 1f,
    val segForeground: Float? = null,
    val faceLuma: Float? = null,
    val bgLuma: Float? = null
)

data class TodoItem(
    val id: String,
    val text: String,
    val done: Boolean,
    val audience: Audience = Audience.PHOTOGRAPHER
)

data class GuidanceState(
    val plan: ShotPlan?,
    val hasPerson: Boolean,
    val metrics: FrameMetrics,
    val todos: List<TodoItem>,
    val allDone: Boolean,
    val headline: String,
    val subjectLine: String,
    val suggestedZoom: Float?,
    val suggestedEvIndex: Int?,
    val targetLeft: Float = 0f,
    val targetTop: Float = 0f,
    val targetRight: Float = 0f,
    val targetBottom: Float = 0f
)
