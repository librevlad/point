package com.point

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
import com.point.core.flow.UsageSummary
import com.point.core.flow.UserAiConfig
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Настройки читаются как настройки, а не как статья (#563).
 *
 * Претензия владельца — «неудобно читать — сделай как у всех, там ещё много будет». Экран собирался
 * из правильных кирпичей: заголовок раздела, абзац-объяснение, кнопка, ещё абзац, поле, ещё абзац —
 * и так пять разделов. Каждый абзац по делу, а вместе стена: чтобы найти «Звук действий», человек
 * прокручивал три экрана текста про ключ AI. Ловится это только тестом на СОСТАВ первого экрана —
 * ни один тест на отдельную возможность такого не видит, потому что все возможности были на месте.
 *
 * Плоского списка из пяти строк оказалось мало: владелец, увидев его, сказал «настройки надо по
 * секциям! как в телеграме, как в Claude, как везде». Поэтому здесь проверяются и группы — их
 * заголовки и то, что каждая строка лежит в своей.
 *
 * Размер окна назван вслух ([Config]) по той же причине, что в [SettingsCompositionTest]: «видно без
 * прокрутки» — утверждение про экран телефона, и без заданного окна оно означало бы «видно на том,
 * что подсунул Robolectric».
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SettingsListTest {

    @get:Rule val compose = createComposeRule()

    private val openRouter = AI_PROVIDERS.first()
    private val savedKey = UserAiConfig("sk-or-v1-abcdef123456", openRouter.baseUrl, "gemma")

    private fun settings(
        config: UserAiConfig = UserAiConfig.DEFAULT,
        note: String? = null,
        soundEnabled: Boolean = true,
        usageEnabled: Boolean = false,
        usageSummary: UsageSummary? = null,
        cloudEnabled: Boolean = false,
        privacyLevel: PrivacyLevel = PrivacyLevel.DEFAULT,
        onToggleSound: (Boolean) -> Unit = {},
        onToggleUsage: (Boolean) -> Unit = {},
        onToggleCloud: (Boolean) -> Unit = {},
        onPickPrivacyLevel: (PrivacyLevel) -> Unit = {},
        onOpenDevices: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) = compose.setContent {
        PointTheme(darkTheme = true) {
            KeyScreen(
                config = config,
                note = note,
                onSave = {},
                onCancel = onCancel,
                usageEnabled = usageEnabled,
                usageSummary = usageSummary,
                onToggleUsage = onToggleUsage,
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

    // --- Общий экран — группы со своими заголовками, и в них все строки ---

    @Test fun `все группы и строки достижимы, ни одна не потеряна`() {
        settings(config = savedKey)

        // Заголовки групп говорят разрядкой заглавными — это и есть их текст на экране.
        compose.onNodeWithText("AI И ОБЛАКО").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("АККАУНТ").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ПРИЛОЖЕНИЕ").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Ключ AI").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Отправка и приватность").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(MY_DEVICES_TITLE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Звук действий").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Приватная статистика").performScrollTo().assertIsDisplayed()
    }

    @Test fun `список начинается со строк, а не с абзацев — первая группа видна сразу`() {
        settings(config = savedKey)

        // Без `performScrollTo` намеренно: тест провалится ровно тогда, когда наверху экрана снова
        // вырастет объяснение и строки начнут уезжать за край.
        compose.onNodeWithText("AI И ОБЛАКО").assertIsDisplayed()
        compose.onNodeWithText("Ключ AI").assertIsDisplayed()
        compose.onNodeWithText("Отправка и приватность").assertIsDisplayed()
    }

    @Test fun `каждая строка лежит в своей группе`() {
        settings(config = savedKey)

        // Порядок сверху вниз — то единственное, чем на экране выражена принадлежность строки
        // группе. Судим по настоящим координатам, а не по порядку вызовов в коде.
        val order = listOf(
            "AI И ОБЛАКО", "Ключ AI", "Отправка и приватность",
            "АККАУНТ", MY_DEVICES_TITLE,
            "ПРИЛОЖЕНИЕ", "Звук действий", "Приватная статистика",
        )
        order.zipWithNext { above, below ->
            assertTrue(
                "«$below» ушла из своей группы — она стоит не под «$above»",
                topOf(above) < topOf(below),
            )
        }
    }

    /** Где строка начинается по вертикали — в точках корня экрана. */
    private fun topOf(text: String): Float =
        compose.onNodeWithText(text).getUnclippedBoundsInRoot().top.value

    @Test fun `на общем экране нет ни одного абзаца — они внутри разделов`() {
        settings(config = savedKey)

        // Ровно то, что человек прокручивал, чтобы добраться до звука.
        compose.onNodeWithText("ШАГ 1 · ОТКУДА ВЗЯТЬ КЛЮЧ").assertDoesNotExist()
        compose.onNodeWithText(AI_KEY_WHY, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Проверить и включить").assertDoesNotExist()
        compose.onNodeWithText(PRIVACY_SETTING_HINT, substring = true).assertDoesNotExist()
        compose.onNodeWithText(PrivacyLevel.FREE_FIRST.what, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Вибрация управляется", substring = true).assertDoesNotExist()
        compose.onNodeWithText("мерит, экономит ли Point", substring = true).assertDoesNotExist()
    }

    // --- Состояние видно, не открывая ---

    @Test fun `заданный ключ виден строкой, не открывая раздел`() {
        settings(config = savedKey)

        compose.onNodeWithText("Ключ на устройстве", substring = true).assertIsDisplayed()
        compose.onNodeWithText("sk-o…3456", substring = true).assertExists()
    }

    @Test fun `отсутствие ключа тоже видно строкой`() {
        settings(config = UserAiConfig("", openRouter.baseUrl, "gemma"))

        compose.onNodeWithText("Ключа пока нет", substring = true).assertIsDisplayed()
    }

    @Test fun `выбранный уровень приватности виден строкой`() {
        settings(config = savedKey, privacyLevel = PrivacyLevel.DEVICE_ONLY, cloudEnabled = false)

        compose.onNodeWithText(PrivacyLevel.DEVICE_ONLY.title, substring = true).assertIsDisplayed()
        compose.onNodeWithText("Облако выключено", substring = true).assertIsDisplayed()
    }

    @Test fun `разрешённое облако видно строкой вместе с уровнем`() {
        settings(config = savedKey, cloudEnabled = true, privacyLevel = PrivacyLevel.NO_TRAINING)

        compose.onNodeWithText("Облако разрешено", substring = true).assertIsDisplayed()
        compose.onNodeWithText(PrivacyLevel.NO_TRAINING.title, substring = true).assertIsDisplayed()
    }

    @Test fun `звук и статистика показывают своё состояние тумблерами`() {
        settings(config = savedKey, soundEnabled = true, usageEnabled = false)

        // Два тумблера на общем экране — звук и статистика, в том же порядке, что строки.
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
        compose.onAllNodes(isToggleable())[0].assertIsOn()
        compose.onAllNodes(isToggleable())[1].assertIsOff()
    }

    // --- Переключать по-прежнему одним тапом ---

    @Test fun `звук переключается прямо с общего экрана`() {
        var sound: Boolean? = null
        settings(config = savedKey, soundEnabled = true, onToggleSound = { sound = it })

        compose.onAllNodes(isToggleable())[0].performClick()

        assertEquals(false, sound)
    }

    @Test fun `статистика переключается прямо с общего экрана`() {
        var usage: Boolean? = null
        settings(config = savedKey, usageEnabled = false, onToggleUsage = { usage = it })

        compose.onAllNodes(isToggleable())[1].performClick()

        assertEquals(true, usage)
    }

    // --- Мастер ключа остался мастером, но внутри своего раздела ---

    @Test fun `мастер ключа доступен изнутри своего раздела`() {
        settings(config = savedKey)

        compose.onNodeWithText("Ключ AI").performClick()

        compose.onNodeWithText("ШАГ 1 · ОТКУДА ВЗЯТЬ КЛЮЧ").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ШАГ 2 · ВСТАВЬТЕ КЛЮЧ").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ШАГ 3 · ПРОВЕРЬТЕ, ЧТО РАБОТАЕТ").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }

    @Test fun `из раздела есть путь обратно в список`() {
        settings(config = savedKey)
        compose.onNodeWithText("Ключ AI").performClick()

        compose.onNodeWithText("← Настройки").performScrollTo().performClick()

        compose.onNodeWithText("Звук действий").assertIsDisplayed()
        compose.onNodeWithText("ШАГ 1 · ОТКУДА ВЗЯТЬ КЛЮЧ").assertDoesNotExist()
    }

    @Test fun `отказ приводит человека сразу в раздел ключа`() {
        // Экран, открытый по поводу ключа, обязан показать ключ — иначе человек, выброшенный сюда
        // отказом, начинал бы со списка разделов и искал в нём, куда ему.
        settings(note = "AI недоступен — задайте свой ключ")

        compose.onNodeWithText("AI недоступен", substring = true).assertIsDisplayed()
        compose.onNodeWithText("ШАГ 1 · ОТКУДА ВЗЯТЬ КЛЮЧ").performScrollTo().assertIsDisplayed()
    }

    @Test fun `раздел приватности открывается своей строкой`() {
        var picked: PrivacyLevel? = null
        settings(config = savedKey, onPickPrivacyLevel = { picked = it })

        compose.onNodeWithText("Отправка и приватность").performClick()

        compose.onNodeWithText("Отправка в облако").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(PRIVACY_SETTING_HINT, substring = true).performScrollTo().assertIsDisplayed()
        PrivacyLevel.entries.forEach { compose.onNodeWithText(it.title).performScrollTo().assertIsDisplayed() }

        compose.onNodeWithText(PrivacyLevel.DEVICE_ONLY.title).performScrollTo().performClick()
        assertEquals(PrivacyLevel.DEVICE_ONLY, picked)
    }

    @Test fun `строка звука открывает раздел с полным объяснением`() {
        settings(config = savedKey, usageEnabled = true, usageSummary = UsageSummary(4, 9, 2))

        compose.onNodeWithText("Звук действий").performClick()

        // Второе предложение про вибрацию — то, что не влезло в строку списка, — лежит здесь.
        compose.onNodeWithText("Вибрация управляется", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("мерит, экономит ли Point", substring = true).performScrollTo().assertIsDisplayed()
        // И счётчик, который этот тумблер насчитал.
        compose.onNodeWithText("Объектов: 4", substring = true).performScrollTo().assertIsDisplayed()
    }

    /**
     * Перекладывание не потеряло ни одной возможности — и они перечислены поимённо.
     *
     * Тест длинный намеренно: цена такой перестройки — тихо потерянная строка, которую никто не
     * хватится, пока она не понадобится живому человеку. Здесь названо всё, что экран умел до #563.
     */
    @Test fun `ни одна прежняя возможность не потеряна`() {
        var devices = false
        var cancelled = false
        var cloud: Boolean? = null
        var picked: PrivacyLevel? = null
        settings(
            config = savedKey,
            usageEnabled = true,
            usageSummary = UsageSummary(objects = 42, actions = 118, completed = 31),
            onOpenDevices = { devices = true },
            onCancel = { cancelled = true },
            onToggleCloud = { cloud = it },
            onPickPrivacyLevel = { picked = it },
        )

        // 1. Раздел ключа: ссылка на страницу сервиса, выбор сервиса из семи, поле ключа, состояние
        //    ключа, «Забыть ключ», модель с адресом, живая проверка и тихое сохранение.
        compose.onNodeWithText("Ключ AI").performClick()
        compose.onNodeWithText("Открыть сайт ${openRouter.name}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сервис").performScrollTo().performClick()
        AI_PROVIDERS.forEach { compose.onNodeWithText(it.name).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText(openRouter.name).performScrollTo().performClick() // свернулся обратно
        compose.onNodeWithText("API-ключ").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ключ на устройстве", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Забыть ключ").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Модель и адрес").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Проверить и включить").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Сохранить без проверки").performScrollTo().assertIsDisplayed()

        // 2. Раздел отправки: тумблер облака и три уровня приватности с их ценой.
        compose.onNodeWithText("← Настройки").performScrollTo().performClick()
        compose.onNodeWithText("Отправка и приватность").performClick()
        compose.onAllNodes(isToggleable()).onFirst().performScrollTo().performClick()
        assertEquals(true, cloud)
        PrivacyLevel.entries.forEach {
            compose.onNodeWithText(it.what, substring = true).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText(PrivacyLevel.NO_TRAINING.title).performScrollTo().performClick()
        assertEquals(PrivacyLevel.NO_TRAINING, picked)

        // 3. Раздел приложения: звук, статистика и её счётчик.
        compose.onNodeWithText("← Настройки").performScrollTo().performClick()
        compose.onNodeWithText("Приватная статистика").performClick()
        compose.onNodeWithText("Звук действий").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Объектов: 42", substring = true).performScrollTo().assertIsDisplayed()

        // 4. Круг устройств — тем же вызовом наверх, что и до перекладывания.
        compose.onNodeWithText("← Настройки").performScrollTo().performClick()
        compose.onNodeWithText(MY_DEVICES_TITLE).performClick()
        assertTrue("строка раздела не открыла круг устройств", devices)

        // 5. И выход с экрана — там же, где был.
        compose.onNodeWithText("Отмена").performScrollTo().performClick()
        assertTrue("выход с экрана настроек потерян", cancelled)
    }
}
