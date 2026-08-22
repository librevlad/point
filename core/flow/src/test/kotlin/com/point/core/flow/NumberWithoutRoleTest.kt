package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Номер без роли — просто номер (#1032, решение владельца).
 *
 * Французское удостоверение: номер 8806923102858 из машиночитаемой зоны модель назвала
 * TRACK, и человеку показали «Накладную» с предложением отследить. У ключа было две мерки:
 * правило-читатель брало 13 цифр только с подписью рядом, модельный путь — по одной длине.
 * Теперь мерка одна: накладная — по форме перевозчика или слову рядом; иначе это «Номер»,
 * идентификатор без «отследить». Организацией становится только правдоподобная строка —
 * тем же судьёй, что у адреса: слово со смешанными алфавитами знанием не становится.
 */
class NumberWithoutRoleTest {

    private val idNumber = "8806923102858"

    @Test fun `накладная — по форме перевозчика`() {
        assertTrue("14 цифр «Новой пошты»", looksLikeTrack("20 4514 9154 9395"))
        assertTrue("склеенные 14 цифр", looksLikeTrack("20451491549395"))
        assertTrue("две группы через дробь", looksLikeTrack("5900162/7808586"))
        assertTrue("S10 с сошедшейся контрольной", looksLikeTrack("RA123456785UA"))
        assertTrue("S10 с несошедшейся контрольной остаётся треком — её судит суд кандидатов", looksLikeTrack("RA123456789UA"))
    }

    @Test fun `13 цифр — накладная только со словом рядом`() {
        assertFalse("сами по себе", looksLikeTrack(idNumber))
        assertFalse("без прочитанного текста", looksLikeTrack(idNumber, ""))
        assertTrue(looksLikeTrack(idNumber, "Номер накладної $idNumber від 12.07"))
        assertTrue("подпись на строке выше", looksLikeTrack(idNumber, "ТТН\n$idNumber"))
        assertFalse("слово на другом конце строки не считается", looksLikeTrack(idNumber, "Ваша накладна відправлена, а рахунок $idNumber сплачено"))
        assertFalse("машиночитаемая зона удостоверения", looksLikeTrack(idNumber, "IDFRABERTHIER<<<<<<<<<<<<<<<<<<\n${idNumber}CORINNE<<<<<<<<<<<"))
    }

    @Test fun `число, названное моделью накладной без подписи, приезжает номером`() {
        val parsed = parseFieldCandidates("TRACK=$idNumber", "IDFRABERTHIER<<<<<<\n${idNumber}CORINNE<<<<")

        assertNull("номер удостоверения остался накладной", parsed.fields[META_ENTITY_TRACK])
        assertEquals(idNumber, parsed.fields.getValue(META_ENTITY_SERIAL).single().text)
    }

    @Test fun `то же число с подписью накладной остаётся накладной`() {
        val parsed = parseFieldCandidates("TRACK=$idNumber", "Експрес-накладна № $idNumber")

        assertEquals(idNumber, parsed.fields.getValue(META_ENTITY_TRACK).single().text)
        assertNull(parsed.fields[META_ENTITY_SERIAL])
    }

    @Test fun `форма перевозчика не требует подписи и без текста`() {
        assertEquals("20 4514 9154 9395", parseFieldCandidates("TRACK=20 4514 9154 9395").fields.getValue(META_ENTITY_TRACK).single().text)
        assertEquals("RA123456785UA", parseFieldCandidates("TRACK=RA123456785UA").fields.getValue(META_ENTITY_TRACK).single().text)
    }

    @Test fun `слово номером не становится — оно по-прежнему никуда не идёт`() {
        val parsed = parseFieldCandidates("TRACK=квитанцію", "квитанцію")

        assertNull(parsed.fields[META_ENTITY_TRACK])
        assertNull(parsed.fields[META_ENTITY_SERIAL])
    }

    @Test fun `у номера нет действия отследить, строка зовётся номером, а не накладной`() {
        val rows = actionReadiness(mapOf(META_ENTITY_SERIAL to idNumber))

        assertTrue("номер без роли получил «отследить»", rows.none { it.schema.id == "track-parcel" })
        assertTrue(understoodName(META_ENTITY_SERIAL) != understoodName(META_ENTITY_TRACK))
        assertEquals(KIND_IDENTIFIER, ENTITY_KINDS.getValue("serial").kind)
    }

    @Test fun `организация и имя — только правдоподобная строка, тем же судьёй, что адрес`() {
        val garbled = "РÉPUBLIOUEFRANCAISE"

        assertTrue(hasMixedScriptWord(garbled))
        assertFalse("искажённая строка стала организацией", plausiblePersonName(garbled))
        assertFalse("судья адреса и судья имени расходятся", plausibleAddress("Бритовка, $garbled, 5"))

        assertTrue(plausiblePersonName("RÉPUBLIQUE FRANÇAISE"))
        assertTrue(plausiblePersonName("ТОВ «Агротрейд»"))
        assertTrue("два алфавита в разных словах — не смешение", plausiblePersonName("Іваненко Ivan"))
        assertTrue("одиночная цифра в слове — искажение, но ещё имя", plausiblePersonName("1ваненко ван"))
    }
}
