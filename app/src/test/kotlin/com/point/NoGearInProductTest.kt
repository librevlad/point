package com.point

import com.point.core.flow.SETTINGS_TITLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Point не зовёт человека к предмету, которого на экране нет (#544).
 *
 * Шестерёнка исчезла с домашнего экрана в #462 — двери получили подписи и стали плитами. А слова
 * про неё остались: отказ по ключу два выпуска подряд отправлял человека искать «шестерёнку на
 * домашнем экране». Поймать это глазами нельзя: строка живёт в `:data`, в ветке отказа, и
 * показывается только тому, у кого ключа нет.
 *
 * Поэтому проверка ищет слово по исходникам продукта, а не по экранам. Комментарии из поиска
 * выброшены намеренно: история («раньше в углу стояла шестерёнка») — это знание о том, почему код
 * такой, и стирать её ради прохождения теста значило бы стирать причину.
 */
class NoGearInProductTest {

    /**
     * Корень репозитория. Ищется по `settings.gradle.kts` вверх от рабочего каталога, а не
     * записан путём: рабочий каталог у Gradle — каталог модуля, а у IDE он бывает другим.
     */
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    /** Всё, что попадает в раздаваемое приложение: `src/main` любого модуля, кроме сборочного мусора. */
    private val productSources: List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && !it.name.startsWith(".") }
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.invariantSeparatorsPath.contains("/src/main/") }
        .toList()

    /** Исходник без комментариев: сначала блочные (KDoc в том числе), потом строчные. */
    private fun code(text: String): String =
        text.replace(BLOCK_COMMENT, " ").lines().joinToString("\n") { it.substringBefore("//") }

    @Test fun `поиск и правда смотрит на исходники, а не на пустоту`() {
        // Сам сторож под сторожем: сломанный корень или фильтр дал бы пустой список — и тест ниже
        // проходил бы всегда, ничего не проверяя.
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
        // Слово живёт в `:core:flow` — его читают дверь, заголовок экрана и отказ по ключу. Пока
        // оно было написано в каждом месте своё, три имени одной вещи и разъехались.
        assertEquals("Настройки", SETTINGS_TITLE)
    }
}

private val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
