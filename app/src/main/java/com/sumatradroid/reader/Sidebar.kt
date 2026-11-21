// Sidebar with "Contents" (table of contents tree) and "Favorites" - like SumatraPDF's sidebar.
package com.sumatradroid.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class Sidebar(ctx: Context) : LinearLayout(ctx) {

    interface Listener {
        fun onGoToPage(page: Int)
        fun onFavoriteMenu(f: Favorite)
        fun onClose()
    }

    var listener: Listener? = null
    private var theme: Theme = Theme.all[0]
    private val tabToc = TextView(ctx)
    private val tabFav = TextView(ctx)
    private val closeBtn = TextView(ctx)
    private val list = ListView(ctx)
    private val empty = TextView(ctx)

    private var toc: List<TocItem> = emptyList()
    private var expanded = BooleanArray(0)
    private var visibleIdx: List<Int> = emptyList()
    private var favs: List<Favorite> = emptyList()
    private var labels: Array<String>? = null
    private var currentPage = 0
    private var showFavorites = false

    private fun dp(v: Float) = Ui.dp(context, v)
    private fun dpi(v: Float) = Ui.dpi(context, v)

    init {
        orientation = VERTICAL
        val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        for ((tv, label) in listOf(tabToc to "Contents", tabFav to "Favorites")) {
            tv.text = label; tv.textSize = 14f; tv.gravity = Gravity.CENTER
            tv.setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f))
            header.addView(tv, LayoutParams(0, -2, 1f))
        }
        closeBtn.text = "✕"; closeBtn.textSize = 16f; closeBtn.gravity = Gravity.CENTER
        closeBtn.setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f))
        closeBtn.setOnClickListener { listener?.onClose() }
        header.addView(closeBtn, LayoutParams(-2, -2))
        tabToc.setOnClickListener { show(false) }
        tabFav.setOnClickListener { show(true) }
        addView(header, LayoutParams(-1, -2))

        list.divider = null
        list.setOnItemClickListener { _, _, pos, _ -> onRowClick(pos) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            if (showFavorites && pos < favs.size) { listener?.onFavoriteMenu(favs[pos]); true } else false
        }
        empty.gravity = Gravity.CENTER; empty.setPadding(dpi(20f), dpi(30f), dpi(20f), 0); empty.textSize = 14f
        addView(empty, LayoutParams(-1, -2))
        addView(list, LayoutParams(-1, 0, 1f))
    }

    fun applyTheme(t: Theme) {
        theme = t
        setBackgroundColor(t.control)
        closeBtn.setTextColor(t.text)
        styleTabs()
        empty.setTextColor(t.darkerText)
        adapter.notifyDataSetChanged()
    }

    private fun styleTabs() {
        tabToc.setTextColor(if (!showFavorites) theme.text else theme.darkerText)
        tabFav.setTextColor(if (showFavorites) theme.text else theme.darkerText)
        tabToc.setBackgroundColor(if (!showFavorites) theme.activeTab else Color.TRANSPARENT)
        tabFav.setBackgroundColor(if (showFavorites) theme.activeTab else Color.TRANSPARENT)
        tabToc.typeface = if (!showFavorites) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tabFav.typeface = if (showFavorites) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun show(favorites: Boolean) {
        showFavorites = favorites
        styleTabs(); refreshEmpty(); adapter.notifyDataSetChanged()
    }

    val isShowingFavorites get() = showFavorites

    fun setToc(items: List<TocItem>) {
        toc = items
        expanded = BooleanArray(items.size) { items.size <= 80 }
        rebuildVisible(); refreshEmpty(); adapter.notifyDataSetChanged()
    }

    fun setFavorites(f: List<Favorite>, labelsArr: Array<String>?) {
        favs = f.sortedBy { it.page }; labels = labelsArr
        refreshEmpty(); adapter.notifyDataSetChanged()
    }

    fun setCurrentPage(p: Int) {
        if (p == currentPage) return
        currentPage = p
        if (!showFavorites) adapter.notifyDataSetChanged()
    }

    fun hasToc() = toc.isNotEmpty()

    fun expandAll(open: Boolean) {
        for (i in expanded.indices) expanded[i] = open
        rebuildVisible(); adapter.notifyDataSetChanged()
    }

    private fun refreshEmpty() {
        val isEmpty = if (showFavorites) favs.isEmpty() else toc.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        empty.text = if (showFavorites) "No favorites yet.\nUse “Add to favorites” to bookmark pages." else "This document has no table of contents."
    }

    private fun rebuildVisible() {
        val out = ArrayList<Int>()
        var hideBelow = Int.MAX_VALUE
        for (i in toc.indices) {
            val it = toc[i]
            if (it.level > hideBelow) continue
            hideBelow = Int.MAX_VALUE
            out.add(i)
            if (it.hasChildren && !expanded[i]) hideBelow = it.level
        }
        visibleIdx = out
    }

    private fun pageLabel(p: Int): String {
        val l = labels
        return if (l != null && p in l.indices && l[p].isNotEmpty()) l[p] else (p + 1).toString()
    }

    private fun currentTocIndex(): Int {
        var best = -1
        for (i in toc.indices) if (toc[i].page in 0..currentPage) best = i
        return best
    }

    private fun onRowClick(pos: Int) {
        if (showFavorites) { if (pos < favs.size) listener?.onGoToPage(favs[pos].page); return }
        if (pos >= visibleIdx.size) return
        val it = toc[visibleIdx[pos]]
        if (it.page >= 0) listener?.onGoToPage(it.page)
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = if (showFavorites) favs.size else visibleIdx.size
        override fun getItem(p: Int): Any = p
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply { id = 1; textSize = 16f; gravity = Gravity.CENTER }, LayoutParams(dpi(32f), -2))
                addView(TextView(context).apply { id = 2; textSize = 14f; maxLines = 3 }, LayoutParams(0, -2, 1f))
                addView(TextView(context).apply { id = 3; textSize = 12f; setPadding(dpi(8f), 0, dpi(12f), 0) })
            }
            val arrow = row.findViewById<TextView>(1); val title = row.findViewById<TextView>(2); val pg = row.findViewById<TextView>(3)
            if (showFavorites) {
                val f = favs[p]
                arrow.text = "★"; arrow.setTextColor(theme.link); arrow.setOnClickListener(null); arrow.isClickable = false
                title.text = f.name; title.setTextColor(theme.text); title.typeface = Typeface.DEFAULT
                pg.text = pageLabel(f.page); pg.setTextColor(theme.darkerText)
                row.setPadding(dpi(6f), dpi(10f), 0, dpi(10f))
                row.setBackgroundColor(Color.TRANSPARENT)
            } else {
                val idx = visibleIdx[p]; val it = toc[idx]
                row.setPadding(dpi(6f + it.level * 14f), dpi(9f), 0, dpi(9f))
                if (it.hasChildren) {
                    arrow.text = if (expanded[idx]) "▾" else "▸"; arrow.setTextColor(theme.darkerText)
                    arrow.setOnClickListener { expanded[idx] = !expanded[idx]; rebuildVisible(); notifyDataSetChanged() }
                } else { arrow.text = ""; arrow.setOnClickListener(null); arrow.isClickable = false }
                title.text = if (it.title.isEmpty()) "(untitled)" else it.title
                title.setTextColor(theme.text)
                val isCur = idx == currentTocIndex()
                title.typeface = if (isCur) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                pg.text = if (it.page >= 0) pageLabel(it.page) else ""
                pg.setTextColor(theme.darkerText)
                row.setBackgroundColor(if (isCur) theme.hot else Color.TRANSPARENT)
            }
            return row
        }
    }

    init {
        list.adapter = adapter
        applyTheme(theme)
    }
}
