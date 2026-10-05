package com.vanu.collagevideo

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** One person found by the pose model: box, head point, hip line and their (ownership-resolved) soft mask. */
class BodyInstance(
    val box: IntArray,              // x0, y0, x1, y1 (exclusive)
    val head: DoubleArray?,         // mean of the visible face landmarks
    val hipY: Double,
    val pts: Array<DoubleArray>,    // 33 x (x, y, visibility)
    val mask: FloatArray,           // W x H, 0..1, only where this person owns the pixel
    val width: Int, val height: Int,
)

private fun mapModel(f: File) = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }

private fun floats(img: MPImage): FloatArray {
    val bb = ByteBufferExtractor.extract(img).order(ByteOrder.nativeOrder())
    bb.rewind()
    val fb = bb.asFloatBuffer(); return FloatArray(fb.remaining()).also { fb.get(it) }
}

/** MediaPipe PoseLandmarker (multi-person, with per-person segmentation masks). */
class BodyDetector(context: android.content.Context, model: File, maxPoses: Int = 6) : Closeable {
    private val lm: PoseLandmarker = PoseLandmarker.createFromOptions(context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(mapModel(model)).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.IMAGE).setNumPoses(maxPoses)
            .setMinPoseDetectionConfidence(0.4f).setMinPosePresenceConfidence(0.4f)
            .setOutputSegmentationMasks(true).build())

    fun detect(bmp: Bitmap): List<BodyInstance> {
        val W = bmp.width; val H = bmp.height
        val r = lm.detect(BitmapImageBuilder(bmp).build())
        val masks = r.segmentationMasks().orElse(emptyList())
        val raw = ArrayList<BodyInstance>()
        for ((k, lms) in r.landmarks().withIndex()) {
            if (k >= masks.size) break
            val m = floats(masks[k])
            if (m.size != W * H) continue
            var x0 = W; var y0 = H; var x1 = -1; var y1 = -1; var cnt = 0
            for (y in 0 until H) for (x in 0 until W) if (m[y * W + x] > 0.5f) {
                cnt++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            if (cnt < 200) continue
            val pts = Array(lms.size) { i -> val l = lms[i]; doubleArrayOf(l.x() * W.toDouble(), l.y() * H.toDouble(), l.visibility().orElse(0f).toDouble()) }
            raw.add(BodyInstance(intArrayOf(x0, y0, x1 + 1, y1 + 1), BodyMath.head(pts), BodyMath.hipY(pts, y0, y1 + 1), pts, m, W, H))
        }
        BodyMath.resolveOwnership(raw.map { it.mask })
        return raw
    }

    override fun close() = lm.close()
}

/** MediaPipe selfie multiclass segmenter: 0 background, 1 hair, 2 body skin, 3 face skin, 4 clothes, 5 others. */
class PartSegmenter(context: android.content.Context, model: File) : Closeable {
    private val seg: ImageSegmenter = ImageSegmenter.createFromOptions(context,
        ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(mapModel(model)).setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.IMAGE).setOutputConfidenceMasks(true).setOutputCategoryMask(false).build())

    /** Class confidences resized to the crop: [6][cw * ch]. */
    fun segment(crop: Bitmap): Array<FloatArray> {
        val r = seg.segment(BitmapImageBuilder(crop).build())
        val ms = r.confidenceMasks().orElse(emptyList())
        val cw = crop.width; val ch = crop.height
        return Array(6) { c ->
            if (c >= ms.size) FloatArray(cw * ch) else {
                val img = ms[c]; val f = floats(img)
                BodyMath.resize(f, img.width, img.height, cw, ch)
            }
        }
    }

    override fun close() = seg.close()

    companion object { const val HAIR = 1; const val BODY = 2; const val FACE = 3; const val CLOTHES = 4 }
}
