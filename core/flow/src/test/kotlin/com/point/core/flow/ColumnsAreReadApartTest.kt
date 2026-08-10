package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Документ в две колонки читается колонками, а не поперёк (#747).
 *
 * Раскладка снята с настоящей почтовой наклейки Нова Пошта: сверху шапка во всю ширину, ниже
 * два столбца — «ВІД» слева, «КОМУ» справа, — и снова строка во всю ширину. Прежнее чтение
 * склеивало соседние столбцы в одну строку: «Тарасенко Світлана Сергіївна» и «Думброван
 * Олександр» оказывались рядом, и получатель с отправителем смешивались.
 *
 * Владелец 10.08.2026, замерив разбор структуры: «структура наша боль».
 */
class ColumnsAreReadApartTest {

    private var next = 0

    private fun atom(text: String, left: Float, top: Float, right: Float, bottom: Float) =
        Atom(id = "a${next++}", text = text, box = Box(left, top, right, bottom))

    /** Наклейка: шапка, два столбца, подвал. */
    private val label = AtomLayer(
        listOf(
            atom("ОДЕСА ПОСИЛКОВИЙ", 100f, 100f, 900f, 150f),
            atom("БІЛГОРОД-ДНІСТРОВСЬКИЙ", 100f, 180f, 900f, 220f),

            atom("ВІД: 29.07/12:59", 100f, 280f, 450f, 310f),
            atom("Приватна особа", 100f, 320f, 450f, 350f),
            atom("Тарасенко Світлана Сергіївна", 100f, 360f, 450f, 390f),
            atom("м.Дніпро, Відділення №14", 100f, 400f, 450f, 430f),
            atom("067 636 05 60", 100f, 440f, 450f, 470f),

            atom("КОМУ:", 520f, 280f, 900f, 310f),
            atom("Приватна особа", 520f, 320f, 900f, 350f),
            atom("Думброван Олександр", 520f, 360f, 900f, 390f),
            atom("Миколайович", 520f, 400f, 900f, 430f),
            atom("с.Бритівка (Одеська обл.),", 520f, 440f, 900f, 470f),
            atom("Відділення №1", 520f, 480f, 900f, 510f),

            atom("Вартість дост.: 210грн.(отрим., г-ка).", 100f, 560f, 900f, 600f),
        ),
    )

    @Test
    fun `имя получателя не разрывается чужой колонкой`() {
        val text = label.text

        assertTrue(
            "получатель собрался не целиком:\n$text",
            "Думброван Олександр Миколайович" in text.replace("\n", " "),
        )
    }

    @Test
    fun `отправитель и получатель не оказываются в одной строке`() {
        label.text.lines().forEach { line ->
            assertTrue(
                "столбцы склеились в строке: «$line»",
                !(("Тарасенко" in line) && ("Думброван" in line)),
            )
        }
    }

    @Test
    fun `столбец читается сверху донизу, а потом соседний`() {
        val text = label.text

        val sender = text.indexOf("067 636 05 60")
        val receiver = text.indexOf("КОМУ:")

        assertTrue("правый столбец вклинился в левый:\n$text", sender in 0..<receiver)
    }

    @Test
    fun `шапка и подвал во всю ширину остаются на своих местах`() {
        val lines = label.text.lines().filter { it.isNotBlank() }

        assertTrue("шапка уехала вниз: $lines", lines.first().startsWith("ОДЕСА"))
        assertTrue("подвал уехал наверх", lines.last().startsWith("Вартість дост."))
    }

    @Test
    fun `одноколоночный документ читается как прежде`() {
        val plain = AtomLayer(
            listOf(
                atom("Договір", 100f, 100f, 400f, 130f),
                atom("від 12 березня", 100f, 140f, 400f, 170f),
                atom("Сторона А", 100f, 180f, 400f, 210f),
            ),
        )

        assertEquals(listOf("Договір", "від 12 березня", "Сторона А"), plain.text.lines())
    }

    @Test
    fun `ридер склеил колонки поперёк — читаем по геометрии, а не по его строке`() {
        val merged = AtomLayer(
            label.atoms,
            readerText = "ВІД: 29.07/12:59 КОМУ:\nПриватна особа Приватна особа\n" +
                "Тарасенко Світлана Сергіївна Думброван Олександр",
        )

        assertTrue(
            "склейка ридера осталась в тексте:\n${merged.text}",
            "Думброван Олександр Миколайович" in merged.text.replace("\n", " "),
        )
    }

    @Test
    fun `редкие ячейки таблицы — всё ещё одна строка, а не столбцы`() {
        val sheet = AtomLayer(
            listOf(
                atom("Артикул", 0f, 0f, 60f, 20f), atom("Наименование", 100f, 0f, 160f, 20f),
                atom("Кол", 300f, 0f, 360f, 20f),
                atom("11004", 0f, 100f, 60f, 120f), atom("Гречка", 100f, 100f, 160f, 120f),
                atom("50", 300f, 100f, 360f, 120f),
            ),
        )

        assertTrue(
            "запись рассыпалась по столбцам:\n${sheet.text}",
            "11004 Гречка 50" in sheet.text,
        )
    }

    @Test
    fun `подпись и значение в разных концах строки не разъезжаются`() {
        val form = AtomLayer(
            listOf(
                atom("ТТН", 10f, 100f, 60f, 120f), atom("20 4514 9154 9395", 200f, 100f, 380f, 120f),
                atom("Одержувач", 10f, 300f, 150f, 320f),
            ),
        )

        assertTrue(
            "номер оторвался от подписи:\n${form.text}",
            "ТТН 20 4514 9154 9395" in form.text,
        )
    }

    @Test
    fun `близкие слова одной строки не считаются столбцами`() {
        val line = AtomLayer(
            listOf(
                atom("Рахунок", 100f, 100f, 220f, 130f),
                atom("IBAN:", 240f, 100f, 320f, 130f),
                atom("UA93", 340f, 100f, 600f, 130f),
            ),
        )

        assertEquals(listOf("Рахунок IBAN: UA93"), line.text.lines())
    }
}
