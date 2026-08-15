package com.point.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.point.core.flow.InvestigationState
import com.point.core.flow.OpenQuestion
import com.point.core.flow.openQuestionLabel
import com.point.core.ui.theme.PointTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * «Смотрели — не нашлось» — знание, а не сбой (Конституция §13), и человек видит его на
 * телефоне так же, как на компьютере (#1016).
 *
 * Компьютер показывал «QR-код · смотрели — не нашлось» приглушённой строкой, телефон не
 * показывал ничего: один и тот же человек за компьютером знал, что QR искали и не нашли, а на
 * телефоне был уверен, что не искали вовсе.
 */
@RunWith(RobolectricTestRunner::class)
class NotFoundReachesTheScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `ненайденное видно человеку`() {
        compose.setContent {
            PointTheme {
                UnderstoodSection(
                    facts = emptyList(),
                    enriching = emptyList(),
                    questions = listOf(OpenQuestion("QR-код", InvestigationState.NOT_FOUND)),
                )
            }
        }

        compose.onNodeWithText(openQuestionLabel(InvestigationState.NOT_FOUND), substring = true)
            .assertIsDisplayed()
    }

    /** Человек сам обвёл область и ждёт ответа именно про неё (#1000). */
    @Test fun `ответ про показанную область говорит, что он про область`() {
        compose.setContent {
            PointTheme {
                UnderstoodSection(
                    facts = emptyList(),
                    enriching = emptyList(),
                    questions = listOf(
                        OpenQuestion("Значения", InvestigationState.NOT_FOUND, aboutArea = true),
                    ),
                )
            }
        }

        compose.onNodeWithText("области", substring = true).assertIsDisplayed()
    }
}
