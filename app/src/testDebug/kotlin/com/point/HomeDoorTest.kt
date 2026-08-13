package com.point

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.SETTINGS_TITLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeDoorTest {

    @get:Rule val compose = createAndroidComposeRule<HomeActivity>()

    private fun openSettings() {
        compose.onNodeWithText(SETTINGS_TITLE).performClick()
        compose.waitUntilAtLeastOneExists(hasText("Ключи AI"), TIMEOUT_MS)
    }

    private fun openKeySettings() {
        openSettings()
        compose.onNodeWithText("Ключи AI").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Проверить все"), TIMEOUT_MS)
    }

    private fun openFirstService() {
        openKeySettings()
        compose.onNodeWithText(AI_PROVIDERS.first().name, substring = true).performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Вставить из буфера"), TIMEOUT_MS)
    }

    private fun saveKey(key: String) {

        compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().performTextInput(key)

        compose.onNodeWithText("Сохранить без проверки").performScrollTo().performClick()

        compose.waitUntilAtLeastOneExists(hasText("ваш ключ", substring = true), TIMEOUT_MS)
    }

    @Test fun `ссылка на страницу сервиса с домашнего экрана открывает браузер`() {
        openFirstService()

        compose.onAllNodesWithText("Открыть сайт", substring = true).onFirst()
            .performScrollTo().performClick()
        compose.waitForIdle()

        val opened = shadowOf(compose.activity).nextStartedActivity
            ?: shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull("тап по ссылке на сайт не открыл ничего — колбэк потерян на этой двери", opened)
        assertEquals(Intent.ACTION_VIEW, opened!!.action)
    }

    @Test fun `за единственной дверью «Недавнего» лежит и аккаунт с устройствами`() {

        openSettings()

        compose.onNodeWithText("Мои устройства").assertExists()
        compose.onNodeWithText("Вход, круг устройств и выход.").assertExists()
    }

    @Test fun `«назад» после сохранения ключа возвращает на «Недавнее», а не закрывает Point`() {
        openFirstService()
        saveKey("ключ-из-буфера")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertFalse("Point закрылся вместо возврата на «Недавнее»", compose.activity.isFinishing)

        compose.onNodeWithText("Новый объект").assertExists()
    }

    @Test fun `сохранённый ключ виден строкой своего сервиса, а не карточкой поверх экрана`() {
        openFirstService()
        saveKey("ключ")

        compose.onAllNodesWithText("ваш ключ", substring = true).onFirst().assertExists()

        compose.onNodeWithText("Отмена").performScrollTo().performClick()
        compose.waitForIdle()

        assertFalse(compose.activity.isFinishing)
        compose.onNodeWithText("Новый объект").assertExists()
    }
}

private const val TIMEOUT_MS = 10_000L
