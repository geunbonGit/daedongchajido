package com.odomap.core.share

import com.odomap.core.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// The fitting machinery: significance-based Douglas–Peucker equals the classic recursion, and cards far over the budget still fit.
class ShareFitTest {
    /** classic recursive DP (simplifyCoords' rule: the first point farther than t, by maximum) on [a, b] */
    private fun dp(x: DoubleArray, y: DoubleArray, a: Int, b: Int, t: Double, keep: BooleanArray) {
        if (b - a < 2) return
        val bx = x[b] - x[a]
        val by = y[b] - y[a]
        val len2 = bx * bx + by * by
        var worst = -1
        var worstD = t
        for (i in a + 1 until b) {
            val px = x[i] - x[a]
            val py = y[i] - y[a]
            val d =
                if (len2 == 0.0) {
                    sqrt(px * px + py * py)
                } else {
                    val f = max(0.0, min(1.0, (px * bx + py * by) / len2))
                    sqrt((px - f * bx) * (px - f * bx) + (py - f * by) * (py - f * by))
                }
            if (d > worstD) {
                worst = i
                worstD = d
            }
        }
        if (worst < 0) return
        keep[worst] = true
        dp(x, y, a, worst, t, keep)
        dp(x, y, worst, b, t, keep)
    }

    @Test
    fun `significance above t keeps exactly what Douglas–Peucker at t keeps (pinned points split the line)`() {
        val random = Random(42)
        var compared = 0
        repeat(400) {
            val n = 2 + random.nextInt(150)
            val x = DoubleArray(n)
            val y = DoubleArray(n)
            for (i in 1 until n) {
                x[i] = x[i - 1] + random.nextGaussian() * 20
                y[i] = y[i - 1] + 5 + random.nextGaussian() * 20
                if (random.nextInt(20) == 0) x[i] = x[i - 1] // repeated points / collinear runs
            }
            val pinned = BooleanArray(n) { it in 1 until n - 1 && random.nextInt(25) == 0 }
            val sig = dpSignificance(x, y, pinned)
            for (t in listOf(0.0, 0.5, 2.0, 7.5, 30.0, 200.0)) {
                val keep = BooleanArray(n)
                keep[0] = true
                keep[n - 1] = true
                var from = 0
                for (k in 1 until n) {
                    if (k == n - 1 || pinned[k]) {
                        keep[k] = true
                        dp(x, y, from, k, t, keep)
                        from = k
                    }
                }
                for (k in 0 until n) assertEquals("n $n t $t point $k", keep[k], sig[k] > t)
                compared += n
            }
        }
        println("[share] DP significance = recursive DP on $compared points")
    }

    @Test
    fun `a huge atlas and a drive with hundreds of style changes still fit version 20`() {
        val random = Random(7)
        val lines =
            List(3500) {
                val lat = 34.5 + random.nextDouble() * 3.5
                val lng = 126.5 + random.nextDouble() * 2.5
                ShareLine(1 + random.nextInt(5), false, List(6) { k -> GeoPoint(lat + k * 0.001 + random.nextGaussian() * 1e-4, lng + k * 0.0012) })
            }
        val atlas = ShareCard.Atlas(2500, 1, 1, 100, "big", 230, lines)
        val t0 = System.nanoTime()
        val qr = ShareEncoder.encode(atlas)
        val ms = (System.nanoTime() - t0) / 1e6
        val kept = (qr.card as ShareCard.Atlas).lines
        println("[share] 3500-line atlas: kept ${kept.size} lines, dropped ${qr.droppedLines}, tolerance %.0f m, ${qr.bytes} bytes, v${qr.version}, %.0f ms".format(qr.toleranceM, ms))
        assertTrue(qr.version <= 20)
        assertTrue(qr.droppedLines >= 3500 - ShareEncoder.MAX_CHAINED_LINES)
        assertEquals(3, (qr.card as ShareCard.Atlas).quantExp)
        assertTrue("took $ms ms", ms < 3000)
        // kind 1 with 600 tiny alternating-style pieces along a 300 km line: falls back to dropping the shortest pieces
        val drive =
            ShareCard.Drive(
                2500, 600, 9000, 30_000, 10_000, "zigzag", 10, emptyList(), emptyList(),
                List(600) { i ->
                    val lat = 35.0 + i * 0.004
                    ShareLine(1 + i % 5, i % 2 == 0, listOf(GeoPoint(lat, 127.0 + (i % 3) * 0.0005), GeoPoint(lat + 0.004, 127.0 + ((i + 1) % 3) * 0.0005)))
                },
            )
        val dq = ShareEncoder.encode(drive)
        println("[share] 600-piece drive: kept ${(dq.card as ShareCard.Drive).lines.size}, dropped ${dq.droppedLines}, tolerance %.0f m, ${dq.bytes} bytes, v${dq.version}".format(dq.toleranceM))
        assertTrue(dq.version <= 20 && dq.droppedLines > 0)
    }
}
