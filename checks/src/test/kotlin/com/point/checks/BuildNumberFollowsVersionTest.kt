package com.point.checks

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Номер сборки для Play выводится из версии, а не набирается числом (#1470).
 *
 * Play принимает сборку, только если её номер больше прежнего. Набранный числом, номер стоял
 * на 3 от 0.3.0 до 0.3.7, и второе обновление закрытого теста упёрлось бы в отказ магазина.
 * Сборка при этом собиралась бы зелёной: о повторе номера узнают только в консоли Play, когда
 * тестеры уже ждут исправления.
 *
 * Живёт в `:checks`: читает файл сборки телефона текстом, сам модуль его не собирает.
 */
class BuildNumberFollowsVersionTest {

    private val script = code(File(repo, "app/build.gradle.kts").readText())

    @Test
    fun `номер сборки не набран числом`() {
        val typed = Regex("""versionCode\s*=\s*\d""").find(script)

        assertTrue("номер сборки снова набран числом: ${typed?.value}", typed == null)
    }

    @Test
    fun `номер сборки считается из общей версии`() {
        assertTrue(
            "номер сборки не выводится из pointVersion",
            script.contains("versionCode = pointVersionCode") &&
                script.contains("val pointVersion = providers.gradleProperty(\"pointVersion\")"),
        )
    }
}
