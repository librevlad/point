package com.point.checks

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Негодность объясняют своими словами, а не какими придётся (#1101).
 *
 * `unusable.reason` — не строка журнала. По ней потом решают, дело в самом файле или в
 * сорвавшейся попытке (`saidFailureIsFatal`), и незнакомая фраза там считается сказанной о
 * содержимом. Значит писатель со своими словами молча отнял бы объекту все двери чтения —
 * без единого падения и без единого слова в коде о том, что так нельзя.
 *
 * Слова сюда кладёт либо названная константа (`EMPTY_FILE_REASON`, `BROKEN_ARCHIVE_REASON`),
 * либо `readerFailure` — единственный, кто произносит слова о неудавшемся чтении. Правило
 * названо и у самой метки, а держит его этот сторож.
 *
 * Живёт в `:checks` (#1293): писатели разбросаны по `:app`, `:data` и `:desktop`, и падать
 * такая проверка обязана там, где ошибка.
 */
class UnfitReasonIsOurOwnWordsTest {

    /** Что кладут в метку: всё до запятой или закрывающей скобки — это и есть выражение. */
    private val writers = Regex("""META_UNUSABLE_REASON to ([^,)\n]+)""")

    /** Названная константа — наши слова, объявленные один раз и видимые всем. */
    private val named = Regex("""^[A-Z][A-Z0-9_]{2,}$""")

    @Test
    fun `слова о негодности кладёт константа или readerFailure`() {
        val said = mutableListOf<String>()

        val guilty = sources().flatMap { file ->
            val text = file.readText()
            writers.findAll(text).map { it.groupValues[1].trim() }
                .onEach { said += it }
                .filterNot { ourWords(it, text) }
                .map { "${file.name}: $it" }
                .toList()
        }

        assertTrue("сторож не нашёл ни одного писателя метки — метку переименовали?", said.isNotEmpty())
        assertTrue("негодность объяснена мимо словаря — чтения уйдут молча: $guilty", guilty.isEmpty())
    }

    /**
     * Имя годится, когда за ним стоят те же слова: местное `reason`, посчитанное тут же из
     * `readerFailure`, — это тот же единственный источник, названный короче.
     */
    private fun ourWords(expression: String, file: String): Boolean =
        expression.startsWith("readerFailure(") ||
            named.matches(expression.substringAfterLast('.')) ||
            file.contains("val $expression = readerFailure(")

    private fun sources(): List<File> =
        listOf("app/src/main", "core/flow/src/main", "core/ui/src/main", "data/src/main", "desktop/src/main", "executors/src/main")
            .map { File(repo, it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
}
