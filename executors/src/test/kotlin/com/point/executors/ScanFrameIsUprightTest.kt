package com.point.executors

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кадр в конвейер скана входит развёрнутым — во всякую его дверь (#1046).
 *
 * Выбеливатель защищает содержимое листа по строкам, а строки ищет горизонтальными прогонами
 * (`MORPH_CLOSE` и `MORPH_OPEN` ядрами 25×1 и 22×1 в `whitenFinish`). У снимка с рук метка
 * камеры обычно стоит «повернуть на 90°», и кадр, взятый «как лежит в файле», приходит листом
 * набок: строки идут поперёк прогонов, защита не собирается вовсе, и полоса вдоль всех
 * четырёх краёв (`BORDER_FRAC`) стирает в белое всё, что в ней лежит, — шапку акта, дату,
 * итог, подписи. Это ровно то, что карточка #1046 требует не съесть.
 *
 * Свой декодер здесь и был корнем: `OpenCvPaperWhitener` считал уменьшение сам и про метку
 * камеры забыл, хотя все остальные двери конвейера берут кадр у [Bitmaps]. Проверка держит
 * не один этот файл, а правило: кто зовёт конвейер, тот берёт кадр у общего декодера.
 *
 * Пикселями это не проверить — конвейер нативный, OpenCV в JVM не поднимается (в
 * `OpenCvScanTest` живут только чистые `orderCorners` и `distance`). Поэтому проверка читает
 * сами файлы модуля, как `PhoneAppsTest` читает свой.
 */
class ScanFrameIsUprightTest {

    private val sources = File("src/main/kotlin")

    /** Двери конвейера: всё, что зовут снаружи самого `OpenCvScan`. */
    private val pipeline = Regex("""OpenCvScan\.(whiten|enhance|enhanceAsIs|process|processAsIs)\(""")

    /** Кадр «как лежит в файле»: декодер, который метку камеры не применяет. */
    private val rawDecode = Regex("""BitmapFactory\.decode""")

    /** Общий декодер, который метку применяет. */
    private val uprightDecode = Regex("""Bitmaps\.(decodeUpright|uprightFrame)\(""")

    private fun callers(): List<File> = sources.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") && it.name != "OpenCvScan.kt" }
        .filter { pipeline.containsMatchIn(it.readText()) }
        .toList()

    @Test
    fun `проверка и правда нашла двери конвейера, а не пустоту`() {
        val names = callers().map { it.name }.sorted()

        assertTrue("дверей конвейера не нашлось — читается не тот каталог: $sources", names.isNotEmpty())
        assertTrue("выбеливатель не среди дверей — проверка смотрит мимо: $names", "OpenCvPaperWhitener.kt" in names)
    }

    @Test
    fun `кадр для конвейера берётся у общего декодера, а не своим`() {
        val guilty = callers()
            .filter { rawDecode.containsMatchIn(it.readText()) || !uprightDecode.containsMatchIn(it.readText()) }
            .map { it.name }
            .sorted()

        assertEquals(
            "кадр в конвейер скана идёт мимо общего декодера: у повёрнутого снимка лист " +
                "ляжет набок, и выбеливание сотрёт строки в полосе вдоль краёв — $guilty",
            emptyList<String>(),
            guilty,
        )
    }
}
