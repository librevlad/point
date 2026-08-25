package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.PcActionOutcome
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.RelayRpc
import com.point.core.flow.chainClosedBy
import com.point.core.flow.decodePcReceiveReply
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Просьба телефона живёт внутри выбранного режима (#1269).
 *
 * Человек закрыл компьютеру дорогу наружу, а компьютер по просьбе своего же телефона всё
 * равно слал страницы чужому сервису: режим проверялся только на пути клика по экрану, и
 * просьба соседа была единственным входом мимо него. Конституция §11: объект покидает
 * устройства человека только с его согласия, и заранее выбранный режим — это и есть ответ.
 *
 * Путь здесь настоящий: письмо телефона приходит в ту же ручку `RelayRequests`, которую
 * заводит `Main.kt`, и оттуда — в то же состояние окна. Отдельного входа для теста нет.
 */
class PhoneRequestStaysInTheModeTest {

    @get:Rule val temp = TemporaryFolder()

    private val ran = AtomicInteger(0)

    private val reading = CapabilityId("прочитать")

    /** Умение компьютера, у которого единственный путь — наружу: чтение чужим сервисом. */
    private inner class Reads : Capability {
        override val id = reading
        override val icon = "read"
        override val meta = CapabilityMeta()
        override fun label(state: ObjectState) = "Прочитать"
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
    }

    private inner class ReadsOutside : Realizer {
        override val capabilityId = reading
        override val meta = RealizerMeta(kind = RealizerKind.CLOUD)
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            ran.incrementAndGet()
            return ActionResult.Done("Прочитано")
        }
    }

    /** Телефон просит компьютер поработать — тем же письмом, каким просит настоящий. */
    private fun askedAt(level: PrivacyLevel): PcActionOutcome? {
        val inbox = Inbox(temp.newFolder("inbox-$level"))
        val outbox = Outbox(temp.newFolder("outbox-$level"))
        val state = DesktopState(
            registry = DesktopRegistry(setOf(Reads())),
            resolver = DesktopResolver(setOf(ReadsOutside())),
            clipboard = { },
            outbox = outbox,
            privacyLevel = { level },
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
                action?.let { state.runRemoteActionNow(it, item, budgetMs = 10_000) }
            },
        )
        val reply = requests.answer(
            RelayRpc.OBJECT,
            mapOf(RelayRpc.ID to "письмо-$level", "name" to "счёт.txt", "mime" to "text/plain", "action" to reading.value),
            "текст счёта".toByteArray(Charsets.UTF_8),
        )
        return decodePcReceiveReply(String(reply!!.body, Charsets.UTF_8))
    }

    @Test
    fun `дорога наружу закрыта — просьба телефона не исполняется, и телефон слышит почему`() {
        val outcome = askedAt(PrivacyLevel.DEVICE_ONLY)

        assertEquals("объект ушёл наружу вопреки выбранному режиму", 0, ran.get())
        assertEquals(PcActionOutcome.Failed(chainClosedBy(PrivacyLevel.DEVICE_ONLY)), outcome)
    }

    @Test
    fun `дорога наружу открыта — просьба телефона исполняется как прежде`() {
        val outcome = askedAt(PrivacyLevel.FREE_FIRST)

        assertEquals("работа не состоялась там, где режим её пускает", 1, ran.get())
        assertEquals(PcActionOutcome.Done("Прочитано"), outcome)
    }
}
