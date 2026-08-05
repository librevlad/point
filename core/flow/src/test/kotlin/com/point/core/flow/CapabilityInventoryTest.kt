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

/**
 * Инвентаризация (#491) — правила, по которым «что вернётся» выводится из декларации и говорится
 * человеку.
 */
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

    // --- Умолчание выводится из produces и различает то, что различимо ---

    @Test
    fun `терминальное действие возвращает свой аргумент — значит не вернёт ничего`() {
        val share = Cap("share", out = { it })

        assertEquals(ActionYield.None, share.yields(image))
    }

    @Test
    fun `превращение в свой же вид — это новый объект, а не терминал`() {
        // «Скан» отдаёт ObjectState(IMAGE) с картинки без признаков. По ЗНАЧЕНИЮ это тот же
        // state, что на входе, и умолчание, сравнивающее значения, объявило бы скан
        // терминальным — то есть сказало бы «ничего не вернёт» действию, которое возвращает
        // новую картинку. Различаем по ссылке.
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
        // «Понять» отдаёт свой же объект — но возвращает его понятым, а не «ничего».
        val understand = Cap("understand", out = { it }, yield = { ActionYield.Same })

        assertEquals(ActionYield.Same, understand.yields(image))
        assertEquals(ActionYield.None, derivedYield(understand, image))
    }

    // --- Слова, которыми это сказано человеку ---

    @Test
    fun `вернёт текст — так и написано`() {
        assertEquals("вернёт текст", yieldLabel(ActionYield.New(ObjectKind.TEXT), Intent.UNDERSTAND))
    }

    @Test
    fun `вид OFFICE слишком широк — уточнение способности выигрывает`() {
        // «В Excel» и «В Word» оба объявляют OFFICE. Без уточнения человек читал бы одно и то же
        // слово под двумя разными действиями.
        assertEquals("вернёт таблицу", yieldLabel(ActionYield.New(ObjectKind.OFFICE, "таблицу"), Intent.PREPARE))
        assertEquals("вернёт документ", yieldLabel(ActionYield.New(ObjectKind.OFFICE), Intent.PREPARE))
    }

    @Test
    fun `терминальное договаривает, что оно вместо объекта сделает`() {
        // Терминальное говорит, ЧТО сделает, а не чего не сделает: дизайн-ревью 04.08.2026 на живом
        // экране показало, что два «ничего не вернёт» подряд читаются как поломка — глаз ловит
        // отрицание раньше глагола. Продолжений по-прежнему три, и они разные.
        assertEquals("отправит и вернётся сюда", yieldLabel(ActionYield.None, Intent.SEND))
        assertEquals("откроет в другом приложении", yieldLabel(ActionYield.None, Intent.OPEN))
        assertEquals("покажет здесь же", yieldLabel(ActionYield.None, Intent.UNDERSTAND))
        // Ни одна подпись не начинается с отрицания — это и было находкой.
        Intent.entries.forEach { intent ->
            assertFalse(
                "подпись начинается с отрицания: " + yieldLabel(ActionYield.None, intent),
                yieldLabel(ActionYield.None, intent).startsWith("ничего"),
            )
        }
    }

    @Test
    fun `у каждого исхода есть свои слова, и они не пустые`() {
        val all = listOf(
            ActionYield.None, ActionYield.Same, ActionYield.Unknown,
        ) + ObjectKind.entries.map { ActionYield.New(it) }
        val said = all.flatMap { y -> Intent.entries.map { yieldLabel(y, it) } }

        assertTrue(said.none { it.isBlank() })
    }

    // --- Ожидание, а не обещание ---

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
        // AI не обещал вида, «Понять» и «Поделиться» судятся не видом. Придираться к ним
        // значило бы ругать за невыполненное обещание, которого никто не давал.
        assertNull(yieldSurprise(ActionYield.Unknown, ObjectKind.OFFICE))
        assertNull(yieldSurprise(ActionYield.Same, ObjectKind.OFFICE))
        assertNull(yieldSurprise(ActionYield.None, ObjectKind.OFFICE))
    }

    // --- Сама таблица ---

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
        // «Поделиться» принимает всё — значит и все виды в таблице.
        assertEquals(ObjectKind.entries, rows.single { it.id.value == "share" }.accepts)
    }

    @Test
    fun `одна способность вправе возвращать разное на разном входе`() {
        // «В PDF» с картинки делает PDF, а с PDF — извлекает текст. Строка обязана показать оба.
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
        // Способность за признаком не должна выпасть из таблицы только потому, что признак
        // зажигается обогащением, а не MIME-типом.
        val gated = Cap(
            "find",
            out = { it },
            takes = { Feature.HAS_WORD_LAYER in it.features },
        )

        assertTrue(capabilityInventory(listOf(gated)).single().accepts.isNotEmpty())
    }

    @Test
    fun `отрицательный гейт не превращает живую способность в мёртвую строку`() {
        // Живая находка среза: «Сохранить контакт» принимает `!HAS_VCARD && HAS_PHONE`. Пока проб
        // было две (голая и полная), при полном наборе горел и HAS_VCARD — способность отказывала,
        // и в таблице выглядела не предлагаемой никому, хотя человек видит её каждый день.
        val saveContact = Cap(
            "save-contact",
            out = { it },
            takes = { Feature.HAS_VCARD !in it.features && Feature.HAS_PHONE in it.features },
        )

        assertTrue(capabilityInventory(listOf(saveContact)).single().accepts.isNotEmpty())
    }

    @Test
    fun `не принявшая ни одной пробы способность видна как пустая строка`() {
        // Ноль принятых видов — это не «ошибка теста», а находка инвентаризации: такую
        // способность человеку не предложат никогда.
        val dead = Cap("dead", out = { it }, takes = { false })

        assertEquals(emptyList<ObjectKind>(), capabilityInventory(listOf(dead)).single().accepts)
    }

    // --- «нужен ключ» в имени действия (#529) ---

    @Test
    fun `с ключом имя действия остаётся именем действия`() {
        assertEquals("В Excel", labelNeedingKey("В Excel", keySet = true))
    }

    @Test
    fun `без ключа имя договаривает цену тем же словом на всех`() {
        // Одно написание на всех — иначе «нужен ключ» на разных строках значило бы разное.
        assertEquals("В Excel · нужен ключ", labelNeedingKey("В Excel", keySet = false))
        assertEquals("Расшифровать · $KEY_NOTE", labelNeedingKey("Расшифровать", keySet = false))
    }

    @Test
    fun `приписка не прячет действие — она к нему приписана`() {
        // Договор среза: действие остаётся действием, тап по нему ведёт на экран ключей. Значит
        // имя обязано начинаться с прежнего имени, а не подменяться сообщением о настройках.
        assertTrue(labelNeedingKey("Понять", keySet = false).startsWith("Понять"))
    }
}
