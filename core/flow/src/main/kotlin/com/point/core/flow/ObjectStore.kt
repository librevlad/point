package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef

interface ObjectStore {

    /**
     * Как эта вещь называется у системы — до того, как её начали копировать (#640).
     *
     * Спрашивается имя, а не содержимое: человеку нужно видеть, что именно открывается,
     * ещё до первого прочитанного байта. Система имени не знает — `null`, и Point молчит,
     * а не выдумывает.
     */
    suspend fun nameOf(sourceUri: String): String? = null

    suspend fun ingest(sourceUri: String, mime: String): PointObject

    suspend fun ingestMultiple(sources: List<String>): PointObject

    suspend fun put(result: ResultObject): PointObject

    suspend fun children(
        collection: PointObject,
        limit: Int = COLLECTION_ITEMS_LIMIT,
    ): CollectionContent<PointObject>

    suspend fun readText(obj: PointObject, limit: Int): String

    suspend fun newScratchFile(extension: String): ScratchRef

    suspend fun clear()
}
