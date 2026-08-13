package com.point.core.flow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Нечитаемый текстовый слой опознаётся до того, как из него выведут знание (#933).
 *
 * Тексты ниже — настоящие: первый снят с PDF счёта «Епіцентр К», второй — со скана из корпуса
 * владельца. Оба ведут себя одинаково, потому что так устроены очень многие украинские
 * бухгалтерские документы.
 */
class ReadableTextTest {

    private val epicentr = """
        Ilnaruux:
        Micqe cKnaAaHHR: l-inepuapxer, u.3anopinolcn
        flocraqanbHHK:
        BaxraxoorpxMyBaq:
        ToeapucrBo 3 o6MexeHop eignoeiganbHicrlo "Eniqgxtp K"
        e.qPnov 32490244
        04128, M. KrTa, eyn. Eeproeequxa,6-K
    """.trimIndent()

    private val scan = """
        flepioa 3,3 09.03.2026 - 15.03.2026
        r1012
        rryEr
        MAXAPOHSIFXPGEE
        Mafioue: (nosal 55 7o
        cenrHcbKe 72,5 % -19,9 %
        nno,[oBo-rnnHelH
        l3rrl l Earosqux uoKo,ra.dHntr 3
    """.trimIndent()

    private val real = """
        Товариство з обмеженою відповідальністю «Епіцентр К»
        Місце складання: Дніпропетровськ, вулиця Набережна
        Постачальник: підприємство оптової торгівлі
        Договір поставки продовольчих товарів від першого вересня
        Сума до сплати становить дванадцять тисяч гривень
    """.trimIndent()

    private val russian = """
        Счёт на оплату номер четыре тысячи четыреста семнадцать
        Покупатель: товарищество с ограниченной ответственностью
        Оплатить до тридцатого сентября две тысячи двадцать шестого года
        Основание: договор поставки продовольственных товаров
    """.trimIndent()

    @Test
    fun `подменённая раскладка шрифта опознаётся нечитаемой`() {
        assertTrue("счёт Епіцентр прошёл за читаемый", ReadableText.unreadable(epicentr))
        assertTrue("скан прошёл за читаемый", ReadableText.unreadable(scan))
    }

    @Test
    fun `настоящий текст читаемым и остаётся`() {
        assertTrue("украинский текст сочли мусором", ReadableText.readable(real))
        assertTrue("русский текст сочли мусором", ReadableText.readable(russian))
    }

    /** Короткий кусок не судим: ошибиться на подписи из трёх слов легко, а цена высока. */
    @Test
    fun `короткий текст не объявляется мусором`() {
        assertTrue(ReadableText.readable("Акт 12-Б"))
        assertTrue(ReadableText.readable("BH9249MT UA"))
    }

    @Test
    fun `английский документ читается как читаемый`() {
        val english = """
            Invoice number four four one seven for payment
            Supplier: limited liability company from the city centre
            Please pay before the thirtieth of September this year
        """.trimIndent()

        assertTrue(ReadableText.readable(english))
    }

    /** Цифры и коды сами по себе текстом не являются, но и мусором их звать не за что. */
    @Test
    fun `таблица из чисел не объявляется нечитаемой`() {
        assertFalse(ReadableText.unreadable("2026 2027 2028 12 500 3 400 7 800 49000 04128"))
    }
}
