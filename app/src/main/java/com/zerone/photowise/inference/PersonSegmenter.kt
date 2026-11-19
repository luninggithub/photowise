package com.zerone.photowise.inference

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 人像分割（MediaPipe Selfie Segmenter）。
 *
 * **为什么必须用 MediaPipe 的运行时，而不是原生 TFLite**：
 * 该模型含自定义算子 `Convolution2DTransposeBias`，
 * 用 `org.tensorflow:tensorflow-lite` 加载会直接抛
 * `Encountered unresolved custom op`（真机已实测）。
 * MediaPipe tasks 内置的 TFLite 变体注册了这些算子，所以只能走它的封装。
 *
 * **读掩码的正确方式**：
 * `ImageSegmenterResult.confidenceMasks()` 返回的是 `MPImage`（不是 MPMask），
 * MPImage 只暴露宽高，像素必须通过 `ByteBufferExtractor.extract()` 取出，
 * 得到 float32 单通道（IMAGE_FORMAT_VEC32F1）。
 *
 * 用途：主体占比的二次校验、背景/路人占比评估。
 * 成本较高，按 1/4 帧率采样（见 MainActivity.SEG_INTERVAL）。
 */
class PersonSegmenter(
    context: Context,
    private val onResult: (Float) -> Unit,
    private val onError: (String) -> Unit
) {
    private val segmenter: ImageSegmenter
    private var logged = false

    init {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL)
            .build()

        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .setResultListener { result, _ -> dispatch(result) }
            .setErrorListener { e -> onError(e.message ?: "seg error") }
            .build()

        segmenter = ImageSegmenter.createFromOptions(context, options)
    }

    private fun dispatch(result: ImageSegmenterResult) {
        try {
            val masks = result.confidenceMasks().orElse(null)
            if (masks.isNullOrEmpty()) return

            // 单通道模型只有前景概率；多通道时取最后一个（人物）
            val mask = masks.last()

            if (!logged) {
                logged = true
                android.util.Log.i(
                    "POC",
                    "seg mask ${mask.width}x${mask.height} masks=${masks.size}"
                )
            }

            val buf: ByteBuffer = ByteBufferExtractor.extract(mask)
            onResult(foregroundRatio(buf, mask.width, mask.height))
        } catch (t: Throwable) {
            android.util.Log.e("POC", "seg dispatch failed", t)
            onError(t.message ?: "seg dispatch failed")
        }
        // 注：ImageSegmenterResult 没有 close()，官方示例也不手动释放掩码。
        // 是否泄漏由 HUD 的 PSS 曲线来验证（见 POC 验收）。
    }

    /** 抽样统计前景占比，避免遍历整张掩码 */
    private fun foregroundRatio(buf: ByteBuffer, w: Int, h: Int): Float {
        buf.rewind()
        val floats = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val stride = 8
        var hit = 0
        var total = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val idx = y * w + x
                if (idx < floats.limit() && floats.get(idx) > 0.5f) hit++
                total++
                x += stride
            }
            y += stride
        }
        return if (total == 0) 0f else hit.toFloat() / total
    }

    fun submit(bitmap: Bitmap, timestampMs: Long) {
        runCatching {
            val image = BitmapImageBuilder(bitmap).build()
            segmenter.segmentAsync(image, timestampMs)
        }.onFailure {
            android.util.Log.e("POC", "seg submit failed", it)
            onError(it.message ?: "seg submit failed")
        }
    }

    fun close() {
        runCatching { segmenter.close() }
    }

    companion object {
        const val MODEL = "selfie_segmenter.tflite"
    }
}
