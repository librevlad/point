package com.point.print

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import androidx.core.content.FileProvider
import com.point.ShareActivity
import com.point.source.Produced
import java.io.File
import java.io.FileInputStream

/**
 * Point как принтер (#251).
 *
 * Самый широкий вход из возможных: печатать умеет почти всё, включая приложения без нормального
 * экспорта — банк, госуслуги, почтовые клиенты. Задание приходит готовым PDF, и он становится
 * объектом Point.
 *
 * Службу печати человек включает один раз руками (Настройки → Печать → Point) — сама она не
 * появится, и это честная цена такого входа.
 *
 * Печать здесь не «выполняется»: Point ничего не печатает и никуда не отправляет, он забирает
 * документ. Поэтому задание сразу завершается — иначе оно висело бы в очереди вечно.
 */
class PointPrintService : PrintService() {

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession =
        object : PrinterDiscoverySession() {

            override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
                val id = generatePrinterId(PRINTER_ID)
                val capabilities = PrinterCapabilitiesInfo.Builder(id)
                    .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                    .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                    .addResolution(
                        PrintAttributes.Resolution("default", "300 dpi", 300, 300),
                        true,
                    )
                    .setColorModes(
                        PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME,
                        PrintAttributes.COLOR_MODE_COLOR,
                    )
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build()

                val printer = PrinterInfo.Builder(id, "Point", PrinterInfo.STATUS_IDLE)
                    .setCapabilities(capabilities)
                    .build()

                addPrinters(listOf(printer))
            }

            override fun onStopPrinterDiscovery() = Unit
            override fun onValidatePrinters(printerIds: MutableList<PrinterId>) = Unit
            override fun onStartPrinterStateTracking(printerId: PrinterId) = Unit
            override fun onStopPrinterStateTracking(printerId: PrinterId) = Unit
            override fun onDestroy() = Unit
        }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        printJob.start()
        val produced = runCatching { save(printJob) }.getOrNull()
        if (produced == null) {
            // «Не смогли забрать документ» — задание обязано завершиться отказом, а не остаться в
            // очереди навсегда: невидимая ошибка хуже видимой (#358).
            printJob.fail("Point не смог забрать документ")
            return
        }
        printJob.complete()
        Handler(Looper.getMainLooper()).post { open(produced) }
    }

    /** Забрать PDF задания в свой кэш. Scratch не годится: он стирается по концу чужой работы. */
    private fun save(printJob: PrintJob): Produced? {
        val descriptor = printJob.document.data ?: return null
        val dir = File(cacheDir, "print").apply { mkdirs() }
        val file = File(dir, printedFileName(printJob.info.label))
        descriptor.use { fd ->
            FileInputStream(fd.fileDescriptor).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return printedToProduced(file.absolutePath, file.length())
    }

    private fun open(produced: Produced) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(produced.uri))
        startActivity(
            Intent(this, ShareActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType(produced.mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private companion object { const val PRINTER_ID = "point-printer" }
}
