package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the document engine, run against **what the device actually produces**.
 *
 * Every other test in this engine feeds it text a human typed. That is how three of the four
 * things шаг 3–5 promised turned out not to happen on a real parcel screenshot: the vocabulary
 * was written the way Ukrainian is spelled, and Tesseract does not spell it that way.
 *
 * The fixtures in `src/test/resources/ocr` are verbatim OCR output pulled off a Samsung A34 on
 * 2026-07-30 — four Nova Poshta screens and two chats. Nothing in them is cleaned up; the
 * mangling (`Мсця`, `Вдчинено`, `Micue`) is the point.
 *
 * **`chat_calc` и `receipt_paper` — обезличены** (#262, кадры 03 и 20, прогон 03.08.2026). Это
 * платёж и переписка владельца, а правило корпуса на образцы распространяется целиком: имя,
 * номер карты, IBAN, номер квитанции и код авторизации заменены на подставные **той же формы**.
 * Порча движка не тронута ни в одном знаке — она и есть предмет проверки: «Квитанція» пришла как
 * `Квитанщя`, время сообщения — как `4...` сразу за итогом расчёта.
 */
class RealOcrTest {

    private fun ocr(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/ocr/$name.txt")) { "нет образца $name" }
            .bufferedReader().readText()

    private val parcels = listOf("parcel_1", "parcel_2", "parcel_3", "parcel_4")
    private val notParcels = listOf("neg_viber", "neg_whatsapp")

    /** Номер, который в `neg_viber` стоит своей строкой, а подписан «ТТН» строкой ниже. */
    private val numberInViber = "20451491549395"

    // --- Тип документа (#222, шаг 5) ---

    @Test
    fun `every real parcel screen is recognised as a parcel`() {
        parcels.forEach { name ->
            assertEquals("«$name» должен быть посылкой", TYPE_PARCEL, documentType(ocr(name)))
        }
    }

    @Test
    fun `a chat is not a parcel, even when a waybill number is in it`() {
        // neg_viber is a conversation about a shipment: the ТТН is genuinely there and must be
        // extracted, but calling the chat itself «Посылка» would be exactly the confident lie
        // the rule exists to avoid.
        notParcels.forEach { name ->
            assertNull("«$name» не посылка", documentType(ocr(name)))
        }
    }

    @Test
    fun `the vocabulary matched nothing before folding away the letter OCR eats`() {
        // The regression that started all this: `відділення` comes back as `вддлення`, `місце`
        // as `мсце`. Spelled-correctly markers scored zero on all four real screens.
        val spelled = listOf("відділення", "місце доставки", "зберігання")

        parcels.forEach { name ->
            val raw = ocr(name).lowercase()
            assertTrue(
                "«$name»: если OCR вдруг стал писать «і», свёртку можно упрощать",
                spelled.none { it in raw },
            )
        }
    }

    // --- Номер накладной (#222, шаг 3) ---

    @Test
    fun `the waybill is found on the screens that carry one`() {
        assertEquals(listOf("20451491549395"), waybillNumbers(ocr("parcel_1")))
        assertEquals(listOf("20 4514 9154 9395"), waybillNumbers(ocr("parcel_2")))
        assertEquals(listOf("20 4514 0308 6865"), waybillNumbers(ocr("parcel_4")))
    }

    @Test
    fun `a waybill quoted in a chat is still a waybill`() {
        assertEquals(listOf("20451491549395"), waybillNumbers(ocr("neg_viber")))
    }

    @Test
    fun `a screen without a number yields none`() {
        // parcel_3 is the map view — no number on it, and the OCR of it is barely readable.
        assertTrue(waybillNumbers(ocr("parcel_3")).isEmpty())
        assertTrue(waybillNumbers(ocr("neg_whatsapp")).isEmpty())
    }

    @Test
    fun `a card number in a chat is not mistaken for a waybill`() {
        // neg_viber also contains «5169 3351 0965 2632» — sixteen digits, a card.
        assertTrue(waybillNumbers(ocr("neg_viber")).none { it.filter(Char::isDigit).length != 14 })
    }

    /**
     * Подпись трека стоит на СОСЕДНЕЙ строке — и это не выдумка, а вёрстка реального экрана
     * (#262, кадр 13). В дословном выводе устройства номер занимает строку целиком, а «ТТН»
     * подписывает его строкой ниже; на бумажной экспресс-накладной кадра 13 подпись стоит,
     * наоборот, строкой выше. Окно соседства, считавшее только свою строку, было слепо к обоим.
     */
    @Test
    fun `слово-маркер на соседней строке — сосед номера`() {
        val text = ocr("neg_viber")
        val at = text.indexOf(numberInViber).let { it until it + numberInViber.length }

        assertTrue("«ТТН» стоит соседней строкой и обязано считаться соседом", markerNear(text, at))
        // Почему прежнее правило этого не видело: на своей строке у номера соседей нет вовсе.
        assertEquals(numberInViber, text.lineSequence().first { numberInViber in it }.trim())
    }

    @Test
    fun `ведомость владельца номеров отправлений не рождает`() {
        // Расширенное окно соседства не имеет права начать находить треки в символьной каше.
        assertTrue(waybillNumbers(ocr("ledger_23")).isEmpty())
    }

    // --- Деньги и квитанция (#262): проверка на дословном выводе устройства ---

    /**
     * Схема «Перевести по реквизитам» считается суммой, и вот как эта сумма выглядит **на самом
     * деле**: в переписке владельца цена и сумма к переводу стоят разными пузырями, а валюта у
     * первой из них уехала на следующую строку — вёрстка, а не смысл. Правило, требующее пробела
     * между числом и валютой, на живом кадре прочло бы только вторую.
     */
    @Test
    fun `суммы переписки читаются дословным выводом устройства, а карта суммой не становится`() {
        val amounts = moneyAmounts(ocr("neg_viber"))

        // Ровно две суммы и ровно эти: значит шестнадцать цифр карты, стоящие двумя строками
        // выше, не отдали правилу ни одного обрезка.
        assertEquals(listOf("320", "300"), amounts.map { it.value })
        assertEquals(listOf("грн", "грн"), amounts.map { it.currency })
    }

    @Test
    fun `экран посылки и ведомость денег не рождают`() {
        // Цена ошибки здесь — карточка «Перевести по реквизитам» на чужом документе.
        (parcels + "ledger_23").forEach { name ->
            assertTrue("«$name» не про деньги", amountFacts(ocr(name)).isEmpty())
        }
    }

    /**
     * Кадр 03 корпуса: два прибора учёта, расчёт вслух и пересланная карта. Знака гривны на
     * странице нет ни одного, а сумма к переводу напечатана — итогом второго расчёта. Прогон
     * 03.08.2026 объявил кадр несправившимся именно здесь: карта прочитана, сумма — нет.
     */
    @Test
    fun `сумма перевода читается там, где валюты на странице нет вовсе`() {
        val facts = amountFacts(ocr("chat_calc"))

        assertEquals("1048,64", facts[META_ENTITY_AMOUNT])
        assertEquals("arithmetic", facts[META_ENTITY_AMOUNT + META_EVIDENCE_SUFFIX])
        // Промежуточный итог не потерян и не назначен ответом.
        assertEquals(altValue(listOf("1048,64", "548,64")), facts[META_ENTITY_AMOUNT + META_MORE_SUFFIX])
    }

    @Test
    fun `порченые часы переписки суммами не становятся`() {
        // Движок отдал таймстемпы как «49.45», «59.55», «15.55», «30.0,» — четыре числа с
        // дробной частью на той же странице, что и настоящий расчёт. Ни одно из них суммой не
        // стало: сумму назначает сошедшийся расчёт, а не форма числа.
        val text = ocr("chat_calc")

        assertEquals(listOf("1048,64", "548,64"), arithmeticTotals(text))
        // И валютного чтения на этой странице нет вовсе — знака гривны движок не отдал ни одного.
        assertTrue(moneyAmounts(text).isEmpty())
    }

    @Test
    fun `на странице квитанции читается ровно один номер`() {
        // Кадр 20: числа на листе повсюду — IBAN, карта, код банка, код авторизации, — и все
        // они рядом со своими подписями. Номером квитанции становится только тот, у кого рядом
        // стоит слово о квитанции, каким бы движок его ни отдал.
        val text = ocr("receipt_paper")

        assertEquals(listOf("AB12-CD34-EF56-GH78"), receiptNumbers(text))
        assertEquals("500.00", amountFacts(text)[META_ENTITY_AMOUNT])
        assertEquals("грн", amountFacts(text)[META_ENTITY_AMOUNT_CURRENCY])
    }

    @Test
    fun `квитанция посылкой не становится`() {
        // На листе стоит «Одержувач» — слово словаря доставки. Одного слова мало, и номера
        // отправления на квитанции нет: ни IBAN, ни номер карты треком не притворяются.
        val text = ocr("receipt_paper")

        assertNull(documentType(text))
        assertTrue(waybillNumbers(text).isEmpty())
    }

    @Test
    fun `номеров квитанций на этих кадрах нет ни одного`() {
        // Слова «квитанція» нет ни на одном из них — значит и допуска нет, как бы ни выглядели
        // числа. Правило молчит там, где документ о себе ничего не сказал.
        (parcels + notParcels + "ledger_23").forEach { name ->
            assertTrue("«$name» не квитанция", receiptFacts(ocr(name)).isEmpty())
        }
    }

    // --- Часы статус-бара (#233) ---

    @Test
    fun `the status bar is dropped from every screenshot that has one`() {
        listOf("parcel_1", "parcel_2", "parcel_4", "neg_viber", "neg_whatsapp").forEach { name ->
            val raw = ocr(name)
            val clean = stripStatusBar(raw)

            assertTrue("«$name»: строка статус-бара должна исчезнуть", clean.length < raw.length)
            assertTrue(
                "«$name»: время из статус-бара не должно остаться первой строкой",
                !Regex("""^\s*\d{1,2}[:.]\d{2}""").containsMatchIn(clean.lineSequence().first()),
            )
        }
    }

    @Test
    fun `text that merely starts with a time keeps it`() {
        // The rule must not eat a real line. «15:12 Встреча с Петром» is content.
        val note = "15:12 Встреча с Петром\nвторой этаж"

        assertEquals(note, stripStatusBar(note))
    }

    @Test
    fun `a screen whose OCR lost the clock entirely is left alone`() {
        // parcel_3's first line is unreadable noise — nothing to strip, and nothing to guess at.
        assertEquals(ocr("parcel_3"), stripStatusBar(ocr("parcel_3")))
    }

    @Test
    fun `stripping never empties the text`() {
        (parcels + notParcels).forEach { name ->
            assertTrue("«$name» не должен опустеть", stripStatusBar(ocr(name)).isNotBlank())
        }
    }

    // --- Что теперь увидит владелец на своём скриншоте ---

    @Test
    fun `the owner's parcel screenshot yields a name, a number and no phone clock`() {
        val text = stripStatusBar(ocr("parcel_1"))

        assertEquals(TYPE_PARCEL, documentType(text))
        assertEquals(listOf("20451491549395"), waybillNumbers(text))
        assertNotNull(documentLabel(documentType(text)))
        assertTrue("часы 15:12 больше не входят в текст", !text.contains("15:12"))
    }

    // --- Продовольственная ведомость владельца (#294) ---

    /**
     * `ledger_23.txt` — дословный вывод движка на эталонном кадре владельца (печатный бланк
     * воинской части, фото 4000×3000, 427 слов): символьная каша `3}3/9|=|=|=|=|-(8}-|8)`.
     * Порог [promptIndex] она проходит — букв и цифр в ней 0,7 от знаков, — поэтому индекс
     * строится, модель отвечает дословно, и гейт диктовки судит каждое её число страницей,
     * которая ни одного из них не содержит. В живом прогоне так и вышло — 0 подтверждений из
     * 384 чисел и 385 помеченных ячеек из 448 непустых, то есть пометка перестала что-либо
     * сообщать.
     */
    @Test
    fun `страница-каша не судит числа ведомости`() {
        val page = AtomLayer(emptyList(), ocr("ledger_23"))
        val ledger = listOf(
            listOf("11004", "6,003", "0,522", "2,088", "0,173", "2,871", "0,261", "1,305"),
            listOf("11006", "0,883", "0,077", "0,307", "0,038", "0,422", "0,038", "0,192"),
            listOf("11008", "1,994", "0,173", "0,694", "0,038", "0,954", "0,087", "0,434"),
        ).map { row -> row.map { CellAnswer.Literal(it) } }

        val table = page.resolveCells(ledger)

        assertTrue("пометки нет ни на одном числе", table.rows.flatten().none { it.contains("⚠") })
        // И это именно правило свидетеля, а не подтверждение страницей: то же число в одиночку
        // (выборки нет — судить нечем) страница по-прежнему бракует как диктовку.
        val alone = page.resolveCells(listOf(listOf(CellAnswer.Literal("6,003"))))
        assertEquals("6,003⚠", alone.rows[0][0])
    }
}
