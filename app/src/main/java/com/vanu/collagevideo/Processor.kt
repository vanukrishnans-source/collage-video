package com.vanu.collagevideo

import com.vanu.faceswap.core.*

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

class UnsupportedVideoException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** What we need to know about the picked video. width/height are upright (display) dimensions. */
data class VideoInfo(
    val durationUs: Long, val width: Int, val height: Int, val rotation: Int, val fps: Double,
    val mime: String, val audioMime: String?, val bitrate: Int,
) {
    val seconds get() = durationUs / 1e6
    val hasAudio get() = audioMime != null
}

enum class EnhanceMode { OFF, LIGHT, HQ }

data class JobParams(
    val video: String, val collage: String, val startUs: Long, val endUs: Long, val fps: Double,
    val enhance: EnhanceMode, val accelerator: Boolean, val output: String,
    val strictness: FilterStrictness,
    /** "Shuffle": rotates the default assignment. */
    val shift: Int,
    /** Reuse collage faces when the video has more people than the collage. */
    val repeat: Boolean,
    /** Collage face indices (reading order) the user left switched on. */
    val enabled: IntArray,
    /** Manual collage person per performer (-1 = keep original), from "Who becomes who"; null = automatic. */
    val personFace: IntArray?,
    val transfer: TransferOptions = TransferOptions(),
)

/** One person of the reference video in the result: thumbnails before / after and the collage face used. */
class PersonResult(val before: Bitmap?, val after: Bitmap?, val face: Int, val frames: Int, val major: Boolean)

data class JobResult(
    val file: File, val frames: Int, val seconds: Double, val people: List<PersonResult>, val collageFaces: Int,
    val swappedPeople: Int, val audioCopied: Boolean, val audioNote: String?, val width: Int, val height: Int, val fps: Double,
    val safetyChecks: Int,
    /** Human-readable notes per performer (exposure, outfit decision, face coverage). */
    val notes: List<String> = emptyList(),
    /** Display order of [people] (indices): main people left to right first. */
    val order: List<Int>,
)

/**
 * Cached pass-1 result so re-rendering with another assignment / options doesn't re-scan the video: per frame the
 * performers' boxes (x0,y0,x1,y1) and head points, the faces, the colour statistics of sampled frames and the safety score.
 */
class DetectionCache(
    val key: String, val bodies: List<List<DoubleArray>>, val heads: List<List<DoubleArray?>>, val faces: List<List<DoubleArray>>,
    val samples: Map<Int, List<Map<Region, LabStats>>>, val pts: LongArray, val maxNsfw: Float, val checks: Int,
)

object VideoProbe {
    private const val TAG = ImageUtils.TAG

    fun tracks(ex: MediaExtractor): Pair<Int, Int> {
        var v = -1; var a = -1
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (v < 0 && m.startsWith("video/")) v = i
            if (a < 0 && m.startsWith("audio/")) a = i
        }
        return v to a
    }

    fun probe(file: File): VideoInfo {
        val ex = MediaExtractor()
        try {
            try { ex.setDataSource(file.absolutePath) } catch (e: Exception) {
                throw UnsupportedVideoException("Couldn't read this video file (unknown or damaged format).", e)
            }
            val (vt, at) = tracks(ex)
            if (vt < 0) throw UnsupportedVideoException("This file has no video track.")
            val vf = ex.getTrackFormat(vt)
            val mime = vf.getString(MediaFormat.KEY_MIME)!!
            var w = vf.getInteger(MediaFormat.KEY_WIDTH); var h = vf.getInteger(MediaFormat.KEY_HEIGHT)
            var dur = if (vf.containsKey(MediaFormat.KEY_DURATION)) vf.getLong(MediaFormat.KEY_DURATION) else 0L
            var fps = if (vf.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { vf.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                .getOrElse { runCatching { vf.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrDefault(0.0) } else 0.0
            var rot = if (Build.VERSION.SDK_INT >= 23 && vf.containsKey(MediaFormat.KEY_ROTATION)) vf.getInteger(MediaFormat.KEY_ROTATION) else -1
            var br = if (vf.containsKey(MediaFormat.KEY_BIT_RATE)) vf.getInteger(MediaFormat.KEY_BIT_RATE) else 0
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.absolutePath)
                if (rot < 0) rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (dur <= 0) dur = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
                if (fps <= 0 && Build.VERSION.SDK_INT >= 28 && dur > 0) {
                    val n = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
                    if (n > 0) fps = n / (dur / 1e6)
                }
                if (br <= 0) br = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            } catch (e: Exception) { Log.w(TAG, "metadata retriever failed", e) } finally { runCatching { mmr.release() } }
            if (fps <= 0 || !fps.isFinite()) fps = 30.0
            rot = Math.floorMod(rot, 360)
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val am = if (at >= 0) ex.getTrackFormat(at).getString(MediaFormat.KEY_MIME) else null
            if (dur <= 0) throw UnsupportedVideoException("Couldn't read the length of this video.")
            return VideoInfo(dur, w, h, rot, fps, mime, am, br)
        } finally { ex.release() }
    }

    /** Upright preview frame at [timeUs], longest side <= [maxSide]. */
    fun thumbnail(file: File, timeUs: Long, maxSide: Int = 720): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val b = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val s = maxSide.toFloat() / maxOf(b.width, b.height)
            if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true).also { if (it !== b) b.recycle() } else b
        } catch (e: Exception) { Log.w(TAG, "thumbnail failed", e); null } finally { runCatching { mmr.release() } }
    }
}

/**
 * Collage → reference video: the collage people become the performers of the reference video.
 *
 * Pass 1 decodes the selected frames; per frame the pose model finds the performers (boxes + masks) and faces are
 * found on the whole frame plus around every performer's head; on sampled frames the part segmenter measures each
 * performer's hair / skin / outfit colours, and the always-on safety filter checks the frame. Performers are
 * tracked and grouped, and get collage people left to right (Flip / Shuffle / manual). Pass 2 decodes again and,
 * per frame, recolours every performer's hair, skin and outfit to their collage person's (at the scene's
 * exposure, keeping the reference lighting and folds), puts the collage person's face identity on their face
 * (inswapper, optional GPEN), and encodes H.264 straight away. The audio track is copied through untouched.
 */
class CollageProcessor(
    private val context: Context,
    private val models: AiModelFiles,
    private val lightEnhancer: File?,
    private val safetyModel: File,
    private val poseModel: File,
    private val segModel: File,
    private val log: (String) -> Unit = { Log.i(ImageUtils.TAG, it) },
) {
    /** stage: 0 = analysing (people, colours, safety), 1 = rendering. */
    fun interface Progress { fun update(stage: Int, done: Int, total: Int, etaSeconds: Double?) }

    private class Crop(val x0: Int, val y0: Int, val w: Int, val h: Int, val weights: Map<Region, FloatArray>)

    /** Region weights of one performer inside a square crop around them (segmenter on the crop). */
    private fun regions(bmp: Bitmap, it: BodyInstance, seg: PartSegmenter): Crop {
        val W = bmp.width; val H = bmp.height; val b = it.box
        val side = maxOf(b[2] - b[0], b[3] - b[1]) * 1.1; val cx = (b[0] + b[2]) / 2.0; val cy = (b[1] + b[3]) / 2.0
        val x0 = maxOf(0, (cx - side / 2).toInt()); val y0 = maxOf(0, (cy - side / 2).toInt())
        val x1 = minOf(W, (cx + side / 2).toInt()); val y1 = minOf(H, (cy + side / 2).toInt())
        val cw = x1 - x0; val ch = y1 - y0
        val crop = Bitmap.createBitmap(bmp, x0, y0, cw, ch)
        val conf = try { seg.segment(crop) } finally { if (crop !== bmp) crop.recycle() }
        val n = cw * ch
        val hair = FloatArray(n); val skin = FloatArray(n); val up = FloatArray(n); val lo = FloatArray(n)
        for (y in 0 until ch) for (x in 0 until cw) {
            val i = y * cw + x; val m = it.mask[(y + y0) * W + x + x0]
            if (m <= 0f) continue
            hair[i] = conf[PartSegmenter.HAIR][i] * m
            skin[i] = (conf[PartSegmenter.FACE][i] + conf[PartSegmenter.BODY][i]) * m
            val c = conf[PartSegmenter.CLOTHES][i] * m
            if (y + y0 < it.hipY) up[i] = c else lo[i] = c
        }
        return Crop(x0, y0, cw, ch, mapOf(Region.HAIR to hair, Region.SKIN to skin, Region.UPPER to up, Region.LOWER to lo))
    }

    private fun cropPixels(rgb: RgbImage, c: Crop): IntArray = IntArray(c.w * c.h).also { px ->
        for (y in 0 until c.h) { var s = ((y + c.y0) * rgb.width + c.x0) * 3
            for (x in 0 until c.w) { px[y * c.w + x] = (0xff shl 24) or ((rgb.px[s].toInt() and 255) shl 16) or ((rgb.px[s + 1].toInt() and 255) shl 8) or (rgb.px[s + 2].toInt() and 255); s += 3 } }
    }

    private fun writePixels(rgb: RgbImage, c: Crop, px: IntArray) {
        for (y in 0 until c.h) { var s = ((y + c.y0) * rgb.width + c.x0) * 3
            for (x in 0 until c.w) { val v = px[y * c.w + x]; rgb.px[s] = (v shr 16).toByte(); rgb.px[s + 1] = (v shr 8).toByte(); rgb.px[s + 2] = v.toByte(); s += 3 } }
    }

    private fun statsOf(rgb: RgbImage, c: Crop): Map<Region, LabStats> {
        val px = cropPixels(rgb, c); val lab = FloatArray(3)
        val out = Region.values().associateWith { LabStats() }
        for ((r, w) in c.weights) { val st = out[r]!!
            for (i in w.indices) if (w[i] > 0.5f) { val v = px[i]; BodyMath.rgbToLab((v shr 16) and 255, (v shr 8) and 255, v and 255, lab); st.add(lab[0], lab[1], lab[2]) } }
        return out
    }

    /** Colour statistics of every collage person (hair, skin above the nose + neck, top below the chin). */
    private fun analyseCollage(bmp: Bitmap, faces: List<FaceData>, seg: PartSegmenter): List<Map<Region, LabStats>> {
        val W = bmp.width; val H = bmp.height
        val cs = faces.map { f -> FaceTracker.mean(FaceTracker.toPts(f)) }
        val ws = faces.map { f -> Thumbs.faceBox(f).let { it[2] - it[0] } }
        val rgb = FaceDetector.toRgb(bmp); val lab = FloatArray(3)
        return faces.indices.map { i ->
            val c = cs[i]; val side = 4.5 * ws[i]
            val x0 = maxOf(0, (c[0] - side / 2).toInt()); val x1 = minOf(W, (c[0] + side / 2).toInt())
            val y0 = maxOf(0, (c[1] - side * 0.4).toInt()); val y1 = minOf(H, (c[1] + side * 0.75).toInt())
            val out = Region.values().associateWith { LabStats() }
            if (x1 - x0 >= 16 && y1 - y0 >= 16) {
                val crop = Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
                val conf = try { seg.segment(crop) } finally { if (crop !== bmp) crop.recycle() }
                val noseY = faces[i].y(1); val chinY = faces[i].y(152); val cw = x1 - x0
                for (y in y0 until y1) for (x in x0 until x1) {
                    // Voronoi ownership (vertical distance counts half: bodies hang below faces)
                    var best = 0; var bd = Double.MAX_VALUE
                    for (j in cs.indices) { val d = Math.hypot(x - cs[j][0], (y - cs[j][1]) * 0.5); if (d < bd) { bd = d; best = j } }
                    if (best != i) continue
                    val k = (y - y0) * cw + (x - x0); val s = (y * W + x) * 3
                    val hair = conf[PartSegmenter.HAIR][k]; val skin = conf[PartSegmenter.FACE][k] * (if (y < noseY) 1f else 0f) + conf[PartSegmenter.BODY][k]
                    val top = conf[PartSegmenter.CLOTHES][k] * (if (y > chinY) 1f else 0f)
                    if (hair > 0.5f || skin > 0.5f || top > 0.5f) {
                        BodyMath.rgbToLab(rgb.px[s].toInt() and 255, rgb.px[s + 1].toInt() and 255, rgb.px[s + 2].toInt() and 255, lab)
                        if (hair > 0.5f) out[Region.HAIR]!!.add(lab[0], lab[1], lab[2])
                        if (skin > 0.5f) out[Region.SKIN]!!.add(lab[0], lab[1], lab[2])
                        if (top > 0.5f) out[Region.UPPER]!!.add(lab[0], lab[1], lab[2])
                    }
                }
            }
            out
        }
    }

    fun run(p: JobParams, info: VideoInfo, collageBmp: Bitmap, collageFaces: List<FaceData>, cache: DetectionCache?,
            onCache: (DetectionCache) -> Unit, progress: Progress, cancelled: () -> Boolean): JobResult {
        val t0 = System.nanoTime()
        val file = File(p.video)
        val fps = VideoPlan.effectiveFps(p.fps, info.fps)
        val (W, H, s) = VideoPlan.outSize(info.width, info.height)
        val startS = p.startUs / 1e6; val endS = p.endUs / 1e6
        val expectedFrames = FrameSelector.indices(endS, fps, startS, endS, fps).size.coerceAtLeast(1)
        val bitrate = VideoPlan.bitrate(W, H, fps)
        val out = File(p.output); out.parentFile?.mkdirs()
        val need = VideoPlan.estimateBytes(bitrate, endS - startS)
        val free = out.parentFile?.usableSpace ?: Long.MAX_VALUE
        if (free < need) throw StorageException("Not enough free storage: about ${need / 1_000_000} MB needed, ${free / 1_000_000} MB free.")
        val enabled = p.enabled.filter { it in collageFaces.indices }.distinct().sorted()
        if (enabled.isEmpty()) throw NoFaceException("All collage people are switched off. Switch at least one on.")
        val nSrc = enabled.size
        val opt = p.transfer
        log("video ${info.width}x${info.height} rot ${info.rotation} ${"%.2f".format(info.fps)}fps -> ${W}x$H @ ${"%.2f".format(fps)}fps, " +
            "range ${"%.2f".format(startS)}-${"%.2f".format(endS)}s, ~$expectedFrames frames, collage people ${collageFaces.size} (on: $enabled), $opt")
        val collage = FaceDetector.toRgb(collageBmp)

        // ---------------- safety: the collage photo itself (always, cheap)
        val threshold = p.strictness.threshold
        SafetyFilter(safetyModel).use { sf ->
            val sc = sf.score(collage); log("safety collage %.4f".format(sc))
            if (SafetyPlan.blocked(sc, threshold)) throw SafetyBlockedException("collage photo", sc)
        }
        val seg = PartSegmenter(context, segModel)
        try {
        val cstats = analyseCollage(collageBmp, collageFaces, seg)
        cstats.forEachIndexed { i, m -> log("collage person ${i + 1}: " + m.entries.joinToString { "${it.key}=${it.value}" }) }

        // ---------------- pass 1: performers + faces every frame, colours + safety on sampled frames (cached)
        val key = "${file.absolutePath}|${file.length()}|${p.startUs}|${p.endUs}|$fps|${W}x$H"
        val det: DetectionCache = if (cache != null && cache.key == key) cache else {
            val bodies = ArrayList<List<DoubleArray>>(); val heads = ArrayList<List<DoubleArray?>>(); val faces = ArrayList<List<DoubleArray>>()
            val samples = HashMap<Int, List<Map<Region, LabStats>>>(); val ptsList = ArrayList<Long>()
            val detector = FaceDetector(context, 10); val body = BodyDetector(context, poseModel)
            val sf = SafetyFilter(safetyModel)
            var maxNsfw = 0f; var checks = 0
            val step = SafetyPlan.step(expectedFrames, fps)
            try {
                val eta = EtaEstimator(); var last = System.nanoTime()
                decodeSelected(file, info, p.startUs, p.endUs, fps, W, H, s, cancelled) { pts, rgb ->
                    val k = bodies.size
                    if (SafetyPlan.isSample(k, step)) {
                        val sc = sf.score(rgb); checks++
                        if (sc > maxNsfw) maxNsfw = sc
                        // stop at the first flagged frame: nothing is produced from this video
                        if (SafetyPlan.blocked(sc, threshold)) throw SafetyBlockedException("reference video", sc)
                    }
                    val bmp = FaceDetector.toBitmap(rgb)
                    try {
                        val insts = body.detect(bmp)
                        bodies.add(insts.map { doubleArrayOf(it.box[0].toDouble(), it.box[1].toDouble(), it.box[2].toDouble(), it.box[3].toDouble()) })
                        heads.add(insts.map { it.head })
                        faces.add(detector.detectWithBodies(bmp, insts))
                        if (SafetyPlan.isSample(k, step)) samples[k] = insts.map { statsOf(rgb, regions(bmp, it, seg)) }
                    } finally { bmp.recycle() }
                    ptsList.add(pts)
                    val now = System.nanoTime(); eta.add((now - last) / 1e9); last = now
                    progress.update(0, bodies.size, expectedFrames, eta.remaining(expectedFrames - bodies.size))
                }
            } finally { detector.close(); body.close(); sf.close() }
            log("safety video: $checks checks, max %.4f".format(maxNsfw))
            DetectionCache(key, bodies, heads, faces, samples, ptsList.toLongArray(), maxNsfw, checks).also(onCache)
        }
        if (SafetyPlan.blocked(det.maxNsfw, threshold)) throw SafetyBlockedException("reference video", det.maxNsfw)
        val n = det.bodies.size
        if (n == 0) throw UnsupportedVideoException("No video frames could be decoded in the selected range.")

        // performers = tracked pose instances, grouped into people; collage people go to them left to right
        val ptracks = FaceTracker.track(det.bodies, maxGap = Math.rint(fps).toInt())
        val people = People.group(ptracks)
        if (people.isEmpty()) throw NoFaceException(
            "No people found in the selected part of the video. Pick a part where the people are clearly visible.")
        val layout = People.layout(people, ptracks, nSrc)
        val personFace: IntArray = p.personFace?.takeIf { it.size == people.size }?.map { if (it in collageFaces.indices) it else -1 }?.toIntArray()
            ?: People.autoAssign(people, layout, nSrc, p.shift, p.repeat).map { if (it >= 0) enabled[it] else -1 }.toIntArray()
        val personOfTrack = IntArray(ptracks.size) { -1 }.also { a -> people.forEachIndexed { pi, pp -> for (t in pp.tracks) a[t] = pi } }
        fun detIndex(t: Int, k: Int): Int { val b = ptracks[t][k] ?: return -1; return det.bodies[k].indexOfFirst { it === b } }

        // faces: tracked + smoothed; each face track belongs to the performer whose head it sits on
        val ftracks = FaceTracker.track(det.faces, maxGap = Math.rint(fps).toInt())
        val fsmooth = ftracks.map { Smoother.smoothTrack(it, fps) }
        val faceOwner = ftracks.map { t ->
            val votes = HashMap<Int, Int>()
            for ((k, pts) in t) {
                val c = FaceTracker.mean(pts); val fb = FaceTracker.bbox(pts); val fw = maxOf(fb[2] - fb[0], 1.0)
                var best = -1; var bd = 2.0
                for (pt in ptracks.indices) { val j = detIndex(pt, k); if (j < 0) continue
                    val hd = det.heads[k][j] ?: continue
                    val d = Math.hypot(hd[0] - c[0], hd[1] - c[1]) / fw
                    if (d < bd) { bd = d; best = personOfTrack[pt] } }
                if (best >= 0) votes[best] = (votes[best] ?: 0) + 1
            }
            votes.maxByOrNull { it.value }?.key ?: -1
        }
        val faceFace = IntArray(ftracks.size) { if (faceOwner[it] >= 0) personFace[faceOwner[it]] else -1 }
        log("performers: tracks ${ptracks.size} ${ptracks.map { it.size }}, people ${people.size} ${people.map { it.tracks.toList() }}, " +
            "layout ${layout.order}, collage person per performer ${personFace.toList()}; face tracks ${ftracks.size} ${ftracks.map { it.size }} " +
            "owners $faceOwner -> $faceFace")

        // colour transfer plan per performer
        val notes = ArrayList<String>()
        val plans = people.indices.map { pi ->
            val a = personFace[pi]
            if (a < 0 || !opt.colours) return@map emptyMap<Region, Xfer>()
            val ps = Region.values().associateWith { LabStats() }.toMutableMap()
            for ((k, per) in det.samples) for (t in people[pi].tracks) { val j = detIndex(t, k); if (j in per.indices) for (r in Region.values()) ps[r] = ps[r]!!.merge(per[j][r]!!) }
            val (plan, e) = BodyMath.plan(cstats[a], ps, opt)
            val outfit = when { Region.LOWER in plan -> "whole outfit (one garment)"; Region.UPPER in plan -> "top only (legs aren't in the collage)"; else -> "outfit unchanged" }
            notes.add("Person ${pi + 1} → collage ${a + 1}: ${plan.keys.filter { it != Region.LOWER && it != Region.UPPER }.joinToString { it.name.lowercase() }}" +
                ", $outfit, exposure ${"%.2f".format(e)}")
            log("performer $pi -> collage ${a + 1}: " + ps.entries.joinToString { "${it.key}=${it.value}" } + " plan ${plan.keys} e=%.2f".format(e))
            plan
        }
        val usedFaces = if (opt.face) faceFace.filter { it >= 0 }.toSet() else emptySet()
        // thumbnails: biggest appearance of every performer
        val best = people.map { People.bestFrame(it, ptracks) }
        val before = arrayOfNulls<Bitmap>(people.size); val after = arrayOfNulls<Bitmap>(people.size)

        // ---------------- pass 2: recolour + face identity + encode (models stay loaded for the whole job)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val engine = AiEngine(models, threads, p.accelerator, lowMemory = true) { Log.w(ImageUtils.TAG, it) }
        val tmp = File(out.parentFile, out.name + ".part")
        var audioCopied = false; var audioNote: String? = null
        val body = if (opt.colours && plans.any { it.isNotEmpty() }) BodyDetector(context, poseModel) else null
        try {
            val latents = HashMap<Int, FloatArray>()
            if (usedFaces.isNotEmpty()) engine.open(models.arcface).use { a ->
                for (si in usedFaces) latents[si] = engine.latent(engine.embedding(a, collage, AiMath.kps5(collageFaces[si])).first)
            }
            val enhFile = when (p.enhance) { EnhanceMode.OFF -> null; EnhanceMode.LIGHT -> lightEnhancer; EnhanceMode.HQ -> models.enhancer }
            val enhSize = if (p.enhance == EnhanceMode.LIGHT) 256 else 512
            val sw = if (usedFaces.isNotEmpty()) engine.open(models.swapper) else null
            val enh = if (sw != null) enhFile?.let { engine.open(it) } else null
            try {
                Mp4Writer(tmp, file, info, W, H, fps, bitrate, p.startUs, p.endUs).use { w ->
                    val eta = EtaEstimator(); var last = System.nanoTime(); var done = 0
                    val index = HashMap<Long, Int>(n * 2).apply { det.pts.forEachIndexed { i, t -> put(t, i) } }
                    val lastInst = HashMap<Int, Pair<Int, BodyInstance>>()
                    decodeSelected(file, info, p.startUs, p.endUs, fps, W, H, s, cancelled) { pts, rgb ->
                        val k = index[pts] ?: -1          // same frame as in pass 1 (by timestamp)
                        if (k >= 0) {
                            val thumbs = people.indices.filter { best[it].first == k }
                            for (pi in thumbs) before[pi] = Thumbs.crop(rgb, ptracks[best[pi].second][k]!!)
                            if (body != null) {
                                val bmp = FaceDetector.toBitmap(rgb)
                                try {
                                    val insts = body.detect(bmp)
                                    for (pi in people.indices) {
                                        val plan = plans[pi]; if (plan.isEmpty()) continue
                                        val tb = people[pi].tracks.asIterable().firstNotNullOfOrNull { ptracks[it][k] }
                                        var inst: BodyInstance? = null
                                        if (tb != null) {
                                            var bs = 0.5
                                            for (it in insts) { val sc = FaceTracker.iou(doubleArrayOf(it.box[0].toDouble(), it.box[1].toDouble(), it.box[2].toDouble(), it.box[3].toDouble()), tb)
                                                if (sc > bs) { bs = sc; inst = it } }
                                        }
                                        if (inst != null) lastInst[pi] = k to inst
                                        else lastInst[pi]?.let { (lk, li) -> if (k - lk <= 3) inst = li }   // brief drop-out: reuse the last mask
                                        val use = inst ?: continue
                                        val c = regions(bmp, use, seg)
                                        val px = cropPixels(rgb, c)
                                        BodyMath.recolour(px, c.w, c.h, c.weights, plan)
                                        writePixels(rgb, c, px)
                                    }
                                } finally { bmp.recycle() }
                            }
                            if (sw != null) for (ti in ftracks.indices) {
                                val a = faceFace[ti]; val pt = fsmooth[ti][k]
                                if (a < 0 || pt == null || a !in latents) continue
                                val face = FaceTracker.toFace(pt)
                                engine.swapFace(sw, rgb, face, latents[a]!!)
                                if (enh != null) engine.enhance(enh, rgb, face, 0.8, enhSize)
                            }
                            for (pi in thumbs) after[pi] = Thumbs.crop(rgb, ptracks[best[pi].second][k]!!)
                        }
                        w.writeFrame(rgb, pts)
                        done++
                        val now = System.nanoTime(); eta.add((now - last) / 1e9); last = now
                        progress.update(1, minOf(done, n), n, eta.remaining(maxOf(0, n - done)))
                    }
                    w.finish()
                    audioCopied = w.audioCopied; audioNote = w.audioNote
                }
            } finally { enh?.close(); sw?.close() }
            if (cancelled()) throw CancellationException()
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        } catch (e: Throwable) {
            tmp.delete(); throw e
        } finally { engine.close(); body?.close() }
        val secs = (System.nanoTime() - t0) / 1e9
        val res = people.indices.map { PersonResult(before[it], after[it], personFace[it], people[it].frames, people[it].major) }
        return JobResult(out, n, secs, res, collageFaces.size, personFace.count { it >= 0 }, audioCopied, audioNote, W, H, fps, det.checks,
            notes = notes, order = layout.order)
        } finally { seg.close() }
    }

    /**
     * Decode [file] and call [onFrame] with an upright W x H RGB image for every selected frame in
     * [startUs, endUs). The same RgbImage buffer is reused between calls.
     */
    private fun decodeSelected(file: File, info: VideoInfo, startUs: Long, endUs: Long, fps: Double, W: Int, H: Int, s: Double,
                               cancelled: () -> Boolean, onFrame: (Long, RgbImage) -> Unit) {
        val ex = MediaExtractor(); var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            val vt = VideoProbe.tracks(ex).first
            ex.selectTrack(vt)
            val fmt = ex.getTrackFormat(vt)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
                throw UnsupportedVideoException("This phone has no decoder for this video format ($mime).", e)
            }
            try { codec.configure(fmt, null, null, 0); codec.start() } catch (e: Exception) {
                throw UnsupportedVideoException("This phone can't decode this video (${mime}, ${info.width}x${info.height}). " +
                    "Very high resolutions (4K/8K) or unusual formats may not be supported — try a 1080p video.", e)
            }
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val sel = FrameSelector(startUs / 1e6, endUs / 1e6, fps)
            val bt709 = colorIs709(fmt, info)
            val rgb = RgbImage(W, H)
            val bi = MediaCodec.BufferInfo()
            var inputDone = false; var done = false; var lastOutput = System.nanoTime()
            while (!done) {
                if (cancelled()) throw CancellationException()
                if (System.nanoTime() - lastOutput > 10_000_000_000L)
                    throw UnsupportedVideoException("The video decoder stopped responding ($mime, ${info.width}x${info.height}).")
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        // samples arrive in decode order; stop feeding a little after the range end (B-frame reorder)
                        if (n < 0 || ex.sampleTime > endUs + 1_000_000) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(bi, 10_000)
                if (oi != MediaCodec.INFO_TRY_AGAIN_LATER) lastOutput = System.nanoTime()
                if (oi >= 0) {
                    val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val pts = bi.presentationTimeUs
                    if (bi.size > 0 && pts >= endUs) done = true
                    else if (bi.size > 0 && sel.accept(pts / 1e6)) {
                        val img = codec.getOutputImage(oi) ?: throw UnsupportedVideoException("The video decoder gave no readable frame.")
                        try {
                            if (img.format != ImageFormat.YUV_420_888) throw UnsupportedVideoException(
                                "HDR / 10-bit videos aren't supported yet (frame format ${img.format}). Use a standard (SDR) video.")
                            val pl = img.planes; val cr = img.cropRect
                            Yuv.toRgb(YuvPlane(pl[0].buffer, pl[0].rowStride, pl[0].pixelStride),
                                YuvPlane(pl[1].buffer, pl[1].rowStride, pl[1].pixelStride),
                                YuvPlane(pl[2].buffer, pl[2].rowStride, pl[2].pixelStride),
                                cr.left, cr.top, cr.width(), cr.height(), info.rotation, W, H, s, bt709, rgb)
                        } finally { img.close() }
                        codec.releaseOutputBuffer(oi, false)
                        onFrame(pts, rgb)
                        lastOutput = System.nanoTime()
                        if (eos) done = true
                        continue
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (eos) done = true
                }
            }
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            ex.release()
        }
    }

    private fun colorIs709(fmt: MediaFormat, info: VideoInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 24 && fmt.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
            return fmt.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT709
        }
        return minOf(info.width, info.height) >= 720
    }
}

/**
 * H.264 encoder (MediaCodec, YUV420Flexible input via getInputImage) + MediaMuxer. Audio samples of
 * the source in [startUs, endUs) are copied untouched, interleaved with the video as it's written.
 * Frames are upright, so the output has rotation 0.
 */
class Mp4Writer(
    private val out: File, source: File, info: VideoInfo, private val w: Int, private val h: Int, fps: Double, bitrate: Int,
    private val startUs: Long, private val endUs: Long,
) : AutoCloseable {
    private val codec: MediaCodec
    private val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var videoTrack = -1; private var audioTrack = -1; private var started = false
    private val pts = PtsMapper(startUs)
    private val bi = MediaCodec.BufferInfo()
    private var audioEx: MediaExtractor? = null
    private var audioBuf: ByteBuffer? = null
    private var audioDone = true
    var audioCopied = false; private set
    var audioNote: String? = null; private set
    private var lastVideoUs = 0L
    private val bt709 = true

    init {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_FRAME_RATE, Math.rint(fps).toInt().coerceAtLeast(1))
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        if (Build.VERSION.SDK_INT >= 24) {
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        codec = try { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) } catch (e: Exception) {
            muxer.release(); throw UnsupportedVideoException("This phone has no H.264 video encoder.", e)
        }
        try { codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start() } catch (e: Exception) {
            codec.release(); muxer.release()
            throw UnsupportedVideoException("The phone's H.264 encoder rejected ${w}x$h.", e)
        }
        muxer.setOrientationHint(0)
        // audio passthrough source
        if (info.hasAudio) {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(source.absolutePath)
                val at = VideoProbe.tracks(ex).second
                ex.selectTrack(at)
                val af = ex.getTrackFormat(at)
                // only add the track if the range really has audio (an empty track can make MediaMuxer.stop() fail)
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (ex.sampleTime in 0 until startUs) ex.advance()
                val hasSamples = ex.sampleTime in startUs until endUs
                audioTrack = if (!hasSamples) { audioNote = "The selected part has no sound."; -1 } else try { muxer.addTrack(af) } catch (e: Exception) {
                    audioNote = "The original sound (${af.getString(MediaFormat.KEY_MIME)}) can't be copied into MP4, so the video is silent."; -1
                }
                if (audioTrack >= 0) {
                    ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val max = if (af.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) af.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                    audioBuf = ByteBuffer.allocateDirect(maxOf(max, 256 * 1024))
                    audioEx = ex; audioDone = false
                } else ex.release()
            } catch (e: Exception) {
                Log.w(ImageUtils.TAG, "audio passthrough unavailable", e)
                audioNote = "The original sound couldn't be read, so the video is silent."; audioTrack = -1
                runCatching { ex.release() }
            }
        }
    }

    fun writeFrame(img: RgbImage, sourcePtsUs: Long) {
        val p = pts.map(sourcePtsUs)
        val t0 = System.nanoTime()
        while (true) {
            if (System.nanoTime() - t0 > 10_000_000_000L) throw UnsupportedVideoException("The video encoder stopped accepting frames.")
            val ii = codec.dequeueInputBuffer(10_000)
            if (ii >= 0) {
                val image = codec.getInputImage(ii)
                val size = if (image != null) {
                    val pl = image.planes
                    Yuv.fromRgb(img, YuvPlane(pl[0].buffer, pl[0].rowStride, pl[0].pixelStride),
                        YuvPlane(pl[1].buffer, pl[1].rowStride, pl[1].pixelStride),
                        YuvPlane(pl[2].buffer, pl[2].rowStride, pl[2].pixelStride), bt709)
                    w * h * 3 / 2
                } else fillBuffer(ii, img)
                codec.queueInputBuffer(ii, 0, size, p, 0)
                break
            }
            drain(false)
        }
        drain(false)
        lastVideoUs = p
        pumpAudio(p + 500_000)
    }

    /** Fallback when the encoder gives no Image: planar (I420) or semi-planar (NV12) buffer. */
    private fun fillBuffer(ii: Int, img: RgbImage): Int {
        val buf = codec.getInputBuffer(ii)!!
        val inf = codec.inputFormat
        val cf = inf.getInteger(MediaFormat.KEY_COLOR_FORMAT)
        val stride = if (inf.containsKey(MediaFormat.KEY_STRIDE)) inf.getInteger(MediaFormat.KEY_STRIDE).coerceAtLeast(w) else w
        val slice = if (inf.containsKey(MediaFormat.KEY_SLICE_HEIGHT)) inf.getInteger(MediaFormat.KEY_SLICE_HEIGHT).coerceAtLeast(h) else h
        val ySize = stride * slice
        val base = buf.duplicate().apply { clear() }
        val yP = YuvPlane(base, stride, 1)
        when (cf) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> {
                val uP = YuvPlane((base.duplicate().apply { position(ySize) }).slice(), stride / 2, 1)
                val vP = YuvPlane((base.duplicate().apply { position(ySize + ySize / 4) }).slice(), stride / 2, 1)
                Yuv.fromRgb(img, yP, uP, vP, bt709)
            }
            else -> {   // NV12 (COLOR_FormatYUV420SemiPlanar and most vendor formats)
                val uP = YuvPlane((base.duplicate().apply { position(ySize) }).slice(), stride, 2)
                val vP = YuvPlane((base.duplicate().apply { position(ySize + 1) }).slice(), stride, 2)
                Yuv.fromRgb(img, yP, uP, vP, bt709)
            }
        }
        return ySize * 3 / 2
    }

    private fun drain(end: Boolean) {
        var idleSince = System.nanoTime()
        while (true) {
            val oi = codec.dequeueOutputBuffer(bi, if (end) 10_000 else 0)
            if (oi != MediaCodec.INFO_TRY_AGAIN_LATER) idleSince = System.nanoTime()
            when {
                oi == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    if (System.nanoTime() - idleSince > 10_000_000_000L) throw UnsupportedVideoException("The video encoder stopped responding.")
                }
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started) { "encoder format changed twice" }
                    videoTrack = muxer.addTrack(codec.outputFormat)
                    muxer.start(); started = true
                }
                oi >= 0 -> {
                    val buf = codec.getOutputBuffer(oi)!!
                    if (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bi.size = 0
                    if (bi.size > 0) {
                        check(started) { "encoder output before format" }
                        buf.position(bi.offset); buf.limit(bi.offset + bi.size)
                        muxer.writeSampleData(videoTrack, buf, bi)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Copy audio samples up to [untilUs] (output timeline) once the muxer has started. */
    private fun pumpAudio(untilUs: Long) {
        val ex = audioEx ?: return
        if (!started || audioDone) return
        val buf = audioBuf!!; val info = MediaCodec.BufferInfo()
        while (true) {
            val t = ex.sampleTime
            if (t < 0) { audioDone = true; return }
            val outT = PtsMapper.audio(t, startUs, endUs)
            if (t >= endUs) { audioDone = true; return }
            if (outT != null && outT > untilUs) return
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n < 0) { audioDone = true; return }
            if (outT != null) {
                info.set(0, n, outT, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(audioTrack, buf, info)
                audioCopied = true
            }
            ex.advance()
        }
    }

    /** Flush the encoder, copy the remaining audio and finalize the MP4. Throws if the file can't be completed. */
    fun finish() {
        while (true) {
            val ii = codec.dequeueInputBuffer(10_000)
            if (ii >= 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
            drain(false)
        }
        drain(true)
        if (!started) throw UnsupportedVideoException("The video encoder produced no output.")
        pumpAudio(Long.MAX_VALUE)
        codec.stop()
        stopped = true
        muxer.stop()          // writes the moov atom; failure here means a broken file -> propagate
        muxerStopped = true
    }

    private var stopped = false
    private var muxerStopped = false

    override fun close() {
        if (!stopped) runCatching { codec.stop() }
        runCatching { codec.release() }
        audioEx?.let { runCatching { it.release() } }
        if (started && !muxerStopped) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }
}
