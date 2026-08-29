package com.point.checks

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Одна работа — одно имя на обоих устройствах (#840).
 *
 * Конституция: «Устройство — свойство исполнения, пока результат для человека один и тот же:
 * не создавать `PhoneCapability`, `DesktopCapability`, `RemoteCapability` ради самого
 * исполнителя».
 *
 * Расхождение стоило не красоты: Investigation State живёт по паре «объект + capabilityId».
 * Пока компьютер звал поиск значений `pc-entities`, а телефон — `entities`, объект, у
 * которого компьютер уже всё нашёл, приезжал на телефон с вопросом «а искали ли?» в
 * состоянии «не исследовано».
 *
 * Сторож смотрит на весь словарь, а не на список из трёх имён (#1254). Пока список был
 * записан здесь руками, «Прочитать документ» прошло мимо него: имя стояло голым литералом в
 * телефонном и компьютерном файлах, и опечатка в одном из них превратила бы одну работу в
 * две — документ, прочитанный на компьютере, приехал бы на телефон непрочитанным.
 */
class OneNameForOneWorkTest {

    private val dictionaryPath = "core/flow/src/main/kotlin/com/point/core/flow/KnownCapabilityIds.kt"

    private val dictionary = File(repo, dictionaryPath)

    /** Имена берутся из самого словаря: список, переписанный сюда, устаревает молча. */
    private fun sharedNames(): List<String> = Regex("""CapabilityId\("([^"]+)"\)""")
        .findAll(dictionary.readText())
        .map { it.groupValues[1] }
        .toList()

    private fun sources(vararg dirs: String): List<Pair<String, String>> = dirs
        .map { File(repo, it) }
        .filter { it.isDirectory }
        .flatMap { dir ->
            dir.walkTopDown()
                .filter { it.extension == "kt" }
                .map { it.relativeTo(repo).invariantSeparatorsPath to it.readText() }
                .toList()
        }

    private fun modules() = sources(
        "app/src/main",
        "core/flow/src/main",
        "core/ui/src/main",
        "data/src/main",
        "desktop/src/main",
        "executors/src/main",
    )

    @Test
    fun `имени устройства нет в идентификаторе общей работы`() {
        val shared = sharedNames()
        assertTrue("словарь общих имён пуст — сторожить нечего", shared.isNotEmpty())

        val guilty = modules().flatMap { (path, text) ->
            shared.filter { work -> text.contains("\"pc-$work\"") || text.contains("\"phone-$work\"") }
                .map { work -> "$path: $work" }
        }

        assertTrue("работа названа именем исполнителя: $guilty", guilty.isEmpty())
    }

    @Test
    fun `общее имя не пишется литералом мимо словаря`() {
        val shared = sharedNames()

        val guilty = modules()
            .filterNot { (path, _) -> path == dictionaryPath }
            .flatMap { (path, text) ->
                shared.filter { work -> text.contains("""CapabilityId("$work")""") }
                    .map { work -> "$path: $work" }
            }

        assertTrue(
            "общее имя написано литералом заново — опечатка разведёт одну работу на две: $guilty",
            guilty.isEmpty(),
        )
    }

    @Test
    fun `общие имена объявлены одним местом, а не литералами по модулям`() {
        assertTrue("общий словарь имён пропал", dictionary.isFile)
        assertTrue("в словаре нет поиска значений", "entities" in sharedNames())
        assertTrue("в словаре нет чтения документа", "read-document" in sharedNames())
    }
}
