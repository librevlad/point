package com.point

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.point.core.model.HistoryEntry
import com.point.core.model.ObjectKind
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * «Недавнее» отдаёт запись обратно человеку (#543): одна убирается свайпом влево, очистка всего
 * сначала спрашивает. Экран проверяется тем же, чем пользуется человек — жестом и тапом.
 */
@RunWith(RobolectricTestRunner::class)
class HomeRecentRemoveTest {

    @get:Rule val compose = createComposeRule()

    private val entries = listOf(
        entry("a", "Счёт за свет.pdf", ObjectKind.PDF),
        entry("b", "Расписка от соседа", ObjectKind.TEXT),
    )

    private val removed = mutableListOf<String>()
    private var cleared = 0

    private fun entry(id: String, name: String, kind: ObjectKind) = HistoryEntry(
        id = id,
        mime = "application/pdf",
        kind = kind,
        name = name,
        epochMillis = System.currentTimeMillis(),
        ref = ScratchRef("/scratch/$id"),
    )

    private fun home() {
        compose.setContent {
            PointTheme {
                HomeScreen(
                    recent = entries,
                    onOpen = {},
                    onSettings = {},
                    onRemove = { removed += it.id },
                    onClear = { cleared++ },
                )
            }
        }
    }

    @Test fun `до свайпа «Убрать» не показано — список остаётся списком`() {
        home()

        compose.onNodeWithText(REMOVE_ENTRY).assertDoesNotExist()
    }

    @Test fun `свайп влево по записи открывает «Убрать»`() {
        home()

        compose.onNodeWithText("Счёт за свет.pdf").performTouchInput { swipeLeft() }
        compose.waitForIdle()

        compose.onNodeWithText(REMOVE_ENTRY).assertExists()
    }

    @Test fun `сам свайп ничего не уносит — запись убирает тап по «Убрать»`() {
        home()

        compose.onNodeWithText("Счёт за свет.pdf").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertTrue("свайп унёс запись, ничего не спросив — $removed", removed.isEmpty())

        compose.onNodeWithText(REMOVE_ENTRY).performClick()
        compose.waitForIdle()

        assertEquals(listOf("a"), removed)
    }

    @Test fun `свайп открывает «Убрать» именно у той записи, по которой провели`() {
        home()

        compose.onNodeWithText("Расписка от соседа").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText(REMOVE_ENTRY).performClick()
        compose.waitForIdle()

        assertEquals(listOf("b"), removed)
    }

    /** «Очистить недавнее» живёт под списком — до него сначала доезжают, как и человек. */
    private fun tapUnderList(label: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    @Test fun `«Очистить недавнее» сначала спрашивает — без ответа ничего не стирается`() {
        home()

        tapUnderList(CLEAR_RECENT)

        assertEquals("вопрос не задан, история уже стёрта", 0, cleared)
        compose.onNodeWithText(CLEAR_RECENT_ASK).assertExists()
    }

    @Test fun `подтверждённая очистка стирает недавнее`() {
        home()

        tapUnderList(CLEAR_RECENT)
        tapUnderList(CLEAR_RECENT_CONFIRM)

        assertEquals(1, cleared)
    }

    @Test fun `«Отмена» закрывает вопрос и оставляет недавнее на месте`() {
        home()

        tapUnderList(CLEAR_RECENT)
        tapUnderList(CANCEL)

        assertEquals(0, cleared)
        compose.onNodeWithText(CLEAR_RECENT_ASK).assertDoesNotExist()
        compose.onNodeWithText(CLEAR_RECENT).assertExists()
    }
}
