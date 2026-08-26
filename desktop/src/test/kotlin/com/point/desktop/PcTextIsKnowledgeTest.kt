package com.point.desktop

import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_READ_TEXT
import com.point.core.flow.OoxmlOfficeTextExtractor
import com.point.core.flow.PcResultFields
import com.point.core.flow.RelayRpc
import com.point.core.flow.capabilities.OfficeCapability
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Компьютерная половина «Извлечь текст» (#995, #997 — строка матрицы DSK-040).
 *
 * Владелец проверил это на ПК 17.08.2026: `smeta.xlsx` (современный OOXML, строки внутри
 * листа) получала «В этом документе текста не нашлось — старые .doc и .xls компьютер не
 * открывает», а три нажатия подряд не меняли знание объекта. Тестов у этой половины не было
 * ни одного, хотя правка числит компьютер исправленным.
 *
 * И второе: результат обязан доехать до того, кто просил. Компьютер отвечал телефону
 * `ocr.text.ref` — путём по своему диску, который на телефоне не значит ничего.
 */
class PcTextIsKnowledgeTest {

    @get:Rule val temp = TemporaryFolder()

    private fun xlsx(name: String, vararg sheets: String): File {
        val file = temp.newFile(name)
        ZipOutputStream(file.outputStream()).use { zos ->
            sheets.forEachIndexed { index, sheetXml ->
                zos.putNextEntry(ZipEntry("xl/worksheets/sheet${index + 1}.xml"))
                zos.write(sheetXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return file
    }

    /**
     * Книга, у которой порядок вкладок разошёлся с номерами файлов внутри архива: первая на
     * экране вкладка лежит в `sheet2.xml`. Так выглядит любая книга, где листы переставляли
     * или удаляли: номер в имени файла помнит, каким лист создавали, а не каким его видят.
     */
    private fun xlsxWithTabs(name: String, vararg tabs: String): File {
        val file = temp.newFile(name)
        ZipOutputStream(file.outputStream()).use { zos ->
            tabs.forEachIndexed { index, sheetXml ->
                zos.putNextEntry(ZipEntry("xl/worksheets/sheet${tabs.size - index}.xml"))
                zos.write(sheetXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("xl/workbook.xml"))
            zos.write(
                tabs.indices.joinToString("", "<workbook><sheets>", "</sheets></workbook>") {
                    """<sheet name="Лист${it + 1}" sheetId="${it + 1}" r:id="rId${it + 1}"/>"""
                }.toByteArray(Charsets.UTF_8),
            )
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zos.write(
                tabs.indices.joinToString("", "<Relationships>", "</Relationships>") {
                    """<Relationship Id="rId${it + 1}" Target="worksheets/sheet${tabs.size - it}.xml"/>"""
                }.toByteArray(Charsets.UTF_8),
            )
            zos.closeEntry()
        }
        return file
    }

    private fun officeObject(file: File) = PointObject(
        id = "xlsx",
        mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        uri = ScratchRef(file.absolutePath),
        state = ObjectState(ObjectKind.OFFICE),
        metadata = mapOf("name" to file.name),
    )

    private fun realizer() = PcOfficeTextRealizer(OoxmlOfficeTextExtractor())

    private fun knownText(result: ActionResult): String {
        assertTrue("ожидалось знание документу, вышло: $result", result is ActionResult.Done)
        val found = (result as ActionResult.Done).findings
        assertTrue("документ не получил признака «текст есть»", Feature.HAS_TEXT in found!!.features)
        return File(found.metadata[META_OCR_TEXT_REF]!!).readText()
    }

    /** Смета владельца: общего словаря строк нет вовсе, строки лежат внутри листа. */
    private val smeta = """
        <worksheet><sheetData>
        <row r="1"><c r="A1" t="inlineStr"><is><t>Работа</t></is></c><c r="B1"><v>12</v></c>
        <c r="C1"><v>250</v></c><c r="D1"><v>3000</v></c></row>
        <row r="2"><c r="A2" t="inlineStr"><is><t>Итого</t></is></c><c r="D2"><v>3480</v></c></row>
        </sheetData></worksheet>
    """.trimIndent()

    private val itogo = """
        <worksheet><sheetData>
        <row r="1"><c r="A1" t="inlineStr"><is><t>Подпись директора</t></is></c></row>
        </sheetData></worksheet>
    """.trimIndent()

    @Test
    fun `смета читается компьютером и остаётся знанием самого документа`() = runTest {
        val result = realizer().perform(officeObject(xlsx("смета.xlsx", smeta)), null)

        val known = knownText(result)
        assertTrue(known, known.contains("Работа"))
        assertTrue("числа таблицы потеряны: $known", known.contains("3480"))
    }

    @Test
    fun `у книги из двух листов компьютер читает оба`() = runTest {
        val result = realizer().perform(officeObject(xlsx("смета.xlsx", smeta, itogo)), null)

        val known = knownText(result)
        assertTrue("первый лист потерян: $known", known.contains("Работа"))
        assertTrue("второй лист книги потерян: $known", known.contains("Подпись директора"))
    }

    /**
     * Знание документа не живёт в системной временной папке (#995).
     *
     * `ocr.text.ref` — постоянная ссылка знания объекта, а не временное тело результата:
     * уборка `%TEMP%` молча убила бы прочитанное, и телефон, попросивший компьютер прочитать
     * документ, получил бы ответ без текста — мёртвая ссылка в дорогу не едет.
     */
    @Test
    fun `прочитанное компьютером лежит рядом с документом, а не в системном временном`() = runTest {
        val file = xlsx("смета.xlsx", smeta)

        val result = realizer().perform(officeObject(file), null)

        val ref = File((result as ActionResult.Done).findings!!.metadata[META_OCR_TEXT_REF]!!)
        assertEquals(
            "знание объекта легло не рядом с документом, а в чужую папку: $ref",
            file.parentFile,
            ref.parentFile,
        )
    }

    /**
     * Текст книги выходит в том порядке, в каком человек видит вкладки у себя (#995).
     *
     * Порядок брался из имён файлов внутри архива (`sheet1.xml`, `sheet2.xml`) — а это след
     * того, каким лист создавали, а не того, каким его видят. У книги с переставленными
     * вкладками текст выходил задом наперёд, хотя код обещал человеку обратное.
     */
    @Test
    fun `текст книги идёт в порядке вкладок, а не в порядке файлов внутри архива`() = runTest {
        val book = xlsxWithTabs("книга.xlsx", smeta, itogo)

        val known = knownText(realizer().perform(officeObject(book), null))

        assertTrue("первая вкладка потеряна: $known", known.contains("Работа"))
        assertTrue("вторая вкладка потеряна: $known", known.contains("Подпись директора"))
        assertTrue(
            "книга вышла порядком файлов архива, а не порядком вкладок: $known",
            known.indexOf("Работа") < known.indexOf("Подпись директора"),
        )
    }

    /**
     * Осечка записи не назначает виноватым целый документ (#995, #997).
     *
     * Документ на компьютере лежит там, где человек его взял: `Inbox` не копирует файл к
     * себе, а оборачивает на месте. Папка бывает только для чтения, диск — сетевым, файл —
     * занятым Office или OneDrive, места — не остаться. Пока чтение и запись лежали в одном
     * `runCatching`, человек слышал «документ повреждён или это не офисный файл» — про целый
     * документ, который только что прочитался.
     */
    @Test
    fun `текст прочитан, а лечь на диск не смог — отказ говорит про запись, а не про документ`() = runTest {
        val file = xlsx("смета.xlsx", smeta)

        // Место знания занято папкой: запись не пройдёт, а документ при этом цел.
        assertTrue("подготовить ловушку не вышло", textBesideDocument(file).mkdir())

        val result = realizer().perform(officeObject(file), null)

        val said = (result as ActionResult.Failure).reason
        assertFalse("виноватым назначен целый документ: $said", "повреждён" in said)
        assertEquals(TEXT_NOT_KEPT, said)
    }

    @Test
    fun `современной таблице компьютер не рассказывает про старые doc и xls`() = runTest {
        val empty = xlsx("смета.xlsx", "<worksheet><sheetData></sheetData></worksheet>")

        val result = realizer().perform(officeObject(empty), null)

        val said = (result as ActionResult.Failure).reason
        assertFalse("причина не про этот файл: $said", said.contains(".doc") || said.contains(".xls "))
    }

    /** Три нажатия подряд не меняли объект (DSK-040): прочитанному документу двери нет. */
    @Test
    fun `прочитанному документу дверь «Извлечь текст» на компьютере не рисуется`() {
        val read = ObjectState(ObjectKind.OFFICE, setOf(Feature.HAS_TEXT))
        val doors = DesktopRegistry(setOf(OfficeCapability())).bubblesFor(read)

        assertTrue("дверь осталась на прочитанном документе: $doors", doors.isEmpty())
        assertTrue(
            "непрочитанный документ дверь потерял",
            DesktopRegistry(setOf(OfficeCapability())).bubblesFor(ObjectState(ObjectKind.OFFICE)).isNotEmpty(),
        )
    }

    /**
     * Телефон попросил компьютер прочитать документ — и прочитанное доехало домой (#811, #995).
     *
     * Компьютер отвечал `ocr.text.ref`: путём по диску ПК, который на телефоне мёртв. Ни
     * текста, ни признака «текст есть» телефон не получал, и документ там снова выглядел
     * непрочитанным — то самое «знание теряется на переносе».
     */
    @Test
    fun `прочитанное компьютером доезжает до телефона значением, а не путём по чужому диску`() {
        val outbox = Outbox(temp.newFolder("outbox"))
        val inbox = Inbox(temp.newFolder("inbox"))
        val state = DesktopState(
            registry = DesktopRegistry(setOf(OfficeCapability())),
            resolver = DesktopResolver(setOf(PcOfficeTextRealizer(OoxmlOfficeTextExtractor()))),
            clipboard = { },
            outbox = outbox,
        )
        val requests = RelayRequests(
            remoteActions = { emptyList() },
            outbox = outbox,
            onPhoneCaps = { },
            clipboardGet = { null },
            clipboardSet = { },
            onObject = { name, mime, meta, bytes, action ->
                val item = inbox.receive(name, mime, meta, bytes.inputStream())
                state.onReceived(item, ObjectSource.PHONE_RELAY)
                action?.let { state.runRemoteActionNow(it, item) }
            },
        )

        val reply = requests.answer(
            RelayRpc.OBJECT,
            mapOf(
                RelayRpc.ID to "письмо-1",
                "name" to "смета.xlsx",
                "mime" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "action" to OfficeCapability.ID.value,
            ),
            xlsx("смета-в-дорогу.xlsx", smeta).readBytes(),
        )!!

        val carried = reply.meta[PcResultFields.UNDERSTOOD + META_READ_TEXT]
        assertTrue("прочитанное не поехало домой: ${reply.meta}", carried.orEmpty().contains("3480"))
        assertNull(
            "телефону уехал путь по диску компьютера",
            reply.meta[PcResultFields.UNDERSTOOD + META_OCR_TEXT_REF],
        )
    }
}
