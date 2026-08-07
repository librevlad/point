package com.point.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

object SendToRunning {

    fun handOff(files: List<File>, pointDir: File): Boolean {
        if (files.isEmpty()) return false

        val free = takeLock(pointDir)
        if (free != null) {
            runCatching { free.release() }
            return false
        }
        return runCatching {
            val drop = File(pointDir.apply { mkdirs() }, HANDOFF).apply { mkdirs() }
            val letter = File(drop, "${System.currentTimeMillis()}-${files.size}.paths")

            val partial = File(drop, letter.name + ".part")
            partial.writeText(files.joinToString("\n") { it.absolutePath }, Charsets.UTF_8)
            partial.renameTo(letter)
        }.getOrDefault(false)
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
}

fun filesFromArgs(args: Array<String>): List<File> =
    args.map(::File).filter { it.isFile }
