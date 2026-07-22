package com.point.data

import com.point.core.flow.FavoritesStore
import com.point.core.model.CapabilityId
import com.point.core.model.FavoriteChain
import com.point.data.di.FavoritesDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** File-based favorite chains (append-only JSONL). Injected dir → JVM-testable. */
class FileFavoritesStore @Inject constructor(
    @FavoritesDir private val baseDir: File,
) : FavoritesStore {

    private val dir: File get() = baseDir.apply { mkdirs() }
    private val index: File get() = File(dir, "favorites.jsonl")

    override suspend fun save(name: String, steps: List<CapabilityId>): FavoriteChain =
        withContext(Dispatchers.IO) {
            val chain = FavoriteChain(UUID.randomUUID().toString(), name, steps)
            index.appendText(row(chain).toString() + "\n")
            chain
        }

    override suspend fun all(): List<FavoriteChain> = withContext(Dispatchers.IO) { read() }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) {
            val kept = read().filter { it.id != id }
            index.writeText(kept.joinToString("") { row(it).toString() + "\n" })
        }
    }

    private fun read(): List<FavoriteChain> {
        if (!index.exists()) return emptyList()
        val map = LinkedHashMap<String, FavoriteChain>()
        index.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            runCatching {
                val json = JSONObject(line)
                val stepsArr = json.getJSONArray("steps")
                val steps = (0 until stepsArr.length()).map { CapabilityId(stepsArr.getString(it)) }
                map[json.getString("id")] = FavoriteChain(json.getString("id"), json.getString("name"), steps)
            }
        }
        return map.values.toList()
    }

    private fun row(chain: FavoriteChain): JSONObject = JSONObject()
        .put("id", chain.id)
        .put("name", chain.name)
        .put("steps", JSONArray(chain.steps.map { it.value }))
}
