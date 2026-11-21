// Start page: grid of recently opened documents with thumbnails (SumatraPDF's "Frequently Read" start page).
package com.sumatradroid.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class HomeView(ctx: Context) : LinearLayout(ctx) {

    interface Listener {
        fun onOpenFile()
        fun onOpenRecent(s: FileState)
        fun onRecentMenu(s: FileState)
    }

    var listener: Listener? = null
    var thumbFor: (FileState) -> File = { File("") }
    private var theme: Theme = Theme.all[0]
    private var items: List<FileState> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val title = TextView(ctx)
    private val openBtn = TextView(ctx)
    private val hint = TextView(ctx)
    private val grid = GridView(ctx)

    private fun dpi(v: Float) = Ui.dpi(context, v)

    init {
        orientation = VERTICAL
        val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(6f)) }
        title.text = "Recent files"; title.textSize = 20f; title.typeface = Typeface.DEFAULT_BOLD
        header.addView(title, LayoutParams(0, -2, 1f))
        openBtn.text = "Open file…"; openBtn.textSize = 15f; openBtn.gravity = Gravity.CENTER
        openBtn.setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f))
        openBtn.setOnClickListener { listener?.onOpenFile() }
        header.addView(openBtn, LayoutParams(-2, -2))
        addView(header, LayoutParams(-1, -2))

        hint.textSize = 14f; hint.setPadding(dpi(16f), dpi(8f), dpi(16f), dpi(8f))
        hint.text = "Open a PDF, EPUB, XPS, CBZ, MOBI or FB2 file. Files you open appear here."
        addView(hint, LayoutParams(-1, -2))

        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = dpi(150f)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.horizontalSpacing = dpi(12f); grid.verticalSpacing = dpi(14f)
        grid.setPadding(dpi(16f), dpi(8f), dpi(16f), dpi(16f)); grid.clipToPadding = false
        grid.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        addView(grid, LayoutParams(-1, 0, 1f))
    }

    fun applyTheme(t: Theme) {
        theme = t
        setBackgroundColor(t.background)
        title.setTextColor(t.text); hint.setTextColor(t.darkerText)
        openBtn.setTextColor(t.text)
        openBtn.background = Ui.pressable(t.control, t.hot, Ui.dp(context, 6f))
        adapter.notifyDataSetChanged()
    }

    fun setItems(list: List<FileState>) {
        items = list
        hint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int): Any = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val cell = (convert as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = VERTICAL
                addView(ImageView(context).apply { id = 1; scaleType = ImageView.ScaleType.FIT_CENTER },
                    LayoutParams(-1, dpi(190f)))
                addView(TextView(context).apply { id = 2; textSize = 13f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dpi(6f), 0, 0) })
                addView(TextView(context).apply { id = 3; textSize = 11f })
            }
            val s = items[p]
            val img = cell.findViewById<ImageView>(1); val name = cell.findViewById<TextView>(2); val info = cell.findViewById<TextView>(3)
            name.text = (if (s.pinned) "📌 " else "") + s.name.ifEmpty { "document" }
            name.setTextColor(theme.text)
            info.text = if (s.pageCount > 0) "Page ${s.page + 1} of ${s.pageCount}" else ""
            info.setTextColor(theme.darkerText)
            img.setBackgroundColor(0xFFFFFFFF.toInt())
            img.setImageBitmap(null)
            img.tag = s.uri
            val cached = cache.get(s.uri)
            if (cached != null) img.setImageBitmap(cached) else {
                val f = thumbFor(s)
                if (f.exists()) Thread {
                    val b = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
                    if (b != null) main.post { cache.put(s.uri, b); if (img.tag == s.uri) img.setImageBitmap(b) }
                }.start()
            }
            cell.setOnClickListener { listener?.onOpenRecent(s) }
            cell.setOnLongClickListener { listener?.onRecentMenu(s); true }
            return cell
        }
    }

    fun invalidateThumb(uri: String) { cache.remove(uri) }

    init {
        grid.adapter = adapter
        applyTheme(theme)
    }
}
