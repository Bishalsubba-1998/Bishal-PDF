// Document engine wrapper around MuPDF (the same engine SumatraPDF uses).
// MuPDF objects are not thread-safe, so every call runs on one dedicated "mupdf" thread;
// results are delivered on the main thread.
package com.sumatradroid.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Callable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ---- plain data classes handed to the UI ------------------------------------------------

/** Raw page boxes in PDF points (x0,y0,x1,y1 per page) plus optional trimmed content boxes. */
class PageBoxes(val n: Int) {
    val raw = FloatArray(n * 4)
    val trim = FloatArray(n * 4) { Float.NaN }
    fun copy(): PageBoxes {
        val c = PageBoxes(n)
        System.arraycopy(raw, 0, c.raw, 0, raw.size); System.arraycopy(trim, 0, c.trim, 0, trim.size)
        return c
    }
    fun hasTrim(i: Int) = !trim[i * 4].isNaN()
    /** Box used for layout/rendering: trimmed one if requested and known, otherwise the full box. */
    fun box(i: Int, useTrim: Boolean, out: FloatArray) {
        val src = if (useTrim && hasTrim(i)) trim else raw
        out[0] = src[i * 4]; out[1] = src[i * 4 + 1]; out[2] = src[i * 4 + 2]; out[3] = src[i * 4 + 3]
    }
}

class TocItem(val title: String, val level: Int, val uri: String?, var page: Int, val hasChildren: Boolean)
class LinkInfo(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val uri: String)
class Sel(val page: Int, val ax: Float, val ay: Float, val bx: Float, val by: Float, val quads: FloatArray, val text: String)
class SearchResult(val page: Int, val index: Int, val hits: List<FloatArray>, val wrapped: Boolean)

sealed class LinkTarget {
    class External(val uri: String) : LinkTarget()
    class Internal(val page: Int, val x: Float, val y: Float, val hasY: Boolean) : LinkTarget()
    object None : LinkTarget()
}

sealed class OpenResult {
    object Ok : OpenResult()
    object NeedPassword : OpenResult()
    class Error(val message: String) : OpenResult()
}

/** Opens a document from a content:// uri (without copying the file) or a file path. */
class DocSource(private val ctx: Context, val uri: Uri) {
    private var pfd: ParcelFileDescriptor? = null
    private var tempFile: File? = null

    val displayName: String = queryName() ?: uri.lastPathSegment ?: "document"
    val mime: String? = try { ctx.contentResolver.getType(uri) } catch (_: Exception) { null }

    private fun queryName(): String? {
        if (uri.scheme == "file") return File(uri.path ?: return null).name
        return try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null }
    }

    private fun magic(): String {
        val n = displayName
        return if (n.contains('.')) n else (mime ?: "application/pdf")
    }

    fun open(): Document {
        if (uri.scheme == "file") return Document.openDocument(uri.path)
        try {
            val p = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("no fd")
            pfd = p
            val s = FdStream(p)
            s.position() // fails for non-seekable descriptors -> fall back to copying
            return Document.openDocument(s, magic())
        } catch (e: Exception) {
            try { pfd?.close() } catch (_: Exception) { }
            pfd = null
        }
        // fallback: copy to cache
        val dir = File(ctx.cacheDir, "opened").apply { mkdirs() }
        val ext = displayName.substringAfterLast('.', "pdf")
        val f = File(dir, HistoryStore.sha1(uri.toString()) + "." + ext)
        ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        tempFile = f
        return Document.openDocument(f.absolutePath)
    }

    fun close() {
        try { pfd?.close() } catch (_: Exception) { }
        pfd = null
    }
}

class FdStream(private val pfd: ParcelFileDescriptor) : SeekableInputStream {
    private val fis = FileInputStream(pfd.fileDescriptor)
    private val ch = fis.channel
    override fun read(b: ByteArray): Int = fis.read(b)
    override fun seek(offset: Long, whence: Int): Long {
        val target = when (whence) {
            0 -> offset
            1 -> ch.position() + offset
            else -> ch.size() + offset
        }
        ch.position(target)
        return ch.position()
    }
    override fun position(): Long = ch.position()
}

// ---- the engine -------------------------------------------------------------------------

class MuDoc(val source: DocSource) {
    private val main = Handler(Looper.getMainLooper())
    private val exec = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        ThreadFactory { r -> Thread(null, r, "mupdf", 16L shl 20) })

    private var doc: Document? = null
    @Volatile var closed = false
    var pageCount = 0
        private set
    var isPdf = false
        private set
    var reflowable = false
        private set
    /** Bumped whenever page contents change (annotations, reflow); part of render cache keys. */
    @Volatile var version = 0
        private set
    @Volatile var dirty = false
        private set

    // reflow parameters (reflowable documents only)
    var layoutW = 300f
    var layoutH = 480f
    var em = 12f

    private val searchGen = AtomicInteger()
    private val trimGen = AtomicInteger()

    private fun <T> async(work: () -> T, done: (T) -> Unit, fail: ((Throwable) -> Unit)? = null) {
        if (closed) return
        try {
            exec.execute {
                if (closed) return@execute
                try {
                    val r = work()
                    main.post { if (!closed) done(r) }
                } catch (t: Throwable) {
                    main.post { if (!closed) fail?.invoke(t) }
                }
            }
        } catch (_: Exception) { }
    }

    // ---- caches (exec thread only) ----
    private val dlCache = object : LinkedHashMap<Int, com.artifex.mupdf.fitz.DisplayList>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, com.artifex.mupdf.fitz.DisplayList>?): Boolean {
            if (size > 6) { try { e?.value?.destroy() } catch (_: Exception) { }; return true }
            return false
        }
    }
    private val stCache = object : LinkedHashMap<Int, StructuredText>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, StructuredText>?): Boolean {
            if (size > 4) { try { e?.value?.destroy() } catch (_: Exception) { }; return true }
            return false
        }
    }
    private val linkCache = HashMap<Int, List<LinkInfo>>()
    private var hitNeedle = ""
    private val hitCache = HashMap<Int, List<FloatArray>>()

    private fun clearCaches() {
        for (v in dlCache.values) try { v.destroy() } catch (_: Exception) { }
        dlCache.clear()
        for (v in stCache.values) try { v.destroy() } catch (_: Exception) { }
        stCache.clear()
        linkCache.clear(); hitCache.clear()
    }

    private fun displayList(p: Int): com.artifex.mupdf.fitz.DisplayList {
        dlCache[p]?.let { return it }
        val page = doc!!.loadPage(p)
        try {
            val dl = page.toDisplayList()
            dlCache[p] = dl
            return dl
        } finally { page.destroy() }
    }

    private fun stext(p: Int): StructuredText {
        stCache[p]?.let { return it }
        val page = doc!!.loadPage(p)
        try {
            val st = page.toStructuredText()
            stCache[p] = st
            return st
        } finally { page.destroy() }
    }

    // ---- open / close ----

    fun open(cb: (OpenResult) -> Unit) {
        async<OpenResult>({
            try {
                val d = source.open()
                doc = d
                if (d.needsPassword()) OpenResult.NeedPassword else { finishOpen(d); OpenResult.Ok }
            } catch (t: Throwable) {
                OpenResult.Error(t.message ?: t.toString())
            }
        }, cb)
    }

    fun authenticate(password: String, cb: (OpenResult) -> Unit) {
        async({
            val d = doc!!
            if (d.authenticatePassword(password)) { finishOpen(d); OpenResult.Ok } else OpenResult.NeedPassword
        }, cb, { cb(OpenResult.Error(it.message ?: "error")) })
    }

    private fun finishOpen(d: Document) {
        reflowable = d.isReflowable
        if (reflowable) d.layout(layoutW, layoutH, em)
        pageCount = d.countPages()
        isPdf = d.isPDF()
    }

    fun close() {
        if (closed) return
        closed = true
        searchGen.incrementAndGet(); trimGen.incrementAndGet()
        try {
            exec.execute {
                try { clearCaches() } catch (_: Throwable) { }
                try { doc?.destroy() } catch (_: Throwable) { }
                doc = null
                source.close()
            }
            exec.shutdown()
        } catch (_: Exception) { }
    }

    /** Drop display lists etc. after the document content changed. */
    fun invalidate() {
        version++
        try { exec.execute { clearCaches() } } catch (_: Exception) { }
    }

    // ---- page geometry ----

    /** Loads the box of every page. Delivers progressively: first (estimated from page 1), then real values. */
    fun loadBoxes(cb: (PageBoxes, Boolean) -> Unit) {
        if (closed) return
        try {
            exec.execute {
                try {
                    val n = pageCount
                    val boxes = PageBoxes(n)
                    var first: FloatArray? = null
                    for (i in 0 until n) {
                        if (closed) return@execute
                        val page = doc!!.loadPage(i)
                        val b = page.getBounds()
                        page.destroy()
                        boxes.raw[i * 4] = b.x0; boxes.raw[i * 4 + 1] = b.y0
                        boxes.raw[i * 4 + 2] = b.x1; boxes.raw[i * 4 + 3] = b.y1
                        if (i == 0) {
                            first = floatArrayOf(b.x0, b.y0, b.x1, b.y1)
                            // estimate for pages not loaded yet
                            for (j in 1 until n) for (k in 0 until 4) boxes.raw[j * 4 + k] = first[k]
                            val snap = boxes.copy()
                            main.post { if (!closed) cb(snap, n == 1) }
                        } else if (i % 400 == 0 && i < n - 1) {
                            val snap = boxes.copy()
                            main.post { if (!closed) cb(snap, false) }
                        }
                    }
                    val snap = boxes.copy()
                    main.post { if (!closed) cb(snap, true) }
                } catch (_: Throwable) { }
            }
        } catch (_: Exception) { }
    }

    /**
     * Finds the bounding box of non-white content for pages (Sumatra's "trim empty margins").
     * Results arrive in batches: pages[i] has box boxes[4*i..4*i+3] (page space), or NaNs if the page is blank.
     */
    fun computeTrim(order: IntArray, batch: (IntArray, FloatArray) -> Unit) {
        if (closed) return
        val gen = trimGen.incrementAndGet()
        try {
            exec.execute {
                var pages = ArrayList<Int>(); var boxes = ArrayList<Float>()
                fun flush() {
                    if (pages.isEmpty()) return
                    val p = pages.toIntArray(); val b = boxes.toFloatArray()
                    pages = ArrayList(); boxes = ArrayList()
                    main.post { if (!closed && trimGen.get() == gen) batch(p, b) }
                }
                var lastFlush = System.currentTimeMillis()
                for (pg in order) {
                    if (closed || trimGen.get() != gen) return@execute
                    val r = try { contentBox(pg) } catch (_: Throwable) { null }
                    pages.add(pg)
                    if (r == null) { for (k in 0 until 4) boxes.add(Float.NaN) } else for (v in r) boxes.add(v)
                    if (pages.size >= 40 || System.currentTimeMillis() - lastFlush > 400) { flush(); lastFlush = System.currentTimeMillis() }
                }
                flush()
            }
        } catch (_: Exception) { }
    }

    fun cancelTrim() { trimGen.incrementAndGet() }

    private fun contentBox(p: Int): FloatArray? {
        val page = doc!!.loadPage(p)
        val b = page.getBounds(); page.destroy()
        val w = b.x1 - b.x0; val h = b.y1 - b.y0
        if (w <= 0f || h <= 0f) return null
        val s = 200f / maxOf(w, h)
        val bw = Math.max(1, Math.ceil((w * s).toDouble()).toInt())
        val bh = Math.max(1, Math.ceil((h * s).toDouble()).toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        try {
            val dev = AndroidDrawDevice(bmp, 0, 0)
            try {
                displayList(p).run(dev, Matrix(s, 0f, 0f, s, -b.x0 * s, -b.y0 * s), Rect(0f, 0f, bw.toFloat(), bh.toFloat()), null)
                dev.close()
            } finally { dev.destroy() }
            val px = IntArray(bw * bh)
            bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
            var minX = bw; var minY = bh; var maxX = -1; var maxY = -1
            for (y in 0 until bh) {
                val row = y * bw
                for (x in 0 until bw) {
                    val c = px[row + x]
                    val r = (c shr 16) and 255; val g = (c shr 8) and 255; val bl = c and 255
                    if (r < 242 || g < 242 || bl < 242) {
                        if (x < minX) minX = x; if (x > maxX) maxX = x
                        if (y < minY) minY = y; if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX < 0) return null
            val pad = 4f // px in the low-res bitmap
            val x0 = b.x0 + Math.max(0f, minX - pad) / s
            val y0 = b.y0 + Math.max(0f, minY - pad) / s
            val x1 = b.x0 + Math.min(bw.toFloat(), maxX + 1 + pad) / s
            val y1 = b.y0 + Math.min(bh.toFloat(), maxY + 1 + pad) / s
            return floatArrayOf(x0, y0, x1, y1)
        } finally { bmp.recycle() }
    }

    // ---- rendering ----

    /**
     * Renders the device-space rectangle [ox, oy, ox+w, oy+h] of a page using matrix (a b c d e f).
     * `needed` is polled just before rendering so stale requests are skipped. Result is null if skipped.
     */
    fun renderTile(page: Int, m: FloatArray, ox: Int, oy: Int, w: Int, h: Int,
                   needed: () -> Boolean, cb: (Bitmap?) -> Unit) {
        async({
            if (!needed()) null else {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val dev = AndroidDrawDevice(bmp, ox, oy)
                try {
                    displayList(page).run(dev, Matrix(m[0], m[1], m[2], m[3], m[4], m[5]),
                        Rect(ox.toFloat(), oy.toFloat(), (ox + w).toFloat(), (oy + h).toFloat()), null)
                    dev.close()
                } finally { dev.destroy() }
                bmp
            }
        }, cb, { cb(null) })
    }

    /** Blocking render for printing. Must not be called from the main or mupdf thread. */
    fun renderBlocking(page: Int, dpi: Float): Bitmap {
        return exec.submit(Callable {
            val d = doc!!
            val pg = d.loadPage(page)
            val b = pg.getBounds(); pg.destroy()
            val s = dpi / 72f
            val w = Math.max(1, Math.round((b.x1 - b.x0) * s)); val h = Math.max(1, Math.round((b.y1 - b.y0) * s))
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val dev = AndroidDrawDevice(bmp, 0, 0)
            try {
                displayList(page).run(dev, Matrix(s, 0f, 0f, s, -b.x0 * s, -b.y0 * s), Rect(0f, 0f, w.toFloat(), h.toFloat()), null)
                dev.close()
            } finally { dev.destroy() }
            bmp
        }).get()
    }

    // ---- text selection ----

    private fun quadsToFloats(q: Array<Quad>?): FloatArray {
        if (q == null) return FloatArray(0)
        val out = FloatArray(q.size * 8)
        for (i in q.indices) {
            val c = q[i]
            out[i * 8] = c.ul_x; out[i * 8 + 1] = c.ul_y; out[i * 8 + 2] = c.ur_x; out[i * 8 + 3] = c.ur_y
            out[i * 8 + 4] = c.ll_x; out[i * 8 + 5] = c.ll_y; out[i * 8 + 6] = c.lr_x; out[i * 8 + 7] = c.lr_y
        }
        return out
    }

    fun selectWord(page: Int, x: Float, y: Float, cb: (Sel?) -> Unit) {
        async({
            val st = stext(page)
            val q = st.snapSelection(Point(x, y), Point(x, y), StructuredText.SELECT_WORDS)
            if (q == null || Math.abs(q.ur_x - q.ul_x) < 0.01f) null else {
                val ax = (q.ul_x + q.ll_x) / 2f + 0.5f; val ay = (q.ul_y + q.ll_y) / 2f
                val bx = (q.ur_x + q.lr_x) / 2f - 0.5f; val by = (q.ur_y + q.lr_y) / 2f
                val a = Point(ax, ay); val b = Point(bx, by)
                Sel(page, ax, ay, bx, by, quadsToFloats(st.highlight(a, b)), st.copy(a, b) ?: "")
            }
        }, cb, { cb(null) })
    }

    fun highlight(page: Int, ax: Float, ay: Float, bx: Float, by: Float, cb: (Sel?) -> Unit) {
        async({
            val st = stext(page)
            val a = Point(ax, ay); val b = Point(bx, by)
            Sel(page, ax, ay, bx, by, quadsToFloats(st.highlight(a, b)), st.copy(a, b) ?: "")
        }, cb, { cb(null) })
    }

    // ---- search ----

    private fun pageHits(p: Int, needle: String): List<FloatArray> {
        if (needle != hitNeedle) { hitCache.clear(); hitNeedle = needle }
        hitCache[p]?.let { return it }
        val page = doc!!.loadPage(p)
        val res = ArrayList<FloatArray>()
        try {
            val hits = page.search(needle)
            if (hits != null) for (h in hits) res.add(quadsToFloats(h))
        } finally { page.destroy() }
        hitCache[p] = res
        return res
    }

    fun hitsForPage(page: Int, needle: String, cb: (List<FloatArray>) -> Unit) {
        async({ pageHits(page, needle) }, cb, { cb(emptyList()) })
    }

    fun cancelSearch() { searchGen.incrementAndGet() }

    /** Finds the next/previous hit after (page, index); index = -1 means "from the start of the page". */
    fun find(needle: String, page: Int, index: Int, forward: Boolean, cb: (SearchResult?) -> Unit) {
        val gen = searchGen.incrementAndGet()
        async({
            val n = pageCount
            var result: SearchResult? = null
            var step = 0
            while (step <= n && result == null) {
                if (closed || searchGen.get() != gen) break
                val raw = if (forward) page + step else page - step
                val p = ((raw % n) + n) % n
                val wrapped = if (forward) raw >= n else raw < 0
                val hits = pageHits(p, needle)
                if (hits.isNotEmpty()) {
                    val idx: Int = if (step == 0) {
                        if (forward) { val i = index + 1; if (i < hits.size) i else -1 }
                        else { val i = (if (index < 0) hits.size else index) - 1; if (i >= 0) i else -1 }
                    } else if (forward) 0 else hits.size - 1
                    if (idx >= 0) result = SearchResult(p, idx, hits, wrapped)
                }
                step++
            }
            if (searchGen.get() == gen) result else null
        }, { if (searchGen.get() == gen) cb(it) }, { cb(null) })
    }

    // ---- links, outline, labels, metadata ----

    fun links(page: Int, cb: (List<LinkInfo>) -> Unit) {
        async({
            linkCache[page] ?: run {
                val pg = doc!!.loadPage(page)
                val out = ArrayList<LinkInfo>()
                try {
                    val ls: Array<Link>? = pg.getLinks()
                    if (ls != null) for (l in ls) {
                        val b = l.getBounds(); val u = l.getURI() ?: continue
                        out.add(LinkInfo(b.x0, b.y0, b.x1, b.y1, u))
                    }
                } finally { pg.destroy() }
                if (linkCache.size > 64) linkCache.clear()
                linkCache[page] = out
                out
            }
        }, cb, { cb(emptyList()) })
    }

    fun resolve(uri: String, cb: (LinkTarget) -> Unit) {
        async({
            val d = doc!!
            if (Link.isExternal(uri)) LinkTarget.External(uri) else {
                val dest = d.resolveLinkDestination(uri)
                if (dest == null) LinkTarget.None else {
                    val pageNo = d.pageNumberFromLocation(dest)
                    val usesY = dest.type == com.artifex.mupdf.fitz.LinkDestination.LINK_DEST_XYZ ||
                        dest.type == com.artifex.mupdf.fitz.LinkDestination.LINK_DEST_FIT_H ||
                        dest.type == com.artifex.mupdf.fitz.LinkDestination.LINK_DEST_FIT_BH
                    LinkTarget.Internal(pageNo, dest.x, dest.y, usesY && dest.hasY())
                }
            }
        }, cb, { cb(LinkTarget.None) })
    }

    fun loadToc(cb: (List<TocItem>) -> Unit) {
        async({
            val d = doc!!
            val out = ArrayList<TocItem>()
            fun walk(items: Array<com.artifex.mupdf.fitz.Outline>?, level: Int) {
                if (items == null) return
                for (o in items) {
                    var page = -1
                    try {
                        val loc = if (o.uri != null) d.resolveLink(o) else null
                        if (loc != null) page = d.pageNumberFromLocation(loc)
                    } catch (_: Throwable) { }
                    val kids = o.down
                    out.add(TocItem((o.title ?: "").trim(), level, o.uri, page, kids != null && kids.isNotEmpty()))
                    walk(kids, level + 1)
                }
            }
            walk(d.loadOutline(), 0)
            out
        }, cb, { cb(emptyList()) })
    }

    /** Page labels ("iv", "A-3"...) for PDFs that define them; null when labels are just 1..N. */
    fun loadLabels(cb: (Array<String>?) -> Unit) {
        async({
            if (!isPdf || pageCount > 4000) null else {
                val d = doc!!
                val labels = Array(pageCount) { "" }
                var custom = false
                for (i in 0 until pageCount) {
                    val pg = d.loadPage(i)
                    val l = try { pg.getLabel() } catch (_: Throwable) { null }
                    pg.destroy()
                    labels[i] = l ?: ""
                    if (!l.isNullOrEmpty() && l != (i + 1).toString()) custom = true
                }
                if (custom) labels else null
            }
        }, cb, { cb(null) })
    }

    fun metadata(cb: (List<Pair<String, String>>) -> Unit) {
        async({
            val d = doc!!
            val keys = listOf(
                "Title" to Document.META_INFO_TITLE, "Author" to Document.META_INFO_AUTHOR,
                "Subject" to Document.META_INFO_SUBJECT, "Keywords" to Document.META_INFO_KEYWORDS,
                "Creator" to Document.META_INFO_CREATOR, "Producer" to Document.META_INFO_PRODUCER,
                "Created" to Document.META_INFO_CREATIONDATE, "Modified" to Document.META_INFO_MODIFICATIONDATE,
                "Format" to Document.META_FORMAT, "Encryption" to Document.META_ENCRYPTION,
            )
            val out = ArrayList<Pair<String, String>>()
            for ((label, key) in keys) {
                val v = try { d.getMetaData(key) } catch (_: Throwable) { null }
                if (!v.isNullOrBlank()) out.add(label to v)
            }
            out
        }, cb, { cb(emptyList()) })
    }

    // ---- reflowable documents ----

    /** Re-layout with a new font size; returns the page number that corresponds to `page` afterwards. */
    fun relayout(page: Int, newEm: Float, cb: (Int) -> Unit) {
        async({
            val d = doc!!
            val mark = d.makeBookmark(d.locationFromPageNumber(page))
            em = newEm
            d.layout(layoutW, layoutH, em)
            pageCount = d.countPages()
            clearCaches(); version++
            d.pageNumberFromLocation(d.findBookmark(mark))
        }, cb, { cb(page) })
    }

    // ---- annotations (PDF only) ----

    /** Adds a highlight/underline/strikeout/squiggly annotation covering the given quads (page space). */
    fun addMarkup(page: Int, type: Int, quads: FloatArray, rgb: FloatArray, cb: (Boolean) -> Unit) {
        async({
            val pg = doc!!.loadPage(page) as? PDFPage ?: return@async false
            try {
                val an = pg.createAnnotation(type)
                an.setColor(rgb)
                val qs = Array(quads.size / 8) { i ->
                    val o = i * 8
                    Quad(quads[o], quads[o + 1], quads[o + 2], quads[o + 3], quads[o + 4], quads[o + 5], quads[o + 6], quads[o + 7])
                }
                an.setQuadPoints(qs)
                an.update()
                pg.update()
            } finally { pg.destroy() }
            dirty = true
            clearCaches(); version++
            true
        }, cb, { cb(false) })
    }

    /** Deletes the first markup annotation under the page-space point. */
    fun deleteAnnotationAt(page: Int, x: Float, y: Float, cb: (Boolean) -> Unit) {
        async({
            val pg = doc!!.loadPage(page) as? PDFPage ?: return@async false
            var removed = false
            try {
                val annots = pg.getAnnotations()
                if (annots != null) for (a in annots) {
                    val t = a.getType()
                    if (t < PDFAnnotation.TYPE_HIGHLIGHT || t > PDFAnnotation.TYPE_STRIKE_OUT) continue
                    if (a.getBounds().contains(x, y)) { pg.deleteAnnotation(a); removed = true; break }
                }
                if (removed) pg.update()
            } finally { pg.destroy() }
            if (removed) { dirty = true; clearCaches(); version++ }
            removed
        }, cb, { cb(false) })
    }

    /** Writes a full copy of the (possibly annotated) PDF to a local file. */
    fun saveCopy(dest: File, cb: (Boolean) -> Unit) {
        async({
            val pdf: PDFDocument? = doc!!.asPDF()
            if (pdf == null) false else { pdf.save(dest.absolutePath, ""); true }
        }, cb, { cb(false) })
    }
}
