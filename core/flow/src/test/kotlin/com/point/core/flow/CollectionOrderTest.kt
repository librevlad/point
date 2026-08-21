package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Порядок страниц набора — знание набора, а не имя файла (#1207).
 *
 * Несколько фото одной накладной приходят в порядке съёмки, а имена им даёт камера.
 * Человек переставляет страницы — и это знание самого набора: его читают и список на экране,
 * и «Сканировать в PDF», и «В Excel». Без знания порядок прежний — по имени.
 */
class CollectionOrderTest {

    private val shot = listOf("IMG_0003.jpg", "IMG_0001.jpg", "IMG_0002.jpg")

    @Test
    fun `без знания о порядке страницы идут по имени, как и раньше`() {
        assertEquals(
            listOf("IMG_0001.jpg", "IMG_0002.jpg", "IMG_0003.jpg"),
            inCollectionOrder(shot, emptyList()) { it },
        )
    }

    @Test
    fun `знание о порядке важнее имени`() {
        val order = listOf("IMG_0002.jpg", "IMG_0003.jpg", "IMG_0001.jpg")

        assertEquals(order, inCollectionOrder(shot, order) { it })
    }

    @Test
    fun `страница, о которой порядок не знает, идёт после известных — по имени`() {
        val order = listOf("IMG_0003.jpg")

        assertEquals(
            listOf("IMG_0003.jpg", "IMG_0001.jpg", "IMG_0002.jpg"),
            inCollectionOrder(shot, order) { it },
        )
    }

    @Test
    fun `порядок, которого в наборе уже нет, не роняет и не выдумывает страниц`() {
        val order = listOf("удалено.jpg", "IMG_0002.jpg")

        assertEquals(
            listOf("IMG_0002.jpg", "IMG_0001.jpg", "IMG_0003.jpg"),
            inCollectionOrder(shot, order) { it },
        )
    }

    @Test
    fun `порядок кладётся в метаданные набора и читается обратно тем же списком`() {
        val order = listOf("стр 2.jpg", "стр 1.jpg")

        val metadata = mapOf(META_COLLECTION_ORDER to collectionOrderValue(order))

        assertEquals(order, collectionOrder(metadata))
        assertEquals(emptyList<String>(), collectionOrder(emptyMap()))
    }

    @Test
    fun `содержимое набора перечисляется в порядке знания`() {
        val content = collectionContent(
            entries = shot.asSequence(),
            isFile = { true },
            name = { it },
            order = listOf("IMG_0002.jpg", "IMG_0001.jpg"),
        )

        assertEquals(listOf("IMG_0002.jpg", "IMG_0001.jpg", "IMG_0003.jpg"), content.shown)
        assertEquals(3, content.total)
    }

    @Test
    fun `новая перестановка заменяет прежнюю, а не уходит с ней в спор`() {
        val known = mapOf(META_COLLECTION_ORDER to collectionOrderValue(listOf("a.jpg", "b.jpg")))

        val merged = mergeKnowledge(
            known,
            mapOf(META_COLLECTION_ORDER to collectionOrderValue(listOf("b.jpg", "a.jpg"))),
            REFRESHABLE_KNOWLEDGE,
        )

        assertEquals(listOf("b.jpg", "a.jpg"), collectionOrder(merged))
        assertNull("порядок — не прочтение, спора по нему нет", merged[META_COLLECTION_ORDER + META_ALT_SUFFIX])
        assertFalse(merged.keys.any { it.startsWith(META_COLLECTION_ORDER) && it != META_COLLECTION_ORDER })
    }
}
