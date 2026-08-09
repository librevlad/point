package com.point.desktop

import java.io.File

/**
 * Память о номерах уже принятых писем. Сервер доставляет «хотя бы раз», поэтому
 * недоподтверждённое письмо приходит снова — особенно после рестарта компьютера
 * (живой прогон 2026-08-09: «Встречи…» дублировались при каждом запуске).
 * Приём обязан быть идемпотентным: повтор письма не рождает второй объект.
 */
class SeenLetters(private val file: File, private val keep: Int = 200) {

    private val ids: ArrayDeque<String> = ArrayDeque(
        runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList()),
    )

    /** true — письмо новое (и теперь запомнено); false — уже приносили. */
    @Synchronized
    fun firstTime(id: String): Boolean {
        if (id.isBlank()) return true
        if (id in ids) return false
        ids.addLast(id)
        while (ids.size > keep) ids.removeFirst()
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(ids.joinToString("\n"))
        }
        return true
    }
}
