// Main window: tabs, toolbar, find bar, sidebar, page bar, command palette - the Android counterpart
// of SumatraPDF's MainWindow (src/MainWindow.cpp, Tabs.cpp, Toolbar.cpp, Commands.h).
@file:Suppress("DEPRECATION")

package com.sumatradroid.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity(), DocView.Host {

    companion object {
        private const val REQ_OPEN = 1
        private const val REQ_SAVE = 2
    }

    private inner class Tab {
        var source: DocSource? = null
        var doc: MuDoc? = null
        var state: FileState? = null
        var boxes: PageBoxes? = null
        var toc: List<TocItem> = emptyList()
        var labels: Array<String>? = null
        var loading = true
        var uri = ""
        var title = ""
        var needle = ""
        var lastSearch: SearchResult? = null
        var thumbDone = false
    }

    private lateinit var settings: Settings
    private lateinit var history: HistoryStore
    private var theme: Theme = Theme.all[0]
    private val tabs = ArrayList<Tab>()
    private var cur: Tab? = null

    // views
    private lateinit var root: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var tabRow: LinearLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findEdit: EditText
    private lateinit var content: FrameLayout
    private lateinit var docView: DocView
    private lateinit var homeView: HomeView
    private lateinit var loadingText: TextView
    private lateinit var selectionBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var pageText: TextView
    private lateinit var zoomBtn: TextView
    private lateinit var viewBtn: TextView
    private lateinit var sidebar: Sidebar
    private lateinit var sidebarHolder: FrameLayout
    private lateinit var scrim: View
    private val icons = ArrayList<IconView>()
    private val barButtons = ArrayList<TextView>()
    private lateinit var commands: List<Command>

    private var chromeVisible = true
    private var presentation = false
    private var prevMode: DisplayMode? = null
    private var prevZoom = Zoom.FIT_WIDTH
    private var sidebarOpen = false
    private var saveTab: Tab? = null

    private fun dp(v: Float) = Ui.dp(this, v)
    private fun dpi(v: Float) = Ui.dpi(this, v)

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        history = HistoryStore(this)
        theme = Theme.byName(settings.themeName)
        buildUi()
        commands = buildCommands()
        applyTheme(theme, save = false)
        showHome()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        saveState(cur)
        history.save()
    }

    override fun onDestroy() {
        super.onDestroy()
        for (t in tabs) t.doc?.close()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_VIEW && i.data != null) {
            val u = i.data!!
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            openUri(u)
        }
    }

    // =========================================================================================
    // UI construction
    // =========================================================================================

    private fun icon(kind: Int, sizeDp: Float = 40f, onClick: () -> Unit): IconView {
        val v = IconView(this, kind)
        v.layoutParams = LinearLayout.LayoutParams(dpi(sizeDp), dpi(sizeDp))
        v.setOnClickListener { onClick() }
        icons.add(v)
        return v
    }

    private fun barButton(text: String, onClick: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = text; tv.textSize = 14f; tv.gravity = Gravity.CENTER
        tv.setPadding(dpi(10f), dpi(8f), dpi(10f), dpi(8f))
        tv.setOnClickListener { onClick() }
        barButtons.add(tv)
        return tv
    }

    private fun buildUi() {
        root = FrameLayout(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- top bar: sidebar button, tabs, find, menu ----
        topBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        topBar.addView(icon(IconView.MENU) { toggleSidebar(false) })
        tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        tabRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tabScroll.addView(tabRow, FrameLayout.LayoutParams(-2, -1))
        topBar.addView(tabScroll, LinearLayout.LayoutParams(0, dpi(44f), 1f))
        topBar.addView(icon(IconView.SEARCH) { showFind() })
        topBar.addView(icon(IconView.MORE) { Ui.palette(this, theme, commands) })
        column.addView(topBar, LinearLayout.LayoutParams(-1, dpi(44f)))

        // ---- find bar ----
        findBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        findEdit = EditText(this).apply {
            hint = "Find"; setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEARCH; textSize = 15f
            setPadding(dpi(10f), dpi(6f), dpi(10f), dpi(6f))
            setOnEditorActionListener { _, _, _ -> findNext(true); true }
        }
        findBar.addView(findEdit, LinearLayout.LayoutParams(0, dpi(40f), 1f).apply { setMargins(dpi(8f), dpi(2f), dpi(4f), dpi(2f)) })
        findBar.addView(icon(IconView.UP, 38f) { findNext(false) })
        findBar.addView(icon(IconView.DOWN, 38f) { findNext(true) })
        findBar.addView(icon(IconView.CLOSE, 38f) { hideFind() })
        column.addView(findBar, LinearLayout.LayoutParams(-1, -2))

        // ---- content area ----
        content = FrameLayout(this)
        docView = DocView(this).apply { host = this@MainActivity; visibility = View.GONE }
        homeView = HomeView(this).apply {
            thumbFor = { history.thumbFile(it.uri) }
            listener = object : HomeView.Listener {
                override fun onOpenFile() = pickFile()
                override fun onOpenRecent(s: FileState) { openUri(Uri.parse(s.uri)) }
                override fun onRecentMenu(s: FileState) = recentMenu(s)
            }
        }
        loadingText = TextView(this).apply { text = "Opening…"; textSize = 16f; gravity = Gravity.CENTER; visibility = View.GONE }
        selectionBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; elevation = dp(6f) }
        buildSelectionBar()
        content.addView(homeView, FrameLayout.LayoutParams(-1, -1))
        content.addView(docView, FrameLayout.LayoutParams(-1, -1))
        content.addView(loadingText, FrameLayout.LayoutParams(-1, -1))
        content.addView(selectionBar, FrameLayout.LayoutParams(-1, dpi(44f), Gravity.TOP))
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- bottom bar: page navigation, zoom, view ----
        bottomBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        bottomBar.addView(icon(IconView.LEFT) { docView.prevPage() })
        pageText = TextView(this).apply {
            textSize = 15f; gravity = Gravity.CENTER; setPadding(dpi(10f), dpi(8f), dpi(10f), dpi(8f)); minWidth = dpi(70f)
            setOnClickListener { goToPageDialog() }
        }
        bottomBar.addView(pageText)
        bottomBar.addView(icon(IconView.RIGHT) { docView.nextPage() })
        bottomBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        zoomBtn = barButton("Fit Width") { zoomDialog() }
        viewBtn = barButton("View") { Ui.palette(this, theme, commands.filter { it.category == "View" || it.category == "Zoom" }) }
        bottomBar.addView(zoomBtn); bottomBar.addView(viewBtn)
        column.addView(bottomBar, LinearLayout.LayoutParams(-1, dpi(44f)))

        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        // ---- sidebar overlay ----
        sidebarHolder = FrameLayout(this).apply { visibility = View.GONE }
        scrim = View(this).apply { setBackgroundColor(0x66000000); setOnClickListener { closeSidebar() } }
        sidebar = Sidebar(this)
        sidebar.listener = object : Sidebar.Listener {
            override fun onGoToPage(page: Int) { docView.goToPage(page); if (resources.displayMetrics.widthPixels < dpi(700f)) closeSidebar() }
            override fun onFavoriteMenu(f: Favorite) = favoriteMenu(f)
            override fun onClose() = closeSidebar()
        }
        sidebarHolder.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val sw = Math.min(dpi(340f), (resources.displayMetrics.widthPixels * 0.86f).toInt())
        sidebarHolder.addView(sidebar, FrameLayout.LayoutParams(sw, -1, Gravity.START))
        root.addView(sidebarHolder, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
    }

    private fun buildSelectionBar() {
        fun b(text: String, onClick: () -> Unit): TextView = barButton(text, onClick).also { selectionBar.addView(it) }
        b("Copy") { selCopy() }
        b("Highlight") { selMarkup(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_HIGHLIGHT, floatArrayOf(1f, 0.92f, 0.2f)) }
        b("Underline") { selMarkup(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_UNDERLINE, floatArrayOf(0.1f, 0.4f, 0.9f)) }
        b("Strike") { selMarkup(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_STRIKE_OUT, floatArrayOf(0.9f, 0.15f, 0.15f)) }
        b("More…") { selMore() }
        selectionBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        selectionBar.addView(icon(IconView.CLOSE, 40f) { docView.clearSelection() })
    }

    private fun applyTheme(t: Theme, save: Boolean = true) {
        theme = t
        if (save) settings.themeName = t.name
        root.setBackgroundColor(t.background)
        topBar.setBackgroundColor(t.control); bottomBar.setBackgroundColor(t.control)
        findBar.setBackgroundColor(t.control); selectionBar.setBackgroundColor(t.control)
        findEdit.setTextColor(t.text); findEdit.setHintTextColor(t.disabledText)
        findEdit.background = Ui.rounded(t.hot, dp(6f))
        for (i in icons) { i.color = t.text; i.background = Ui.pressable(Color.TRANSPARENT, t.hot, dp(20f)) }
        for (b in barButtons) { b.setTextColor(t.text); b.background = Ui.pressable(Color.TRANSPARENT, t.hot, dp(6f)) }
        pageText.setTextColor(t.text); pageText.background = Ui.pressable(Color.TRANSPARENT, t.hot, dp(6f))
        loadingText.setTextColor(t.darkerText); loadingText.setBackgroundColor(t.background)
        homeView.applyTheme(t); sidebar.applyTheme(t)
        docView.setTheme(t)
        applyDocColors()
        rebuildTabs()
        window.statusBarColor = t.control
        window.navigationBarColor = t.control
        applySystemUi()
    }

    private fun applyDocColors() {
        docView.setColors(settings.invert, settings.grayscale, settings.followTheme, settings.brightness, settings.contrast)
        docView.tapZonesEnabled = settings.tapZones
        docView.spacingDp = settings.pageSpacing.toFloat()
    }

    private fun applySystemUi() {
        var f = 0
        if (!theme.isDark) f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (!chromeVisible) {
            f = f or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        window.decorView.systemUiVisibility = f
    }

    private fun rebuildTabs() {
        tabRow.removeAllViews()
        for (t in tabs) {
            val box = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(10f), 0, dpi(2f), 0) }
            val title = TextView(this).apply {
                text = t.title; maxWidth = dpi(150f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 13f; setTextColor(theme.text)
                if (t === cur) typeface = Typeface.DEFAULT_BOLD
            }
            val close = IconView(this, IconView.CLOSE).apply { color = theme.darkerText }
            close.setOnClickListener { requestClose(t) }
            box.addView(title); box.addView(close, LinearLayout.LayoutParams(dpi(28f), dpi(28f)))
            box.background = Ui.rounded(if (t === cur) theme.activeTab else theme.inactiveTab, dp(6f))
            box.setOnClickListener { select(t) }
            tabRow.addView(box, LinearLayout.LayoutParams(-2, dpi(34f)).apply { setMargins(dpi(3f), 0, dpi(3f), 0) })
        }
        val plus = IconView(this, IconView.PLUS).apply { color = theme.text }
        plus.setOnClickListener { showHome() }
        plus.background = Ui.pressable(if (cur == null) theme.hot else Color.TRANSPARENT, theme.hot, dp(17f))
        tabRow.addView(plus, LinearLayout.LayoutParams(dpi(34f), dpi(34f)).apply { setMargins(dpi(3f), 0, dpi(3f), 0) })
        val idx = tabs.indexOf(cur)
        if (idx >= 0) tabRow.post { tabScroll.smoothScrollTo(tabRow.getChildAt(idx).left - dpi(40f), 0) }
    }

    // =========================================================================================
    // tabs and documents
    // =========================================================================================

    private fun hasDoc(): Boolean = cur?.let { !it.loading && it.doc != null } == true
    private fun isPdfDoc(): Boolean = hasDoc() && cur!!.doc!!.isPdf

    private fun showHome() {
        select(null)
    }

    private fun select(tab: Tab?) {
        if (cur !== tab) saveState(cur)
        cur = tab
        findBar.visibility = View.GONE
        docView.clearSelection()
        if (tab == null) {
            homeView.setItems(history.recent())
            homeView.visibility = View.VISIBLE; docView.visibility = View.GONE
            loadingText.visibility = View.GONE; bottomBar.visibility = View.GONE
            docView.detach()
            sidebar.setToc(emptyList()); sidebar.setFavorites(emptyList(), null)
        } else if (tab.loading || tab.state == null) {
            homeView.visibility = View.GONE; docView.visibility = View.GONE
            loadingText.visibility = View.VISIBLE; loadingText.text = "Opening…\n" + tab.title
            bottomBar.visibility = View.GONE
        } else {
            attachTab(tab)
        }
        rebuildTabs()
        updateKeepScreen()
    }

    private fun attachTab(tab: Tab) {
        val st = tab.state ?: return
        val d = tab.doc ?: return
        homeView.visibility = View.GONE; loadingText.visibility = View.GONE
        docView.visibility = View.VISIBLE
        bottomBar.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        val mode = try { DisplayMode.valueOf(if (st.displayMode.isNotEmpty()) st.displayMode else settings.displayMode) } catch (_: Exception) { DisplayMode.CONTINUOUS }
        val zoom = if (st.zoom.isNaN()) settings.zoom else st.zoom
        val keepPos = settings.rememberState
        docView.attach(d, mode, zoom, st.rotation, if (keepPos) st.page else 0, if (keepPos) st.offsetX else 0f, if (keepPos) st.offsetY else 0f, st.trim)
        docView.labels = tab.labels
        applyDocColors()
        tab.boxes?.let { docView.setBoxes(it) }
        sidebar.setToc(tab.toc); sidebar.setFavorites(st.favorites, tab.labels)
        updatePageText(st.page, tab.state?.pageCount ?: d.pageCount)
        zoomBtn.text = docView.zoomText()
    }

    private fun saveState(tab: Tab?) {
        if (tab == null || tab !== cur) return
        val st = tab.state ?: return
        if (docView.visibility != View.VISIBLE || docView.pageCount == 0) return
        val v = docView.viewState()
        st.page = v.first; st.offsetX = v.second; st.offsetY = v.third
        st.zoom = docView.zoomVirtual; st.displayMode = docView.displayMode.name
        st.rotation = docView.rotation; st.trim = docView.trimMargins
    }

    fun openUri(uri: Uri) {
        val key = uri.toString()
        tabs.firstOrNull { it.uri == key }?.let { select(it); return }
        val tab = Tab()
        tab.uri = key
        tab.title = try { DocSource(this, uri).displayName } catch (_: Exception) { uri.lastPathSegment ?: "document" }
        tabs.add(tab)
        cur.let { saveState(it) }
        cur = tab
        startOpen(tab)
        select(tab)
    }

    private fun startOpen(tab: Tab) {
        tab.loading = true
        val src = DocSource(this, Uri.parse(tab.uri))
        tab.source = src; tab.title = src.displayName
        val d = MuDoc(src)
        tab.doc = d
        val vw = (if (docView.width > 0) docView.width else resources.displayMetrics.widthPixels).toFloat()
        val vh = (if (docView.height > 0) docView.height else resources.displayMetrics.heightPixels).toFloat()
        d.layoutW = 300f; d.layoutH = Math.max(400f, 300f * vh / vw); d.em = settings.ebookFontSize
        d.open { r ->
            when (r) {
                is OpenResult.Ok -> opened(tab)
                is OpenResult.NeedPassword -> askPassword(tab, false)
                is OpenResult.Error -> fail(tab, r.message)
            }
        }
    }

    private fun askPassword(tab: Tab, wrong: Boolean) {
        Ui.password(this, theme, (if (wrong) "Wrong password. " else "") + tab.title, { pw ->
            tab.doc?.authenticate(pw) { r ->
                when (r) {
                    is OpenResult.Ok -> opened(tab)
                    is OpenResult.NeedPassword -> askPassword(tab, true)
                    is OpenResult.Error -> fail(tab, r.message)
                }
            }
        }, { closeTab(tab, force = true) })
    }

    private fun fail(tab: Tab, msg: String) {
        val known = history.get(tab.uri) != null
        closeTab(tab, force = true)
        val b = Ui.dialogBuilder(this, theme).setTitle("Cannot open document")
            .setMessage(tab.title + "\n\n" + msg).setPositiveButton("OK", null)
        if (known) b.setNeutralButton("Remove from list") { _, _ -> history.remove(tab.uri); if (cur == null) homeView.setItems(history.recent()) }
        b.show()
    }

    private fun opened(tab: Tab) {
        val d = tab.doc ?: return
        tab.loading = false
        val st = history.getOrCreate(tab.uri, tab.title)
        st.openCount++; st.lastOpened = System.currentTimeMillis(); st.pageCount = d.pageCount
        tab.state = st
        history.save()
        d.loadBoxes { b, _ ->
            tab.boxes = b
            if (cur === tab) docView.setBoxes(b)
            if (!tab.thumbDone) { tab.thumbDone = true; makeThumb(tab) }
        }
        d.loadToc { toc -> tab.toc = toc; if (cur === tab) sidebar.setToc(toc) }
        d.loadLabels { l ->
            tab.labels = l
            if (cur === tab) { docView.labels = l; sidebar.setFavorites(tab.state?.favorites ?: emptyList(), l); updatePageText(docView.currentPage(), docView.pageCount) }
        }
        if (cur === tab) attachTab(tab)
        rebuildTabs()
        updateKeepScreen()
    }

    private fun makeThumb(tab: Tab) {
        val d = tab.doc ?: return
        val b = tab.boxes ?: return
        if (b.n == 0) return
        val f = history.thumbFile(tab.uri)
        if (f.exists()) return
        val w = b.raw[2] - b.raw[0]; val h = b.raw[3] - b.raw[1]
        if (w <= 0f || h <= 0f) return
        val s = 300f / w
        val bw = Math.ceil((w * s).toDouble()).toInt(); val bh = Math.ceil((h * s).toDouble()).toInt()
        val m = floatArrayOf(s, 0f, 0f, s, -b.raw[0] * s, -b.raw[1] * s)
        d.renderTile(0, m, 0, 0, bw, bh, { true }) { bmp ->
            if (bmp != null) Thread {
                try { FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) } } catch (_: Exception) { }
                runOnUiThread { homeView.invalidateThumb(tab.uri) }
            }.start()
        }
    }

    private fun requestClose(tab: Tab) {
        val d = tab.doc
        if (d != null && d.dirty) {
            Ui.dialogBuilder(this, theme).setTitle("Unsaved annotations")
                .setMessage("“${tab.title}” has annotations that were not saved.")
                .setPositiveButton("Save a copy…") { _, _ -> saveAnnotated(tab) }
                .setNegativeButton("Discard") { _, _ -> closeTab(tab, force = true) }
                .setNeutralButton("Cancel", null).show()
        } else closeTab(tab, force = true)
    }

    private fun closeTab(tab: Tab, force: Boolean) {
        saveState(tab)
        val i = tabs.indexOf(tab)
        tabs.remove(tab)
        tab.doc?.close(); tab.doc = null
        history.save()
        if (cur === tab) {
            cur = null
            val next = if (tabs.isEmpty()) null else tabs[Math.min(i, tabs.size - 1).coerceAtLeast(0)]
            select(next)
        } else rebuildTabs()
    }

    private fun recentMenu(s: FileState) {
        val items = listOf("Open", if (s.pinned) "Unpin" else "Pin to top", "Remove from list")
        Ui.list(this, theme, s.name, items, -1) { w ->
            when (w) {
                0 -> openUri(Uri.parse(s.uri))
                1 -> { s.pinned = !s.pinned; history.save(); homeView.setItems(history.recent()) }
                2 -> { history.remove(s.uri); homeView.setItems(history.recent()) }
            }
        }
    }

    // ---- file picking ----

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/pdf", "application/epub+zip", "application/vnd.ms-xpsdocument", "application/oxps",
                "application/x-cbz", "application/vnd.comicbook+zip", "application/x-mobipocket-ebook",
                "application/x-fictionbook+xml", "application/zip", "application/octet-stream"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(i, REQ_OPEN) } catch (e: Exception) { toast("No file picker available") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_OPEN -> {
                val uris = ArrayList<Uri>()
                data.data?.let { uris.add(it) }
                val cd = data.clipData
                if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { if (!uris.contains(it)) uris.add(it) }
                for (u in uris) {
                    try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                    openUri(u)
                }
            }
            REQ_SAVE -> {
                val out = data.data ?: return
                val tab = saveTab ?: return
                val d = tab.doc ?: return
                val tmp = File(cacheDir, "save-tmp.pdf")
                d.saveCopy(tmp) { ok ->
                    if (!ok) toast("Saving failed") else Thread {
                        val msg = try {
                            contentResolver.openOutputStream(out)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                            "Saved a copy with annotations"
                        } catch (e: Exception) { "Saving failed: " + e.message }
                        tmp.delete()
                        runOnUiThread { toast(msg) }
                    }.start()
                }
            }
        }
    }

    private fun saveAnnotated(tab: Tab? = cur) {
        if (tab == null || tab.doc?.isPdf != true) { toast("Only PDF files can be annotated"); return }
        saveTab = tab
        val base = tab.title.substringBeforeLast('.')
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "$base-annotated.pdf")
        }
        try { startActivityForResult(i, REQ_SAVE) } catch (e: Exception) { toast("No file picker available") }
    }

    // =========================================================================================
    // DocView.Host
    // =========================================================================================

    override fun onPageChanged(page: Int, count: Int) {
        updatePageText(page, count)
        sidebar.setCurrentPage(page)
        cur?.state?.page = page
    }

    private fun updatePageText(page: Int, count: Int) {
        val l = cur?.labels
        val label = if (l != null && page in l.indices && l[page].isNotEmpty()) l[page] else null
        pageText.text = if (label != null && label != (page + 1).toString()) "$label (${page + 1}/$count)" else "${page + 1} / $count"
    }

    override fun onZoomChanged(text: String) { zoomBtn.text = text }

    override fun onTapCanvas() { if (!presentation) setChrome(!chromeVisible) else docView.nextPage() }

    override fun onSelectionChanged(sel: Sel?) {
        selectionBar.visibility = if (sel != null) View.VISIBLE else View.GONE
    }

    override fun onExternalLink(uri: String) {
        Ui.dialogBuilder(this, theme).setTitle("Open link?").setMessage(uri)
            .setPositiveButton("Open") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) } catch (e: Exception) { toast("Cannot open link") }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Copy") { _, _ -> copyText(uri) }.show()
    }

    override fun onNavChanged() {}
    override fun onMessage(msg: String) = toast(msg)
    override fun onAutoScrollEnded() = toast("End of document")

    private fun setChrome(visible: Boolean) {
        chromeVisible = visible
        val v = if (visible) View.VISIBLE else View.GONE
        topBar.visibility = v
        if (!visible) findBar.visibility = View.GONE
        bottomBar.visibility = if (visible && docView.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        applySystemUi()
    }

    private fun updateKeepScreen() {
        if (settings.keepScreenOn && cur != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun copyText(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", s))
    }

    // =========================================================================================
    // selection actions
    // =========================================================================================

    private fun selCopy() {
        val s = docView.selection ?: return
        copyText(s.text); toast("Copied"); docView.clearSelection()
    }

    private fun selMarkup(type: Int, rgb: FloatArray) {
        val s = docView.selection ?: return
        val d = cur?.doc ?: return
        if (!d.isPdf) { toast("Annotations are only supported in PDF files"); return }
        d.addMarkup(s.page, type, s.quads, rgb) { ok ->
            docView.clearSelection()
            if (ok) { docView.contentChanged(); toast("Annotation added. Use “Save annotated copy” to keep it.") } else toast("Could not add annotation")
        }
    }

    private fun selMore() {
        val s = docView.selection ?: return
        val items = listOf("Squiggly underline", "Remove markup under selection", "Search the web", "Share text", "Select all on page")
        Ui.list(this, theme, "Selection", items, -1) { w ->
            when (w) {
                0 -> selMarkup(com.artifex.mupdf.fitz.PDFAnnotation.TYPE_SQUIGGLY, floatArrayOf(0.1f, 0.6f, 0.2f))
                1 -> {
                    val q = s.quads
                    if (q.size >= 8 && cur?.doc?.isPdf == true) {
                        val x = (q[0] + q[2] + q[4] + q[6]) / 4f; val y = (q[1] + q[3] + q[5] + q[7]) / 4f
                        cur?.doc?.deleteAnnotationAt(s.page, x, y) { ok ->
                            docView.clearSelection()
                            if (ok) { docView.contentChanged(); toast("Markup removed") } else toast("No markup found")
                        }
                    } else toast("Nothing to remove")
                }
                2 -> try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(s.text))))
                } catch (e: Exception) { toast("No browser available") }
                3 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, s.text) }, "Share"))
                4 -> docView.selectAllOnPage()
            }
        }
    }

    // =========================================================================================
    // find
    // =========================================================================================

    private fun showFind() {
        if (!hasDoc()) return
        if (!chromeVisible) setChrome(true)
        findBar.visibility = View.VISIBLE
        findEdit.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findEdit, 0)
        val s = docView.selection
        if (s != null && s.text.isNotBlank() && s.text.length < 80) findEdit.setText(s.text.trim())
        findEdit.selectAll()
    }

    private fun hideFind() {
        findBar.visibility = View.GONE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(findEdit.windowToken, 0)
        docView.clearSearch()
        cur?.let { it.lastSearch = null; it.needle = ""; it.doc?.cancelSearch() }
        docView.requestFocus()
    }

    private fun findNext(forward: Boolean) {
        val tab = cur ?: return
        val d = tab.doc ?: return
        val text = findEdit.text.toString().trim()
        if (text.isEmpty()) { if (findBar.visibility != View.VISIBLE) showFind(); return }
        if (text != tab.needle) { tab.needle = text; docView.setSearchNeedle(text); tab.lastSearch = null }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(findEdit.windowToken, 0)
        val ls = tab.lastSearch
        val startPage = ls?.page ?: docView.currentPage()
        val idx = ls?.index ?: -1
        d.find(text, startPage, idx, forward) { r ->
            if (r == null) toast("No matches for “$text”") else {
                tab.lastSearch = r
                if (r.wrapped) toast("Search wrapped around")
                docView.showSearchResult(r)
            }
        }
    }

    // =========================================================================================
    // dialogs
    // =========================================================================================

    private fun goToPageDialog() {
        if (!hasDoc()) return
        val t = cur!!
        Ui.input(this, theme, "Go to page", "1 - ${docView.pageCount}", (docView.currentPage() + 1).toString(), false) { s ->
            var p = s.toIntOrNull()?.minus(1)
            if (p == null) { val l = t.labels; if (l != null) { val i = l.indexOfFirst { it.equals(s, true) }; if (i >= 0) p = i } }
            if (p != null && p in 0 until docView.pageCount) docView.goToPage(p) else toast("Invalid page")
        }
    }

    private fun zoomDialog() {
        if (!hasDoc()) return
        val levels = floatArrayOf(25f, 50f, 75f, 100f, 125f, 150f, 200f, 300f, 400f, 800f)
        val items = ArrayList<String>(); items.add("Fit Page"); items.add("Fit Width")
        for (l in levels) items.add(Zoom.toString(l) + if (l == 100f) " (actual size)" else "")
        items.add("Custom…")
        val checked = when (docView.zoomVirtual) { Zoom.FIT_PAGE -> 0; Zoom.FIT_WIDTH -> 1; else -> levels.indexOfFirst { it == docView.zoomVirtual }.let { if (it >= 0) it + 2 else -1 } }
        Ui.list(this, theme, "Zoom", items, checked) { w ->
            when {
                w == 0 -> docView.setZoom(Zoom.FIT_PAGE)
                w == 1 -> docView.setZoom(Zoom.FIT_WIDTH)
                w == items.size - 1 -> customZoom()
                else -> docView.setZoom(levels[w - 2])
            }
        }
    }

    private fun customZoom() {
        Ui.input(this, theme, "Custom zoom (%)", "8.33 - 6400", Math.round(docView.currentPercent()).toString(), true) { s ->
            val z = s.toFloatOrNull()
            if (z == null || z < Zoom.MIN || z > Zoom.MAX) toast("Enter a value between 8.33 and 6400") else docView.setZoom(z)
        }
    }

    private fun themeDialog() {
        val dlg = Ui.dialogBuilder(this, theme).setTitle("Theme").create()
        val list = android.widget.ListView(this)
        list.adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = Theme.all.size
            override fun getItem(p: Int): Any = Theme.all[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, c: View?, parent: ViewGroup): View {
                val t = Theme.all[p]
                val row = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f)) }
                for (col in intArrayOf(t.background, t.control, t.text, t.link)) {
                    row.addView(View(this@MainActivity).apply { background = Ui.rounded(col, dp(4f), 0x55808080, 1) },
                        LinearLayout.LayoutParams(dpi(18f), dpi(18f)).apply { rightMargin = dpi(4f) })
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = (if (t.name == theme.name) "✓ " else "") + t.name; textSize = 16f
                    setPadding(dpi(10f), 0, 0, 0)
                    setTextColor(if (theme.isDark) Color.WHITE else Color.BLACK)
                })
                return row
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> dlg.dismiss(); applyTheme(Theme.all[pos]) }
        dlg.setView(list)
        dlg.show()
    }

    private fun optionsDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(16f), dpi(8f), dpi(16f), dpi(8f)) }
        val textColor = if (theme.isDark) Color.WHITE else Color.BLACK
        fun check(label: String, value: Boolean, change: (Boolean) -> Unit) {
            val cb = CheckBox(this); cb.text = label; cb.isChecked = value; cb.setTextColor(textColor)
            cb.setOnCheckedChangeListener { _, v -> change(v) }
            box.addView(cb)
        }
        fun button(label: String, onClick: () -> Unit) {
            val b = TextView(this); b.text = label; b.setTextColor(textColor); b.textSize = 16f
            b.setPadding(dpi(4f), dpi(12f), dpi(4f), dpi(12f)); b.setOnClickListener { onClick() }
            box.addView(b)
        }
        button("Theme: ${theme.name}") { themeDialog() }
        button("Default display mode: ${modeName(DisplayMode.valueOf(settings.displayMode))}") {
            val modes = listOf(DisplayMode.CONTINUOUS, DisplayMode.SINGLE, DisplayMode.CONTINUOUS_FACING, DisplayMode.FACING, DisplayMode.CONTINUOUS_BOOK, DisplayMode.BOOK)
            Ui.list(this, theme, "Default display mode", modes.map { modeName(it) }, modes.indexOf(DisplayMode.valueOf(settings.displayMode))) { settings.displayMode = modes[it].name }
        }
        button("Default zoom: ${Zoom.toString(settings.zoom)}") {
            val zs = floatArrayOf(Zoom.FIT_PAGE, Zoom.FIT_WIDTH, 100f, 125f, 150f, 200f)
            Ui.list(this, theme, "Default zoom", zs.map { Zoom.toString(it) }, zs.indexOf(settings.zoom)) { settings.zoom = zs[it] }
        }
        button("Page spacing: ${settings.pageSpacing} dp") {
            Ui.slider(this, theme, "Page spacing", 0, 24, settings.pageSpacing, { "$it dp" }, { settings.pageSpacing = it; applyDocColors() })
        }
        button("E-book font size: ${settings.ebookFontSize.toInt()} pt") { ebookFontDialog() }
        check("Keep screen on while reading", settings.keepScreenOn) { settings.keepScreenOn = it; updateKeepScreen() }
        check("Volume keys turn pages", settings.volumeKeys) { settings.volumeKeys = it }
        check("Tap screen edges to turn pages (single page views)", settings.tapZones) { settings.tapZones = it; applyDocColors() }
        check("Document colors follow theme", settings.followTheme) { settings.followTheme = it; applyDocColors() }
        check("Remember reading position and view of each file", settings.rememberState) { settings.rememberState = it }
        val sv = ScrollView(this); sv.addView(box)
        Ui.dialogBuilder(this, theme).setTitle("Options").setView(sv).setPositiveButton("Close", null).show()
    }

    private fun ebookFontDialog() {
        val t = cur
        Ui.input(this, theme, "E-book font size (pt)", "8 - 30", settings.ebookFontSize.toInt().toString(), true) { s ->
            val v = s.toFloatOrNull() ?: return@input
            if (v < 6f || v > 40f) { toast("Choose a size between 6 and 40"); return@input }
            settings.ebookFontSize = v
            val tab = t ?: return@input
            val d = tab.doc ?: return@input
            if (d.reflowable) {
                d.relayout(docView.currentPage(), v) { newPage ->
                    docView.setSearchNeedle("")
                    d.loadBoxes { b, _ -> tab.boxes = b; if (cur === tab) { docView.setBoxes(b); docView.goToPage(newPage, 0f, false) } }
                    d.loadToc { toc -> tab.toc = toc; if (cur === tab) sidebar.setToc(toc) }
                }
            }
        }
    }

    private fun propertiesDialog() {
        val tab = cur ?: return
        val d = tab.doc ?: return
        d.metadata { list ->
            val sb = StringBuilder()
            sb.append("File: ").append(tab.title).append('\n')
            sb.append("Pages: ").append(d.pageCount).append('\n')
            for ((k, v) in list) sb.append(k).append(": ").append(v).append('\n')
            Ui.message(this, theme, "Properties", sb.toString().trim())
        }
    }

    private fun brightnessDialog() {
        Ui.slider(this, theme, "Brightness", -100, 100, settings.brightness, { it.toString() }, { settings.brightness = it; applyDocColors() }) { settings.brightness = 0; applyDocColors(); 0 }
    }

    private fun contrastDialog() {
        Ui.slider(this, theme, "Contrast", -100, 100, settings.contrast, { it.toString() }, { settings.contrast = it; applyDocColors() }) { settings.contrast = 0; applyDocColors(); 0 }
    }

    private fun shortcutsDialog() {
        val txt = """
            Touch
              Tap            follow link / show or hide bars
              Double tap     zoom in / back to fit width
              Pinch          zoom
              Long press     select text (drag the handles)
              Swipe edge     turn page (single page views)

            Keyboard (Sumatra shortcuts)
              Ctrl+O  open        Ctrl+W  close tab
              Ctrl+F  find        F3 / Shift+F3  next / previous
              Ctrl+G  go to page  Ctrl+K  command palette
              Ctrl+0  fit page    Ctrl+1  actual size    Ctrl+2  fit width
              Ctrl++ / Ctrl+-  zoom in / out
              Ctrl+6 single page, Ctrl+7 facing, Ctrl+8 book view
              Ctrl+Shift++ / -  rotate right / left
              Ctrl+Tab  next tab  Ctrl+P  print
              F11 fullscreen      F5  presentation
              Space / Shift+Space  scroll by a screen
              Alt+Left / Alt+Right  navigate back / forward
        """.trimIndent()
        Ui.message(this, theme, "Shortcuts", txt)
    }

    private fun aboutDialog() {
        Ui.message(this, theme, "About SumatraDroid",
            "A SumatraPDF-style reader for Android.\n\n" +
                "Built from a study of the SumatraPDF source code (GPLv3): display modes, zoom levels, themes, " +
                "tabs, favorites, table of contents, find, navigation history and keyboard shortcuts. " +
                "Documents are rendered with MuPDF, the same engine SumatraPDF uses (AGPLv3).\n\n" +
                "This app is licensed under the GNU AGPL v3 and is not affiliated with the SumatraPDF project.")
    }

    private fun favoriteMenu(f: Favorite) {
        Ui.list(this, theme, f.name, listOf("Go to page", "Rename", "Delete"), -1) { w ->
            val st = cur?.state ?: return@list
            when (w) {
                0 -> docView.goToPage(f.page)
                1 -> Ui.input(this, theme, "Rename favorite", "Name", f.name, false) { s -> if (s.isNotEmpty()) { f.name = s; refreshFavorites() } }
                2 -> { st.favorites.remove(f); refreshFavorites() }
            }
        }
    }

    private fun refreshFavorites() {
        val tab = cur ?: return
        sidebar.setFavorites(tab.state?.favorites ?: emptyList(), tab.labels)
        history.save()
    }

    private fun toggleFavorite() {
        val tab = cur ?: return
        val st = tab.state ?: return
        val p = docView.currentPage()
        val existing = st.favorites.firstOrNull { it.page == p }
        if (existing != null) { st.favorites.remove(existing); toast("Removed from favorites"); refreshFavorites(); return }
        val l = tab.labels
        val label = if (l != null && p in l.indices && l[p].isNotEmpty()) l[p] else (p + 1).toString()
        Ui.input(this, theme, "Add to favorites", "Name", "Page $label", false) { s ->
            st.favorites.add(Favorite(p, if (s.isEmpty()) "Page $label" else s))
            refreshFavorites(); toast("Added to favorites")
        }
    }

    private fun gotoFavorite(next: Boolean) {
        val st = cur?.state ?: return
        val p = docView.currentPage()
        val sorted = st.favorites.sortedBy { it.page }
        val f = if (next) sorted.firstOrNull { it.page > p } ?: sorted.firstOrNull() else sorted.lastOrNull { it.page < p } ?: sorted.lastOrNull()
        if (f == null) toast("No favorites") else docView.goToPage(f.page)
    }

    // ---- sidebar ----

    private fun toggleSidebar(favorites: Boolean) {
        if (sidebarOpen && sidebar.isShowingFavorites == favorites) { closeSidebar(); return }
        if (!hasDoc()) return
        sidebar.show(favorites)
        sidebarHolder.visibility = View.VISIBLE
        sidebar.translationX = -sidebar.width.toFloat().coerceAtLeast(dpi(300f).toFloat())
        scrim.alpha = 0f
        sidebar.animate().translationX(0f).setDuration(160).start()
        scrim.animate().alpha(1f).setDuration(160).start()
        sidebarOpen = true
    }

    private fun closeSidebar() {
        if (!sidebarOpen) return
        sidebarOpen = false
        sidebar.animate().translationX(-sidebar.width.toFloat()).setDuration(140).withEndAction { sidebarHolder.visibility = View.GONE }.start()
        scrim.animate().alpha(0f).setDuration(140).start()
    }

    // ---- presentation / fullscreen ----

    private fun togglePresentation() {
        if (!hasDoc()) return
        if (!presentation) {
            presentation = true
            prevMode = docView.displayMode; prevZoom = docView.zoomVirtual
            docView.setDisplayMode(DisplayMode.SINGLE); docView.setZoom(Zoom.FIT_PAGE)
            docView.setPresentation(true)
            setChrome(false)
        } else {
            presentation = false
            docView.setPresentation(false)
            prevMode?.let { docView.setDisplayMode(it) }; docView.setZoom(prevZoom)
            setChrome(true)
        }
    }

    // =========================================================================================
    // commands (the palette / menu)
    // =========================================================================================

    private fun modeName(m: DisplayMode) = when (m) {
        DisplayMode.SINGLE -> "Single page"
        DisplayMode.FACING -> "Facing pages"
        DisplayMode.BOOK -> "Book view"
        DisplayMode.CONTINUOUS -> "Continuous (single)"
        DisplayMode.CONTINUOUS_FACING -> "Continuous facing"
        DisplayMode.CONTINUOUS_BOOK -> "Continuous book view"
    }

    private fun buildCommands(): List<Command> {
        val doc = { hasDoc() }
        val l = ArrayList<Command>()
        fun add(cat: String, title: String, shortcut: String = "", enabled: () -> Boolean = doc, checked: (() -> Boolean)? = null, run: () -> Unit) {
            l.add(Command(title, cat, title, shortcut, enabled, checked, run))
        }
        val always = { true }

        // File
        add("File", "Open file…", "Ctrl+O", always) { pickFile() }
        add("File", "Recent files / new tab", "", always) { showHome() }
        add("File", "Reload document", "R") { cur?.let { t -> saveState(t); t.doc?.close(); t.doc = null; t.loading = true; t.boxes = null; select(t); startOpen(t) } }
        add("File", "Close tab", "Ctrl+W") { cur?.let { requestClose(it) } }
        add("File", "Close other tabs", "") { val keep = cur; for (t in tabs.toList()) if (t !== keep) closeTab(t, true) }
        add("File", "Close all tabs", "", { tabs.isNotEmpty() }) { for (t in tabs.toList()) closeTab(t, true) }
        add("File", "Save annotated copy…", "", { isPdfDoc() }) { saveAnnotated() }
        add("File", "Share file", "") {
            val t = cur ?: return@add
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = t.source?.mime ?: "application/pdf"; putExtra(Intent.EXTRA_STREAM, Uri.parse(t.uri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share"))
            } catch (e: Exception) { toast("Cannot share this file") }
        }
        add("File", "Print…", "Ctrl+P") {
            val t = cur ?: return@add; val d = t.doc ?: return@add
            (getSystemService(Context.PRINT_SERVICE) as PrintManager).print(t.title, DocPrintAdapter(this, d, t.title), null)
        }
        add("File", "Document properties", "Ctrl+D") { propertiesDialog() }
        add("File", "Copy file name", "") { cur?.let { copyText(it.title); toast("Copied") } }
        add("File", "Clear recent files", "", always) { history.clear(); if (cur == null) homeView.setItems(history.recent()); toast("History cleared") }

        // View
        fun cols() = docView.displayMode.columns
        add("View", "Single page", "Ctrl+6", checked = { cols() == 1 }) { docView.setDisplayMode(docView.displayMode.withColumns(1, false)) }
        add("View", "Facing pages", "Ctrl+7", checked = { cols() == 2 && !docView.displayMode.bookCover }) { docView.setDisplayMode(docView.displayMode.withColumns(2, false)) }
        add("View", "Book view", "Ctrl+8", checked = { docView.displayMode.bookCover }) { docView.setDisplayMode(docView.displayMode.withColumns(2, true)) }
        add("View", "Continuous scrolling", "C", checked = { docView.displayMode.continuous }) { docView.setDisplayMode(docView.displayMode.withContinuous(!docView.displayMode.continuous)) }
        add("View", "Book view: show cover page alone", "", { hasDoc() && docView.displayMode.bookCover }, { docView.coverPage }) { docView.coverPage = !docView.coverPage }
        add("View", "Rotate right", "Ctrl+Shift++") { docView.rotate(+1) }
        add("View", "Rotate left", "Ctrl+Shift+-") { docView.rotate(-1) }
        add("View", "Trim empty margins", "", checked = { docView.trimMargins }) { docView.setTrim(!docView.trimMargins) }
        add("View", "Fit content (trim margins + fit width)", "") { docView.setTrim(true); docView.setZoom(Zoom.FIT_WIDTH) }
        add("View", "Invert colors", "", checked = { settings.invert }) { settings.invert = !settings.invert; applyDocColors() }
        add("View", "Grayscale", "", checked = { settings.grayscale }) { settings.grayscale = !settings.grayscale; applyDocColors() }
        add("View", "Document colors follow theme", "", checked = { settings.followTheme }) { settings.followTheme = !settings.followTheme; applyDocColors() }
        add("View", "Brightness…", "") { brightnessDialog() }
        add("View", "Contrast…", "") { contrastDialog() }
        add("View", "Show links", "", checked = { docView.showLinks }) { docView.showLinks = !docView.showLinks }
        add("View", "Fullscreen", "F11", checked = { !chromeVisible && !presentation }) { setChrome(!chromeVisible) }
        add("View", "Presentation mode", "F5", checked = { presentation }) { togglePresentation() }
        add("View", "Start auto-scroll", "", { hasDoc() && docView.autoScrollSpeed == 0f }) { docView.setAutoScroll(40f); toast("Auto-scroll on. Tap to hide bars.") }
        add("View", "Auto-scroll faster", "", { hasDoc() && docView.autoScrollSpeed > 0f }) { docView.setAutoScroll(Math.min(400f, docView.autoScrollSpeed * 1.5f)) }
        add("View", "Auto-scroll slower", "", { hasDoc() && docView.autoScrollSpeed > 0f }) { docView.setAutoScroll(Math.max(8f, docView.autoScrollSpeed / 1.5f)) }
        add("View", "Stop auto-scroll", "", { hasDoc() && docView.autoScrollSpeed > 0f }) { docView.setAutoScroll(0f) }

        // Zoom
        add("Zoom", "Fit page", "Ctrl+0", checked = { docView.zoomVirtual == Zoom.FIT_PAGE }) { docView.setZoom(Zoom.FIT_PAGE) }
        add("Zoom", "Fit width", "Ctrl+2", checked = { docView.zoomVirtual == Zoom.FIT_WIDTH }) { docView.setZoom(Zoom.FIT_WIDTH) }
        add("Zoom", "Actual size (100%)", "Ctrl+1") { docView.setZoom(Zoom.ACTUAL) }
        add("Zoom", "Zoom in", "Ctrl++") { docView.zoomIn() }
        add("Zoom", "Zoom out", "Ctrl+-") { docView.zoomOut() }
        add("Zoom", "Custom zoom…", "Ctrl+Y") { customZoom() }
        add("Zoom", "Choose zoom level…", "") { zoomDialog() }

        // Go to
        add("Go to", "Next page", "N") { docView.nextPage() }
        add("Go to", "Previous page", "P") { docView.prevPage() }
        add("Go to", "First page", "Home") { docView.firstPage() }
        add("Go to", "Last page", "End") { docView.lastPage() }
        add("Go to", "Go to page…", "Ctrl+G") { goToPageDialog() }
        add("Go to", "Navigate back", "Alt+Left", { hasDoc() && docView.canNavBack }) { docView.navBack() }
        add("Go to", "Navigate forward", "Alt+Right", { hasDoc() && docView.canNavForward }) { docView.navForward() }
        add("Go to", "Next tab", "Ctrl+Tab", { tabs.size > 1 }) { cycleTab(+1) }
        add("Go to", "Previous tab", "Ctrl+Shift+Tab", { tabs.size > 1 }) { cycleTab(-1) }

        // Find
        add("Find", "Find…", "Ctrl+F") { showFind() }
        add("Find", "Find next", "F3") { findNext(true) }
        add("Find", "Find previous", "Shift+F3") { findNext(false) }
        add("Find", "Select all text on page", "Ctrl+A") { docView.selectAllOnPage() }

        // Sidebar & favorites
        add("Sidebar", "Table of contents", "F12", checked = { sidebarOpen && !sidebar.isShowingFavorites }) { toggleSidebar(false) }
        add("Sidebar", "Favorites", "", checked = { sidebarOpen && sidebar.isShowingFavorites }) { toggleSidebar(true) }
        add("Sidebar", "Expand all contents", "", { hasDoc() && sidebar.hasToc() }) { sidebar.expandAll(true) }
        add("Sidebar", "Collapse all contents", "", { hasDoc() && sidebar.hasToc() }) { sidebar.expandAll(false) }
        add("Favorites", "Add / remove this page as favorite", "Ctrl+B") { toggleFavorite() }
        add("Favorites", "Next favorite", "") { gotoFavorite(true) }
        add("Favorites", "Previous favorite", "") { gotoFavorite(false) }

        // Settings
        add("Settings", "Change theme…", "", always) { themeDialog() }
        add("Settings", "Toggle light / dark theme", "", always) {
            val next = if (theme.isDark) Theme.byName("Light") else Theme.byName("Dark"); applyTheme(next)
        }
        add("Settings", "E-book font size…", "", { hasDoc() && cur!!.doc!!.reflowable }) { ebookFontDialog() }
        add("Settings", "Options…", "", always) { optionsDialog() }

        // Help
        add("Help", "Keyboard shortcuts", "", always) { shortcutsDialog() }
        add("Help", "About", "", always) { aboutDialog() }
        return l
    }

    private fun cycleTab(delta: Int) {
        if (tabs.isEmpty()) return
        val i = tabs.indexOf(cur)
        val n = if (i < 0) 0 else ((i + delta) % tabs.size + tabs.size) % tabs.size
        select(tabs[n])
    }

    // =========================================================================================
    // keys
    // =========================================================================================

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (settings.volumeKeys && hasDoc() && docView.visibility == View.VISIBLE &&
            (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) { if (code == KeyEvent.KEYCODE_VOLUME_UP) docView.prevPage() else docView.nextPage() }
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN) {
            val ctrl = e.isCtrlPressed; val shift = e.isShiftPressed; val alt = e.isAltPressed
            if (ctrl) {
                when (code) {
                    KeyEvent.KEYCODE_O -> { pickFile(); return true }
                    KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_F4 -> { cur?.let { requestClose(it) }; return true }
                    KeyEvent.KEYCODE_F -> { showFind(); return true }
                    KeyEvent.KEYCODE_G -> { goToPageDialog(); return true }
                    KeyEvent.KEYCODE_K -> { Ui.palette(this, theme, commands); return true }
                    KeyEvent.KEYCODE_P -> { commands.firstOrNull { it.title == "Print…" }?.run?.invoke(); return true }
                    KeyEvent.KEYCODE_D -> { propertiesDialog(); return true }
                    KeyEvent.KEYCODE_B -> { toggleFavorite(); return true }
                    KeyEvent.KEYCODE_A -> { docView.selectAllOnPage(); return true }
                    KeyEvent.KEYCODE_Y -> { customZoom(); return true }
                    KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> { docView.setZoom(Zoom.FIT_PAGE); return true }
                    KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> { docView.setZoom(Zoom.ACTUAL); return true }
                    KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> { docView.setZoom(Zoom.FIT_WIDTH); return true }
                    KeyEvent.KEYCODE_6 -> { docView.setDisplayMode(docView.displayMode.withColumns(1, false)); return true }
                    KeyEvent.KEYCODE_7 -> { docView.setDisplayMode(docView.displayMode.withColumns(2, false)); return true }
                    KeyEvent.KEYCODE_8 -> { docView.setDisplayMode(docView.displayMode.withColumns(2, true)); return true }
                    KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> { if (shift) docView.rotate(+1) else docView.zoomIn(); return true }
                    KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { if (shift) docView.rotate(-1) else docView.zoomOut(); return true }
                    KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_PAGE_DOWN -> { cycleTab(if (shift) -1 else +1); return true }
                    KeyEvent.KEYCODE_PAGE_UP -> { cycleTab(-1); return true }
                }
            }
            if (alt && code == KeyEvent.KEYCODE_DPAD_LEFT) { docView.navBack(); return true }
            if (alt && code == KeyEvent.KEYCODE_DPAD_RIGHT) { docView.navForward(); return true }
            when (code) {
                KeyEvent.KEYCODE_F3 -> { findNext(!shift); return true }
                KeyEvent.KEYCODE_F11 -> { setChrome(!chromeVisible); return true }
                KeyEvent.KEYCODE_F5 -> { togglePresentation(); return true }
                KeyEvent.KEYCODE_F12 -> { toggleSidebar(false); return true }
                KeyEvent.KEYCODE_ESCAPE -> { onBackPressed(); return true }
            }
            val focus = currentFocus
            if (focus !is EditText && hasDoc() && docView.visibility == View.VISIBLE) {
                when (code) {
                    KeyEvent.KEYCODE_N -> { docView.nextPage(); return true }
                    KeyEvent.KEYCODE_P -> { docView.prevPage(); return true }
                    KeyEvent.KEYCODE_C -> { docView.setDisplayMode(docView.displayMode.withContinuous(!docView.displayMode.continuous)); return true }
                    KeyEvent.KEYCODE_R -> { commands.firstOrNull { it.title == "Reload document" }?.run?.invoke(); return true }
                    KeyEvent.KEYCODE_G -> { goToPageDialog(); return true }
                    KeyEvent.KEYCODE_SLASH -> { showFind(); return true }
                }
                if (docView.handleKey(code, e)) return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onBackPressed() {
        when {
            sidebarOpen -> closeSidebar()
            docView.selection != null -> docView.clearSelection()
            findBar.visibility == View.VISIBLE -> hideFind()
            presentation -> togglePresentation()
            !chromeVisible -> setChrome(true)
            else -> super.onBackPressed()
        }
    }
}
