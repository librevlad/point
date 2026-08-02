package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * Разбор входа счётчика корпуса: карта кадров и журнал флоу, снятый с устройства.
 *
 * Проверяется на **дословном** журнале прогона (кадр 04, 02.08.2026), а не на сочинённом JSON:
 * правило проекта — правила движка судить по тому, что реально отдаёт устройство.
 */
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
            # кадр	действие
            09	meter-reading

            11	track-parcel
            01	извлечь таблицу   # схемы пока нет — это законное значение
            """.trimIndent(),
        )

        assertEquals(mapOf("09" to "meter-reading", "11" to "track-parcel", "01" to "извлечь таблицу"), map)
    }

    @Test
    fun `факты берутся из журнала устройства дословно, косые черты JSON разэкранированы`() {
        val facts = factsOf(realJournal)

        assertEquals("продовольча сировина, 3", facts["entity.address"])
        assertEquals("01.12.2020", facts["entity.date"])
        assertEquals("PRINTED", facts["reading.mode"])
        assertTrue("путь разэкранирован", facts["ocr.text.ref"]!!.startsWith("/data/user/0/"))
    }

    /**
     * Прогон заканчивается на объекте, который видит человек: если после действия объект сменился,
     * готовность считается по НЕМУ. Взять первый значило бы мерить вход вместо результата.
     */
    @Test
    fun `из стека объектов берутся факты последнего`() {
        val stack = """[{"metadata":{"entity.track":"первый"}},{"metadata":{"entity.track":"последний"}}]"""

        assertEquals("последний", factsOf(stack)["entity.track"])
    }

    @Test
    fun `журнал без метаданных — пусто, а не падение`() {
        assertEquals(emptyMap<String, String>(), factsOf("[]"))
    }

    /**
     * «Не мерили» и «не готово» — разные факты. Кадр без журнала обязан быть назван отдельно:
     * сложенный с непройденными, он тихо ухудшил бы число, а спрятанный — тихо улучшил.
     */
    @Test
    fun `пример без журнала назван отдельно, а не зачтён провалом`() {
        val score = CorpusScore(ready = listOf("11"), notReady = listOf("13"), unscored = listOf("01"))

        val text = renderCorpusScore(score, missing = listOf("23"))

        assertTrue(text.contains("справился с 1 из 2"))
        assertTrue(text.contains("пока не проверяем, не описано что считать успехом: 01"))
        assertTrue("непройденный пример назван", text.contains("не проверялись") && text.contains("23"))
    }

    @Test
    fun `мерить нечего — так и сказано, а не ноль процентов`() {
        val text = renderCorpusScore(CorpusScore(emptyList(), emptyList(), listOf("01", "04")), emptyList())

        assertTrue(text.contains("проверять пока нечего"))
    }

    /**
     * Карта в репозитории — вход харнесса: опечатка в ней иначе всплыла бы только после прогона
     * на устройстве, то есть через полчаса работы эмулятора.
     */
    @Test
    fun `карта кадров корпуса читается и покрывает все кадры`() {
        val file = File("../../tools/corpus/frames.tsv")
        assertTrue("не найден ${file.absolutePath}", file.exists())

        val map = parseFrameMap(file.readText())

        assertEquals("кадров корпуса", 23, map.size)
        assertEquals("meter-reading", map["09"])
        assertEquals("track-parcel", map["11"])
        assertEquals("route", map["22"])
        assertEquals("извлечь таблицу", map["23"])
    }
}
