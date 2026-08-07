package com.point.data

import com.point.core.flow.LinkedPc
import com.point.core.flow.PcLinks
import com.point.data.di.UsageDir
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
class FilePcLinks @Inject constructor(
    @UsageDir private val baseDir: File,
) : PcLinks {

    private val lock = Mutex()
    private val file: File get() = File(baseDir.apply { mkdirs() }, "pc-pairing.json")

    @Volatile
    private var cache: LinkedPc? = null

    @Volatile
    private var loaded = false

    override fun current(): LinkedPc? {
        if (!loaded) {
            cache = load()
            loaded = true
        }
        return cache
    }

    override suspend fun save(pc: LinkedPc): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            cache = pc
            loaded = true
            runCatching {
                file.writeText(
                    JSONObject()
                        .put("device_id", pc.deviceId)
                        .put("name", pc.name)
                        .put("key", pc.key)
                        .toString(),
                )
            }
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            cache = null
            loaded = true
            file.delete()
        }
    }

    private fun load(): LinkedPc? = runCatching {
        if (!file.exists()) return@runCatching null
        val o = JSONObject(file.readText())
        val id = o.optString("device_id").takeIf { it.isNotBlank() } ?: return@runCatching null
        LinkedPc(id, o.optString("name").ifBlank { "Компьютер" }, o.optString("key"))
    }.getOrNull()
}
