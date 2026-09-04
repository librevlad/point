package com.point.desktop

import com.point.core.flow.COPY_LIFETIME_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Временные файлы исполнителей ПК убираются при старте по сроку (#1435, решение владельца
 * 04.09.2026, вариант A). Живая охота нашла в системном temp больше гигабайта таких файлов
 * за неделю: `deleteOnExit` у резидентного Point не срабатывает, они копятся невидимо.
 */
class StaleTempFilesTest {

    @get:Rule val temp = TemporaryFolder()

    private val сутки = COPY_LIFETIME_MS

    @Test fun `брошенные свои файлы старше срока исчезают, свежие и чужие остаются`() {
        val tmp = temp.newFolder("systmp")
        val давно = System.currentTimeMillis() - 11 * сутки

        val ocr = File(tmp, "pc-ocr-123.txt").apply { writeText("текст"); setLastModified(давно) }
        val small = File(tmp, "pc-small-9.jpg").apply { writeText("кадр"); setLastModified(давно) }
        val topdf = File(tmp, "point-topdf-1.ps1").apply { writeText("скрипт"); setLastModified(давно) }
        val shared = File(tmp, "point-shared-77").apply { mkdirs(); File(this, "o.bin").writeText("o"); setLastModified(давно) }
        val pages = File(tmp, "pages-7").apply { mkdirs(); File(this, "page-0001.png").writeText("стр"); setLastModified(давно) }

        val fresh = File(tmp, "pc-ocr-fresh.txt").apply { writeText("свежий") }
        val foreign = File(tmp, "someone-else.tmp").apply { writeText("чужой"); setLastModified(давно) }

        forgetStaleTempFiles(tmp, System.currentTimeMillis() - сутки)

        assertFalse("OCR-файл остался", ocr.exists())
        assertFalse("уменьшенная картинка осталась", small.exists())
        assertFalse("скрипт топдф остался", topdf.exists())
        assertFalse("папка принятого осталась", shared.exists())
        assertFalse("папка страниц осталась", pages.exists())

        assertTrue("свежий temp убрали — а операция могла ещё идти", fresh.exists())
        assertTrue("Point удалил чужой файл в системном temp", foreign.exists())
    }
}
