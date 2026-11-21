// Printing through Android's print framework: pages are rendered with MuPDF into a PrintedPdfDocument.
package com.sumatradroid.reader

import android.content.Context
import android.graphics.RectF
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import java.io.FileOutputStream

class DocPrintAdapter(private val ctx: Context, private val doc: MuDoc, private val name: String) : PrintDocumentAdapter() {
    private var attrs: PrintAttributes? = null

    override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal?,
                          cb: LayoutResultCallback, extras: Bundle?) {
        attrs = new
        if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(doc.pageCount).build()
        cb.onLayoutFinished(info, true)
    }

    private fun wanted(i: Int, ranges: Array<out PageRange>): Boolean {
        for (r in ranges) if (i >= r.start && i <= r.end) return true
        return false
    }

    override fun onWrite(pages: Array<out PageRange>, dest: ParcelFileDescriptor, cancel: CancellationSignal?,
                         cb: WriteResultCallback) {
        val a = attrs ?: run { cb.onWriteFailed("no print attributes"); return }
        Thread {
            val pdf = PrintedPdfDocument(ctx, a)
            try {
                var n = 0
                for (i in 0 until doc.pageCount) {
                    if (!wanted(i, pages)) continue
                    if (cancel?.isCanceled == true) { pdf.close(); cb.onWriteCancelled(); return@Thread }
                    val page = pdf.startPage(n++)
                    val bmp = doc.renderBlocking(i, 150f)
                    val c = page.canvas
                    val pw = c.width.toFloat(); val ph = c.height.toFloat()
                    val s = Math.min(pw / bmp.width, ph / bmp.height)
                    val dw = bmp.width * s; val dh = bmp.height * s
                    c.drawBitmap(bmp, null, RectF((pw - dw) / 2f, (ph - dh) / 2f, (pw + dw) / 2f, (ph + dh) / 2f), null)
                    bmp.recycle()
                    pdf.finishPage(page)
                }
                FileOutputStream(dest.fileDescriptor).use { pdf.writeTo(it) }
                pdf.close()
                cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Throwable) {
                try { pdf.close() } catch (_: Throwable) { }
                cb.onWriteFailed(e.message)
            }
        }.start()
    }
}
