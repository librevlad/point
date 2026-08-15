package com.point.executors

import com.point.core.flow.layoutOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подзаголовком объекта у русскоязычного человека вставало `Family Dollar store receipt for
 * drinks` (#1036).
 *
 * Правило языка было решено ещё в #670 и записано в тексте зрячего промпта, но текстовый путь
 * просил ровно противоположное — «на языке документа», — и человек получал подзаголовки то на
 * своём языке, то на чужом, в зависимости от того, какой путь понимания сработал.
 *
 * Поэтому правило одно на все пути и живёт одной строкой: тест сторожит и её присутствие,
 * и отсутствие прежнего противоположного требования.
 */
class SummarySpeaksHumanLanguageTest {

    private val prompt = understandPrompt(layoutOf("FAMILY DOLLAR\nMUSKOGEE OK\nTOTAL 4.32"))

    @Test
    fun `текстовый путь просит SUMMARY на языке человека`() {
        assertTrue("промпт не называет язык ответа", prompt.contains(ANSWER_IN_RUSSIAN))
    }

    @Test
    fun `прежнее противоположное правило не вернулось`() {
        assertFalse("SUMMARY снова просят на языке документа", prompt.contains("на языке документа"))
    }

    /** Строка правила называет и SUMMARY, и язык: обрезанная копия сторожа не пройдёт. */
    @Test
    fun `правило называет язык прямо`() {
        assertTrue(ANSWER_IN_RUSSIAN.contains("SUMMARY"))
        assertTrue(ANSWER_IN_RUSSIAN.contains("по-русски"))
    }
}
