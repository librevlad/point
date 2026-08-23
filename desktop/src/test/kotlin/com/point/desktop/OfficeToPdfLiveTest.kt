package com.point.desktop

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Офисный файл превращается в PDF настоящим конвертером (#403) — и проверка эта настоящая
 * (#1248-соседнее, #1251).
 *
 * Раньше исходник брался из `-Dpoint.test.office`, а без него тело теста делало голый
 * `return`: JUnit печатал такой прогон как «пройден», не открыв ни одного файла. Свойство не
 * задаёт ни канонический гейт, ни CI — значит превращение не исполнялось нигде, а зелёный
 * стоял везде.
 *
 * Теперь исходник лежит в дереве, и на машине с PowerPoint или LibreOffice конвертация
 * правда идёт. Где конвертера нет (CI) — прогон честно помечается пропущенным.
 */
class OfficeToPdfLiveTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `офисный файл превращается в PDF на этой машине`() {
        val converter = LocalOfficeToPdf()
        assumeTrue("конвертера на машине нет — превращать нечем", converter.whyUnavailable() == null)

        val source = source()

        val pdf = converter.convert(source)

        assertTrue("PDF не собрался", pdf != null && pdf.isFile)

        assertTrue("PDF пустой", (pdf?.length() ?: 0) > 1000)
    }

    /**
     * Исходник — фикстура из дерева, скопированная во временный каталог: `convert()` кладёт
     * PDF рядом с исходником, и без копии результат оседал бы в `build/resources`. Свой файл
     * по-прежнему можно подставить через `-Dpoint.test.office=…`.
     *
     * Фикстура — презентация: ветка PowerPoint зовёт `Presentations.Open` и документ Word не
     * откроет, а LibreOffice берёт и её.
     */
    private fun source(): File {
        System.getProperty("point.test.office")?.let { own ->
            val chosen = File(own)
            assumeTrue("исходника $own на месте нет", chosen.isFile)
            return chosen
        }
        val bundled = javaClass.getResourceAsStream("/$FIXTURE")
            ?: throw AssertionError("фикстуры $FIXTURE_IN_TREE нет на classpath — превращать нечего")
        return File(tmp.newFolder(), FIXTURE).apply { outputStream().use(bundled::copyTo) }
    }

    private companion object {

        const val FIXTURE = "office-to-pdf.pptx"

        /** Где фикстура лежит в дереве — чтобы сообщение сказало, чего не хватает. */
        const val FIXTURE_IN_TREE = "desktop/src/test/resources/$FIXTURE"
    }
}
