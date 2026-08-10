package com.point.core.flow

import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityInventoryTest {

    private class Cap(
        id: String,
        private val out: (ObjectState) -> ObjectState?,
        private val yield: ((ObjectState) -> ActionYield)? = null,
        private val takes: (ObjectState) -> Boolean = { true },
        override val meta: CapabilityMeta = CapabilityMeta(),
    ) : Capability {
        override val id = CapabilityId(id)
        override val icon = "x"
        override fun label(state: ObjectState) = "тест"
        override fun accepts(state: ObjectState) = takes(state)
        override fun produces(state: ObjectState) = out(state)
        override fun yields(state: ObjectState) = yield?.invoke(state) ?: super.yields(state)
    }

    private val image = ObjectState(ObjectKind.IMAGE)

    @Test
    fun `терминальное действие возвращает свой аргумент — значит не вернёт ничего`() {
        val share = Cap("share", out = { it })

        assertEquals(ActionYield.None, share.yields(image))
    }

    @Test
    fun `превращение в свой же вид — это новый объект, а не терминал`() {

        val scan = Cap("scan", out = { ObjectState(ObjectKind.IMAGE) })

        assertEquals(ActionYield.New(ObjectKind.IMAGE), scan.yields(image))
    }

    @Test
    fun `неизвестный выход остаётся неизвестным`() {
        val ai = Cap("ai", out = { null })

        assertEquals(ActionYield.Unknown, ai.yields(image))
    }

    @Test
    fun `способность вправе сказать о себе точнее, чем умолчание`() {

        val understand = Cap("understand", out = { it }, yield = { ActionYield.Same })

        assertEquals(ActionYield.Same, understand.yields(image))
        assertEquals(ActionYield.None, derivedYield(understand, image))
    }

    @Test
    fun `вернёт текст — так и написано`() {
        assertEquals("вернёт текст", yieldLabel(ActionYield.New(ObjectKind.TEXT), Intent.UNDERSTAND))
    }

    @Test
    fun `вид OFFICE слишком широк — уточнение способности выигрывает`() {

        assertEquals("вернёт таблицу", yieldLabel(ActionYield.New(ObjectKind.OFFICE, "таблицу"), Intent.PREPARE))
        assertEquals("вернёт документ", yieldLabel(ActionYield.New(ObjectKind.OFFICE), Intent.PREPARE))
    }

    @Test
    fun `копирование ничего не дописывает — имя уже сказало всё (#629)`() {

        assertNull(yieldLabel(ActionYield.Copied, Intent.SEND))
    }

    @Test
    fun `подпись «Понять» называет результат, а не механику`() {

        val label = yieldLabel(ActionYield.Same, Intent.UNDERSTAND)!!

        assertFalse("подпись снова про механику: " + label, label.contains("объект тот же"))
        assertTrue("подпись не называет ничего из того, что человек получит: " + label,
            listOf("суть", "суммы", "даты", "контакты").any { label.contains(it) })
    }

    @Test
    fun `у терминального действия второй строки нет, если ей нечего добавить (#629)`() {

        // Решение владельца: у «Сохранить», «Поделиться», «Напечатать» имя уже описывает
        // работу — одинаковая подпись под шестью действиями подряд не добавляла ничего.
        assertNull(yieldLabel(ActionYield.None, Intent.SEND))

        // А там, где подписи есть что сказать, она остаётся: куда уйдёт и где покажется.
        assertEquals("откроет в другом приложении", yieldLabel(ActionYield.None, Intent.OPEN))
        assertEquals("покажет здесь же", yieldLabel(ActionYield.None, Intent.UNDERSTAND))
    }

    @Test
    fun `негодный объект — подпись становится причиной, а не обещанием`() {

        val label = yieldLabel(ActionYield.Same, Intent.UNDERSTAND, unusableReason = "Файл пустой — в нём нечего читать")

        assertEquals("Файл пустой — в нём нечего читать", label)
    }

    @Test
    fun `причина перекрывает любой исход — New, Copied, Unknown, None`() {
        val reason = "Файл не открылся — он повреждён или это не изображение"

        assertEquals(reason, yieldLabel(ActionYield.New(ObjectKind.TEXT), Intent.UNDERSTAND, reason))
        assertEquals(reason, yieldLabel(ActionYield.Copied, Intent.SEND, reason))
        assertEquals(reason, yieldLabel(ActionYield.Unknown, Intent.UNDERSTAND, reason))
        assertEquals(reason, yieldLabel(ActionYield.None, Intent.OPEN, reason))
    }

    @Test
    fun `без причины подпись работает как раньше`() {
        assertEquals("найдёт суть, суммы, даты и контакты", yieldLabel(ActionYield.Same, Intent.UNDERSTAND))
    }

    @Test
    fun `у каждого исхода есть свои слова, и они не пустые`() {
        val all = listOf(
            ActionYield.None, ActionYield.Same, ActionYield.Unknown,
        ) + ObjectKind.entries.map { ActionYield.New(it) }
        val said = all.flatMap { y -> Intent.entries.map { yieldLabel(y, it) } }

        // Пустая строка — не подпись, а дырка на экране: подпись либо есть, либо её нет вовсе.
        assertTrue(said.none { it != null && it.isBlank() })
    }

    @Test
    fun `вышло другое — Point говорит об этом, а не заминает`() {
        val note = yieldSurprise(ActionYield.New(ObjectKind.TEXT), ObjectKind.OFFICE)

        assertEquals("Ожидался текст — вышел документ", note)
    }

    @Test
    fun `вышло обещанное — говорить не о чем`() {
        assertNull(yieldSurprise(ActionYield.New(ObjectKind.TEXT), ObjectKind.TEXT))
    }

    @Test
    fun `кто ничего не обещал, тот и не ошибся`() {

        assertNull(yieldSurprise(ActionYield.Unknown, ObjectKind.OFFICE))
        assertNull(yieldSurprise(ActionYield.Same, ObjectKind.OFFICE))
        assertNull(yieldSurprise(ActionYield.None, ObjectKind.OFFICE))
    }

    @Test
    fun `вид совпал, а внутри другое — и об этом сказано`() {

        val note = yieldSurprise(
            ActionYield.New(ObjectKind.PDF, "PDF с текстом документа"),
            ObjectKind.PDF,
            actualNoun = "снимок страницы",
        )

        assertEquals("Обещали PDF с текстом документа — вышло снимок страницы", note)
    }

    @Test
    fun `сошлось и по виду, и по существу — лишних слов нет`() {
        assertNull(
            yieldSurprise(
                ActionYield.New(ObjectKind.PDF, "PDF с текстом документа · без оформления"),
                ObjectKind.PDF,
                actualNoun = "PDF с текстом документа",
            ),
        )
    }

    @Test
    fun `приписка про цену в сверку не идёт — она про дорогу, а не про результат`() {

        assertNull(
            yieldSurprise(
                ActionYield.New(ObjectKind.TEXT, "текст · снимок уйдёт в сервис"),
                ObjectKind.TEXT,
                actualNoun = "текст",
            ),
        )
    }

    @Test
    fun `промолчавший о существе не считается разошедшимся`() {

        assertNull(yieldSurprise(ActionYield.New(ObjectKind.PDF, "PDF с текстом документа"), ObjectKind.PDF))
        assertNull(yieldSurprise(ActionYield.New(ObjectKind.PDF), ObjectKind.PDF, actualNoun = "снимок страницы"))
    }

    @Test
    fun `таблица строится по декларациям и знает, кто что принимает`() {
        val ocr = Cap(
            "ocr",
            out = { ObjectState(ObjectKind.TEXT) },
            takes = { it.kind == ObjectKind.IMAGE || it.kind == ObjectKind.PDF },
        )
        val share = Cap("share", out = { it })

        val rows = capabilityInventory(listOf(ocr, share))

        val ocrRow = rows.single { it.id.value == "ocr" }
        assertEquals(listOf(ObjectKind.IMAGE, ObjectKind.PDF), ocrRow.accepts)
        assertEquals(listOf(ActionYield.New(ObjectKind.TEXT)), ocrRow.yields)

        assertEquals(ObjectKind.entries, rows.single { it.id.value == "share" }.accepts)
    }

    @Test
    fun `одна способность вправе возвращать разное на разном входе`() {

        val pdf = Cap(
            "pdf",
            out = { if (it.kind == ObjectKind.PDF) ObjectState(ObjectKind.TEXT) else ObjectState(ObjectKind.PDF) },
            takes = { it.kind == ObjectKind.IMAGE || it.kind == ObjectKind.PDF },
        )

        val row = capabilityInventory(listOf(pdf)).single()

        assertEquals(
            listOf(ActionYield.New(ObjectKind.PDF), ActionYield.New(ObjectKind.TEXT)),
            row.yields,
        )
    }

    @Test
    fun `способность, сказавшая о себе сама, в таблице помечена`() {
        val understand = Cap("understand", out = { it }, yield = { ActionYield.Same })
        val share = Cap("share", out = { it })

        val rows = capabilityInventory(listOf(understand, share)).associateBy { it.id.value }

        assertTrue(rows.getValue("understand").declaredOnly)
        assertTrue(!rows.getValue("share").declaredOnly)
    }

    @Test
    fun `пробы покрывают каждый вид и голым, и при полном понимании`() {

        val gated = Cap(
            "find",
            out = { it },
            takes = { Feature.HAS_WORD_LAYER in it.features },
        )

        assertTrue(capabilityInventory(listOf(gated)).single().accepts.isNotEmpty())
    }

    @Test
    fun `отрицательный гейт не превращает живую способность в мёртвую строку`() {

        val saveContact = Cap(
            "save-contact",
            out = { it },
            takes = { Feature.HAS_VCARD !in it.features && Feature.HAS_PHONE in it.features },
        )

        assertTrue(capabilityInventory(listOf(saveContact)).single().accepts.isNotEmpty())
    }

    @Test
    fun `не принявшая ни одной пробы способность видна как пустая строка`() {

        val dead = Cap("dead", out = { it }, takes = { false })

        assertEquals(emptyList<ObjectKind>(), capabilityInventory(listOf(dead)).single().accepts)
    }

    @Test
    fun `с ключом имя действия остаётся именем действия`() {
        assertEquals("В Excel", labelNeedingKey("В Excel", keySet = true))
    }

    @Test
    fun `без ключа имя договаривает цену тем же словом на всех`() {

        assertEquals("В Excel · нужен ключ", labelNeedingKey("В Excel", keySet = false))
        assertEquals("Расшифровать · $KEY_NOTE", labelNeedingKey("Расшифровать", keySet = false))
    }

    @Test
    fun `приписка не прячет действие — она к нему приписана`() {

        assertTrue(labelNeedingKey("Понять", keySet = false).startsWith("Понять"))
    }

    @Test
    fun `прочитанное совпадает с написанным`() {
        assertTrue(labelNeedsKey(labelNeedingKey("Понять", keySet = false)))
        assertFalse(labelNeedsKey(labelNeedingKey("Понять", keySet = true)))
    }

    @Test
    fun `чужая похожая фраза за приписку не считается`() {

        assertFalse(labelNeedsKey("Прочитать ключ из QR"))
        assertFalse(labelNeedsKey("нужен ключ"))
    }

    @Test
    fun `имя без приписки — то, ради чего человек пошёл за ключом`() {
        assertEquals("Понять", labelWithoutKeyNote(labelNeedingKey("Понять", keySet = false)))

        assertEquals("Понять", labelWithoutKeyNote("Понять"))
    }

    @Test
    fun `довод на экране ключа называет действие по имени`() {
        val why = keyErrandWhy("Понять")

        assertTrue(why.contains("«Понять»"))

        assertTrue(why.contains("вернётесь"))
    }
}
