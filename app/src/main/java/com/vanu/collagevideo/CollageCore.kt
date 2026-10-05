package com.vanu.collagevideo

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Collage-specific logic that does not touch Android APIs (unit-tested on the JVM):
 *  - CollageOrder: number the faces found in the collage photo in reading order (rows top to bottom,
 *    left to right inside a row), so "Face 1, Face 2, ..." matches what the user sees.
 *  - People: group the face tracks of the reference video into people (a person who leaves and comes
 *    back gets one entry) and pick which collage face each person receives by default.
 *  - SafetyPlan: which frames of the reference video the NSFW filter checks.
 */

/** Face box in image pixels: x0, y0, x1, y1. */
typealias Box = DoubleArray

object CollageOrder {
    /** Reading order of face boxes: returns indices into [boxes]. */
    fun order(boxes: List<Box>): List<Int> {
        if (boxes.isEmpty()) return emptyList()
        val cy = boxes.map { (it[1] + it[3]) / 2 }; val cx = boxes.map { (it[0] + it[2]) / 2 }
        val hgt = boxes.map { it[3] - it[1] }
        val byY = boxes.indices.sortedWith(compareBy<Int>({ cy[it] }, { cx[it] }))
        val rows = ArrayList<MutableList<Int>>()
        for (i in byY) {
            val row = rows.lastOrNull()
            // same row when the centre is within half a (mean) face height of the row's mean centre
            if (row != null) {
                val rc = row.map { cy[it] }.average(); val rh = row.map { hgt[it] }.average()
                if (abs(cy[i] - rc) < 0.5 * max(rh, hgt[i])) { row.add(i); continue }
            }
            rows.add(mutableListOf(i))
        }
        return rows.flatMap { r -> r.sortedBy { cx[it] } }
    }

    /** Faces narrower than this (pixels in the collage) are too small for a usable identity. */
    const val MIN_FACE_PX = 24.0
    /** Faces narrower than this give a weaker likeness; the UI shows a hint. */
    const val GOOD_FACE_PX = 64.0
    const val MAX_FACES = 12
}

/** One person in the reference video = one or more non-overlapping face tracks. */
class Person(val tracks: IntArray, val frames: Int, val firstFrame: Int, val major: Boolean)

object People {
    /** Tracks shorter than this are "minor" (brief / unreliable); same rule as Face Swap Video's pairing. */
    fun solidLen(nFrames: Int) = min(max(3, nFrames / 4), 8)

    /**
     * Group tracks into people: tracks are visited by start frame; a track joins an existing person
     * when it never overlaps that person in time and starts close (< 1.5 face widths) to where the
     * person was last/next seen — i.e. someone who left the frame or was lost for > 1 s and came back.
     */
    fun group(tracks: List<Map<Int, DoubleArray>>): List<Person> {
        if (tracks.isEmpty()) return emptyList()
        val nf = tracks.maxOf { it.keys.max() } + 1
        val order = tracks.indices.sortedWith(compareBy<Int>({ tracks[it].keys.min() }, { it }))
        val groups = ArrayList<MutableList<Int>>()
        for (t in order) {
            val tk = tracks[t].keys; val f0 = tk.min(); val p0 = tracks[t][f0]!!
            val c0 = FaceTracker.mean(p0); val bb = FaceTracker.bbox(p0); val w = max(bb[2] - bb[0], 1.0)
            var best = -1; var bestD = Double.MAX_VALUE
            for ((gi, g) in groups.withIndex()) {
                if (g.any { o -> tracks[o].keys.any { it in tk } }) continue
                // nearest-in-time point of the group
                var nearF = -1; var nearT = -1
                for (o in g) for (f in tracks[o].keys) if (nearF < 0 || abs(f - f0) < abs(nearF - f0)) { nearF = f; nearT = o }
                val c = FaceTracker.mean(tracks[nearT][nearF]!!)
                val d = hypot(c[0] - c0[0], c[1] - c0[1])
                if (d < 1.5 * w && d < bestD) { bestD = d; best = gi }
            }
            if (best >= 0) groups[best].add(t) else groups.add(mutableListOf(t))
        }
        val solid = solidLen(nf)
        return groups.map { g ->
            val frames = g.sumOf { tracks[it].size }
            Person(g.sorted().toIntArray(), frames, g.minOf { tracks[it].keys.min() }, frames >= solid)
        }
    }

    class Layout(val order: List<Int>, val keyFrame: Int)

    /**
     * Display / default-assignment order of people: major people first. Among major people, those
     * visible on the first frame where most of them are visible come first, biggest first up to
     * [nSrc], then left to right (both averaged over all frames where they are all visible);
     * everyone else by first appearance.
     */
    fun layout(people: List<Person>, tracks: List<Map<Int, DoubleArray>>, nSrc: Int): Layout {
        if (people.isEmpty()) return Layout(emptyList(), -1)
        val nf = tracks.maxOf { it.keys.max() } + 1
        val major = people.indices.filter { people[it].major }.ifEmpty { people.indices.toList() }
        fun pointAt(p: Int, f: Int): DoubleArray? { for (t in people[p].tracks) tracks[t][f]?.let { return it }; return null }
        var kf = 0; var kc = -1
        for (f in 0 until nf) { val c = major.count { pointAt(it, f) != null }; if (c > kc) { kc = c; kf = f } }
        val vis = major.filter { pointAt(it, kf) != null }
        // size and left/right are averaged over every frame where all of them are visible, so one bad detection
        // (e.g. a pose instance that briefly covers both people of a couple) can't swap the order
        val co = (0 until nf).filter { f -> vis.all { pointAt(it, f) != null } }.ifEmpty { listOf(kf) }
        val widthM = vis.associateWith { p -> co.sumOf { f -> FaceTracker.bbox(pointAt(p, f)!!).let { it[2] - it[0] } } / co.size }
        val cxM = vis.associateWith { p -> co.sumOf { f -> FaceTracker.mean(pointAt(p, f)!!)[0] } / co.size }
        fun width(p: Int) = widthM[p]!!
        fun cx(p: Int) = cxM[p]!!
        val take = max(1, nSrc)
        val main = vis.sortedBy { -width(it) }.take(take).sortedBy { cx(it) }
        val restVis = vis.filter { it !in main }.sortedBy { cx(it) }
        val rest = people.indices.filter { it !in main && it !in restVis }
            .sortedWith(compareBy<Int>({ if (people[it].major) 0 else 1 }, { people[it].firstFrame }))
        return Layout(main + restVis + rest, kf)
    }

    /**
     * Default collage face per person (-1 = keep the original face). People in layout order get
     * faces 0, 1, 2 … shifted by [shift] ("Shuffle"). When there are more people than collage faces,
     * [repeat] reuses faces round-robin; otherwise the extra people keep their own faces. Minor people
     * (brief detections) keep their own face unless [repeat] is on.
     */
    fun autoAssign(people: List<Person>, layout: Layout, nSrc: Int, shift: Int, repeat: Boolean): IntArray {
        val a = IntArray(people.size) { -1 }
        if (nSrc <= 0) return a
        var k = 0
        for (p in layout.order) {
            if (!people[p].major && !repeat) continue
            if (k >= nSrc && !repeat) break
            a[p] = Math.floorMod(k + shift, nSrc); k++
        }
        return a
    }

    /** Per-track source face from a per-person assignment. */
    fun trackAssign(people: List<Person>, personFace: IntArray, nTracks: Int): IntArray {
        val out = IntArray(nTracks) { -1 }
        for ((i, p) in people.withIndex()) for (t in p.tracks) out[t] = personFace.getOrElse(i) { -1 }
        return out
    }

    /** Frame index (into the selected frames) where [p] shows its biggest face, for the thumbnail. */
    fun bestFrame(p: Person, tracks: List<Map<Int, DoubleArray>>): Pair<Int, Int> {
        var bf = -1; var bt = -1; var bw = -1.0
        for (t in p.tracks) for ((f, pts) in tracks[t]) {
            val b = FaceTracker.bbox(pts); val w = b[2] - b[0]
            if (w > bw) { bw = w; bf = f; bt = t }
        }
        return bf to bt
    }
}

object SafetyPlan {
    const val INTERVAL_S = 1.0
    const val MAX_SAMPLES = 24

    /**
     * The safety filter checks every [step]-th selected frame of the reference video, starting with
     * the first: one frame every [INTERVAL_S] s, but never more than about [MAX_SAMPLES] checks.
     */
    fun step(expectedFrames: Int, fps: Double): Int =
        max(max(1, Math.rint(fps * INTERVAL_S).toInt()), (expectedFrames + MAX_SAMPLES - 1) / MAX_SAMPLES)

    fun isSample(index: Int, step: Int) = index % step == 0

    fun blocked(score: Float, threshold: Float) = score > threshold
}

/** NSFW filter strictness. The filter itself is always on; there is deliberately no "off" (same policy as AI Image Create).
 *  Default is [RELAXED] (threshold 0.85), matching AI Image Create — block only high-confidence NSFW. */
enum class FilterStrictness(val threshold: Float, val label: String, val help: String) {
    STANDARD(0.5f, "Standard", "Blocks anything that looks likely to be explicit."),
    RELAXED(0.85f, "Relaxed", "Blocks only clearly explicit content."),
}
