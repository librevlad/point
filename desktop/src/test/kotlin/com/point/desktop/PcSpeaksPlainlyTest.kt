package com.point.desktop

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PcSpeaksPlainlyTest {

    private val sources: List<File>
        get() = File("src/main/kotlin/com/point/desktop")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun `ни один отказ не показывает человеку текст исключения`() {
        val guilty = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->

                val fromException = Regex("""ActionResult\.Failure\(\s*\w+\.message""").containsMatchIn(line)
                if (fromException) "${file.name}:${i + 1}" else null
            }
        }

        assertTrue(
            "отказ собран из исключения — человек прочитает хвост стека: $guilty",
            guilty.isEmpty(),
        )
    }

    @Test fun `отказ не обрывается на констатации — в нём есть, что делать`() {

        val short = sources.flatMap { file ->

            Regex("""ActionResult\.Failure\(\s*"([^"]{4,})"\s*[,)]""").findAll(file.readText())
                .map { it.groupValues[1] }
                .filter { it.first().isUpperCase() }
                .filter { text -> text.length < 25 && !text.contains('—') && !text.contains(',') }
                .map { "${file.name}: «$it»" }
                .toList()
        }

        assertTrue("отказ ничего не советует: $short", short.isEmpty())
    }

    @Test fun `исход печати говорит, что произошло, а не в каком состоянии очередь`() {

        val print = File("src/main/kotlin/com/point/desktop/DesktopActions.kt").readText()

        assertTrue("исход печати снова описывает очередь", "В очереди «" !in print)
        assertTrue("исход печати не назвал, что произошло", "Отправлено на печать" in print)
    }

    @Test fun `предел и факт не совпадают на экране`() {

        val ocr = File("src/main/kotlin/com/point/desktop/OcrActions.kt").readText()

        assertTrue("размер снимка снова округляется до целых", "%.1f" in ocr)
    }
}
