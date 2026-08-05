package com.point.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/**
 * «Отправить в Point» из проводника (#252): файл, названный в командной строке, попадает в Point.
 *
 * Тонкость, ради которой это отдельный файл: Point на компьютере обычно **уже открыт**. Запускать
 * второй экземпляр на каждый пункт меню — значит плодить окна.
 *
 * Раньше живой экземпляр находили стуком по `127.0.0.1` в его же HTTP-сервер. Сервера больше нет
 * (#475), и передача идёт через файлы: работающий Point держит замок на `~/.point-pc/lock`, а
 * новый запуск, не сумевший его взять, кладёт пути в `~/.point-pc/handoff` и уходит. Замок вместо
 * стука — не обходной путь, а честный: слушающий сокет на Windows вызывает окно брандмауэра при
 * первом же запуске, и человек читает его как «Point лезет в сеть», хотя он не лез никуда.
 */
object SendToRunning {

    /**
     * Отдать файлы работающему Point.
     *
     * `true` — всё отдано и запускаться незачем. `false` — живого Point нет, работаем сами.
     */
    fun handOff(files: List<File>, pointDir: File): Boolean {
        if (files.isEmpty()) return false
        // Замок берётся и тут же отпускается: он нужен как ВОПРОС «есть ли живой», а не как право.
        val free = takeLock(pointDir)
        if (free != null) {
            runCatching { free.release() }
            return false
        }
        return runCatching {
            val drop = File(pointDir.apply { mkdirs() }, HANDOFF).apply { mkdirs() }
            val letter = File(drop, "${System.currentTimeMillis()}-${files.size}.paths")
            // Пишем во временное имя и переименовываем: живой Point читает каталог всё время, и
            // недописанный файл он прочитал бы как список из половины путей.
            val partial = File(drop, letter.name + ".part")
            partial.writeText(files.joinToString("\n") { it.absolutePath }, Charsets.UTF_8)
            partial.renameTo(letter)
        }.getOrDefault(false)
    }

    /**
     * Занять место живого экземпляра. `null` — место уже занято.
     *
     * Замок держится, пока жив процесс, и умирает вместе с ним: убитый Point не оставляет после
     * себя «занято навсегда», в отличие от файла-метки.
     */
    fun takeLock(pointDir: File): FileLock? = runCatching {
        val file = File(pointDir.apply { mkdirs() }, LOCK)
        RandomAccessFile(file, "rw").channel.tryLock()
    }.getOrNull()

    /** Что передали живому Point с прошлого раза. Прочитанное удаляется — иначе вернётся эхом. */
    fun collectHandOffs(pointDir: File): List<File> {
        val drop = File(pointDir, HANDOFF)
        val letters = drop.listFiles { f: File -> f.isFile && f.name.endsWith(".paths") }.orEmpty()
        return letters.sortedBy { it.name }.flatMap { letter ->
            val paths = runCatching { letter.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            runCatching { letter.delete() }
            paths.map(::File).filter { it.isFile }
        }
    }

    private const val LOCK = "lock"
    private const val HANDOFF = "handoff"
}

/**
 * Что делать с тем, что пришло в командной строке.
 *
 * Вынесено в чистую функцию, потому что решение здесь неочевидное и стоит теста: пустой запуск —
 * это обычное окно, а запуск с файлами — «отправить в Point», и второе окно человеку не нужно.
 */
fun filesFromArgs(args: Array<String>): List<File> =
    args.map(::File).filter { it.isFile }
