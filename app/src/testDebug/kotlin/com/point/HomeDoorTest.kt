package com.point

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Домашняя дверь целиком (#114): настоящая `HomeActivity`, настоящие нажатия, настоящий «назад».
 *
 * Обе находки ревью жили ровно здесь и были невидимы для CI по построению: ни один тест проекта не
 * создавал экрана, поэтому потерянный колбэк и «назад», закрывающий Point, не мог поймать никто.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeDoorTest {

    @get:Rule val compose = createAndroidComposeRule<HomeActivity>()

    /** Шестерёнка на «Недавнем» — тот самый основной путь за ключом. */
    private fun openKeySettings() {
        compose.onNodeWithContentDescription("Ваш AI-ключ").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Ваш AI-ключ"), TIMEOUT_MS)
    }

    /** Сохранить ключ так, как это делает человек: доехать, набрать, нажать. */
    private fun saveKey(key: String) {
        // Первое поле экрана — сам ключ; кнопки до него погашены. Экран длиннее окна, поэтому
        // до узлов доезжаем прокруткой — как пальцем.
        compose.onAllNodes(hasSetTextAction()).onFirst().performScrollTo().performTextInput(key)
        // Тихая дорога в обход живой проверки (#465) — она и есть прежнее «Сохранить». Тест про
        // дверь, а не про сеть, поэтому идёт именно ею.
        compose.onNodeWithText("Сохранить без проверки").performScrollTo().performClick()
        // Ключ уходит на диск в фоновом потоке — ждём словами экрана, а не «должно было успеть».
        compose.waitUntilAtLeastOneExists(hasText("Ключ AI сохранён"), TIMEOUT_MS)
    }

    @Test fun `«Взять ключ» с домашнего экрана открывает страницу провайдера`() {
        openKeySettings()
        compose.onAllNodesWithText("Взять ключ").onFirst().performClick()
        compose.waitForIdle()

        val opened = shadowOf(compose.activity).nextStartedActivity
            ?: shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull("тап по «Взять ключ» не открыл ничего — колбэк потерян на этой двери", opened)
        assertEquals(Intent.ACTION_VIEW, opened!!.action)
    }

    @Test fun `«назад» после сохранения ключа возвращает на «Недавнее», а не закрывает Point`() {
        openKeySettings()
        saveKey("ключ-из-буфера")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertFalse("Point закрылся вместо возврата на «Недавнее»", compose.activity.isFinishing)
        // Шестерёнка есть только на «Недавнем» — значит вернулись именно туда.
        compose.onNodeWithContentDescription("Ваш AI-ключ").assertExists()
        compose.onNodeWithText("Ключ AI сохранён").assertDoesNotExist()
    }

    @Test fun `с экрана-сообщения есть видимый выход, а не одна карточка`() {
        openKeySettings()
        saveKey("ключ")

        compose.onNodeWithText("Готово").performClick()
        compose.waitForIdle()

        assertFalse(compose.activity.isFinishing)
        compose.onNodeWithText("Ключ AI сохранён").assertDoesNotExist()
        compose.onNodeWithContentDescription("Ваш AI-ключ").assertExists()
    }
}

/** Экран рисуется в фоне и по-настоящему, поэтому ждём словами, а не «должно было успеть». */
private const val TIMEOUT_MS = 10_000L
