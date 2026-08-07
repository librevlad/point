package com.point

import com.point.core.flow.SETTINGS_TITLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoGearInProductTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val productSources: List<File> = root.walkTopDown()
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
            .map { it.toRelativeString(root) }

        assertEquals("эти файлы зовут к шестерёнке, которой на экране нет с #462: $guilty", emptyList<String>(), guilty)
    }

    @Test fun `дверь настроек названа одним словом на все модули`() {

        assertEquals("Настройки", SETTINGS_TITLE)
    }
}

private val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
