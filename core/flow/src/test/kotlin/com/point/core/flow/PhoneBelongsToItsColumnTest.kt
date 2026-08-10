package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Телефон принадлежит своему столбцу (#747).
 *
 * Владелец, разбирая кейс наклейки: «Сохранить контакт 067 636 05 60 — телефон без роли:
 * чей он, отправителя или получателя, не сказано». На наклейке это видно глазами: номер
 * стоит под именем отправителя, в его же колонке.
 */
class PhoneBelongsToItsColumnTest {

    private var next = 0

    private fun atom(text: String, left: Float, top: Float, right: Float, bottom: Float) =
        Atom(id = "a${next++}", text = text, box = Box(left, top, right, bottom))

    private val sender = atom("Тарасенко Світлана Сергіївна", 100f, 360f, 450f, 390f)
    private val phone = atom("067 636 05 60", 100f, 440f, 450f, 470f)

    private val label = AtomLayer(
        listOf(
            atom("ОДЕСА ПОСИЛКОВИЙ", 100f, 100f, 900f, 150f),

            atom("ВІД: 29.07/12:59", 100f, 280f, 450f, 310f),
            sender,
            atom("м.Дніпро, Відділення №14", 100f, 400f, 450f, 430f),
            phone,

            atom("КОМУ:", 520f, 280f, 900f, 310f),
            atom("Думброван Олександр", 520f, 360f, 900f, 390f),
            atom("с.Бритівка (Одеська обл.),", 520f, 440f, 900f, 470f),
        ),
    )

    private val names = listOf("Тарасенко Світлана Сергіївна", "Думброван Олександр")

    @Test
    fun `номер достаётся тому, в чьей колонке он стоит`() {
        val found = label.phoneOwners(listOf(FieldCandidate(phone.text, listOf(phone.id))), names)

        assertEquals(listOf(PersonContact("Тарасенко Світлана Сергіївна", "067 636 05 60")), found)
    }

    @Test
    fun `имя, названное моделью, не переписывается страницей`() {
        val named = FieldCandidate(phone.text, listOf(phone.id), person = "Думброван Олександр")

        assertEquals(emptyList<PersonContact>(), label.phoneOwners(listOf(named), names))
    }

    @Test
    fun `в блоке два имени — хозяин не назначается`() {
        val crowd = AtomLayer(
            listOf(
                atom("Іваненко Іван", 100f, 100f, 400f, 130f),
                atom("Петренко Петро", 100f, 140f, 400f, 170f),
                atom("067 636 05 60", 100f, 180f, 400f, 210f),
                atom("Сидоренко Сидір", 600f, 100f, 900f, 130f),
                atom("Відділення №1", 600f, 140f, 900f, 170f),
                atom("м.Одеса", 600f, 180f, 900f, 210f),
            ),
        )
        val number = crowd.atoms.single { it.text == "067 636 05 60" }

        val found = crowd.phoneOwners(
            listOf(FieldCandidate(number.text, listOf(number.id))),
            listOf("Іваненко Іван", "Петренко Петро", "Сидоренко Сидір"),
        )

        assertEquals(emptyList<PersonContact>(), found)
    }

    @Test
    fun `одноколоночная страница хозяина не выдумывает`() {
        val plain = AtomLayer(
            listOf(
                atom("Іваненко Іван", 100f, 100f, 400f, 130f),
                atom("067 636 05 60", 100f, 140f, 400f, 170f),
            ),
        )
        val number = plain.atoms.last()

        assertEquals(
            emptyList<PersonContact>(),
            plain.phoneOwners(listOf(FieldCandidate(number.text, listOf(number.id))), listOf("Іваненко Іван")),
        )
    }

    @Test
    fun `номер без опоры в словах страницы хозяина не получает`() {
        val guessed = FieldCandidate("067 636 05 60")

        assertEquals(emptyList<PersonContact>(), label.phoneOwners(listOf(guessed), names))
    }
}
