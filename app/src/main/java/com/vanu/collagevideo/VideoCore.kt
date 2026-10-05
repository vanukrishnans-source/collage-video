package com.vanu.collagevideo

import com.vanu.faceswap.core.FaceData
import com.vanu.faceswap.core.RgbImage

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Video mode logic that does not touch Android APIs: frame selection, face tracking, temporal
 * smoothing, pairing, YUV<->RGB conversion and small muxing helpers. Taken unchanged from Face Swap Video 1.0
 * (parity-tested there against test/video_pipeline.py). The collage-specific logic is in CollageCore.kt.
 *
 * Face points in video mode are DoubleArray(468 * 2) = x0, y0, x1, y1, ... (image pixels).
 */

object VideoPlan {
    const val MAX_SHORT_SIDE = 720
    const val MAX_SECONDS = 30.0
    const val MAX_FPS = 30.0

    /** Python round(): half to even. */
    private fun pyRound(v: Double) = Math.rint(v).toInt()

    /** Processing size: short side <= 720, both dims multiples of 16 (centre-cropped). Returns (W, H, scale). */
    fun outSize(w: Int, h: Int, maxShort: Int = MAX_SHORT_SIDE): Triple<Int, Int, Double> {
        val s = min(1.0, maxShort.toDouble() / min(w, h))
        val sw = pyRound(w * s); val sh = pyRound(h * s)
        return Triple(sw - sw % 16, sh - sh % 16, s)
    }

    /** Encoder bitrate (bits/s) for W x H at fps. */
    fun bitrate(w: Int, h: Int, fps: Double): Int =
        (w.toDouble() * h * fps * 0.25).coerceIn(2_000_000.0, 20_000_000.0).toInt()

    /** Conservative output-size estimate used for the free-space check (video + audio + 8 MB). */
    fun estimateBytes(bitrate: Int, seconds: Double, audioBitrate: Int = 320_000): Long =
        ((bitrate + audioBitrate) / 8.0 * seconds * 1.2).toLong() + 8_000_000L

    fun effectiveFps(requested: Double, srcFps: Double): Double {
        val src = if (srcFps.isFinite() && srcFps > 0) srcFps else 30.0
        return min(if (requested <= 0) src else requested, min(src, MAX_FPS))
    }
}

/**
 * Streaming version of video_pipeline.frame_times: feed the presentation time of every decoded frame
 * (in order); accept() says whether the frame is used. Output slot k is the first frame at/after
 * start + k/fps, so reduced frame rates keep the original timing.
 */
class FrameSelector(private val start: Double, private val end: Double, private val fps: Double) {
    private var slot = 0
    fun accept(t: Double): Boolean {
        if (t < start - 1e-9 || t >= end - 1e-9) return false
        if (t - start >= slot / fps - 1e-9) {
            slot++
            while (slot / fps <= t - start + 1e-9) slot++
            return true
        }
        return false
    }

    companion object {
        /** Indices of frames i/srcFps in [0, duration) that get used (for tests / estimates). */
        fun indices(duration: Double, srcFps: Double, start: Double, end: Double, fps: Double): List<Int> {
            val sel = FrameSelector(start, end, fps); val n = floor(duration * srcFps + 1e-6).toInt()
            return (0 until n).filter { sel.accept(it / srcFps) }
        }
    }
}

object FaceTracker {
    const val N = 468

    fun toPts(f: FaceData): DoubleArray = DoubleArray(N * 2) { if (it % 2 == 0) f.x(it / 2).toDouble() else f.y(it / 2).toDouble() }
    fun toFace(p: DoubleArray): FaceData = FaceData(FloatArray(N * 3).also { a ->
        for (i in 0 until N) { a[i * 3] = p[i * 2].toFloat(); a[i * 3 + 1] = p[i * 2 + 1].toFloat() }
    })

    /** x0, y0, x1, y1 */
    fun bbox(p: DoubleArray): DoubleArray {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in 0 until p.size / 2) {
            val x = p[i * 2]; val y = p[i * 2 + 1]
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        return doubleArrayOf(x0, y0, x1, y1)
    }

    fun iou(a: DoubleArray, b: DoubleArray): Double {
        val ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0])); val iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
        val inter = ix * iy; val u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (u > 0) inter / u else 0.0
    }

    /** IoU when boxes overlap well, otherwise a small centre-distance score (fast motion at low fps). */
    fun matchScore(tb: DoubleArray, db: DoubleArray): Double {
        val s = iou(tb, db)
        if (s >= 0.1) return s
        val tcx = (tb[0] + tb[2]) / 2; val tcy = (tb[1] + tb[3]) / 2; val dcx = (db[0] + db[2]) / 2; val dcy = (db[1] + db[3]) / 2
        val r = 0.5 * max(tb[2] - tb[0], db[2] - db[0]); val d = sqrt((tcx - dcx) * (tcx - dcx) + (tcy - dcy) * (tcy - dcy))
        return if (d < r) 0.1 * (1 - d / r) else 0.0
    }

    /** Two detections of the same face (MediaPipe occasionally returns duplicates). */
    fun isDup(a: DoubleArray, b: DoubleArray): Boolean {
        val ba = bbox(a); val bb = bbox(b)
        if (iou(ba, bb) > 0.3) return true
        val d = hypot((ba[0] + ba[2]) / 2 - (bb[0] + bb[2]) / 2, (ba[1] + ba[3]) / 2 - (bb[1] + bb[3]) / 2)
        return d < 0.5 * min(ba[2] - ba[0], bb[2] - bb[0])
    }

    fun dedupe(faces: List<DoubleArray>): List<DoubleArray> {
        val keep = ArrayList<DoubleArray>()
        for (f in faces) if (keep.none { isDup(f, it) }) keep.add(f)
        return keep
    }

    /** Greedy frame-to-frame matching. dets[frame] = faces; returns tracks as frame -> points. */
    fun track(dets: List<List<DoubleArray>>, maxGap: Int): List<java.util.TreeMap<Int, DoubleArray>> {
        val tracks = ArrayList<java.util.TreeMap<Int, DoubleArray>>()
        val lastF = ArrayList<Int>(); val lastB = ArrayList<DoubleArray>()
        class Cand(val s: Double, val t: Int, val j: Int)
        for ((f, ds) in dets.withIndex()) {
            val boxes = ds.map { bbox(it) }
            val cand = ArrayList<Cand>()
            for (t in tracks.indices) {
                if (f - lastF[t] > maxGap) continue
                for (j in boxes.indices) {
                    val s = matchScore(lastB[t], boxes[j])
                    if (s > 0) cand.add(Cand(s, t, j))
                }
            }
            cand.sortWith(compareBy<Cand>({ -it.s }, { it.t }, { it.j }))
            val usedT = HashSet<Int>(); val usedD = HashSet<Int>()
            for (c in cand) {
                if (c.t in usedT || c.j in usedD) continue
                usedT.add(c.t); usedD.add(c.j)
                tracks[c.t][f] = ds[c.j]; lastF[c.t] = f; lastB[c.t] = boxes[c.j]
            }
            for (j in ds.indices) if (j !in usedD) {
                tracks.add(java.util.TreeMap<Int, DoubleArray>().also { it[f] = ds[j] }); lastF.add(f); lastB.add(boxes[j])
            }
        }
        return tracks
    }

    /** Which source face each track gets (-1 = none), and the pairing frame (-1 = no faces at all). */
    class Pairing(val assign: IntArray, val frame: Int)

    /**
     * Pair tracks left-to-right on the first frame where min(sourceCount, max visible) solid tracks
     * are visible; [rotation] implements Flip. Later tracks that don't overlap in time with a paired
     * track inherit its source face from the nearest one (a person re-appearing after a gap).
     */
    fun pair(tracks: List<Map<Int, DoubleArray>>, nSrc: Int, rotation: Int): Pairing {
        val assign = IntArray(tracks.size) { -1 }
        if (tracks.isEmpty()) return Pairing(assign, -1)
        val nf = tracks.maxOf { it.keys.max() } + 1
        val solidLen = min(max(3, nf / 4), 8)
        val solid = tracks.indices.filter { tracks[it].size >= solidLen }.ifEmpty { tracks.indices.toList() }
        val vis = List(nf) { f -> solid.filter { tracks[it].containsKey(f) } }
        val target = min(nSrc, vis.maxOf { it.size })
        if (target == 0) return Pairing(assign, -1)
        val pf = (0 until nf).first { vis[it].size >= target }
        fun cx(k: Int) = tracks[k][pf]!!.let { p -> var s = 0.0; for (i in 0 until p.size / 2) s += p[i * 2]; s / (p.size / 2) }
        fun wd(k: Int) = bbox(tracks[k][pf]!!).let { it[2] - it[0] }
        val biggest = if (nSrc >= 2) vis[pf].sortedBy { -wd(it) }.take(max(nSrc, 1)) else vis[pf]
        val first = biggest.sortedBy { cx(it) }
        for ((i, k) in first.withIndex()) assign[k] = if (nSrc >= 2) Math.floorMod(i + rotation, nSrc) else 0
        val order = tracks.indices.filter { assign[it] < 0 }.sortedBy { tracks[it].keys.min() }
        for (k in order) {
            val fk = tracks[k].keys; val f0 = fk.min(); val c = mean(tracks[k][f0]!!)
            var bestD = Double.MAX_VALUE; var bestJ = -1
            for (j in tracks.indices) {
                if (assign[j] < 0 || j == k || tracks[j].keys.any { it in fk }) continue
                val fj = tracks[j].keys.minWith(compareBy<Int> { abs(it - f0) }.thenBy { it })
                val cj = mean(tracks[j][fj]!!); val d = hypot(cj[0] - c[0], cj[1] - c[1])
                if (d < bestD) { bestD = d; bestJ = j }
            }
            if (bestJ >= 0) assign[k] = assign[bestJ]
        }
        return Pairing(assign, pf)
    }

    fun mean(p: DoubleArray): DoubleArray {
        var sx = 0.0; var sy = 0.0; val n = p.size / 2
        for (i in 0 until n) { sx += p[i * 2]; sy += p[i * 2 + 1] }
        return doubleArrayOf(sx / n, sy / n)
    }
}

/** One-Euro filter run forward and backward (averaged) over each contiguous run of a track. */
object Smoother {
    var MIN_CUTOFF = 1.0; var BETA = 3.0; var D_CUTOFF = 1.0
    const val MAX_FILL = 2

    private fun alpha(cutoff: Double, te: Double): Double { val tau = 1.0 / (2 * PI * cutoff); return 1.0 / (1.0 + tau / te) }

    /** Bridge detection drop-outs of <= [maxFill] frames by linear interpolation. */
    fun fillGaps(tr: Map<Int, DoubleArray>, maxFill: Int = MAX_FILL): java.util.TreeMap<Int, DoubleArray> {
        val out = java.util.TreeMap(tr); val frames = tr.keys.sorted()
        for (i in 0 until frames.size - 1) {
            val a = frames[i]; val b = frames[i + 1]
            if (b - a in 2..maxFill + 1) {
                val pa = tr[a]!!; val pb = tr[b]!!
                for (f in a + 1 until b) {
                    val u = (f - a).toDouble() / (b - a)
                    out[f] = DoubleArray(pa.size) { (1 - u) * pa[it] + u * pb[it] }
                }
            }
        }
        return out
    }

    /** Whole-face One-Euro: cutoff from the centroid speed in face-widths per second. */
    fun oneEuro(seq: List<DoubleArray>, te: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(seq.size); out.add(seq[0].copyOf()); var sHat = 0.0
        val ad = alpha(D_CUTOFF, te)
        for (i in 1 until seq.size) {
            val c1 = FaceTracker.mean(seq[i]); val c0 = FaceTracker.mean(seq[i - 1])
            val bb = FaceTracker.bbox(seq[i]); val w = bb[2] - bb[0]
            val raw = sqrt((c1[0] - c0[0]) * (c1[0] - c0[0]) + (c1[1] - c0[1]) * (c1[1] - c0[1])) / te / max(w, 1.0)
            sHat = ad * raw + (1 - ad) * sHat
            val a = alpha(MIN_CUTOFF + BETA * sHat, te)
            val prev = out[i - 1]; val cur = seq[i]
            out.add(DoubleArray(cur.size) { a * cur[it] + (1 - a) * prev[it] })
        }
        return out
    }

    fun smoothTrack(track: Map<Int, DoubleArray>, fps: Double): java.util.TreeMap<Int, DoubleArray> {
        val tr = fillGaps(track); val te = 1.0 / fps; val out = java.util.TreeMap<Int, DoubleArray>()
        val frames = tr.keys.sorted(); var i = 0
        while (i < frames.size) {
            var j = i
            while (j + 1 < frames.size && frames[j + 1] == frames[j] + 1) j++
            val run = frames.subList(i, j + 1); val seq = run.map { tr[it]!! }
            val fw = oneEuro(seq, te); val bw = oneEuro(seq.reversed(), te).reversed()
            for ((k, f) in run.withIndex()) out[f] = DoubleArray(seq[k].size) { (fw[k][it] + bw[k][it]) / 2 }
            i = j + 1
        }
        return out
    }
}

/** Remaining-time estimate from an exponential moving average of the per-frame time. */
class EtaEstimator(private val smoothing: Double = 0.15) {
    private var avg = -1.0
    fun add(seconds: Double) { avg = if (avg < 0) seconds else avg + smoothing * (seconds - avg) }
    fun remaining(framesLeft: Int): Double? = if (avg < 0) null else avg * framesLeft
    companion object {
        fun format(s: Double): String { val t = s.roundToInt(); return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60) }
    }
}

/** Keeps muxer timestamps strictly increasing and relative to the trim start. */
class PtsMapper(private val startUs: Long) {
    private var last = -1L
    fun map(ptsUs: Long): Long { var p = max(0L, ptsUs - startUs); if (p <= last) p = last + 1; last = p; return p }
    companion object {
        /** Audio sample kept for trim [startUs, endUs)? Returns the output timestamp or null. */
        fun audio(sampleUs: Long, startUs: Long, endUs: Long): Long? = if (sampleUs < startUs || sampleUs >= endUs) null else sampleUs - startUs
    }
}

/** A plane of a YUV_420_888 image (android.media.Image.Plane, or a test buffer). */
class YuvPlane(val buf: ByteBuffer, val rowStride: Int, val pixelStride: Int)

/**
 * YUV <-> RGB for the MediaCodec path. [bt709] selects the matrix (BT.709 for HD, BT.601 for SD);
 * both use limited ("TV") range, which is what phone video uses.
 */
object Yuv {
    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    /**
     * Decode a (cropped) YUV frame of size cw x ch at offset (cx, cy) into an upright W x H RgbImage:
     * rotate by [rotation] (clockwise degrees needed for display), scale by [s] and centre-crop exactly
     * like video_pipeline.prep. Downscales average a k x k block (k = floor(1/s)) to avoid aliasing.
     */
    fun toRgb(y: YuvPlane, u: YuvPlane, v: YuvPlane, cx: Int, cy: Int, cw: Int, ch: Int, rotation: Int,
              outW: Int, outH: Int, s: Double, bt709: Boolean, out: RgbImage = RgbImage(outW, outH)): RgbImage {
        val rot = Math.floorMod(rotation, 360)
        val uw = if (rot == 90 || rot == 270) ch else cw; val uh = if (rot == 90 || rot == 270) cw else ch
        val sw = Math.rint(uw * s).toInt(); val sh = Math.rint(uh * s).toInt()
        val x0 = (sw - outW) / 2; val y0 = (sh - outH) / 2
        val sx = uw.toDouble() / sw; val sy = uh.toDouble() / sh
        val k = max(1, floor(min(sx, sy) + 1e-9).toInt())
        val (kr, kgU, kgV, kb) = if (bt709) listOf(1.793, 0.213, 0.533, 2.112) else listOf(1.596, 0.391, 0.813, 2.018)
        val yb = y.buf; val ub = u.buf; val vb = v.buf
        val px = out.px; var o = 0
        for (oy in 0 until outH) {
            val fy = (oy + y0 + 0.5) * sy - 0.5
            for (ox in 0 until outW) {
                val fx = (ox + x0 + 0.5) * sx - 0.5
                var ysum = 0; var dxs = 0.0; var dys = 0.0
                for (j in 0 until k) for (i in 0 until k) {
                    val ux = (fx + (i - (k - 1) / 2.0)).roundToInt().coerceIn(0, uw - 1)
                    val uy = (fy + (j - (k - 1) / 2.0)).roundToInt().coerceIn(0, uh - 1)
                    val dx: Int; val dy: Int
                    when (rot) { 90 -> { dx = uy; dy = ch - 1 - ux }; 180 -> { dx = cw - 1 - ux; dy = ch - 1 - uy }
                                 270 -> { dx = cw - 1 - uy; dy = ux }; else -> { dx = ux; dy = uy } }
                    ysum += yb.get((dy + cy) * y.rowStride + (dx + cx) * y.pixelStride).toInt() and 255
                    dxs += dx; dys += dy
                }
                val kk = k * k
                val cxx = ((dxs / kk).roundToInt() + cx) / 2; val cyy = ((dys / kk).roundToInt() + cy) / 2
                val yy = (ysum.toDouble() / kk - 16.0) * 1.164
                val uu = (ub.get(cyy * u.rowStride + cxx * u.pixelStride).toInt() and 255) - 128.0
                val vv = (vb.get(cyy * v.rowStride + cxx * v.pixelStride).toInt() and 255) - 128.0
                px[o] = clamp((yy + kr * vv).roundToInt()).toByte()
                px[o + 1] = clamp((yy - kgU * uu - kgV * vv).roundToInt()).toByte()
                px[o + 2] = clamp((yy + kb * uu).roundToInt()).toByte()
                o += 3
            }
        }
        return out
    }

    /** Write an RgbImage into YUV 4:2:0 planes (limited range); chroma is the 2x2 average. */
    fun fromRgb(img: RgbImage, y: YuvPlane, u: YuvPlane, v: YuvPlane, bt709: Boolean) {
        val w = img.width; val h = img.height; val px = img.px
        val (yr, yg, ybc) = if (bt709) Triple(0.2126, 0.7152, 0.0722) else Triple(0.299, 0.587, 0.114)
        val cbS = 0.5 / (1 - ybc); val crS = 0.5 / (1 - yr)
        for (r in 0 until h) {
            var i = r * w * 3
            for (c in 0 until w) {
                val R = px[i].toInt() and 255; val G = px[i + 1].toInt() and 255; val B = px[i + 2].toInt() and 255
                val yl = yr * R + yg * G + ybc * B
                y.buf.put(r * y.rowStride + c * y.pixelStride, clamp((16 + yl * 219.0 / 255.0).roundToInt()).toByte())
                i += 3
            }
        }
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            var R = 0; var G = 0; var B = 0
            for (dy in 0..1) for (dx in 0..1) {
                val i = ((r * 2 + dy) * w + c * 2 + dx) * 3
                R += px[i].toInt() and 255; G += px[i + 1].toInt() and 255; B += px[i + 2].toInt() and 255
            }
            val rr = R / 4.0; val gg = G / 4.0; val bb = B / 4.0
            val yl = yr * rr + yg * gg + ybc * bb
            val cb = (bb - yl) * cbS; val cr = (rr - yl) * crS
            u.buf.put(r * u.rowStride + c * u.pixelStride, clamp((128 + cb * 224.0 / 255.0).roundToInt()).toByte())
            v.buf.put(r * v.rowStride + c * v.pixelStride, clamp((128 + cr * 224.0 / 255.0).roundToInt()).toByte())
        }
    }
}
