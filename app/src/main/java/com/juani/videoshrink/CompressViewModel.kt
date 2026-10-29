package com.juani.videoshrink

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class Status { PENDING, WORKING, DONE, SKIPPED, ERROR }

data class Job(
    val id: Long,
    val info: VideoInfo,
    val status: Status = Status.PENDING,
    val progress: Float = 0f,
    val outBytes: Long = 0L,
    val message: String? = null
)

@OptIn(UnstableApi::class)
class CompressViewModel(private val app: Application) : AndroidViewModel(app) {

    var jobs by mutableStateOf<List<Job>>(emptyList())
        private set
    var quality by mutableStateOf(Quality.BALANCED)
    var maxRes by mutableStateOf(MaxRes.ORIGINAL)
    var running by mutableStateOf(false)
        private set

    private var nextId = 1L
    private var transformer: Transformer? = null
    private var currentId = -1L
    private var currentOut: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()

    private val poll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                val p = progressHolder.progress / 100f
                updateJob(currentId) { it.copy(progress = p) }
            }
            handler.postDelayed(this, 400)
        }
    }

    private fun updateJob(id: Long, f: (Job) -> Job) {
        jobs = jobs.map { if (it.id == id) f(it) else it }
    }

    fun addVideos(uris: List<android.net.Uri>) {
        viewModelScope.launch {
            val infos = withContext(Dispatchers.IO) { uris.mapNotNull { readInfo(app, it) } }
            val fresh = infos.filter { i -> jobs.none { it.info.uri == i.uri } }
            jobs = jobs + fresh.map { Job(id = nextId++, info = it) }
        }
    }

    fun remove(id: Long) {
        jobs = jobs.filter { it.id != id }
    }

    fun clearFinished() {
        jobs = jobs.filter { it.status == Status.PENDING || it.status == Status.WORKING }
    }

    fun start() {
        if (running) return
        if (jobs.none { it.status == Status.PENDING }) return
        running = true
        ContextCompat.startForegroundService(app, Intent(app, KeepAliveService::class.java))
        processNext()
    }

    fun cancel() {
        handler.removeCallbacks(poll)
        transformer?.cancel()
        transformer = null
        currentOut?.delete()
        currentOut = null
        updateJob(currentId) {
            if (it.status == Status.WORKING) it.copy(status = Status.PENDING, progress = 0f) else it
        }
        stopAll()
    }

    private fun stopAll() {
        running = false
        app.stopService(Intent(app, KeepAliveService::class.java))
    }

    private fun processNext() {
        val job = jobs.firstOrNull { it.status == Status.PENDING }
        if (job == null) {
            stopAll()
            return
        }
        val plan = Planner.plan(job.info, quality, maxRes)
        if (plan == null) {
            updateJob(job.id) {
                it.copy(
                    status = Status.SKIPPED,
                    message = "Ya está bastante optimizado: recomprimirlo casi no ahorra espacio y solo perdería calidad."
                )
            }
            processNext()
            return
        }

        val out = File(app.cacheDir, "vs_${job.id}_${System.currentTimeMillis()}.mp4")
        currentId = job.id
        currentOut = out
        updateJob(job.id) { it.copy(status = Status.WORKING, progress = 0f) }

        try {
            val encoderFactory = DefaultEncoderFactory.Builder(app)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate(plan.bitrate.toInt()).build()
                )
                .setEnableFallback(true)
                .build()

            val t = Transformer.Builder(app)
                .setVideoMimeType(plan.mime)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        onExportDone(job, out)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        onExportFailed(job, out, exportException)
                    }
                })
                .build()

            val outHeight = plan.outHeight
            val effects = if (outHeight != null) {
                Effects(emptyList<AudioProcessor>(), listOf<Effect>(Presentation.createForHeight(outHeight)))
            } else {
                Effects.EMPTY
            }
            val item = EditedMediaItem.Builder(MediaItem.fromUri(job.info.uri))
                .setEffects(effects)
                .build()

            transformer = t
            t.start(item, out.absolutePath)
            handler.postDelayed(poll, 400)
        } catch (e: Exception) {
            onExportFailed(job, out, e)
        }
    }

    private fun onExportDone(job: Job, out: File) {
        handler.removeCallbacks(poll)
        transformer = null
        viewModelScope.launch {
            val outSize = out.length()
            if (outSize <= 0L || outSize >= job.info.sizeBytes * 0.95) {
                out.delete()
                updateJob(job.id) {
                    it.copy(
                        status = Status.SKIPPED,
                        message = "El resultado no quedó más chico que el original, así que lo descarté. No toqué nada."
                    )
                }
            } else {
                val ok = withContext(Dispatchers.IO) { runCatching { saveToGallery(out, job.info) }.isSuccess }
                out.delete()
                if (ok) {
                    updateJob(job.id) { it.copy(status = Status.DONE, progress = 1f, outBytes = outSize) }
                } else {
                    updateJob(job.id) {
                        it.copy(status = Status.ERROR, message = "Se comprimió, pero no pude guardar el archivo en Películas.")
                    }
                }
            }
            currentOut = null
            if (running) processNext()
        }
    }

    private fun onExportFailed(job: Job, out: File, e: Exception) {
        handler.removeCallbacks(poll)
        transformer = null
        out.delete()
        currentOut = null
        updateJob(job.id) {
            it.copy(status = Status.ERROR, message = "No se pudo comprimir (${e.message ?: "error desconocido"}).")
        }
        if (running) processNext()
    }

    private fun saveToGallery(file: File, info: VideoInfo) {
        val base = info.name.substringBeforeLast('.', info.name)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${base}_comprimido.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoShrink")
            put(MediaStore.Video.Media.IS_PENDING, 1)
            info.dateMs?.let { put(MediaStore.Video.Media.DATE_TAKEN, it) }
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("No se pudo crear el archivo")
        try {
            val os = resolver.openOutputStream(uri) ?: throw IOException("No se pudo abrir el archivo")
            os.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    override fun onCleared() {
        handler.removeCallbacks(poll)
        transformer?.cancel()
        transformer = null
    }
}
