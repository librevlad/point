package com.point.core.flow

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
 */
class OneNameForOneWorkTest {

    private val repo = File("../..")

    private fun sources(vararg dirs: String): List<Pair<String, String>> = dirs
        .map { File(repo, it) }
        .filter { it.isDirectory }
        .flatMap { dir ->
            dir.walkTopDown()
                .filter { it.extension == "kt" }
                .map { it.relativeTo(repo).invariantSeparatorsPath to it.readText() }
                .toList()
        }

    @Test
    fun `имени устройства нет в идентификаторе общей работы`() {
        val shared = listOf("entities", "transcribe", "ocr")

        val guilty = sources("desktop/src/main", "executors/src/main", "data/src/main")
            .flatMap { (path, text) ->
                shared.filter { work -> text.contains("\"pc-$work\"") || text.contains("\"phone-$work\"") }
                    .map { work -> "$path: $work" }
            }

        assertTrue("работа названа именем исполнителя: $guilty", guilty.isEmpty())
    }

    @Test
    fun `общие имена объявлены одним местом, а не литералами по модулям`() {
        val declared = File(repo, "core/flow/src/main/kotlin/com/point/core/flow/KnownCapabilityIds.kt")

        assertTrue("общий словарь имён пропал", declared.isFile)
        assertTrue("в словаре нет поиска значений", declared.readText().contains("""CapabilityId("entities")"""))
    }
}
