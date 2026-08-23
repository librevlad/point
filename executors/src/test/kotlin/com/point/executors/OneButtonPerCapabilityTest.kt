package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.LinkedPc
import com.point.core.flow.PcLinks
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.SpeechReadiness
import com.point.core.flow.capabilities.sharedCapabilities
import com.point.core.flow.decodePcCaps
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #628, решение владельца — «одна способность — одна кнопка».
 *
 * Умение, объявленное компьютером, не заводит на телефоне вторую строку, если телефон
 * умеет то же самое сам- компьютер там просто ещё один исполнитель той же способности,
 * а кого позвать, решает Resolver. Слияние идёт по любому известному телефону
 * CapabilityId, а не по короткому словарю общих способностей.
 */
class OneButtonPerCapabilityTest {

    @Test fun `умение, которое телефон уже умеет, не заводит вторую способность`() {

        val added = remotePcCapabilities(phoneOwn, listOf(transcribeOnPc), pairedPc)

        assertEquals("компьютер завёл вторую способность на то же умение", emptySet<Capability>(), added)
    }

    @Test fun `компьютер становится вторым исполнителем той же способности`() {

        val remote = remotePcRealizers(phoneOwn, listOf(transcribeOnPc), pairedPc, NoTransport).single()

        assertEquals(TranscribeCapability.ID, remote.capabilityId)
    }

    @Test fun `на записи одна «Расшифровать», а не две`() {

        val titles = registryWithPc(listOf(transcribeOnPc)).bubblesFor(ObjectState(ObjectKind.AUDIO)).map { it.title }

        assertEquals(
            "расшифровка предложена не один раз- $titles",
            1,
            titles.count { it.startsWith("Расшифровать") },
        )
    }

    @Test fun `умение, которого у телефона нет, остаётся видимым`() {

        val titles = registryWithPc(listOf(printOnPc)).bubblesFor(ObjectState(ObjectKind.PDF)).map { it.title }

        assertTrue("действие компьютера пропало с телефона- $titles", printOnPc.label in titles)
    }

    @Test fun `при подключённом компьютере ни одно название не стоит в списке дважды`() {

        ObjectKind.entries.forEach { kind ->
            val titles = registryWithPc(advertisedByPc).bubblesFor(ObjectState(kind)).map { it.title }
            val twins = titles.groupBy { it }.filterValues { it.size > 1 }.keys

            assertTrue("на $kind одно намерение предложено дважды- $twins", twins.isEmpty())
        }
    }

    /** Несколько дверей одного умения (#1174) — одна способность, объект открывает любая. */
    @Test fun `двери одного умения не двоят способность и не сливаются во всеядную`() {
        val rows = listOf(
            PcRemoteAction("browse", "browse", kinds = setOf("URL")),
            PcRemoteAction("browse", "browse", features = setOf("HAS_URL")),
        )

        val cap = remotePcCapabilities(phoneOwn, rows, pairedPc).single()

        assertTrue(cap.accepts(ObjectState(ObjectKind.URL)))
        assertTrue(cap.accepts(ObjectState(ObjectKind.TEXT, setOf(com.point.core.model.Feature.HAS_URL))))
        assertTrue(!cap.accepts(ObjectState(ObjectKind.TEXT)))
        assertEquals(1, remotePcRealizers(phoneOwn, rows, pairedPc, NoTransport).size)
    }

    private fun registryWithPc(fromPc: List<PcRemoteAction>) = DefaultCapabilityRegistry(
        capabilities = phoneOwn + remotePcCapabilities(phoneOwn, fromPc, pairedPc),
        policy = DefaultBubblePolicy(),
    )

    private companion object {

        val pairedPc = object : PcLinks {
            override fun current() = LinkedPc("d-pc", "Домашний ПК", "ключ")
            override suspend fun save(pc: LinkedPc) = Unit
            override suspend fun clear() = Unit
        }

        val keysReady = SpeechReadiness { emptyList() }

        /** Часть настоящего набора телефона- сюда взято то, на что компьютер отвечает своим умением. */
        val phoneOwn: Set<Capability> = setOf(
            TranscribeCapability(keysReady),
            SaveCapability(),
            ShareCapability(),
            CopyCapability(),
            OpenCapability(),
            OpenUrlCapability(),
            TranslateCapability(aiKeysReady),
        ) + sharedCapabilities()

        /** Корень репозитория: снимок лежит у того, кто объявление пишет, — в `:desktop`. */
        val repo: File = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

        /**
         * Что компьютер объявляет телефону — дословно то, что `:desktop` шлёт по проводу,
         * снятое в файл (#1094). Рукописная копия здесь синхронизировалась руками и к боевому
         * набору отношения не имела; свежесть снимка сторожит PhoneFacingTest в `:desktop`.
         */
        val advertisedByPc: List<PcRemoteAction> =
            decodePcCaps(File(repo, "desktop/src/test/resources/phone-facing-actions.txt").readText())

        val transcribeOnPc = advertisedByPc.first { it.id == TranscribeCapability.ID.value }
        val printOnPc = advertisedByPc.first { it.id == "pc-print" }

        object NoTransport : com.point.core.flow.PcTransport {
            override suspend fun send(
                pc: LinkedPc,
                obj: com.point.core.model.PointObject,
                fileName: String,
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
}
