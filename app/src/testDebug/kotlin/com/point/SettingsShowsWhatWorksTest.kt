package com.point

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.point.core.flow.HistoryFootprint
import com.point.core.model.ObjectKind
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Настройки показывают то, что уже работает и негде было увидеть (#821, пункт 4 карточки
 * #610; решение владельца 12.08.2026 «Всё четыре сейчас»).
 *
 * Жест закрепления есть с самого начала, а списка закреплённого не было — открепить, не
 * вспомнив жеста, человек не мог. Плитка бывает, но знать о ней неоткуда. Копии объектов
 * лежат на телефоне молча. Версию спрашивает первый же тестер.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SettingsShowsWhatWorksTest {

    @get:Rule val compose = createComposeRule()


    private fun settings(
        tileAdded: Boolean = false,
        memory: HistoryFootprint? = null,
        version: String = "0.3.0",
        onForgetAll: () -> Unit = {},
    ) = compose.setContent {
        PointTheme(darkTheme = true) {
            KeyScreen(
                screen = aiKeysScreenOf(),
                onSave = {},
                onCancel = {},
                tileAdded = tileAdded,
                memory = memory,
                onForgetAll = onForgetAll,
                version = version,
            )
        }
    }

    @Test fun `обзоры того, что уже работает, видны на общем экране`() {
        settings(memory = HistoryFootprint(count = 7, bytes = 3 * 1024 * 1024))

        compose.onNodeWithText("Точки входа").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Что Point помнит").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0.3.0", substring = true).performScrollTo().assertIsDisplayed()
    }



    @Test fun `про плитку сказано по-разному — есть она или нет`() {
        settings(tileAdded = true)

        compose.onNodeWithText("Точки входа").performScrollTo().performClick()
        compose.onNodeWithText("в шторке", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Системное", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `память названа числом и местом, а забыть можно с подтверждением`() {
        var forgotten = false
        settings(memory = HistoryFootprint(count = 7, bytes = 3 * 1024 * 1024), onForgetAll = { forgotten = true })

        compose.onNodeWithText("Что Point помнит").performScrollTo().performClick()
        compose.onNodeWithText("Объектов: 7", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Забыть всё").performScrollTo().performClick()

        assertEquals("подтверждение не спросили", false, forgotten)
        compose.onNodeWithText(CLEAR_RECENT_CONFIRM).performScrollTo().performClick()
        assertEquals(true, forgotten)
    }

    /** Размер человеку считает общее правило ядра (#840): своей копии у настроек нет. */
    @Test fun `размер показан человеку, а не числом байт`() {
        settings(memory = HistoryFootprint(count = 2, bytes = 3L * 1024 * 1024))

        compose.onNodeWithText("Что Point помнит").performScrollTo().performClick()
        compose.onNodeWithText("3", substring = true).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("3145728", substring = true).assertCountEquals(0)
    }
}
