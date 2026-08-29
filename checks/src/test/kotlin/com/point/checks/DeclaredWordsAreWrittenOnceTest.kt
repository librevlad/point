package com.point.checks

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Слово, уже объявленное человеку, не переписывается литералом заново (#1254).
 *
 * #1254 заведена ровно про это: одно обещание человеку жило в двух файлах, которые никто не
 * сверял. «Расшифровать» обещало «слова записи · запись уйдёт в сервис» голым литералом и в
 * `:executors`, и в `:desktop`; правка формулировки на одной стороне молча оставила бы вторую
 * с прежними словами, и одна работа заговорила бы на двух устройствах по-разному.
 *
 * Сторож смотрит на то, что уже объявлено в `:core:flow`, — как `OneNameForOneWorkTest`
 * смотрит на словарь имён. Задним числом он вторую копию не поймал бы: фразы в словаре не
 * было. Зато теперь каждое объявленное слово, нынешнее и будущее, под сторожем без правки
 * этого теста.
 *
 * Живёт в `:checks` (#1293): читает `src/main` всех модулей сразу, а такого модуля нет.
 */
class DeclaredWordsAreWrittenOnceTest {

    private val dictionary = File(repo, "core/flow/src/main")

    private val modules = listOf(
        "app/src/main",
        "core/flow/src/main",
        "core/ui/src/main",
        "data/src/main",
        "desktop/src/main",
        "executors/src/main",
    )

    private val declaration =
        Regex("""^\s*(?:internal )?const val [A-Z][A-Z0-9_]*(?:: String)? = "([^"]*)"\s*$""", RegexOption.MULTILINE)

    /** Слова человеку → файл, где они объявлены. */
    private fun declaredWords(): Map<String, String> = dictionary.walkTopDown()
        .filter { it.extension == "kt" }
        .flatMap { file ->
            val home = file.relativeTo(repo).invariantSeparatorsPath
            declaration.findAll(file.readText())
                .map { it.groupValues[1] }
                .filter { said -> said.length >= HUMAN_ENOUGH && said.any { it in 'а'..'я' || it in 'А'..'Я' } }
                .map { said -> said to home }
        }
        .toMap()

    private fun sources(): List<Pair<String, String>> = modules
        .map { File(repo, it) }
        .filter { it.isDirectory }
        .flatMap { dir ->
            dir.walkTopDown()
                .filter { it.extension == "kt" }
                .map { it.relativeTo(repo).invariantSeparatorsPath to it.readText() }
                .toList()
        }

    @Test
    fun `объявленное слово человеку не написано литералом второй раз`() {
        val words = declaredWords()
        assertTrue("объявленных слов не нашлось — сторожить нечего", words.size > 20)

        val guilty = sources().flatMap { (path, text) ->
            words.filter { (said, home) -> path != home && text.contains("\"" + said + "\"") }
                .map { (said, home) -> "$path пишет заново слово из $home: «$said»" }
        }

        assertTrue("одно слово человеку живёт в двух файлах, и сверять их некому: $guilty", guilty.isEmpty())
    }

    @Test
    fun `обещание расшифровки объявлено один раз на оба устройства`() {
        val guilty = listOf(
            "executors/src/main/kotlin/com/point/executors/TranscribeAction.kt",
            "desktop/src/main/kotlin/com/point/desktop/SpeechActions.kt",
        ).filterNot { File(repo, it).readText().contains("SPEECH_PROMISE") }

        assertTrue("обещание написано мимо общего слова: $guilty", guilty.isEmpty())
    }

    private companion object {

        /** Короткое совпадение бывает случайным; слово человеку короче этого не бывает. */
        const val HUMAN_ENOUGH = 12
    }
}
