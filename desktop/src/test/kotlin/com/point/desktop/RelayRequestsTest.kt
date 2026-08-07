package com.point.desktop

import com.point.core.flow.ClipboardPayload
import com.point.core.flow.PcActionOutcome
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.RelayRpc
import com.point.core.flow.clipMeta
import com.point.core.flow.clipPayloadOf
import com.point.core.flow.decodePcCaps
import com.point.core.flow.decodePcReceiveReply
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RelayRequestsTest {

    @get:Rule val tmp = TemporaryFolder()

    private var clip: ClipboardPayload? = null
    private var phoneCaps: List<PcRemoteAction> = emptyList()
    private val received = mutableListOf<Triple<String, String, String>>()
    private var result: com.point.core.model.ActionResult? = null

    private val actions = listOf(
        PcRemoteAction("pc-open", "Открыть на компьютере"),
        PcRemoteAction("pc-print", "Напечатать на ПК", unavailable = "на компьютере нет принтера"),
    )

    private fun requests(outbox: Outbox = Outbox(tmp.newFolder())) = RelayRequests(
        remoteActions = { actions },
        outbox = outbox,
        onPhoneCaps = { phoneCaps = it },
        clipboardGet = { clip },
        clipboardSet = { clip = it },
        onObject = { name, mime, meta, bytes, action ->
            received += Triple(name, mime, String(bytes, Charsets.UTF_8))
            assertEquals("понимание доезжает целиком", "693,40", meta["entity.money"])
            assertNull("служебные поля дороги в объект не попадают", meta[RelayRpc.KIND])
            if (action != null) result else null
        },
    )

    @Test
    fun `объект становится объектом, и в ответ едет исход, а не факт доставки`() {
        result = com.point.core.model.ActionResult.Done("В очереди «HP LaserJet»")

        val reply = requests().answer(
            RelayRpc.OBJECT,
            mapOf("name" to "чек.txt", "mime" to "text/plain", "action" to "pc-print", "entity.money" to "693,40"),
            "чек".toByteArray(Charsets.UTF_8),
        )

        assertEquals(listOf(Triple("чек.txt", "text/plain", "чек")), received)
        assertEquals(
            PcActionOutcome.Done("В очереди «HP LaserJet»"),
            decodePcReceiveReply(String(reply!!.body, Charsets.UTF_8)),
        )
    }

    @Test
    fun `действия не заказывали — исход неизвестен, и телефон скажет «отправлено»`() {
        val reply = requests().answer(
            RelayRpc.OBJECT,
            mapOf("name" to "чек.txt", "mime" to "text/plain", "entity.money" to "693,40"),
            "чек".toByteArray(Charsets.UTF_8),
        )

        assertNull(decodePcReceiveReply(String(reply!!.body, Charsets.UTF_8)))
    }

    @Test
    fun `компьютер не смог принять объект — это отказ, а не молчание`() {
        val broken = RelayRequests(
            remoteActions = { actions },
            outbox = Outbox(tmp.newFolder()),
            onPhoneCaps = {},
            clipboardGet = { null },
            clipboardSet = {},
            onObject = { _, _, _, _, _ -> error("диск переполнен") },
        )

        val reply = broken.answer(RelayRpc.OBJECT, mapOf("name" to "ч.txt"), ByteArray(0))

        val said = decodePcReceiveReply(String(reply!!.body, Charsets.UTF_8))
        assertTrue("телефон обязан узнать про отказ", said is PcActionOutcome.Failed)
    }

    @Test
    fun `на вопрос о возможностях едет тот же список, что видит человек`() {
        val reply = requests().answer(RelayRpc.CAPS, emptyMap(), ByteArray(0))

        assertEquals(actions, decodePcCaps(String(reply!!.body, Charsets.UTF_8)))
    }

    @Test
    fun `буфер кладётся и отдаётся, а пустой буфер отвечает пустотой`() {
        val requests = requests()
        val crossed = ClipboardPayload.ofText("+380671234567")

        requests.answer(RelayRpc.CLIP_PUSH, clipMeta(crossed), crossed.bytes)
        assertEquals("+380671234567", clip?.text())

        val answered = requests.answer(RelayRpc.CLIP_PULL, emptyMap(), ByteArray(0))!!
        assertEquals("+380671234567", clipPayloadOf(answered.meta, answered.body)?.text())

        clip = null
        val empty = requests.answer(RelayRpc.CLIP_PULL, emptyMap(), ByteArray(0))!!
        assertNull("пусто — это ответ, а не отказ", clipPayloadOf(empty.meta, empty.body))
        assertNotNull("ответ обязан быть, иначе телефон решит, что компьютер не запущен", empty)
    }

    @Test
    fun `телефон рассказал, что умеет, — компьютер это запомнил`() {
        requests().answer(
            RelayRpc.PHONE_CAPS,
            emptyMap(),
            com.point.core.flow.encodePcCaps(listOf(PcRemoteAction("call", "Позвонить"))).toByteArray(Charsets.UTF_8),
        )

        assertEquals(listOf("call"), phoneCaps.map { it.id })
    }

    @Test
    fun `очередь на телефон отдаётся с человеческим именем, а не служебным номером`() {
        val outbox = Outbox(tmp.newFolder())
        val file = File(tmp.newFolder(), "смета.txt").apply { writeText("итог") }
        outbox.add(
            com.point.core.model.PointObject(
                "o", "text/plain", com.point.core.model.ScratchRef(file.absolutePath),
                com.point.core.model.ObjectState(com.point.core.model.ObjectKind.TEXT),
                mapOf("name" to "смета.txt"),
            ),
        )
        val requests = requests(outbox)

        val listing = requests.answer(RelayRpc.OUTBOX, emptyMap(), ByteArray(0))!!
        val entries = com.point.core.flow.decodePcOutbox(String(listing.body, Charsets.UTF_8))
        assertEquals(listOf("смета.txt"), entries.map { it.meta["name"] })

        val fetched = requests.answer(RelayRpc.FETCH, mapOf("id" to entries[0].id.toString()), ByteArray(0))!!
        assertEquals("смета.txt", fetched.meta["name"])
        assertEquals("итог", String(fetched.body, Charsets.UTF_8))

        requests.answer(RelayRpc.ACK, mapOf("id" to entries[0].id.toString()), ByteArray(0))
        assertTrue("подтверждённое уходит из очереди", outbox.entries().isEmpty())
    }

    @Test
    fun `нет такого объекта в очереди — сказано словами, а не пустым файлом`() {
        val reply = requests().answer(RelayRpc.FETCH, mapOf("id" to "42"), ByteArray(0))!!

        assertEquals("нет такого объекта", reply.meta["error"])
        assertEquals(0, reply.body.size)
    }

    @Test
    fun `незнакомый вид письма остаётся без ответа — отвечать за будущее мы не будем`() {
        assertNull(requests().answer("что-то-из-завтра", emptyMap(), ByteArray(0)))
    }
}
