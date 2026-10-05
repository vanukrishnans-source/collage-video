package com.vanu.collagevideo

import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Appearance transfer maths (pure Kotlin, unit-tested; mirrors reference/collage_pipeline.py).
 *
 * The collage people become the performers of the reference video: the performer's pose, motion, body shape,
 * lighting and shading stay; their hair, skin tone and outfit colours become the collage person's (Reinhard
 * colour transfer in CIE Lab, contrast clamped, at the scene's exposure), and the face gets the collage
 * person's identity (inswapper, done elsewhere).
 */

/** Weighted Lab moments of one region (hair / skin / upper clothes / lower clothes). */
class LabStats {
    var w = 0.0; val s = DoubleArray(3); val q = DoubleArray(3)
    fun add(L: Float, a: Float, b: Float) { w += 1; s[0] += L; s[1] += a; s[2] += b; q[0] += L.toDouble() * L; q[1] += a.toDouble() * a; q[2] += b.toDouble() * b }
    fun merge(o: LabStats): LabStats = LabStats().also { r -> r.w = w + o.w; for (i in 0..2) { r.s[i] = s[i] + o.s[i]; r.q[i] = q[i] + o.q[i] } }
    val ok get() = w >= MIN_PIXELS
    fun mean() = DoubleArray(3) { s[it] / max(w, 1.0) }
    fun std() = mean().let { m -> DoubleArray(3) { sqrt(max(q[it] / max(w, 1.0) - m[it] * m[it], 1.0)) } }
    override fun toString() = if (!ok) "-" else "n=${w.toLong()} L/a/b=${mean().joinToString("/") { "%.1f".format(it) }}"
    companion object { const val MIN_PIXELS = 200.0 }
}

enum class Region { HAIR, SKIN, UPPER, LOWER }

/** Per-channel gain/offset in Lab, plus the performer's own stats (for the colour gate). */
class Xfer(val g: FloatArray, val o: FloatArray, val mt: FloatArray, val st: FloatArray)

/** What to transfer (UI switches). */
data class TransferOptions(val face: Boolean = true, val hair: Boolean = true, val skin: Boolean = true, val outfit: Boolean = true) {
    val colours get() = hair || skin || outfit
}

object BodyMath {
    val STRENGTH = mapOf(Region.HAIR to 0.9f, Region.SKIN to 0.75f, Region.UPPER to 1.0f, Region.LOWER to 1.0f)
    const val GAIN_L_MIN = 0.6; const val GAIN_L_MAX = 1.4
    const val GAIN_AB_MIN = 0.5; const val GAIN_AB_MAX = 1.5
    const val SAME_GARMENT_DE = 12.0
    const val EXPOSURE_MIN = 0.25; const val EXPOSURE_MAX = 1.25
    const val GATE_LO = 3.0f; const val GATE_HI = 5.0f

    fun smoothstep(x: Float, a: Float, b: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    fun head(pts: Array<DoubleArray>): DoubleArray? {
        var sx = 0.0; var sy = 0.0; var n = 0
        for (i in 0..10) if (i < pts.size && pts[i][2] > 0.3) { sx += pts[i][0]; sy += pts[i][1]; n++ }
        return if (n == 0) null else doubleArrayOf(sx / n, sy / n)
    }

    fun hipY(pts: Array<DoubleArray>, y0: Int, y1: Int): Double =
        if (pts.size > 24 && pts[23][2] > 0.3 && pts[24][2] > 0.3) (pts[23][1] + pts[24][1]) / 2 else y0 + 0.55 * (y1 - y0)

    /** Square crop around a pose instance's head, for finding small faces; null if the head isn't visible. */
    fun headCrop(pts: Array<DoubleArray>, W: Int, H: Int): IntArray? {
        val vis = (0..10).filter { it < pts.size && pts[it][2] > 0.3 }
        if (vis.size < 3) return null
        val cx = vis.sumOf { pts[it][0] } / vis.size; val cy = vis.sumOf { pts[it][1] } / vis.size
        val spread = vis.maxOf { pts[it][0] } - vis.minOf { pts[it][0] }
        val sh = if (pts.size > 12 && pts[11][2] > 0.3 && pts[12][2] > 0.3) Math.hypot(pts[11][0] - pts[12][0], pts[11][1] - pts[12][1]) * 1.6 else 0.0
        val size = maxOf(spread * 3.0, sh, 48.0)
        val x0 = max(0, (cx - size / 2).toInt()); val y0 = max(0, (cy - size / 2).toInt())
        val x1 = min(W, (cx + size / 2).toInt()); val y1 = min(H, (cy + size / 2).toInt())
        return if (x1 - x0 < 16 || y1 - y0 < 16) null else intArrayOf(x0, y0, x1, y1)
    }

    /** Each pixel belongs to the instance with the highest mask confidence; soft edges (in place). */
    fun resolveOwnership(masks: List<FloatArray>) {
        if (masks.isEmpty()) return
        val n = masks[0].size
        for (i in 0 until n) {
            var top = 0f; for (m in masks) if (m[i] > top) top = m[i]
            for (m in masks) m[i] = if (m[i] >= top) smoothstep(m[i], 0.15f, 0.45f) else 0f
        }
    }

    /** Bilinear resize of a single-channel float map (align corners = false). */
    fun resize(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        val fx = sw.toDouble() / dw; val fy = sh.toDouble() / dh
        for (y in 0 until dh) {
            val sy = ((y + 0.5) * fy - 0.5).coerceIn(0.0, sh - 1.0); val y0 = sy.toInt(); val y1 = min(y0 + 1, sh - 1); val wy = (sy - y0).toFloat()
            for (x in 0 until dw) {
                val sx = ((x + 0.5) * fx - 0.5).coerceIn(0.0, sw - 1.0); val x0 = sx.toInt(); val x1 = min(x0 + 1, sw - 1); val wx = (sx - x0).toFloat()
                val a = src[y0 * sw + x0] * (1 - wx) + src[y0 * sw + x1] * wx
                val b = src[y1 * sw + x0] * (1 - wx) + src[y1 * sw + x1] * wx
                out[y * dw + x] = a * (1 - wy) + b * wy
            }
        }
        return out
    }

    /** Separable Gaussian blur (sigma in px, reflect-101 borders) of a w x h map. */
    fun blur(m: FloatArray, w: Int, h: Int, sigma: Double): FloatArray {
        val r = max(1, Math.ceil(sigma * 3).toInt()); val k = FloatArray(2 * r + 1) { val d = it - r; Math.exp(-d * d / (2 * sigma * sigma)).toFloat() }
        val ks = k.sum(); for (i in k.indices) k[i] /= ks
        fun refl(i: Int, n: Int): Int { var j = i; if (n == 1) return 0; while (j < 0 || j >= n) j = if (j < 0) -j else 2 * n - 2 - j; return j }
        val t = FloatArray(m.size); val out = FloatArray(m.size)
        for (y in 0 until h) for (x in 0 until w) { var s = 0f; for (d in -r..r) s += k[d + r] * m[y * w + refl(x + d, w)]; t[y * w + x] = s }
        for (y in 0 until h) for (x in 0 until w) { var s = 0f; for (d in -r..r) s += k[d + r] * t[refl(y + d, h) * w + x]; out[y * w + x] = s }
        return out
    }

    // ------------------------------------------------------------------ sRGB <-> CIE Lab (D65), same as OpenCV float path
    private val toLin = FloatArray(256) { val c = it / 255.0; (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat() }
    private const val XN = 0.950456f; private const val ZN = 1.088754f
    private fun f(t: Float): Float = if (t > 0.008856f) cbrt(t.toDouble()).toFloat() else 7.787f * t + 16f / 116f
    private fun finv(t: Float): Float = if (t > 0.206893f) t * t * t else (t - 16f / 116f) / 7.787f
    private fun toSrgb(l: Float): Int {
        val c = if (l <= 0.0031308f) 12.92f * l else (1.055 * l.toDouble().pow(1 / 2.4) - 0.055).toFloat()
        return (c * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    fun rgbToLab(r: Int, g: Int, b: Int, out: FloatArray, o: Int = 0) {
        val R = toLin[r]; val G = toLin[g]; val B = toLin[b]
        val x = (0.412453f * R + 0.357580f * G + 0.180423f * B) / XN
        val y = 0.212671f * R + 0.715160f * G + 0.072169f * B
        val z = (0.019334f * R + 0.119193f * G + 0.950227f * B) / ZN
        val fy = f(y)
        out[o] = if (y > 0.008856f) 116f * fy - 16f else 903.3f * y
        out[o + 1] = 500f * (f(x) - fy); out[o + 2] = 200f * (fy - f(z))
    }

    /** Returns packed 0xRRGGBB. */
    fun labToRgb(L: Float, a: Float, b: Float): Int {
        val fy = (L + 16f) / 116f; val fx = fy + a / 500f; val fz = fy - b / 200f
        val x = finv(fx) * XN; val y = if (L > 7.9996f) fy * fy * fy else L / 903.3f; val z = finv(fz) * ZN
        val R = 3.240479f * x - 1.537150f * y - 0.498535f * z
        val G = -0.969256f * x + 1.875992f * y + 0.041556f * z
        val B = 0.055648f * x - 0.204043f * y + 1.057311f * z
        return (toSrgb(R.coerceIn(0f, 1f)) shl 16) or (toSrgb(G.coerceIn(0f, 1f)) shl 8) or toSrgb(B.coerceIn(0f, 1f))
    }

    // ------------------------------------------------------------------ transfer
    /** Scene exposure relative to the collage photo, anchored on skin. */
    fun exposure(collageSkin: LabStats, performerSkin: LabStats): Double =
        if (!collageSkin.ok || !performerSkin.ok) 1.0
        else (performerSkin.mean()[0] / max(collageSkin.mean()[0], 1.0)).coerceIn(EXPOSURE_MIN, EXPOSURE_MAX)

    fun transfer(src: LabStats, tgt: LabStats, e: Double, keepL: Boolean = false): Xfer {
        val ms = src.mean(); val ss = src.std(); val mt = tgt.mean(); val st = tgt.std()
        ms[0] *= e; ss[0] *= e
        if (!keepL) { val se = sqrt(e); ms[1] *= se; ms[2] *= se; ss[1] *= se; ss[2] *= se }
        val g = DoubleArray(3) { ss[it] / st[it] }
        g[0] = g[0].coerceIn(GAIN_L_MIN, GAIN_L_MAX); g[1] = g[1].coerceIn(GAIN_AB_MIN, GAIN_AB_MAX); g[2] = g[2].coerceIn(GAIN_AB_MIN, GAIN_AB_MAX)
        val o = DoubleArray(3) { ms[it] - g[it] * mt[it] }
        if (keepL) { g[0] = 1.0; o[0] = 0.0 }
        return Xfer(FloatArray(3) { g[it].toFloat() }, FloatArray(3) { o[it].toFloat() }, FloatArray(3) { mt[it].toFloat() }, FloatArray(3) { st[it].toFloat() })
    }

    fun deltaE(a: LabStats, b: LabStats): Double { val x = a.mean(); val y = b.mean(); return sqrt((0..2).sumOf { (x[it] - y[it]) * (x[it] - y[it]) }) }

    /**
     * Transfer parameters for one performer from their stats and the collage person's. Outfit: if the
     * performer's upper and lower clothes look like one garment (dress / jumpsuit) both are recoloured,
     * otherwise only the top (the collage usually doesn't show legs).
     */
    fun plan(collage: Map<Region, LabStats>, performer: Map<Region, LabStats>, opt: TransferOptions): Pair<Map<Region, Xfer>, Double> {
        val cs = { r: Region -> collage[r] ?: LabStats() }; val ps = { r: Region -> performer[r] ?: LabStats() }
        val e = exposure(cs(Region.SKIN), ps(Region.SKIN))
        val out = HashMap<Region, Xfer>()
        if (opt.hair && cs(Region.HAIR).ok && ps(Region.HAIR).ok) out[Region.HAIR] = transfer(cs(Region.HAIR), ps(Region.HAIR), e)
        if (opt.skin && cs(Region.SKIN).ok && ps(Region.SKIN).ok) out[Region.SKIN] = transfer(cs(Region.SKIN), ps(Region.SKIN), e, keepL = true)
        if (opt.outfit && cs(Region.UPPER).ok && ps(Region.UPPER).ok) {
            if (ps(Region.LOWER).ok && deltaE(ps(Region.UPPER), ps(Region.LOWER)) < SAME_GARMENT_DE) {
                val x = transfer(cs(Region.UPPER), ps(Region.UPPER).merge(ps(Region.LOWER)), e); out[Region.UPPER] = x; out[Region.LOWER] = x
            } else out[Region.UPPER] = transfer(cs(Region.UPPER), ps(Region.UPPER), e)
        }
        return out to e
    }

    /**
     * Recolour a crop in place. [px] = packed RGB of the crop (cw x ch); [weights] = soft region masks
     * (already multiplied by the instance mask). Regions are applied in Region order; their weights never sum above 1.
     */
    fun recolour(px: IntArray, cw: Int, ch: Int, weights: Map<Region, FloatArray>, params: Map<Region, Xfer>) {
        val n = cw * ch
        val lab = FloatArray(n * 3)
        for (i in 0 until n) { val c = px[i]; rgbToLab((c shr 16) and 255, (c shr 8) and 255, c and 255, lab, i * 3) }
        val acc = FloatArray(n); val dL = FloatArray(n); val da = FloatArray(n); val db = FloatArray(n)
        for (r in Region.values()) {
            val x = params[r] ?: continue; val wr = weights[r] ?: continue
            val sm = FloatArray(n) { smoothstep(wr[it], 0.3f, 0.6f) }
            val w = blur(sm, cw, ch, 1.0); val str = STRENGTH[r] ?: 1f
            for (i in 0 until n) {
                var wi = w[i] * str
                if (wi <= 1e-3f) continue
                val L = lab[i * 3]; val a = lab[i * 3 + 1]; val b = lab[i * 3 + 2]
                val zl = (L - x.mt[0]) / x.st[0]; val za = (a - x.mt[1]) / x.st[1]; val zb = (b - x.mt[2]) / x.st[2]
                val d = sqrt(zl * zl + za * za + zb * zb)
                wi *= 1f - smoothstep(d, GATE_LO, GATE_HI)
                wi = min(wi, 1f - acc[i]); if (wi <= 0f) continue
                acc[i] += wi
                dL[i] += wi * (L * x.g[0] + x.o[0] - L); da[i] += wi * (a * x.g[1] + x.o[1] - a); db[i] += wi * (b * x.g[2] + x.o[2] - b)
            }
        }
        for (i in 0 until n) if (acc[i] > 0f) {
            val rgb = labToRgb(lab[i * 3] + dL[i], lab[i * 3 + 1] + da[i], lab[i * 3 + 2] + db[i])
            px[i] = (0xff shl 24) or rgb
        }
    }
}
