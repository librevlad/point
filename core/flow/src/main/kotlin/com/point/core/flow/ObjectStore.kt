package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef

interface ObjectStore {

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
