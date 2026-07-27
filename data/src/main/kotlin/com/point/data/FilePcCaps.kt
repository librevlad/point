package com.point.data

import com.point.core.flow.PcCapsStore
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.decodePcCaps
import com.point.core.flow.encodePcCaps
import com.point.data.di.UsageDir
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The paired PC's advertised actions (#80), persisted with the same line codec the
 * wire uses — warm at process start so capability synthesis is sync and I/O-free.
 * Refreshed on pairing, dropped on unpair.
 */
@Singleton
class FilePcCaps @Inject constructor(
    @UsageDir private val baseDir: File,
) : PcCapsStore {

    private val lock = Mutex()
    private val file: File get() = File(baseDir.apply { mkdirs() }, "pc-caps.txt")

    @Volatile
    private var cache: List<PcRemoteAction>? = null

    override fun all(): List<PcRemoteAction> = cache ?: load().also { cache = it }

    override suspend fun save(caps: List<PcRemoteAction>): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            file.writeText(encodePcCaps(caps))
            cache = caps
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            file.delete()
            cache = emptyList()
        }
    }

    private fun load(): List<PcRemoteAction> =
        runCatching { decodePcCaps(file.readText()) }.getOrDefault(emptyList())
}
