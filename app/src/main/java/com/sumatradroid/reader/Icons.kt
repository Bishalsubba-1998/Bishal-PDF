// Tiny vector icons drawn in code (so no drawable assets / icon fonts are required).
package com.sumatradroid.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class IconView(ctx: Context, private val kind: Int) : View(ctx) {
    companion object {
        const val MENU = 0; const val SEARCH = 1; const val MORE = 2; const val PLUS = 3; const val CLOSE = 4
        const val LEFT = 5; const val RIGHT = 6; const val UP = 7; const val DOWN = 8; const val STAR = 9
    }

    var color: Int = Color.BLACK
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    init { isClickable = true }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val u = Math.min(w, h) * 0.22f
        p.color = if (isEnabled) color else (color and 0x00FFFFFF) or 0x55000000
        p.strokeWidth = Math.max(2f, Math.min(w, h) * 0.07f)
        p.style = Paint.Style.STROKE
        path.rewind()
        when (kind) {
            MENU -> for (k in -1..1) c.drawLine(cx - u * 1.1f, cy + k * u, cx + u * 1.1f, cy + k * u, p)
            SEARCH -> {
                c.drawCircle(cx - u * 0.25f, cy - u * 0.25f, u * 0.85f, p)
                c.drawLine(cx + u * 0.4f, cy + u * 0.4f, cx + u * 1.15f, cy + u * 1.15f, p)
            }
            MORE -> {
                p.style = Paint.Style.FILL
                for (k in -1..1) c.drawCircle(cx, cy + k * u * 1.1f, u * 0.2f, p)
            }
            PLUS -> { c.drawLine(cx - u, cy, cx + u, cy, p); c.drawLine(cx, cy - u, cx, cy + u, p) }
            CLOSE -> { c.drawLine(cx - u * 0.8f, cy - u * 0.8f, cx + u * 0.8f, cy + u * 0.8f, p); c.drawLine(cx - u * 0.8f, cy + u * 0.8f, cx + u * 0.8f, cy - u * 0.8f, p) }
            LEFT -> { path.moveTo(cx + u * 0.4f, cy - u); path.lineTo(cx - u * 0.5f, cy); path.lineTo(cx + u * 0.4f, cy + u); c.drawPath(path, p) }
            RIGHT -> { path.moveTo(cx - u * 0.4f, cy - u); path.lineTo(cx + u * 0.5f, cy); path.lineTo(cx - u * 0.4f, cy + u); c.drawPath(path, p) }
            UP -> { path.moveTo(cx - u, cy + u * 0.4f); path.lineTo(cx, cy - u * 0.5f); path.lineTo(cx + u, cy + u * 0.4f); c.drawPath(path, p) }
            DOWN -> { path.moveTo(cx - u, cy - u * 0.4f); path.lineTo(cx, cy + u * 0.5f); path.lineTo(cx + u, cy - u * 0.4f); c.drawPath(path, p) }
            STAR -> {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) u * 1.15f else u * 0.5f
                    val a = Math.PI * i / 5.0 - Math.PI / 2.0
                    val x = cx + (r * Math.cos(a)).toFloat(); val y = cy + (r * Math.sin(a)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close(); c.drawPath(path, p)
            }
        }
    }
}
