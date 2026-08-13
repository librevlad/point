package com.point.executors

import com.point.core.flow.UNDERSTAND_CONTRACT_KEYS
import com.point.core.flow.parseFieldCandidates
import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * Заплатили и выбросили (#935).
 *
 * Модель отвечает по строгому контракту `КЛЮЧ=значение`, и каждый такой ответ стоит человеку
 * денег, времени и — в облачном режиме — согласия отдать объект наружу. Значит потеря ответа
 * это не «не нашли»: это выброшенное на глазах у человека знание, за которое уже заплачено.
 *
 * Так пропала сумма счёта: модель ответила `AMOUNT=12 500`, значение стояло в тексте
 * дословно, а в графе суммы не оказалось ни на телефоне, ни на компьютере.
 *
 * Сторож стоит на классе, а не на сумме: **каждый** ключ контракта проверяется одинаково —
 * значение названо, оно стоит в прочитанном тексте, значит оно обязано дойти до знания.
 * Новый ключ контракта без своего примера здесь — тоже падение: сторож не умеет молчать о
 * том, чего не проверял.
 */
class PaidAnswerIsNotThrownAwayTest {

    /** Что модель могла бы ответить на реальном счёте, и текст, в котором это стоит. */
    private val said = mapOf(
        "PHONE" to "+380 67 636 05 60",
        "EMAIL" to "info@epicentrk.ua",
        "URL" to "https://epicentrk.ua/invoice",
        "ADDRESS" to "вул. Соборна, 12, Запоріжжя",
        "DATE" to "30.09.2026",
        "CARD" to "5169 3351 0912 3456",
        "TRACK" to "20450749113295",
        "METER" to "12345",
        "GEO" to "47.8388, 35.1396",
        "PLACE" to "Відділення №5",
        "AMOUNT" to "12 500",
        "RECEIPT" to "1234567",
        "SUBJECT" to "Оплата рахунку",
    )

    private val page = """
        Рахунок 4417 від 30.09.2026
        ТОВ «Епіцентр К», вул. Соборна, 12, Запоріжжя, Відділення №5
        Тел. +380 67 636 05 60, info@epicentrk.ua, https://epicentrk.ua/invoice
        Оплата рахунку, квитанція 1234567, картка 5169 3351 0912 3456
        Накладна 20450749113295, показання 12345, координати 47.8388, 35.1396
        До сплати 12 500 грн
    """.trimIndent()

    @Test
    fun `названное моделью значение доходит до знания`() {
        val lost = said.filter { (key, value) ->
            val judged = judgeFields(parseFieldCandidates("$key=$value").fields, layer = null, readText = page)
            judged.won[com.point.core.flow.META_ENTITY_PREFIX + UNDERSTAND_CONTRACT_KEYS.getValue(key)] == null
        }.keys

        assertTrue("модель назвала, текст подтвердил, а знания нет: $lost", lost.isEmpty())
    }

    @Test
    fun `у каждого ключа контракта есть свой пример`() {
        assertTrue(
            "контракт вырос, а сторож об этом не знает: ${UNDERSTAND_CONTRACT_KEYS.keys - said.keys}",
            UNDERSTAND_CONTRACT_KEYS.keys.all { it in said },
        )
    }
}
