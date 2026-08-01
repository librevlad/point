package com.point.core.ui

import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.flow.TYPE_PARCEL
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The hero verdict: WHAT the object is, degrading from kind to semantic type as AI deepens (#114). */
class ObjectVerdictTest {

    private fun obj(
        kind: ObjectKind = ObjectKind.IMAGE,
        features: Set<Feature> = emptySet(),
        metadata: Map<String, String> = emptyMap(),
        mime: String = "mime",
    ) = PointObject("id", mime, ScratchRef("/x"), ObjectState(kind, features), metadata)

    @Test
    fun `falls back to the kind label when nothing is understood`() {
        val o = obj(kind = ObjectKind.IMAGE)
        assertEquals(kindLabel(ObjectKind.IMAGE), objectVerdict(o).headline)
        assertNull(objectVerdict(o).subline)
    }

    @Test
    fun `a recognised purchase leads with its human verdict`() {
        assertEquals("Покупка", objectVerdict(obj(features = setOf(Feature.IS_PURCHASE))).headline)
    }

    @Test
    fun `a vcard is a visiting card, not just an image`() {
        assertEquals("Визитка", objectVerdict(obj(features = setOf(Feature.HAS_VCARD))).headline)
    }

    @Test
    fun `the AI summary becomes the subline`() {
        val o = obj(
            features = setOf(Feature.IS_RECIPE),
            metadata = mapOf(META_SEMANTIC_SUMMARY to "Борщ на говяжьем бульоне"),
        )
        assertEquals("Рецепт", objectVerdict(o).headline)
        assertEquals("Борщ на говяжьем бульоне", objectVerdict(o).subline)
    }

    @Test
    fun `without a summary the filename fills the subline`() {
        val o = obj(metadata = mapOf("name" to "чек.jpg"))
        assertEquals(kindLabel(ObjectKind.IMAGE), objectVerdict(o).headline)
        assertEquals("чек.jpg", objectVerdict(o).subline)
    }

    // --- Тип документа (#222, шаг 5) ---

    @Test
    fun `a parcel screenshot is called a parcel, not an image`() {
        val o = obj(
            kind = ObjectKind.IMAGE,
            metadata = mapOf(META_SEMANTIC_TYPE to TYPE_PARCEL, "name" to "Screenshot_Nova Post.jpg"),
        )

        assertEquals("Посылка", objectVerdict(o).headline)
        // The file name stays as the subline — the headline says what it is, the subline which one.
        assertEquals("Screenshot_Nova Post.jpg", objectVerdict(o).subline)
    }

    @Test
    fun `a capability-backed feature still wins over a document tag`() {
        // IS_PURCHASE is backed by something that acts; a document type only renames. When both
        // are present the acting one names the object.
        val o = obj(features = setOf(Feature.IS_PURCHASE), metadata = mapOf(META_SEMANTIC_TYPE to TYPE_PARCEL))

        assertEquals("Покупка", objectVerdict(o).headline)
    }

    @Test
    fun `a tag this build does not know falls back to the kind`() {
        val o = obj(kind = ObjectKind.PDF, metadata = mapOf(META_SEMANTIC_TYPE to "cmr"))

        assertEquals(kindLabel(ObjectKind.PDF), objectVerdict(o).headline)
    }

    // --- Подпись героя не спорит со знаком (#295) ---

    @Test
    fun `над знаком таблицы стоит «Таблица», а не «Документ»`() {
        // Результат «В Excel»: знак уже говорит «таблица», подпись обязана говорить то же.
        val o = obj(
            kind = ObjectKind.OFFICE,
            metadata = mapOf("name" to "таблица.xlsx"),
            mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )

        assertEquals("Таблица", objectVerdict(o).headline)
        assertEquals("таблица.xlsx", objectVerdict(o).subline)
    }

    @Test
    fun `docx остаётся «Документом» — переименован только тот, у кого свой знак`() {
        val o = obj(
            kind = ObjectKind.OFFICE,
            metadata = mapOf("name" to "документ.docx"),
            mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )

        assertEquals(kindLabel(ObjectKind.OFFICE), objectVerdict(o).headline)
    }

    @Test
    fun `понятый тип документа сильнее переименования по знаку`() {
        // Порядок тот же, что был до #295 — семантика, добытая работой, важнее вида файла.
        val o = obj(
            kind = ObjectKind.OFFICE,
            metadata = mapOf(META_SEMANTIC_TYPE to TYPE_PARCEL, "name" to "таблица.xlsx"),
            mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )

        assertEquals("Посылка", objectVerdict(o).headline)
    }
}
