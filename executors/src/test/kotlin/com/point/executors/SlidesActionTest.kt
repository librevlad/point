package com.point.executors

import com.point.core.flow.ObjectClassifier
import com.point.core.flow.OoxmlOfficeTextExtractor
import com.point.core.flow.capabilities.OfficeCapability
import com.point.core.flow.collectionOrder
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * У презентации есть слайды (#1105, решение владельца 23.08.2026).
 *
 * Живой прогон 17.08.2026 (A34, OBJ-040): .pptx из трёх слайдов предлагала только «Извлечь
 * текст», и та возвращала один сплошной текст всех слайдов подряд — сумма с первого слайда и
 * контакт со второго лежали общей кучей, и сказать, где что сказано, было нечем.
 *
 * Проверка идёт настоящим .pptx и путём человека: принёс файл → увидел дверь на первом
 * экране → нажал → получил набор, где каждый слайд знает своё.
 */
class SlidesActionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val classifier = ObjectClassifier()

    private val store = object : com.point.core.flow.ObjectStore {
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
            ScratchRef(File(tmp.newFolder(), "out.$extension").absolutePath)
        override suspend fun clear() = Unit
    }

    private val registry = DefaultCapabilityRegistry(
        capabilities = setOf(OfficeCapability(), SlidesCapability(), OpenCapability(), ShareCapability()),
        policy = DefaultBubblePolicy(),
    )

    private fun doorsFor(state: ObjectState) = registry.bubblesFor(state).map { it.capabilityId.value }.toSet()

    /** Настоящая .pptx — слайды лежат отдельными частями архива, как их кладёт PowerPoint. */
    private fun pptx(vararg slides: Pair<Int, String>): PointObject {
        val file = File(tmp.newFolder(), NAME)
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("ppt/presentation.xml"))
            zos.write("<p:presentation/>".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            slides.forEach { (number, text) ->
                zos.putNextEntry(ZipEntry("ppt/slides/slide$number.xml"))
                zos.write(slideXml(text).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return PointObject(
            id = "p",
            mime = PPTX,
            uri = ScratchRef(file.absolutePath),
            state = classifier.classify(PPTX, file.length(), file.name),
            metadata = mapOf("name" to file.name),
        )
    }

    private fun slideXml(text: String) =
        "<p:sld><p:cSld><p:spTree><a:t>$text</a:t></p:spTree></p:cSld></p:sld>"

    /** Старый .ppt — двоичный формат, который Point не открывает вовсе. */
    private fun ppt(): PointObject {
        val file = File(tmp.newFolder(), OLD_NAME)
        file.writeBytes(ByteArray(OLD_HEAD_BYTES) { if (it == 0) 0xD0.toByte() else 0xCF.toByte() })
        return PointObject(
            id = "p",
            mime = PPT,
            uri = ScratchRef(file.absolutePath),
            state = classifier.classify(PPT, file.length(), file.name),
            metadata = mapOf("name" to file.name),
        )
    }

    /**
     * Та же презентация, оборванная посреди второго слайда: первый слайд в файле целый — его
     * и отдавали за весь набор, — а дальше архив кончается на полуслове, как у недокачанного
     * или побитого файла.
     */
    private fun cutInsideSecondSlide(obj: PointObject): PointObject {
        val file = File(obj.uri.value)
        val bytes = file.readBytes()
        val part = "ppt/slides/slide2.xml".toByteArray(Charsets.UTF_8)
        file.writeBytes(bytes.copyOf(bytes.startOf(part) + part.size + INSIDE_PART_BYTES))
        return obj
    }

    /** Где в архиве начинается запись с этим именем: сразу за именем идут её байты. */
    private fun ByteArray.startOf(name: ByteArray): Int =
        (0..size - name.size).first { at -> name.indices.all { this[at + it] == name[it] } }

    private suspend fun split(obj: PointObject) =
        SlidesRealizer(store, OoxmlOfficeTextExtractor()).perform(obj, null)

    private fun partsOf(result: ActionResult): Map<String, String> {
        val out = (result as ActionResult.Success).result
        assertEquals(ObjectKind.COLLECTION, out.type)
        return File(out.uri.value).listFiles().orEmpty().associate { it.name to it.readText() }
    }

    /**
     * Дверь стоит на первом экране: из чего документ состоит, видно по имени и mime, и ждать
     * прочитанных байтов для этого не нужно.
     */
    @Test
    fun `у презентации есть «Слайды», а у текстового документа их нет`() {
        val presentation = classifier.classify(PPTX, SIZE, NAME)
        val document = classifier.classify(DOCX, SIZE, "акт.docx")

        assertTrue("человек не увидит, что презентация состоит из слайдов", "slides" in doorsFor(presentation))
        assertTrue("«Слайды» предложены документу, у которого их нет", "slides" !in doorsFor(document))
    }

    /**
     * То самое место, где прежде выходила общая куча: сумма названа на первом слайде, контакт
     * — на втором, и знание держится своего слайда.
     */
    @Test
    fun `презентация из трёх слайдов становится набором, где каждый слайд знает своё`() = runTest {
        val obj = pptx(
            1 to "Слайд первый — смета. Сумма 12 500 грн",
            2 to "Слайд второй — контакты. Олена Ковальчук",
            3 to "Слайд третий — итог",
        )

        val result = split(obj)

        assertEquals("3", (result as ActionResult.Success).result.metadata["count"])
        val parts = partsOf(result)
        val first = parts.getValue(SlidesRealizer.slideName(1))
        val second = parts.getValue(SlidesRealizer.slideName(2))

        assertTrue(first, "12 500" in first)
        assertTrue(second, "Ковальчук" in second)
        assertTrue("сумма первого слайда лежит и во втором", "12 500" !in second)
        assertTrue("контакт второго слайда лежит и в первом", "Ковальчук" !in first)
    }

    /**
     * Обычная колода: заголовок, фотография, текст. Слайд с одной фотографией из набора не
     * пропадает (§13 и инвариант 13) — «слов не нашлось» это не «такого слайда нет»: иначе
     * человек не увидел бы второго слайда ни в списке, ни в порядке и не смог бы войти в
     * него, чтобы продолжить понимание. Порядок — знание набора (#1207): по имени «Слайд 10»
     * встал бы между первым и вторым.
     */
    @Test
    fun `слайд с одной фотографией остаётся в наборе, а десятый не встаёт вторым`() = runTest {
        val obj = pptx(1 to "первый", 2 to "", 3 to "третий", 10 to "десятый")

        val out = (split(obj) as ActionResult.Success).result

        assertEquals("4", out.metadata["count"])
        assertEquals(
            listOf(1, 2, 3, 10).map { SlidesRealizer.slideName(it) },
            collectionOrder(out.metadata),
        )
    }

    /**
     * Слайд без слов честно непригоден, а не выдуман: часть пустая, и негодность её видна
     * тем же нулевым сигналом, каким Point видит любой пустой файл (#684). Придумать вместо
     * текста фразу «здесь текста нет» нельзя — это было бы знание, которого в слайде нет.
     */
    @Test
    fun `часть слайда без слов пуста, и пустота названа своими словами`() = runTest {
        val out = (split(pptx(1 to "первый", 2 to "")) as ActionResult.Success).result

        val empty = File(out.uri.value, SlidesRealizer.slideName(2))
        assertTrue("слайда без слов нет в наборе — войти в него нечем", empty.isFile)
        assertEquals(0L, empty.length())
        assertTrue(
            "человек войдёт в такой слайд и не услышит, почему он пуст",
            classifier.classify("text/plain", empty.length(), empty.name).has(Feature.UNUSABLE),
        )
    }

    /**
     * Человек нажал «Слайды» — и слышит про слайды (#1105, §13).
     *
     * Колода из одних картинок отвечала «В этом документе текста нет»: ответ про текст на
     * вопрос про слайды, да ещё и отменяющий сами слайды — они-то есть.
     */
    @Test
    fun `колода из одних картинок говорит про слайды, а не про документ без текста`() = runTest {
        val result = split(pptx(1 to "", 2 to ""))

        assertEquals(SlidesRealizer.NO_WORDS_ON_SLIDES, (result as ActionResult.Failure).reason)
        assertFalse("это про саму презентацию, а не про сорвавшуюся попытку", result.recoverable)
    }

    /** Старый .ppt Point не открывает вовсе — и причина названа его, а не чужая (#997). */
    @Test
    fun `у старого формата причина своя — формат, а не отсутствие слов`() = runTest {
        val result = split(ppt())

        assertEquals(com.point.core.flow.OLD_OFFICE_FORMAT, (result as ActionResult.Failure).reason)
    }

    /**
     * Побитая презентация не притворяется целой (инвариант 8): чтение архива оборвалось на
     * втором слайде — и человеку сказано, что разобрать не вышло, а не выдан набор из
     * одной части с подписью «слайдов: 1».
     */
    @Test
    fun `побитая презентация отказывает, а не выдаёт неполный набор за целый`() = runTest {
        val obj = cutInsideSecondSlide(pptx(1 to "первый", 2 to "второй", 3 to "третий"))

        val result = split(obj)

        assertEquals(SlidesRealizer.NOT_SPLIT, (result as ActionResult.Failure).reason)
        assertTrue("оборванное чтение — про попытку, а не про сам файл", result.recoverable)
    }

    private companion object {

        const val NAME = "презентация.pptx"

        const val OLD_NAME = "старая.ppt"

        const val SIZE = 3_032L

        /** Столько байт двоичного .ppt: больше заголовка записи архива, чтобы читатель дошёл до сути. */
        const val OLD_HEAD_BYTES = 64

        /** Сколько байт второго слайда остаётся в оборванном файле — начало без продолжения. */
        const val INSIDE_PART_BYTES = 4

        const val PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

        const val PPT = "application/vnd.ms-powerpoint"

        const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
