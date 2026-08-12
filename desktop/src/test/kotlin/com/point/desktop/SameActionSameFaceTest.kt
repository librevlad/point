package com.point.desktop

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Одно действие — одно лицо на обоих устройствах (#879).
 *
 * Значки лежат в одном месте с #849, но зовут их по ключу: пока компьютер называл
 * расшифровку «voice», а телефон «transcribe», общая таблица про это действие ничего не
 * знала и рисовала молнию-заглушку. Человек видел незаконченный интерфейс там, где всё
 * готово.
 *
 * Сторож сверяет не картинки, а ключи: каждый ключ, которым компьютер зовёт значок, обязан
 * быть известен общей таблице.
 */
class SameActionSameFaceTest {

    private val repo = File("..")

    private val shared = File(repo, "core/ui/src/shared/kotlin/com/point/core/ui/BubbleIcons.kt").readText()

    private fun keysKnownToTable(): Set<String> =
        Regex(""""([a-z0-9:_-]+)" ->""").findAll(shared).map { it.groupValues[1] }.toSet()

    private fun keysUsedByDesktop(): Map<String, String> =
        File("src/main/kotlin/com/point/desktop").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                Regex("""override val icon = "([^"]+)"""").findAll(file.readText())
                    .map { it.groupValues[1] to file.name }
            }
            .toMap()

    @Test
    fun `каждый значок компьютера известен общей таблице`() {
        val known = keysKnownToTable()

        val strangers = keysUsedByDesktop().filterKeys { it !in known }

        assertTrue("значок нарисуется заглушкой: $strangers", strangers.isEmpty())
    }

    @Test
    fun `расшифровка и поиск зовутся теми же именами, что на телефоне`() {
        val used = keysUsedByDesktop().keys

        assertTrue("расшифровка зовётся не так, как на телефоне", "transcribe" in used)
        assertTrue("поиск зовётся не так, как на телефоне", "find" in used)
    }

    /**
     * Группы действий на экране объекта — то же правило, что на телефоне. Оно живёт в общем
     * каталоге, и компьютер обязан звать именно его, а не складывать всё в один список.
     */
    @Test
    fun `компьютер группирует действия общим правилом`() {
        val screen = File("src/main/kotlin/com/point/desktop/ui/CompactApp.kt").readText()

        assertTrue("группировка своя, а не общая", screen.contains("actionGroupOrder("))
        assertTrue("старый общий заголовок вернулся", !screen.contains("ЧТО МОЖНО СДЕЛАТЬ"))
    }

    @Test
    fun `у действия на компьютере есть намерение, а не умолчание`() {
        val registry = File("src/main/kotlin/com/point/desktop/DesktopRegistry.kt").readText()

        assertTrue("намерение не проставляется — все действия сольются в одну группу",
            registry.contains("primaryIntentOf("))
    }
}
