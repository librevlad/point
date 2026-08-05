package com.point.desktop

import com.point.core.flow.PcRemoteAction
import com.point.core.flow.RelayCrypto
import com.point.core.flow.RelayRpc
import com.point.core.flow.decodePcCaps
import com.point.core.flow.decodePcFrame
import com.point.core.flow.decodePcOutbox
import com.point.core.flow.encodePcFrame
import com.point.core.flow.isOurReply
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Связь через релей — живьём, а не на фейках (#412).
 *
 * У владельца роутер разводит его же устройства: телефон видит шлюз и не видит компьютер. Значит
 * релей для него не запасной путь, а единственный, и «работает» здесь обязано быть измеренным
 * фактом. Фейки этого не ловят: в них не бывает ни мёртвого процесса на сервере, ни ящика, из
 * которого письмо забрал кто-то другой, ни протухшего ответа на прошлый вопрос.
 *
 * Тест играет **за телефон**: сам запечатывает вопрос тем же кодеком, кладёт в ящик `rpc-to-pc` и
 * ждёт ответа в `rpc-to-phone`. Отвечает при этом настоящая сторона ПК — [RelayRequestPoller],
 * тот самый класс, что работает в приложении.
 *
 * Пропускается, если релей не настроен (на CI его нет) или не отвечает: наказывать сборку за
 * чужой упавший сервер нельзя, а вот молча считать связь рабочей — тем более.
 */
class RelayLiveLinkTest {

    /** Свой токен на прогон: чужие письма в общем ящике нас не касаются, наши — никого. */
    private val token = "live-link-${System.nanoTime()}"
    private val base = LiveServer.url.trimEnd('/')

    private var poller: RelayRequestPoller? = null

    @After
    fun tearDown() {
        poller?.stop()
    }

    @Test
    fun `компьютер отвечает телефону через релей`() {
        assumeTrue("релей не настроен — пропускаем", LiveServer.configured)
        assumeTrue("релей не отвечает — пропускаем", relayAlive())

        val caps = listOf(
            PcRemoteAction("pc-print", "Напечатать на ПК"),
            PcRemoteAction("pc-office-pdf", "Собрать PDF"),
        )
        val contacts = mutableListOf<Long>()
        poller = RelayRequestPoller(
            relayUrl = LiveServer.url,
            pass = { LiveServer.pass },
            token = token,
            remoteActions = { caps },
            outbox = Outbox(File(System.getProperty("java.io.tmpdir"), "point-live-link-$token")),
            onPhoneCaps = {},
            onContact = { contacts += System.currentTimeMillis() },
        ).also { it.start() }

        val answer = ask(RelayRpc.CAPS)

        assertTrue("компьютер не ответил через релей за $DEADLINE_MS мс", answer != null)
        assertEquals(caps, decodePcCaps(String(answer!!, Charsets.UTF_8)))
        // Ответ дошёл — значит и обратное направление живо: письмо расшифровалось нашим токеном,
        // и сторона ПК обязана была это заметить. Иначе экран связи так и покажет «ни разу».
        assertTrue("контакт не засчитан — экран связи соврёт", contacts.isNotEmpty())
    }

    @Test
    fun `связать устройства можно без локальной сети`() {
        assumeTrue("релей не настроен — пропускаем", LiveServer.configured)
        assumeTrue("релей не отвечает — пропускаем", relayAlive())

        poller = RelayRequestPoller(
            relayUrl = LiveServer.url,
            pass = { LiveServer.pass },
            token = token,
            remoteActions = { emptyList() },
            outbox = Outbox(File(System.getProperty("java.io.tmpdir"), "point-live-pair-$token")),
            onPhoneCaps = {},
        ).also { it.start() }

        // Ответ на «ты там?» — имя компьютера: человек должен увидеть, с чем именно связался.
        val name = askMeta(RelayRpc.PAIR)["name"]

        assertTrue("компьютер не представился при связывании: $name", !name.isNullOrBlank())
    }

    @Test
    fun `очередь на телефон проходит через релей целиком`() {
        assumeTrue("релей не настроен — пропускаем", LiveServer.configured)
        assumeTrue("релей не отвечает — пропускаем", relayAlive())

        val dir = File(System.getProperty("java.io.tmpdir"), "point-live-outbox-$token")
        val outbox = Outbox(File(dir, "outbox"))
        val source = File(dir, "ведомость.txt").apply {
            parentFile.mkdirs()
            writeText("строка, которую компьютер обязан донести до телефона")
        }
        val queued = outbox.add(
            PointObject(
                "live", "text/plain", ScratchRef(source.absolutePath), ObjectState(ObjectKind.TEXT),
                metadata = mapOf("name" to source.name),
            ),
        )

        poller = RelayRequestPoller(
            relayUrl = LiveServer.url,
            pass = { LiveServer.pass },
            token = token,
            remoteActions = { emptyList() },
            outbox = outbox,
            onPhoneCaps = {},
        ).also { it.start() }

        // 1. Что для меня приготовлено.
        val listed = decodePcOutbox(String(ask(RelayRpc.OUTBOX) ?: ByteArray(0), Charsets.UTF_8))
        assertTrue("очередь не доехала: $listed", listed.any { it.id == queued })

        // 2. Отдай его сюда — содержимое обязано дойти дословно, а не «примерно».
        val fetched = exchange(RelayRpc.FETCH, mapOf("id" to queued.toString()))
        assertEquals("файл доехал искажённым", source.readText(), String(fetched?.bytes ?: ByteArray(0), Charsets.UTF_8))
        assertEquals("имя файла потерялось", source.name, fetched?.meta?.get("name"))

        // 3. Забрал — вычеркни. Иначе один и тот же файл будет приезжать вечно.
        exchange(RelayRpc.ACK, mapOf("id" to queued.toString()))
        assertTrue("объект остался в очереди после подтверждения", outbox.entries().none { it.id == queued })
    }

    // --- сторона телефона -------------------------------------------------------------------

    private fun ask(kind: String): ByteArray? = exchange(kind)?.bytes

    private fun askMeta(kind: String): Map<String, String> = exchange(kind)?.meta.orEmpty()

    private fun exchange(kind: String, meta: Map<String, String> = emptyMap()): com.point.core.flow.PcFrame? {
        val requestId = "req-${System.nanoTime()}"
        put(
            RelayCrypto.mailboxId(token, RelayRpc.TO_PC),
            RelayCrypto.seal(
                token,
                encodePcFrame(meta + mapOf(RelayRpc.KIND to kind, RelayRpc.ID to requestId), ByteArray(0)),
            ),
        )

        val deadline = System.currentTimeMillis() + DEADLINE_MS
        val mailbox = RelayCrypto.mailboxId(token, RelayRpc.TO_PHONE)
        while (System.currentTimeMillis() < deadline) {
            val frame = take(mailbox) ?: continue
            if (isOurReply(frame.meta, requestId)) return frame
        }
        return null
    }

    private fun put(mailbox: String, blob: ByteArray) {
        val c = open("$base/mbx/$mailbox", 20)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(blob.size)
        c.outputStream.use { it.write(blob) }
        check(c.responseCode in 200..299) { "релей не принял письмо: ${c.responseCode}" }
        c.disconnect()
    }

    private fun take(mailbox: String): com.point.core.flow.PcFrame? {
        val c = open("$base/mbx/$mailbox?wait=$WAIT_SECONDS", WAIT_SECONDS + 10)
        val blob = if (c.responseCode == 200) c.inputStream.readBytes() else null
        val blobId = c.getHeaderField("X-Blob-Id")
        c.disconnect()
        if (blob == null) return null
        blobId?.let { ackBlob(mailbox, it) }
        return runCatching { decodePcFrame(RelayCrypto.open(token, blob)) }.getOrNull()
    }

    private fun ackBlob(mailbox: String, blobId: String) {
        runCatching {
            val c = open("$base/mbx/$mailbox/ack", 10)
            c.requestMethod = "POST"
            c.setRequestProperty("X-Blob-Id", blobId)
            c.doOutput = true
            c.outputStream.use { it.write(ByteArray(0)) }
            c.responseCode
            c.disconnect()
        }
    }

    private fun relayAlive(): Boolean = runCatching {
        val c = open("$base/health", 8)
        val alive = c.responseCode == 200
        c.disconnect()
        alive
    }.getOrDefault(false)

    private fun open(url: String, readSeconds: Int): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = readSeconds * 1000
            setRequestProperty("Authorization", "Bearer " + LiveServer.pass)
        }

    private companion object {
        const val WAIT_SECONDS = 10
        const val DEADLINE_MS = 45_000L
    }
}
