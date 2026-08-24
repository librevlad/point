package com.point

import com.point.core.flow.SETTINGS_TITLE
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Дверь настроек названа одним словом на все модули (#462).
 *
 * Осталось от `NoGearInProductTest` после #1293: сам обход исходников проекта уехал в
 * `:checks`, а эта проверка сверяет константу `:core:flow` и живёт там, где её и собирают.
 */
class SettingsDoorHasOneNameTest {

    @Test
    fun `дверь настроек названа одним словом на все модули`() {

        assertEquals("Настройки", SETTINGS_TITLE)
    }
}
