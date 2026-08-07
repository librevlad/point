package com.point.core.flow

import com.point.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentifiersTest {

    @Test
    fun `finds the waybill from a real Nova Poshta screenshot, spaces and all`() {

        val text = "Прибула в пункт 1, Олексіївка\n20 4514 9154 9395\nОстанній день зберігання – 29.07"

        assertEquals(listOf("20 4514 9154 9395"), waybillNumbers(text))
    }

    @Test
    fun `finds an ungrouped waybill too`() {
        assertEquals(listOf("20451491549395"), waybillNumbers("ТТН 20451491549395 прибула"))
    }

    @Test
    fun `a phone is not a waybill`() {

        assertTrue(waybillNumbers("тел. +380 67 123 45 67").isEmpty())
    }

    @Test
    fun `a card number is not a waybill`() {

        assertTrue(waybillNumbers("4149 6293 1234 5678").isEmpty())
    }

    @Test
    fun `dates and small numbers are left alone`() {
        assertTrue(waybillNumbers("Invoice № 146 від 08.06.2026, 8 970.00 грн").isEmpty())
    }

    @Test
    fun `a longer digit run is not silently truncated to 14`() {

        assertTrue(waybillNumbers("рахунок 202045149154939512").isEmpty())
    }

    @Test
    fun `the same number twice is reported once`() {
        val text = "20 4514 9154 9395 ... повторно 20 4514 9154 9395"

        assertEquals(listOf("20 4514 9154 9395"), waybillNumbers(text))
    }

    @Test
    fun `does not run across a line break into a neighbouring number`() {

        assertTrue(waybillNumbers("1234567\n8901234567").isEmpty())
    }

    @Test
    fun `blank text yields nothing`() {
        assertTrue(waybillNumbers("").isEmpty())
        assertTrue(waybillNumbers("   \n  ").isEmpty())
    }

    @Test
    fun `форма совпала, контрольной цифры нет — одна улика, а не уверенность 0_8`() {

        val facts = trackFacts("ТТН 20 4514 9154 9395 прибула")

        assertEquals(
            EvidenceClass.SEMANTIC.name.lowercase(),
            facts[META_ENTITY_TRACK + META_EVIDENCE_SUFFIX],
        )
        val ready = ACTION_SCHEMAS.single { it.id == "track-parcel" }.readiness(facts)
        assertTrue(ready is Readiness.Ready)
        assertTrue(
            "одна улика — предположение, и оно обязано быть видно как предположение",
            (ready as Readiness.Ready).present.single { it.spec.critical }.assumption,
        )
    }

    @Test
    fun `трек становится фактом объекта с происхождением «прочитано» и одной уликой формы`() {
        assertEquals(
            mapOf(
                META_ENTITY_TRACK to "20 4514 9154 9395",
                META_ENTITY_TRACK + META_SOURCE_SUFFIX to Provenance.OCR.wire,
                META_ENTITY_TRACK + META_EVIDENCE_SUFFIX to "semantic",
            ),
            trackFacts("ТТН 20 4514 9154 9395 прибула"),
        )
    }

    @Test
    fun `второй настоящий номер не прячется — все номера в more`() {

        val facts = trackFacts("20 4514 9154 9395 та 20451491549396")

        assertEquals("20 4514 9154 9395", facts[META_ENTITY_TRACK])
        assertEquals(
            listOf("20 4514 9154 9395", "20451491549396"),
            moreOf(facts, META_ENTITY_TRACK),
        )
        assertTrue(alternativesOf(facts, META_ENTITY_TRACK).isEmpty())
    }

    @Test
    fun `один номер в двух написаниях — один трек, а не «второй похожий»`() {

        val facts = trackFacts("20 4514 9154 9395\nпід штрихкодом: 20451491549395")

        assertEquals("20 4514 9154 9395", facts[META_ENTITY_TRACK])
        assertTrue(moreOf(facts, META_ENTITY_TRACK).isEmpty())
        assertEquals(listOf("20 4514 9154 9395"), waybillNumbers("20 4514 9154 9395 і 20451491549395"))
    }

    @Test
    fun `нет трека — нет ключей, а не ключ с пустотой`() {
        assertTrue(trackFacts("Позвони на +380671234567").isEmpty())
    }

    @Test
    fun `номер бумажной накладной через косую — трек, каким его печатают`() {

        assertEquals(
            listOf("5900162/7808586"),
            waybillNumbers("Експрес-накладна 5900162/7808586 від 12.07"),
        )
    }

    @Test
    fun `13 цифр рядом со словом-маркером — трек`() {

        assertEquals(listOf("0501234567890"), waybillNumbers("ТТН 0501234567890"))
        assertEquals(listOf("0501234567890"), waybillNumbers("Номер накладної 0501234567890"))

        assertEquals(listOf("0501234567890"), waybillNumbers("Експрес-накладна № 0501234567890"))
        assertEquals(listOf("0501234567890"), waybillNumbers("Ваш номер 0501234567890, ТТН"))
    }

    @Test
    fun `у каждой допущенной формы своя цена допуска`() {

        assertEquals(TrackForm.NOVA_POSHTA, trackHits("20 4514 9154 9395").single().form)
        assertEquals(TrackForm.SPLIT, trackHits("5900162/7808586").single().form)
        assertEquals(TrackForm.MARKED, trackHits("ТТН 0501234567890").single().form)
        assertEquals(TrackForm.S10, trackHits("RA123456785UA").single().form)
    }

    @Test
    fun `подпись строкой выше — тот же сосед, что подпись перед номером`() {

        val text = "ТОВ НОВА ПОШТА\nЕКСПРЕС-НАКЛАДНА №\n5900162780858\nДАТА ОФОРМЛЕННЯ ЧТ 16.04.2026"

        assertEquals(listOf("5900162780858"), waybillNumbers(text))

        assertEquals(TrackForm.MARKED, trackHits(text).single().form)
    }

    @Test
    fun `номер через косую читается одинаково с подписью строкой выше и без неё`() {

        val page = "ТОВ НОВА ПОШТА\nЕКСПРЕС-НАКЛАДНА №\n5900162/7808586\nДАТА ОФОРМЛЕННЯ ЧТ 16.04.2026"
        val noLabel = "ТОВ НОВА ПОШТА\n5900162/7808586\nДАТА ОФОРМЛЕННЯ ЧТ 16.04.2026"

        assertEquals(listOf("5900162/7808586"), waybillNumbers(page))
        assertEquals(waybillNumbers(page), waybillNumbers(noLabel))
        assertEquals(TrackForm.SPLIT, trackHits(page).single().form)
    }

    @Test
    fun `подпись строкой ниже — тоже сосед`() {

        assertEquals(listOf("0501234567890"), waybillNumbers("0501234567890\nТТН"))
    }

    @Test
    fun `через чужую строку соседство не тянется`() {

        assertTrue(
            waybillNumbers("Експрес-накладна №\nвiд 16.04.2026\n5900162780858").isEmpty(),
        )
    }

    @Test
    fun `13 цифр сами по себе треком не становятся`() {

        assertTrue(waybillNumbers("4820000000001").isEmpty())
        assertTrue(waybillNumbers("Штрихкод 4820000000001").isEmpty())
    }

    @Test
    fun `слово-маркер на другом конце строки 13 цифр не допускает`() {

        assertTrue(
            waybillNumbers("Ваша накладна відправлена, а рахунок 1234567890123 сплачено").isEmpty(),
        )
    }

    @Test
    fun `S10 с сошедшейся контрольной цифрой — трек без всякого контекста`() {
        assertEquals(listOf("RA123456785UA"), waybillNumbers("Ваше відправлення RA123456785UA"))

        assertEquals(listOf("RA123456785UA"), waybillNumbers("RA123456785UA"))
    }

    @Test
    fun `S10 с несошедшейся цифрой значением не становится, но и не исчезает`() {

        val facts = trackFacts("Відправлення RA123456789UA")

        assertNull(facts[META_ENTITY_TRACK])
        assertEquals(
            listOf("RA123456789UA"),
            facts[META_ENTITY_TRACK + META_BLOCKED_SUFFIX]?.split("\n"),
        )
        assertTrue(waybillNumbers("RA123456789UA").isEmpty())
    }

    @Test
    fun `контрольная цифра — второй класс улик, и «возможно» у S10 честно исчезает`() {
        val facts = trackFacts("Відправлення RA123456785UA")

        assertEquals("semantic,arithmetic", facts[META_ENTITY_TRACK + META_EVIDENCE_SUFFIX])
        assertFalse(isAssumption(facts, META_ENTITY_TRACK))

        assertTrue(isAssumption(trackFacts("ТТН 20 4514 9154 9395"), META_ENTITY_TRACK))
    }

    @Test
    fun `телефон, карта, сумма и номер счёта треком не становятся`() {

        listOf(
            "тел. +380 67 123 45 67",
            "+38 098 928 1316",
            "Картка 4149 6293 1234 5678",
            "5169 3351 0965 2632",
            "Сума до сплати 8 970.00 грн",
            "рахунок 202045149154939512",
            "IBAN UA213223130000026007233566001",
            "Накладна від 08.06.2026 на суму 1 234.56",
            "Договір 12/2026 від 01/08/2026",
        ).forEach { assertTrue("«$it» треком быть не должно", waybillNumbers(it).isEmpty()) }
    }

    @Test
    fun `цепочка чисел через косую на пары не разбирается`() {

        assertTrue(waybillNumbers("1234567/8901234/5678901").isEmpty())

        assertTrue(waybillNumbers("1234567 / 8901234 / 5678901").isEmpty())
        assertTrue(waybillNumbers("1234567/8901234 / 5678901").isEmpty())

        assertEquals(listOf("5900162 / 7808586"), waybillNumbers("Накладна 5900162 / 7808586"))
    }

    @Test
    fun `несошедшаяся цифра без слова рядом каналом «прочиталось» не пользуется`() {

        assertTrue(trackFacts("Артикул QQ111111111ZZ").isEmpty())
        assertTrue(trackFacts("ID AB123456789 CD").isEmpty())

        assertEquals(
            "RA123456789UA",
            trackFacts("ТТН RA123456789UA")[META_ENTITY_TRACK + META_BLOCKED_SUFFIX],
        )
    }

    @Test
    fun `трек с бумажной накладной становится фактом и действие едет без облака`() {
        val facts = trackFacts("Експрес-накладна 5900162/7808586")

        assertEquals("5900162/7808586", facts[META_ENTITY_TRACK])
        assertEquals(Provenance.OCR.wire, facts[META_ENTITY_TRACK + META_SOURCE_SUFFIX])

        assertEquals("semantic", facts[META_ENTITY_TRACK + META_EVIDENCE_SUFFIX])
        assertTrue(
            ACTION_SCHEMAS.single { it.id == "track-parcel" }.readiness(facts) is Readiness.Ready,
        )
    }

    @Test
    fun `один номер в двух формах — один трек, а не «второй похожий»`() {

        val facts = trackFacts("5900162/7808586\nпід штрихкодом 59001627808586")

        assertEquals("5900162/7808586", facts[META_ENTITY_TRACK])
        assertTrue(moreOf(facts, META_ENTITY_TRACK).isEmpty())
    }

    @Test
    fun `маркеры трека на слое и в плоском тексте не расходятся`() {

        FIELD_MARKERS.getValue(META_ENTITY_TRACK).forEach {
            assertTrue("«$it» не узнан стемами плоского текста", looksLikeTrackMarker(it))
        }
    }
}
