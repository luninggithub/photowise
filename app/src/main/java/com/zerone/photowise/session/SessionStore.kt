package com.zerone.photowise.session

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.zerone.photowise.guidance.GuidanceState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 一次打开 App 对应一个会话目录，方便整包导出：
 *
 * adb pull /sdcard/Android/data/com.zerone.photowise/files/sessions
 *
 * 目录里有 events.jsonl，以及 shots/shot_XXX.jpg 和同名 json。
 * 照片同时写入系统相册 Pictures/PhotoWise。
 */
class SessionStore(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    val sessionId: String = stamp.format(Date())
    val dir: File = File(appContext.getExternalFilesDir(null), "sessions/$sessionId").apply {
        mkdirs()
        File(this, "shots").mkdirs()
    }
    private val events = File(dir, "events.jsonl")
    private var shotIndex = 0

    init {
        val readme = File(dir, "README.txt")
        readme.writeText(
            """
            PhotoWise 拍摄记录 $sessionId
            事件日志：events.jsonl
            快门照片：shots/shot_XXX.jpg ，旁边的 json 是按下快门时的指导状态
            相册副本：Pictures/PhotoWise

            导出到电脑：
            adb pull /sdcard/Android/data/com.zerone.photowise/files/sessions
            """.trimIndent()
        )
        write("open", null, mapOf("session" to sessionId, "dir" to dir.absolutePath))
    }

    fun sample(state: GuidanceState?) {
        write("sample", state, emptyMap())
    }

    fun event(type: String, state: GuidanceState?, extra: Map<String, String> = emptyMap()) {
        write(type, state, extra)
    }

    fun saveShot(jpeg: ByteArray, state: GuidanceState?, onDone: (String) -> Unit) {
        io.execute {
            val n = ++shotIndex
            val name = "shot_%03d.jpg".format(Locale.US, n)
            val photo = File(dir, "shots/$name")
            runCatching { photo.writeBytes(jpeg) }
            val gallery = runCatching { publishToGallery(name, jpeg) }.getOrElse { it.message ?: "gallery failed" }
            val side = linkedMapOf(
                "file" to "shots/$name",
                "bytes" to jpeg.size.toString(),
                "gallery" to gallery
            )
            write("shot", state, side)
            File(dir, "shots/${name.removeSuffix(".jpg")}.json").writeText(snapshot("shot", state, side))
            val msg = "已保存 ${photo.name}"
            main.post { onDone(msg) }
        }
    }

    fun close() {
        write("close", null, emptyMap())
        io.shutdown()
    }

    private fun publishToGallery(name: String, jpeg: ByteArray): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${sessionId}_$name")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoWise")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return "insert failed"
        resolver.openOutputStream(uri)?.use { it.write(jpeg) }
            ?: return "stream failed"
        val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return uri.toString()
    }

    private fun write(type: String, state: GuidanceState?, extra: Map<String, String>) {
        val line = snapshot(type, state, extra)
        io.execute {
            runCatching { events.appendText(line + "\n") }
        }
    }

    private fun snapshot(type: String, state: GuidanceState?, extra: Map<String, String>): String {
        val sb = StringBuilder(512)
        sb.append("{\"ts\":\"").append(iso.format(Date())).append('"')
        sb.append(",\"type\":\"").append(esc(type)).append('"')
        sb.append(",\"plan\":\"").append(state?.plan?.name ?: "").append('"')
        sb.append(",\"ready\":").append(state?.allDone == true)
        sb.append(",\"headline\":\"").append(esc(state?.headline ?: "")).append('"')
        sb.append(",\"subject\":\"").append(esc(state?.subjectLine ?: "")).append('"')
        val m = state?.metrics
        sb.append(",\"ratio\":").append(fmt(m?.subjectRatio))
        sb.append(",\"headroom\":").append(fmt(m?.headroom))
        sb.append(",\"centerX\":").append(fmt(m?.centerX))
        sb.append(",\"roll\":").append(fmt(m?.rollDeg))
        sb.append(",\"tilt\":").append(fmt(m?.tiltDeg))
        sb.append(",\"zoom\":").append(fmt(m?.zoom))
        sb.append(",\"face\":").append(fmt(m?.faceLuma))
        sb.append(",\"bg\":").append(fmt(m?.bgLuma))
        sb.append(",\"zoomSuggest\":").append(fmt(state?.suggestedZoom))
        sb.append(",\"evSuggest\":").append(state?.suggestedEvIndex?.toString() ?: "null")
        sb.append(",\"todos\":[")
        state?.todos?.forEachIndexed { i, t ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":\"").append(t.id).append("\",\"done\":").append(t.done)
            sb.append(",\"who\":\"").append(if (t.audience.name == "SUBJECT") "subject" else "photographer")
            sb.append("\",\"text\":\"").append(esc(t.text)).append("\"}")
        }
        sb.append(']')
        for ((k, v) in extra) {
            sb.append(",\"").append(esc(k)).append("\":\"").append(esc(v)).append('"')
        }
        sb.append('}')
        return sb.toString()
    }

    private fun fmt(v: Float?): String =
        if (v == null) "null" else String.format(Locale.US, "%.3f", v)

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")
}
