package com.point.data

import android.content.Context
import com.point.core.flow.CapabilityUsage
import com.point.core.model.CapabilityId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileCapabilityUsage @Inject constructor(
    @ApplicationContext private val context: Context,
) : CapabilityUsage {

    private val file: File
        get() = File(context.filesDir, "usage").apply { mkdirs() }.resolve("counts.properties")

    @Volatile
    private var snapshot: Map<CapabilityId, Int> = load()

    override fun counts(): Map<CapabilityId, Int> = snapshot

    override suspend fun record(id: CapabilityId): Unit = withContext(Dispatchers.IO) {
        val next = snapshot + (id to (snapshot[id] ?: 0) + 1)
        snapshot = next
        runCatching {
            val props = Properties()
            next.forEach { (k, v) -> props.setProperty(k.value, v.toString()) }
            file.outputStream().use { props.store(it, "point capability usage") }
        }
        Unit
    }

    private fun load(): Map<CapabilityId, Int> = runCatching {
        if (!file.exists()) return emptyMap()
        val props = Properties().apply { file.inputStream().use { load(it) } }
        props.stringPropertyNames()
            .associate { CapabilityId(it) to (props.getProperty(it)?.toIntOrNull() ?: 0) }
    }.getOrDefault(emptyMap())
}
