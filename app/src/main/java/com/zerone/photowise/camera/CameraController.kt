package com.zerone.photowise.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX 封装：预览 + 逐帧分析 + 变焦/曝光控制。
 *
 * 设计要点：
 * 1. ImageAnalysis 直接输出 RGBA_8888，跳过 YUV 转换（省一次全图 CPU 拷贝）。
 * 2. 单线程分析器 + KEEP_ONLY_LATEST，保证不会排队堆积。
 * 3. 复用两块 Bitmap（源 + 旋转后），避免每帧分配造成的 GC 抖动。
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrame: (Bitmap) -> Unit
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null

    private var srcBitmap: Bitmap? = null
    private var dstBitmap: Bitmap? = null
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    var zoomMin = 1f
        private set
    var zoomMax = 1f
        private set
    var exposureMin = 0
        private set
    var exposureMax = 0
        private set
    var frameWidth = 0
        private set
    var frameHeight = 0
        private set

    fun start() {
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching { bind(future.get()) }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind(provider: ProcessCameraProvider) {
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val selector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(640, 480),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val ia = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setResolutionSelector(selector)
            .build()

        ia.setAnalyzer(executor) { proxy -> analyze(proxy) }

        camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            ia
        )
        analysis = ia

        camera?.let { c ->
            val zs = c.cameraInfo.zoomState.value
            if (zs != null) {
                zoomMin = zs.minZoomRatio
                zoomMax = zs.maxZoomRatio
            }
            val es = c.cameraInfo.exposureState
            exposureMin = es.exposureCompensationRange.lower
            exposureMax = es.exposureCompensationRange.upper
        }
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val w = proxy.width
            val h = proxy.height
            val plane = proxy.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            // RGBA_8888 输出时行可能有 padding，需要按 padded 宽度建源图
            val rowPadding = rowStride - pixelStride * w
            val paddedW = w + rowPadding / pixelStride

            var src = srcBitmap
            if (src == null || src.width != paddedW || src.height != h) {
                src = Bitmap.createBitmap(paddedW, h, Bitmap.Config.ARGB_8888)
                srcBitmap = src
            }
            plane.buffer.rewind()
            src.copyPixelsFromBuffer(plane.buffer)

            val rot = proxy.imageInfo.rotationDegrees
            val swap = rot % 180 != 0
            val dw = if (swap) h else w
            val dh = if (swap) w else h

            var dst = dstBitmap
            if (dst == null || dst.width != dw || dst.height != dh) {
                dst = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
                dstBitmap = dst
            }

            val canvas = Canvas(dst)
            canvas.save()
            when (rot) {
                90 -> {
                    canvas.translate(dw.toFloat(), 0f)
                    canvas.rotate(90f)
                }
                180 -> {
                    canvas.translate(dw.toFloat(), dh.toFloat())
                    canvas.rotate(180f)
                }
                270 -> {
                    canvas.translate(0f, dh.toFloat())
                    canvas.rotate(270f)
                }
            }
            // 只绘制可见区域，丢弃行 padding
            canvas.drawBitmap(src, Rect(0, 0, w, h), Rect(0, 0, w, h), drawPaint)
            canvas.restore()

            frameWidth = dw
            frameHeight = dh
            onFrame(dst)
        } catch (_: Throwable) {
            // 单帧失败不影响整体流程
        } finally {
            proxy.close()
        }
    }

    /** 读取相机当前真实变焦比（可能因设备裁剪策略与设定值不同） */
    fun liveZoom(): Float =
        camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: zoomMin

    fun setZoom(ratio: Float) {
        val c = camera ?: return
        val target = ratio.coerceIn(zoomMin, zoomMax)
        ContextCompat.getMainExecutor(context).execute {
            runCatching { c.cameraControl.setZoomRatio(target) }
        }
    }

    fun setExposureIndex(index: Int) {
        val c = camera ?: return
        val target = index.coerceIn(exposureMin, exposureMax)
        ContextCompat.getMainExecutor(context).execute {
            runCatching { c.cameraControl.setExposureCompensationIndex(target) }
        }
    }

    fun stop() {
        analysis?.clearAnalyzer()
        analysis = null
        camera = null
    }
}
