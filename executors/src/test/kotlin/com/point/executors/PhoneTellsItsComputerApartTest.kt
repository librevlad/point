package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.DefaultExecutionPolicy
import com.point.core.flow.LinkedPc
import com.point.core.flow.PcLinks
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.PcSendOutcome
import com.point.core.flow.PcTransport
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Работа, которую телефон умеет сам. Имя класса — после `RemotePcRealizer` по алфавиту. */
private class ZzzOwnWork : Realizer {
    var asked = false
    override val capabilityId = CapabilityId("entities")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        asked = true
        return ActionResult.Done("mine")
    }
}

/** Та же работа под другим именем — до `RemotePcRealizer` по алфавиту. */
private class AaaOwnWork : Realizer {
    override val capabilityId = CapabilityId("entities")
    override suspend fun perform(input: PointObject, amendment: String?) = ActionResult.Done("mine")
}

/** Чужой сервис той же цены. */
private class AaaService : Realizer {
    override val capabilityId = CapabilityId("entities")
    override val meta = RealizerMeta(kind = RealizerKind.CLOUD)
    override suspend fun perform(input: PointObject, amendment: String?) = ActionResult.Done("service")
}

/**
 * Телефон отличает свой компьютер от облака и от себя (#1088, решение владельца 23.08.2026,
 * вариант A).
 *
 * Исполнитель компьютера звался то здешним, то облачным — смотря по тому, отправит ли
 * компьютер объект дальше, — а `RealizerKind.REMOTE` не использовался нигде. Поэтому при
 * равной объявленной цене порядок между своим исполнителем и соседским решало имя класса по
 * алфавиту: `EntityInvestigation` выигрывал у `RemotePcRealizer` случайно, и переименование
 * класса меняло, кто делает работу и где оказывается объект.
 */
class PhoneTellsItsComputerApartTest {

    private val anything = ObjectState(ObjectKind.TEXT)

    private val work = CapabilityId("entities")

    private fun obj() = PointObject("id", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT))

    private class Circle(private val pc: LinkedPc? = LinkedPc("d-pc", "Мой ПК", "ключ")) : PcLinks {
        override fun current() = pc
        override suspend fun save(pc: LinkedPc) = Unit
        override suspend fun clear() = Unit
    }

    /** Компьютер, который молча запоминает, показали ли ему объект. */
    private class Knock(private val outcome: PcSendOutcome = PcSendOutcome.Parked) : PcTransport {
        var asked = false
        override suspend fun send(
            pc: LinkedPc,
            obj: PointObject,
            name: String,
            meta: Map<String, String>,
            action: String?,
        ): PcSendOutcome {
            asked = true
            return outcome
        }

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

    /** Компьютер объявил ту же работу под тем же именем — той же цены, что и своя. */
    private fun computer(transport: PcTransport = Knock()) = RemotePcRealizer(
        PcRemoteAction("entities", "Найти значения"),
        Circle(),
        transport,
        ownIds = setOf(work),
    )

    private class Named(override val id: CapabilityId) : Capability {
        override val icon = "x"
        override fun label(state: ObjectState) = "тест"
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
    }

    private fun resolver(vararg realizers: Realizer): DefaultResolver {
        val caps = realizers.mapTo(mutableSetOf<Capability>()) { Named(it.capabilityId) }
        return DefaultResolver(realizers.toSet(), DefaultCapabilityRegistry(caps, DefaultBubblePolicy()))
    }

    /**
     * Путь человека: он тапнул работу, которую телефон умеет сам, а его компьютер объявил то
     * же умение под тем же именем и той же цены. Объект остаётся здесь.
     */
    @Test
    fun `равное умение телефон делает сам, а не отдаёт компьютеру`() = runTest {
        val mine = ZzzOwnWork()
        val knock = Knock()

        resolver(mine, computer(knock)).realizerFor(work, anything).perform(obj(), null)

        assertTrue("телефон не взялся за то, что умеет сам", mine.asked)
        assertFalse("объект уехал на компьютер, хотя работа делается здесь", knock.asked)
    }

    /**
     * То же правило словами владельца: при равной цене сначала я, потом мой компьютер, потом
     * чужой сервис. Раньше эту тройку выстраивал алфавит имён классов.
     */
    @Test
    fun `при равной цене сначала я, потом мой компьютер, потом чужой сервис`() {
        val order = DefaultExecutionPolicy()
            .choose(anything, listOf(AaaService(), computer(), ZzzOwnWork()))

        assertEquals(
            listOf(RealizerKind.LOCAL, RealizerKind.REMOTE, RealizerKind.CLOUD),
            order.map { it.meta.kind },
        )
    }

    @Test
    fun `порядок исполнителей не меняется от переименования класса`() {
        val policy = DefaultExecutionPolicy()

        val order = policy.choose(anything, listOf(computer(), ZzzOwnWork())).map { it.meta.kind }
        val renamed = policy.choose(anything, listOf(computer(), AaaOwnWork())).map { it.meta.kind }

        assertEquals("переименование класса переставило исполнителей", order, renamed)
        assertEquals(listOf(RealizerKind.LOCAL, RealizerKind.REMOTE), order)
    }

    /** Согласие держится на объявленном уходе наружу, а не на виде исполнителя. */
    @Test
    fun `компьютер круга — не чужой сервис, и согласия сам по себе не требует`() {
        val stays = computer()

        assertEquals(RealizerKind.REMOTE, stays.meta.kind)
        assertFalse(resolver(stays).leavesDevice(work))
    }
}
