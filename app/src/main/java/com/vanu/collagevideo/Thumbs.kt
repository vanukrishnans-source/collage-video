package com.vanu.collagevideo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.vanu.faceswap.core.FaceData
import com.vanu.faceswap.core.RgbImage

/** Small face thumbnails and the numbered collage preview. */
object Thumbs {
    const val SIZE = 128

    /** Square crop around a face (1.6x its box), scaled to [SIZE]. [pts] = x0,y0,x1,y1,… */
    fun crop(img: RgbImage, pts: DoubleArray, size: Int = SIZE): Bitmap {
        val b = FaceTracker.bbox(pts)
        val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2
        val half = maxOf(b[2] - b[0], b[3] - b[1]) * 0.8
        val out = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val sx = (cx - half + (x + 0.5) * 2 * half / size).toInt().coerceIn(0, img.width - 1)
            val sy = (cy - half + (y + 0.5) * 2 * half / size).toInt().coerceIn(0, img.height - 1)
            val i = (sy * img.width + sx) * 3
            out[y * size + x] = (0xff shl 24) or ((img.px[i].toInt() and 255) shl 16) or ((img.px[i + 1].toInt() and 255) shl 8) or (img.px[i + 2].toInt() and 255)
        }
        return Bitmap.createBitmap(out, size, size, Bitmap.Config.ARGB_8888)
    }

    fun faceBox(f: FaceData): DoubleArray = FaceTracker.bbox(FaceTracker.toPts(f))

    /** Crop of collage face [f] from bitmap [src]. */
    fun cropBitmap(src: Bitmap, f: FaceData, size: Int = SIZE): Bitmap {
        val b = faceBox(f)
        val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2
        val half = maxOf(b[2] - b[0], b[3] - b[1]) * 0.8
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.DKGRAY)
            drawBitmap(src, android.graphics.Rect((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt()),
                android.graphics.Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    /** Collage with a numbered badge on every face (dimmed outline for faces switched off). */
    fun annotate(src: Bitmap, faces: List<FaceData>, enabled: Set<Int>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val unit = maxOf(out.width, out.height) / 60f
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = unit * 0.35f }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        for ((i, f) in faces.withIndex()) {
            val b = faceBox(f); val on = i in enabled
            stroke.color = if (on) Color.rgb(255, 193, 7) else Color.argb(160, 160, 160, 160)
            c.drawRoundRect(RectF(b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat()), unit, unit, stroke)
            val r = maxOf(unit * 1.4f, ((b[2] - b[0]) * 0.18).toFloat().coerceAtMost(unit * 3f))
            val bx = b[0].toFloat() + r * 0.2f; val by = b[1].toFloat() + r * 0.2f
            fill.color = if (on) Color.rgb(230, 81, 0) else Color.GRAY
            c.drawCircle(bx, by, r, fill)
            text.textSize = r * 1.2f
            c.drawText("${i + 1}", bx, by + r * 0.42f, text)
        }
        return out
    }
}
