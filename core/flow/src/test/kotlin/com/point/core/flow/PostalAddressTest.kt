package com.point.core.flow

import com.point.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostalAddressTest {

    private fun ocr(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/ocr/$name.txt")) { "нет образца $name" }
            .bufferedReader().readText()

    @Test
    fun `адрес отделения с кадра карты наконец собирается`() {

        assertEquals(listOf("Бритвка, Центральна, 586"), addressLines(ocr("parcel_3")))
    }

    @Test
    fun `адрес с кадра карты становится фактом с происхождением и одной уликой`() {
        val facts = addressFacts(ocr("parcel_3"))

        assertEquals("Бритвка, Центральна, 586", facts[META_ENTITY_ADDRESS])

        assertEquals(Provenance.OCR.wire, facts[META_ENTITY_ADDRESS + META_SOURCE_SUFFIX])

        assertEquals("semantic", facts[META_ENTITY_ADDRESS + META_EVIDENCE_SUFFIX])
        assertTrue(isAssumption(facts, META_ENTITY_ADDRESS))
    }

    @Test
    fun `прочитанный адрес закрывает требование «куда» у маршрута`() {

        val facts = addressFacts(ocr("parcel_3"))

        val route = ACTION_SCHEMAS.single { it.id == "route" }.readiness(facts)

        assertTrue("маршрут по кадру 12 обязан быть готов", route is Readiness.Ready)
    }

    @Test
    fun `адрес отделения на скриншоте посылки читается тем же правилом`() {

        assertEquals(listOf("Олексйвка, вул. Сонячна, 15"), addressLines(ocr("parcel_1")))
        assertEquals(listOf("Олексйвка, вул. Сонячна, 15"), addressLines(ocr("parcel_2")))
    }

    @Test
    fun `переписка, ведомость и экран посылки без адреса адресов не рождают`() {

        listOf("neg_viber", "neg_whatsapp", "ledger_23", "parcel_4").forEach { name ->
            assertEquals("«$name» адреса не содержит", emptyList<String>(), addressLines(ocr(name)))
        }
    }

    @Test
    fun `нет адреса — нет ключей, а не ключ с пустотой`() {
        assertTrue(addressFacts(ocr("neg_viber")).isEmpty())
        assertNull(addressFacts("").get(META_ENTITY_ADDRESS))
    }

    @Test
    fun `населённый пункт с областью — тоже адрес`() {
        assertEquals(
            listOf("Новониколаевка, Запорожская обл."),
            addressLines("Мое местоположение\nНовониколаевка, Запорожская обл.\nАвтомобиль"),
        )
        assertEquals(AddressForm.AREA, addressForm("Кринички, Днiпропетровська область"))
        assertEquals(AddressForm.AREA, addressForm("Вiльнянськ, Запорiзький р-н"))
    }

    @Test
    fun `обрезанную экраном строку правило не достраивает`() {

        assertTrue(addressLines("Новониколаевка, Запорожская о...").isEmpty())
    }

    @Test
    fun `слово административной единицы считается целым, а не куском`() {

        assertNull(addressForm("Приезжай, я в нашем районе"))
        assertNull(addressForm("Округлил, район 300 грн"))
    }

    @Test
    fun `у каждой допущенной формы своя цена допуска`() {
        assertEquals(AddressForm.STREET, addressForm("Бритвка, Центральна, 586"))
        assertEquals(AddressForm.STREET, addressForm("Олексйвка, вул. Сонячна, 15"))
        assertEquals(AddressForm.STREET, addressForm("вул. Сонячна, 15"))
        assertEquals(AddressForm.AREA, addressForm("Новониколаевка, Запорожская обл."))
    }

    @Test
    fun `улику адреса считает та же функция, что судит чтение модели`() {

        assertEquals(
            setOf(EvidenceClass.SEMANTIC),
            formEvidence(META_ENTITY_ADDRESS, "Бритвка, Центральна, 586"),
        )

        assertNull(semanticFits(META_ENTITY_ADDRESS, "м. Павлоград, вул. Кодацька, 39, Днiпропетровська обл."))
    }

    @Test
    fun `одного названия перед числом мало`() {

        assertNull(addressForm("Паринкн Виктор, 300"))
        assertNull(addressForm("Спасибо, 300"))
    }

    @Test
    fun `предложение с запятыми адресом не становится`() {
        assertNull(addressForm("Если вас не затруднит, сбросьте разницу на карту"))
        assertNull(addressForm("Оплата 120.00 грн, чек 4402, отримувач Іванов І. І., вул. Сонячна, 15"))
        assertNull(addressForm("Прибула в пункт 2, Михайло-"))
    }

    @Test
    fun `длинная строка адресом не считается`() {

        val long = "Александровскоеказачье Первое Поселение, Краснознаменнаяпобедная " +
            "Большая Улица, Новониколаевский Заповедный Округ"

        assertTrue(long.length > 96)
        assertNull(addressForm(long))
        assertTrue(addressLines(long).isEmpty())
    }

    @Test
    fun `строка таблицы, прайса и чека адресом не становится`() {

        listOf(
            "Цукор, пачка, 25",
            "Хлб, молоко, 120",
            "Оплата, чек, 4402",
            "Разом, до сплати, 250",
            "Сума, грн, 1000",
            "Дата, номер, 2026",
        ).forEach { assertNull("«$it» — не адрес", addressForm(it)) }
    }

    @Test
    fun `у части со строчной буквы допуск держит слово улицы`() {

        assertEquals(AddressForm.STREET, addressForm("м. Павлоград, вул. Кодацька, 15"))
        assertEquals(AddressForm.STREET, addressForm("Олексйвка, вул. Сонячна, 15"))
        assertEquals(AddressForm.STREET, addressForm("вул. Сонячна, 15"))

        assertEquals(AddressForm.STREET, addressForm("Бритвка, Центральна, 586"))
    }

    @Test
    fun `второй адрес страницы не исчезает`() {

        val facts = addressFacts("Бритвка, Центральна, 586\nОлексйвка, вул. Сонячна, 15")

        assertEquals("Бритвка, Центральна, 586", facts[META_ENTITY_ADDRESS])
        assertEquals(
            listOf("Бритвка, Центральна, 586", "Олексйвка, вул. Сонячна, 15"),
            moreOf(facts, META_ENTITY_ADDRESS),
        )
    }
}
