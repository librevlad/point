package com.point.executors

import com.point.core.flow.Atom
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.META_ENTITY_AMOUNT
import com.point.core.flow.parseFieldCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сумма — число (#1059, решение владельца).
 *
 * Живой прогон 17.08.2026, чек Family Dollar на итог $2.18: под галочкой главного факта
 * стояло слово чека — «Сумма TAX1», — а настоящие 2.00, 0.18 и 2.18 уходили в «ещё».
 * Прежде тем же путём главной суммой вставал «0»: строки чека при чтении слиплись, число
 * прочиталось нулём, метки слов модели поехали на соседнее слово, и заземление по странице
 * подставило слово страницы вместо числа модели. Судья формы у суммы считал одни цифры —
 * и в «TAX1», и в «0» цифра есть.
 *
 * Здесь тот же путь целиком и с того же чека: слой слов страницы, дословный ответ модели
 * с метками, разбор ответа, судья. Номера слов свои — в живом логе они были w11…w16.
 */
class AmountIsANumberTest {

    private var next = 0

    private fun atom(text: String, left: Float, top: Float) = word("w${next++}", text, left, top)

    private fun word(id: String, text: String, left: Float, top: Float) =
        Atom(id = id, text = text, box = Box(left, top, left + 30f * text.length, top + 40f))

    /**
     * Чек, как его прочитала страница: подписи целы, а числа при них слиплись в «0» — ровно
     * те строки «TAX1 0» и «CASH TOTAL 0», что видны в живом тексте чтения.
     */
    private val receipt = AtomLayer(
        listOf(
            atom("FAMILY", 100f, 100f), atom("DOLLAR", 400f, 100f),
            atom("SUBTOTAL", 100f, 200f),
            atom("TAX1", 100f, 300f), atom("0", 600f, 300f),
            atom("CASH", 100f, 400f), atom("TOTAL", 300f, 400f), atom("0", 600f, 400f),
            atom("CASH", 100f, 500f), atom("0", 600f, 500f),
            atom("CHANGE", 100f, 600f), atom("0", 600f, 600f),
        ),
    )

    /**
     * Ответ модели дословно из лога карточки: пять сумм чека. Числа названы верно, а метки
     * слов указывают на слова страницы — на подпись «TAX1» и на слипшиеся нули.
     */
    private val answer = """
        AMOUNT=2.00 [w3]
        AMOUNT=0.18 [w4]
        AMOUNT=2.18 [w7]
        AMOUNT=2.25 [w9]
        AMOUNT=0.07 [w11]
    """.trimIndent()

    private val readings = parseFieldCandidates(answer).fields

    private fun amount() = judgeFields(readings, receipt).won.getValue(META_ENTITY_AMOUNT)

    @Test
    fun `слово чека и слипшийся ноль суммой не становятся ни значением, ни прочтением`() {
        val amount = amount()

        assertTrue("суммой встало слово страницы: " + amount.text, amount.text.none(Char::isLetter))
        assertTrue(
            "слово или ноль остались среди прочтений суммы: " + amount.candidates,
            amount.candidates.none { it.any(Char::isLetter) || it == "0" },
        )
    }

    @Test
    fun `главная сумма чека — итог, а не подытог и не налог`() {
        assertEquals("2.18", amount().text)
    }

    @Test
    fun `подписанный итог побеждает большее число — так велит улика подписи`() {
        // Тот же чек, прочитанный целиком: «CASH TOTAL 2.18», а ниже «CASH 2.25». Величина
        // назвала бы главным 2.25 — деньги, протянутые кассиру; подпись при числе называет
        // итог, и она сильнее: улика подписи спрашивается раньше величины.
        val whole = AtomLayer(
            listOf(
                word("t1", "CASH", 100f, 400f), word("t2", "TOTAL", 300f, 400f),
                word("t3", "2.18", 600f, 400f),
                word("t4", "CASH", 100f, 500f), word("t5", "2.25", 600f, 500f),
            ),
        )
        val said = parseFieldCandidates("AMOUNT=2.25 [t5]\nAMOUNT=2.18 [t3]").fields

        assertEquals("2.18", judgeFields(said, whole).won.getValue(META_ENTITY_AMOUNT).text)
    }

    @Test
    fun `до судьи доходят три лучших прочтения — так велит протокол`() {
        // Наличные и сдача — «CASH 2.25» и «CHANGE 0.07» — до судьи не доходят вовсе: модель
        // обязана называть не больше трёх прочтений поля, лучшее первым. Итог здесь третий,
        // и наибольшее из дошедших им и оказывается.
        assertEquals(
            listOf("2.00", "0.18", "2.18"),
            readings.getValue(META_ENTITY_AMOUNT).map { it.text },
        )
    }
}
