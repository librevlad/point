package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ответ сервера — чужой текст, и падать на нём нельзя (#472). Поэтому здесь проверяется не только
 * «разобрал правильный JSON», но и «на обрубке вернул `null`, а не исключение»: клиент читает
 * то, что пришло по сети, и оборванный ответ бывает чаще, чем хочется.
 */
class JsonTest {

    private val BACKSLASH = "\\"

    @Test fun `плоский ответ входа читается по полям`() {
        val json = parseJson("""{"login_id":"a1","code":"K7-42Q","url":"https://p/login?d=a1"}""")
        assertEquals("a1", json.str("login_id"))
        assertEquals("K7-42Q", json.str("code"))
        assertEquals("https://p/login?d=a1", json.str("url"))
        assertNull(json.str("отсутствует"))
    }

    @Test fun `круг устройств читается массивом объектов`() {
        val json = parseJson(
            """{"devices":[
                {"id":"d1","kind":"PHONE","name":"Pixel","last_seen":1700000000000,"self":true},
                {"id":"d2","kind":"PC","name":"Ноутбук","last_seen":null,"self":false}
            ]}""",
        )
        val devices = json.array("devices")
        assertEquals(2, devices.size)
        assertEquals("Pixel", devices[0].str("name"))
        assertEquals(1700000000000L, devices[0].long("last_seen"))
        assertEquals(true, devices[0].bool("self"))
        // `null` в поле — это «неизвестно», и читаться оно обязано как отсутствие, а не как ноль.
        assertNull(devices[1].long("last_seen"))
    }

    @Test fun `экранирование переживает кавычки и перевод строки`() {
        val text = jsonObject("name" to "Мой \"ПК\"\nдома")
        assertEquals("""{"name":"Мой \"ПК\"\nдома"}""", text)
        assertEquals("Мой \"ПК\"\nдома", parseJson(text).str("name"))
    }

    @Test fun `обрубок и мусор дают null, а не падение`() {
        assertNull(parseJson("""{"a":"""))
        assertNull(parseJson("""{"a":1,}"""))
        assertNull(parseJson("не json вовсе"))
        assertNull(parseJson(""))
        assertNull(parseJson("""{"a":1} лишнее"""))
    }

    @Test fun `экранированные символы разбираются обратно`() {
        val escaped = "{" + "\"v\"" + ":" + "\"таб" + BACKSLASH + "tи" + BACKSLASH + "u0416\"" + "}"
        assertEquals("таб	иЖ", parseJson(escaped).str("v"))
    }

    @Test fun `пустые объект и массив — законные ответы`() {
        assertTrue(parseJson("{}") is JsonValue.Obj)
        assertEquals(emptyList<JsonValue>(), parseJson("""{"devices":[]}""").array("devices"))
    }
}
