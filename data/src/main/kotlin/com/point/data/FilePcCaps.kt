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

@Singleton
class FilePcCaps @Inject constructor(
    @UsageDir private val baseDir: File,
) : PcCapsStore {

    private val lock = Mutex()
    private val file: File get() = File(baseDir.apply { mkdirs() }, "pc-caps.txt")

    @Volatile
    private var cache: List<PcRemoteAction>? = null

    override fun all(): List<PcRemoteAction> = cache ?: load().also { cache = it }

    /**
     * Метка файла и есть «когда объявлялся»: чтение с диска её не молодит, потому что читаем
     * мы, а не телефон говорит (тот же урок, что на компьютере — #624).
     */
    override fun savedAt(): Long? = file.takeIf { it.exists() }?.lastModified()?.takeIf { it > 0 }

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
