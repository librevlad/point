package com.point.data

import com.google.mlkit.nl.entityextraction.EntityAnnotation
import com.google.mlkit.nl.entityextraction.EntityExtraction
import com.google.mlkit.nl.entityextraction.EntityExtractionParams
import com.google.mlkit.nl.entityextraction.EntityExtractorOptions
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.google.mlkit.nl.entityextraction.Entity as MlEntity

/**
 * On-device entity detection via ML Kit — phone/email/address/date/card/… without the cloud
 * (fits Point's no-surveillance stance). The model downloads on first use; any failure (no model,
 * no Play Services on the device) degrades to an empty list, so entity actions simply don't appear
 * rather than crashing. Constructed via @Provides in DataModule so Dagger's KSP aggregation never
 * has to resolve the ML Kit AAR types.
 */
class MlKitEntityExtractor : EntityExtractor {

    private val client = EntityExtraction.getClient(
        EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build(),
    )

    override suspend fun extract(text: String): List<Entity> = withContext(Dispatchers.IO) {
        runCatching {
            client.downloadModelIfNeeded().await()
            val params = EntityExtractionParams.Builder(text).build()
            client.annotate(params).await().flatMap { annotation: EntityAnnotation ->
                annotation.entities.mapNotNull { map(it, annotation.annotatedText) }
            }
        }.getOrDefault(emptyList())
    }

    private fun map(entity: MlEntity, matched: String): Entity? = when (entity.type) {
        MlEntity.TYPE_PHONE -> Entity(EntityType.PHONE, matched)
        MlEntity.TYPE_EMAIL -> Entity(EntityType.EMAIL, matched)
        MlEntity.TYPE_URL -> Entity(EntityType.URL, matched)
        MlEntity.TYPE_ADDRESS -> Entity(EntityType.ADDRESS, matched)
        MlEntity.TYPE_DATE_TIME -> Entity(EntityType.DATE_TIME, matched)
        MlEntity.TYPE_PAYMENT_CARD -> Entity(EntityType.PAYMENT_CARD, matched)
        MlEntity.TYPE_MONEY -> Entity(EntityType.MONEY, matched)
        else -> null
    }
}
