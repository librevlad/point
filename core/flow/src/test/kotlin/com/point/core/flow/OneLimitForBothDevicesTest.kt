package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Пределы и расчёты — по одному месту, а не по копии на устройство (#861).
 *
 * Каждое из этих чисел — обещание человеку: объект тяжелее просто не поедет. Записанные
 * дважды, они разъезжаются молча, и человек получает «на телефоне работает, на компьютере
 * нет» без единого слова о том, почему.
 *
 * Здесь проверяются сами общие расчёты. Проверка «никто не набрал число второй раз» читает
 * файлы других модулей и после #1293 живёт в `:checks`.
 */
class OneLimitForBothDevicesTest {

    /**
     * Имя тут не украшение: сервис расшифровки по расширению решает, как читать байты.
     * Телефон брал его по точному mime и звал незнакомое `ogg`, ПК искал подстроку и звал
     * `audio` — один и тот же файл уезжал под разными именами.
     */
    @Test
    fun `запись зовётся одинаково на обоих устройствах`() {
        assertEquals("ogg", audioExtensionFor("audio/ogg"))
        assertEquals("mp3", audioExtensionFor("audio/mpeg"))
        assertEquals("m4a", audioExtensionFor("audio/mp4"))
        assertEquals("wav", audioExtensionFor("audio/wav"))
        assertEquals("flac", audioExtensionFor("audio/flac"))
        assertEquals("aac", audioExtensionFor("audio/aac"))
    }

    @Test
    fun `незнакомая запись получает имя, с которым её примут, а не выдуманное`() {
        assertEquals("ogg", audioExtensionFor("audio/неизвестное"))
        assertEquals("ogg", audioExtensionFor(""))
    }

    @Test
    fun `уменьшение снимка считается делением пополам, и одинаково везде`() {
        assertEquals(1, sampleSizeFor(80, 60, 96))
        assertEquals(2, sampleSizeFor(192, 100, 96))
        assertEquals(4, sampleSizeFor(400, 300, 96))
        assertEquals(32, sampleSizeFor(4000, 3000, 96))
    }

    @Test
    fun `без предела и без сторон уменьшать нечего`() {
        assertEquals(1, sampleSizeFor(0, 0, 96))
        assertEquals(1, sampleSizeFor(4000, 3000, 0))
    }
}
