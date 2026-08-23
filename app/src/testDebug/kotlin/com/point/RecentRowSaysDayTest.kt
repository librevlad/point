package com.point

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import com.point.core.flow.rowTimeLabel
import com.point.core.model.HistoryEntry
import com.point.core.model.ObjectKind
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * День у старой записи доходит до экрана телефона (#1056).
 *
 * Общий шов в `:core:flow` умеет называть день, но человек видит не шов, а строку списка:
 * пока «Недавнее» само печатало час, обещание карточки жило только в чистой функции рядом.
 * Здесь экран собирается целиком и читается так же, как глазами — по тому, что на нём
 * написано.
 */
@RunWith(RobolectricTestRunner::class)
class RecentRowSaysDayTest {

    @get:Rule val compose = createComposeRule()

    private val zone: ZoneId = ZoneId.systemDefault()

    private val now: Long = System.currentTimeMillis()

    /** Момент того же часа, но разной давности: подпись обязана их различить. */
    private fun daysBack(days: Long, hour: Int, minute: Int): Long =
        LocalDate.now(zone).minusDays(days).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun entry(name: String, at: Long) = HistoryEntry(
        id = name,
        mime = "application/pdf",
        kind = ObjectKind.PDF,
        name = name,
        epochMillis = at,
        ref = ScratchRef("/scratch/$name"),
    )

    private fun home(vararg entries: HistoryEntry) {
        compose.setContent {
            PointTheme {
                HomeScreen(recent = entries.toList(), onOpen = {}, onSettings = {})
            }
        }
    }

    /** Что написано на экране прямо сейчас — все надписи, как их видит человек. */
    private fun onScreen(): List<String> = compose
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    /** До нужной записи человек доезжает — список длиннее экрана. */
    private fun scrollTo(name: String): List<String> {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(name))
        compose.waitForIdle()
        return onScreen()
    }

    private fun namesHour(text: String) = Regex("""\d{1,2}:\d{2}""").containsMatchIn(text)

    @Test
    fun `запись под «Раньше» показывает день, а не час`() {
        val at = daysBack(3, 14, 5)
        home(entry("счёт-март.pdf", at))

        val seen = scrollTo("счёт-март.pdf")

        assertTrue(
            "день не дошёл до экрана — видно $seen",
            rowTimeLabel(at, now, zone) in seen,
        )
        assertTrue(
            "под «Раньше» на экране остался час — видно $seen",
            seen.none(::namesHour),
        )
    }

    @Test
    fun `трёхдневная и трёхмесячная записи на экране различимы`() {
        val recent = daysBack(3, 14, 5)
        val old = daysBack(90, 14, 5)
        home(entry("счёт-март.pdf", recent), entry("договор-аренды.pdf", old))

        val seen = scrollTo("счёт-март.pdf") + scrollTo("договор-аренды.pdf")

        val younger = rowTimeLabel(recent, now, zone)
        val older = rowTimeLabel(old, now, zone)
        assertNotEquals("две старые записи подписаны одинаково", younger, older)
        assertTrue("подписи дней не дошли до экрана — видно $seen", seen.containsAll(listOf(younger, older)))
    }

    /** Свежей записи день говорит секция — строке остаётся час (#880), и он на месте. */
    @Test
    fun `у сегодняшней записи на экране остаётся час`() {
        val at = now - 2 * 60 * 60 * 1000L
        home(entry("фото с парковки.jpg", at))

        val seen = scrollTo("фото с парковки.jpg")

        assertTrue("час пропал у свежей записи — видно $seen", rowTimeLabel(at, now, zone) in seen)
        assertTrue("на экране нет ни одного часа — видно $seen", seen.any(::namesHour))
    }
}
