package com.point

import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.ComponentActivity

/**
 * DEBUG-проба принтера (#251): печатает одну страницу через системный `PrintManager`.
 *
 * Нужна потому, что печать иначе нечем проверить с машины: в образе эмулятора нет ни одного
 * приложения, умеющего печатать, а без живого задания «Point как принтер» пришлось бы сдавать
 * непроверенным. В release её нет.
 */
class PrintProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(PrintManager::class.java)
        manager.print("Проба Point", OnePageAdapter(), null)
    }

    private class OnePageAdapter : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            val info = PrintDocumentInfo.Builder("проба.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            val document = PdfDocument()
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            page.canvas.drawText("Проба печати Point", 60f, 120f, android.graphics.Paint().apply { textSize = 24f })
            document.finishPage(page)
            ParcelFileDescriptor.AutoCloseOutputStream(destination).use { document.writeTo(it) }
            document.close()
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        }
    }
}
