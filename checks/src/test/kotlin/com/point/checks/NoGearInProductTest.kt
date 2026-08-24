package com.point.checks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Шестерёнки, которой на экране нет с #462, нет и в словах продукта.
 *
 * Живёт в `:checks` (#1293): проверка обходит исходники всего проекта целиком, вместе с
 * компьютером, а `:app` компьютер не собирает. Тест про имя двери настроек остался в `:app`
 * — он сверяет константу, а не читает чужие файлы.
 */
class NoGearInProductTest {

    private val productSources: List<File> = repo.walkTopDown()
        .onEnter { it.name != "build" && !it.name.startsWith(".") }
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.invariantSeparatorsPath.contains("/src/main/") }
        .toList()

    private fun code(text: String): String =
        text.replace(BLOCK_COMMENT, " ").lines().joinToString("\n") { it.substringBefore("//") }

    @Test fun `поиск и правда смотрит на исходники, а не на пустоту`() {

        assertTrue("исходников продукта не нашлось — сломан поиск, а не продукт", productSources.size > 100)
        assertTrue(productSources.any { it.name == "HomeScreen.kt" })
    }

    @Test fun `ни одна строка продукта не зовёт человека к шестерёнке`() {
        val guilty = productSources
            .filter { code(it.readText()).contains("шестерён", ignoreCase = true) }
            .map { it.toRelativeString(repo) }

        assertEquals("эти файлы зовут к шестерёнке, которой на экране нет с #462: $guilty", emptyList<String>(), guilty)
    }
}

private val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
