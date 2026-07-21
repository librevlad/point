package com.point.executors

import com.point.core.flow.Executor
import com.point.core.flow.ObjectStore
import com.point.core.model.ExecutorId
import com.point.core.model.ExecutorResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject

/** zip -> unpacked contents (into a scratch folder). Terminal for MVP. */
class ZipExecutor @Inject constructor(
    private val store: ObjectStore,
) : Executor {
    override val id = ExecutorId("zip")
    override val icon = "unzip"
    override fun title(state: ObjectState) = "Распаковать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.ZIP
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.UNKNOWN)

    override suspend fun execute(input: PointObject, amendment: String?): ExecutorResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(store.newScratchFile("unzipped").value).apply { mkdirs() }
                var count = 0
                ZipInputStream(File(input.uri.value).inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val target = safeChild(dir, entry.name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zis.copyTo(it) }
                            count++
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                ExecutorResult.Done("Распаковано файлов: $count → ${dir.name}")
            }.getOrElse { ExecutorResult.Failure(it.message ?: "Ошибка распаковки", recoverable = true) }
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
