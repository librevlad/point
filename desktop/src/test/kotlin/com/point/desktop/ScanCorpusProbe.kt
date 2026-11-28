package com.point.desktop

import com.point.vision.PageVision
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File

/**
 * Зонд «Скана» компьютера на корпусе владельца (#1044, DOC-1): тот же путь, что у
 * `PcPageScanRealizer`, по каждому кадру — что нашлось (сетка строк, четыре угла или кадр
 * целиком), куда легли углы и сколько это заняло. Рядом с готовой страницей кладётся снимок с
 * обведённым листом: судят по нему глазами, сравнивая с тем, что на кадре на самом деле.
 *
 * Не тест и в CI не идёт: кадры владельца в репозиторий не попадают. Запуск:
 *
 *     POINT_SCAN_PROBE=<папка для результата> POINT_CORPUS=<папка корпуса> \
 *       ./gradlew :desktop:test --tests com.point.desktop.ScanCorpusProbe
 *
 * `POINT_SCAN_FRAMES` — номера кадров через запятую, если нужны не все.
 * `POINT_SCAN_MAX_PX` — длинная сторона кадра; телефон 12 Мп снимок уменьшает до 2000, компьютер до 2600.
 */
class ScanCorpusProbe {

    @Test fun `скан по корпусу`() {
        val out = System.getenv("POINT_SCAN_PROBE")?.let(::File)
        assumeTrue("POINT_SCAN_PROBE не задан — зонд не запускается", out != null)
        assumeTrue("OpenCV для этой машины не загрузилась", PcOpenCv.available)
        val corpus = File(System.getenv("POINT_CORPUS") ?: "C:/work/points/point-corpus")
        val only = System.getenv("POINT_SCAN_FRAMES")?.split(',')?.map { it.trim() }?.toSet()
        out!!.mkdirs()

        val report = StringBuilder("кадр\tпуть\tуглы\tразмер\tмс\n")
        val frames = corpus.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }!!
            .filter { only == null || it.nameWithoutExtension in only }
            .sortedBy { it.name }
        for (file in frames) {
            val picture = (System.getenv("POINT_SCAN_MAX_PX")?.toIntOrNull()?.let { uprightPicture(file, it) } ?: uprightPicture(file)) ?: continue
            val started = System.nanoTime()
            var path = "кадр целиком"
            var corners = ""
            val page = onFrame(picture) { rgba, scratch ->
                runBlocking {
                    val found = PageVision.straightened(rgba, scratch)
                    if (found != null) {
                        val q = found.quad
                        path = if (q == null) "расправлен по линиям" else "четыре угла"
                        if (q != null) {
                            corners = listOf(q.topLeft, q.topRight, q.bottomRight, q.bottomLeft)
                                .joinToString(" ") { "${it.x.toInt()},${it.y.toInt()}" }
                        }
                        imageOf(found.mat)
                    } else {
                        imageOf(PageVision.enhanceAsIs(rgba, scratch))
                    }
                }
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            val name = file.nameWithoutExtension
            writeJpeg(page, File(out, "$name.scan.jpg"), 0.85f)
            writeJpeg(outlined(picture, corners), File(out, "$name.where.jpg"), 0.8f)
            report.append("$name\t$path\t$corners\t${page.width}x${page.height}\t$ms\n")
        }
        File(out, "report.tsv").writeText(report.toString())
        println(report)
    }

    /** Кадр, уменьшенный для глаз, с обведённым листом, если углы есть. */
    private fun outlined(picture: BufferedImage, corners: String): BufferedImage {
        val scale = 1000.0 / maxOf(picture.width, picture.height)
        val w = (picture.width * scale).toInt()
        val h = (picture.height * scale).toInt()
        val small = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        small.createGraphics().apply {
            drawImage(picture, 0, 0, w, h, null)
            if (corners.isNotEmpty()) {
                val pts = corners.split(' ').map { p -> p.split(',').map { it.toDouble() * scale } }
                color = Color(255, 0, 160)
                stroke = BasicStroke(5f)
                for (i in pts.indices) {
                    val a = pts[i]
                    val b = pts[(i + 1) % pts.size]
                    drawLine(a[0].toInt(), a[1].toInt(), b[0].toInt(), b[1].toInt())
                }
            }
            dispose()
        }
        return small
    }
}
