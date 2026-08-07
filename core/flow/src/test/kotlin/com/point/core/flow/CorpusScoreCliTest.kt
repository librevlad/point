package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

class CorpusScoreCliTest {

    private val realJournal = """
        [{"id":"76082c43-d349-4f2e-a016-65549d503b22","kind":"IMAGE","mime":"image\/jpeg",
        "ref":"\/data\/user\/0\/com.point\/files\/scratch\/76082c43","metadata":{"name":"c.jpg",
        "entity.address":"продовольча сировина, 3","entity.date":"01.12.2020",
        "reading.mode":"PRINTED","ocr.text.ref":"\/data\/user\/0\/com.point\/files\/scratch\/80e27ef6.txt"},
        "via":null,"viaTitle":null}]
    """.trimIndent().replace("\n", "")

    @Test
    fun `карта читается, комментарии и пустые строки не мешают`() {
        val map = parseFrameMap(
            """
            # кадр	действие	почему вне счёта
            09	meter-reading

            11	track-parcel
            01	извлечь таблицу	таблица   # схемы нет — это законное значение
            16	собрать список в текст	отказ
            """.trimIndent(),
        )

        assertEquals(
            mapOf(
                "09" to FrameExpectation("meter-reading", null),
                "11" to FrameExpectation("track-parcel", null),
                "01" to FrameExpectation("извлечь таблицу", OutOfCount.TABLE),
                "16" to FrameExpectation("собрать список в текст", OutOfCount.REFUSED),
            ),
            map,
        )
    }

    @Test
    fun `факты берутся из журнала устройства дословно, косые черты JSON разэкранированы`() {
        val facts = factsOf(realJournal)

        assertEquals("продовольча сировина, 3", facts["entity.address"])
        assertEquals("01.12.2020", facts["entity.date"])
        assertEquals("PRINTED", facts["reading.mode"])
        assertTrue("путь разэкранирован", facts["ocr.text.ref"]!!.startsWith("/data/user/0/"))
    }

    @Test
    fun `из стека объектов берутся факты последнего`() {
        val stack = """[{"metadata":{"entity.track":"первый"}},{"metadata":{"entity.track":"последний"}}]"""

        assertEquals("последний", factsOf(stack)["entity.track"])
    }

    @Test
    fun `журнал без метаданных — пусто, а не падение`() {
        assertEquals(emptyMap<String, String>(), factsOf("[]"))
    }

    @Test
    fun `пример без журнала назван отдельно, а не зачтён провалом`() {
        val score = CorpusScore(
            ready = listOf("11"),
            notReady = listOf("13"),
            unscored = listOf(UnscoredFrame("01", OutOfCount.TABLE)),
        )

        val text = renderCorpusScore(score, missing = listOf("23"))

        assertTrue(text.contains("справился с 1 из 2"))
        assertTrue(text.contains("${OutOfCount.TABLE.note}: 01"))
        assertTrue("непройденный пример назван", text.contains("не проверялись") && text.contains("23"))
    }

    @Test
    fun `кадры вне счёта печатаются по причинам, а не одной строкой`() {
        val score = CorpusScore(
            ready = listOf("11"),
            notReady = emptyList(),
            unscored = listOf(
                UnscoredFrame("01", OutOfCount.TABLE),
                UnscoredFrame("23", OutOfCount.TABLE),
                UnscoredFrame("16", OutOfCount.REFUSED),
                UnscoredFrame("21", OutOfCount.REFUSED),
                UnscoredFrame("24", null),
            ),
        )

        val text = renderCorpusScore(score, missing = emptyList())

        assertTrue(text.contains("${OutOfCount.TABLE.note}: 01, 23"))
        assertTrue(text.contains("${OutOfCount.REFUSED.note}: 16, 21"))
        assertTrue("выпавший молча назван громко", text.contains("причина не названа") && text.contains("24"))
        assertTrue("слово «пока» на решённом отказе — обещание работы, которой не будет", !text.contains("пока не проверяем"))
    }

    @Test
    fun `мерить нечего — так и сказано, а не ноль процентов`() {
        val unscored = listOf(UnscoredFrame("01", OutOfCount.TABLE), UnscoredFrame("04", OutOfCount.TABLE))
        val text = renderCorpusScore(CorpusScore(emptyList(), emptyList(), unscored), emptyList())

        assertTrue(text.contains("проверять пока нечего"))
    }

    @Test
    fun `карта кадров корпуса читается и покрывает все кадры`() {
        val map = parseFrameMap(realFrameMap.readText())

        assertEquals("кадров корпуса", 23, map.size)
        assertEquals("meter-reading", map["09"]?.action)
        assertEquals("track-parcel", map["11"]?.action)
        assertEquals("route", map["22"]?.action)
        assertEquals("извлечь таблицу", map["23"]?.action)
    }

    @Test
    fun `каждый кадр вне счёта назван причиной — молча не выпадает никто`() {
        val ids = ACTION_SCHEMAS.map { it.id }.toSet()

        val lost = parseFrameMap(realFrameMap.readText())
            .filterValues { it.action !in ids && it.outOfCount == null }

        assertTrue("вне счёта без причины: ${lost.keys}", lost.isEmpty())
    }

    @Test
    fun `причина не остаётся на кадре, у которого появилась схема`() {
        val ids = ACTION_SCHEMAS.map { it.id }.toSet()

        val stale = parseFrameMap(realFrameMap.readText())
            .filterValues { it.action in ids && it.outOfCount != null }

        assertTrue("схема есть, а причина осталась: ${stale.keys}", stale.isEmpty())
    }

    @Test
    fun `два отказа названы отказом, а не ожиданием`() {
        val map = parseFrameMap(realFrameMap.readText())

        assertEquals(OutOfCount.REFUSED, map["16"]?.outOfCount)
        assertEquals(OutOfCount.REFUSED, map["21"]?.outOfCount)
        assertEquals(
            "таблицы меряются своим числом, а не ждут схемы",
            listOf("01", "04", "06", "10", "18", "19", "23"),
            map.filterValues { it.outOfCount == OutOfCount.TABLE }.keys.sorted(),
        )
    }

    private val realFrameMap: File
        get() = File("../../tools/corpus/frames.tsv")
            .also { assertTrue("не найден ${it.absolutePath}", it.exists()) }
}
