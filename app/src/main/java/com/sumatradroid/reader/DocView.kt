// The document canvas: Sumatra's "Canvas" + DisplayModel on Android.
// Draws pages from tile bitmaps rendered by MuDoc, handles scroll / fling / pinch zoom, link taps,
// text selection (long-press + draggable handles), search highlights and the document color filters.
package com.sumatradroid.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LruCache
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class DocView(ctx: Context) : View(ctx) {

    interface Host {
        fun onPageChanged(page: Int, count: Int)
        fun onZoomChanged(text: String)
        fun onTapCanvas()
        fun onSelectionChanged(sel: Sel?)
        fun onExternalLink(uri: String)
        fun onNavChanged()
        fun onMessage(msg: String)
        fun onAutoScrollEnded()
    }

    var host: Host? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var doc: MuDoc? = null
    private var boxes: PageBoxes? = null
    private val layout = DocLayout()
    private val tmpBox = FloatArray(4)

    // ---- view state ----
    private var scrollX = 0f
    private var scrollY = 0f
    private var curRow = 0
    private var lastReportedPage = -1
    private var restore: Triple<Int, Float, Float>? = null   // page, fx, fy applied when sizes are known
    var labels: Array<String>? = null

    var displayMode: DisplayMode = DisplayMode.CONTINUOUS
        private set
    var zoomVirtual: Float = Zoom.FIT_WIDTH
        private set
    var rotation: Int = 0
        private set
    var trimMargins = false
        private set
    var showLinks = false
        set(v) { field = v; invalidate() }
    var coverPage = true
        set(v) { field = v; layout.coverPage = v; relayoutKeep() }

    // ---- colors ----
    private var theme: Theme = Theme.all[0]
    private var invert = false
    private var gray = false
    private var followTheme = false
    private var brightness = 0
    private var contrast = 0
    private var presentation = false
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var paperColor = 0xFFFFFFFF.toInt()

    // ---- caches ----
    private data class TileKey(val page: Int, val zq: Int, val rot: Int, val tx: Int, val ty: Int, val ver: Int, val sig: Int)
    private data class ThumbKey(val page: Int, val rot: Int, val ver: Int, val sig: Int)

    private val maxCache = min(96L shl 20, Runtime.getRuntime().maxMemory() / 5).toInt()
    private val tiles = object : LruCache<TileKey, Bitmap>(maxCache) {
        override fun sizeOf(key: TileKey, value: Bitmap) = value.byteCount
    }
    private val thumbs = object : LruCache<ThumbKey, Bitmap>(min(32L shl 20, Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: ThumbKey, value: Bitmap) = value.byteCount
    }
    private val pendingTiles = HashSet<TileKey>()
    private val pendingThumbs = HashSet<ThumbKey>()
    @Volatile private var wantedTiles: Set<TileKey> = emptySet()
    @Volatile private var wantedThumbs: Set<ThumbKey> = emptySet()
    private val TILE = 512

    // ---- gestures ----
    private val scroller = OverScroller(ctx)
    private var scaling = false
    private var overX = 0f
    private var overY = 0f
    private var scrollbarUntil = 0L
    private val flipThreshold = dp(72f)

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            scaling = true; scroller.forceFinished(true); return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val cur = currentPercent()
            setZoom(cur * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) { scaling = false; invalidate() }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollByUser(dx, dy); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling) return false
            flingTo(vx, vy); return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { handleTap(e.x, e.y); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { handleDoubleTap(e.x, e.y); return true }
        override fun onLongPress(e: MotionEvent) { handleLongPress(e.x, e.y) }
    })

    // ---- selection / search / links ----
    var selection: Sel? = null
        private set
    private var selHandle = 0           // 1 = start handle being dragged, 2 = end handle
    private var selLongDrag = false
    private var selFixedX = 0f
    private var selFixedY = 0f
    private var selBusy = false
    private var selPending: FloatArray? = null
    private var fingerDown = false

    private var needle = ""
    private val hitsByPage = HashMap<Int, List<FloatArray>>()
    private val hitsRequested = HashSet<Int>()
    private var curHitPage = -1
    private var curHitIdx = -1
    private val linksByPage = HashMap<Int, List<LinkInfo>>()
    private val linksRequested = HashSet<Int>()

    // ---- navigation history (Sumatra: navigate back / forward) ----
    private class NavPoint(val page: Int, val fy: Float)
    private val navStack = ArrayList<NavPoint>()
    private var navPos = 0
    val canNavBack get() = navPos > 0
    val canNavForward get() = navPos < navStack.size - 1

    // ---- auto scroll ----
    var autoScrollSpeed = 0f     // dp per second, 0 = off
        private set
    private var autoLast = 0L

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        gestures.setIsLongpressEnabled(true)
        updateFilter()
    }

    // =========================================================================================
    // public API
    // =========================================================================================

    val pageCount: Int get() = layout.pageCount
    val hasDocument: Boolean get() = doc != null

    fun attach(d: MuDoc, mode: DisplayMode, zoom: Float, rot: Int, page: Int, fx: Float, fy: Float, trim: Boolean) {
        doc = d
        displayMode = mode; zoomVirtual = zoom; rotation = ((rot % 4) + 4) % 4
        restore = Triple(page, fx, fy)
        boxes = null
        tiles.evictAll(); thumbs.evictAll()
        hitsByPage.clear(); hitsRequested.clear(); linksByPage.clear(); linksRequested.clear()
        selection = null
        navStack.clear(); navPos = 0
        layout.pageW = FloatArray(0); layout.pageH = FloatArray(0)
        layout.relayout()
        trimMargins = trim
        invalidate()
    }

    fun detach() {
        doc = null; boxes = null
        tiles.evictAll(); thumbs.evictAll()
        layout.pageW = FloatArray(0); layout.pageH = FloatArray(0); layout.relayout()
    }

    /** Called when (more of) the page boxes are known. */
    fun setBoxes(b: PageBoxes) {
        val first = boxes == null
        // keep already computed trim boxes
        val old = boxes
        if (old != null && old.n == b.n) System.arraycopy(old.trim, 0, b.trim, 0, old.trim.size)
        boxes = b
        if (first) {
            relayoutKeep()
            applyRestore()
            if (trimMargins) startTrim()
        } else relayoutKeep()
    }

    private fun applyRestore() {
        val r = restore ?: return
        restore = null
        if (layout.pageCount == 0) return
        goToPage(r.first.coerceIn(0, layout.pageCount - 1), r.third, addNav = false, fx = r.second)
    }

    fun setTheme(t: Theme) { theme = t; updateFilter() }

    fun setColors(invert: Boolean, gray: Boolean, followTheme: Boolean, brightness: Int, contrast: Int) {
        this.invert = invert; this.gray = gray; this.followTheme = followTheme
        this.brightness = brightness; this.contrast = contrast
        updateFilter()
    }

    fun setPresentation(on: Boolean) { presentation = on; invalidate() }

    /** Called when the document content changed (annotations) so cached tiles are re-rendered. */
    fun contentChanged() { invalidate() }

    fun currentPage(): Int {
        if (layout.pageCount == 0) return 0
        return if (displayMode.continuous) layout.currentPage(scrollX, scrollY)
        else layout.rowFirst[curRow.coerceIn(0, layout.rows - 1)]
    }

    /** (page, fx, fy) fractions for persisting the reading position. */
    fun viewState(): Triple<Int, Float, Float> {
        if (layout.pageCount == 0) return Triple(0, 0f, 0f)
        val p = currentPage()
        val fy = if (layout.ph[p] > 0) ((scrollY + layout.marginY - layout.py[p]) / layout.ph[p]).coerceIn(0f, 1f) else 0f
        val fx = if (layout.pw[p] > 0) ((scrollX + layout.marginX - layout.px[p]) / layout.pw[p]).coerceIn(0f, 1f) else 0f
        return Triple(p, fx, fy)
    }

    // ---- display mode / zoom / rotation ----

    fun setDisplayMode(m: DisplayMode) {
        if (m == displayMode) return
        val p = currentPage()
        displayMode = m
        relayoutKeep()
        goToPage(p, 0f, addNav = false)
    }

    fun setZoom(z: Float, fx: Float = width / 2f, fy: Float = height / 2f) {
        var zz = z
        if (zz > 0f) zz = zz.coerceIn(Zoom.MIN, min(Zoom.MAX, layout.maxPercentForCanvas().coerceAtLeast(Zoom.MIN)))
        if (zz == zoomVirtual && !scaling) return
        zoomVirtual = zz
        relayoutKeep(fx, fy)
        host?.onZoomChanged(zoomText())
        invalidate()
    }

    fun zoomIn() = setZoom(Zoom.step(currentPercent(), true))
    fun zoomOut() = setZoom(Zoom.step(currentPercent(), false))

    fun currentPercent(): Float {
        if (layout.pageCount == 0) return if (zoomVirtual > 0) zoomVirtual else 100f
        val row = if (displayMode.continuous) layout.rowAtY(scrollY + height / 2f) else curRow
        return layout.percentOf(row)
    }

    fun zoomText(): String = if (zoomVirtual > 0f) Zoom.toString(currentPercent()) else Zoom.toString(zoomVirtual)

    fun rotate(delta: Int) {
        val p = currentPage()
        rotation = ((rotation + delta) % 4 + 4) % 4
        invalidateTiles()
        relayoutKeep()
        goToPage(p, 0f, addNav = false)
    }

    fun setTrim(on: Boolean) {
        if (on == trimMargins) return
        trimMargins = on
        val p = currentPage()
        if (on) startTrim() else doc?.cancelTrim()
        invalidateTiles()
        relayoutKeep()
        goToPage(p, 0f, addNav = false)
    }

    private fun startTrim() {
        val d = doc ?: return
        val n = layout.pageCount
        if (n == 0) return
        val cur = currentPage()
        val order = IntArray(n) { (cur + it) % n }
        d.computeTrim(order) { pages, vals ->
            val b = boxes ?: return@computeTrim
            for ((idx, pg) in pages.withIndex()) {
                if (pg >= b.n) continue
                for (k in 0 until 4) b.trim[pg * 4 + k] = vals[idx * 4 + k]
            }
            if (trimMargins) relayoutKeep()
        }
    }

    private fun invalidateTiles() { /* keys contain rotation + box signature, nothing to purge */ invalidate() }

    // ---- navigation ----

    fun goToPage(page: Int, fy: Float = 0f, addNav: Boolean = true, fx: Float = 0f) {
        if (layout.pageCount == 0) { restore = Triple(page, fx, fy); return }
        val p = page.coerceIn(0, layout.pageCount - 1)
        if (addNav) pushNav()
        scroller.forceFinished(true)
        curRow = layout.rowOfPage[p]
        val (sx, sy) = layout.scrollForPage(p, fy)
        scrollY = sy
        scrollX = if (fx > 0f) layout.px[p] + layout.pw[p] * fx - layout.marginX else sx
        clampScroll()
        afterScroll()
        if (addNav) pushNavTarget(p, fy)
        invalidate()
    }

    fun nextPage() { goRow(+1) }
    fun prevPage() { goRow(-1) }
    fun firstPage() = goToPage(0)
    fun lastPage() = goToPage(layout.pageCount - 1)

    private fun goRow(delta: Int, toBottom: Boolean = false) {
        if (layout.rows == 0) return
        val cur = if (displayMode.continuous) layout.rowOfPage[currentPage()] else curRow
        val r = (cur + delta).coerceIn(0, layout.rows - 1)
        if (r == cur && displayMode.continuous.not()) { return }
        curRow = r
        scroller.forceFinished(true)
        val first = layout.rowFirst[r]
        val (sx, sy) = layout.scrollForPage(first, 0f)
        scrollX = sx
        scrollY = if (toBottom && !displayMode.continuous) layout.yRange(r).second else sy
        clampScroll()
        afterScroll()
        invalidate()
    }

    private fun pushNav() {
        val st = viewState()
        val here = NavPoint(st.first, st.third)
        while (navStack.size > navPos + 1) navStack.removeAt(navStack.size - 1)
        if (navStack.isEmpty()) { navStack.add(here); navPos = 0 }
        else navStack[navPos] = here
    }

    private fun pushNavTarget(p: Int, fy: Float) {
        navStack.add(NavPoint(p, fy)); navPos = navStack.size - 1
        host?.onNavChanged()
    }

    fun navBack() {
        if (!canNavBack) return
        pushNavIfAtEnd()
        navPos--; val n = navStack[navPos]
        goToPage(n.page, n.fy, addNav = false); host?.onNavChanged()
    }

    fun navForward() {
        if (!canNavForward) return
        navPos++; val n = navStack[navPos]
        goToPage(n.page, n.fy, addNav = false); host?.onNavChanged()
    }

    private fun pushNavIfAtEnd() {
        if (navPos == navStack.size - 1) {
            val st = viewState()
            navStack[navPos] = NavPoint(st.first, st.third)
        }
    }

    fun scrollByKey(dx: Float, dy: Float) { scrollByUser(dx, dy) }

    // ---- reading helpers ----

    fun setAutoScroll(dpPerSecond: Float) {
        autoScrollSpeed = dpPerSecond
        autoLast = 0L
        if (dpPerSecond > 0f) postInvalidateOnAnimation()
    }

    // =========================================================================================
    // layout
    // =========================================================================================

    private fun rebuildInputs() {
        val b = boxes
        layout.vw = width; layout.vh = height
        layout.marginX = dp(2f); layout.marginY = dp(3f)
        val gap = dp(spacingDp)
        layout.gapX = gap; layout.gapY = gap
        layout.dpiFactor = 96f / 72f * density
        layout.mode = displayMode
        layout.zoomVirtual = zoomVirtual
        layout.rotation = rotation
        layout.coverPage = coverPage
        if (b == null) {
            layout.pageW = FloatArray(0); layout.pageH = FloatArray(0); return
        }
        if (layout.pageW.size != b.n) { layout.pageW = FloatArray(b.n); layout.pageH = FloatArray(b.n) }
        for (i in 0 until b.n) {
            b.box(i, trimMargins, tmpBox)
            layout.pageW[i] = max(1f, tmpBox[2] - tmpBox[0]); layout.pageH[i] = max(1f, tmpBox[3] - tmpBox[1])
        }
    }

    var spacingDp = 6f
        set(v) { field = v; relayoutKeep() }

    private fun relayoutKeep(fx: Float = width / 2f, fy: Float = height / 2f) {
        if (width == 0 || height == 0) return
        val had = layout.pageCount > 0 && layout.px.size == layout.pageCount
        val a = if (had) layout.anchorAt(scrollX + fx, scrollY + fy) else null
        val oldCount = layout.pageCount
        rebuildInputs()
        layout.relayout()
        if (a != null && layout.pageCount == oldCount) {
            val (x, y) = layout.pointOfAnchor(a)
            scrollX = x - fx; scrollY = y - fy
            if (!displayMode.continuous) curRow = layout.rowOfPage[a.first]
        } else if (layout.pageCount > 0) {
            curRow = curRow.coerceIn(0, layout.rows - 1)
        }
        clampScroll()
        afterScroll()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0) {
            val keepCenter = layout.pageCount > 0
            relayoutKeep(if (keepCenter && ow > 0) ow / 2f else w / 2f, if (keepCenter && oh > 0) oh / 2f else h / 2f)
            if (boxes != null && restore != null) applyRestore()
            host?.onZoomChanged(zoomText())
        }
    }

    private fun clampScroll() {
        val (xl, xh) = layout.xRange()
        scrollX = scrollX.coerceIn(xl, max(xl, xh))
        val (yl, yh) = layout.yRange(curRow)
        scrollY = scrollY.coerceIn(yl, max(yl, yh))
    }

    private fun afterScroll() {
        if (layout.pageCount == 0) return
        val p = currentPage()
        if (p != lastReportedPage) {
            lastReportedPage = p
            host?.onPageChanged(p, layout.pageCount)
        }
        scrollbarUntil = System.currentTimeMillis() + 900
    }

    // =========================================================================================
    // input
    // =========================================================================================

    private fun scrollByUser(dx: Float, dy: Float) {
        if (layout.pageCount == 0) return
        val (xl, xh) = layout.xRange(); val (yl, yh) = layout.yRange(curRow)
        val wx = scrollX + dx; val wy = scrollY + dy
        val cx = wx.coerceIn(xl, max(xl, xh)); val cy = wy.coerceIn(yl, max(yl, yh))
        // accumulate what could not be scrolled (used for page flips in non-continuous modes)
        overX += wx - cx; overY += wy - cy
        scrollX = cx; scrollY = cy
        if (displayMode.continuous) { overX = 0f; overY = 0f }
        afterScroll()
        invalidate()
    }

    private fun finishOverscroll() {
        if (!displayMode.continuous) {
            val ox = overX; val oy = overY
            if (abs(oy) > flipThreshold && abs(oy) >= abs(ox)) {
                if (oy > 0) goRow(+1) else goRow(-1, toBottom = true)
            } else if (abs(ox) > flipThreshold) {
                if (ox > 0) goRow(+1) else goRow(-1, toBottom = true)
            }
        }
        overX = 0f; overY = 0f
    }

    private fun flingTo(vx: Float, vy: Float) {
        if (layout.pageCount == 0) return
        val (xl, xh) = layout.xRange(); val (yl, yh) = layout.yRange(curRow)
        if (!displayMode.continuous) {
            val atBottom = scrollY >= yh - 1f; val atTop = scrollY <= yl + 1f
            val fitsX = xh - xl < 1f
            if (fitsX && abs(vx) > 1400f && abs(vx) > abs(vy)) { goRow(if (vx < 0) +1 else -1); return }
            if (atBottom && vy < -2600f) { goRow(+1); return }
            if (atTop && vy > 2600f) { goRow(-1, toBottom = true); return }
        }
        scroller.fling(scrollX.roundToInt(), scrollY.roundToInt(), (-vx).roundToInt(), (-vy).roundToInt(),
            xl.roundToInt(), max(xl, xh).roundToInt(), yl.roundToInt(), max(yl, yh).roundToInt())
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX = scroller.currX.toFloat(); scrollY = scroller.currY.toFloat()
            afterScroll()
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerDown = true
                if (selection != null) {
                    val h = handleAt(ev.x, ev.y)
                    if (h != 0) {
                        selHandle = h; scroller.forceFinished(true)
                        val s = selection!!
                        if (h == 1) { val e = selEndPt(s); selFixedX = e[0]; selFixedY = e[1] }
                        else { val st = selStartPt(s); selFixedX = st[0]; selFixedY = st[1] }
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (selHandle != 0 || selLongDrag) {
                    dragSelection(ev.x, ev.y - if (selHandle != 0) dp(22f) else 0f)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                fingerDown = false
                selHandle = 0; selLongDrag = false
                finishOverscroll()
            }
        }
        scaleDetector.onTouchEvent(ev)
        if (!scaling || ev.pointerCount > 1) gestures.onTouchEvent(ev)
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        if (layout.pageCount == 0) return
        if (selection != null) { clearSelection(); return }
        val hit = pageAt(x, y)
        if (hit >= 0) {
            val pt = FloatArray(2)
            viewToPage(hit, x, y, pt)
            val d = doc
            if (d != null) {
                d.links(hit) { list ->
                    linksByPage[hit] = list
                    val l = list.firstOrNull { pt[0] >= it.x0 && pt[0] <= it.x1 && pt[1] >= it.y0 && pt[1] <= it.y1 }
                    if (l != null) followLink(l.uri) else tapEmpty(x)
                }
                return
            }
        }
        tapEmpty(x)
    }

    private fun tapEmpty(x: Float) {
        val tapZones = host != null && !displayMode.continuous && tapZonesEnabled
        if (tapZones) {
            if (x < width * 0.22f) { goRow(-1); return }
            if (x > width * 0.78f) { goRow(+1); return }
        }
        host?.onTapCanvas()
    }

    var tapZonesEnabled = true

    private fun followLink(uri: String) {
        doc?.resolve(uri) { t ->
            when (t) {
                is LinkTarget.External -> host?.onExternalLink(t.uri)
                is LinkTarget.Internal -> {
                    if (t.page in 0 until layout.pageCount) {
                        val fy = if (t.hasY) destFraction(t.page, if (t.x.isNaN()) 0f else t.x, t.y) else 0f
                        goToPage(t.page, fy.coerceIn(0f, 1f))
                    }
                }
                LinkTarget.None -> host?.onMessage("Link target not found")
            }
        }
    }

    /** Fractional vertical position (0 = top of the displayed page) of a destination point in page space. */
    private fun destFraction(p: Int, x: Float, y: Float): Float {
        val xf = FloatArray(6); xfFor(p, layout.pz[p], xf)
        val ry = xf[1] * x + xf[3] * y + xf[5]
        return if (layout.ph[p] > 0f) ry / layout.ph[p] else 0f
    }

    private fun handleDoubleTap(x: Float, y: Float) {
        if (layout.pageCount == 0) return
        if (Zoom.isFit(zoomVirtual) || currentPercent() < 150f) {
            // zoom in around the tap (Sumatra: double-click toggles between fit and a larger zoom)
            val fit = currentPercent()
            setZoom(max(fit * 2.2f, 150f).coerceAtMost(1000f), x, y)
        } else {
            setZoom(Zoom.FIT_WIDTH, x, y)
            if (!displayMode.continuous) goToPage(currentPage(), 0f, addNav = false)
        }
    }

    private fun handleLongPress(x: Float, y: Float) {
        val pg = pageAt(x, y)
        if (pg < 0) return
        val pt = FloatArray(2); viewToPage(pg, x, y, pt)
        doc?.selectWord(pg, pt[0], pt[1]) { s ->
            if (s != null) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                setSelection(s)
                if (fingerDown) {
                    selLongDrag = true
                    selFixedX = s.ax; selFixedY = s.ay
                }
            }
        }
    }

    // ---- selection ----

    private fun setSelection(s: Sel?) {
        selection = s
        host?.onSelectionChanged(s)
        invalidate()
    }

    fun clearSelection() { if (selection != null) setSelection(null) }

    fun selectAllOnPage() {
        val p = currentPage()
        val b = boxes ?: return
        val x0 = b.raw[p * 4]; val y0 = b.raw[p * 4 + 1]; val x1 = b.raw[p * 4 + 2]; val y1 = b.raw[p * 4 + 3]
        doc?.highlight(p, x0 + 1f, y0 + 1f, x1 - 1f, y1 - 1f) { s -> if (s != null && s.text.isNotEmpty()) setSelection(s) }
    }

    private fun selStartPt(s: Sel): FloatArray {
        val q = s.quads
        if (q.size < 8) return floatArrayOf(s.ax, s.ay)
        return floatArrayOf((q[0] + q[4]) / 2f + 0.5f, (q[1] + q[5]) / 2f)
    }

    private fun selEndPt(s: Sel): FloatArray {
        val q = s.quads
        if (q.size < 8) return floatArrayOf(s.bx, s.by)
        val o = q.size - 8
        return floatArrayOf((q[o + 2] + q[o + 6]) / 2f - 0.5f, (q[o + 3] + q[o + 7]) / 2f)
    }

    /** Handle anchor positions in view coordinates: [startX, startY, endX, endY]. */
    private fun handlePositions(): FloatArray? {
        val s = selection ?: return null
        val q = s.quads
        if (q.size < 8) return null
        val o = q.size - 8
        val out = FloatArray(4)
        val pt = FloatArray(2)
        pageToView(s.page, q[4], q[5], pt); out[0] = pt[0]; out[1] = pt[1]
        pageToView(s.page, q[o + 6], q[o + 7], pt); out[2] = pt[0]; out[3] = pt[1]
        return out
    }

    private fun handleAt(x: Float, y: Float): Int {
        val h = handlePositions() ?: return 0
        val r = dp(26f)
        val d1 = dist(x, y, h[0], h[1] + dp(12f)); val d2 = dist(x, y, h[2], h[3] + dp(12f))
        if (d1 > r && d2 > r) return 0
        return if (d1 <= d2) 1 else 2
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = Math.hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()

    private fun dragSelection(vx: Float, vy: Float) {
        val s = selection ?: return
        val pt = FloatArray(2); viewToPage(s.page, vx, vy, pt)
        val args = floatArrayOf(selFixedX, selFixedY, pt[0], pt[1])
        if (selBusy) { selPending = args; return }
        issueSelection(s.page, args)
    }

    private fun issueSelection(page: Int, a: FloatArray) {
        selBusy = true
        doc?.highlight(page, a[0], a[1], a[2], a[3]) { s ->
            selBusy = false
            if (s != null && selection != null) setSelection(s)
            val pend = selPending; selPending = null
            if (pend != null && (selHandle != 0 || selLongDrag)) issueSelection(page, pend)
        }
    }

    // ---- search ----

    fun setSearchNeedle(n: String) {
        needle = n
        hitsByPage.clear(); hitsRequested.clear(); curHitPage = -1; curHitIdx = -1
        invalidate()
    }

    fun showSearchResult(r: SearchResult) {
        hitsByPage[r.page] = r.hits; hitsRequested.add(r.page)
        curHitPage = r.page; curHitIdx = r.index
        val q = r.hits[r.index]
        // bounding box of the hit in page space -> view position
        var cx = 0f; var cy = 0f; var n = 0
        var i = 0
        while (i + 7 < q.size) {
            cx += (q[i] + q[i + 2] + q[i + 4] + q[i + 6]) / 4f; cy += (q[i + 1] + q[i + 3] + q[i + 5] + q[i + 7]) / 4f; n++; i += 8
        }
        if (n > 0) { cx /= n; cy /= n }
        if (layout.pageCount == 0) return
        pushNav()
        curRow = layout.rowOfPage[r.page]
        val xf = FloatArray(6); xfFor(r.page, layout.pz[r.page], xf)
        val rx = xf[0] * cx + xf[2] * cy + xf[4]; val ry = xf[1] * cx + xf[3] * cy + xf[5]
        scroller.forceFinished(true)
        scrollX = layout.px[r.page] + rx - width / 2f
        scrollY = layout.py[r.page] + ry - height / 3f
        clampScroll(); afterScroll(); invalidate()
        pushNavTarget(r.page, 0f.coerceAtLeast(if (layout.ph[r.page] > 0) ry / layout.ph[r.page] else 0f))
    }

    fun clearSearch() { setSearchNeedle("") }

    // =========================================================================================
    // coordinates
    // =========================================================================================

    /** Page space -> page raster px (relative to the page's top-left) for scale s. */
    private fun xfFor(i: Int, s: Float, o: FloatArray) {
        val b = boxes
        if (b == null) { o[0] = s; o[1] = 0f; o[2] = 0f; o[3] = s; o[4] = 0f; o[5] = 0f; return }
        b.box(i, trimMargins, tmpBox)
        val x0 = tmpBox[0]; val y0 = tmpBox[1]; val x1 = tmpBox[2]; val y1 = tmpBox[3]
        when (rotation) {
            0 -> { o[0] = s; o[1] = 0f; o[2] = 0f; o[3] = s; o[4] = -s * x0; o[5] = -s * y0 }
            1 -> { o[0] = 0f; o[1] = s; o[2] = -s; o[3] = 0f; o[4] = s * y1; o[5] = -s * x0 }
            2 -> { o[0] = -s; o[1] = 0f; o[2] = 0f; o[3] = -s; o[4] = s * x1; o[5] = s * y1 }
            else -> { o[0] = 0f; o[1] = -s; o[2] = s; o[3] = 0f; o[4] = -s * y0; o[5] = s * x1 }
        }
    }

    private val xfTmp = FloatArray(6)

    private fun pageToView(i: Int, x: Float, y: Float, out: FloatArray) {
        xfFor(i, layout.pz[i], xfTmp)
        out[0] = xfTmp[0] * x + xfTmp[2] * y + xfTmp[4] + layout.px[i] - scrollX
        out[1] = xfTmp[1] * x + xfTmp[3] * y + xfTmp[5] + layout.py[i] - scrollY
    }

    private fun viewToPage(i: Int, vx: Float, vy: Float, out: FloatArray) {
        xfFor(i, layout.pz[i], xfTmp)
        val X = vx + scrollX - layout.px[i]; val Y = vy + scrollY - layout.py[i]
        val a = xfTmp[0]; val b = xfTmp[1]; val c = xfTmp[2]; val d = xfTmp[3]; val e = xfTmp[4]; val f = xfTmp[5]
        val det = a * d - b * c
        if (det == 0f) { out[0] = 0f; out[1] = 0f; return }
        out[0] = (d * (X - e) - c * (Y - f)) / det
        out[1] = (-b * (X - e) + a * (Y - f)) / det
    }

    /** Page under a view point, or -1. */
    private fun pageAt(vx: Float, vy: Float): Int {
        if (layout.pageCount == 0) return -1
        val x = vx + scrollX; val y = vy + scrollY
        val row = layout.rowAtY(y)
        for (j in layout.rowFirst[row] until layout.rowFirst[row] + layout.rowCount[row]) {
            if (x >= layout.px[j] && x <= layout.px[j] + layout.pw[j] && y >= layout.py[j] && y <= layout.py[j] + layout.ph[j]) return j
        }
        return -1
    }

    // =========================================================================================
    // colors
    // =========================================================================================

    private fun updateFilter() {
        val m = ColorMatrix()
        if (gray) m.setSaturation(0f)
        if (followTheme) {
            // map black -> theme text color, white -> theme background (luminance based), like Sumatra's "document colors"
            val t = theme.text; val bg = theme.background
            val tr = android.graphics.Color.red(t).toFloat(); val tg = android.graphics.Color.green(t).toFloat(); val tb = android.graphics.Color.blue(t).toFloat()
            val br = android.graphics.Color.red(bg).toFloat(); val bgG = android.graphics.Color.green(bg).toFloat(); val bb = android.graphics.Color.blue(bg).toFloat()
            val lr = 0.299f; val lg = 0.587f; val lb = 0.114f
            val tint = ColorMatrix(floatArrayOf(
                (br - tr) / 255f * lr, (br - tr) / 255f * lg, (br - tr) / 255f * lb, 0f, tr,
                (bgG - tg) / 255f * lr, (bgG - tg) / 255f * lg, (bgG - tg) / 255f * lb, 0f, tg,
                (bb - tb) / 255f * lr, (bb - tb) / 255f * lg, (bb - tb) / 255f * lb, 0f, tb,
                0f, 0f, 0f, 1f, 0f))
            m.postConcat(tint)
        }
        if (invert) m.postConcat(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        if (brightness != 0 || contrast != 0) {
            val k = (100f + contrast) / 100f
            val t = 128f * (1f - k) + brightness * 2.55f
            m.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        }
        val arr = m.array
        fun ch(row: Int): Int {
            val v = arr[row * 5] * 255f + arr[row * 5 + 1] * 255f + arr[row * 5 + 2] * 255f + arr[row * 5 + 4]
            return v.roundToInt().coerceIn(0, 255)
        }
        paperColor = (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        tilePaint.colorFilter = ColorMatrixColorFilter(m)
        invalidate()
    }

    // =========================================================================================
    // drawing
    // =========================================================================================

    private val rect = RectF()
    private val visible = ArrayList<Int>()
    private val path = Path()
    private val ptTmp = FloatArray(2)

    private fun boxSig(i: Int): Int {
        val b = boxes ?: return 0
        b.box(i, trimMargins, tmpBox)
        var h = 17
        for (k in 0 until 4) h = h * 31 + java.lang.Float.floatToIntBits(tmpBox[k])
        return h
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(if (presentation) 0xFF000000.toInt() else theme.background)
        val d = doc
        if (d == null || layout.pageCount == 0) return

        layout.visiblePages(scrollX, scrollY, visible)
        val ver = d.version
        val wantT = HashSet<TileKey>(); val wantH = HashSet<ThumbKey>()
        val doRequests = !scaling

        for (i in visible) {
            val pl = layout.px[i] - scrollX; val pt = layout.py[i] - scrollY
            val pw = layout.pw[i]; val ph = layout.ph[i]
            // shadow + border + paper
            if (!presentation) {
                fill.color = 0x33000000; rect.set(pl + dp(2f), pt + dp(2f), pl + pw + dp(2f), pt + ph + dp(2f)); canvas.drawRect(rect, fill)
            }
            fill.color = paperColor; rect.set(pl, pt, pl + pw, pt + ph); canvas.drawRect(rect, fill)

            val sig = boxSig(i)
            // low-res thumbnail as placeholder
            val tk = ThumbKey(i, rotation, ver, sig)
            wantH.add(tk)
            val th = thumbs.get(tk)
            if (th != null) canvas.drawBitmap(th, null, rect, tilePaint)
            else if (doRequests) requestThumb(i, tk)

            // tiles at the current zoom
            val zq = (layout.pz[i] * 1000f).roundToInt().coerceAtLeast(1)
            val s = zq / 1000f
            val rw = layout.rotW(i); val rh = layout.rotH(i)
            val wi = ceil(rw * s).toInt().coerceAtLeast(1); val hi = ceil(rh * s).toInt().coerceAtLeast(1)
            val fx = if (wi > 0) pw / (rw * s) else 1f
            val fy = if (hi > 0) ph / (rh * s) else 1f
            val rx0 = max(0f, (0f - pl) / fx); val rx1 = min(wi.toFloat(), (width - pl) / fx)
            val ry0 = max(0f, (0f - pt) / fy); val ry1 = min(hi.toFloat(), (height - pt) / fy)
            if (rx1 > rx0 && ry1 > ry0) {
                val tx0 = floor(rx0 / TILE).toInt(); val tx1 = floor((rx1 - 0.01f) / TILE).toInt()
                val ty0 = floor(ry0 / TILE).toInt(); val ty1 = floor((ry1 - 0.01f) / TILE).toInt()
                for (ty in ty0..ty1) for (tx in tx0..tx1) {
                    val key = TileKey(i, zq, rotation, tx, ty, ver, sig)
                    wantT.add(key)
                    val bmp = tiles.get(key)
                    if (bmp != null) {
                        rect.set(pl + tx * TILE * fx, pt + ty * TILE * fy,
                            pl + min((tx + 1) * TILE, wi) * fx, pt + min((ty + 1) * TILE, hi) * fy)
                        canvas.drawBitmap(bmp, null, rect, tilePaint)
                    } else if (doRequests) requestTile(i, key, s, tx, ty, wi, hi)
                }
            }
            if (!presentation) {
                stroke.color = theme.edge; stroke.strokeWidth = 1f
                rect.set(pl, pt, pl + pw, pt + ph); canvas.drawRect(rect, stroke)
            }
        }
        wantedTiles = wantT; wantedThumbs = wantH

        drawOverlays(canvas)
        drawScrollbar(canvas)

        if (autoScrollSpeed > 0f) {
            val now = System.nanoTime()
            if (autoLast != 0L) {
                val dt = (now - autoLast) / 1e9f
                autoStep(autoScrollSpeed * density * dt)
            }
            autoLast = now
            if (autoScrollSpeed > 0f) postInvalidateOnAnimation()
        }
    }

    private var autoAcc = 0f

    private fun autoStep(dy: Float) {
        autoAcc += dy
        if (autoAcc < 1f) return
        val step = autoAcc.toInt(); autoAcc -= step
        val (_, yh) = layout.yRange(curRow)
        if (scrollY >= yh - 0.5f) {
            val lastRow = if (displayMode.continuous) true else curRow >= layout.rows - 1
            if (lastRow) { autoScrollSpeed = 0f; host?.onAutoScrollEnded(); return }
            goRow(+1); return
        }
        scrollY += step
        clampScroll(); afterScroll()
    }

    private fun requestThumb(i: Int, key: ThumbKey) {
        if (!pendingThumbs.add(key)) return
        val d = doc ?: return
        val rw = layout.rotW(i)
        val s = 320f / max(1f, rw)
        val m = FloatArray(6); xfFor(i, s, m)
        val w = ceil(rw * s).toInt().coerceAtLeast(1); val h = ceil(layout.rotH(i) * s).toInt().coerceAtLeast(1)
        d.renderTile(i, m, 0, 0, w, h, { wantedThumbs.contains(key) }) { bmp ->
            pendingThumbs.remove(key)
            if (bmp != null) { thumbs.put(key, bmp); invalidate() }
        }
    }

    private fun requestTile(i: Int, key: TileKey, s: Float, tx: Int, ty: Int, wi: Int, hi: Int) {
        if (!pendingTiles.add(key)) return
        val d = doc ?: return
        val m = FloatArray(6); xfFor(i, s, m)
        val ox = tx * TILE; val oy = ty * TILE
        val w = min(TILE, wi - ox); val h = min(TILE, hi - oy)
        if (w <= 0 || h <= 0) { pendingTiles.remove(key); return }
        d.renderTile(i, m, ox, oy, w, h, { wantedTiles.contains(key) }) { bmp ->
            pendingTiles.remove(key)
            if (bmp != null) { tiles.put(key, bmp); invalidate() }
        }
    }

    private fun drawQuads(canvas: Canvas, page: Int, q: FloatArray) {
        var i = 0
        while (i + 7 < q.size) {
            path.rewind()
            pageToView(page, q[i], q[i + 1], ptTmp); path.moveTo(ptTmp[0], ptTmp[1])          // ul
            pageToView(page, q[i + 2], q[i + 3], ptTmp); path.lineTo(ptTmp[0], ptTmp[1])      // ur
            pageToView(page, q[i + 6], q[i + 7], ptTmp); path.lineTo(ptTmp[0], ptTmp[1])      // lr
            pageToView(page, q[i + 4], q[i + 5], ptTmp); path.lineTo(ptTmp[0], ptTmp[1])      // ll
            path.close()
            canvas.drawPath(path, fill)
            i += 8
        }
    }

    private fun drawOverlays(canvas: Canvas) {
        val d = doc ?: return
        // search hits
        if (needle.isNotEmpty()) {
            for (i in visible) {
                val hs = hitsByPage[i]
                if (hs == null) {
                    if (hitsRequested.add(i)) {
                        val nd = needle
                        d.hitsForPage(i, nd) { list -> if (nd == needle) { hitsByPage[i] = list; invalidate() } }
                    }
                    continue
                }
                for ((idx, h) in hs.withIndex()) {
                    val cur = i == curHitPage && idx == curHitIdx
                    fill.color = if (cur) 0x99FF8F00.toInt() else 0x66FFEB3B
                    fill.style = Paint.Style.FILL
                    drawQuads(canvas, i, h)
                }
            }
        }
        // links
        if (showLinks) {
            for (i in visible) {
                val ls = linksByPage[i]
                if (ls == null) {
                    if (linksRequested.add(i)) d.links(i) { l -> linksByPage[i] = l; invalidate() }
                    continue
                }
                stroke.color = theme.link; stroke.strokeWidth = dp(1f)
                for (l in ls) {
                    path.rewind()
                    pageToView(i, l.x0, l.y0, ptTmp); path.moveTo(ptTmp[0], ptTmp[1])
                    pageToView(i, l.x1, l.y0, ptTmp); path.lineTo(ptTmp[0], ptTmp[1])
                    pageToView(i, l.x1, l.y1, ptTmp); path.lineTo(ptTmp[0], ptTmp[1])
                    pageToView(i, l.x0, l.y1, ptTmp); path.lineTo(ptTmp[0], ptTmp[1])
                    path.close(); canvas.drawPath(path, stroke)
                }
            }
        }
        // selection
        val s = selection
        if (s != null && s.page in 0 until layout.pageCount) {
            fill.color = 0x553399FF; fill.style = Paint.Style.FILL
            drawQuads(canvas, s.page, s.quads)
            val h = handlePositions()
            if (h != null) {
                fill.color = theme.link or (0xFF shl 24)
                val r = dp(8f)
                canvas.drawCircle(h[0], h[1] + dp(12f), r, fill); canvas.drawRect(h[0] - 1f, h[1], h[0] + 1f, h[1] + dp(6f), fill)
                canvas.drawCircle(h[2], h[3] + dp(12f), r, fill); canvas.drawRect(h[2] - 1f, h[3], h[2] + 1f, h[3] + dp(6f), fill)
            }
        }
    }

    private fun drawScrollbar(canvas: Canvas) {
        if (System.currentTimeMillis() > scrollbarUntil && scroller.isFinished && !fingerDown) return
        if (!displayMode.continuous || layout.canvasH <= height) return
        val trackH = height - dp(8f)
        val thumbH = max(dp(36f), trackH * height / layout.canvasH)
        val frac = (scrollY / (layout.canvasH - height)).coerceIn(0f, 1f)
        val top = dp(4f) + (trackH - thumbH) * frac
        fill.color = (theme.darkerText and 0x00FFFFFF) or 0x99000000.toInt()
        rect.set(width - dp(5f), top, width - dp(2f), top + thumbH)
        canvas.drawRoundRect(rect, dp(2f), dp(2f), fill)
        postInvalidateDelayed(200)
    }

    // =========================================================================================
    // keyboard (hardware keyboards, volume keys are handled by the activity)
    // =========================================================================================

    fun handleKey(code: Int, ev: KeyEvent): Boolean {
        val line = dp(48f); val page = height * 0.9f
        when (code) {
            KeyEvent.KEYCODE_DPAD_DOWN -> scrollByUser(0f, line)
            KeyEvent.KEYCODE_DPAD_UP -> scrollByUser(0f, -line)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (ev.isShiftPressed || !displayMode.continuous) nextPage() else scrollByUser(line, 0f)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (ev.isShiftPressed || !displayMode.continuous) prevPage() else scrollByUser(-line, 0f)
            KeyEvent.KEYCODE_PAGE_DOWN -> nextPage()
            KeyEvent.KEYCODE_PAGE_UP -> prevPage()
            KeyEvent.KEYCODE_SPACE -> if (ev.isShiftPressed) scrollPageUp() else scrollPageDown()
            KeyEvent.KEYCODE_MOVE_HOME -> firstPage()
            KeyEvent.KEYCODE_MOVE_END -> lastPage()
            else -> return false
        }
        return true
    }

    /** Scroll down by a screenful; at the end of the page/row go to the next one (Sumatra's Space key). */
    fun scrollPageDown() {
        val (_, yh) = layout.yRange(curRow)
        if (!displayMode.continuous && scrollY >= yh - 1f) { goRow(+1); return }
        scrollByUser(0f, height * 0.9f)
    }

    fun scrollPageUp() {
        val (yl, _) = layout.yRange(curRow)
        if (!displayMode.continuous && scrollY <= yl + 1f) { goRow(-1, toBottom = true); return }
        scrollByUser(0f, -height * 0.9f)
    }
}
