package com.point.data

import com.point.core.flow.Basket
import com.point.core.model.PointObject
import com.point.data.di.UsageDir
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The basket lives in the app's own `basket/` directory (#96) — never in scratch,
 * which dies with every flow. Files are prefixed with an insertion index so
 * [items] preserves the order the user built the pile in; the display name is
 * flattened to a safe basename so nothing can escape the directory.
 */
@Singleton
class FileBasket @Inject constructor(
    @UsageDir private val baseDir: File,
) : Basket {

    private val lock = Mutex()
    private val dir: File get() = File(baseDir, "basket").apply { mkdirs() }

    override suspend fun add(obj: PointObject): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val source = File(obj.uri.value)
            val display = (obj.metadata["name"] ?: source.name)
                .replace('/', '_').replace('\\', '_').trim().ifEmpty { "object" }
            val index = nextIndex()
            source.copyTo(File(dir, "$index-$display"), overwrite = false)
            index
        }
    }

    override suspend fun items(): List<String> = withContext(Dispatchers.IO) {
        lock.withLock { listOrdered().map(File::getAbsolutePath) }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        lock.withLock { dir.listFiles()?.forEach(File::delete) }
    }

    /** Files sorted by their numeric insertion prefix — the order the pile was built in. */
    private fun listOrdered(): List<File> =
        (dir.listFiles()?.filter(File::isFile) ?: emptyList())
            .sortedBy { it.name.substringBefore('-').toIntOrNull() ?: Int.MAX_VALUE }

    private fun nextIndex(): Int =
        (dir.listFiles()?.mapNotNull { it.name.substringBefore('-').toIntOrNull() }?.maxOrNull() ?: 0) + 1
}
