package com.point.executors

import com.point.core.flow.ExternalEyeOcrRealizer

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
import org.junit.Assert.assertSame
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
 * алфавиту: живая пара `ExternalEyeOcrRealizer` и `RemotePcRealizer` делит способность «ocr»
 * и цену 50, и снимок уходил в чужой сервис случайно — потому что `E` раньше `R`. Здесь эта
 * пара проверена как есть, а работа под именем «entities» — та же связка на упрощённой паре.
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
     * Путь человека: он нажал работу, которую телефон умеет сам, а его компьютер объявил то
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

    /**
     * «На компьютер» — действие, которое буквально увозит объект на второе устройство
     * человека, и его исполнитель обязан называться этим устройством.
     *
     * Сегодня у способности `pc` исполнитель один, и порядок между ним и кем-то ещё не
     * возникает. Но правило близости одно на всех, а подпись исполнителя — его единственный
     * вход в это правило: назовись он здешним, он делил бы ступень с собственной работой
     * телефона, и их разводило бы имя класса — ровно та случайность, ради которой #1088 и
     * заводился. Уход за круг устройств — отдельный вопрос, и ответ на него прежний.
     *
     * Здесь проверяется признак, а не экран: спрашивают ли согласие у человека, нажавшего
     * «На компьютер», решает `FlowViewModel`, и это доказано на его пути —
     * `FlowViewModelTest.«На компьютер» не спрашивает про облако`.
     */
    @Test
    fun `«На компьютер» — второе устройство человека, а не сам телефон`() {
        val toPc = PcRealizer(Circle(), Knock(), unusedStore)

        val order = DefaultExecutionPolicy().choose(anything, listOf(AaaService(), toPc, ZzzOwnWork()))

        assertEquals(
            listOf(RealizerKind.LOCAL, RealizerKind.REMOTE, RealizerKind.CLOUD),
            order.map { it.meta.kind },
        )
        assertFalse(
            "«На компьютер» стало объявлять уход за круг устройств",
            resolver(toPc).leavesDevice(PcCapability.ID),
        )
    }

    /**
     * Живая пара одной цены, а не выдуманная (#1088).
     *
     * «Распознать текст» на снимке умеют и чужой глаз (`ExternalEyeOcrRealizer`), и компьютер
     * человека, объявивший телефону то же умение: у обоих исполнителей объявленная цена 50.
     * Разводил их алфавит имён классов — `E` раньше `R`, — и снимок уходил в чужой сервис
     * мимо своего компьютера. Теперь при равной цене первым идёт компьютер.
     */
    @Test
    fun `при равной цене снимок читает свой компьютер, а не чужой глаз`() {
        val eye = ExternalEyeOcrRealizer(openEye, unusedStore)
        val pc = remotePcRealizers(
            own = setOf(com.point.core.flow.capabilities.OcrCapability()),

            // Так это умение и приезжает с компьютера: тем же именем, что своё, и с честным
            // «дальше уйдёт наружу» — читает-то там сервис (`DesktopRegistry.leavesDevice`).
            fromPc = listOf(PcRemoteAction("ocr", "Распознать текст", leavesCircle = true)),
            links = Circle(),
            transport = Knock(),
        ).single()

        val order = DefaultExecutionPolicy().choose(ObjectState(ObjectKind.IMAGE), listOf(eye, pc))

        assertEquals("цена у пары разошлась — проверка больше не о близости", eye.meta.priority, pc.meta.priority)
        assertSame("снимок ушёл в чужой сервис мимо своего компьютера", pc, order.first())
    }

    /** Чужой глаз — сервис вне круга, читать его тут не зовут. */
    private val openEye = object : com.point.core.flow.ExternalEye {
        override fun available() = true
        override suspend fun read(obj: PointObject) = error("не зовут")
    }

    /** Порядок исполнителей считается без единого обращения к байтам. */
    private val unusedStore = object : com.point.core.flow.ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("не зовут")
        override suspend fun ingestMultiple(sources: List<String>) = error("не зовут")
        override suspend fun put(
            result: com.point.core.model.ResultObject,
            from: PointObject?,
            by: CapabilityId?,
        ) = error("не зовут")
        override suspend fun children(collection: PointObject, limit: Int) = error("не зовут")
        override suspend fun readText(obj: PointObject, limit: Int) = error("не зовут")
        override suspend fun newScratchFile(extension: String) = error("не зовут")
        override suspend fun clear() = Unit
    }
}
