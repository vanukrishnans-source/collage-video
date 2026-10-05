package com.vanu.collagevideo

import com.vanu.faceswap.core.*

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException

data class CollageUi(
    val video: File? = null,
    val info: VideoInfo? = null,
    val thumb: Bitmap? = null,
    /** Collage with numbered faces drawn on it. */
    val collage: Bitmap? = null,
    val collageFaces: List<FaceData> = emptyList(),
    val faceThumbs: List<Bitmap> = emptyList(),
    /** Faces that are small in the collage (weaker likeness). */
    val smallFaces: Set<Int> = emptySet(),
    val enabled: Set<Int> = emptySet(),
    val startS: Float = 0f,
    val endS: Float = 0f,
    val fps: Int = 15,                 // 10, 15 or 0 = original (max 30)
    val enhance: EnhanceMode = EnhanceMode.OFF,
    val lightInstalled: Boolean = false,
    val hqInstalled: Boolean = false,
    val enhDownloading: EnhanceMode? = null,
    val enhDone: Long = 0L,
    val accelerator: Boolean = false,
    val repeat: Boolean = true,
    val strictness: FilterStrictness = FilterStrictness.STANDARD,
    val loading: String? = null,
    val error: String? = null,
    val errorDetail: String? = null,
    val toast: String? = null,
    val shift: Int = 0,
    /** Bumped whenever an input changes; the "who gets which face" editor only works on a result made from the current inputs. */
    val inputsVersion: Int = 0,
    val resultVersion: Int = -1,
    /** Pending manual assignment (collage face per person, -1 = keep original) edited on the result. */
    val edit: IntArray? = null,
    /** What the collage people give the performers: face identity, hair colour, skin tone, outfit colours. */
    val transfer: TransferOptions = TransferOptions(),
)

class CollageViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val modelsDir = File(app.filesDir, "models")
    private val lightStore = ModelStore(modelsDir, listOf(Models.ENHANCER_LIGHT))
    private val hqStore = ModelStore(modelsDir, listOf(Models.ENHANCER))
    /** First-run download: ArcFace + inswapper + safety filter + pose + parts (566 MB). */
    val models = ModelDownloadController(ModelStore(modelsDir, Models.COLLAGE_REQUIRED), viewModelScope, Models.COLLAGE_REQUIRED_BYTES)
    private val dir = File(app.cacheDir, "collage").apply { mkdirs() }
    private val _state = MutableStateFlow(CollageUi(fps = prefs.getInt("video_fps", 15), accelerator = prefs.getBoolean("xnnpack", false),
        repeat = prefs.getBoolean("repeat", true),
        transfer = TransferOptions(prefs.getBoolean("t_face", true), prefs.getBoolean("t_hair", true), prefs.getBoolean("t_skin", true),
            prefs.getBoolean("t_outfit", true)),
        strictness = runCatching { FilterStrictness.valueOf(prefs.getString("strictness", "STANDARD")!!) }.getOrDefault(FilterStrictness.STANDARD),
        enhance = runCatching { EnhanceMode.valueOf(prefs.getString("video_enhance", "OFF")!!) }.getOrDefault(EnhanceMode.OFF)))
    val state: StateFlow<CollageUi> = _state.asStateFlow()
    val job: StateFlow<JobState> = Jobs.state
    private var enhJob: Job? = null
    @Volatile private var enhPause = false
    private var collageRaw: Bitmap? = null

    init {
        models.check()
        viewModelScope.launch {
            val light = withContext(Dispatchers.IO) { lightStore.allInstalled() }
            val hq = withContext(Dispatchers.IO) { hqStore.allInstalled() }
            _state.update { it.copy(lightInstalled = light, hqInstalled = hq, enhance = when {
                it.enhance == EnhanceMode.LIGHT && !light -> EnhanceMode.OFF
                it.enhance == EnhanceMode.HQ && !hq -> EnhanceMode.OFF
                else -> it.enhance }) }
        }
    }

    private fun changed(s: CollageUi) = s.copy(inputsVersion = s.inputsVersion + 1, edit = null, shift = 0)

    fun pickVideo(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(loading = "Loading video…", error = null, errorDetail = null) }
            try {
                val (file, info, thumb) = withContext(Dispatchers.IO) {
                    dir.listFiles()?.filter { it.name.startsWith("input_") }?.forEach { it.delete() }
                    val f = File(dir, "input_${System.currentTimeMillis()}.mp4")
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw IOException("The gallery returned no data for this video.")
                    try { input.use { i -> FileOutputStream(f).use { o -> i.copyTo(o, 1 shl 20) } } }
                    catch (e: IOException) { f.delete(); throw IOException("Couldn't copy the video (is there enough free storage?).", e) }
                    val inf = VideoProbe.probe(f)
                    Triple(f, inf, VideoProbe.thumbnail(f, 0))
                }
                Jobs.detections = null
                if (!Jobs.running) Jobs.reset()
                _state.update { changed(it).copy(video = file, info = info, thumb = thumb, startS = 0f,
                    endS = minOf(info.seconds, DEFAULT_SECONDS).toFloat()) }
            } catch (e: Throwable) {
                Log.e(ImageUtils.TAG, "video load failed", e)
                _state.update { it.copy(error = (e as? UnsupportedVideoException)?.message ?: "Couldn't open the selected video.",
                    errorDetail = ImageUtils.describe(e)) }
            } finally { _state.update { it.copy(loading = null) } }
        }
    }

    fun pickCollage(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(loading = "Finding faces in the collage…", error = null, errorDetail = null) }
            try {
                val (bmp, faces) = withContext(Dispatchers.Default) {
                    val b = ImageUtils.load(getApplication(), uri, "collage")
                    FileOutputStream(File(dir, "collage.png")).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val det = FaceDetector(getApplication(), CollageOrder.MAX_FACES)
                    val f = try { det.detectCollage(b) } finally { det.close() }
                    b to f
                }
                collageRaw = bmp
                val all = faces.indices.toSet()
                val thumbs = withContext(Dispatchers.Default) { faces.map { Thumbs.cropBitmap(bmp, it) } }
                val small = faces.indices.filter { Thumbs.faceBox(faces[it]).let { b -> b[2] - b[0] } < CollageOrder.GOOD_FACE_PX }.toSet()
                val annotated = withContext(Dispatchers.Default) { Thumbs.annotate(bmp, faces, all) }
                if (!Jobs.running) Jobs.reset()
                _state.update { changed(it).copy(collage = annotated, collageFaces = faces, faceThumbs = thumbs, smallFaces = small, enabled = all,
                    error = if (faces.isEmpty()) "No faces found in this photo. Pick a photo where the faces are clear, not too small and facing the camera." else null) }
            } catch (e: Throwable) {
                Log.e(ImageUtils.TAG, "collage load failed", e)
                _state.update { it.copy(error = (e as? ImageLoadException)?.message ?: "Couldn't open the selected image.",
                    errorDetail = ImageUtils.describe(e)) }
            } finally { _state.update { it.copy(loading = null) } }
        }
    }

    /** Switch a collage face on/off (e.g. a false detection or someone you don't want used). */
    fun toggleFace(i: Int) {
        val s = _state.value
        val en = if (i in s.enabled) s.enabled - i else s.enabled + i
        if (en.isEmpty()) { _state.update { it.copy(toast = "At least one face has to stay on.") }; return }
        val raw = collageRaw
        _state.update { changed(it).copy(enabled = en) }
        if (raw != null) viewModelScope.launch {
            val a = withContext(Dispatchers.Default) { Thumbs.annotate(raw, s.collageFaces, en) }
            _state.update { it.copy(collage = a) }
        }
    }

    /** Trim range; keeps at most 30 s by moving the other end. */
    fun setRange(a: Float, b: Float) {
        val dur = _state.value.info?.seconds?.toFloat() ?: return
        var s = a.coerceIn(0f, dur); var e = b.coerceIn(0f, dur)
        val max = VideoPlan.MAX_SECONDS.toFloat()
        if (e - s > max) { if (s != _state.value.startS) e = s + max else s = e - max }
        if (e - s < 0.5f) return
        _state.update { changed(it).copy(startS = s, endS = e) }
    }

    fun setFps(f: Int) { prefs.edit().putInt("video_fps", f).apply(); _state.update { changed(it).copy(fps = f) } }
    fun setEnhance(m: EnhanceMode) { prefs.edit().putString("video_enhance", m.name).apply(); _state.update { it.copy(enhance = m) } }
    fun setRepeat(on: Boolean) { prefs.edit().putBoolean("repeat", on).apply(); _state.update { changed(it).copy(repeat = on) } }
    fun setStrictness(v: FilterStrictness) { prefs.edit().putString("strictness", v.name).apply(); _state.update { it.copy(strictness = v) } }
    fun setTransfer(t: TransferOptions) {
        if (!t.face && !t.colours) { _state.update { it.copy(toast = "Keep at least one thing to take from the collage.") }; return }
        prefs.edit().putBoolean("t_face", t.face).putBoolean("t_hair", t.hair).putBoolean("t_skin", t.skin).putBoolean("t_outfit", t.outfit).apply()
        _state.update { it.copy(transfer = t) }
    }
    fun setAccelerator(on: Boolean) { prefs.edit().putBoolean("xnnpack", on).apply(); _state.update { it.copy(accelerator = on) } }

    fun frameCount(): Int {
        val s = _state.value; val info = s.info ?: return 0
        val fps = VideoPlan.effectiveFps(s.fps.toDouble(), info.fps)
        return FrameSelector.indices(s.endS.toDouble(), fps, s.startS.toDouble(), s.endS.toDouble(), fps).size
    }

    /** Optional extra download of the Light (GPEN-256, 76 MB) or HQ (GPEN-512, 284 MB) enhancer; resumable. */
    fun downloadEnhancer(mode: EnhanceMode) {
        if (mode == EnhanceMode.OFF || enhJob?.isActive == true) return
        val store = if (mode == EnhanceMode.LIGHT) lightStore else hqStore
        enhPause = false
        _state.update { it.copy(enhDownloading = mode, enhDone = runCatching { store.bytesPresent() }.getOrDefault(0L), error = null, errorDetail = null) }
        enhJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.downloadAll({ enhPause }) { p -> _state.update { it.copy(enhDone = p.done) } } }
                prefs.edit().putString("video_enhance", mode.name).apply()
                _state.update { if (mode == EnhanceMode.LIGHT) it.copy(lightInstalled = true, enhance = mode) else it.copy(hqInstalled = true, enhance = mode) }
            } catch (e: CancellationException) {
            } catch (e: Throwable) {
                Log.e(ImageUtils.TAG, "enhancer download failed", e)
                _state.update { it.copy(error = downloadErrorMessage(e) ?: "Download failed.", errorDetail = ImageUtils.describe(e)) }
            } finally { _state.update { it.copy(enhDownloading = null) } }
        }
    }

    fun pauseEnhancer() { enhPause = true }

    /** Automatic assignment (faces left to right). */
    fun start() = run(_state.value.shift, null)
    /** Rotate which collage face goes to which person, then re-render (no new face search). */
    fun shuffle() = run(_state.value.shift + 1, null)
    /** Re-render with the faces picked in the "who gets which face" editor. */
    fun applyEdit() { val e = _state.value.edit ?: return; run(_state.value.shift, e) }

    /** In the result editor: person [person] gets collage face [face] (-1 = keep their own face). */
    fun setPersonFace(person: Int, face: Int) {
        val d = Jobs.state.value as? JobState.Done ?: return
        val base = _state.value.edit ?: d.result.people.map { it.face }.toIntArray()
        if (person !in base.indices) return
        val e = base.copyOf().also { it[person] = face }
        _state.update { it.copy(edit = if (e.contentEquals(d.result.people.map { p -> p.face }.toIntArray())) null else e) }
    }

    private fun run(shift: Int, personFace: IntArray?) {
        val s = _state.value
        val v = s.video ?: return; if (s.collage == null || s.collageFaces.isEmpty()) return
        if (Jobs.running) return
        if ((s.enhance == EnhanceMode.LIGHT && !s.lightInstalled) || (s.enhance == EnhanceMode.HQ && !s.hqInstalled)) {
            _state.update { it.copy(error = "Download that enhancer first, or pick another Enhance option.") }; return }
        dir.listFiles()?.filter { it.name.startsWith("out_") }?.forEach { it.delete() }
        val out = File(dir, "out_${System.currentTimeMillis()}.mp4")
        _state.update { it.copy(shift = shift, error = null, errorDetail = null, edit = null, resultVersion = it.inputsVersion) }
        CollageJobService.start(getApplication(), JobParams(v.absolutePath, File(dir, "collage.png").absolutePath,
            (s.startS * 1e6).toLong(), (s.endS * 1e6).toLong(), s.fps.toDouble(), s.enhance, s.accelerator, out.absolutePath,
            s.strictness, shift, s.repeat, s.enabled.sorted().toIntArray(), personFace, s.transfer))
    }

    fun cancel() = CollageJobService.cancel(getApplication())

    fun save(file: File) {
        viewModelScope.launch {
            val msg = try { "Saved to " + withContext(Dispatchers.IO) { VideoFiles.saveToMovies(getApplication(), file) } }
                      catch (e: Throwable) { Log.e(ImageUtils.TAG, "video save failed", e); "Save failed: ${e.message ?: e.javaClass.simpleName}" }
            _state.update { it.copy(toast = msg) }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun setInputsForTest(file: File, info: VideoInfo, collage: Bitmap?, faces: List<FaceData>) = _state.update {
        changed(it).copy(video = file, info = info, collage = collage, collageFaces = faces, faceThumbs = faces.map { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) },
            enabled = faces.indices.toSet(), startS = 0f, endS = minOf(info.seconds, DEFAULT_SECONDS).toFloat())
    }

    fun showError(msg: String, detail: String? = null) = _state.update { it.copy(error = msg, errorDetail = detail) }
    fun toastShown() = _state.update { it.copy(toast = null) }
    fun dismissResult() = Jobs.reset()

    override fun onCleared() { enhPause = true; models.pause() }

    companion object { const val DEFAULT_SECONDS = 10.0 }
}

object VideoFiles {
    /** Copy to Movies/CollageVideo (MediaStore on Android 10+, public dir + scan on 9 and older). */
    fun saveToMovies(context: Context, src: File): String {
        if (!src.isFile || src.length() == 0L) throw IOException("The result file is missing.")
        val name = "CollageVideo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CollageVideo")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val cr = context.contentResolver
            val uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("Couldn't create the video in the gallery.")
            try {
                (cr.openOutputStream(uri) ?: throw IOException("Couldn't write the video.")).use { o -> src.inputStream().use { it.copyTo(o, 1 shl 20) } }
                values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
                cr.update(uri, values, null, null)
            } catch (e: Exception) { cr.delete(uri, null, null); throw e }
        } else {
            @Suppress("DEPRECATION")
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "CollageVideo")
            if (!d.exists() && !d.mkdirs()) throw IOException("Couldn't create Movies/CollageVideo.")
            val f = File(d, name); src.copyTo(f, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("video/mp4"), null)
        }
        return "Movies/CollageVideo/$name"
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share collage video")
    }
}
