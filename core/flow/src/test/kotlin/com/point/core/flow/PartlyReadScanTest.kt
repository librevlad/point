package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.model.tr
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Скан, прочитанный не целиком, не получает «Ничего не нашлось» и дочитывается (карточка «Не
 * отвечать «Ничего не нашлось» по скану, прочитанному не целиком», найдено вживую 28.11.2026).
 *
 * Скан на 110 страниц телефон прочитал на сотню и честно сказал «100 из 110», а «Найти в
 * документе» на слово со 107-й ответило «Ничего не нашлось» — ответ обо всём документе, хвост
 * которого никто не видел. Дочитать хвост было нечем: дверь чтения закрывалась, едва у документа
 * появлялся текст. То же на компьютере со страницами, на которых сервис отказал. А чистый лист в
 * середине скана делал прочитанный целиком документ «2 из 3» — неотличимо от непрочитанного.
 */
class PartlyReadScanTest {

    /**
     * Документ на [total] страниц, который растеризатор рисует как телефон: просимое, но не
     * больше [atMost] страниц за заход. Имена файлов — настоящие номера страниц.
     */
    private class Document(private val total: Int, private val atMost: Int = Int.MAX_VALUE) : PdfRasterizer {
        val drawn = mutableListOf<List<Int>>()

        override suspend fun rasterize(obj: PointObject) = rasterize(obj, except = emptySet())

        override suspend fun rasterize(obj: PointObject, except: Set<Int>): RasterizedPages {
            val pages = pagesToDraw(except, total, atMost).also { drawn += it }
            val dir = File.createTempFile("pages-", "").apply { delete(); mkdirs(); deleteOnExit() }
            pages.forEach { File(dir, "page-%03d.jpg".format(it)).apply { writeBytes(ByteArray(4)); deleteOnExit() } }
            return RasterizedPages(ScratchRef(dir.absolutePath), total, numbers = pages)
        }

        override suspend fun rasterizeFirstPage(obj: PointObject) = null
    }

    /** Читатель знает, какую страницу читает, по её номеру — и помнит, какие читал. */
    private class Reader(private val say: (Int) -> String) : TextRecognizer {
        val read = mutableListOf<Int>()

        override suspend fun recognize(obj: PointObject): String {
            val page = Regex("""page-(\d+)""").find(File(obj.uri.value).name)!!.groupValues[1].toInt()
            read += page
            return say(page)
        }
    }

    private val keeper = TextKeeper { _, text ->
        File.createTempFile("read-", ".txt").apply { deleteOnExit(); writeText(text) }.absolutePath
    }

    private val scan = PointObject(
        "pdf",
        "application/pdf",
        ScratchRef("/tmp/skan.pdf"),
        ObjectState(ObjectKind.PDF, setOf(Feature.IS_IMAGE_PDF)),
    )

    private val door = ReadDocumentCapability()

    /** Знание шага ложится на объект тем же слиянием, каким его кладёт продукт. */
    private fun PointObject.learned(result: ActionResult): PointObject {
        val found = (result as ActionResult.Done).findings!!
        return copy(
            state = found.features.fold(state) { known, feature -> known.with(feature) },
            metadata = mergeKnowledge(metadata, found.metadata, REFRESHABLE_KNOWLEDGE),
        )
    }

    private suspend fun PointObject.find(query: String): String =
        (FindRealizer().perform(this, query) as ActionResult.Done).message

    private fun PointObject.text(): String = File(metadata.getValue(META_OCR_TEXT_REF)).readText()

    private fun stateOf(obj: PointObject) = investigationStateOf(obj.metadata, KnownCapabilities.IMAGE_TEXT)

    /** Страница скана на 110 страниц: на 3-й — сумма, на 107-й — фамилия. */
    private fun page(number: Int): String = buildString {
        append("Сторінка $number.")
        if (number == 3) append(" Сума 1 450 грн")
        if (number == 107) append(" Одержувач Петренко")
    }

    @Test fun `скан на 110 страниц, прочитанный на сотню, на слово из хвоста не отвечает «ничего»`() = runTest {
        val phone = Document(total = 110, atMost = 100)
        val reader = Reader(::page)

        val first = ReadDocumentRealizer(phone, reader, keeper).perform(scan, null)
        val read = scan.learned(first)

        assertEquals("прочитанное начало выдано за весь документ", InvestigationState.INSUFFICIENTLY_INVESTIGATED, stateOf(read))
        assertEquals(PageCoverage((1..100).toSet(), total = 110), pageCoverageOf(read.metadata))

        val tail = read.find("Петренко")
        assertNotEquals("про хвост, которого никто не читал, сказано «ничего не нашлось»", foundOnPageLabel(0), tail)
        assertTrue("ответ не говорит, сколько прочитано из скольких: $tail", "100" in tail && "110" in tail)

        val head = read.find("1 450")
        assertTrue("найденное в прочитанном не названо со страницей: $head", head.startsWith(foundInDocumentLabel(1, listOf(3))))
        assertTrue("найденное звучит как все места документа: $head", "110" in head)
    }

    /**
     * «Дочитать» — следующий виток того же чтения: дверь не закрывается, пока документ прочитан
     * не целиком, и читает только непросмотренное, а текст ложится в порядке страниц.
     */
    @Test fun `недочитанный скан дочитывается — только хвост, и слово из хвоста находится на своей странице`() = runTest {
        val phone = Document(total = 110, atMost = 100)
        val reader = Reader(::page)
        val realizer = ReadDocumentRealizer(phone, reader, keeper)
        val read = scan.learned(realizer.perform(scan, null))

        assertTrue("у недочитанного скана нет двери дочитать", door.accepts(GraphState(read)))
        assertNotEquals("дверь зовётся так, будто читает заново с первой страницы", door.label(read.state), door.label(GraphState(read)))

        reader.read.clear()
        val heard = mutableListOf<String>()
        var second: ActionResult? = null
        heard += stagesHeard { second = realizer.perform(read, null) }
        val whole = read.learned(second!!)

        assertEquals("второй заход перечитал прочитанное", (101..110).toList(), reader.read)
        assertTrue("слова о ходе — не настоящие номер и счёт: $heard", tr("Читаю страницу {0} из {1}", 101, 110) in heard)
        assertEquals(InvestigationState.FOUND, stateOf(whole))
        assertTrue("документ прочитан целиком, а счёт об этом не знает", pageCoverageOf(whole.metadata)!!.complete)

        val text = whole.text()
        val starts = pageStartsOf(whole.metadata)
        assertEquals("страницы встали не по порядку документа", (1..110).toList(), starts.map { it.page })
        assertTrue(
            "граница страницы указывает не туда, где её текст",
            starts.all { text.startsWith("Сторінка ${it.page}.", it.offset) },
        )
        assertEquals(foundInDocumentLabel(1, listOf(107)), whole.find("Петренко"))
        assertFalse("у прочитанного целиком скана осталась дверь чтения", door.accepts(GraphState(whole)))
    }

    @Test fun `ход чтения называет настоящий номер страницы и счёт документа, а не нарисованного`() = runTest {
        val heard = stagesHeard {
            ReadDocumentRealizer(Document(total = 110, atMost = 100), Reader(::page), keeper).perform(scan, null)
        }

        assertTrue("счёт документа в словах о ходе потерян: $heard", tr("Читаю страницу {0} из {1}", 1, 110) in heard)
        assertFalse("сотня нарисованных выдана за документ: $heard", tr("Читаю страницу {0} из {1}", 1, 100) in heard)
    }

    /** Чистый лист прочитан: текста на нём нет — это ответ, а не непрочитанная страница. */
    @Test fun `пустая страница не делает прочитанный скан недочитанным`() = runTest {
        val texts = mapOf(1 to "перша сторінка", 2 to "", 3 to "третя сторінка")

        val done = ReadDocumentRealizer(Document(total = 3), Reader { texts.getValue(it) }, keeper).perform(scan, null)
        val read = scan.learned(done)

        assertTrue("чистый лист посчитан непрочитанным: ${(done as ActionResult.Done).message}", "3 из 3" in done.message)
        assertEquals(InvestigationState.FOUND, stateOf(read))
        assertTrue(pageCoverageOf(read.metadata)!!.complete)
        assertEquals("номер страницы после чистого листа сдвинулся", foundInDocumentLabel(1, listOf(3)), read.find("третя"))
        assertFalse("у прочитанного целиком скана осталась дверь чтения", door.accepts(GraphState(read)))
    }

    /**
     * Страница, на которой чтение сорвалось, не просмотрена — и дочитывается одна, без
     * перечитывания соседних: на компьютере каждая страница — запрос к сервису.
     */
    @Test fun `сорвавшаяся страница дочитывается одна, и её текст встаёт на своё место`() = runTest {
        var refused = false
        val texts = mapOf(1 to "перша сторінка", 2 to "друга сторінка", 3 to "третя сторінка")
        val reader = Reader { page ->
            if (page == 2 && !refused) {
                refused = true
                error("service quota")
            }
            texts.getValue(page)
        }
        val realizer = ReadDocumentRealizer(Document(total = 3), reader, keeper)

        val read = scan.learned(realizer.perform(scan, null))

        assertEquals(InvestigationState.INSUFFICIENTLY_INVESTIGATED, stateOf(read))
        assertEquals("сорвавшаяся страница посчитана прочитанной", setOf(1, 3), pageCoverageOf(read.metadata)!!.pages)
        assertTrue("после срыва дочитать нечем", door.accepts(GraphState(read)))
        assertNotEquals(
            "по слову со страницы, которую не прочли, сказано «ничего не нашлось»",
            foundOnPageLabel(0),
            read.find("друга"),
        )

        reader.read.clear()
        val whole = read.learned(realizer.perform(read, null))

        assertEquals("дочитывание перечитало прочитанное", listOf(2), reader.read)
        assertEquals(InvestigationState.FOUND, stateOf(whole))
        assertEquals(listOf(1, 2, 3), pageStartsOf(whole.metadata).map { it.page })
        val text = whole.text()
        assertTrue("страницы склеены не по порядку: $text", text.indexOf("перша") < text.indexOf("друга") && text.indexOf("друга") < text.indexOf("третя"))
        assertEquals(foundInDocumentLabel(1, listOf(2)), whole.find("друга"))
        assertEquals(foundInDocumentLabel(1, listOf(3)), whole.find("третя"))
    }

    /** Пустое начало длинного скана — не конец чтения: дверь дочитать остаётся и читает хвост. */
    @Test fun `пустое начало длинного скана дочитывается с первой непросмотренной страницы`() = runTest {
        val reader = Reader { page -> if (page <= 100) "" else "Сторінка $page." }
        val realizer = ReadDocumentRealizer(Document(total = 150, atMost = 100), reader, keeper)

        val blank = scan.learned(realizer.perform(scan, null))

        assertTrue("после пустого начала дочитать нечем", door.accepts(GraphState(blank)))
        assertNotEquals(door.label(blank.state), door.label(GraphState(blank)))

        reader.read.clear()
        val whole = blank.learned(realizer.perform(blank, null))

        assertEquals("пустые страницы читаются второй раз", (101..150).toList(), reader.read)
        assertEquals(InvestigationState.FOUND, stateOf(whole))
        assertEquals(foundInDocumentLabel(1, listOf(120)), whole.find("Сторінка 120."))
    }

    /**
     * Текст, доехавший с другого устройства обрезком, дочитывается с места обрыва (#1014): счёт
     * просмотренного едет целиком, но страницы за обрывом сюда не доехали, а страница обрыва —
     * не вся. Склеить новое с ними по их границам значило бы положить текст хвоста под чужие
     * номера.
     */
    @Test fun `доехавший обрезком текст дочитывается с места обрыва, и границы страниц сходятся`() = runTest {
        val long = { page: Int -> "Сторінка $page. " + "умови договору ".repeat(100) }
        val there = scan.learned(ReadDocumentRealizer(Document(total = 110, atMost = 100), Reader(long), keeper).perform(scan, null))
        val packed = knowledgePackedForTravel(there.metadata, there.text().take(READ_TEXT_TRAVEL_READ))
        val kept = File.createTempFile("arrived-", ".txt").apply { deleteOnExit(); writeText(textArrivedFromTravel(packed)!!) }
        val landed = knowledgeArrivedFromTravel(packed, kept.absolutePath)
        val here = scan.copy(state = scan.state.with(Feature.HAS_TEXT), metadata = landed.metadata)
        val cutAt = pageCutAt(pageStartsOf(here.metadata), kept.readText().length)!!

        val reader = Reader(long)
        val whole = here.learned(ReadDocumentRealizer(Document(total = 110), reader, keeper).perform(here, null))

        assertEquals("обрыв и хвост не дочитаны, или перечитано доехавшее", (cutAt..110).toList(), reader.read)
        assertEquals(InvestigationState.FOUND, stateOf(whole))
        val text = whole.text()
        val starts = pageStartsOf(whole.metadata)
        assertEquals((1..110).toList(), starts.map { it.page })
        assertTrue(
            "граница страницы указывает не туда, где её текст",
            starts.all { text.startsWith("Сторінка ${it.page}.", it.offset) },
        )
        assertEquals(foundInDocumentLabel(1, listOf(cutAt + 5)), whole.find("Сторінка ${cutAt + 5}."))
    }

    /** Читатель телефона: о срыве он не бросает, а говорит слоем — пустым и с пометкой `incomplete`. */
    private class LayerReader(private val say: (Int) -> AtomLayer) : AtomRecognizer {
        val read = mutableListOf<Int>()

        override suspend fun read(obj: PointObject): AtomLayer {
            val page = Regex("""page-(\d+)""").find(File(obj.uri.value).name)!!.groupValues[1].toInt()
            read += page
            return say(page)
        }
    }

    /**
     * Не завёлся движок, кончилась память, вышло время — страница не просмотрена, и чистым листом
     * она не считается («пусто и оборвано» — не «текста нет», ADR-0001 §9). Иначе скан выходил
     * «3 из 3» и «найдено», дверь «Дочитать» закрывалась, а слово со страницы, до которой движок
     * не дошёл, искалось в ответ «Ничего не нашлось».
     */
    @Test fun `страница, до которой движок телефона не дошёл, не считается чистым листом`() = runTest {
        var outOfMemory = true
        val texts = mapOf(1 to "перша сторінка", 2 to "друга сторінка", 3 to "третя сторінка")
        val reader = LayerReader { page ->
            if (page == 2 && outOfMemory) {
                AtomLayer(emptyList(), incomplete = "error: OutOfMemoryError")
            } else {
                AtomLayer(emptyList(), readerText = texts.getValue(page))
            }
        }
        val realizer = ReadDocumentRealizer(Document(total = 3), reader, keeper)

        val done = realizer.perform(scan, null) as ActionResult.Done
        val read = scan.learned(done)

        assertFalse("сорвавшаяся страница посчитана прочитанной: ${done.message}", "3 из 3" in done.message)
        assertEquals(InvestigationState.INSUFFICIENTLY_INVESTIGATED, stateOf(read))
        assertEquals("сорвавшаяся страница посчитана прочитанной", setOf(1, 3), pageCoverageOf(read.metadata)!!.pages)
        assertTrue("после срыва движка дочитать нечем", door.accepts(GraphState(read)))
        assertNotEquals(
            "по слову со страницы, до которой движок не дошёл, сказано «ничего не нашлось»",
            foundOnPageLabel(0),
            read.find("друга"),
        )

        outOfMemory = false
        reader.read.clear()
        val whole = read.learned(realizer.perform(read, null))

        assertEquals("дочитывание перечитало прочитанное", listOf(2), reader.read)
        assertEquals(InvestigationState.FOUND, stateOf(whole))
        assertEquals(foundInDocumentLabel(1, listOf(2)), whole.find("друга"))
    }

    /**
     * Страница, на которой читатель срывается каждый раз, не держит на себе дочитывание: сотня
     * телефона — сотня непрочитанных страниц, а не сотня подряд от сорванной. Прежде скан на 250
     * страниц с вечно сорванной третьей рисовал 3–102 снова и снова, и хвост за 102-й не читался
     * никогда, хотя слова чтения обещали, что его дочитает «Дочитать документ».
     */
    @Test fun `вечно сорванная страница не мешает дочитать хвост длинного скана`() = runTest {
        val phone = Document(total = 250, atMost = 100)
        val reader = Reader { page -> if (page == 3) error("engine crashed") else "Сторінка $page." }
        val realizer = ReadDocumentRealizer(phone, reader, keeper)

        var read = scan.learned(realizer.perform(scan, null))
        reader.read.clear()
        read = read.learned(realizer.perform(read, null))
        assertTrue("второй заход перечитал прочитанное: ${reader.read}", reader.read.none { it in 1..100 && it != 3 })
        read = read.learned(realizer.perform(read, null))

        assertEquals(
            "сотня захода снова ушла на те же страницы",
            listOf((1..100).toList(), listOf(3) + (101..199).toList(), listOf(3) + (200..250).toList()),
            phone.drawn,
        )
        assertEquals("хвост за сотней так и не дочитан", (1..250).toSet() - 3, pageCoverageOf(read.metadata)!!.pages)
        val tail = read.find("Сторінка 250.")
        assertTrue("слово с последней страницы не нашлось на своей странице: $tail", tail.startsWith(foundInDocumentLabel(1, listOf(250))))
        assertTrue("у скана с непрочитанной страницей не осталось двери дочитать", door.accepts(GraphState(read)))

        // Заход, в котором сорвалась только та же страница, знания не добыл: это срыв операции,
        // а не ответ о документе (`not investigated` ≠ `not found`).
        assertTrue(realizer.perform(read, null) is ActionResult.Failure)
    }

    @Test fun `заход рисует непрочитанное в пределах документа и не больше предела`() {
        assertEquals((1..100).toList(), pagesToDraw(emptySet(), pageCount = 110, atMost = 100))
        assertEquals((101..110).toList(), pagesToDraw((1..100).toSet(), pageCount = 110, atMost = 100))
        assertEquals((101..200).toList(), pagesToDraw((1..100).toSet(), pageCount = 250, atMost = 100))
        assertEquals(listOf(2), pagesToDraw(setOf(1, 3), pageCount = 3))
        assertTrue("прочитанный целиком документ нарисован снова", pagesToDraw((1..110).toSet(), pageCount = 110).isEmpty())
        assertEquals(
            "сорванная страница держит на себе сотню захода",
            listOf(3) + (101..199).toList(),
            pagesToDraw((1..100).toSet() - 3, pageCount = 250, atMost = 100),
        )
    }

    @Test fun `счёт просмотренных страниц переживает запись и чтение`() {
        val coverage = PageCoverage((1..100).toSet() + 102 + (104..110), total = 110)

        assertEquals(coverage, pageCoverageOf(mapOf(META_OCR_TEXT_COVERAGE to pageCoverageWire(coverage))))
        assertEquals("испорченная запись выдана за знание", null, pageCoverageOf(mapOf(META_OCR_TEXT_COVERAGE to "1-x/")))
    }

    /** Счёт — пометка при тексте: ушёл текст — ушёл и счёт, а не остался подписью к пустоте. */
    @Test fun `счёт страниц уходит вместе с текстом`() {
        val meta = mapOf(
            META_OCR_TEXT_REF to "/нет/такого.txt",
            META_OCR_TEXT_PAGES to "1:0",
            META_OCR_TEXT_COVERAGE to "1-100/110",
        )

        assertTrue(META_OCR_TEXT_COVERAGE in REFRESHABLE_KNOWLEDGE)
        assertEquals(emptyMap<String, String>(), withoutKnowledge(meta, setOf(META_OCR_TEXT_REF)))
    }
}
