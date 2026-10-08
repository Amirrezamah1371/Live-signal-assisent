package com.example.livesignalassistant

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Result of one chart-trace extraction. ys are screen pixel rows (smaller = higher price). */
class TraceResult(
    val ys: DoubleArray,
    val real: BooleanArray,
    val realFrac: Double,
    val ambiguity: Double,
    val maxJumpFrac: Double,
    val barFound: Boolean,
    val roiTop: Int,
    val roiBottom: Int,
    val quality: Double,
    val stepPx: Int = 0,
    val tipGapFrac: Double = 0.0
)

/**
 * Robust price-line extraction (72.0).
 * Phase 1 showed that the old "blue pixel" test also accepted the shaded fill below the line,
 * the horizontal current-price line, the pair button and the trade panel.
 * This extractor:
 *  1. keeps only DARK line-blue pixels (the stroke is clearly darker than the area fill),
 *  2. removes solid blobs (pair button, icons) and full-width horizontal lines,
 *  3. cuts the ROI at the green/red sentiment bar so the trade panel is never scanned,
 *  4. chooses one point per column with dynamic programming (spatial continuity),
 *     allowing gaps that are interpolated and reported through realFrac.
 */
object TraceExtractor {
    private var pix = IntArray(0)
    private var mask = ByteArray(0)
    private var blob = ByteArray(0)
    private var integ = IntArray(0)

    @Synchronized
    fun extract(b: Bitmap): TraceResult? {
        val w = b.width
        val h = b.height
        if (w < 240 || h < 360) return null
        if (pix.size != w * h) pix = IntArray(w * h)
        b.getPixels(pix, 0, w, 0, 0, w, h)

        val x0 = (w * 0.055).toInt()
        val x1 = (w * 0.79).toInt()
        val y0 = (h * 0.12).toInt()
        var y1 = (h * 0.79).toInt()

        // Sentiment bar (green | red) marks the lower edge of the chart area.
        var bar = -1
        run {
            var yy = (h * 0.25).toInt()
            val yEnd = (h * 0.95).toInt()
            while (yy < yEnd) {
                var g = 0
                var rd = 0
                var cnt = 0
                var x = x0
                while (x < x1) {
                    val c = pix[yy * w + x]
                    val r = (c shr 16) and 255
                    val gg = (c shr 8) and 255
                    val bb = c and 255
                    if (gg >= 110 && (gg - r) >= 50 && (gg - bb) >= 30) g++
                    if (r >= 170 && (r - gg) >= 90 && (r - bb) >= 90) rd++
                    cnt++
                    x += 3
                }
                if (g > 0.18 * cnt && rd > 0.05 * cnt) {
                    bar = yy
                    break
                }
                yy++
            }
        }
        if (bar > 0) y1 = min(y1, bar - max(2, (h * 0.006).toInt()))
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < 100 || rh < 100) return null

        // 1) dark line-blue mask
        if (mask.size != rw * rh) {
            mask = ByteArray(rw * rh)
            blob = ByteArray(rw * rh)
        }
        for (yy in 0 until rh) {
            val rowBase = (y0 + yy) * w + x0
            for (xx in 0 until rw) {
                val c = pix[rowBase + xx]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val bl = c and 255
                mask[yy * rw + xx] =
                    if (r <= 145 && (bl - r) >= 38 && (g - r) >= 10 && bl >= g - 6) 1 else 0
            }
        }

        // 2) blob removal with integral images
        val k = max(5, (w / 60) or 1)
        val half = k / 2
        val iw = rw + 1
        if (integ.size != iw * (rh + 1)) integ = IntArray(iw * (rh + 1))
        fun buildIntegral(src: ByteArray) {
            for (xx in 0..rw) integ[xx] = 0
            for (yy in 1..rh) {
                var rowSum = 0
                integ[yy * iw] = 0
                for (xx in 1..rw) {
                    rowSum += src[(yy - 1) * rw + (xx - 1)].toInt()
                    integ[yy * iw + xx] = integ[(yy - 1) * iw + xx] + rowSum
                }
            }
        }
        fun boxSum(xc: Int, yc: Int): Int {
            val xa = max(0, xc - half)
            val xb = min(rw, xc + half + 1)
            val ya = max(0, yc - half)
            val yb = min(rh, yc + half + 1)
            return integ[yb * iw + xb] - integ[ya * iw + xb] - integ[yb * iw + xa] + integ[ya * iw + xa]
        }
        buildIntegral(mask)
        val thr = (0.55 * k * k).toInt()
        for (yy in 0 until rh) for (xx in 0 until rw) {
            blob[yy * rw + xx] =
                if (mask[yy * rw + xx].toInt() == 1 && boxSum(xx, yy) >= thr) 1 else 0
        }
        buildIntegral(blob)
        for (yy in 0 until rh) for (xx in 0 until rw) {
            if (mask[yy * rw + xx].toInt() == 1 && boxSum(xx, yy) > 0) mask[yy * rw + xx] = 0
        }

        // 3) Full-width horizontal HUD lines (price level, trade line).
        // A flat price trace is itself horizontal, so delete these rows only when a separate
        // structure still holds at least 30% of the mask pixels.
        run {
            val rowCount = IntArray(rh)
            var total = 0
            for (yy in 0 until rh) {
                var cnt = 0
                for (xx in 0 until rw) cnt += mask[yy * rw + xx].toInt()
                rowCount[yy] = cnt
                total += cnt
            }
            if (total > 0) {
                val bar = (0.60 * rw).toInt()
                var horiz = 0
                val horizRows = ArrayList<Int>()
                for (yy in 0 until rh) if (rowCount[yy] > bar) {
                    horiz += rowCount[yy]
                    horizRows.add(yy)
                }
                if (horizRows.isNotEmpty() && (total - horiz) > 0.30 * total) {
                    for (yy in horizRows) {
                        for (d in -1..1) {
                            val ry = yy + d
                            if (ry in 0 until rh) for (xx in 0 until rw) mask[ry * rw + xx] = 0
                        }
                    }
                }
            }
        }

        // 4) per-column candidates + dynamic programming
        val step = max(2, w / 400)
        val gapPx = max(2, w / 270)
        val thick = max(4, h / 200)
        val nCols = (rw + step - 1) / step
        val maxC = 8
        val candY = Array(nCols) { DoubleArray(maxC) }
        val candN = IntArray(nCols)
        var multi = 0
        for (ci in 0 until nCols) {
            val xx = ci * step
            var n = 0
            var runStart = -1
            var last = -100
            fun flush() {
                if (runStart < 0) return
                val top = runStart
                val bot = last
                if (bot - top <= thick) {
                    if (n < maxC) candY[ci][n++] = (top + bot) / 2.0
                } else {
                    if (n < maxC) candY[ci][n++] = top.toDouble()
                    if (n < maxC) candY[ci][n++] = (top + bot) / 2.0
                    if (n < maxC) candY[ci][n++] = bot.toDouble()
                }
            }
            for (yy in 0 until rh) {
                if (mask[yy * rw + xx].toInt() == 1) {
                    if (runStart < 0) {
                        runStart = yy
                    } else if (yy - last > gapPx) {
                        flush()
                        runStart = yy
                    }
                    last = yy
                }
            }
            flush()
            candN[ci] = n
            if (n > 1) multi++
        }

        val gap = 0.05 * rh
        val jmax = 0.25 * rh
        val js = 0.02 * rh
        val cost = Array(nCols) { DoubleArray(maxC + 1) }
        val back = Array(nCols) { IntArray(maxC + 1) }
        for (ci in 1 until nCols) {
            val ns = candN[ci] + 1
            val ps = candN[ci - 1] + 1
            for (j in 0 until ns) {
                val missing = j == candN[ci]
                var best = Double.MAX_VALUE
                var bk = 0
                for (jj in 0 until ps) {
                    val prevMissing = jj == candN[ci - 1]
                    val t = when {
                        missing && prevMissing -> gap * 0.3
                        missing -> gap * 0.6
                        prevMissing -> gap
                        else -> min(abs(candY[ci][j] - candY[ci - 1][jj]), jmax) / js
                    }
                    val v = cost[ci - 1][jj] + t
                    if (v < best) {
                        best = v
                        bk = jj
                    }
                }
                cost[ci][j] = best
                back[ci][j] = bk
            }
        }
        var j = 0
        run {
            var best = Double.MAX_VALUE
            for (jj in 0..candN[nCols - 1]) {
                if (cost[nCols - 1][jj] < best) {
                    best = cost[nCols - 1][jj]
                    j = jj
                }
            }
        }
        val pick = DoubleArray(nCols)
        val has = BooleanArray(nCols)
        for (ci in nCols - 1 downTo 0) {
            if (j < candN[ci]) {
                pick[ci] = candY[ci][j]
                has[ci] = true
            }
            j = back[ci][j]
        }
        var lo = -1
        var hi = -1
        var realCount = 0
        for (ci in 0 until nCols) if (has[ci]) {
            if (lo < 0) lo = ci
            hi = ci
            realCount++
        }
        if (realCount < 10 || lo < 0) return null
        // Fixed width: cropping to the first/last hit made the path length flicker and broke registration.
        val n = nCols
        val ys = DoubleArray(n)
        val real = BooleanArray(n)
        for (i in 0 until n) {
            real[i] = has[i]
            if (has[i]) ys[i] = pick[i] + y0
        }
        // linear interpolation across gaps
        var i = 0
        while (i < n) {
            if (!real[i]) {
                var a = i - 1
                var bnd = i
                while (bnd < n && !real[bnd]) bnd++
                for (m in i until bnd) {
                    ys[m] = if (a >= 0 && bnd < n) {
                        ys[a] + (ys[bnd] - ys[a]) * (m - a).toDouble() / (bnd - a).toDouble()
                    } else if (a >= 0) ys[a] else ys[bnd]
                }
                i = bnd
            } else i++
        }
        var maxJump = 0.0
        for (m in 1 until n) if (real[m] && real[m - 1]) maxJump = max(maxJump, abs(ys[m] - ys[m - 1]) / rh)
        val realFrac = realCount.toDouble() / n
        val ambiguity = multi.toDouble() / max(1, n)
        val tipGapFrac = if (hi < 0) 1.0 else (nCols - 1 - hi).coerceAtLeast(0).toDouble() / nCols
        val q = (((realFrac - 0.35) / 0.5).coerceIn(0.0, 1.0)) *
            (1.0 - 0.3 * ((maxJump - 0.2) / 0.4).coerceIn(0.0, 1.0))
        return TraceResult(ys, real, realFrac, ambiguity, maxJump, bar > 0, y0, y1, q, step, tipGapFrac)
    }
}
