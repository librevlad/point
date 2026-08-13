package com.point.desktop

import com.point.core.flow.TimeSection
import com.point.core.flow.byTimeSection
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.desktop.ui.RecentLine
import com.point.desktop.ui.recentLines
import com.point.desktop.ui.recentNote
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * «Недавнее» — один список с одной лестницей времени (#884).
 *
 * Компьютер держит объект двумя способами: открытый прямо сейчас лежит во входящих, а тот,
 * что уже убрали, остаётся записью журнала. Для человека это один список, и «Сегодня» в нём
 * стоит один раз. Пока каждый источник резался на секции сам по себе, «СЕГОДНЯ» печаталось
 * дважды — сначала над живыми объектами, потом над журналом.
 */
class OneTimeLadderTest {

    /**
     * Полдень, а не произвольная метка: секции режутся по календарю (#931), и «два часа
     * назад» от полуночи — это уже вчера. Полдень делает намерение теста однозначным.
     */
    private val now = java.time.LocalDate.of(2026, 8, 13)
        .atTime(12, 0)
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    private fun live(minutesAgo: Long) = InboxItem(
        obj = PointObject(
            id = "live-$minutesAgo",
            mime = "text/plain",
            uri = ScratchRef("/scratch/live-$minutesAgo"),
            state = ObjectState(kind = ObjectKind.TEXT),
            metadata = mapOf("name" to "Открытый объект"),
        ),
        receivedAt = now - minutesAgo * 60_000L,
    )

    private fun kept(minutesAgo: Long) = JournalEntry(
        path = "/scratch/kept-$minutesAgo",
        name = "Убранный объект",
        kind = ObjectKind.TEXT.name,
        mime = "text/plain",
        source = ObjectSource.DROPPED,
        at = now - minutesAgo * 60_000L,
    )

    @Test
    fun `у одной секции один заголовок, из скольких бы источников она ни собралась`() {
        val lines = recentLines(listOf(live(120)), listOf(kept(200)))

        val sections = byTimeSection(lines, now) { it.at }.map { it.first }

        assertEquals(listOf(TimeSection.TODAY), sections)
    }

    @Test
    fun `вторая строка означает одно и то же у открытого объекта и у убранного`() {
        val entry = kept(5)
        val alive = live(3)
        val known = entry.copy(path = alive.obj.uri.value, at = alive.receivedAt)

        val lines = recentLines(listOf(alive), listOf(entry), journal = listOf(known, entry))

        val notes = lines.map {
            when (it) {
                is RecentLine.Live -> recentNote(it.item.obj.state.kind, it.source)
                is RecentLine.Kept -> recentNote(ObjectKind.TEXT, it.entry.source)
            }
        }
        assertEquals(notes[0], notes[1])
    }

    @Test
    fun `свежий журнал стоит выше старого объекта, а не отдельным списком`() {
        val lines = recentLines(listOf(live(300)), listOf(kept(5)))

        val order = lines.map { it::class.simpleName }

        assertEquals(listOf("Kept", "Live"), order)
    }

    @Test
    fun `лестница остаётся лестницей, когда источники перемешаны по времени`() {
        val lines = recentLines(
            listOf(live(10), live(60 * 30)),
            listOf(kept(120), kept(60 * 50)),
        )

        val sections = byTimeSection(lines, now) { it.at }.map { it.first }

        assertEquals(
            listOf(TimeSection.NOW, TimeSection.TODAY, TimeSection.YESTERDAY, TimeSection.EARLIER),
            sections,
        )
    }
}
