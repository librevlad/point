package com.point.data

import com.github.junrar.Archive
import com.point.core.flow.ArchiveExtractor
import com.point.core.flow.ObjectStore
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/**
 * Extracts archives into scratch. zip/tar/gz/bz2/xz (and combinations like
 * tar.gz) go through Apache Commons Compress stream detection; 7z is handled by
 * [SevenZFile] and rar by junrar — both are picked by magic bytes so the format
 * is detected from content, not the file name. Guards against zip-slip.
 */
class CommonsArchiveExtractor @Inject constructor(
    private val store: ObjectStore,
) : ArchiveExtractor {

    override suspend fun extract(obj: PointObject): ScratchRef = withContext(Dispatchers.IO) {
        val dir = File(store.newScratchFile("unpacked").value).apply { mkdirs() }
        val source = File(obj.uri.value)

        when (magic(source)) {
            Format.SEVEN_Z -> extractSevenZ(source, dir)
            Format.RAR -> extractRar(source, dir)
            Format.OTHER -> extractStream(source, dir)
        }
        ScratchRef(dir.absolutePath)
    }

    private fun extractStream(source: File, dir: File): Int {
        var input: InputStream = BufferedInputStream(source.inputStream())
        val compressed = runCatching { CompressorStreamFactory.detect(input) }.isSuccess
        if (compressed) {
            input = BufferedInputStream(CompressorStreamFactory().createCompressorInputStream(input))
        }
        val isArchive = runCatching { ArchiveStreamFactory.detect(input) }.isSuccess

        return when {
            isArchive -> {
                var count = 0
                val archive: ArchiveInputStream<*> = ArchiveStreamFactory().createArchiveInputStream(input)
                archive.use { stream ->
                    var entry: ArchiveEntry? = stream.nextEntry
                    while (entry != null) {
                        val current = entry
                        if (!current.isDirectory) {
                            val target = safeChild(dir, current.name)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { stream.copyTo(it) }
                            count++
                        }
                        entry = stream.nextEntry
                    }
                }
                count
            }

            compressed -> {
                val name = source.name
                    .removeSuffix(".gz").removeSuffix(".bz2").removeSuffix(".xz")
                    .ifBlank { "content" }
                File(dir, name).outputStream().use { input.copyTo(it) }
                1
            }

            else -> 0
        }
    }

    private fun extractSevenZ(source: File, dir: File): Int {
        var count = 0
        SevenZFile.builder().setFile(source).get().use { sevenZ ->
            var entry = sevenZ.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val target = safeChild(dir, entry.name)
                    target.parentFile?.mkdirs()
                    val buffer = ByteArray(8192)
                    target.outputStream().use { out ->
                        var read = sevenZ.read(buffer)
                        while (read > 0) {
                            out.write(buffer, 0, read)
                            read = sevenZ.read(buffer)
                        }
                    }
                    count++
                }
                entry = sevenZ.nextEntry
            }
        }
        return count
    }

    private fun extractRar(source: File, dir: File): Int {
        var count = 0
        Archive(source).use { archive ->
            var header = archive.nextFileHeader()
            while (header != null) {
                if (!header.isDirectory) {
                    val target = safeChild(dir, header.fileName)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { archive.extractFile(header, it) }
                    count++
                }
                header = archive.nextFileHeader()
            }
        }
        return count
    }

    private fun magic(file: File): Format {
        val head = ByteArray(8)
        val n = file.inputStream().use { it.read(head) }
        return when {
            n >= 6 && head.startsWith(SEVEN_Z_SIG) -> Format.SEVEN_Z
            n >= 6 && head.startsWith(RAR_SIG) -> Format.RAR
            else -> Format.OTHER
        }
    }

    private fun ByteArray.startsWith(sig: ByteArray): Boolean {
        if (size < sig.size) return false
        for (i in sig.indices) if (this[i] != sig[i]) return false
        return true
    }

    /** Guards against zip-slip: strips `..` and absolute segments. */
    private fun safeChild(base: File, entryName: String): File {
        val clean = entryName.replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != ".." && it != "." }
            .joinToString("/")
        return File(base, clean)
    }

    private enum class Format { SEVEN_Z, RAR, OTHER }

    private companion object {
        // 7z: 37 7A BC AF 27 1C
        val SEVEN_Z_SIG = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
        // rar: 52 61 72 21 1A 07  ("Rar!\x1a\x07")
        val RAR_SIG = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)
    }
}
