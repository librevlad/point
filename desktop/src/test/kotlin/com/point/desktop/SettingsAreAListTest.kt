package com.point.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.point.core.flow.PrivacyLevel
import com.point.desktop.ui.PointDesktopTheme
import com.point.desktop.ui.SettingsPage
import com.point.desktop.ui.SettingsPrivacy
import com.point.desktop.ui.SettingsRoot
import com.point.desktop.ui.devicesLine
import com.point.desktop.ui.keysLine
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Настройки компьютера — список разделов, а не одно полотно (#886).
 *
 * Раньше здесь лежало всё сразу: круг устройств, имя компьютера, одиннадцать сервисов AI,
 * три поля ключей, данные, интеграции. Экран был длиной в три с половиной окна, и тот, кто
 * пришёл переименовать компьютер, прокручивал мимо чужой ему инфраструктуры.
 *
 * Тест смотрит на места склейки: за каждым разделом есть свой экран, крупных заголовков-статей
 * внутри настроек нет, а номер версии один на оба устройства.
 */
class SettingsAreAListTest {

    private val repo = File("..")

    private val screen = File("src/main/kotlin/com/point/desktop/ui/SettingsScreen.kt").readText()

    private val window = File("src/main/kotlin/com/point/desktop/ui/SettingsPane.kt").readText()

    @Test
    fun `у каждого раздела свой экран, а не кусок общего полотна`() {
        val pages = SettingsPage.entries.map { it.name }

        assertEquals(listOf("ROOT", "DEVICES", "KEYS", "PRIVACY", "DATA"), pages)
    }

    @Test
    fun `ключи ушли с корня настроек за строку`() {
        val root = screen.substringAfter("fun SettingsRoot(").substringBefore("internal fun devicesLine")

        assertTrue("сервисы AI вернулись в корень настроек", !root.contains("AI_PROVIDERS.forEach"))
        assertTrue("нет входа на экран ключей", root.contains("SettingsPage.KEYS"))
    }

    @Test
    fun `раздел настроек — метка, а не заголовок статьи`() {
        val section = screen.substringAfter("private fun Section(").substringBefore("/** Строка-переключатель")

        assertTrue("раздел набран не меткой", section.contains("PointType.label"))
        assertTrue("раздел снова кричит заголовком", !section.contains("PointType.title"))
    }

    @Test
    fun `слово «Настройки» сказано один раз — шапкой окна`() {
        val settings = window.substringAfter("internal fun CompactSettings(")

        assertTrue("заголовок окна не следует за экраном", settings.contains("title = page.title"))
        assertTrue("«Настройки» написано ещё раз внутри", !settings.contains("\"НАСТРОЙКИ\""))
    }

    @Test
    fun `звук действий можно выключить и на компьютере`() {
        assertTrue(
            "выключателя звука нет — настройку меняет только телефон",
            screen.contains("title = \"Звук действий\""),
        )
    }

    @Test
    fun `версия Point одна на телефон и компьютер`() {
        val properties = File(repo, "gradle.properties").readText()
        val phone = File(repo, "app/build.gradle.kts").readText()
        val pc = File(repo, "desktop/build.gradle.kts").readText()

        assertTrue("нет общего номера версии", properties.contains("pointVersion="))
        assertTrue("телефон держит свой номер", phone.contains("providers.gradleProperty(\"pointVersion\")"))
        assertTrue("компьютер держит свой номер", pc.contains("providers.gradleProperty(\"pointVersion\")"))
        assertEquals(
            "компьютер собран с чужим номером",
            properties.lineSequence().first { it.startsWith("pointVersion=") }.substringAfter('='),
            BuildInfo.VERSION,
        )
    }

    @Test
    fun `строка устройств называет почту и считает устройства`() {
        val two = devicesLine("me@point.app", 2)

        assertTrue("почта не названа: непонятно, чей это круг", two.contains("me@point.app"))
        assertTrue("устройства не сосчитаны", two.contains("2"))
        assertTrue("счёт не согласован со словом", devicesLine("me@point.app", 1).contains("одно"))
        assertTrue("без почты строка не должна начинаться с разделителя", !devicesLine("", 5).startsWith(" ·"))
    }

    /**
     * Сводка «Отправка и приватность» в корне называет выбранный уровень — и только его
     * (#1003, решение владельца 20.08.2026: показывается ровно имя уровня, без «Облако
     * разрешено/выключено»).
     *
     * Смотрится экран, а не исходник. Раньше это место сторожила подстрока `config.privacy.title`
     * в тексте `SettingsScreen.kt`: такой сторож держит букву кода, а не то, что человек читает, —
     * подпись могла не дойти до окна, и проверка осталась бы зелёной.
     *
     * Уровень назван и тогда, когда согласия на облако ещё нет: выбор человека — его состояние,
     * и оно видно с корня, без захода внутрь. Разные состояния обязаны выглядеть по-разному
     * (P9): «Не учатся на моём» не должно читаться так же, как «Максимум бесплатного».
     */
    @Test
    fun `сводка отправки называет выбранный уровень и только его`() {
        PrivacyLevel.entries.forEach { level ->
            val seen = shownInRoot(PcConfig(name = "User-PC", privacy = level))
            val others = PrivacyLevel.entries.filter { it != level }.map { it.title }.filter { it in seen }

            assertTrue("выбранное «${level.title}» не дошло до корня — видно $seen", level.title in seen)
            assertTrue("в корне назван невыбранный уровень $others — видно $seen", others.isEmpty())
        }
    }

    /** Сторож обещания: «Облако разрешено · Только на этом устройстве» читалось противоречием (#1003). */
    @Test
    fun `сводка отправки не оправдывается облаком`() {
        PrivacyLevel.entries.forEach { level ->
            val seen = shownInRoot(PcConfig(name = "User-PC", privacy = level))

            assertTrue(
                "сводка снова оправдывается облаком — видно $seen",
                seen.none { it.contains("облак", ignoreCase = true) },
            )
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `строка отправки открывает свой экран`() = runComposeUiTest {
        val opened = mutableListOf<SettingsPage>()
        setContent { PointDesktopTheme { rootScreen(PcConfig(name = "User-PC"), onOpen = { opened += it }) } }

        onNodeWithText(SettingsPage.PRIVACY.title).performClick()

        assertEquals(listOf(SettingsPage.PRIVACY), opened)
    }

    /**
     * Согласие на облако видно и отзывается на своём экране: разрешение без выхода из него
     * нарушает §11 — объект уходит с устройства только по живому согласию (#886).
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `разрешение на облако видно и отзывается`() = runComposeUiTest {
        var revoked = false
        setContent {
            PointDesktopTheme {
                SettingsPrivacy(
                    allowed = true,
                    level = PrivacyLevel.DEFAULT,
                    onRevoke = { revoked = true },
                    onPickLevel = {},
                )
            }
        }

        onNodeWithText(REVOKE).performClick()

        assertTrue("выход из разрешения нажат, а согласие осталось", revoked)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `пока облако не разрешено — забирать нечего`() = runComposeUiTest {
        setContent {
            PointDesktopTheme {
                SettingsPrivacy(allowed = false, level = PrivacyLevel.DEFAULT, onRevoke = {}, onPickLevel = {})
            }
        }

        onNodeWithText(REVOKE).assertDoesNotExist()
    }

    /** Что написано в корне настроек — все надписи собранного экрана, как их читает человек. */
    @OptIn(ExperimentalTestApi::class)
    private fun shownInRoot(pc: PcConfig): List<String> {
        val texts = mutableListOf<String>()
        runComposeUiTest {
            setContent { PointDesktopTheme { rootScreen(pc) } }
            texts += onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
                .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        }
        return texts
    }

    @Composable
    private fun rootScreen(config: PcConfig, onOpen: (SettingsPage) -> Unit = {}) =
        SettingsRoot(config = config, devices = 0, email = "", onSave = { true }, onOpen = onOpen)

    @Test
    fun `строка ключей считает свои ключи так же, как на телефоне`() {
        val empty = PcConfig(name = "User-PC")
        val one = empty.copy(
            aiKeys = empty.aiKeys.with(com.point.core.flow.UserAiKey("openrouter", "sk-мой")),
        )

        assertEquals(com.point.core.flow.aiKeysSummary(empty.aiKeys), keysLine(empty))
        assertEquals(com.point.core.flow.aiKeysSummary(one.aiKeys), keysLine(one))
        assertTrue("ноль не сосчитан", keysLine(empty).contains("0"))
        assertTrue("свой ключ не сосчитан", keysLine(one).contains("1"))
    }

    private companion object {

        /** Выход из разрешения — им экран и нажимается, как пальцем. */
        const val REVOKE = "Забрать разрешение"
    }
}
