package com.point.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

object SendToRunning {

    /**
     * true — Point уже живёт: файлы (если есть) отданы ему, оставлен сигнал «покажись»,
     * вызвавший обязан выйти. Вторая копия не живёт и при запуске без аргументов
     * (живой запуск 2026-08-09: у владельца оказалось две копии).
     */
    fun handOff(files: List<File>, pointDir: File): Boolean {
        val free = takeLock(pointDir)
        if (free != null) {
            runCatching { free.release() }
            return false
        }
        runCatching {
            val drop = File(pointDir.apply { mkdirs() }, HANDOFF).apply { mkdirs() }
            if (files.isNotEmpty()) {
                val letter = File(drop, "${System.currentTimeMillis()}-${files.size}.paths")
                val partial = File(drop, letter.name + ".part")
                partial.writeText(files.joinToString("\n") { it.absolutePath }, Charsets.UTF_8)
                partial.renameTo(letter)
            }
            File(drop, WAKE).writeText("")
        }
        return true
    }

    /** Одноразовый сигнал «покажись» от второй копии. */
    fun takeWake(pointDir: File): Boolean {
        val wake = File(File(pointDir, HANDOFF), WAKE)
        return wake.isFile && runCatching { wake.delete() }.getOrDefault(false)
    }

    fun takeLock(pointDir: File): FileLock? = runCatching {
        val file = File(pointDir.apply { mkdirs() }, LOCK)
        RandomAccessFile(file, "rw").channel.tryLock()
    }.getOrNull()

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
    private const val WAKE = "wake"
}

fun filesFromArgs(args: Array<String>): List<File> =
    args.map(::File).filter { it.isFile }
