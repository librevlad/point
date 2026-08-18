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

class MlKitEntityExtractor(
    private val region: com.point.core.flow.PhoneRegion = com.point.core.flow.DEFAULT_PHONE_REGION,
) : EntityExtractor {

    private val clients = HashMap<String, com.google.mlkit.nl.entityextraction.EntityExtractor>()

    /**
     * Пустой список — только честный итог отработавшего движка: «посмотрели, сущностей нет».
     *
     * Сбой модели или разметки уходит исключением в существующий Failure-путь исследования —
     * «не смогли посмотреть» не превращается в «ничего нет» (ADR-0001 §9).
     */
    override suspend fun extract(text: String): List<Entity> = withContext(Dispatchers.IO) {
        val lang = languageOf(text)
        val client = clients.getOrPut(lang) {
            EntityExtraction.getClient(EntityExtractorOptions.Builder(lang).build())
        }
        client.downloadModelIfNeeded().await()
        val params = EntityExtractionParams.Builder(text).build()
        val raw = client.annotate(params).await().flatMap { annotation: EntityAnnotation ->
            annotation.entities.mapNotNull { map(it, annotation.annotatedText) }
        }

        com.point.core.flow.plausibleEntities(raw, text, region.code())
    }

    private fun languageOf(text: String): String {
        val cyrillic = text.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return if (cyrillic > latin) EntityExtractorOptions.RUSSIAN else EntityExtractorOptions.ENGLISH
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
