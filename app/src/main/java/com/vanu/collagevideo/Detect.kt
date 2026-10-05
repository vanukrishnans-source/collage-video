package com.vanu.collagevideo

import android.graphics.Bitmap
import com.vanu.faceswap.core.FaceData
import com.vanu.faceswap.core.FaceDetector

/**
 * Video frames (same as Face Swap Video): full frame, plus a 2x2 tile scan when fewer than
 * [expected] faces were found, then duplicate detections removed. Points as x,y doubles.
 */
fun FaceDetector.detectFrame(bitmap: Bitmap, expected: Int): List<DoubleArray> {
    val faces: MutableList<FaceData> = detectRegion(bitmap, 0, 0, bitmap.width, bitmap.height).toMutableList()
    if (faces.size < expected) {
        val w = bitmap.width; val h = bitmap.height
        val tw = (w / 2.0 * 1.5).toInt().coerceAtMost(w); val th = (h / 2.0 * 1.5).toInt().coerceAtMost(h)
        for (gy in 0 until 2) for (gx in 0 until 2) {
            val x0 = Math.rint((w - tw) * gx.toDouble()).toInt(); val y0 = Math.rint((h - th) * gy.toDouble()).toInt()
            for (f in detectRegion(bitmap, x0, y0, tw, th)) mergeFace(faces, f)
        }
    }
    return FaceTracker.dedupe(faces.map { FaceTracker.toPts(it) })
}

/**
 * Collage photo: a collage or group photo usually has many small faces, so the whole picture is
 * scanned plus overlapping 2x2, 3x3 and 4x4 tiles (the detector itself works at 128 px). Faces that
 * are too small to give a usable identity are dropped; the rest are returned in reading order
 * (rows top to bottom, left to right), at most [CollageOrder.MAX_FACES].
 */
fun FaceDetector.detectCollage(bitmap: Bitmap): List<FaceData> {
    val faces = detectRegion(bitmap, 0, 0, bitmap.width, bitmap.height).toMutableList()
    val w = bitmap.width; val h = bitmap.height
    for (grid in intArrayOf(2, 3, 4)) {
        val tw = (w / grid.toFloat() * 1.5f).toInt().coerceAtMost(w)
        val th = (h / grid.toFloat() * 1.5f).toInt().coerceAtMost(h)
        if (minOf(tw, th) < 96) break
        for (gy in 0 until grid) for (gx in 0 until grid) {
            val x0 = Math.round((w - tw) * gx / (grid - 1).toFloat())
            val y0 = Math.round((h - th) * gy / (grid - 1).toFloat())
            for (f in detectRegion(bitmap, x0, y0, tw, th)) mergeFace(faces, f)
        }
    }
    val pts = faces.map { FaceTracker.toPts(it) }
    val keep = pts.indices.filter { FaceTracker.bbox(pts[it]).let { b -> b[2] - b[0] } >= CollageOrder.MIN_FACE_PX }
    val order = CollageOrder.order(keep.map { FaceTracker.bbox(pts[it]) }).map { keep[it] }
    return order.take(CollageOrder.MAX_FACES).map { faces[it] }
}

/**
 * Video frames with performers: whole frame plus a close look around every performer's head (faces of people
 * walking in a wide shot are often only 50-60 px and the detector works at 128 px), duplicates removed.
 */
fun FaceDetector.detectWithBodies(bitmap: Bitmap, bodies: List<BodyInstance>): List<DoubleArray> {
    val faces: MutableList<FaceData> = detectRegion(bitmap, 0, 0, bitmap.width, bitmap.height).toMutableList()
    for (b in bodies) {
        val c = BodyMath.headCrop(b.pts, bitmap.width, bitmap.height) ?: continue
        for (f in detectRegion(bitmap, c[0], c[1], c[2] - c[0], c[3] - c[1])) mergeFace(faces, f)
    }
    return FaceTracker.dedupe(faces.map { FaceTracker.toPts(it) })
}
