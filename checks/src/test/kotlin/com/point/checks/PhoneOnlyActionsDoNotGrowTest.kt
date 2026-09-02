package com.point.checks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Умение, которого нет на компьютере, названо вслух и список не растёт (#1379, волна 3).
 *
 * Решение владельца 01.09.2026: «пк должен все уметь не хуже телефона». Волна 1 свела общую
 * работу в `:core:flow`, волна 2 (нативная обработка кадра) идёт отдельными карточками.
 * Между ними живёт разрыв, и опасен не он сам, а его молчание: новая способность телефона
 * добавляется одной строкой в `CapabilityModule`, а компьютер о ней не узнаёт никогда — при
 * зелёных тестах.
 *
 * Поэтому здесь не запрет, а список, который может только уменьшаться, — тот же приём, что
 * у цемента формулировок (#584). Появилось умение без пары на компьютере — сборка падает, и
 * человек либо делает его на компьютере, либо вписывает сюда причину. Причину читает
 * следующий, а не вспоминает.
 *
 * Ключ сверки — id способности, а не имя класса: телефон носит двери `XCapabilityOnPhone`,
 * и по именам классов одна работа выглядела бы двумя.
 *
 * Живёт в `:checks` (#1293): проверка читает `:executors` и `:desktop` разом, а модуля,
 * который собирал бы оба, в проекте нет — один android-библиотека, другой обычный JVM.
 */
class PhoneOnlyActionsDoNotGrowTest {

    private val listed = File(repo, LIST)

    /** Способности телефона: то, что Point объявляет своим на этом устройстве. */
    private fun phoneIds(): Set<String> {
        val ids = CapabilityIds.map(repo, SOURCES)
        val module = File(repo, PHONE_MODULE).readText()
        val bound = Regex("""@OwnCapabilities\s+abstract fun \w+\(\s*\w+:\s*(\w+)\s*\):\s*Capability""")
            .findAll(module).map { it.groupValues[1] }.toList()

        val lost = bound.filter { ids[it] == null }
        assertTrue(
            "у способности телефона не прочитано имя: $lost — разбор ослеп, и сторож считает не всё",
            lost.isEmpty(),
        )
        return bound.mapNotNull { ids[it] }.toSet()
    }

    /** Способности компьютера: и свои, и общие, взятые из того же места, что и в проводе. */
    private fun pcIds(): Set<String> {
        val ids = CapabilityIds.map(repo, SOURCES)
        val text = PC_SOURCES.joinToString("\n") { File(repo, it).readText() }
        return Regex("""(\w+Capability\w*)\s*\(""").findAll(text)
            .mapNotNull { ids[it.groupValues[1]] }.toSet()
    }

    private fun measured(): List<String> = (phoneIds() - pcIds()).sorted()

    private fun written(): List<String> = listed.readLines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .sorted()

    @Test
    fun `у каждого телефонного умения без пары на компьютере записана причина`() {
        assertTrue("список причин пропал: $listed", listed.isFile)

        val new = measured() - written().toSet()

        assertEquals(
            "у этих умений телефона нет пары на компьютере и не записано почему — " +
                "сделайте их на компьютере или впишите причину в $LIST: $new",
            emptyList<String>(),
            new,
        )
    }

    @Test
    fun `список причин не держит того, чего уже нет`() {
        val stale = written() - measured().toSet()

        assertEquals(
            "эти умения уже есть на компьютере или исчезли — строки из $LIST пора убрать: $stale",
            emptyList<String>(),
            stale,
        )
    }

    @Test
    fun `сторож видит обе стороны, а не пустоту`() {
        assertTrue("не прочитано ни одной способности телефона", phoneIds().size > 20)
        assertTrue("не прочитано ни одной способности компьютера", pcIds().size > 10)
    }

    private companion object {

        const val LIST = "tools/phone-only-actions.txt"

        const val PHONE_MODULE = "executors/src/main/kotlin/com/point/executors/di/CapabilityModule.kt"

        val SOURCES = listOf("core/flow/src/main", "executors/src/main", "desktop/src/main")

        /** Где компьютер собирает свой набор: объявление умений и провод (#1379). */
        val PC_SOURCES = listOf(
            "desktop/src/main/kotlin/com/point/desktop/PhoneFacing.kt",
            "desktop/src/main/kotlin/com/point/desktop/Main.kt",
            "core/flow/src/main/kotlin/com/point/core/flow/capabilities/SharedDictionary.kt",
        )
    }
}
