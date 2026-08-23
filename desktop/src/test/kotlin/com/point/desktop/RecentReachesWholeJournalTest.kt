package com.point.desktop

import com.point.core.model.ObjectKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Недавнее» на компьютере доводит до любого объекта, который Point ещё помнит (#1098).
 *
 * Живой прогон: журнал держал сорок записей со знанием, а в окне их было восемь — всё
 * остальное было недостижимо ни прокруткой, ни поиском, и выглядело так, будто Point это
 * забыл. Предела в списке больше нет: есть страница и «показать ещё».
 */
class RecentReachesWholeJournalTest {

    private val pane = File("src/main/kotlin/com/point/desktop/ui/RecentPane.kt").readText()

    private fun entry(path: String) = JournalEntry(
        path = path,
        name = File(path).name,
        kind = ObjectKind.TEXT.name,
        mime = "text/plain",
        source = ObjectSource.DROPPED,
        at = 1_000L,
    )

    @Test
    fun `список помнит столько же, сколько журнал`() {
        val journal = (1..JOURNAL_LIMIT).map { entry("/объекты/$it.txt") }

        val recent = recentBesides(journal, livePaths = emptySet())

        assertEquals(journal.size, recent.size)
    }

    @Test
    fun `открытый сейчас объект в прошлом не повторяется`() {
        val journal = (1..20).map { entry("/объекты/$it.txt") }

        val recent = recentBesides(journal, livePaths = setOf("/объекты/3.txt"))

        assertEquals(journal.size - 1, recent.size)
        assertTrue("открытый объект остался и в прошлом", recent.none { it.path == "/объекты/3.txt" })
    }

    @Test
    fun `сразу видна страница, а дальше — по просьбе`() {
        val page = Regex("""RECENT_PAGE = (\d+)""").find(pane)?.groupValues?.get(1)?.toInt() ?: 0

        assertTrue("страница списка исчезла или стала бесполезной: $page", page in 1..40)
        assertTrue("список снова обрывается молча", pane.contains("showMoreLabel"))
        assertTrue("страница не растёт по просьбе", pane.contains("shown += RECENT_PAGE"))
    }
}
