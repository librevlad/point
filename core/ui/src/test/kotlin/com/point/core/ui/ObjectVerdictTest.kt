package com.point.core.ui

import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.flow.META_SIZE
import com.point.core.flow.META_UNUSABLE_REASON
import com.point.core.flow.TYPE_PARCEL
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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

    @Test
    fun `a parcel screenshot is called a parcel, not an image`() {
        val o = obj(
            kind = ObjectKind.IMAGE,
            metadata = mapOf(META_SEMANTIC_TYPE to TYPE_PARCEL, "name" to "Screenshot_Nova Post.jpg"),
        )

        assertEquals("Посылка", objectVerdict(o).headline)

        assertEquals("Screenshot_Nova Post.jpg", objectVerdict(o).subline)
    }

    @Test
    fun `a capability-backed feature still wins over a document tag`() {

        val o = obj(features = setOf(Feature.IS_PURCHASE), metadata = mapOf(META_SEMANTIC_TYPE to TYPE_PARCEL))

        assertEquals("Покупка", objectVerdict(o).headline)
    }

    @Test
    fun `a tag this build does not know falls back to the kind`() {
        val o = obj(kind = ObjectKind.PDF, metadata = mapOf(META_SEMANTIC_TYPE to "cmr"))

        assertEquals(kindLabel(ObjectKind.PDF), objectVerdict(o).headline)
    }

    @Test
    fun `над знаком таблицы стоит «Таблица», а не «Документ»`() {

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
    fun `над записью стоит её длина — до всякого тапа`() {

        val o = obj(
            kind = ObjectKind.AUDIO,
            mime = "audio/ogg",
            metadata = mapOf("name" to "AUD-0001.ogg", META_SIZE to (180 * 3000).toString()),
        )

        assertEquals("примерно 3 мин", objectVerdict(o).measure)
    }

    @Test
    fun `сорок секунд и сорок минут различимы глазом, а не только тапом`() {
        fun measureOf(seconds: Int) = objectMeasure(
            obj(
                kind = ObjectKind.AUDIO,
                mime = "audio/ogg",
                metadata = mapOf(META_SIZE to (seconds * 3000).toString()),
            ),
        )

        assertEquals("примерно 40 сек", measureOf(40))
        assertEquals("примерно 40 мин", measureOf(40 * 60))
    }

    @Test
    fun `битрейт неизвестен — тогда честный вес, а не выдуманные минуты`() {
        val o = obj(
            kind = ObjectKind.AUDIO,
            mime = "audio/amr",
            metadata = mapOf("name" to "REC001.amr", META_SIZE to (5L * 1024 * 1024).toString()),
        )

        assertEquals("5 МБ", objectVerdict(o).measure)
    }

    @Test
    fun `веса не приехало — экран молчит, а не показывает «0 мин»`() {
        assertNull(objectMeasure(obj(kind = ObjectKind.AUDIO, mime = "audio/ogg")))
    }

    @Test
    fun `у снимка и документа меры нет — число под каждым объектом было бы шумом`() {
        val size = mapOf(META_SIZE to (5L * 1024 * 1024).toString())

        assertNull(objectMeasure(obj(kind = ObjectKind.IMAGE, metadata = size)))
        assertNull(objectMeasure(obj(kind = ObjectKind.PDF, metadata = size)))
    }

    // ---- #684/#685: годность видна на экране раньше первого тапа. ----

    @Test
    fun `негодный объект называет причину в подстроке — раньше имени файла`() {
        val o = obj(
            features = setOf(Feature.UNUSABLE),
            metadata = mapOf("name" to "note.txt", META_UNUSABLE_REASON to "Файл пустой — в нём нечего читать"),
        )

        assertEquals("Файл пустой — в нём нечего читать", objectVerdict(o).subline)
    }

    @Test
    fun `причина видна и сильнее модельного резюме`() {
        val o = obj(
            features = setOf(Feature.UNUSABLE, Feature.IS_RECIPE),
            metadata = mapOf(
                META_SEMANTIC_SUMMARY to "Борщ на говяжьем бульоне",
                META_UNUSABLE_REASON to "Файл не открылся — он повреждён или это не изображение",
            ),
        )

        assertEquals("Файл не открылся — он повреждён или это не изображение", objectVerdict(o).subline)
    }

    @Test
    fun `метка без выставленного состояния — не считово, экран её не подхватывает`() {
        val o = obj(metadata = mapOf("name" to "note.txt", META_UNUSABLE_REASON to "залежавшийся ключ"))

        assertEquals("note.txt", objectVerdict(o).subline)
    }

    @Test
    fun `понятый тип документа сильнее переименования по знаку`() {

        val o = obj(
            kind = ObjectKind.OFFICE,
            metadata = mapOf(META_SEMANTIC_TYPE to TYPE_PARCEL, "name" to "таблица.xlsx"),
            mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )

        assertEquals("Посылка", objectVerdict(o).headline)
    }
}
