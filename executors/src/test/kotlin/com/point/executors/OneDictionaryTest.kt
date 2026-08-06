package com.point.executors

import com.point.core.flow.LinkedPc
import com.point.core.flow.PcLinks
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.capabilities.OcrCapability
import com.point.core.flow.capabilities.sharedCapabilities
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Одно намерение — одна декларация (контракт 06.08.2026, `docs/DESKTOP-CONTRACT.md`, И1 и И2).
 *
 * Судится не деталь реализации, а сам контракт: **человек не выбирает устройство.** Пока у
 * компьютера была своя способность `pc-ocr`, на телефоне рядом с бесплатным локальным «Распознать
 * текст» стояло «Прочитать в облаке на ПК» — предложение свезти снимок на другое устройство,
 * чтобы тот выгрузил его чужому сервису. Владелец назвал это абсурдом, и абсурдом это было не
 * из-за подписи, а из-за второй декларации одного намерения.
 *
 * Здесь же проверяется и то, что реализация компьютера при этом **не потерялась**: она встаёт
 * кандидатом к той же способности, и выбирать между ней и местной — работа `Resolver`.
 */
class OneDictionaryTest {

    private class FixedPc(private val pc: LinkedPc?) : PcLinks {
        override fun current() = pc
        override suspend fun save(pc: LinkedPc) = Unit
        override suspend fun clear() = Unit
    }

    private val pc = LinkedPc("d-pc", "Рабочий ноутбук", "ключ")

    /** Что объявил компьютер: чтение снимка — общее намерение; печать — только его. */
    private val fromPc = listOf(
        PcRemoteAction("ocr", "Распознать текст", setOf("IMAGE")),
        PcRemoteAction("pc-print", "Напечатать на ПК"),
    )

    /**
     * Реестр телефона так, как его собирает `CapabilityModule`: свои способности, общий словарь и
     * синтезированные из объявления компьютера — **с тем же правилом отбора, что в модуле**.
     */
    private fun phoneRegistry() = DefaultCapabilityRegistry(
        capabilities = setOf(ShareCapability(), SaveCapability()) +
            sharedCapabilities() +
            fromPc
                .filterNot { CapabilityId(it.id) in com.point.core.flow.capabilities.sharedCapabilityIds }
                .map { RemotePcCapability(it, FixedPc(pc)) },
        policy = DefaultBubblePolicy(),
    )

    @Test fun `на снимке одно «Распознать текст», а не два`() {
        val titles = phoneRegistry().bubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.title }

        assertEquals(
            "чтение снимка предложено не один раз: $titles",
            1,
            titles.count { it.contains("Распознать") },
        )
        // Именно у переехавшего намерения не должно быть двойника с устройством в подписи. Общее
        // правило «на экране вообще нет устройств» (И2) станет верным, когда переедет весь
        // словарь; сегодня «Напечатать на ПК» законно остаётся — принтера у телефона нет, и это
        // не выбор устройства, а единственное место, где действие возможно.
        assertTrue(
            "у общего намерения снова появился двойник с устройством: $titles",
            titles.none { it.contains("Распознать") && it.contains("на ПК") },
        )
    }

    @Test fun `реализация компьютера не потерялась — она кандидат к той же способности`() {
        // Иначе «убрали кнопку» означало бы «убрали умение»: компьютер по-прежнему умеет читать
        // снимок, просто теперь это реализация общего намерения, а не второе намерение.
        val remote = RemotePcRealizer(fromPc[0], FixedPc(pc), NoTransport)

        assertEquals(OcrCapability.ID, remote.capabilityId)
    }

    @Test fun `непереехавшее объявляется по-старому и остаётся видимым`() {
        // Сторож против чрезмерного усердия: словарь общий не для всего. «Напечатать» на телефоне
        // не объявлено вовсе — принтер есть только у компьютера, и это его законная способность.
        val remote = RemotePcRealizer(fromPc[1], FixedPc(pc), NoTransport)

        assertEquals(CapabilityId("pc-do:pc-print"), remote.capabilityId)
        assertTrue(
            "действие компьютера пропало с телефона",
            phoneRegistry().bubblesFor(ObjectState(ObjectKind.IMAGE)).any { it.title == "Напечатать на ПК" },
        )
    }

    @Test fun `в общем словаре нет ни одной способности с устройством в идентификаторе`() {
        // Сторож против возврата: `pc-…` в общем словаре значит, что намерение снова присвоено
        // устройству — ровно то, что чинил этот срез.
        val owned = sharedCapabilities().map { it.id.value }.filter { it.startsWith("pc-") }

        assertTrue("намерение снова присвоено устройству: $owned", owned.isEmpty())
    }

    /** Транспорт здесь не зовётся ни разу: судится, под какой способностью живёт реализация. */
    private object NoTransport : com.point.core.flow.PcTransport {
        override suspend fun send(
            pc: LinkedPc,
            obj: com.point.core.model.PointObject,
            name: String,
            meta: Map<String, String>,
            action: String?,
        ) = com.point.core.flow.PcSendOutcome.Unreachable("тест", com.point.core.flow.PcUnreachable.PC_ASLEEP)

        override suspend fun fetchCaps(pc: LinkedPc): List<PcRemoteAction>? = null
        override suspend fun fetchOutbox(pc: LinkedPc): List<com.point.core.flow.PcOutboxEntry>? = null
        override suspend fun downloadOutboxFile(pc: LinkedPc, id: Int, targetPath: String) = false
        override suspend fun ackOutbox(pc: LinkedPc, id: Int) = Unit
        override suspend fun pushPhoneCaps(pc: LinkedPc, caps: List<PcRemoteAction>) = false
        override suspend fun exchangeSecrets(
            pc: LinkedPc,
            mine: com.point.core.flow.SharedSecrets,
        ): com.point.core.flow.SharedSecrets? = null
    }
}
