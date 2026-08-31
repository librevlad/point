package com.point.checks

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Работа дольше секунды не молчит (#555, вторая половина #288).
 *
 * Человек нажал и смотрит в экран. Пока действие не сказало, что делает, он не знает, идёт
 * ли работа вообще, — и разница между «думает» и «зависло» ему не видна. Стадию докладывают
 * те действия, чьи авторы об этом позаботились; следующее написанное действие так же легко
 * окажется немым, и заметит это человек, а не сборка.
 *
 * Правило узкое нарочно: спрашивается оно только с тех, кто сам объявил себя медленным
 * (`Latency.SLOW`). Быстрому действию докладывать нечего, и выдуманная стадия ради
 * формальности хуже молчания.
 *
 * «Нечего сказать» отличается от «забыли сказать» списком исключений: у каждого записано,
 * почему именно этому исполнителю сказать нечего. Список может только уменьшаться.
 *
 * Доклад засчитывается и через соседнюю функцию файла: у половины действий работа вынесена
 * в общий `readWithExternalEye`, `fix`, `fixOwnText`, и стадия докладывается там. Это то же
 * действие, просто разложенное на части.
 *
 * Живёт в `:checks` (#1293): проверка читает исполнителей телефона и компьютера сразу, а
 * модуля, который собирал бы оба, в проекте нет.
 */
class SlowWorkSaysWhatItDoesTest {

    /** Кому сказать нечего — и почему. Список может только уменьшаться. */
    private val nothingToSay = mapOf<String, String>()

    private val roots = listOf("executors/src/main", "core/flow/src/main", "desktop/src/main")

    private fun sources(): Map<String, String> = roots
        .map { File(repo, it) }
        .filter { it.isDirectory }
        .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
        .associate { it.relativeTo(repo).invariantSeparatorsPath to it.readText() }

    /**
     * Объявления верхнего уровня файла: имя и всё, что до следующего объявления.
     *
     * Граница текстовая, а не по скобкам: считать вложенность фигурных скобок в Kotlin со
     * шаблонными строками — отдельная неправда, а «следующее объявление слева у края» для
     * исходников проекта точна и видна глазом.
     */
    private fun declarations(src: String): List<Pair<String, String>> {
        val heads = HEAD.findAll(src).toList()
        return heads.mapIndexed { i, head ->
            val to = heads.getOrNull(i + 1)?.range?.first ?: src.length
            head.groupValues[2] to src.substring(head.range.first, to)
        }
    }

    /** Способности, объявившие себя медленными. */
    private fun slowCapabilities(sources: Map<String, String>): Set<String> =
        sources.values
            .flatMap { declarations(it) }
            .filter { (name, body) -> name.endsWith("Capability") && "Latency.SLOW" in body }
            .map { it.first }
            .toSet()

    @Test
    fun `медленное действие говорит человеку, что делает`() {
        val sources = sources()
        val slow = slowCapabilities(sources)
        assertTrue("медленных способностей не нашлось — проверка ослепла", slow.size > 5)

        val checked = mutableListOf<String>()
        val silent = mutableListOf<String>()
        sources.forEach { (path, src) ->
            val declared = declarations(src)
            declared.forEach { (name, body) ->
                if (!name.endsWith("Realizer")) return@forEach
                val serves = SERVES.find(body)?.groupValues?.get(1) ?: return@forEach
                if (serves !in slow || name in nothingToSay) return@forEach
                checked += name
                if (!saysStage(body, declared)) silent += "$path: $name (за $serves)"
            }
        }

        assertTrue("медленных исполнителей не нашлось — проверка ослепла", checked.size > 5)
        assertTrue(
            "медленное действие молчит — человек не знает, идёт ли работа: $silent",
            silent.isEmpty(),
        )
    }

    /**
     * Докладывает ли исполнитель — сам или через функцию своего файла, которую зовёт.
     *
     * Один шаг в глубину: дальше начинается не проверка, а разбор чужого кода на глаз.
     */
    private fun saysStage(body: String, declared: List<Pair<String, String>>): Boolean {
        if (STAGE in body) return true
        val called = CALL.findAll(body).map { it.groupValues[1] }.toSet()
        return declared.any { (name, own) -> name in called && own !== body && STAGE in own }
    }

    private companion object {

        const val STAGE = "reportStage"

        /** Объявление верхнего уровня: слева у края строки, с именем. */
        val HEAD = Regex(
            """^(?:@\w+\s+)?(?:internal |private |abstract |open |sealed |data |suspend |inline )*""" +
                """(class|object|fun|val|interface)\s+(\w+)""",
            RegexOption.MULTILINE,
        )

        val SERVES = Regex("""capabilityId\s*=\s*(\w+)\.ID""")

        val CALL = Regex("""\b(\w+)\s*\(""")
    }
}
