package com.point

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.point.core.flow.AI_KEY_WHY
import com.point.core.flow.AI_PROVIDERS
import com.point.core.flow.MY_DEVICES_TITLE
import com.point.core.flow.PRIVACY_SETTING_HINT
import com.point.core.flow.PrivacyLevel
import com.point.core.flow.UserAiKey
import com.point.core.flow.UserAiKeys
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SettingsListTest {

    @get:Rule val compose = createComposeRule()

    private val openRouter = AI_PROVIDERS.first()
    private val savedKey = UserAiKeys.NONE.with(UserAiKey(openRouter.id, "sk-or-v1-abcdef123456", model = "gemma"))

    private fun settings(
        keys: UserAiKeys = UserAiKeys.NONE,
        note: String? = null,
        soundEnabled: Boolean = true,
        cloudEnabled: Boolean = false,
        privacyLevel: PrivacyLevel = PrivacyLevel.DEFAULT,
        onToggleSound: (Boolean) -> Unit = {},
        onToggleCloud: (Boolean) -> Unit = {},
        onPickPrivacyLevel: (PrivacyLevel) -> Unit = {},
        onOpenDevices: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) = compose.setContent {
        PointTheme(darkTheme = true) {
            KeyScreen(
                screen = aiKeysScreenOf(keys = keys),
                note = note,
                onSave = {},
                onCancel = onCancel,
                soundEnabled = soundEnabled,
                onToggleSound = onToggleSound,
                cloudEnabled = cloudEnabled,
                onToggleCloud = onToggleCloud,
                privacyLevel = privacyLevel,
                onPickPrivacyLevel = onPickPrivacyLevel,
                onOpenDevices = onOpenDevices,
            )
        }
    }

    @Test fun `все группы и строки достижимы, ни одна не потеряна`() {
        settings(keys = savedKey)

        compose.onNodeWithText("AI И ОБЛАКО").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("АККАУНТ").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ПРИЛОЖЕНИЕ").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Ключи AI").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Отправка и приватность").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(MY_DEVICES_TITLE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Звук действий").performScrollTo().assertIsDisplayed()
    }

    @Test fun `настройки, которой никто не мог объяснить, в списке больше нет`() {
        settings(keys = savedKey)

        compose.onNodeWithText("Приватная статистика").assertDoesNotExist()
        compose.onNodeWithText("Обезличенно", substring = true).assertDoesNotExist()

        compose.onNodeWithText("Звук действий").performClick()
        compose.onNodeWithText("Приватная статистика").assertDoesNotExist()
        compose.onNodeWithText("Объектов:", substring = true).assertDoesNotExist()
    }

    @Test fun `список начинается со строк, а не с абзацев — первая группа видна сразу`() {
        settings(keys = savedKey)

        compose.onNodeWithText("AI И ОБЛАКО").assertIsDisplayed()
        compose.onNodeWithText("Ключи AI").assertIsDisplayed()
        compose.onNodeWithText("Отправка и приватность").assertIsDisplayed()
    }

    @Test fun `каждая строка лежит в своей группе`() {
        settings(keys = savedKey)

        val order = listOf(
            "AI И ОБЛАКО", "Ключи AI", "Отправка и приватность",
            "АККАУНТ", MY_DEVICES_TITLE,
            "ПРИЛОЖЕНИЕ", "Звук действий",
        )
        order.zipWithNext { above, below ->
            assertTrue(
                "«$below» ушла из своей группы — она стоит не под «$above»",
                topOf(above) < topOf(below),
            )
        }
    }

    private fun topOf(text: String): Float =
        compose.onNodeWithText(text).getUnclippedBoundsInRoot().top.value

    @Test fun `на общем экране нет ни одного абзаца — они внутри разделов`() {
        settings(keys = savedKey)

        compose.onNodeWithText(AI_KEY_WHY, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Проверить и включить").assertDoesNotExist()
        compose.onNodeWithText("Проверить все").assertDoesNotExist()
        compose.onNodeWithText(PRIVACY_SETTING_HINT, substring = true).assertDoesNotExist()
        compose.onNodeWithText(PrivacyLevel.FREE_FIRST.what, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Вибрация управляется", substring = true).assertDoesNotExist()
    }

    @Test fun `сколько своих ключей задано — видно строкой, не открывая раздел`() {
        settings(keys = savedKey)

        compose.onNodeWithText("Свой ключ у 1 сервиса", substring = true).assertIsDisplayed()
    }

    @Test fun `отсутствие своих ключей тоже видно строкой`() {
        settings()

        compose.onNodeWithText("Своих ключей пока нет", substring = true).assertIsDisplayed()
    }

    @Test fun `выбранный уровень приватности виден строкой`() {
        settings(keys = savedKey, privacyLevel = PrivacyLevel.DEVICE_ONLY, cloudEnabled = false)

        compose.onNodeWithText(PrivacyLevel.DEVICE_ONLY.title, substring = true).assertIsDisplayed()
        compose.onNodeWithText("Облако выключено", substring = true).assertIsDisplayed()
    }

    @Test fun `разрешённое облако видно строкой вместе с уровнем`() {
        settings(keys = savedKey, cloudEnabled = true, privacyLevel = PrivacyLevel.NO_TRAINING)

        compose.onNodeWithText("Облако разрешено", substring = true).assertIsDisplayed()
        compose.onNodeWithText(PrivacyLevel.NO_TRAINING.title, substring = true).assertIsDisplayed()
    }

    @Test fun `звук показывает своё состояние тумблером — и он на экране один`() {
        settings(keys = savedKey, soundEnabled = false)

        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onAllNodes(isToggleable())[0].assertIsOff()
    }

    @Test fun `звук переключается прямо с общего экрана`() {
        var sound: Boolean? = null
        settings(keys = savedKey, soundEnabled = true, onToggleSound = { sound = it })

        compose.onAllNodes(isToggleable())[0].performClick()

        assertEquals(false, sound)
    }

    @Test fun `раздел ключей доступен изнутри своей строки`() {
        settings(keys = savedKey)

        compose.onNodeWithText("Ключи AI").performClick()

        compose.onNodeWithText("Проверить все").performScrollTo().assertIsDisplayed()
        AI_PROVIDERS.forEach { compose.onNodeWithText(it.name).performScrollTo().assertIsDisplayed() }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun `из раздела есть путь обратно в список`() {
        settings(keys = savedKey)
        compose.onNodeWithText("Ключи AI").performClick()

        compose.onNodeWithText("← Настройки").performScrollTo().performClick()

        compose.onNodeWithText("Звук действий").assertIsDisplayed()
        compose.onNodeWithText("Проверить все").assertDoesNotExist()
    }

    @Test fun `отказ приводит человека сразу в раздел ключей`() {

        settings(note = "AI недоступен — задайте свой ключ")

        compose.onNodeWithText("AI недоступен", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Проверить все").performScrollTo().assertIsDisplayed()
    }

    @Test fun `раздел приватности открывается своей строкой`() {
        var picked: PrivacyLevel? = null
        settings(keys = savedKey, onPickPrivacyLevel = { picked = it })

        compose.onNodeWithText("Отправка и приватность").performClick()

        compose.onNodeWithText("Отправка в облако").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(PRIVACY_SETTING_HINT, substring = true).performScrollTo().assertIsDisplayed()
        PrivacyLevel.entries.forEach { compose.onNodeWithText(it.title).performScrollTo().assertIsDisplayed() }

        compose.onNodeWithText(PrivacyLevel.DEVICE_ONLY.title).performScrollTo().performClick()
        assertEquals(PrivacyLevel.DEVICE_ONLY, picked)
    }

    @Test fun `строка звука открывает раздел с полным объяснением`() {
        settings(keys = savedKey)

        compose.onNodeWithText("Звук действий").performClick()

        compose.onNodeWithText("Вибрация управляется", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun `ни одна прежняя возможность не потеряна`() {
        var devices = false
        var cancelled = false
        var cloud: Boolean? = null
        var picked: PrivacyLevel? = null
        settings(
            keys = savedKey,
            onOpenDevices = { devices = true },
            onCancel = { cancelled = true },
            onToggleCloud = { cloud = it },
            onPickPrivacyLevel = { picked = it },
        )

        compose.onNodeWithText("Ключи AI").performClick()
        AI_PROVIDERS.forEach { compose.onNodeWithText(it.name).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText(openRouter.name).performScrollTo().performClick()
        compose.onNodeWithText("Открыть сайт ${openRouter.name}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ключ ${openRouter.name}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ваш ключ", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Удалить ключ").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Модель и адрес").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Проверить и включить").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сохранить без проверки").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("← Настройки").performScrollTo().performClick()
        compose.onNodeWithText("Отправка и приватность").performClick()
        compose.onAllNodes(isToggleable()).onFirst().performScrollTo().performClick()
        assertEquals(true, cloud)
        PrivacyLevel.entries.forEach {
            compose.onNodeWithText(it.what, substring = true).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(PrivacyLevel.NO_TRAINING.title).performScrollTo().performClick()
        assertEquals(PrivacyLevel.NO_TRAINING, picked)

        compose.onNodeWithText("← Настройки").performScrollTo().performClick()
        compose.onNodeWithText("Звук действий").performClick()
        compose.onNodeWithText("Вибрация управляется", substring = true).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("← Настройки").performScrollTo().performClick()
        compose.onNodeWithText(MY_DEVICES_TITLE).performClick()
        assertTrue("строка раздела не открыла круг устройств", devices)

        compose.onNodeWithText("Отмена").performScrollTo().performClick()
        assertTrue("выход с экрана настроек потерян", cancelled)
    }
}
