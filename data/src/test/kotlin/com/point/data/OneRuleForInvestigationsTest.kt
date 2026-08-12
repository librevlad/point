package com.point.data

import com.point.core.flow.INVESTIGATION_FAILED
import com.point.core.flow.investigated
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.Findings
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Исследование либо принесло знание, либо честно не вышло — одним правилом (#863).
 *
 * Норма конституции: «Сорвавшееся исследование оставляет знание в `not investigated` или
 * «исследовано недостаточно» и никогда не переводит его в `not found`». Пока правило было
 * написано тринадцать раз, оно держалось на том, что все тринадцать авторов написали
 * одинаково — и один уже начал расходиться, вписав фразу литералом вместо константы.
 */
class OneRuleForInvestigationsTest {

    @Test
    fun `найденное знание доезжает до Graph`() = runTest {
        val result = investigated { Findings(features = setOf(Feature.HAS_TEXT)) }

        assertEquals(ActionResult.Done("", Findings(features = setOf(Feature.HAS_TEXT))), result)
    }

    /**
     * Главное в правиле: срыв — это `Failure`, а не `Done` с пустыми находками. «Не вышло
     * спросить» и «спросили, не нашли» — разные вещи, и вторая закрывает вопрос, которого
     * никто не задавал.
     */
    @Test
    fun `срыв не превращается в «спросили, не нашли»`() = runTest {
        val result = investigated { error("сеть отвалилась") }

        assertTrue(result.toString(), result is ActionResult.Failure)
        assertTrue("вопрос ещё можно задать заново", (result as ActionResult.Failure).recoverable)
    }

    @Test
    fun `у срыва без своей причины есть общее слово`() = runTest {
        val result = investigated { throw RuntimeException() }

        assertEquals(INVESTIGATION_FAILED, (result as ActionResult.Failure).reason)
    }

    /**
     * Чужой текст исключения человеку не показывается ни при каком исходе (#570): в нём
     * бывает и путь из недр, и «Central Directory Entry not found».
     */
    @Test
    fun `своя причина закрывает чужой текст исключения`() = runTest {
        val ours = "архив не удалось прочитать"

        val result = investigated(whenFailed = ours) {
            error("Central Directory Entry not found at /data/user/0/…")
        }

        assertEquals(ours, (result as ActionResult.Failure).reason)
    }

    @Test
    fun `ни одно исследование не пишет это правило заново`() {
        val dir = File("src/main/kotlin/com/point/data")
        val guilty = dir.listFiles { f -> f.name.endsWith("Investigation.kt") }.orEmpty()
            .filterNot { it.readText().contains("investigated") }
            .map { it.name }

        assertTrue("исследование со своей обёрткой: $guilty", guilty.isEmpty())
    }
}
