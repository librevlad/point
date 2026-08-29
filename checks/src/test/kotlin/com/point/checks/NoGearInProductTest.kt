package com.point.checks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Шестерёнки, которой на экране нет с #462, нет и в словах продукта, а у каждого слова
 * настроек один дом на все модули (#1249).
 *
 * Живёт в `:checks` (#1293): проверка обходит исходники всего проекта целиком, вместе с
 * компьютером, а `:app` компьютер не собирает.
 */
class NoGearInProductTest {

    private val productSources: List<File> = repo.walkTopDown()
        .onEnter { it.name != "build" && !it.name.startsWith(".") }
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.invariantSeparatorsPath.contains("/src/main/") }
        .toList()

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

    /**
     * Дверь настроек и её разделы человек открывает и на телефоне, и в окне компьютера — и
     * зовутся они одним словом на все модули (#1249, #1144 DIST-2).
     *
     * Обещание в имени этого теста раньше держала одна строка — сверка SETTINGS_TITLE с самим
     * словом. Ни одного модуля она не открывала и падала только на себя, а слова совпадали
     * случайно: окно компьютера набирало все пять заголовков своими литералами. Здесь
     * проверяется обещанное: у слова один дом, а копий нет ни в одном модуле.
     *
     * Само слово берётся из объявления, а не переписывается сюда: `:checks` намеренно живёт
     * без зависимостей (#1293), и второй список слов разошёлся бы с продуктом молча. Пропало
     * объявление — падает счёт домов, а не тишина.
     *
     * Ищется точный литерал целиком. Строка «Настройки не открылись …» в SourcePickerActivity
     * зовёт системные настройки Android — чужая дверь, и своим словом её звать нечем.
     */
    @Test fun `у каждого слова настроек один дом на все модули`() {

        val sources = productSources.map { it.toRelativeString(repo) to code(it.readText()) }

        val copies = SETTINGS_WORDS.flatMap { name ->
            val declaration = Regex("""const val $name = "([^"\n]+)"""")
            val homes = sources.mapNotNull { (path, code) ->
                declaration.find(code)?.let { path to it.groupValues[1] }
            }

            assertEquals(
                "у слова $name домов не один, а ${homes.size}: ${homes.map { it.first }}",
                1,
                homes.size,
            )

            val (home, word) = homes.single()
            sources.filter { (path, code) -> path != home && "\"$word\"" in code }
                .map { "«$word» — ${it.first}" }
        }

        assertEquals(
            "слово настроек набрано вторым литералом — переименование в одном месте оставит " +
                "второе окно со старым именем: $copies",
            emptyList<String>(),
            copies,
        )
    }

    private companion object {

        /** Дверь настроек и заголовки её разделов — то, что человек читает на обоих устройствах. */
        val SETTINGS_WORDS = listOf(
            "SETTINGS_TITLE", "MY_DEVICES_TITLE", "KEY_SECTION_TITLE", "PRIVACY_SECTION_TITLE", "MEMORY_TITLE",
        )
    }
}

