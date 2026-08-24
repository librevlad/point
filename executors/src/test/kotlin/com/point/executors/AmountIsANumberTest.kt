package com.point.executors

import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.META_ENTITY_AMOUNT
import com.point.core.flow.amountFacts
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
 * Здесь тот же путь целиком: слой слов страницы, ответ модели с метками, разбор ответа,
 * судья. Слой — не собранный руками, а снятый устройством с того же `chek.jpg`.
 */
class AmountIsANumberTest {

    private fun layerOf(name: String): AtomLayer = AtomCodec.decode(
        checkNotNull(javaClass.getResourceAsStream("/ocr/$name.atoms.tsv")) { "нет фикстуры $name" }
            .bufferedReader().readText(),
    )

    /**
     * Чек, как его прочитало устройство: подписи целы, а числа при них слиплись в «0» — ровно
     * те строки «TAX1 0» и «CASH TOTAL 0», что названы в карточке. Снято с `chek.jpg` карточки
     * (130 950 байт, тот же телефонный номер в графе) прогоном 24.08.2026, слово в слово.
     */
    private val receipt = layerOf("receipt_family_dollar")

    /**
     * Ответ модели дословно из живого лога карточки (`llm-1786919509897-001.txt`): пять сумм
     * чека. Числа названы верно, а метки указывают на слова страницы — в этом слое `w11` это
     * подпись «SUBTOTAL», `w12` — слипшийся ноль, `w14` — «TOTAL».
     */
    private val answer = """
        AMOUNT=2.00 [w11]
        AMOUNT=0.18 [w12]
        AMOUNT=2.18 [w14]
        AMOUNT=2.25 [w15]
        AMOUNT=0.07 [w16]
    """.trimIndent()

    private val readings = parseFieldCandidates(answer).fields

    private fun amount() = judgeFields(readings, receipt).won.getValue(META_ENTITY_AMOUNT)

    @Test
    fun `слипшийся ноль страницы суммой не становится ни значением, ни прочтением`() {
        val amount = amount()

        assertTrue("суммой встал ноль страницы", amount.text != "0")
        assertTrue(
            "ноль остался среди прочтений суммы: " + amount.candidates,
            amount.candidates.none { it == "0" },
        )
    }

    @Test
    fun `слово чека суммой не становится — метка модели попала на подпись`() {
        // Второй живой прогон того же чека (17.08.2026, строка матрицы AND-097) дал под
        // галочкой «Сумма TAX1»: метка одного из чисел попала на подпись строки, а не на
        // число при ней. Подпись «TAX1» стоит в этом слое словом `w13`.
        val marked = parseFieldCandidates("AMOUNT=0.18 [w13]\nAMOUNT=2.18 [w14]").fields
        val amount = judgeFields(marked, receipt).won.getValue(META_ENTITY_AMOUNT)

        assertTrue("суммой встало слово страницы: " + amount.text, amount.text.none(Char::isLetter))
        assertTrue(
            "слово страницы осталось среди прочтений суммы: " + amount.candidates,
            amount.candidates.none { it.any(Char::isLetter) },
        )
    }

    @Test
    fun `главная сумма чека — итог, а не подытог и не налог`() {
        assertEquals("2.18", amount().text)
    }

    @Test
    fun `подписанный итог побеждает большее число — так велит улика подписи`() {
        // Тот же чек, прочитанный без слипания: «CASH TOTAL 2.18», а ниже «CASH 2.25».
        // Величина назвала бы главным 2.25 — деньги, протянутые кассиру; подпись при числе
        // называет итог, и она сильнее: улика подписи спрашивается раньше величины.
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
    fun `подпись итога у судьи — те же слова, что у правила страницы (#1059)`() {
        // Квитанция: «Всього 500,00 грн» и «Отримано 1000,00 грн». Знак и слово валюты стояли
        // в подписях поля — а валюта стоит при каждой сумме документа, и улику подписи
        // получали обе строки. Улик выходило поровну, спор решала величина, и главной вставала
        // полученная тысяча. Одна страница давала два ответа: правило — 500,00, судья — 1000,00.
        val receiptLayer = AtomLayer(
            listOf(
                word("r1", "Всього", 100f, 100f), word("r2", "500,00", 300f, 100f),
                word("r3", "грн", 500f, 100f),
                word("r4", "Отримано", 100f, 200f), word("r5", "1000,00", 360f, 200f),
                word("r6", "грн", 590f, 200f),
            ),
        )
        val said = parseFieldCandidates("AMOUNT=1000,00 [r5]\nAMOUNT=500,00 [r2]").fields

        assertEquals("500,00", judgeFields(said, receiptLayer).won.getValue(META_ENTITY_AMOUNT).text)
        assertEquals("500,00", amountFacts(receiptLayer.text)[META_ENTITY_AMOUNT])
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

    private fun word(id: String, text: String, left: Float, top: Float) =
        Atom(id = id, text = text, box = Box(left, top, left + 30f * text.length, top + 40f))
}
