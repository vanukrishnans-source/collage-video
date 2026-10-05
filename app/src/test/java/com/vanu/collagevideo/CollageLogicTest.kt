package com.vanu.collagevideo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TreeMap
import kotlin.math.abs

class CollageLogicTest {
    @Test fun collageReadingOrder() {
        // two rows: top row right-then-left given out of order, bottom row single face
        val boxes = listOf(doubleArrayOf(600.0, 110.0, 700.0, 210.0), doubleArrayOf(400.0, 600.0, 500.0, 700.0), doubleArrayOf(100.0, 100.0, 200.0, 200.0))
        assertEquals(listOf(2, 0, 1), CollageOrder.order(boxes))
    }

    @Test fun safetyPlanSamplesEveryHalfSecondCapped() {
        assertEquals(8, SafetyPlan.step(240, 15.0))       // 0.5 s at 15 fps -> every 7.5 -> 8th frame
        assertEquals(15, SafetyPlan.step(900, 30.0))
        assertTrue(SafetyPlan.step(5000, 30.0) * 60 >= 5000)  // at most ~60 checks
        assertTrue(SafetyPlan.isSample(0, 8)); assertFalse(SafetyPlan.isSample(3, 8))
        assertTrue(SafetyPlan.blocked(0.6f, FilterStrictness.STANDARD.threshold))
        assertFalse(SafetyPlan.blocked(0.6f, FilterStrictness.RELAXED.threshold))
    }

    private fun box(x: Double) = doubleArrayOf(x, 100.0, x + 80.0, 400.0)

    @Test fun performersGetCollagePeopleLeftToRightAndFlip() {
        // two performers walking side by side for 20 frames: left at x=100, right at x=500
        val n = 20
        val dets = (0 until n).map { listOf(box(500.0 + it), box(100.0 + it)) }
        val tracks = FaceTracker.track(dets, maxGap = 15)
        val people = People.group(tracks)
        assertEquals(2, people.size)
        val layout = People.layout(people, tracks, 2)
        val a = People.autoAssign(people, layout, 2, shift = 0, repeat = true)
        val leftPerson = people.indices.minBy { FaceTracker.mean(tracks[people[it].tracks[0]].values.first())[0] }
        assertEquals(0, a[leftPerson]); assertEquals(1, a[1 - leftPerson])
        val flipped = People.autoAssign(people, layout, 2, shift = 1, repeat = true)
        assertEquals(1, flipped[leftPerson]); assertEquals(0, flipped[1 - leftPerson])
    }

    @Test fun oneBadFrameDoesNotSwapTheOrder() {
        // frame 0: the left performer's detection briefly sits right of the other one (a pose instance covering both)
        val n = 20
        val dets = (0 until n).map { f -> if (f == 0) listOf(doubleArrayOf(630.0, 100.0, 850.0, 720.0), doubleArrayOf(476.0, 100.0, 849.0, 720.0))
            else listOf(doubleArrayOf(640.0, 100.0, 900.0, 720.0), doubleArrayOf(450.0, 100.0, 700.0, 720.0)) }
        val tracks = FaceTracker.track(dets, maxGap = 15)
        val people = People.group(tracks)
        val layout = People.layout(people, tracks, 2)
        val a = People.autoAssign(people, layout, 2, 0, true)
        // whoever is left on frames 1..19 gets collage person 0
        val left = people.indices.minBy { p -> FaceTracker.mean(tracks[people[p].tracks[0]][10]!!)[0] }
        assertEquals(0, a[left])
    }

    @Test fun labMatchesOpenCv() {
        val cases = listOf(
            intArrayOf(200, 150, 120) to floatArrayOf(66.04f, 14.734f, 23.297f),
            intArrayOf(30, 30, 30) to floatArrayOf(11.243f, 0.094f, 0.047f),
            intArrayOf(255, 255, 255) to floatArrayOf(100f, 0f, 0f),
            intArrayOf(70, 40, 90) to floatArrayOf(22.07f, 24.438f, -24.609f),
            intArrayOf(10, 200, 30) to floatArrayOf(70.41f, -70.422f, 64.844f))
        val out = FloatArray(3)
        for ((rgb, lab) in cases) {
            BodyMath.rgbToLab(rgb[0], rgb[1], rgb[2], out)
            for (i in 0..2) assertEquals("Lab of ${rgb.toList()}[$i]", lab[i], out[i], 0.3f)   // OpenCV uses spline-approximated gamma/cbrt tables
            val back = BodyMath.labToRgb(out[0], out[1], out[2])
            assertTrue(abs(((back shr 16) and 255) - rgb[0]) <= 1 && abs(((back shr 8) and 255) - rgb[1]) <= 1 && abs((back and 255) - rgb[2]) <= 1)
        }
    }

    private fun stats(L: Float, a: Float, b: Float, spread: Float = 4f, n: Int = 400) = LabStats().apply {
        for (i in 0 until n) { val d = if (i % 2 == 0) spread else -spread; add(L + d, a + d / 2, b - d / 2) }
    }

    @Test fun planDressIsOneGarmentJeansAreKept() {
        val collage = mapOf(Region.HAIR to stats(20f, 5f, 5f), Region.SKIN to stats(60f, 15f, 20f), Region.UPPER to stats(30f, 30f, -5f))
        val opt = TransferOptions()
        // white dress: top and bottom the same colour -> both recoloured
        val dress = mapOf(Region.HAIR to stats(70f, 5f, 20f), Region.SKIN to stats(66f, 14f, 22f), Region.UPPER to stats(90f, 0f, 5f), Region.LOWER to stats(88f, 1f, 6f))
        val (p1, e1) = BodyMath.plan(collage, dress, opt)
        assertTrue(Region.UPPER in p1 && Region.LOWER in p1 && Region.HAIR in p1 && Region.SKIN in p1)
        assertEquals(1.1, e1, 0.01)
        // brown jacket + blue jeans -> only the top
        val jacket = mapOf(Region.SKIN to stats(60f, 15f, 20f), Region.UPPER to stats(40f, 15f, 30f), Region.LOWER to stats(45f, 0f, -25f))
        val (p2, _) = BodyMath.plan(collage, jacket, opt)
        assertTrue(Region.UPPER in p2); assertFalse(Region.LOWER in p2); assertFalse(Region.HAIR in p2)
        // options respected
        val (p3, _) = BodyMath.plan(collage, dress, TransferOptions(hair = false, skin = true, outfit = false))
        assertEquals(setOf(Region.SKIN), p3.keys)
    }

    @Test fun skinTransferKeepsLightness() {
        val x = BodyMath.transfer(stats(40f, 20f, 25f), stats(70f, 10f, 15f), 1.0, keepL = true)
        assertEquals(1f, x.g[0], 0f); assertEquals(0f, x.o[0], 0f)
    }

    @Test fun recolourMovesMatchingPixelsAndGatesOutliers() {
        val w = 12; val h = 12; val n = w * h
        val white = 0xffe6e6e6.toInt(); val brown = 0xff6b4423.toInt()
        val px = IntArray(n) { if (it % w < 10) white else brown }      // last two columns: someone else's brown sleeve
        val target = LabStats(); val lab = FloatArray(3)
        BodyMath.rgbToLab(0xe6, 0xe6, 0xe6, lab); for (i in 0 until 400) { val d = if (i % 2 == 0) 3f else -3f; target.add(lab[0] + d, lab[1] + d / 3, lab[2] - d / 3) }
        val plum = stats(30f, 30f, -8f)
        val x = BodyMath.transfer(plum, target, 1.0)
        val weights = mapOf(Region.UPPER to FloatArray(n) { 1f })
        val before = px.copyOf()
        BodyMath.recolour(px, w, h, weights, mapOf(Region.UPPER to x))
        val c = px[5 * w + 4]
        assertTrue("white became darker/purple", ((c shr 16) and 255) < 200 && ((c shr 16) and 255) > (c and 255) - 40)
        assertEquals("outlier sleeve is gated", before[5 * w + 11], px[5 * w + 11])
    }

    @Test fun ownershipGivesEachPixelToOneInstance() {
        val a = floatArrayOf(0.9f, 0.6f, 0.2f, 0.0f); val b = floatArrayOf(0.1f, 0.7f, 0.8f, 0.05f)
        BodyMath.resolveOwnership(listOf(a, b))
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), a, 1e-6f)
        assertEquals(0f, b[0], 0f); assertEquals(1f, b[1], 0f); assertEquals(1f, b[2], 0f); assertEquals(0f, b[3], 0f)
    }

    @Test fun headCropAroundVisibleFace() {
        val pts = Array(33) { doubleArrayOf(0.0, 0.0, 0.0) }
        for (i in 0..10) pts[i] = doubleArrayOf(300.0 + (i - 5) * 3, 120.0 + (i % 3), 0.9)
        pts[11] = doubleArrayOf(270.0, 180.0, 0.9); pts[12] = doubleArrayOf(330.0, 180.0, 0.9)
        val c = BodyMath.headCrop(pts, 1280, 720)!!
        assertTrue(c[0] < 300 && c[2] > 300 && c[1] < 121 && c[3] > 121)
        assertTrue(c[2] - c[0] >= 90)       // ~1.6 x shoulder width
        assertEquals(null, BodyMath.headCrop(Array(33) { doubleArrayOf(0.0, 0.0, 0.0) }, 1280, 720))
    }

    @Test fun trackHelpersOnBoxes() {
        val t = TreeMap<Int, DoubleArray>().apply { put(0, box(10.0)); put(1, box(12.0)) }
        assertEquals(0.95, FaceTracker.iou(t[0]!!, t[1]!!), 0.05)
    }
}
