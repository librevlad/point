package com.point.data

import com.point.core.flow.PcPairing
import com.point.core.flow.PcPairings
import com.point.data.di.UsageDir
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The remembered PC — same tiny warm-cached JSON file pattern as [FileChosenApps]. */
@Singleton
class FilePcPairings @Inject constructor(
    @UsageDir private val baseDir: File,
) : PcPairings {

    private val lock = Mutex()
    private val file: File get() = File(baseDir.apply { mkdirs() }, "pc-pairing.json")

    @Volatile
    private var cache: PcPairing? = null

    @Volatile
    private var loaded = false

    override fun current(): PcPairing? {
        if (!loaded) {
            cache = load()
            loaded = true
        }
        return cache
    }

    override suspend fun save(pairing: PcPairing): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            cache = pairing
            loaded = true
            runCatching {
                file.writeText(
                    JSONObject()
                        .put("host", pairing.host)
                        .put("port", pairing.port)
                        .put("token", pairing.token)
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

    private fun load(): PcPairing? = runCatching {
        if (!file.exists()) return@runCatching null
        val o = JSONObject(file.readText())
        PcPairing(o.getString("host"), o.getInt("port"), o.getString("token"))
    }.getOrNull()
}
