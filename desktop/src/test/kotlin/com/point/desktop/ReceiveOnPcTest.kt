package com.point.desktop

import com.point.core.flow.DropArrival
import com.point.core.flow.DropInbox
import com.point.core.flow.DropInboxBox
import com.point.core.flow.DropWait
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Приём файла по ссылке есть и на компьютере (#727), и подтверждение уходит только после
 * того, как объект создан (#726): пока объекта нет, файл на сервере не стирается.
 */
class ReceiveOnPcTest {

    @get:Rule val temp = TemporaryFolder()

    private val box = DropInboxBox("box-1", "https://point.leerio.app/u/box-1")

    private class Server(
        val opened: DropInboxBox?,
        val outcomes: MutableList<DropWait>,
    ) : DropInbox {
        var acked: String? = null
        var awaits = 0

        var closed = 0

        override suspend fun open(): com.point.core.flow.DropOpen =
            opened?.let { com.point.core.flow.DropOpen.Opened(it) }
                ?: com.point.core.flow.DropOpen.Refused("сервер не дал ссылку")

        override suspend fun close(box: DropInboxBox) { closed++ }

        override suspend fun await(box: DropInboxBox, target: (name: String) -> String): DropWait {
            awaits++
            val next = outcomes.removeFirstOrNull() ?: DropWait.Empty
            return if (next is DropWait.Arrived) {
                val path = target(next.arrival.name)
                File(path).writeText("файл")
                DropWait.Arrived(next.arrival.copy(path = path))
            } else {
                next
            }
        }

        override suspend fun ack(box: DropInboxBox, fileId: String) { acked = fileId }
    }

    private fun receiver(server: Server, onArrived: (String, String, String) -> Unit) = ReceiveOnPc(
        inbox = server,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        tmpDir = temp.newFolder("received-" + System.nanoTime()),
        onArrived = onArrived,
        retryPauseMs = 10,
    )

    /**
     * Ждём события, а не срока: срок здесь — только страховка от вечного зависания (#1299).
     *
     * Настенные часы тут законны и переводу на планировщик теста не подлежат: под тестом
     * настоящий приём — реле, файлы, диск, — а не работа фоновой корутины окна. У этих дел
     * своё время исполнения, и подменять его планировщиком нечего: механический перевод дал
     * бы не зелёный тест, а тихо неправильный.
     *
     * Число большое нарочно: на занятой машине сборки корутина просыпается позже, и оно ни
     * о чём не судит — судит `check`.
     */
    private fun waitUntil(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) {
            if (check()) return
            Thread.sleep(20)
        }
        throw AssertionError("не дождались: $what")
    }

    @Test
    fun `ссылку показывают тому, кто далеко`() {
        val server = Server(box, mutableListOf())
        val pc = receiver(server) { _, _, _ -> }

        pc.start { }

        waitUntil("ссылка не появилась") { pc.waiting.value != null }
        assertEquals(box.link, pc.waiting.value?.link)
        pc.cancel()
    }

    @Test
    fun `пришедший файл становится объектом, и только потом подтверждается приём`() {
        val arrived = mutableListOf<String>()
        val server = Server(
            box,
            mutableListOf(DropWait.Arrived(DropArrival("", "смета.pdf", "application/pdf", "f-42"))),
        )
        val pc = receiver(server) { name, _, path ->
            assertNull("приём подтверждён раньше объекта", server.acked)
            arrived += name + "|" + File(path).readText()
        }

        pc.start { }

        waitUntil("объект не появился") { arrived.isNotEmpty() }
        waitUntil("приём не подтверждён") { server.acked != null }
        assertEquals(listOf("смета.pdf|файл"), arrived)
        assertEquals("f-42", server.acked)
        assertNull("ожидание не закрылось", pc.waiting.value)
    }

    @Test
    fun `ящик не открылся — человеку сказано, а ожидание не висит`() {
        val server = Server(null, mutableListOf())
        var said: String? = null
        val pc = receiver(server) { _, _, _ -> }

        pc.start { why -> said = why }

        waitUntil("отказ не назван") { said != null }

        // Причину называет сервер, а не экран одной фразой на все беды (#729): «ящиков
        // слишком много», «устройство не в аккаунте» и «нет связи» — три разных положения.
        assertEquals("сервер не дал ссылку", said)
        assertNull(pc.waiting.value)
    }

    @Test
    fun `связь оборвалась — ждём дальше и говорим об этом`() {
        val server = Server(box, mutableListOf(DropWait.Failed("Сервер Point не отвечает")))
        val pc = receiver(server) { _, _, _ -> }

        // Состояние сменяется быстро — ловим всё сказанное, а не мгновенный снимок.
        val said = java.util.concurrent.CopyOnWriteArrayList<String>()
        pc.onWaiting = { waiting -> waiting?.failed?.let { said += it } }

        pc.start { }

        waitUntil("причина не показана") { said.isNotEmpty() }
        assertEquals("Сервер Point не отвечает", said.first())
        waitUntil("ожидание прекратилось после одной неудачи") { server.awaits > 1 }
        pc.cancel()
    }
}
