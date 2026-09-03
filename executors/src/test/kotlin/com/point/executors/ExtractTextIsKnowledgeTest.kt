package com.point.executors

import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.ObjectStore
import com.point.core.flow.OoxmlOfficeTextExtractor
import com.point.core.flow.PdfTextExtractor
import com.point.core.flow.capabilities.OfficeCapability
import com.point.core.flow.capabilities.PdfCapability
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * «Извлечь текст» отдаёт знание самому документу (#995) и называет настоящую причину (#997).
 *
 * На PDF действие рождало второй объект, который Point тут же объявлял непригодным: папка
 * отрисованных страниц выдавалась за одиночную картинку, объект указывал на каталог и не
 * открывался ни у кого. На современной .xlsx действие падало с причиной про старые .doc и
 * .xls — к этому файлу она не относится вовсе. А прочитанный документ оставлял дверь на
 * месте: три нажатия подряд, и знание объекта не менялось (DSK-040).
 */
class ExtractTextIsKnowledgeTest {

    @get:Rule val temp = TemporaryFolder()

    private val store = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(
            result: ResultObject,
            from: PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(temp.newFile("scratch-${System.nanoTime()}.$extension").absolutePath)
        override suspend fun clear() = Unit
    }

    private fun layer(text: String) = object : PdfTextExtractor {
        override suspend fun extractText(obj: PointObject, atMost: Int?) = text
    }

    private fun pdf() = PointObject(
        id = "pdf",
        mime = "application/pdf",
        uri = ScratchRef(temp.newFile("счёт-${System.nanoTime()}.pdf").absolutePath),
        state = ObjectState(ObjectKind.PDF),
        metadata = mapOf("name" to "счёт.pdf"),
    )

    private fun knownText(result: ActionResult): String {
        assertTrue("ожидалось знание документу, вышло: $result", result is ActionResult.Done)
        val found = (result as ActionResult.Done).findings
        assertTrue("знания при шаге нет вовсе", found != null)
        assertTrue("документ не получил признака «текст есть»", Feature.HAS_TEXT in found!!.features)
        val ref = found.metadata[META_OCR_TEXT_REF]
        assertTrue("текста у документа нет: ${found.metadata}", !ref.isNullOrBlank())
        return File(ref!!).readText()
    }

    // ——— PDF: текст остаётся у документа ———

    @Test
    fun `текст PDF ложится знанием на сам документ, второго объекта не появляется`() = runTest {
        val realizer = PdfRealizer(store, layer("Счёт № 12 на 3480 гривен"))

        val result = realizer.perform(pdf())

        assertTrue(knownText(result).contains("3480"))
    }

    /**
     * Долгую работу делает то действие, которое её объявляет (#1257).
     *
     * «Извлечь текст» обещает «текст документа · без сети» и латентность FAST. Слоя нет или он
     * нечитаем — страницы рисует и читает «Прочитать документ», у которого объявлены и SLOW, и
     * вопрос, на который оно отвечает. Отказ называет именно этот шаг, а не путь в два действия.
     */
    @Test
    fun `нечитаемый слой зовёт «Прочитать документ», а не делает его работу молча`() = runTest {
        val realizer = PdfRealizer(store, layer(GARBLED))

        val result = realizer.perform(pdf())

        val said = (result as ActionResult.Failure).reason
        assertTrue(said, ReadDocumentCapability().label(ObjectState(ObjectKind.PDF)) in said)
        assertFalse(
            "совет снова ведёт в два действия",
            PagesCapability().label(ObjectState(ObjectKind.PDF)) in said,
        )
    }

    /**
     * Дверь долгой работы у такого документа есть — иначе отказ звал бы в пустоту.
     *
     * Признак ставит исследование `pdf-image-shape` по общему с компьютером правилу, и
     * «Извлечь текст» на таком документе больше не рисуется вовсе.
     */
    @Test
    fun `у документа без пригодного слоя дверь чтения открыта, а быстрой двери нет`() {
        val scan = ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF))

        assertTrue("читать документ нечем", ReadDocumentCapability().accepts(scan))
        assertFalse("быстрая дверь обещает то, чего за ней нет", PdfCapability().accepts(scan))
    }

    /** Второе нажатие ничего не меняло: текст уже знание объекта (#997, DSK-040). */
    @Test
    fun `прочитанному документу «Извлечь текст» больше не предлагают`() {
        val readPdf = ObjectState(ObjectKind.PDF, setOf(Feature.HAS_TEXT))
        val readOffice = ObjectState(ObjectKind.OFFICE, setOf(Feature.HAS_TEXT))

        assertFalse("дверь осталась на прочитанном PDF", PdfCapability().accepts(readPdf))
        assertFalse("дверь осталась на прочитанном документе", OfficeCapability().accepts(readOffice))
        assertTrue("непрочитанный документ дверь потерял", OfficeCapability().accepts(ObjectState(ObjectKind.OFFICE)))
    }

    // ——— Таблица: читается своим читателем, отказ называет настоящую причину ———

    private fun xlsx(name: String, vararg sheets: String): PointObject {
        val file = temp.newFile(name)
        ZipOutputStream(file.outputStream()).use { zos ->
            sheets.forEachIndexed { index, sheetXml ->
                zos.putNextEntry(ZipEntry("xl/worksheets/sheet${index + 1}.xml"))
                zos.write(sheetXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return PointObject(
            id = "xlsx",
            mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            uri = ScratchRef(file.absolutePath),
            state = ObjectState(ObjectKind.OFFICE),
            metadata = mapOf("name" to name),
        )
    }

    /** Смета владельца: общего словаря строк в файле нет вовсе, строки лежат внутри листа. */
    private val smeta = """
        <worksheet><sheetData>
        <row r="1"><c r="A1" t="inlineStr"><is><t>Работа</t></is></c><c r="B1"><v>12</v></c>
        <c r="C1"><v>250</v></c><c r="D1"><v>3000</v></c></row>
        <row r="2"><c r="A2" t="inlineStr"><is><t>Материалы</t></is></c><c r="B2"><v>4</v></c>
        <c r="C2"><v>120</v></c><c r="D2"><v>480</v></c></row>
        <row r="3"><c r="A3" t="inlineStr"><is><t>Итого</t></is></c><c r="D3"><v>3480</v></c></row>
        </sheetData></worksheet>
    """.trimIndent()

    /** Второй лист книги — «Итого»: у сметы владельца сумма и подпись живут именно там. */
    private val itogo = """
        <worksheet><sheetData>
        <row r="1"><c r="A1" t="inlineStr"><is><t>Подпись директора</t></is></c></row>
        <row r="2"><c r="A2" t="inlineStr"><is><t>К оплате</t></is></c><c r="B2"><v>3480</v></c></row>
        </sheetData></worksheet>
    """.trimIndent()

    @Test
    fun `смета со строками внутри листа читается — вместе с числами`() = runTest {
        val result = OfficeRealizerOnPhone(store, OoxmlOfficeTextExtractor())
            .perform(xlsx("смета.xlsx", smeta), null)

        val known = knownText(result)
        assertTrue(known, known.contains("Работа") && known.contains("Материалы"))
        assertTrue("числа таблицы потеряны: $known", known.contains("3480") && known.contains("250"))
    }

    /** Книга читается целиком: у неё бывает больше одного листа (#995). */
    @Test
    fun `у книги из двух листов читаются оба, а не только первый`() = runTest {
        val result = OfficeRealizerOnPhone(store, OoxmlOfficeTextExtractor())
            .perform(xlsx("смета.xlsx", smeta, itogo), null)

        val known = knownText(result)
        assertTrue("первый лист потерян: $known", known.contains("Материалы"))
        assertTrue("второй лист книги потерян: $known", known.contains("Подпись директора"))
        assertTrue("второй лист потерян вместе с суммой: $known", known.contains("К оплате"))
    }

    @Test
    fun `современной таблице не рассказывают про старые doc и xls`() = runTest {
        val empty = xlsx("смета.xlsx", "<worksheet><sheetData></sheetData></worksheet>")

        val result = OfficeRealizerOnPhone(store, OoxmlOfficeTextExtractor()).perform(empty, null)

        val said = (result as ActionResult.Failure).reason
        assertFalse("причина не про этот файл: $said", said.contains(".doc") || said.contains(".xls "))
    }

    @Test
    fun `старому формату причина названа его именем`() = runTest {
        val old = PointObject(
            id = "xls",
            mime = "application/vnd.ms-excel",
            uri = ScratchRef(temp.newFile("смета.xls").absolutePath),
            state = ObjectState(ObjectKind.OFFICE),
            metadata = mapOf("name" to "смета.xls"),
        )

        val result = OfficeRealizerOnPhone(store, OoxmlOfficeTextExtractor()).perform(old, null)

        val said = (result as ActionResult.Failure).reason
        assertTrue(said, said.contains(".xlsx"))
    }

    private companion object {

        /** Слой украинского бухгалтерского PDF с подменённой раскладкой шрифта (#933). */
        const val GARBLED =
            "ToeapucrBo 3 o6MexeHop eignoeiganbHicrlo BaxraxoorpxMyBaq cKnaAaHHR " +
                "flocraqanbHHK e.qPnov Eniqgxtp 3aMoBHHK PaxyHok-cbakrypa"
    }
}
