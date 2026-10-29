package com.juani.videoshrink

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class VideoInfo(
    val uri: Uri,
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Float,
    val totalBitrate: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val mime: String,
    val dateMs: Long?
)

enum class Quality(
    val label: String,
    val desc: String,
    val bpp: Double,          // bits por pixel por cuadro (tope de bitrate en HEVC)
    val factorAvc: Double,    // fracción del bitrate original si el origen es H.264
    val factorHevc: Double    // idem si el origen ya es H.265
) {
    MAX("Máxima calidad", "La diferencia casi no se nota a simple vista", 0.11, 0.70, 0.90),
    BALANCED("Equilibrada", "Muy buena calidad y bastante más liviano", 0.075, 0.55, 0.75),
    SMALL("Más liviana", "Buena calidad, pensada para compartir", 0.05, 0.40, 0.60)
}

enum class MaxRes(val label: String, val shortSide: Int?) {
    ORIGINAL("Original", null),
    P1080("Hasta 1080p", 1080),
    P720("Hasta 720p", 720)
}

data class Plan(
    val bitrate: Long,
    val outHeight: Int?,      // null = no cambiar resolución
    val mime: String,
    val estimatedBytes: Long
)

object Planner {
    private const val AUDIO_BR = 128_000L

    val hevcAvailable: Boolean by lazy {
        try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
                ci.isEncoder && ci.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun even(v: Double): Int = (v / 2.0).roundToInt() * 2

    /** Devuelve null si recomprimir no vale la pena (ahorro menor al 15%). */
    fun plan(info: VideoInfo, q: Quality, res: MaxRes): Plan? {
        var outW = info.width
        var outH = info.height
        var outHeightParam: Int? = null

        val target = res.shortSide
        val shortSide = min(info.width, info.height)
        if (target != null && shortSide > target) {
            val scale = target.toDouble() / shortSide
            outW = even(info.width * scale)
            outH = even(info.height * scale)
            outHeightParam = outH
        }

        val srcVideoBr = max(info.totalBitrate - AUDIO_BR, (info.totalBitrate * 0.85).toLong())
        val useHevc = hevcAvailable
        val srcHevc = info.mime.equals("video/hevc", ignoreCase = true)

        val pixelRate = outW.toDouble() * outH * info.fps
        val cap = pixelRate * q.bpp * (if (useHevc) 1.0 else 1.6)
        val rel = srcVideoBr * (if (srcHevc) q.factorHevc else q.factorAvc)

        val br = max(min(cap, rel), 500_000.0)
        if (br > srcVideoBr * 0.85) return null

        val est = ((br + AUDIO_BR) * info.durationMs / 8000.0).toLong()
        return Plan(
            bitrate = br.toLong(),
            outHeight = outHeightParam,
            mime = if (useHevc) "video/hevc" else "video/avc",
            estimatedBytes = est
        )
    }
}

fun readInfo(context: Context, uri: Uri): VideoInfo? {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, uri)
        fun meta(k: Int): String? = r.extractMetadata(k)

        var w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        var h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        val rot = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) {
            val t = w; w = h; h = t
        }
        val dur = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        if (dur <= 0L) return null

        val frames = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
        val fps = if (frames > 0) (frames * 1000f / dur).coerceIn(10f, 120f) else 30f

        val (name, size) = queryNameSize(context, uri)
        val total = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
            ?: if (size > 0) size * 8000 / dur else 0L
        val realSize = if (size > 0) size else total * dur / 8000
        if (total <= 0L || realSize <= 0L) return null

        return VideoInfo(
            uri = uri,
            name = name,
            width = w,
            height = h,
            fps = fps,
            totalBitrate = total,
            durationMs = dur,
            sizeBytes = realSize,
            mime = videoMime(context, uri),
            dateMs = parseDate(meta(MediaMetadataRetriever.METADATA_KEY_DATE))
        )
    } catch (e: Exception) {
        return null
    } finally {
        r.release()
    }
}

private fun videoMime(context: Context, uri: Uri): String {
    val ex = MediaExtractor()
    return try {
        ex.setDataSource(context, uri, null)
        (0 until ex.trackCount)
            .map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "" }
            .firstOrNull { it.startsWith("video/") } ?: "video/avc"
    } catch (e: Exception) {
        "video/avc"
    } finally {
        ex.release()
    }
}

private fun queryNameSize(context: Context, uri: Uri): Pair<String, Long> {
    var name = "video"
    var size = 0L
    try {
        val cols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
    } catch (_: Exception) {
    }
    return name to size
}

private fun parseDate(s: String?): Long? {
    if (s.isNullOrBlank() || s.length < 15) return null
    return try {
        val f = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        val t = f.parse(s.take(15))?.time
        if (t != null && t > 946_684_800_000L) t else null
    } catch (e: Exception) {
        null
    }
}

fun fmtSize(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 1024) String.format(Locale.US, "%.2f GB", mb / 1024)
    else String.format(Locale.US, "%.1f MB", mb)
}

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
