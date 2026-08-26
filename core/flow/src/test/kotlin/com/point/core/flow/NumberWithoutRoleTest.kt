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
 * Теперь мерка одна — в общем гейте формы: накладная — по форме перевозчика или слову рядом;
 * иначе это «Номер», идентификатор без «отследить». Организацией становится только
 * правдоподобная строка — тем же судьёй, что у адреса: слово со смешанными алфавитами
 * знанием не становится, и правило это общее для всех словесных значений.
 */
class NumberWithoutRoleTest {

    private val idNumber = "8806923102858"

    private val garbled = "РÉPUBLIOUEFRANCAISE"

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

    @Test fun `общий гейт формы судит трек той же меркой, что правило-читатель`() {
        assertFalse("13 цифр без подписи прошли гейт", factFits(META_ENTITY_TRACK, idNumber))
        assertTrue(factFits(META_ENTITY_TRACK, idNumber, "Номер накладної $idNumber"))
        assertTrue("форма перевозчика проходит и без текста", factFits(META_ENTITY_TRACK, "20 4514 9154 9395"))
        assertFalse("форма IBAN по-прежнему не трек", factFits(META_ENTITY_TRACK, "NO93 8601 1117 947", "Рахунок NO93 8601 1117 947"))
    }

    @Test fun `улика «это трек» не расширилась вместе с гейтом ключа`() {
        // #1032: гейт ключа стал шире (14 цифр подряд, номер со словом рядом), а улика для
        // атома страницы — та же, что была. Она питает заземление значений, и метить ею
        // строго больше атомов правило владельца не разрешало.
        assertFalse("голый прогон 14 цифр стал уликой", looksLikeTrackToken("20451491549395"))
        assertFalse("номер, найденный подстрокой, стал уликой", looksLikeTrackToken("ТТН 5900162/7808586 від 12.07"))
        assertFalse("13 цифр стали уликой", looksLikeTrackToken(idNumber))
        assertTrue("атом целиком и есть номер через дробь", looksLikeTrackToken("5900162/7808586"))
        assertTrue("S10 с сошедшейся контрольной", looksLikeTrackToken("RA123456785UA"))
        assertFalse("несошедшаяся контрольная уликой не была и не стала", looksLikeTrackToken("RA123456789UA"))
    }

    @Test fun `исправление ошибок судит накладную той же меркой, что и находка`() {
        val facts = listOf(FixableFact(META_ENTITY_TRACK, "20 4514 9154 9395"))

        assertTrue("14 → 13 цифр без подписи стало накладной", parseFixes("1 = 2045149154939", facts).isEmpty())
        assertEquals("20 4514 9154 9396", parseFixes("1 = 20 4514 9154 9396", facts)[META_ENTITY_TRACK])

        // Страница на этом пути — старая: текст объекта не правится, там стоит прежнее
        // прочтение. Заземление исправленное наследует от него — иначе 13-значную накладную,
        // взятую по слову рядом, «Исправить ошибки» не починила бы никогда.
        val marked = listOf(FixableFact(META_ENTITY_TRACK, idNumber))
        assertEquals(
            "8806923102859",
            parseFixes("1 = 8806923102859", marked, "Номер накладної $idNumber від 12.07")[META_ENTITY_TRACK],
        )
        assertTrue(
            "13 цифр без слова рядом стали накладной за счёт правки",
            parseFixes("1 = 8806923102859", marked, "Рахунок $idNumber сплачено").isEmpty(),
        )
    }

    @Test fun `правка поверх правки ложится — заземление наследуется от прежнего прочтения`() {
        // Остаток того же дефекта (#1032): первая правка легла, сидекар остался прежним, и
        // прежнего прочтения на странице больше нет. Вторая дверь («Исправить сильнее», где
        // модель видит сам снимок) возвращала верное число, гейт искал его на старой странице,
        // не находил и выбрасывал правку молча — человек читал «Ошибок не нашлось».
        val page = "Експрес-накладна № $idNumber"
        val once = listOf(FixableFact(META_ENTITY_TRACK, "8806923102859", earlier = listOf(idNumber)))

        assertEquals("8806923102851", parseFixes("1 = 8806923102851", once, page)[META_ENTITY_TRACK])

        // Тот же провал у числа, которое стоит на странице не теми пробелами, что в значении:
        // заземление ищет число на странице целиком, а не дословным совпадением.
        val spaced = listOf(FixableFact(META_ENTITY_TRACK, idNumber))
        assertEquals(
            "8806923102859",
            parseFixes("1 = 8806923102859", spaced, "Експрес-накладна № 880 692 310 2858")[META_ENTITY_TRACK],
        )

        // Прочтение, которому страница не нужна вовсе (14 цифр — накладная по себе),
        // заземления не передаёт: 13 цифр без слова рядом накладной не станут и за счёт следа.
        val carrier = listOf(FixableFact(META_ENTITY_TRACK, "20 4514 9154 9395", earlier = listOf("20 4514 9154 9394")))
        assertTrue(
            "13 цифр без слова рядом стали накладной за счёт следа",
            parseFixes("1 = 2045149154939", carrier, "Накладна 20 4514 9154 9394").isEmpty(),
        )
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

    @Test fun `короткий IBAN формы трека — не накладная и не номер`() {
        // Охранники ключа track судят раньше переезда: «NO93 8601 1117 947» отбрасывался
        // целиком — так и остаётся, «Номером» счёт не становится.
        val parsed = parseFieldCandidates("TRACK=NO93 8601 1117 947", "Рахунок NO93 8601 1117 947")

        assertNull(parsed.fields[META_ENTITY_TRACK])
        assertNull("IBAN приехал номером", parsed.fields[META_ENTITY_SERIAL])
    }

    @Test fun `у номера нет действия отследить, строка зовётся номером, а не накладной`() {
        val rows = actionReadiness(mapOf(META_ENTITY_SERIAL to idNumber))

        assertTrue("номер без роли получил «отследить»", rows.none { it.schema.id == "track-parcel" })
        assertTrue(understoodName(META_ENTITY_SERIAL) != understoodName(META_ENTITY_TRACK))
        assertEquals(KIND_IDENTIFIER, ENTITY_KINDS.getValue("serial").kind)
    }

    @Test fun `два разных номера у объекта — два номера, а не спор прочтений`() {
        // Госномер от правила и номер удостоверения от модели — разные идентификаторы:
        // «номер — ещё», как у правила-читателя, а не «прочтения спорят».
        assertTrue(isMultiValueFact(META_ENTITY_SERIAL))
        assertTrue("у правила-читателя второй номер и так идёт в «ещё»", serialFacts("BH9249MT і AB1234CD").containsKey(META_ENTITY_SERIAL + META_MORE_SUFFIX))
    }

    @Test fun `организация и имя — только правдоподобная строка, тем же судьёй, что адрес`() {
        assertTrue(hasMixedScriptWord(garbled))
        assertFalse("искажённая строка стала организацией", plausiblePartyName(garbled))
        assertFalse("искажённая строка стала именем человека", plausiblePersonName(garbled))
        assertFalse("судья адреса и судья имени расходятся", plausibleAddress("Бритовка, $garbled, 5"))

        assertTrue(plausiblePartyName("RÉPUBLIQUE FRANÇAISE"))
        assertTrue("организация осталась правдоподобной стороной", plausiblePartyName("ТОВ «Агротрейд»"))
        assertTrue("два алфавита в разных словах — не смешение", plausiblePersonName("Іваненко Ivan"))
        assertTrue("одиночная цифра в слове — искажение, но ещё имя", plausiblePersonName("1ваненко ван"))
    }

    @Test fun `смешанные алфавиты — правило общее для всех словесных значений`() {
        val link = "https://uk.wikipedia.org/wiki/Київ"
        val parsed = parseFieldCandidates("PLACE=$garbled\nSUBJECT=Paris $garbled\nURL=$link")

        assertNull("место со словом из смешанных алфавитов стало знанием", parsed.fields[META_ENTITY_PLACE])
        assertNull("тема со словом из смешанных алфавитов стала знанием", parsed.fields[META_ENTITY_SUBJECT])
        assertEquals("ссылка — не слово: кириллический путь законен", link, parsed.fields.getValue(META_ENTITY_PREFIX + "url").single().text)

        assertFalse(factFits(META_ENTITY_PLACE, garbled))
        assertFalse(factFits(META_SEMANTIC_SUMMARY, "Удостоверение $garbled"))
        assertFalse(factFits(META_GRAPH_ROLE_PREFIX + "issuer", garbled))
        assertTrue(factFits(META_ENTITY_PREFIX + "url", link))
        assertTrue(factFits(META_ENTITY_PREFIX + "email", "іван@gmail.com"))
        assertTrue(factFits(META_ENTITY_PLACE, "PARIS 1ER (75)"))
    }

    @Test fun `отброшенное прочтение держит вопрос в «исследовано недостаточно», а не в «не нашлось»`() {
        val role = mapOf(META_GRAPH_ROLE_PREFIX + "issuer" + META_BLOCKED_SUFFIX to garbled)
        assertEquals(InvestigationState.INSUFFICIENTLY_INVESTIGATED, investigationOutcome(role, role.keys))

        val s10 = mapOf(META_ENTITY_TRACK + META_BLOCKED_SUFFIX to "RA123456789UA")
        assertEquals(InvestigationState.INSUFFICIENTLY_INVESTIGATED, investigationOutcome(s10, s10.keys))

        // Другие находки отвечают на вопрос — след при них вопроса не открывает.
        val withFact = s10 + (META_SEMANTIC_SUMMARY to "лист")
        assertEquals(InvestigationState.FOUND, investigationOutcome(withFact, withFact.keys))
    }

    @Test fun `след держит только свой вопрос — чужой отброшенное прочтение не открывает`() {
        // #1032: исход считается по тому, что заявило о себе само исследование. Номер,
        // отклонённый правилом-читателем офлайн, лежит в состоянии объекта — но за вопрос,
        // который его не заявлял, он не отвечает, иначе тот вечно висел бы «недостаточно».
        val foreign = mapOf(META_ENTITY_TRACK + META_BLOCKED_SUFFIX to "RA123456789UA")

        assertEquals(InvestigationState.NOT_FOUND, investigationOutcome(foreign, emptySet()))
        assertEquals(
            InvestigationState.NOT_FOUND,
            investigationOutcome(foreign + (META_READING_MODE to "PRINTED"), setOf(META_READING_MODE)),
        )
    }
}
