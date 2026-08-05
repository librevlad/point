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

    /**
     * Дверь «Настройки» на «Недавнем» — тот самый основной путь за ключом (#544).
     *
     * Ждём не заголовка экрана: он теперь слово в слово совпадает с подписью самой двери, и
     * ожидание было бы удовлетворено дверью, по которой только что нажали, — то есть не ждало бы
     * ничего. Ждём Шага 1: его нет нигде, кроме экрана настроек.
     */
    private fun openKeySettings() {
        compose.onNodeWithText(SETTINGS_TITLE).performClick()
        compose.waitUntilAtLeastOneExists(hasText("ШАГ 1 · ОТКУДА ВЗЯТЬ КЛЮЧ"), TIMEOUT_MS)
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

    @Test fun `ссылка на страницу сервиса с домашнего экрана открывает браузер`() {
        openKeySettings()
        // Строка называлась «Взять ключ» и стояла внутри строки выбора сервиса — владелец прочитал
        // её как «взять этот ключ» (#447). Теперь она называет, что произойдёт.
        compose.onAllNodesWithText("Открыть сайт", substring = true).onFirst()
            .performScrollTo().performClick()
        compose.waitForIdle()

        val opened = shadowOf(compose.activity).nextStartedActivity
            ?: shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull("тап по ссылке на сайт не открыл ничего — колбэк потерян на этой двери", opened)
        assertEquals(Intent.ACTION_VIEW, opened!!.action)
    }

    @Test fun `за единственной дверью «Недавнего» лежит и аккаунт с устройствами`() {
        // #544 через настоящую Activity, а не через один composable: колбэк `onOpenDevices` едет
        // сюда через [PointFlow], и потеряться по дороге он может ровно так же, как когда-то
        // потерялся `onOpenUrl` (см. KDoc [PointFlow]).
        openKeySettings()

        compose.onNodeWithText("АККАУНТ И УСТРОЙСТВА").performScrollTo().assertExists()
        compose.onNodeWithText("Мои устройства").performScrollTo().assertExists()
    }

    @Test fun `«назад» после сохранения ключа возвращает на «Недавнее», а не закрывает Point`() {
        openKeySettings()
        saveKey("ключ-из-буфера")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertFalse("Point закрылся вместо возврата на «Недавнее»", compose.activity.isFinishing)
        // Дверь «Новый объект» есть только на «Недавнем» — значит вернулись именно туда. Слово
        // «Настройки» для этого больше не годится: им названы и дверь, и экран за ней (#544).
        compose.onNodeWithText("Новый объект").assertExists()
        compose.onNodeWithText("Ключ AI сохранён").assertDoesNotExist()
    }

    @Test fun `с экрана-сообщения есть видимый выход, а не одна карточка`() {
        openKeySettings()
        saveKey("ключ")

        compose.onNodeWithText("Готово").performClick()
        compose.waitForIdle()

        assertFalse(compose.activity.isFinishing)
        compose.onNodeWithText("Ключ AI сохранён").assertDoesNotExist()
        compose.onNodeWithText("Новый объект").assertExists()
    }
}

/** Экран рисуется в фоне и по-настоящему, поэтому ждём словами, а не «должно было успеть». */
private const val TIMEOUT_MS = 10_000L
