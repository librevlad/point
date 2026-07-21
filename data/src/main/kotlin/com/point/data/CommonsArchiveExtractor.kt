package com.point.data

import com.point.core.flow.ArchiveExtractor
import com.point.core.flow.ObjectStore
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/**
 * Extracts zip/tar/gz/bz2/xz (and combinations like tar.gz) via Apache Commons
 * Compress. It auto-detects a compression layer, then an archive layer; a single
 * compressed file (a plain .gz) is written out as one file. Guards zip-slip.
 */
class CommonsArchiveExtractor @Inject constructor(
    private val store: ObjectStore,
) : ArchiveExtractor {

    override suspend fun extract(obj: PointObject): Int = withContext(Dispatchers.IO) {
        val dir = File(store.newScratchFile("unpacked").value).apply { mkdirs() }
        val source = File(obj.uri.value)

        var input: InputStream = BufferedInputStream(source.inputStream())
        val compressed = runCatching { CompressorStreamFactory.detect(input) }.isSuccess
        if (compressed) {
            input = BufferedInputStream(CompressorStreamFactory().createCompressorInputStream(input))
        }
        val isArchive = runCatching { ArchiveStreamFactory.detect(input) }.isSuccess

        when {
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
                // A plain single compressed file (e.g. foo.txt.gz) — write it out.
                val name = source.name
                    .removeSuffix(".gz").removeSuffix(".bz2").removeSuffix(".xz")
                    .ifBlank { "content" }
                File(dir, name).outputStream().use { input.copyTo(it) }
                1
            }

            else -> 0
        }
    }

    /** Guards against zip-slip: strips `..` and absolute segments. */
    private fun safeChild(base: File, entryName: String): File {
        val clean = entryName.replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != ".." && it != "." }
            .joinToString("/")
        return File(base, clean)
    }
}
