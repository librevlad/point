package com.point.data

import com.point.core.flow.EnrichCost
import com.point.core.flow.Enricher
import com.point.core.flow.EnricherMeta
import com.point.core.flow.EnrichmentDelta
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject

class ZipImagesEnricher @Inject constructor() : Enricher {

    override val meta = EnricherMeta(cost = EnrichCost.FAST, mayYield = setOf(Feature.ZIP_OF_IMAGES))

    override fun appliesTo(state: ObjectState) = state.kind == ObjectKind.ZIP

    override suspend fun enrich(obj: PointObject): EnrichmentDelta = withContext(Dispatchers.IO) {
        var files = 0
        var images = 0
        runCatching {
            ZipInputStream(File(obj.uri.value).inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null && files < MAX_SCAN) {
                    if (!entry.isDirectory) {
                        files++
                        if (isImage(entry.name)) images++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        if (files > 0 && images == files) EnrichmentDelta(setOf(Feature.ZIP_OF_IMAGES)) else EnrichmentDelta()
    }

    private fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    private companion object {
        const val MAX_SCAN = 200
        val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    }
}
