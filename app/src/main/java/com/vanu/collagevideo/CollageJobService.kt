package com.vanu.collagevideo

import com.vanu.faceswap.core.*

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CancellationException

class EnhancerMissingException(val which: String) : Exception("$which enhancer missing")

/** State of the (single) video job, shared between the service and the UI. */
sealed class JobState {
    data object Idle : JobState()
    data class Running(val stage: String, val done: Int, val total: Int, val etaSeconds: Double?, val detail: String) : JobState()
    data class Done(val result: JobResult, val params: JobParams) : JobState()
    data class Failed(val message: String, val detail: String?, val offerRedownload: Boolean = false, val blocked: Boolean = false) : JobState()
    data object Cancelled : JobState()
}

object Jobs {
    private val _state = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = _state.asStateFlow()
    @Volatile var cancelRequested = false
    @Volatile var detections: DetectionCache? = null
    internal fun set(s: JobState) { _state.value = s }
    fun reset() { if (_state.value !is JobState.Running) _state.value = JobState.Idle }
    val running get() = _state.value is JobState.Running
}

/**
 * Foreground service that runs one video face-swap job, so it keeps going with the screen off or the
 * app in the background. Type mediaProcessing on Android 15+, dataSync on 10-14. Shows frame x of y
 * with an ETA and a Cancel action.
 */
class CollageJobService : Service() {
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> { Jobs.cancelRequested = true; return START_NOT_STICKY }
            ACTION_START -> {
                val p = intent.toParams()
                if (p == null || worker?.isAlive == true) return START_NOT_STICKY
                createChannel(this)
                startInForeground(progressNotification("Preparing…", 0, 0, null))
                Jobs.cancelRequested = false
                Jobs.set(JobState.Running("Preparing…", 0, 0, null, ""))
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CollageVideo:job").apply { acquire(4 * 60 * 60 * 1000L) }
                worker = Thread({ runJob(p) }, "collage-job").apply { priority = Thread.NORM_PRIORITY; start() }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(n: Notification) {
        when {
            Build.VERSION.SDK_INT >= 35 -> startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 -> startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIF_ID, n)
        }
    }

    private fun runJob(p: JobParams) {
        val final: JobState = try {
            val dir = File(filesDir, "models")
            val store = ModelStore(dir, Models.COLLAGE_REQUIRED)
            if (!store.allInstalled()) throw ModelMissingException()
            val light = ModelStore(dir, listOf(Models.ENHANCER_LIGHT))
            if (p.enhance == EnhanceMode.LIGHT && !light.allInstalled()) throw EnhancerMissingException("Light")
            if (p.enhance == EnhanceMode.HQ && !ModelStore(dir, listOf(Models.ENHANCER)).allInstalled()) throw EnhancerMissingException("HQ")
            val info = VideoProbe.probe(File(p.video))
            val srcBmp = ImageUtils.loadFile(File(p.collage))
            val det = FaceDetector(this, CollageOrder.MAX_FACES)
            val srcFaces = try { det.detectCollage(srcBmp) } finally { det.close() }
            if (srcFaces.isEmpty()) throw NoFaceException(
                "No face found in the collage photo. Try a clearer photo where the faces are not too small, covered or turned away.")
            val proc = CollageProcessor(this, store.files(), light.file(Models.ENHANCER_LIGHT), store.file(Models.SAFETY),
                store.file(Models.POSE), store.file(Models.SEG))
            val res = try { proc.run(p, info, srcBmp, srcFaces, Jobs.detections, { Jobs.detections = it }, { stage, done, total, eta ->
                val label = if (stage == 0) "Studying the people and colours" else "Turning them into the collage people"
                val detail = "Frame $done of $total" + (eta?.let { " · about ${EtaEstimator.format(it)} left" } ?: "")
                Jobs.set(JobState.Running(label, done, total, eta, detail))
                val now = System.currentTimeMillis()
                if (now - lastNotify > 1000 || done == total) { lastNotify = now; notifyProgress(label, done, total, eta) }
            }) { Jobs.cancelRequested } } finally { srcBmp.recycle() }
            JobState.Done(res, p)
        } catch (e: CancellationException) {
            JobState.Cancelled
        } catch (e: Throwable) {
            Log.e(ImageUtils.TAG, "collage job failed", e)
            errorState(e)
        }
        Jobs.set(final)
        finish(final)
    }

    private fun errorState(e: Throwable): JobState.Failed = when (e) {
        is SafetyBlockedException -> JobState.Failed("The safety filter flagged the ${e.what}, so no video was made. " +
            "Use a different ${if (e.what.startsWith("collage")) "photo" else "video or a different part of it"}.", null, blocked = true)
        is NoFaceException -> JobState.Failed(e.message ?: "No faces found.", null)
        is ModelMissingException -> JobState.Failed("The AI models are missing or incomplete. Please download them again.", null)
        is EnhancerMissingException -> JobState.Failed("The ${e.which} enhancer isn't downloaded. Download it from the Enhance setting or pick Off.", null)
        is UnsupportedVideoException -> JobState.Failed(e.message ?: "This video isn't supported.", ImageUtils.describe(e))
        is StorageException -> JobState.Failed(e.message ?: "Not enough free storage.", null)
        is OutOfMemoryError -> JobState.Failed("Not enough memory to process this video. Close other apps and try again.", ImageUtils.describe(e))
        is ai.onnxruntime.OrtException -> JobState.Failed("The AI model failed to run. If this keeps happening, re-download the models.",
            ImageUtils.describe(e), offerRedownload = true)
        is android.media.MediaCodec.CodecException -> JobState.Failed(
            "The phone's video codec stopped with an error. Try a shorter range, a lower resolution video, or restart the phone.", ImageUtils.describe(e))
        is java.io.IOException -> JobState.Failed(
            if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true) "The phone ran out of storage while writing the video."
            else "Couldn't read or write the video.", ImageUtils.describe(e))
        else -> JobState.Failed("Making the collage video failed.", ImageUtils.describe(e))
    }

    private fun finish(s: JobState) {
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        val (title, text) = when (s) {
            is JobState.Done -> "Collage video ready" to "${s.result.frames} frames in ${EtaEstimator.format(s.result.seconds)}. Tap to watch and save."
            is JobState.Failed -> (if (s.blocked) "Video not made" else "Collage video failed") to s.message
            else -> null to null
        }
        if (title != null && canNotify(this)) {
            val n = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp()).setAutoCancel(true).build()
            try { NotificationManagerCompat.from(this).notify(DONE_ID, n) } catch (e: SecurityException) { Log.w(ImageUtils.TAG, "notify", e) }
        }
        stopSelf()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun progressNotification(label: String, done: Int, total: Int, eta: Double?): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, CollageJobService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = if (total > 0) "Frame $done of $total" + (eta?.let { " · about ${EtaEstimator.format(it)} left" } ?: "") else label
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (total > 0) "$label…" else "Collage video")
            .setContentText(text)
            .setProgress(total, done, total == 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .build()
    }

    private fun notifyProgress(label: String, done: Int, total: Int, eta: Double?) {
        if (!canNotify(this)) return
        try { NotificationManagerCompat.from(this).notify(NOTIF_ID, progressNotification(label, done, total, eta)) }
        catch (e: SecurityException) { Log.w(ImageUtils.TAG, "notify", e) }
    }

    /** Android 15 time limit for dataSync / mediaProcessing (6 h): stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) { Jobs.cancelRequested = true }
    @Deprecated("API 34 variant") override fun onTimeout(startId: Int) { Jobs.cancelRequested = true }

    override fun onDestroy() {
        Jobs.cancelRequested = true
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "collage_jobs"
        const val NOTIF_ID = 42
        const val DONE_ID = 43
        const val ACTION_START = "com.vanu.collagevideo.START"
        const val ACTION_CANCEL = "com.vanu.collagevideo.CANCEL"

        fun canNotify(c: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun createChannel(c: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = c.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Collage video progress", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Progress of collage videos" })
            }
        }

        fun start(c: Context, p: JobParams) {
            val i = Intent(c, CollageJobService::class.java).setAction(ACTION_START)
                .putExtra("video", p.video).putExtra("collage", p.collage).putExtra("start", p.startUs).putExtra("end", p.endUs)
                .putExtra("fps", p.fps).putExtra("enhance", p.enhance.name).putExtra("accel", p.accelerator).putExtra("output", p.output)
                .putExtra("strict", p.strictness.name).putExtra("shift", p.shift).putExtra("repeat", p.repeat)
                .putExtra("enabled", p.enabled).putExtra("personFace", p.personFace)
                .putExtra("tFace", p.transfer.face).putExtra("tHair", p.transfer.hair).putExtra("tSkin", p.transfer.skin)
                .putExtra("tOutfit", p.transfer.outfit)
            Jobs.cancelRequested = false
            Jobs.set(JobState.Running("Starting…", 0, 0, null, ""))
            ContextCompat.startForegroundService(c, i)
        }

        fun cancel(c: Context) {
            Jobs.cancelRequested = true
            c.startService(Intent(c, CollageJobService::class.java).setAction(ACTION_CANCEL))
        }

        private fun Intent.toParams(): JobParams? {
            val v = getStringExtra("video") ?: return null; val f = getStringExtra("collage") ?: return null
            val o = getStringExtra("output") ?: return null
            return JobParams(v, f, getLongExtra("start", 0), getLongExtra("end", 0), getDoubleExtra("fps", 15.0),
                runCatching { EnhanceMode.valueOf(getStringExtra("enhance")!!) }.getOrDefault(EnhanceMode.OFF),
                getBooleanExtra("accel", false), o,
                runCatching { FilterStrictness.valueOf(getStringExtra("strict")!!) }.getOrDefault(FilterStrictness.STANDARD),
                getIntExtra("shift", 0), getBooleanExtra("repeat", true), getIntArrayExtra("enabled") ?: IntArray(0),
                getIntArrayExtra("personFace"),
                TransferOptions(getBooleanExtra("tFace", true), getBooleanExtra("tHair", true), getBooleanExtra("tSkin", true),
                    getBooleanExtra("tOutfit", true)))
        }
    }
}
