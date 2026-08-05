package com.point

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.point.core.flow.MY_DEVICES_TITLE
import com.point.core.flow.SETTINGS_TITLE
import com.point.core.flow.UserAiConfig
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

/**
 * Одна дверь вместо двух (#544) — на «Недавнем» и за ним.
 *
 * Дверей было две, и обе врали в меньшую сторону: «AI-ключ» называл одну настройку из пяти, а
 * «Устройства» держали аккаунт со входом отдельным входом. Цена этого — человек, которому надо
 * выключить звук или запретить облако, не идёт ни в ту, ни в другую: за ними, по подписям, лежит
 * не то. Ловится это только тестом на СОСТАВ экрана: обе двери работали, просто вели не туда, где
 * человек стал бы искать.
 *
 * Размер окна назван вслух ([Config]) по той же причине, что и в [SettingsCompositionTest]: без
 * него «видно» означало бы «видно на том, что подсунул Robolectric».
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class OneSettingsDoorTest {

    @get:Rule val compose = createComposeRule()

    /** Один объект в «Недавнем»: пустой дом рисует бренд-портал с бесконечной анимацией, и покоя
     *  тест на нём не дождался бы никогда. */
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
                config = UserAiConfig.DEFAULT,
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
        // Прежние две подписи называли часть того, что за ними лежало, — и потому не звали тех,
        // кому нужно было остальное.
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

        // Ключ AI — то, ради чего дверь звалась «AI-ключ».
        compose.onNodeWithText("ШАГ 1 · ОТКУДА ВЗЯТЬ КЛЮЧ").performScrollTo().assertIsDisplayed()
        // Отправка в облако, приватность, звук и статистика — те четыре, о которых подпись молчала.
        compose.onNodeWithText("ОТПРАВКА И ПРИВАТНОСТЬ").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Звук действий").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Приватная статистика").performScrollTo().assertIsDisplayed()
        // И аккаунт с кругом устройств — то, ради чего была вторая дверь.
        compose.onNodeWithText("АККАУНТ И УСТРОЙСТВА").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(MY_DEVICES_TITLE).performScrollTo().assertIsDisplayed()
    }

    @Test fun `раздел устройств открывает тот же круг, что открывала вторая дверь`() {
        var opened = false
        settings(onOpenDevices = { opened = true })

        compose.onNodeWithText(MY_DEVICES_TITLE).performScrollTo().performClick()

        assertTrue("строка раздела не открыла круг устройств — путь к аккаунту потерян", opened)
    }

    @Test fun `экран не обещает больше того, что за ним лежит`() {
        settings()

        // Обещание «больше ни о чём не спрашивает» было неправдой ровно на одну дверь: аккаунт жил
        // соседним входом. Теперь подпись называет и его.
        compose.onNodeWithText("аккаунт и устройства", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Больше Point ни о чём не спрашивает", substring = true).assertIsDisplayed()
    }
}
