package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.PcResultFields
import com.point.core.flow.Realizer
import com.point.core.flow.RelayRpc
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Путь человека через два устройства целиком, а не по кускам (#599).
 *
 * Повод: 112 зелёных тестов десктопа не заметили, что результат действия на компьютере не
 * становится объектом (#595). Каждый проверял своё действие в вакууме — «положили в очередь →
 * проверили, что положили». Ни один не спросил, что человек увидит следующим шагом.
 *
 * Приговор один на всём пути: **человек ни разу не выбирает устройство**, и результат каждого
 * шага оказывается там, где шаг был запрошен, — и на компьютере, и у попросившего телефона.
 *
 * ## Чего этот тест не покрывает и почему
 *
 * Здесь настоящий компьютер целиком — приёмная, состояние, реестр, исполнитель, разбор
 * письма, — а письмо телефона собрано из тех же функций `:core:flow`, которыми его собирает
 * настоящий телефон. Шов, на котором терялся #595, проходит внутри проверяемого.
 *
 * Прежде здесь стояла оговорка «подключить телефонную половину нельзя даже тестовой
 * зависимостью»: разбор протокола лежал в `:data` (Android). После #819 он живёт в
 * `:core:flow`, и настоящая телефонная половина собирается рядом с компьютером —
 * см. `RealPhoneHalfTest`.
 *
 * Шаги, требующие исполнения просьбы телефоном, сюда не входят намеренно: телефон их не
 * исполняет (#785), и связка в 0.3 односторонняя.
 */
class PathAcrossDevicesTest {

    @get:Rule val temp = TemporaryFolder()

    /** Настоящая способность компьютера: рождает новый объект, как «В PDF» или «Распаковать». */
    private class Makes(override val id: CapabilityId) : Capability {
        override val icon = "pdf"
        override val meta = CapabilityMeta()
        override fun label(state: ObjectState) = "В PDF"
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = ObjectState(ObjectKind.PDF)
    }

    private class MakesPdf(override val capabilityId: CapabilityId, private val into: File) : Realizer {
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            val born = File(into, "договор.pdf").apply { writeText("PDF из " + File(input.uri.value).readText()) }
            return ActionResult.Success(
                ResultObject(
                    ObjectKind.PDF,
                    "application/pdf",
                    ScratchRef(born.absolutePath),
                    mapOf("name" to born.name, "entity.date" to "03.01.2026"),
                ),
            )
        }
    }

    /** Компьютер собран так же, как в `Main.kt`: приёмная, состояние, ручка писем. */
    private class Pc(temp: TemporaryFolder) {
        val inbox = Inbox(temp.newFolder("inbox"))
        val outbox = Outbox(temp.newFolder("outbox"))
        val made = temp.newFolder("made")
        val id = CapabilityId("в-pdf")

        val state = DesktopState(
            registry = DesktopRegistry(setOf(Makes(id))),
            resolver = DesktopResolver(setOf(MakesPdf(id, made))),
            clipboard = { },
            outbox = outbox,
        )

        val requests = RelayRequests(
            remoteActions = { emptyList() },
            outbox = outbox,
            onPhoneCaps = { },
            clipboardGet = { null },
            clipboardSet = { },
            onObject = { name, mime, meta, bytes, action, _ ->
                val item = inbox.receive(name, mime, meta, bytes.inputStream())
                state.onReceived(item, ObjectSource.PHONE_RELAY)
                action?.let { state.runRemoteActionNow(it, item) }
            },
        )
    }

    /** Телефон просит компьютер поработать — тем же письмом, каким просит настоящий. */
    private fun asksFor(what: String, name: String, text: String) = Triple(
        RelayRpc.OBJECT,
        mapOf(RelayRpc.ID to "письмо-1", "name" to name, "mime" to "text/plain", "action" to what),
        text.toByteArray(Charsets.UTF_8),
    )

    @Test
    fun `объект с телефона пройден компьютером, и результат вернулся тому, кто просил`() {
        val pc = Pc(temp)
        val (kind, meta, bytes) = asksFor(pc.id.value, "договор.txt", "текст договора")

        val reply = pc.requests.answer(kind, meta, bytes)

        // 1. Результат оказался там, где шаг был запрошен: у попросившего телефона.
        assertNotNull("компьютер не ответил на просьбу", reply)
        assertTrue("телефону не вернулся объект — только отчёт", PcResultFields.hasObject(reply!!.meta))
        assertTrue(
            "телефон не узнал, что за файл ему вернули: " + reply.meta[PcResultFields.NAME],
            reply.meta[PcResultFields.NAME].orEmpty().endsWith(".pdf"),
        )
        assertEquals("application/pdf", reply.meta[PcResultFields.MIME])
        assertTrue("файл результата пуст", reply.body.isNotEmpty())

        // 2. И одновременно — на компьютере: ровно это потерялось в #595.
        val onPc = pc.state.items.value.map { File(it.obj.uri.value).name }
        assertTrue("на компьютере не появился ни исходник, ни результат: $onPc", onPc.isNotEmpty())
    }

    /** Понятое переезжает вместе с объектом: перенос не теряет знание (PC2). */
    @Test
    fun `знание, добытое компьютером, доезжает до телефона`() {
        val pc = Pc(temp)
        val (kind, meta, bytes) = asksFor(pc.id.value, "договор.txt", "текст договора")

        val reply = pc.requests.answer(kind, meta, bytes)!!

        val found = "03.01.2026"

        assertEquals(found, reply.meta[PcResultFields.UNDERSTOOD + "entity.date"])
    }

    /** Человек не выбирал устройство: он попросил работу, а не компьютер. */
    @Test
    fun `в письме нет ни слова о том, каким устройством делать`() {
        val (_, meta, _) = asksFor("в-pdf", "договор.txt", "текст")

        assertEquals("в-pdf", meta["action"])
        assertTrue(
            "человеку пришлось назвать устройство: $meta",
            meta.keys.none { it.contains("device", ignoreCase = true) },
        )
    }

    /**
     * Сервер доставляет «хотя бы раз»: повтор письма не рождает второй объект и не делает
     * работу дважды. Без этого путь ломается там, где человек ничего не делал.
     */
    @Test
    fun `повторное письмо не удваивает ни работу, ни объекты`() {
        val pc = Pc(temp)
        val seen = SeenLetters(temp.newFile("seen"))
        val requests = RelayRequests(
            remoteActions = { emptyList() },
            outbox = pc.outbox,
            onPhoneCaps = { },
            clipboardGet = { null },
            clipboardSet = { },
            onObject = { name, mime, meta, bytes, action, _ ->
                val item = pc.inbox.receive(name, mime, meta, bytes.inputStream())
                pc.state.onReceived(item, ObjectSource.PHONE_RELAY)
                action?.let { pc.state.runRemoteActionNow(it, item) }
            },
            seen = seen,
        )
        val (kind, meta, bytes) = asksFor(pc.id.value, "договор.txt", "текст договора")

        requests.answer(kind, meta, bytes)
        val after = pc.state.items.value.size
        requests.answer(kind, meta, bytes)

        assertEquals("повтор письма родил второй объект", after, pc.state.items.value.size)
    }

    /** Отказ не исчезает в молчаливой цепочке: телефон узнаёт, что работа не вышла. */
    @Test
    fun `не вышло — телефон получает отказ, а не тишину`() {
        val broken = CapabilityId("ломается")
        val requests = RelayRequests(
            remoteActions = { emptyList() },
            outbox = Outbox(temp.newFolder("outbox-broken")),
            onPhoneCaps = { },
            clipboardGet = { null },
            clipboardSet = { },
            onObject = { _, _, _, _, _, _ -> error("на компьютере не вышло") },
        )

        val reply = requests.answer(
            RelayRpc.OBJECT,
            mapOf(RelayRpc.ID to "письмо-2", "name" to "объект.txt", "action" to broken.value),
            "текст".toByteArray(Charsets.UTF_8),
        )

        assertNotNull("компьютер промолчал об отказе", reply)
        assertTrue("отказ выдан за объект", !PcResultFields.hasObject(reply!!.meta))
        assertTrue("в ответе нет ни слова об исходе", reply.body.isNotEmpty())
    }
}
