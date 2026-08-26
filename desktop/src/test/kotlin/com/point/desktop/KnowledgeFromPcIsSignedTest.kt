package com.point.desktop

import com.point.core.flow.ENTITY_RULES_ACTOR
import com.point.core.flow.KnownCapabilities
import com.point.core.flow.META_ACTOR_SUFFIX
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.PcResultFields
import com.point.core.flow.RegexEntityExtractor
import com.point.core.flow.RelayRpc
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Знание, добытое компьютером, знает своего исполнителя (#1273, продолжение #1127).
 *
 * Шов для имени заведён в `Actor.knownBy` и стоит на телефоне; на компьютере его не было
 * нигде — ни у нажатого действия, ни у автоматического исследования. Знание уезжало на
 * телефон безымянным и вставало рядом со значениями, у которых имя есть: спор «телефон
 * прочитал так, компьютер иначе» было нечем разобрать.
 *
 * Имя — по движку, а не по устройству: правила поиска значений у обеих поверхностей одни
 * и те же, поэтому и свидетель один. Отдельное «pc» сделало бы из одного прочтения два
 * независимых подтверждения.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeFromPcIsSignedTest {

    @get:Rule val temp = TemporaryFolder()

    private val phone = META_ENTITY_PREFIX + "phone"

    private val said = "Позвони мне: +380671234567"

    /** Исследование доведено до конца — событие планировщика, а не истёкший срок в секундах. */
    private val dispatcher = StandardTestDispatcher()

    /** Компьютер собран так же, как в `Main.kt`: приёмная, состояние, ручка писем. */
    private class Pc(temp: TemporaryFolder) {
        val inbox = Inbox(temp.newFolder("inbox"))
        val outbox = Outbox(temp.newFolder("outbox"))

        val state = DesktopState(
            registry = DesktopRegistry(setOf(PcEntitiesCapability())),
            resolver = DesktopResolver(setOf(PcEntitiesRealizer(RegexEntityExtractor()))),
            clipboard = { },
            outbox = outbox,
        )

        val requests = RelayRequests(
            remoteActions = { emptyList() },
            outbox = outbox,
            onPhoneCaps = { },
            clipboardGet = { null },
            clipboardSet = { },
            onObject = { name, mime, meta, bytes, action ->
                val item = inbox.receive(name, mime, meta, bytes.inputStream())
                state.onReceived(item, ObjectSource.PHONE_RELAY)
                action?.let { state.runRemoteActionNow(it, item) }
            },
        )
    }

    @Test
    fun `знание, сделанное компьютером по просьбе, приезжает на телефон подписанным`() {
        val pc = Pc(temp)

        val reply = pc.requests.answer(
            RelayRpc.OBJECT,
            mapOf(
                RelayRpc.ID to "письмо-1",
                "name" to "визитка.txt",
                "mime" to "text/plain",
                "action" to KnownCapabilities.ENTITIES.value,
            ),
            said.toByteArray(Charsets.UTF_8),
        )!!

        assertEquals(
            "знание приехало на телефон безымянным",
            ENTITY_RULES_ACTOR,
            reply.meta[PcResultFields.UNDERSTOOD + phone + META_ACTOR_SUFFIX],
        )
    }

    @Test
    fun `знание, добытое компьютером без просьбы, тоже названо своим исполнителем`() = runTest(dispatcher) {
        val st = DesktopState(
            DesktopRegistry(setOf(PcEntitiesCapability())),
            DesktopResolver(setOf(PcEntitiesRealizer(RegexEntityExtractor()))),
            clipboard = { },
            background = dispatcher,
        )
        val file = temp.newFile("прибыло.txt").apply { writeText(said) }
        val item = InboxItem(
            PointObject("t", "text/plain", ScratchRef(file.absolutePath), ObjectState(ObjectKind.TEXT)),
        )

        st.onReceived(item, ObjectSource.PHONE_RELAY)
        advanceUntilIdle()

        val meta = st.items.value.first().obj.metadata
        assertEquals("тихое исследование положило знание без имени", "+380671234567", meta[phone])
        assertEquals(
            "исследование, начатое самим компьютером, осталось неподписанным",
            ENTITY_RULES_ACTOR,
            meta[phone + META_ACTOR_SUFFIX],
        )
    }
}
