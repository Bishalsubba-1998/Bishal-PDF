// Small UI helpers (everything is built in code: no layout XML, no AndroidX).
package com.sumatradroid.reader

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView

class Command(
    val id: String,
    val category: String,
    val title: String,
    val shortcut: String = "",
    val enabled: () -> Boolean = { true },
    val checked: (() -> Boolean)? = null,
    val run: () -> Unit,
)

object Ui {
    fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density
    fun dpi(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun pressable(normal: Int, pressed: Int, radius: Float = 0f): Drawable {
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(pressed); cornerRadius = radius })
        sl.addState(intArrayOf(), GradientDrawable().apply { setColor(normal); cornerRadius = radius })
        return sl
    }

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = radius
            if (strokeW > 0) setStroke(strokeW, stroke)
        }

    fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    fun dialogBuilder(ctx: Context, t: Theme): AlertDialog.Builder =
        AlertDialog.Builder(ctx, if (t.isDark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)

    fun message(ctx: Context, t: Theme, title: String, msg: String) {
        dialogBuilder(ctx, t).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    fun input(ctx: Context, t: Theme, title: String, hint: String, initial: String, numeric: Boolean, ok: (String) -> Unit) {
        val et = EditText(ctx)
        et.hint = hint; et.setText(initial); et.setSelectAllOnFocus(true); et.setSingleLine(true)
        et.inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
        et.imeOptions = EditorInfo.IME_ACTION_DONE
        val box = LinearLayout(ctx).apply { setPadding(dpi(ctx, 20f), dpi(ctx, 8f), dpi(ctx, 20f), 0); addView(et, LinearLayout.LayoutParams(-1, -2)) }
        val dlg = dialogBuilder(ctx, t).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> ok(et.text.toString().trim()) }
            .setNegativeButton("Cancel", null).create()
        et.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { dlg.dismiss(); ok(et.text.toString().trim()); true } else false
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
    }

    fun password(ctx: Context, t: Theme, name: String, ok: (String) -> Unit, cancel: () -> Unit) {
        val et = EditText(ctx)
        et.hint = "Password"; et.setSingleLine(true)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val box = LinearLayout(ctx).apply { setPadding(dpi(ctx, 20f), dpi(ctx, 8f), dpi(ctx, 20f), 0); addView(et, LinearLayout.LayoutParams(-1, -2)) }
        dialogBuilder(ctx, t).setTitle("Password required").setMessage(name).setView(box)
            .setPositiveButton("OK") { _, _ -> ok(et.text.toString()) }
            .setNegativeButton("Cancel") { _, _ -> cancel() }
            .setOnCancelListener { cancel() }.show()
    }

    fun list(ctx: Context, t: Theme, title: String, items: List<String>, checked: Int, pick: (Int) -> Unit) {
        val labels = items.mapIndexed { i, s -> if (i == checked) "✓  $s" else "     $s" }.toTypedArray()
        dialogBuilder(ctx, t).setTitle(title).setItems(labels) { _, which -> pick(which) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Dialog with a titled slider, used for brightness/contrast, font size, spacing. */
    fun slider(ctx: Context, t: Theme, title: String, min: Int, max: Int, value: Int, fmt: (Int) -> String,
               live: (Int) -> Unit, reset: (() -> Int)? = null) {
        val label = TextView(ctx).apply { text = fmt(value); setTextColor(t.text); textSize = 16f; gravity = Gravity.CENTER }
        val sb = SeekBar(ctx).apply { this.max = max - min; progress = value - min }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { label.text = fmt(p + min); live(p + min) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(ctx, 20f), dpi(ctx, 12f), dpi(ctx, 20f), 0)
            addView(label, LinearLayout.LayoutParams(-1, -2)); addView(sb, LinearLayout.LayoutParams(-1, -2))
        }
        val b = dialogBuilder(ctx, t).setTitle(title).setView(box).setPositiveButton("Close", null)
        if (reset != null) b.setNeutralButton("Reset") { _, _ -> reset() }
        b.show()
    }

    // ---- command palette (Sumatra's Ctrl+K) ----

    private class Row(val header: String?, val cmd: Command?)

    fun palette(ctx: Context, t: Theme, commands: List<Command>, startFilter: String = "") {
        val dlg = Dialog(ctx, if (t.isDark) android.R.style.Theme_Material_Dialog else android.R.style.Theme_Material_Light_Dialog)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(t.control) }
        val et = EditText(ctx).apply {
            hint = "Type a command"; setHintTextColor(t.disabledText); setTextColor(t.text)
            setSingleLine(true); setText(startFilter); setPadding(dpi(ctx, 16f), dpi(ctx, 12f), dpi(ctx, 16f), dpi(ctx, 12f))
            background = rounded(t.hot, dp(ctx, 6f))
        }
        val lv = ListView(ctx).apply { divider = null; setBackgroundColor(t.control) }
        root.addView(et, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dpi(ctx, 8f), dpi(ctx, 8f), dpi(ctx, 8f), dpi(ctx, 4f)) })
        root.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))

        var rows: List<Row> = emptyList()
        fun rebuild(filter: String) {
            val avail = commands.filter { it.enabled() }
            val toks = filter.lowercase().split(' ').filter { it.isNotEmpty() }
            val res = ArrayList<Row>()
            if (toks.isEmpty()) {
                var cat = ""
                for (c in avail) { if (c.category != cat) { cat = c.category; res.add(Row(cat, null)) }; res.add(Row(null, c)) }
            } else {
                for (c in avail) {
                    val hay = (c.category + " " + c.title).lowercase()
                    if (toks.all { hay.contains(it) }) res.add(Row(null, c))
                }
            }
            rows = res
        }
        rebuild(startFilter)

        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun isEnabled(p: Int) = rows[p].cmd != null
            override fun getViewTypeCount() = 2
            override fun getItemViewType(p: Int) = if (rows[p].cmd == null) 0 else 1
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val r = rows[p]
                if (r.cmd == null) {
                    val tv = (convert as? TextView) ?: TextView(ctx)
                    tv.text = r.header!!.uppercase(); tv.setTextColor(t.link); tv.textSize = 12f; tv.typeface = Typeface.DEFAULT_BOLD
                    tv.setPadding(dpi(ctx, 16f), dpi(ctx, 14f), dpi(ctx, 16f), dpi(ctx, 4f))
                    return tv
                }
                val row = (convert as? LinearLayout) ?: LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpi(ctx, 16f), dpi(ctx, 12f), dpi(ctx, 16f), dpi(ctx, 12f))
                    addView(TextView(ctx).apply { id = 1; textSize = 16f }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(ctx).apply { id = 2; textSize = 12f })
                }
                val c = r.cmd
                val title = row.findViewById<TextView>(1); val sc = row.findViewById<TextView>(2)
                val mark = c.checked?.let { if (it()) "✓  " else "     " } ?: ""
                title.text = mark + c.title; title.setTextColor(t.text)
                sc.text = c.shortcut; sc.setTextColor(t.darkerText)
                row.background = pressable(Color.TRANSPARENT, t.hot)
                return row
            }
        }
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ ->
            val c = rows[pos].cmd ?: return@setOnItemClickListener
            dlg.dismiss(); c.run()
        }
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { rebuild(s?.toString() ?: ""); adapter.notifyDataSetChanged() }
        })
        et.setOnEditorActionListener { _, _, _ ->
            val first = rows.firstOrNull { it.cmd != null }?.cmd
            if (first != null) { dlg.dismiss(); first.run() }
            true
        }
        dlg.setContentView(root)
        dlg.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.85f).toInt())
            setGravity(Gravity.TOP)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dlg.show()
        if (startFilter.isNotEmpty()) et.setSelection(startFilter.length)
    }
}
