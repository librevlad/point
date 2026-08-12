package com.point.desktop

import com.point.desktop.ui.SettingsPage
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
 * Сторож смотрит на швы: за каждым разделом есть свой экран, крупных заголовков-статей
 * внутри настроек нет, а номер версии один на оба устройства.
 */
class SettingsAreAListTest {

    private val repo = File("..")

    private val screen = File("src/main/kotlin/com/point/desktop/ui/SettingsScreen.kt").readText()

    private val window = File("src/main/kotlin/com/point/desktop/ui/CompactApp.kt").readText()

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
        val settings = window.substringAfter("private fun CompactSettings(").substringBefore("\n@Composable")

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

    @Test
    fun `разрешение на облако можно увидеть и забрать`() {
        val root = screen.substringAfter("fun SettingsRoot(").substringBefore("internal fun devicesLine")

        assertTrue("на компьютере не видно, разрешено ли облако", root.contains("SettingsPage.PRIVACY"))
        assertTrue("разрешение нельзя забрать", screen.contains("Забрать разрешение"))
        assertTrue(com.point.desktop.ui.cloudLine(true).contains("разрешено"))
        assertTrue(com.point.desktop.ui.cloudLine(false).contains("каждый раз"))
    }

    @Test
    fun `строка ключей говорит, есть ли свой ключ`() {
        val empty = PcConfig(name = "User-PC")

        assertTrue(keysLine(empty).contains("Своего ключа нет"))
        assertTrue(keysLine(empty.copy(ai = empty.ai.copy(key = "sk-мой"))).contains("Свой ключ задан"))
    }
}
