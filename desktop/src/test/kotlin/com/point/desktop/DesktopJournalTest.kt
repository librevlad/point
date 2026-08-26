package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopJournalTest {

    /**
     * Действие идёт в работе окна, и станция пути появляется, когда работа дошла до
     * журнала. Планировщик теста доводит её до конца — прежде тут стоял опрос со сроком
     * в пять секунд, который на занятой машине кончался раньше самой работы.
     */
    private val dispatcher = StandardTestDispatcher()

    private class FakeJournal(private var entries: List<JournalEntry> = emptyList()) : JournalStore {
        var saves = 0
        override fun load(): List<JournalEntry> = entries
        override fun save(entries: List<JournalEntry>) {
            this.entries = entries
            saves++
        }
    }

    private class RecordingRealizer(
        id: String,
        private val result: ActionResult,
    ) : Realizer {
        override val capabilityId = CapabilityId(id)
        var calls = 0
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            calls++
            return result
        }
    }

    private class TestCapability(id: String, private val name: String) : Capability {
        override val id = CapabilityId(id)
        override val icon = "open"
        override val meta = CapabilityMeta(priority = 10)
        override fun label(state: ObjectState) = name
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
    }

    private fun item(path: String = "/дом/накладная.pdf", at: Long = 1_000L) = InboxItem(
        PointObject(
            id = "obj-${path.hashCode()}",
            mime = "application/pdf",
            uri = ScratchRef(path),
            state = ObjectState(ObjectKind.PDF),
            metadata = mapOf("name" to path.substringAfterLast('/')),
        ),
        receivedAt = at,
    )

    /**
     * Телефон — исполнитель, а не новый дом объекта (ADR-0001 §7).
     *
     * Прежде просьба «сделай у себя» выглядела переездом: телефон открывал объект у себя,
     * делал работу и там же её оставлял, а на компьютере шаг ждал вечно.
     */
    @Test
    fun `результат телефона возвращается к своему объекту на компьютере`() {
        val store = FakeJournal()
        val s = state(store)
        val home = item()
        s.onReceived(home, ObjectSource.LOCAL)

        val done = s.onExecutionResult(
            home.obj.id,
            mapOf(
                com.point.core.flow.PcExecFields.HOME to home.obj.id,
                com.point.core.flow.PcExecFields.ACTION to "cutout",
                com.point.core.flow.PcExecFields.LABEL to "Убрать фон",
                com.point.core.flow.PcResultFields.OUTCOME to com.point.core.flow.PcResultFields.DONE,
                com.point.core.flow.PcResultFields.UNDERSTOOD + "entity.amount" to "7800 UAH",
            ),
            born = null,
        )

        assertTrue(done is ActionResult.Done)
        val landed = s.items.value.single { it.obj.id == home.obj.id }
        assertEquals("7800 UAH", landed.obj.metadata["entity.amount"])
        assertEquals(1, s.items.value.size)
    }

    @Test
    fun `неудача на телефоне возвращается исходом, а не молчанием`() {
        val s = state(FakeJournal())
        val home = item()
        s.onReceived(home, ObjectSource.LOCAL)

        val outcome = s.onExecutionResult(
            home.obj.id,
            mapOf(
                com.point.core.flow.PcExecFields.HOME to home.obj.id,
                com.point.core.flow.PcExecFields.ACTION to "cutout",
                com.point.core.flow.PcResultFields.OUTCOME to com.point.core.flow.PcResultFields.FAILED,
                com.point.core.flow.PcResultFields.DETAIL to "телефон не смог",
            ),
            born = null,
        )

        assertTrue(outcome is ActionResult.Failure)
        val steps = s.journal.value.single { it.path == home.obj.uri.value }.steps
        assertEquals(StepOutcome.FAILED, steps.last().outcome)
    }

    private fun state(
        journal: JournalStore,
        realizers: Set<Realizer> = emptySet(),
        capabilities: Set<Capability> = emptySet(),
        reopen: (String) -> InboxItem? = { null },
    ) = DesktopState(
        registry = DesktopRegistry(capabilities),
        resolver = DesktopResolver(realizers),
        clipboard = { },
        journalStore = journal,
        clock = { 5_000L },
        reopenPath = reopen,
        background = dispatcher,
        io = dispatcher,
    )

    @Test
    fun `приехавший объект попадает в память вместе с тем, откуда он приехал`() {
        val store = FakeJournal()
        val s = state(store)

        s.onReceived(item(at = 777L), ObjectSource.PHONE_RELAY)

        val entry = s.journal.value.single()
        assertEquals("/дом/накладная.pdf", entry.path)
        assertEquals("накладная.pdf", entry.name)
        assertEquals(ObjectSource.PHONE_RELAY, entry.source)
        assertEquals(777L, entry.at)
        assertEquals(1, store.saves)
    }

    @Test
    fun `память компьютера поднимается при старте, а не начинается с нуля`() {
        val remembered = JournalEntry(
            "/дом/счёт.pdf", "счёт.pdf", ObjectKind.PDF.name, "application/pdf",
            ObjectSource.PHONE_LAN, 100L,
        )

        val s = state(FakeJournal(listOf(remembered)))

        assertEquals(listOf(remembered), s.journal.value)
    }

    @Test
    fun `выполненное действие становится станцией пути`() = runTest(dispatcher) {
        val store = FakeJournal()
        val realizer = RecordingRealizer("pc-print", ActionResult.Done("Ушло на HP LaserJet"))
        val s = state(store, realizers = setOf(realizer))
        val obj = item()
        s.onReceived(obj, ObjectSource.PHONE_LAN)

        s.onBubble(obj, Bubble("open", "Напечатать", CapabilityId("pc-print"), obj.obj.state))
        advanceUntilIdle()

        val step = s.journal.value.single().steps.single()
        assertEquals("Напечатать", step.title)
        assertEquals("Ушло на HP LaserJet", step.note)
        assertTrue(step.ok)
        assertEquals(5_000L, step.at)
    }

    @Test
    fun `неудача записывается неудачей и с причиной`() = runTest(dispatcher) {
        val store = FakeJournal()
        val realizer = RecordingRealizer("pc-print", ActionResult.Failure("нет принтера", recoverable = true))
        val s = state(store, realizers = setOf(realizer))
        val obj = item()
        s.onReceived(obj, ObjectSource.PHONE_LAN)

        s.onBubble(obj, Bubble("open", "Напечатать", CapabilityId("pc-print"), obj.obj.state))
        advanceUntilIdle()

        val step = s.journal.value.single().steps.single()
        assertFalse(step.ok)
        assertEquals("нет принтера", step.note)
    }

    @Test
    fun `действие, запущенное с телефона, помечено в пути`() = runTest(dispatcher) {
        val store = FakeJournal()
        val s = state(
            store,
            realizers = setOf(RecordingRealizer("pc-print", ActionResult.Done("Напечатано"))),
            capabilities = setOf(TestCapability("pc-print", "Напечатать")),
        )
        val obj = item()
        s.onReceived(obj, ObjectSource.PHONE_LAN)

        s.runRemoteAction("pc-print", obj)
        advanceUntilIdle()

        assertEquals("Напечатать · с телефона", s.journal.value.single().steps.single().title)
    }

    @Test
    fun `открыть заново возвращает объект на экран и ничего не выполняет`() = runTest(dispatcher) {
        val realizer = RecordingRealizer("pc-print", ActionResult.Done("Напечатано"))
        val remembered = JournalEntry(
            "/дом/счёт.pdf", "счёт.pdf", ObjectKind.PDF.name, "application/pdf",
            ObjectSource.PHONE_LAN, 100L,
            steps = listOf(JournalStep("pc-print", "Напечатать", 200L, StepOutcome.DONE, note = "Напечатано")),
        )
        val store = FakeJournal(listOf(remembered))
        val s = state(store, realizers = setOf(realizer), reopen = { path -> item(path, at = 9_000L) })

        s.openAgain(remembered) { }
        advanceUntilIdle()

        assertEquals(listOf("/дом/счёт.pdf"), s.items.value.map { it.obj.uri.value })
        assertEquals(0, realizer.calls)

        assertEquals(100L, s.journal.value.single().at)
        assertEquals(1, s.journal.value.single().steps.size)
    }

    @Test
    fun `исчезнувший файл говорит об этом словами, а не открывает пустоту`() = runTest(dispatcher) {
        val remembered = JournalEntry(
            "/дом/унесли.pdf", "унесли.pdf", ObjectKind.PDF.name, "application/pdf",
            ObjectSource.DROPPED, 100L,
        )
        val s = state(FakeJournal(listOf(remembered)), reopen = { null })

        s.openAgain(remembered) { }
        advanceUntilIdle()

        assertTrue(s.items.value.isEmpty())
        assertEquals("Файла больше нет: унесли.pdf", s.message.value)
    }

    @Test
    fun `путь показывается тому объекту, чей он есть`() {
        val s = state(FakeJournal())
        val first = item("/дом/а.pdf")
        val second = item("/дом/б.pdf")
        s.onReceived(first, ObjectSource.DROPPED)

        assertEquals("/дом/а.pdf", s.pathOf(first)?.path)
        assertEquals(null, s.pathOf(second))
    }
}
