package com.zerone.photowise.inference

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/** 单个关键点：归一化坐标 0..1，原点在左上角 */
data class Lm(val x: Float, val y: Float, val visibility: Float)

/**
 * MediaPipe Pose Landmarker（lite）。
 *
 * 使用 LIVE_STREAM 模式，内部维护时序跟踪，帧间可复用上一帧结果，
 * 因此实际耗时低于单帧全量推理。
 */
class PoseEstimator(
    context: Context,
    private val onResult: (List<Lm>?) -> Unit,
    private val onError: (String) -> Unit
) {
    private val landmarker: PoseLandmarker

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL)
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> dispatch(result) }
            .setErrorListener { e -> onError(e.message ?: "pose error") }
            .build()

        landmarker = PoseLandmarker.createFromOptions(context, options)
    }

    private fun dispatch(result: PoseLandmarkerResult) {
        val landmarks = result.landmarks().firstOrNull()
        if (landmarks == null || landmarks.isEmpty()) {
            onResult(null)
            return
        }
        val out = ArrayList<Lm>(landmarks.size)
        for (lm in landmarks) {
            out.add(Lm(lm.x(), lm.y(), lm.visibility().orElse(1.0f)))
        }
        onResult(out)
    }

    /** 提交一帧。调用方保证同一时刻只有一帧在飞行中。 */
    fun submit(bitmap: Bitmap, timestampMs: Long) {
        runCatching {
            val image = BitmapImageBuilder(bitmap).build()
            landmarker.detectAsync(image, timestampMs)
        }.onFailure { onError(it.message ?: "pose submit failed") }
    }

    fun close() {
        runCatching { landmarker.close() }
    }

    companion object {
        const val MODEL = "pose_landmarker_lite.task"

        // 33 点骨骼连接（只画上身+下肢主干，够用且不杂乱）
        val CONNECTIONS = arrayOf(
            11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
            11 to 23, 12 to 24, 23 to 24,
            23 to 25, 25 to 27, 24 to 26, 26 to 28,
            27 to 29, 29 to 31, 28 to 30, 30 to 32,
            0 to 11, 0 to 12
        )
    }
}
