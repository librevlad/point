package com.point

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.point.core.flow.MY_DEVICES_TITLE
import com.point.core.flow.SETTINGS_TITLE
import com.point.core.model.HistoryEntry
import com.point.core.model.ObjectKind
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class OneSettingsDoorTest {

    @get:Rule val compose = createComposeRule()

    private val oneRecent = listOf(
        HistoryEntry(
            id = "1",
            mime = "text/plain",
            kind = ObjectKind.TEXT,
            name = "заметка.txt",
            epochMillis = 1_700_000_000_000,
            ref = ScratchRef("/tmp/заметка.txt"),
        ),
    )

    private fun home(onSettings: () -> Unit = {}) = compose.setContent {
        PointTheme(darkTheme = true) {
            HomeScreen(recent = oneRecent, onOpen = {}, onSettings = onSettings)
        }
    }

    private fun settings(onOpenDevices: () -> Unit = {}) = compose.setContent {
        PointTheme(darkTheme = true) {
            KeyScreen(
                screen = aiKeysScreenOf(),
                onSave = {},
                onCancel = {},
                usageEnabled = false,
                usageSummary = null,
                onToggleUsage = {},
                onOpenDevices = onOpenDevices,
            )
        }
    }

    @Test fun `служебная дверь «Недавнего» одна, и она называется «Настройки»`() {
        home()

        compose.onNodeWithText(SETTINGS_TITLE).assertIsDisplayed()

        compose.onNodeWithText("AI-ключ").assertDoesNotExist()
        compose.onNodeWithText("Устройства").assertDoesNotExist()
    }

    @Test fun `дверь ведёт в настройки, а не остаётся украшением`() {
        var opened = false
        home(onSettings = { opened = true })

        compose.onNodeWithText(SETTINGS_TITLE).performClick()

        assertTrue("тап по единственной служебной двери не открыл ничего", opened)
    }

    @Test fun `за одной дверью лежит всё, что лежало за двумя`() {
        settings()

        compose.onNodeWithText("Ключи AI").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Отправка и приватность").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Звук действий").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Приватная статистика").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(MY_DEVICES_TITLE).performScrollTo().assertIsDisplayed()
    }

    @Test fun `раздел устройств открывает тот же круг, что открывала вторая дверь`() {
        var opened = false
        settings(onOpenDevices = { opened = true })

        compose.onNodeWithText(MY_DEVICES_TITLE).performScrollTo().performClick()

        assertTrue("строка раздела не открыла круг устройств — путь к аккаунту потерян", opened)
    }

    @Test fun `экран ничего не обещает — он просто показывает, что за ним лежит`() {
        settings()

        compose.onNodeWithText("Больше Point ни о чём не спрашивает", substring = true)
            .assertDoesNotExist()
        compose.onNodeWithText("Ключи AI, отправка в облако", substring = true).assertDoesNotExist()
    }
}
