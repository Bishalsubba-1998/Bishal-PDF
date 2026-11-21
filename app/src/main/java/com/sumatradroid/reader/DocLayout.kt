// Page layout engine. Modeled on SumatraPDF's DisplayModel (src/shared/DisplayModel.cpp):
// display modes (single / facing / book view, each continuous or not), virtual zoom levels
// (fit page / fit width / percent), per-page zoom for fit modes, page spacing and window margins.
// Pure Kotlin (no Android imports) so it can be unit-tested on the JVM.
package com.sumatradroid.reader

import kotlin.math.max
import kotlin.math.min

/** Same set as SumatraPDF's DisplayMode enum (minus "Automatic", which equals CONTINUOUS). */
enum class DisplayMode(val continuous: Boolean, val columns: Int, val bookCover: Boolean) {
    SINGLE(false, 1, false),
    FACING(false, 2, false),
    BOOK(false, 2, true),
    CONTINUOUS(true, 1, false),
    CONTINUOUS_FACING(true, 2, false),
    CONTINUOUS_BOOK(true, 2, true);

    fun withContinuous(c: Boolean): DisplayMode = when (columns to bookCover) {
        1 to false -> if (c) CONTINUOUS else SINGLE
        2 to false -> if (c) CONTINUOUS_FACING else FACING
        else -> if (c) CONTINUOUS_BOOK else BOOK
    }

    fun withColumns(cols: Int, cover: Boolean): DisplayMode = when {
        cols == 1 -> if (continuous) CONTINUOUS else SINGLE
        cover -> if (continuous) CONTINUOUS_BOOK else BOOK
        else -> if (continuous) CONTINUOUS_FACING else FACING
    }
}

/** Virtual zoom values: > 0 is a percentage, negatives are "fit" modes (same values as Sumatra). */
object Zoom {
    const val FIT_PAGE = -1f
    const val FIT_WIDTH = -2f
    const val ACTUAL = 100f
    const val MIN = 8.33f
    const val MAX = 6400f

    // SumatraPDF's defaultZoomLevels (DisplayModel.cpp)
    val LEVELS = floatArrayOf(
        8.33f, 12.5f, 18f, 25f, 33.33f, 50f, 66.67f, 75f, 100f, 125f, 150f, 200f, 300f, 400f,
        600f, 800f, 1000f, 1200f, 1600f, 2000f, 2400f, 3200f, 4800f, 6400f,
    )

    fun isFit(z: Float) = z < 0f

    fun toString(z: Float): String = when {
        z == FIT_PAGE -> "Fit Page"
        z == FIT_WIDTH -> "Fit Width"
        else -> {
            val r = Math.round(z * 100f) / 100f
            (if (r == Math.floor(r.toDouble()).toFloat()) r.toInt().toString() else r.toString()) + "%"
        }
    }

    /** Next zoom level above / below `cur`, like MaybeGetNextZoomByIncrement. */
    fun step(cur: Float, up: Boolean): Float {
        if (up) {
            for (l in LEVELS) if (l > cur * 1.001f) return l
            return MAX
        }
        for (i in LEVELS.indices.reversed()) if (LEVELS[i] < cur * 0.999f) return LEVELS[i]
        return MIN
    }
}

class DocLayout {
    // ---- inputs -------------------------------------------------------------------------
    /** Display box size of each page in PDF points, before user rotation. */
    var pageW = FloatArray(0)
    var pageH = FloatArray(0)
    var rotation = 0          // quarter turns clockwise, 0..3
    var mode = DisplayMode.CONTINUOUS
    var zoomVirtual = Zoom.FIT_WIDTH
    var vw = 0                // viewport size in px
    var vh = 0
    var marginX = 0f          // window margin left/right (px)
    var marginY = 0f          // window margin top/bottom (px)
    var gapX = 0f             // spacing between pages in a row (px)
    var gapY = 0f             // spacing between rows (px)
    var dpiFactor = 96f / 72f // 100% zoom = 96 dpi (as on Windows 96-dpi screens), scaled by density
    var coverPage = true      // for book view: first page alone

    // ---- outputs ------------------------------------------------------------------------
    var rowFirst = IntArray(0)
        private set
    var rowCount = IntArray(0)
        private set
    var rowBlankLeft = BooleanArray(0)
        private set
    var rowY = FloatArray(0)
        private set
    var rowH = FloatArray(0)
        private set
    var rowZoom = FloatArray(0)
        private set
    var rowOfPage = IntArray(0)
        private set
    var px = FloatArray(0)
        private set   // page rect in document coordinates (px)
    var py = FloatArray(0)
        private set
    var pw = FloatArray(0)
        private set
    var ph = FloatArray(0)
        private set
    var pz = FloatArray(0)
        private set   // real zoom (px per pt) of each page
    var canvasW = 0f
        private set
    var canvasH = 0f
        private set

    val pageCount get() = pageW.size
    val rows get() = rowFirst.size

    private val effMode: DisplayMode get() = mode

    fun rotW(i: Int) = if (rotation % 2 == 1) pageH[i] else pageW[i]
    fun rotH(i: Int) = if (rotation % 2 == 1) pageW[i] else pageH[i]

    private fun realZoomMin() = Zoom.MIN / 100f * dpiFactor
    private fun realZoomMax() = Zoom.MAX / 100f * dpiFactor

    /** Recompute everything. Cheap (O(pages)); call after any input changes. */
    fun relayout() {
        val n = pageW.size
        if (n == 0) {
            rowFirst = IntArray(0); rowCount = IntArray(0); rowBlankLeft = BooleanArray(0)
            rowY = FloatArray(0); rowH = FloatArray(0); rowZoom = FloatArray(0)
            rowOfPage = IntArray(0); px = FloatArray(0); py = FloatArray(0)
            pw = FloatArray(0); ph = FloatArray(0); pz = FloatArray(0)
            canvasW = vw.toFloat(); canvasH = vh.toFloat()
            return
        }
        val m = effMode
        // build rows
        val first = ArrayList<Int>()
        val cnt = ArrayList<Int>()
        val blank = ArrayList<Boolean>()
        var i = 0
        if (m.columns == 1) {
            while (i < n) { first.add(i); cnt.add(1); blank.add(false); i++ }
        } else {
            if (m.bookCover && coverPage) { first.add(0); cnt.add(1); blank.add(true); i = 1 }
            while (i < n) {
                val c = min(2, n - i)
                first.add(i); cnt.add(c); blank.add(false); i += c
            }
        }
        val r = first.size
        rowFirst = first.toIntArray(); rowCount = cnt.toIntArray(); rowBlankLeft = blank.toBooleanArray()
        rowY = FloatArray(r); rowH = FloatArray(r); rowZoom = FloatArray(r)
        rowOfPage = IntArray(n)
        px = FloatArray(n); py = FloatArray(n); pw = FloatArray(n); ph = FloatArray(n); pz = FloatArray(n)

        // per-row zoom and size
        val rowW = FloatArray(r)
        for (k in 0 until r) {
            val f = rowFirst[k]; val c = rowCount[k]
            var sumW = 0f; var maxH = 0f
            for (j in f until f + c) { sumW += rotW(j); maxH = max(maxH, rotH(j)) }
            val slots = if (rowBlankLeft[k]) c + 1 else c
            if (rowBlankLeft[k]) sumW += rotW(f) // the blank half of the spread
            val gaps = gapX * (slots - 1)
            val z = zoomFor(sumW, maxH, gaps)
            rowZoom[k] = z
            rowH[k] = maxH * z
            rowW[k] = sumW * z + gaps
            for (j in f until f + c) rowOfPage[j] = k
        }
        var maxRowW = 0f
        for (k in 0 until r) maxRowW = max(maxRowW, rowW[k])
        canvasW = max(vw.toFloat(), maxRowW + 2 * marginX)

        var y = marginY
        for (k in 0 until r) {
            rowY[k] = y
            val f = rowFirst[k]; val c = rowCount[k]
            val z = rowZoom[k]
            var x = (canvasW - rowW[k]) / 2f
            if (rowBlankLeft[k]) x += rotW(f) * z + gapX
            for (j in f until f + c) {
                val w = rotW(j) * z; val h = rotH(j) * z
                px[j] = x; py[j] = y + (rowH[k] - h) / 2f
                pw[j] = w; ph[j] = h; pz[j] = z
                x += w + gapX
            }
            y += rowH[k] + gapY
        }
        canvasH = y - gapY + marginY
    }

    private fun zoomFor(sumW: Float, maxH: Float, gaps: Float): Float {
        val zv = zoomVirtual
        val z: Float
        if (zv > 0f) {
            z = zv / 100f * dpiFactor
        } else {
            val availW = max(1f, vw - 2 * marginX - gaps)
            val availH = max(1f, vh - 2 * marginY)
            val fitW = if (sumW > 0f) availW / sumW else 1f
            val fitH = if (maxH > 0f) availH / maxH else 1f
            z = if (zv == Zoom.FIT_PAGE) min(fitW, fitH) else fitW
        }
        return z.coerceIn(realZoomMin(), realZoomMax())
    }

    /** Zoom of the row, expressed as percent (what Sumatra shows in its zoom box). */
    fun percentOf(row: Int): Float = if (row in 0 until rows) rowZoom[row] / dpiFactor * 100f else 100f

    /** Largest zoom % that keeps the document height under ~ 8M px (float precision limit). */
    fun maxPercentForCanvas(): Float {
        var sumH = 0f
        for (k in 0 until rows) {
            var h = 0f
            for (j in rowFirst[k] until rowFirst[k] + rowCount[k]) h = max(h, rotH(j))
            sumH += h
        }
        if (sumH <= 0f) return Zoom.MAX
        return min(Zoom.MAX, 8_000_000f / sumH / dpiFactor * 100f)
    }

    // ---- queries ------------------------------------------------------------------------

    /** Row index containing document-y (clamped). Binary search on row bottoms. */
    fun rowAtY(y: Float): Int {
        if (rows == 0) return 0
        var lo = 0; var hi = rows - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (rowY[mid] + rowH[mid] + gapY / 2f < y) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Pages (indexes) intersecting the viewport [sx, sy, sx+vw, sy+vh]. */
    fun visiblePages(sx: Float, sy: Float, out: MutableList<Int>) {
        out.clear()
        if (rows == 0) return
        var k = rowAtY(sy)
        while (k < rows && rowY[k] <= sy + vh) {
            for (j in rowFirst[k] until rowFirst[k] + rowCount[k]) {
                if (py[j] + ph[j] >= sy && py[j] <= sy + vh && px[j] + pw[j] >= sx && px[j] <= sx + vw) out.add(j)
            }
            k++
        }
    }

    /** Page that has the largest visible area (Sumatra's CurrentPageNo). */
    fun currentPage(sx: Float, sy: Float): Int {
        if (rows == 0) return 0
        var best = -1; var bestArea = -1f
        var k = rowAtY(sy)
        while (k < rows && rowY[k] <= sy + vh) {
            for (j in rowFirst[k] until rowFirst[k] + rowCount[k]) {
                val ix = max(0f, min(px[j] + pw[j], sx + vw) - max(px[j], sx))
                val iy = max(0f, min(py[j] + ph[j], sy + vh) - max(py[j], sy))
                val a = ix * iy
                if (a > bestArea + 0.5f) { bestArea = a; best = j }
            }
            k++
        }
        return if (best < 0) min(rowFirst[min(k, rows - 1)], pageCount - 1) else best
    }

    /** Document-space scroll offset that puts `page` (at relative position `fy` of its height) at top-left. */
    fun scrollForPage(page: Int, fy: Float = 0f): Pair<Float, Float> {
        val p = page.coerceIn(0, pageCount - 1)
        val x = px[p] - marginX
        return x to (py[p] + ph[p] * fy - marginY)
    }

    /** Valid scroll range for Y in the current mode. `row` is used by non-continuous modes. */
    fun yRange(row: Int): Pair<Float, Float> {
        if (rows == 0) return 0f to 0f
        val top: Float; val bottom: Float
        if (mode.continuous) { top = 0f; bottom = canvasH } else {
            val r = row.coerceIn(0, rows - 1)
            top = rowY[r] - marginY; bottom = rowY[r] + rowH[r] + marginY
        }
        return if (bottom - top <= vh) {
            val c = (top + bottom) / 2f - vh / 2f; c to c
        } else top to (bottom - vh)
    }

    fun xRange(): Pair<Float, Float> {
        return if (canvasW <= vw) { val c = (canvasW - vw) / 2f; c to c } else 0f to (canvasW - vw)
    }

    /**
     * Anchor = (page, fractional position inside the page) for the doc-space point (x,y).
     * Used to keep the content under the finger fixed while zooming.
     */
    fun anchorAt(x: Float, y: Float): Triple<Int, Float, Float> {
        if (pageCount == 0) return Triple(0, 0f, 0f)
        var best = 0; var bestD = Float.MAX_VALUE
        val k = rowAtY(y)
        for (kk in max(0, k - 1)..min(rows - 1, k + 1)) {
            for (j in rowFirst[kk] until rowFirst[kk] + rowCount[kk]) {
                val dx = if (x < px[j]) px[j] - x else if (x > px[j] + pw[j]) x - px[j] - pw[j] else 0f
                val dy = if (y < py[j]) py[j] - y else if (y > py[j] + ph[j]) y - py[j] - ph[j] else 0f
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = j }
            }
        }
        val fx = if (pw[best] > 0) (x - px[best]) / pw[best] else 0f
        val fy = if (ph[best] > 0) (y - py[best]) / ph[best] else 0f
        return Triple(best, fx, fy)
    }

    fun pointOfAnchor(a: Triple<Int, Float, Float>): Pair<Float, Float> {
        val p = a.first.coerceIn(0, max(0, pageCount - 1))
        return (px[p] + pw[p] * a.second) to (py[p] + ph[p] * a.third)
    }

    /** First page of the row containing `page` (for facing modes: next/prev moves whole rows). */
    fun firstPageOfRowOf(page: Int): Int = if (rows == 0) 0 else rowFirst[rowOfPage[page.coerceIn(0, pageCount - 1)]]
}
