package com.point.core.flow

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Обещания, до которого нет нажатия, в продукте не живёт (#1252, #1253).
 *
 * Конституция, инвариант 11: «Отложенная возможность не становится частью продукта только
 * потому, что код уже существует». Два контура лежали в сборке целиком — со своим DI, своими
 * ключами и зелёными тестами, — а человек не мог дойти до них ни одним нажатием:
 *
 * - облачное чтение с координатами слов писало `cloud.atoms.ref`, и того, кто его пишет, никто
 *   не звал; пять мест продукта читали ключ, которого в проде не бывает, а владелец добывал и
 *   перемерял бесплатные ключи ради пути, которого нет;
 * - платный гейт спрашивал `Entitlements.allowsPaid()`, а единственная реализация возвращала
 *   `true` константой: отказ «Это Pro-функция» человек получить не мог никогда.
 *
 * Сторож держит снос: решение возвращается шейпингом и настоящим источником — согласием на
 * внешнее чтение, аккаунтом с подпиской, — а не тем, что код опять «уже есть».
 */
class NoPromiseWithoutPressTest {

    private val repo = File("../..")

    private fun mainSources(): List<Pair<String, String>> = listOf(
        "app/src/main", "core/flow/src/main", "core/model/src/main", "core/ui/src/main",
        "data/src/main", "executors/src/main", "desktop/src/main",
    ).map { File(repo, it) }
        .filter { it.isDirectory }
        .flatMap { dir ->
            dir.walkTopDown()
                .filter { it.extension == "kt" }
                .map { it.relativeTo(repo).invariantSeparatorsPath to it.readText() }
                .toList()
        }

    @Test
    fun `слой слов приходит чтением, которое человек может запустить`() {
        val guilty = mainSources()
            .filter { (_, text) -> text.contains("cloud.atoms.ref") }
            .map { it.first }

        assertTrue(
            "ключ слоя слов, который в продукте некому положить: $guilty",
            guilty.isEmpty(),
        )
    }

    @Test
    fun `дорогая способность не встречает ворот, которых некому открыть`() {
        val guilty = mainSources()
            .filter { (_, text) -> text.contains("allowsPaid") || text.contains("Entitlements") }
            .map { it.first }

        assertTrue(
            "платный контур без источника прав вернулся: $guilty",
            guilty.isEmpty(),
        )
    }
}
