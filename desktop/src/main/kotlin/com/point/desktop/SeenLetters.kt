package com.point.desktop

import java.io.File

/**
 * Память о номерах уже принятых писем. Сервер доставляет «хотя бы раз», поэтому
 * недоподтверждённое письмо приходит снова — особенно после рестарта компьютера
 * (живой прогон 2026-08-09: «Встречи…» дублировались при каждом запуске).
 * Приём обязан быть идемпотентным: повтор письма не рождает второй объект.
 *
 * Живёт в ядре, потому что теперь память нужна обеим сторонам: телефон тоже начал принимать
 * чужие письма, и повторно принесённая просьба не должна сделать работу дважды (#817).
 */
class SeenLetters(private val file: File, private val keep: Int = 200) {

    private val ids: ArrayDeque<String> = ArrayDeque(
        runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList()),
    )

    /** true — письмо новое (и теперь запомнено); false — уже приносили. */
    @Synchronized
    fun firstTime(id: String): Boolean {
        if (knows(id)) return false
        remember(id)
        return true
    }

    /** Приносили ли это письмо. Письмо без номера — всегда новое: дедупу не за что зацепиться. */
    @Synchronized
    fun knows(id: String): Boolean = id.isNotBlank() && id in ids

    /**
     * Запомнить письмо принятым — **после** того, как объект принят (#1409).
     *
     * Пока номер записывался до приёма, сорвавшийся приём (смерть процесса, замена jar под
     * живым Point, ошибка диска) делал письмо навсегда «уже полученным»: сохранённая копия
     * (#680) на повторе отвергалась как дубль, телефону уходило «уже получено», объекта не
     * было нигде. Признак идемпотентности выставляется по совершённой работе, не по начатой.
     */
    @Synchronized
    fun remember(id: String) {
        if (id.isBlank() || id in ids) return
        ids.addLast(id)
        while (ids.size > keep) ids.removeFirst()
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(ids.joinToString("\n"))
        }
    }
}
