package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressLineTest {

    private fun ocr(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/ocr/$name.txt")) { "нет образца $name" }
            .bufferedReader().readText()

    @Test
    fun `the settlement comes back on the owner's real screenshot`() {
        val text = ocr("parcel_1")

        assertEquals("Олексйвка, вул. Сонячна, 15", expandAddressToLine("вул. Сонячна, 15", text))
    }

    @Test
    fun `trailing icons are not part of the address`() {

        val text = "Олексйвка, вул. Сонячна, 15 ©"

        assertEquals("Олексйвка, вул. Сонячна, 15", expandAddressToLine("вул. Сонячна, 15", text))
    }

    @Test
    fun `only what precedes the value is restored`() {

        val text = "Київ, вул. Хрещатик, 1 · Оплата 120.00 ₴"

        assertEquals("Київ, вул. Хрещатик, 1", expandAddressToLine("вул. Хрещатик, 1", text))
    }

    @Test
    fun `a value already whole is left alone`() {
        assertEquals("вул. Сонячна, 15", expandAddressToLine("вул. Сонячна, 15", "вул. Сонячна, 15"))
    }

    @Test
    fun `a sentence is not a settlement`() {
        val text = "Позвони мне когда доедешь до вул. Сонячна, 15"

        assertEquals("вул. Сонячна, 15", expandAddressToLine("вул. Сонячна, 15", text))
    }

    @Test
    fun `a line carrying something else entirely is not merged in`() {
        val text = "Оплата 120.00 грн, чек 4402, отримувач Іванов І. І., вул. Сонячна, 15"

        assertEquals("вул. Сонячна, 15", expandAddressToLine("вул. Сонячна, 15", text))
    }

    @Test
    fun `a value that is not in the text at all changes nothing`() {
        assertEquals("вул. Інша, 7", expandAddressToLine("вул. Інша, 7", ocr("parcel_1")))
    }

    @Test
    fun `only the value's own line is used`() {
        val text = "Одержувач\nОлексйвка, вул. Сонячна, 15"

        assertEquals("Олексйвка, вул. Сонячна, 15", expandAddressToLine("вул. Сонячна, 15", text))
    }

    @Test
    fun `blank input is returned untouched`() {
        assertEquals("  ", expandAddressToLine("  ", "что угодно"))
        assertEquals("вул. Сонячна, 15", expandAddressToLine("вул. Сонячна, 15", ""))
    }
}
