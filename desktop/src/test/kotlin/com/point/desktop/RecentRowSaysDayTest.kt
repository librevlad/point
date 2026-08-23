package com.point.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.runComposeUiTest
import com.point.core.flow.rowTimeLabel
import com.point.core.model.ObjectKind
import com.point.desktop.ui.CompactList
import com.point.desktop.ui.PointColors
import com.point.desktop.ui.PointDesktopTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * День у старой записи доходит до экрана компьютера (#1056).
 *
 * Общий шов в `:core:flow` умеет называть день, но человек видит не шов, а строку списка.
 * Пока проверялась только чистая функция, вторая поверхность жила на слово: подпись могла
 * не дойти до окна — и это ровно тот случай, из-за которого карточка появилась на телефоне.
 * Здесь список окна собирается целиком и читается так же, как глазами.
 */
@OptIn(ExperimentalTestApi::class)
class RecentRowSaysDayTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val now: Long = System.currentTimeMillis()

    /** Момент того же часа, но разной давности: подпись обязана их различить. */
    private fun daysBack(days: Long, hour: Int, minute: Int): Long =
        LocalDate.now(zone).minusDays(days).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun kept(name: String, at: Long) = JournalEntry(
        path = "/tmp/$name",
        name = name,
        kind = ObjectKind.PDF.name,
        mime = "application/pdf",
        source = ObjectSource.PHONE_RELAY,
        at = at,
    )

    private fun state(journal: List<JournalEntry>) = DesktopState(
        registry = DesktopRegistry(emptySet()),
        resolver = DesktopResolver(emptySet()),
        clipboard = { },
        journalStore = object : JournalStore {
            override fun load() = journal
            override fun save(entries: List<JournalEntry>) = Unit
        },
    )

    private fun namesHour(text: String) = Regex("""\d{1,2}:\d{2}""").containsMatchIn(text)

    /** Что написано в окне «Недавнего» — все надписи списка, как их видит человек. */
    private fun shownIn(journal: List<JournalEntry>): List<String> {
        val texts = mutableListOf<String>()
        runComposeUiTest {

            // Часы окна идут вперёд сами: список каждую минуту заново спрашивает «сколько
            // времени» и живёт вечным ожиданием. Автоматический ход времени в тесте такого
            // экрана не дожидается никогда, поэтому кадр здесь выдаётся вручную — один,
            // ровно как человек видит окно в момент открытия.
            mainClock.autoAdvance = false
            val st = state(journal)
            setContent {
                PointDesktopTheme {
                    Box(Modifier.fillMaxSize().background(PointColors.window)) {
                        CompactList(
                            state = st,
                            items = emptyList(),
                            onOpen = {},
                            onTakeClipboard = {},
                            onGrabScreen = {},
                            onSettings = {},
                            onHide = {},
                        )
                    }
                }
            }
            mainClock.advanceTimeByFrame()
            texts += onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        }
        return texts
    }

    @Test
    fun `запись под «Раньше» показывает день, а не час`() {
        val at = daysBack(3, 14, 5)

        val seen = shownIn(listOf(kept("счёт-март.pdf", at)))

        assertTrue("день не дошёл до окна — видно $seen", rowTimeLabel(at, now, zone) in seen)
        assertTrue("под «Раньше» в окне остался час — видно $seen", seen.none(::namesHour))
    }

    @Test
    fun `трёхдневная и трёхмесячная записи в окне различимы`() {
        val recent = daysBack(3, 14, 5)
        val old = daysBack(90, 14, 5)

        val seen = shownIn(listOf(kept("счёт-март.pdf", recent), kept("договор-аренды.pdf", old)))

        val younger = rowTimeLabel(recent, now, zone)
        val older = rowTimeLabel(old, now, zone)
        assertNotEquals("две старые записи подписаны одинаково", younger, older)
        assertTrue("подписи дней не дошли до окна — видно $seen", seen.containsAll(listOf(younger, older)))
    }

    /** Свежей записи день говорит секция — строке остаётся час (#880), и он на месте. */
    @Test
    fun `у сегодняшней записи в окне остаётся час`() {
        val at = now - 2 * 60 * 60 * 1000L

        val seen = shownIn(listOf(kept("фото с парковки.jpg", at)))

        assertTrue("час пропал у свежей записи — видно $seen", rowTimeLabel(at, now, zone) in seen)
        assertTrue("в окне нет ни одного часа — видно $seen", seen.any(::namesHour))
    }
}
