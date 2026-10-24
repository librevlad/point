package com.point.checks

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Дверь передаёт всё, что умеет обёрнутое действие (#1044, проверка 24.10.2026).
 *
 * Телефон зовёт общие действия через тонкие двери (`XCapabilityOnPhone`): дверь держит
 * `inner` и пересылает ему каждый вопрос. Вопрос, который дверь не переслала, получает ответ
 * по умолчанию, и молча. Так #1505 поставил «Скан» первым у прочитанной страницы, тест на
 * общем действии был зелёным, а на телефоне «Скан» стоял за «Показать ещё»: дверь не
 * передавала `priority`. У «Перевести» дверь так же теряла `missing`, и на непрочитанном
 * снимке телефон не говорил «сначала распознайте текст».
 *
 * Поэтому сторож сверяет: что обёрнутый класс переопределяет, то дверь передаёт.
 */
class DoorPassesWhatItWrapsTest {

    private fun bodies(dir: String): List<Pair<String, String>> =
        File(repo, dir).walkTopDown().filter { it.extension == "kt" }
            .flatMap { CapabilityIds.classBodies(code(it.readText())) }
            .toList()

    private fun overrides(body: String): Set<String> =
        OVERRIDE.findAll(body).map { it.groupValues[1] }.toSet()

    @Test
    fun `дверь переопределяет то же, что обёрнутое действие`() {
        val shared = bodies("core/flow/src/main").toMap()
        val doors = (bodies("executors/src/main") + bodies("desktop/src/main"))
            .mapNotNull { (name, body) -> INNER.find(body)?.let { Triple(name, it.groupValues[1], body) } }

        assertTrue("ни одной двери не нашлось — оборачивают иначе?", doors.size >= 10)

        val lost = doors.mapNotNull { (door, wrapped, body) ->
            val missing = overrides(shared[wrapped] ?: return@mapNotNull null) - overrides(body)
            missing.takeIf { it.isNotEmpty() }?.let { "$door не передаёт $wrapped: ${it.sorted()}" }
        }

        assertTrue(lost.joinToString("\n"), lost.isEmpty())
    }

    private companion object {
        val INNER = Regex("""private val inner = com\.point\.core\.flow\.(\w+)\(""")
        val OVERRIDE = Regex("""override (?:fun|val) (\w+)""")
    }
}
