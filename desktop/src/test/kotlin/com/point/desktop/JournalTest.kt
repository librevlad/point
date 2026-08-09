package com.point.desktop

import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JournalTest {

    @get:Rule val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Kyiv")

    private fun at(day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 8, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun entry(path: String, name: String = "накладная.pdf", at: Long = 1_000L) = JournalEntry(
        path = path,
        name = name,
        kind = ObjectKind.PDF.name,
        mime = "application/pdf",
        source = ObjectSource.PHONE_LAN,
        at = at,
    )

    private fun step(title: String, at: Long = 2_000L, ok: Boolean = true, note: String = "готово") =
        JournalStep("pc-print", title, at, ok, note)

    @Test
    fun `приехавший объект встаёт первым`() {
        val entries = recordArrival(recordArrival(emptyList(), entry("/a")), entry("/b"))

        assertEquals(listOf("/b", "/a"), entries.map { it.path })
    }

    @Test
    fun `повторный приезд того же файла не заводит вторую запись и сохраняет станции`() {
        val first = recordArrival(emptyList(), entry("/a", at = 100L))
        val withStep = recordStep(first, "/a", step("Напечатать"))

        val again = recordArrival(withStep, entry("/a", at = 500L).copy(source = ObjectSource.DROPPED))

        assertEquals(1, again.size)
        assertEquals(listOf("Напечатать"), again.single().steps.map { it.title })
        assertEquals(500L, again.single().at)
        assertEquals(ObjectSource.DROPPED, again.single().source)
    }

    @Test
    fun `журнал держит только последние записи`() {
        val entries = (1..5).fold(emptyList<JournalEntry>()) { acc, i ->
            recordArrival(acc, entry("/$i"), limit = 3)
        }

        assertEquals(listOf("/5", "/4", "/3"), entries.map { it.path })
    }

    @Test
    fun `у одного объекта хранится ограниченное число станций - остаются последние`() {
        val entries = (1..5).fold(recordArrival(emptyList(), entry("/a"))) { acc, i ->
            recordStep(acc, "/a", step("шаг $i"), stepsLimit = 3)
        }

        assertEquals(listOf("шаг 3", "шаг 4", "шаг 5"), entries.single().steps.map { it.title })
    }

    @Test
    fun `шаг к неизвестному объекту не создаёт запись`() {
        val entries = recordStep(recordArrival(emptyList(), entry("/a")), "/чужой", step("Напечатать"))

        assertEquals(1, entries.size)
        assertTrue(entries.single().steps.isEmpty())
    }

    @Test
    fun `провал остаётся в пути с причиной, а не исчезает`() {
        val failed = stepOf("pc-print", "Напечатать", 7L, ActionResult.Failure("нет принтера", recoverable = true))

        assertFalse(failed.ok)
        assertEquals("нет принтера", failed.note)
    }

    @Test
    fun `удачный шаг несёт сказанное действием`() {
        val done = stepOf("pc-copy", "Копировать", 7L, ActionResult.Done("Скопировано в буфер"))

        assertTrue(done.ok)
        assertEquals("Скопировано в буфер", done.note)
    }

    @Test
    fun `остановка на вопросе не считается сделанной работой`() {
        val asked = stepOf("ai", "Спросить AI", 7L, ActionResult.NeedsInput("О чём спросить?"))

        assertFalse(asked.ok)
        assertTrue(asked.note.contains("О чём спросить?"))
    }

    @Test
    fun `новый объект от действия отмечается как сделанная работа`() {
        val made = stepOf(
            "pc-office-pdf", "Сделать PDF", 7L,
            ActionResult.Success(ResultObject(ObjectKind.PDF, "application/pdf", ScratchRef("/tmp/out.pdf"))),
        )

        assertTrue(made.ok)
    }

    @Test
    fun `в списке было-раньше нет того, что сейчас на экране`() {
        val entries = recordArrival(recordArrival(emptyList(), entry("/a")), entry("/b"))

        assertEquals(listOf("/a"), recentBesides(entries, livePaths = setOf("/b")).map { it.path })
    }

    @Test
    fun `запись со станциями переживает запись и чтение`() {
        val entries = recordStep(
            recordArrival(emptyList(), entry("/дом/накладная.pdf", name = "накладная.pdf", at = 111L)),
            "/дом/накладная.pdf",
            step("Напечатать", at = 222L, ok = false, note = "нет принтера"),
        )

        val back = decodeJournal(encodeJournal(entries))

        assertEquals(entries, back)
    }

    @Test
    fun `перенос строки и таб внутри причины не ломают журнал`() {
        val entries = recordStep(
            recordArrival(recordArrival(emptyList(), entry("/a")), entry("/b")),
            "/b",
            step("Открыть", note = "не вышло:\nфайла\tнет"),
        )

        val back = decodeJournal(encodeJournal(entries))

        assertEquals(listOf("/b", "/a"), back.map { it.path })
        assertEquals("не вышло: файла\tнет", back.first().steps.single().note)
    }

    @Test
    fun `знание объекта переживает запись и чтение — включая многострочные значения`() {
        val entries = recordArrival(
            emptyList(),
            entry("/дом/квитанция.jpg").copy(
                meta = mapOf(
                    "entity.phone" to "+380222222222",
                    "text.value" to "строка 1\nстрока 2",
                    "name" to "Квитанция",
                ),
            ),
        )

        assertEquals(entries, decodeJournal(encodeJournal(entries)))
    }

    @Test
    fun `повторный приезд не стирает журнальное знание, свежее знание побеждает`() {
        val first = recordArrival(
            emptyList(),
            entry("/a").copy(meta = mapOf("entity.phone" to "+380111111111", "ai.verdict" to "Квитанция")),
        )

        val again = recordArrival(first, entry("/a").copy(meta = mapOf("entity.phone" to "+380222222222")))

        assertEquals(
            mapOf("entity.phone" to "+380222222222", "ai.verdict" to "Квитанция"),
            again.single().meta,
        )
    }

    @Test
    fun `огромное значение в журнал не пишется, остальное знание остаётся`() {
        val entries = recordArrival(
            emptyList(),
            entry("/a").copy(
                meta = mapOf("text.value" to "х".repeat(10_000), "entity.phone" to "+380222222222"),
            ),
        )

        val back = decodeJournal(encodeJournal(entries))

        assertEquals(mapOf("entity.phone" to "+380222222222"), back.single().meta)
    }

    @Test
    fun `битая строка выбрасывается, остальная память остаётся`() {
        val good = encodeJournal(listOf(entry("/a"), entry("/b")))

        val back = decodeJournal(good.lines().first() + "\n@@не base64@@\n" + good.lines().last())

        assertEquals(listOf("/a", "/b"), back.map { it.path })
    }

    @Test
    fun `неизвестное происхождение читается как с этого компьютера`() {
        val line = encodeJournal(listOf(entry("/a"))).let {
            java.util.Base64.getEncoder().encodeToString(
                String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8)
                    .replace("source=PHONE_LAN", "source=TELEPORT").toByteArray(Charsets.UTF_8),
            )
        }

        assertEquals(ObjectSource.LOCAL, decodeJournal(line).single().source)
    }

    @Test
    fun `файл журнала читается обратно, а отсутствующий файл даёт пустую память`() {
        val file = File(tmp.root, "state/journal")
        val store = FileJournalStore(file)
        assertTrue(store.load().isEmpty())

        val entries = recordStep(recordArrival(emptyList(), entry("/a")), "/a", step("Напечатать"))
        store.save(entries)

        assertEquals(entries, FileJournalStore(file).load())
    }

    @Test
    fun `происхождение названо словами продукта, а не транспортом`() {

        assertEquals("с телефона", sourceLabel(ObjectSource.PHONE_LAN))
        assertEquals("с телефона", sourceLabel(ObjectSource.PHONE_RELAY))
        assertEquals("взят из буфера", sourceLabel(ObjectSource.CLIPBOARD))
        assertEquals("с телефона", sourceShort(ObjectSource.PHONE_RELAY))
    }

    @Test
    fun `станции считаются по-русски`() {
        assertEquals("1 действие", stepsWord(1))
        assertEquals("2 действия", stepsWord(2))
        assertEquals("5 действий", stepsWord(5))
        assertEquals("11 действий", stepsWord(11))
        assertEquals("21 действие", stepsWord(21))
    }

    @Test
    fun `время сегодня, вчера и раньше называется по-человечески`() {
        val now = at(4, 12, 0)

        assertEquals("сегодня 09:05", whenLabel(at(4, 9, 5), now, zone))
        assertEquals("вчера 23:40", whenLabel(at(3, 23, 40), now, zone))
        assertEquals("1 августа · 08:00", whenLabel(at(1, 8, 0), now, zone))
    }

    @Test
    fun `у прошлогодней записи назван год`() {
        val lastYear = ZonedDateTime.of(2025, 12, 31, 18, 30, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals("31 декабря 2025 · 18:30", whenLabel(lastYear, at(4, 12, 0), zone))
    }
}
