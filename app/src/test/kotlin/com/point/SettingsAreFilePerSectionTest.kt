package com.point

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * У каждого раздела настроек свой файл (#834).
 *
 * `KeyScreen.kt` был 1039 строк и 46 composable: список разделов, ключи девяти сервисов с
 * проверкой и редактором, приватность, звук, точки входа, память, версия. Разделы не знают
 * друг о друге, но делили один файл и его состояние — добавление одной строки в настройки
 * требовало правки в пяти местах.
 *
 * Решение владельца: «файл на раздел».
 */
class SettingsAreFilePerSectionTest {

    private val dir = File("src/main/kotlin/com/point")

    @Test
    fun `у каждого раздела свой файл`() {
        listOf(
            "SettingsKeysSection.kt",
            "SettingsPrivacySection.kt",
            "SettingsEntriesSection.kt",
            "SettingsMemorySection.kt",
            "SettingsAppSection.kt",
        ).forEach { assertTrue("раздела нет: $it", File(dir, it).isFile) }
    }

    @Test
    fun `общий файл держит список и переходы, а не сами разделы`() {
        val root = File(dir, "KeyScreen.kt").readText()

        assertTrue("список разделов пропал", root.contains("fun SettingsList("))
        listOf("fun KeySection(", "fun PrivacySection(", "fun MemorySection(").forEach {
            assertTrue("раздел вернулся в общий файл: $it", !root.contains(it))
        }
    }

    @Test
    fun `ни один файл настроек не разрастается снова`() {
        val big = dir.listFiles().orEmpty()
            .filter { it.name == "KeyScreen.kt" || it.name.startsWith("Settings") }
            .filter { it.readLines().size > 600 }
            .map { it.name + ":" + it.readLines().size }

        assertTrue("файл настроек снова стал складом: $big", big.isEmpty())
    }
}
