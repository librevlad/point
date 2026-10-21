package com.point.desktop.ui

import androidx.compose.ui.unit.dp
import com.point.core.flow.ClipboardPayload
import com.point.core.ui.ObjectVerdict
import com.point.desktop.JournalEntry
import com.point.desktop.JournalStep
import com.point.desktop.ObjectSource
import com.point.desktop.ScreenArea
import com.point.desktop.StepOutcome
import com.point.desktop.WINDOW_HEIGHT
import com.point.desktop.WINDOW_WIDTH
import com.point.desktop.windowBounds
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Чистые части окна по макету 19.10.2026: шапка объекта, путь, подсветка, буфер, поиск, место. */
class GlassWindowPartsTest {

    private val zone: ZoneId = ZoneId.of("Europe/Kyiv")

    private fun at(hour: Int, minute: Int): Long =
        LocalDate.of(2026, 10, 19).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `найденное подсвечено там, где сказано, без учёта регистра`() {
        val text = "Доплата 85 грн. Звоните: 067 123 45 67, адрес — Крещатик, 22."

        val ranges = highlightRanges(text, listOf("067 123 45 67", "крещатик, 22"))

        assertEquals(listOf("067 123 45 67", "Крещатик, 22"), ranges.map { text.substring(it) })
    }

    @Test
    fun `подсветки не налезают друг на друга, длинное прочтение выигрывает`() {
        val text = "Встреча 12.10.2026 в 14:00"

        val ranges = highlightRanges(text, listOf("12.10", "12.10.2026"))

        assertEquals(listOf("12.10.2026"), ranges.map { text.substring(it) })
    }

    @Test
    fun `одиночный знак не подсвечивается — он был бы везде`() {
        assertTrue(highlightRanges("5 рублей и 5 копеек", listOf("5")).isEmpty())
    }

    @Test
    fun `значение встречается дважды — подсвечены оба места`() {
        val text = "Код 4411. Повторяю: 4411."

        assertEquals(2, highlightRanges(text, listOf("4411")).size)
    }

    @Test
    fun `вторая строка шапки — мера, подпись и откуда, в этом порядке`() {
        val verdict = ObjectVerdict(HEADLINE, SUBLINE, MEASURE)

        val line = headLine(verdict, ARRIVAL) ?: throw AssertionError("второй строки нет вовсе")
        val at = listOf(MEASURE, SUBLINE, ARRIVAL).map(line::indexOf)
        assertTrue("строка потеряла часть или перепутала порядок: $line", at.none { it < 0 } && at == at.sorted())
        assertNull("пустая строка шапки нарисовалась бы пустым местом", headLine(ObjectVerdict(HEADLINE, null), null))
    }

    @Test
    fun `имя файла не повторяется, если его уже сказала подпись`() {
        val weight = com.point.core.flow.humanWeight(BYTES)!!
        val named = ObjectVerdict(HEADLINE, FILE)

        val repeated = fileLine(FILE, BYTES, named) ?: throw AssertionError("строки файла нет вовсе")
        assertFalse("имя файла сказано дважды: $repeated", FILE in repeated)
        assertTrue("вес пропал: $repeated", weight in repeated)

        val own = fileLine(FILE, BYTES, ObjectVerdict(HEADLINE, SUBLINE)) ?: throw AssertionError("строки файла нет вовсе")
        assertTrue("имя файла пропало, хотя его никто не сказал: $own", FILE in own && weight in own)
        assertNull(fileLine(null, null, named))
    }

    @Test
    fun `вес, ставший мерой записи, второй раз не пишется`() {
        val weight = com.point.core.flow.humanWeight(BYTES)!!

        val line = fileLine(FILE, BYTES, ObjectVerdict(HEADLINE, null, measure = weight)) ?: ""
        assertFalse("вес сказан и мерой, и строкой файла: $line", weight in line)
    }

    @Test
    fun `путь называет откуда, саму вещь и последние шаги, а сорвавшийся — словом`() {
        val entry = entry(
            step("Извлечь текст", at(17, 27), StepOutcome.DONE),
            step("Перевести", at(17, 30), StepOutcome.FAILED),
        )

        val segments = historySegments(entry, HEADLINE, zone)

        assertEquals("откуда пришла вещь — не тем словом, что в списке", com.point.desktop.sourceShort(ObjectSource.PHONE_RELAY), segments[0].text)
        assertTrue("вещь не стоит в пути сама и не отмечена текущей", segments[1].current && segments[1].text == HEADLINE)
        assertTrue("сделанный шаг не назван со своим временем: ${segments[2].text}", "Извлечь" in segments[2].text && "17:27" in segments[2].text)

        val failed = segments.last()
        assertTrue("сорвавшийся шаг не притушен", failed.faded)
        assertTrue("сорвавшийся шаг выдан за сделанный — со временем: ${failed.text}", "Перевести" in failed.text && "17:30" !in failed.text)
    }

    @Test
    fun `длинный путь показывает последние шаги и счёт скрытых`() {
        val entry = entry(*(1..5).map { step("Шаг $it", at(10, it), StepOutcome.DONE) }.toTypedArray())

        val segments = historySegments(entry, HEADLINE, zone)

        assertTrue("скрытые шаги не сосчитаны: ${segments[2].text}", "2" in segments[2].text && segments[2].faded)
        assertTrue("в полосе не последние шаги: ${segments.drop(3).map { it.text }}", segments.drop(3).map { it.text.substringBefore(" ·") } == listOf("Шаг 3", "Шаг 4", "Шаг 5"))
    }

    @Test
    fun `узкая полоса истории стягивает шаги в счёт, а не обрезает их за краем`() {
        val entry = entry(*(1..5).map { step("Шаг $it", at(10, it), StepOutcome.DONE) }.toTypedArray())

        assertEquals("широкой полосе не хватило шагов", HISTORY_STEPS, historyStepsFit(900.dp))
        assertEquals("узкая полоса держит шаги, которые в неё не влезут", 0, historyStepsFit(320.dp))

        val narrow = historySegments(entry, HEADLINE, zone, historyStepsFit(320.dp))
        assertEquals("в узкой полосе не откуда, вещь и счёт: ${narrow.map { it.text }}", 3, narrow.size)
        assertTrue("скрытые шаги не сосчитаны все: ${narrow.last().text}", "5" in narrow.last().text && narrow.last().faded)
    }

    @Test
    fun `меню нового объекта не повторяет то, что делает сама кнопка`() {
        val ways = NewObjectWays(pickFile = {}, takeClipboard = {}, grabScreen = {}, receiveFile = {})

        val menu = newObjectMenu(ways, clip = null)

        assertTrue("выбор файла и в кнопке, и в меню: ${menu.map { it.title }}", menu.none { it.onClick === ways.pickFile })
        assertFalse("в меню снова «Выбрать файл»", menu.any { "файл" in it.title.lowercase() && "Выбрать" in it.title })
    }

    @Test
    fun `ждущий шаг говорит, чего ждёт, а не время`() {
        val waiting = step("Печать", at(9, 0), StepOutcome.AWAITING, note = com.point.desktop.WAITS_FOR_PHONE)

        val label = stepLabel(waiting, zone)
        assertTrue("ждущий шаг не говорит, чего ждёт: $label", "Печать" in label && com.point.desktop.WAITS_FOR_PHONE in label)
        assertFalse("ждущий шаг выдан за сделанный — со временем: $label", "09:00" in label)
    }

    @Test
    fun `буфер назван словами — что в нём и сколько`() {
        val two = clipboardNote(ClipboardPayload.ofText("первая\nвторая"))
        val five = clipboardNote(ClipboardPayload.ofText("1\n2\n3\n4\n5"))
        val picture = clipboardNote(ClipboardPayload("image/png", "снимок.png", ByteArray(8) { 1 }))
        val nothing = clipboardNote(null)

        assertTrue("строки текста не сосчитаны: $two / $five", "2" in two && "5" in five)
        assertTrue("картинка и текст в буфере названы одинаково", picture != two && picture != nothing)
        assertEquals("пробелы в буфере названы не так, как пустой буфер", nothing, clipboardNote(ClipboardPayload.ofText("   ")))
    }

    @Test
    fun `поиск окна находит по имени и по второй строке, все слова сразу`() {
        assertTrue(matchesQuery("Голосовое из WhatsApp", "голосовое whatsapp"))
        assertFalse(matchesQuery("Голосовое из WhatsApp", "голосовое telegram"))
        assertTrue("пустой поиск обязан показывать всё", matchesQuery("что угодно", "  "))
    }

    @Test
    fun `окно встаёт посередине и не вылезает за маленький экран`() {
        val big = windowBounds(ScreenArea(0, 0, 1920, 1040))
        assertEquals(WINDOW_WIDTH, big.width)
        assertEquals(WINDOW_HEIGHT, big.height)
        assertEquals((1920 - WINDOW_WIDTH) / 2, big.x)

        val small = windowBounds(ScreenArea(0, 0, 1024, 700))
        assertTrue("окно шире экрана", small.x >= 0 && small.x + small.width <= 1024)
        assertTrue("окно выше экрана — шапку не достать", small.y >= 0 && small.y + small.height <= 700)
    }

    private fun entry(vararg steps: JournalStep) = JournalEntry(
        path = "/tmp/запись.m4a",
        name = "запись.m4a",
        kind = "AUDIO",
        mime = "audio/mp4",
        source = ObjectSource.PHONE_RELAY,
        at = at(17, 20),
        steps = steps.toList(),
    )

    private fun step(title: String, at: Long, outcome: StepOutcome, note: String = "") =
        JournalStep(capabilityId = "c", title = title, at = at, outcome = outcome, note = note)

    private companion object {
        const val HEADLINE = "Запись"
        const val SUBLINE = "голосовое из WhatsApp"
        const val MEASURE = "1 мин"
        const val ARRIVAL = "с телефона · сегодня 17:27"
        const val FILE = "AUD-0001.m4a"
        const val BYTES = 480 * 1024L
    }
}
