package com.zerone.photowise.guidance

/** 单帧量化指标，供 HUD 展示与规则判定 */
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
    val meanLuma: Float? = null
)

/** 待办项：一次只让用户专注 2–3 件事 */
data class TodoItem(
    val id: String,
    val text: String,
    val done: Boolean,
    val adjustableByApp: Boolean = false
)

data class GuidanceState(
    val hasPerson: Boolean,
    val metrics: FrameMetrics,
    val todos: List<TodoItem>,
    val allDone: Boolean,
    val headline: String,
    val suggestedZoom: Float?,
    val suggestedEvIndex: Int?
)
