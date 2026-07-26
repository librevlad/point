package com.point.data

import com.point.core.flow.ChosenApp
import com.point.core.flow.ChosenApps
import com.point.core.model.ObjectKind
import com.point.data.di.UsageDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JSON file store for remembered app picks (#66 slice 4). The whole list is tiny
 * (≤ [MAX_PER_KIND] per kind), so it loads once into a warm snapshot — [all] is the
 * sync, I/O-free read the capability synthesis needs at process start. Newest pick
 * first; re-picking bubbles the app up; the per-kind cap keeps one-off choices from
 * flooding the graph.
 */
@Singleton
class FileChosenApps @Inject constructor(
    @UsageDir private val baseDir: File,
) : ChosenApps {

    private val lock = Mutex()
    private val file: File get() = File(baseDir.apply { mkdirs() }, "chosen-apps.json")

    @Volatile
    private var cache: List<ChosenApp>? = null

    override fun all(): List<ChosenApp> = cache ?: load().also { cache = it }

    override suspend fun record(app: ChosenApp): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val next = buildList {
                add(app)
                addAll(all().filterNot { it.kind == app.kind && it.packageName == app.packageName })
            }
                .groupBy { it.kind }
                .flatMap { (_, perKind) -> perKind.take(MAX_PER_KIND) }
            cache = next
            runCatching {
                val arr = JSONArray()
                next.forEach {
                    arr.put(
                        JSONObject()
                            .put("kind", it.kind.name)
                            .put("pkg", it.packageName)
                            .put("activity", it.activity)
                            .put("label", it.label),
                    )
                }
                file.writeText(arr.toString())
            }
        }
    }

    private fun load(): List<ChosenApp> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                ChosenApp(
                    kind = ObjectKind.valueOf(o.getString("kind")),
                    packageName = o.getString("pkg"),
                    activity = o.getString("activity"),
                    label = o.getString("label"),
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX_PER_KIND = 4
    }
}
