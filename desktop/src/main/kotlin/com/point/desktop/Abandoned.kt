package com.point.desktop

import com.point.core.flow.COPY_LIFETIME_MS
import java.io.File

/**
 * Забыть брошенное на компьютере — при запуске и по одному сроку (#1317, решение владельца
 * 29.08.2026, вариант A).
 *
 * Папка `~/Point` своё забывала, а очередь на телефон — ничего и никогда: уйти из неё можно
 * было только забором с телефона, и вещи вместе с записями-исходами копились там невидимо для
 * обеих сторон. Срок тот же, что у копии объекта на телефоне (#1012): у человека одно
 * представление о том, сколько живёт брошенное, и разные числа здесь разъехались бы молча.
 *
 * Сюда же встала уборка временных файлов исполнителей ПК (#1435): у них тот же срок, и
 * проверяется всё одним запуском, а не тремя строками в `main()`.
 */
fun forgetAbandoned(inbox: Inbox, outbox: Outbox, now: Long) {
    val before = now - COPY_LIFETIME_MS
    runCatching { inbox.sweep(before) }
    runCatching { outbox.forgetOlderThan(before) }
    runCatching { forgetStaleTempFiles(File(System.getProperty("java.io.tmpdir")), before) }
}

/**
 * Временные файлы исполнителей ПК убираются при старте по тому же сроку (#1435, решение
 * владельца 04.09.2026, вариант A).
 *
 * `pc-*` (уменьшенные картинки, OCR, QR, речь), `point-*` (топдф, буфер, меню оболочки,
 * принятое) и `pages-*` (страницы PDF) создаются в системном temp и живут, пока идёт операция.
 * `deleteOnExit` рассчитан на процесс, который скоро закончится, а Point на компьютере живёт
 * днями — за это время таких файлов набегает на гигабайты (замер 04.09.2026: 1 ГБ за неделю).
 * При старте ничего не в работе, поэтому всё старше срока — брошенное, и его можно убрать.
 * Чужие файлы в temp не трогаем: только свои префиксы.
 */
internal fun forgetStaleTempFiles(tmpdir: File, before: Long) {
    tmpdir.listFiles()?.forEach { entry ->
        val ours = POINT_TEMP_PREFIXES.any { entry.name.startsWith(it) }
        if (ours && entry.lastModified() < before) {
            runCatching { entry.deleteRecursively() }
        }
    }
}

private val POINT_TEMP_PREFIXES = listOf("pc-", "point-", "pages-")
