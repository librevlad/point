package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Бюджет ответа телефону — не таймаут работы (Product Constitution PC4): долгое
 * действие не обрывается, телефону сразу уходит честное «ещё работаю», а готовый
 * результат уезжает существующей очередью ПК→телефон вместе со знанием.
 */
class SlowPcActionTest {

    @get:Rule val temp = TemporaryFolder()

    private class Says(id: String) : Capability {
        override val id = CapabilityId(id)
        override val icon = "x"
        override val meta = CapabilityMeta()
        override fun label(state: ObjectState) = id.value
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)
    }

    private class Slow(
        id: String,
        private val dir: File,
        private val delayMs: Long,
        private val fail: Boolean = false,

        /** Шаг состоялся словами, без объекта — как отмена у диалога «Сохранить в…» (#1073). */
        private val saysDone: ActionResult.Done? = null,

        /**
         * Пока эта дверь закрыта, работа не кончается (#1299).
         *
         * Так «долгое действие» становится событием, а не сроком: тест открывает дверь, когда
         * ему нужно, и не гадает, успеет ли машина за отведённые миллисекунды.
         */
        private val holds: kotlinx.coroutines.CompletableDeferred<Unit>? = null,

        /** Работа рождает набор — папку страниц, как «Страницы» и «Слайды» (#1414). */
        private val makesSet: Boolean = false,
    ) : Realizer {
        override val capabilityId = CapabilityId(id)

        @Volatile var finished = false
        @Volatile var cancelled = false

        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            try {
                if (holds != null) holds.await() else kotlinx.coroutines.delay(delayMs)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
            finished = true
            if (fail) return ActionResult.Failure("сервис не ответил", recoverable = true)
            saysDone?.let { return it }
            if (makesSet) {
                val pages = File(dir, "pages").apply { mkdirs() }
                listOf("p10.png", "p2.png", "p1.png").forEach { File(pages, it).writeText(it) }
                return ActionResult.Success(
                    ResultObject(
                        ObjectKind.COLLECTION, "inode/directory", ScratchRef(pages.absolutePath),
                        mapOf(
                            "name" to "pages-1168",
                            com.point.core.flow.META_COLLECTION_ORDER to
                                com.point.core.flow.collectionOrderValue(listOf("p1.png", "p2.png", "p10.png")),
                        ),
                    ),
                )
            }
            val out = File(dir, "result.txt").apply { writeText("готово") }
            return ActionResult.Success(
                ResultObject(
                    ObjectKind.TEXT, "text/plain", ScratchRef(out.absolutePath),
                    mapOf("name" to "Текст со снимка", "entity.phone" to "+380671234567"),
                ),
            )
        }
    }

    private class Pick(private val byId: Map<String, Realizer>) : Resolver {
        override fun realizerFor(capabilityId: CapabilityId): Realizer = byId.getValue(capabilityId.value)
    }

    private fun waitUntil(timeoutMs: Long = 5_000, what: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!what() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("не дождались условия за ${timeoutMs}мс", what())
    }

    private val knocks = java.util.concurrent.atomic.AtomicInteger()

    private fun harness(realizer: Slow, outbox: Outbox): Pair<DesktopState, InboxItem> {
        val state = DesktopState(
            DesktopRegistry(setOf(Says(realizer.capabilityId.value))),
            Pick(mapOf(realizer.capabilityId.value to realizer)),
            clipboard = { },
            outbox = outbox,
            knockPhone = {
                knocks.incrementAndGet()
                emptyMap()
            },
        )
        val source = temp.newFile("исходный.jpg").apply { writeText("байты") }
        val item = InboxItem(
            PointObject("in", "image/jpeg", ScratchRef(source.absolutePath), ObjectState(ObjectKind.IMAGE)),
        )
        return state to item
    }

    /**
     * Работа уложилась в бюджет — телефон получает результат (#1299).
     *
     * Срок здесь не наступает вовсе: под тестом он событие, а не секунды на занятой машине.
     */
    @Test
    fun `быстрое действие отвечает результатом, как раньше`() {
        val outbox = Outbox(temp.newFolder("out-fast"))
        val slow = Slow("read", temp.newFolder("r1"), delayMs = 0)
        val (state, item) = harness(slow, outbox)
        state.budgetTimer = { kotlinx.coroutines.awaitCancellation() }

        val result = state.runRemoteActionNow("read", item, 5_000)

        assertTrue(result is ActionResult.Success)
        assertTrue(outbox.entries().isEmpty())
    }

    /**
     * Бюджет вышел раньше работы — и работа не обрывается (#1299).
     *
     * Оба события здесь названы, а не отмерены: срок наступает сразу, а работа кончается
     * тогда, когда тест откроет ей дверь. Прежде это была гонка 400 мс против 50, и на
     * занятой машине она решалась не в ту сторону молча.
     */
    @Test
    fun `долгое действие не обрывается — телефону честный ответ, результат в очереди со знанием`() {
        val outbox = Outbox(temp.newFolder("out-slow"))
        val until = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = Slow("read", temp.newFolder("r2"), delayMs = 0, holds = until)
        val (state, item) = harness(slow, outbox)
        state.budgetTimer = { }

        val result = state.runRemoteActionNow("read", item, 5_000)

        assertEquals(com.point.core.flow.PC_STILL_WORKING, (result as ActionResult.Done).message)
        assertFalse("работа кончилась раньше, чем её отпустили", slow.finished)

        until.complete(Unit)
        waitUntil { slow.finished && outbox.entries().isNotEmpty() }
        assertFalse("работа не смеет отменяться бюджетом ответа", slow.cancelled)
        val entry = outbox.entries().single()
        assertEquals("Текст со снимка", entry.meta["name"])
        assertEquals("знание едет вместе с результатом", "+380671234567", entry.meta["entity.phone"])
    }

    /**
     * Готовый файл просьбы не привязывался к ней (#1416, живая охота 03.09): телефон десять
     * раз видел его в очереди, а объект говорил «пока не ответил». Вещь несёт дом и название
     * просьбы так же, как исход словами, — и компьютер стучит телефону, что готово.
     */
    @Test
    fun `долгий результат несёт дом своей просьбы, и компьютер стучит телефону`() {
        val outbox = Outbox(temp.newFolder("out-home"))
        val until = kotlinx.coroutines.CompletableDeferred<Unit>()
        val made = temp.newFolder("r6")
        val slow = Slow("pdf", made, delayMs = 0, holds = until)
        val (state, item) = harness(slow, outbox)
        val fromPhone = item.copy(obj = item.obj.copy(metadata = mapOf(com.point.core.flow.META_ORIGIN_ID to "phone-obj")))
        state.budgetTimer = { }

        state.runRemoteActionNow("pdf", fromPhone, 5_000)
        until.complete(Unit)

        waitUntil { slow.finished && outbox.entries().isNotEmpty() }
        val entry = outbox.entries().single()
        val e = com.point.core.flow.PcExecFields
        assertEquals("вещь не знает, к чьей просьбе она", "phone-obj", entry.meta[e.HOME])
        assertEquals(phoneFacingLabel("pdf", "pdf"), entry.meta[e.LABEL])
        assertTrue("телефон не узнает в ней ответ своей просьбы", com.point.core.flow.answersRequest(entry.meta))
        assertEquals("готовое и есть файл", File(made, "result.txt").readText(), outbox.file(entry.id)!!.readText())
        waitUntil { knocks.get() == 1 }
    }

    /**
     * Набор страниц доезжает целиком (#1414): прежде очередь клала пустую папку, и телефон
     * ждал вечно, а забор с главного экрана упирался в неё.
     */
    @Test
    fun `долгий набор уезжает архивом в порядке страниц, а не пустой папкой`() {
        val outbox = Outbox(temp.newFolder("out-set"))
        val until = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = Slow("pdf-pages", temp.newFolder("r7"), delayMs = 0, holds = until, makesSet = true)
        val (state, item) = harness(slow, outbox)
        state.budgetTimer = { }

        state.runRemoteActionNow("pdf-pages", item, 5_000)
        until.complete(Unit)

        waitUntil { slow.finished && outbox.entries().isNotEmpty() }
        val entry = outbox.entries().single()
        assertEquals("pages-1168.zip", entry.meta["name"])
        assertEquals("application/zip", entry.meta["mime"])
        val names = java.util.zip.ZipFile(outbox.file(entry.id)!!).use { zip -> zip.entries().toList().map { it.name } }
        assertEquals(listOf("p1.png", "p2.png", "p10.png"), names)
    }

    /**
     * Любой исход просьбы соседу возвращается телефону (#1073, решение владельца) — и отказ
     * тоже: прежде долгий провал не клал в очередь ничего, и телефон вечно ждал обещанного.
     */
    @Test
    fun `долгий отказ едет телефону исходом без объекта, а не молчанием`() {
        val outbox = Outbox(temp.newFolder("out-fail"))
        val until = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = Slow("read", temp.newFolder("r3"), delayMs = 0, fail = true, holds = until)
        val (state, item) = harness(slow, outbox)
        state.budgetTimer = { }

        val result = state.runRemoteActionNow("read", item, 5_000)
        until.complete(Unit)

        assertEquals(com.point.core.flow.PC_STILL_WORKING, (result as ActionResult.Done).message)
        waitUntil { slow.finished && outbox.entries().isNotEmpty() }
        val entry = outbox.entries().single()
        val f = com.point.core.flow.PcResultFields
        assertTrue("исход без объекта — слова домой, не вещь", f.outcomeOnly(entry.meta))
        assertEquals(com.point.core.flow.PcActionOutcome.Failed("сервис не ответил"), f.outcomeOf(entry.meta))
        assertNull("файла у исхода нет", outbox.file(entry.id))
    }

    /**
     * Отмена у системного диалога на компьютере — `Done("Отменено")` без объекта (#1073): до
     * телефона она не доезжала никак, и «ещё работает» висело там навсегда. Теперь исход
     * едет той же очередью, к своему объекту, со знанием, если оно было.
     */
    @Test
    fun `долгое «готово» без объекта — отмена у диалога — едет телефону к своему объекту`() {
        val outbox = Outbox(temp.newFolder("out-cancel"))
        val cancelled = com.point.core.flow.PC_CANCELLED
        val until = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = Slow(
            "pc-save-as", temp.newFolder("r5"), delayMs = 0,
            saysDone = ActionResult.Done(
                cancelled,
                com.point.core.model.Findings(metadata = mapOf("entity.phone" to "+380671234567")),
            ),
            holds = until,
        )
        val (state, item) = harness(slow, outbox)
        val fromPhone = item.copy(obj = item.obj.copy(metadata = mapOf(com.point.core.flow.META_ORIGIN_ID to "phone-obj")))
        state.budgetTimer = { }

        val result = state.runRemoteActionNow("pc-save-as", fromPhone, 5_000)
        until.complete(Unit)

        assertEquals(com.point.core.flow.PC_STILL_WORKING, (result as ActionResult.Done).message)
        waitUntil { slow.finished && outbox.entries().isNotEmpty() }
        val entry = outbox.entries().single()
        val f = com.point.core.flow.PcResultFields
        assertEquals(com.point.core.flow.PcActionOutcome.Done(cancelled), f.outcomeOf(entry.meta))
        assertEquals("исход едет к объекту телефона, а не к копии на компьютере", "phone-obj", entry.meta[com.point.core.flow.PcExecFields.HOME])

        // Просьба названа так, как её видел человек на телефоне: он нажимал «Сохранить на
        // компьютере», а не то, как это же умение зовётся здесь.
        assertEquals(
            "исход называет просьбу не тем именем, под которым она объявлена телефону",
            phoneFacingLabel("pc-save-as", "pc-save-as"),
            entry.meta[com.point.core.flow.PcExecFields.LABEL],
        )
        assertEquals("понятое едет вместе с исходом", "+380671234567", entry.meta[f.UNDERSTOOD + "entity.phone"])
        assertNull(outbox.file(entry.id))
    }

    /**
     * Два исхода одного диалога «Сохранить в…» (#1073): человек передумал — слово отмены
     * объявленное, и телефон по нему знает, что говорить не о чем; человек сохранил —
     * компьютер называет место, и это место телефон человеку повторяет (PC3).
     */
    @Test
    fun `сохранение называет место, а отмена — объявленное слово`() {
        val file = temp.newFile("акт.docx")
        val obj = PointObject("o-1", "application/octet-stream", ScratchRef(file.absolutePath), ObjectState(ObjectKind.OFFICE))
        val where = "C:/Users/User/Документы/акт.docx"

        val saved = kotlinx.coroutines.runBlocking { PcSaveAsRealizer { where }.perform(obj, null) }
        assertEquals("человек не знает, где искать файл", "Сохранено: $where", (saved as ActionResult.Done).message)

        val gaveUp = kotlinx.coroutines.runBlocking { PcSaveAsRealizer { null }.perform(obj, null) }
        assertEquals(com.point.core.flow.PC_CANCELLED, (gaveUp as ActionResult.Done).message)
    }

    @Test
    fun `очередь не приняла результат — компьютер говорит об этом, а не молчит`() {
        val broken = Outbox(temp.newFile("не-папка"))
        val until = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = Slow("read", temp.newFolder("r4"), delayMs = 0, holds = until)
        val (state, item) = harness(slow, broken)
        state.budgetTimer = { }

        state.runRemoteActionNow("read", item, 5_000)
        until.complete(Unit)

        waitUntil { slow.finished }
        waitUntil {
            state.message.value == "Результат не лёг в очередь для телефона — проверьте, что на диске есть место"
        }
    }
}

/**
 * «На телефон» — действие самого компьютера: рекламировать его телефону бессмысленно
 * (объект и так на телефоне; на экране телефона строка звучала «На телефон на ПК»).
 */
class PcCapsAdvertisedTest {
    @org.junit.Test
    fun `на телефон не рекламируется телефону`() {
        val advertised = com.point.core.flow.advertisedActions(setOf(PcToPhoneCapability()))
        org.junit.Assert.assertTrue(advertised.none { it.id == "pc-to-phone" })
    }
}
