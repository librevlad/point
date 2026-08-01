package com.point.core.flow

import com.point.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that stops a waybill number from falling through the floor (#222). ML Kit reads it as
 * a phone, [isPlausible] correctly drops it as undialable, and until now nobody picked it up.
 */
class IdentifiersTest {

    @Test
    fun `finds the waybill from a real Nova Poshta screenshot, spaces and all`() {
        // The number off the parcel screen that started this whole change.
        val text = "Прибула в пункт 1, Олексіївка\n20 4514 9154 9395\nОстанній день зберігання – 29.07"

        assertEquals(listOf("20 4514 9154 9395"), waybillNumbers(text))
    }

    @Test
    fun `finds an ungrouped waybill too`() {
        assertEquals(listOf("20451491549395"), waybillNumbers("ТТН 20451491549395 прибула"))
    }

    @Test
    fun `a phone is not a waybill`() {
        // 12 digits. The two rules must not fight over the same string.
        assertTrue(waybillNumbers("тел. +380 67 123 45 67").isEmpty())
    }

    @Test
    fun `a card number is not a waybill`() {
        // 16 digits — PAYMENT_CARD has its own path and its own masking.
        assertTrue(waybillNumbers("4149 6293 1234 5678").isEmpty())
    }

    @Test
    fun `dates and small numbers are left alone`() {
        assertTrue(waybillNumbers("Invoice № 146 від 08.06.2026, 8 970.00 грн").isEmpty())
    }

    @Test
    fun `a longer digit run is not silently truncated to 14`() {
        // An account number must not be mistaken for a waybill just because it contains one.
        assertTrue(waybillNumbers("рахунок 202045149154939512").isEmpty())
    }

    @Test
    fun `the same number twice is reported once`() {
        val text = "20 4514 9154 9395 ... повторно 20 4514 9154 9395"

        assertEquals(listOf("20 4514 9154 9395"), waybillNumbers(text))
    }

    @Test
    fun `does not run across a line break into a neighbouring number`() {
        // Newlines are not grouping — «1234567\n8901234567» is two numbers, not one waybill.
        assertTrue(waybillNumbers("1234567\n8901234567").isEmpty())
    }

    @Test
    fun `blank text yields nothing`() {
        assertTrue(waybillNumbers("").isEmpty())
        assertTrue(waybillNumbers("   \n  ").isEmpty())
    }

    @Test
    fun `форма совпала, контрольной цифры нет — одна улика, а не уверенность 0_8`() {
        // Было: WAYBILL_CONFIDENCE = 0.8f — число, которое видел только граф и которое экран
        // всё равно переводил в «возможно» (#264). Стало: ровно один класс улик, и «возможно»
        // выводится из него же — утверждение сильнее прежнего, а не слабее.
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

    // --- Трек как факт (#260): схема «Отследить отправление» читает entity.track ---

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
        // design v3 §8: «трек найден, но есть второй похожий» вместо ложной однозначности.
        // Именно .more, не .alt: это второй номер на странице, а не спор о чтении первого,
        // и подтверждение первого моделью его не стирает (ревью #260).
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
        // Шапка накладной и цифры под штрихкодом: строка разная, цифры те же (ревью #260 —
        // граф склеивал их в один узел, а карточка готовности показывала ложный спор).
        val facts = trackFacts("20 4514 9154 9395\nпід штрихкодом: 20451491549395")

        assertEquals("20 4514 9154 9395", facts[META_ENTITY_TRACK])
        assertTrue(moreOf(facts, META_ENTITY_TRACK).isEmpty())
        assertEquals(listOf("20 4514 9154 9395"), waybillNumbers("20 4514 9154 9395 і 20451491549395"))
    }

    @Test
    fun `нет трека — нет ключей, а не ключ с пустотой`() {
        assertTrue(trackFacts("Позвони на +380671234567").isEmpty())
    }

    // --- Формы реальности (#262): замер корпуса поймал правило на форме уже реальности ---

    @Test
    fun `номер бумажной накладной через косую — трек, каким его печатают`() {
        // Кадр 13 корпуса (docs/CORPUS.md) — фото бумажной экспресс-накладной. Правило «ровно
        // 14 цифр подряд» было слепо к напечатанному разделителю, и единственное измеримое
        // действие кадра не ехало ни офлайн, ни после тапа в облако.
        assertEquals(
            listOf("5900162/7808586"),
            waybillNumbers("Експрес-накладна 5900162/7808586 від 12.07"),
        )
    }

    @Test
    fun `13 цифр рядом со словом-маркером — трек`() {
        // Форма Укрпошты. Допускает её соседнее слово, а не длина.
        assertEquals(listOf("0501234567890"), waybillNumbers("ТТН 0501234567890"))
        assertEquals(listOf("0501234567890"), waybillNumbers("Номер накладної 0501234567890"))
        // Знак номера и запятая соседства не отменяют — окно считает слова, а не символы.
        assertEquals(listOf("0501234567890"), waybillNumbers("Експрес-накладна № 0501234567890"))
        assertEquals(listOf("0501234567890"), waybillNumbers("Ваш номер 0501234567890, ТТН"))
    }

    @Test
    fun `у каждой допущенной формы своя цена допуска`() {
        // Четыре пути внутрь, и каждый назван: по ним видно, какая форма держится на себе, а
        // какая — на соседнем слове. Свалить их в один «нашли номер» значило бы потерять именно
        // то, чем удержана защита от ложных.
        assertEquals(TrackForm.NOVA_POSHTA, trackHits("20 4514 9154 9395").single().form)
        assertEquals(TrackForm.SPLIT, trackHits("5900162/7808586").single().form)
        assertEquals(TrackForm.MARKED, trackHits("ТТН 0501234567890").single().form)
        assertEquals(TrackForm.S10, trackHits("RA123456785UA").single().form)
    }

    @Test
    fun `13 цифр сами по себе треком не становятся`() {
        // Ровно столько же цифр у штрихкода EAN-13 на любой упаковке — форма без подтверждения
        // сделала бы треком каждую пачку печенья.
        assertTrue(waybillNumbers("4820000000001").isEmpty())
        assertTrue(waybillNumbers("Штрихкод 4820000000001").isEmpty())
    }

    @Test
    fun `слово-маркер на другом конце строки 13 цифр не допускает`() {
        // Маркер в строке есть, а номер — счёта. «Рядом» обязано значить рядом, иначе слово
        // «накладна» в шапке документа делало бы треком любое число на той же строке.
        assertTrue(
            waybillNumbers("Ваша накладна відправлена, а рахунок 1234567890123 сплачено").isEmpty(),
        )
    }

    @Test
    fun `S10 с сошедшейся контрольной цифрой — трек без всякого контекста`() {
        assertEquals(listOf("RA123456785UA"), waybillNumbers("Ваше відправлення RA123456785UA"))
        // Контрольная цифра сама себе подтверждение, поэтому слово рядом не нужно вовсе.
        assertEquals(listOf("RA123456785UA"), waybillNumbers("RA123456785UA"))
    }

    @Test
    fun `S10 с несошедшейся цифрой значением не становится, но и не исчезает`() {
        // Единственное, что правило имеет право отклонить, — математически невозможное. След
        // остаётся в том же `.blocked`, что у чтений модели, и карточка скажет «прочиталось,
        // но контрольная цифра не сошлась» вместо ложного «не нашлось» (ревью #261).
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
        // У 14-значного номера такого доказательства не существует — и он остаётся
        // предположением. Порог не смягчён, просто у одной формы появилось доказательство.
        assertTrue(isAssumption(trackFacts("ТТН 20 4514 9154 9395"), META_ENTITY_TRACK))
    }

    @Test
    fun `телефон, карта, сумма и номер счёта треком не становятся`() {
        // Главный риск расширения формы, закрытый живыми числами корпуса и фикстур устройства.
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
        // Иначе средняя пара «8901234/5678901» дала бы ровно 14 цифр — трек из ниоткуда.
        assertTrue(waybillNumbers("1234567/8901234/5678901").isEmpty())
    }

    @Test
    fun `трек с бумажной накладной становится фактом и действие едет без облака`() {
        val facts = trackFacts("Експрес-накладна 5900162/7808586")

        assertEquals("5900162/7808586", facts[META_ENTITY_TRACK])
        assertEquals(Provenance.OCR.wire, facts[META_ENTITY_TRACK + META_SOURCE_SUFFIX])
        // Улика по-прежнему одна — совпала форма, и только. Разделитель допускает номер,
        // но доказательством не становится.
        assertEquals("semantic", facts[META_ENTITY_TRACK + META_EVIDENCE_SUFFIX])
        assertTrue(
            ACTION_SCHEMAS.single { it.id == "track-parcel" }.readiness(facts) is Readiness.Ready,
        )
    }

    @Test
    fun `один номер в двух формах — один трек, а не «второй похожий»`() {
        // Шапка печатает номер через косую, штрихкод — слитно. Формы судятся врозь, но номер
        // остаётся один (тот же урок, что в ревью #260 про два написания).
        val facts = trackFacts("5900162/7808586\nпід штрихкодом 59001627808586")

        assertEquals("5900162/7808586", facts[META_ENTITY_TRACK])
        assertTrue(moreOf(facts, META_ENTITY_TRACK).isEmpty())
    }

    @Test
    fun `маркеры трека на слое и в плоском тексте не расходятся`() {
        // Два судьи одного слова — словарь атомного слоя и стемы плоского текста. Разъедутся —
        // «ТТН рядом» станет значить разное на скриншоте и в тексте, и увидеть это будет нечем.
        FIELD_MARKERS.getValue(META_ENTITY_TRACK).forEach {
            assertTrue("«$it» не узнан стемами плоского текста", looksLikeTrackMarker(it))
        }
    }
}
