package com.point.executors

import com.point.core.flow.META_COLLECTION_ORDER
import com.point.core.flow.collectionOrderValue
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * «Сканировать в PDF» и «Объединить в PDF» кладут страницы в порядке набора (#1207): тем
 * самым `pagesOf`, из которого `imagesToPdf` берёт файлы. Порядок — знание объекта-набора;
 * без него страницы идут по имени, как и раньше.
 */
class CollectionPagesOrderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun set(order: List<String>? = null): PointObject {
        val dir = tmp.newFolder("набор")
        listOf("IMG_3.jpg", "IMG_1.jpg", "IMG_2.jpg").forEach { java.io.File(dir, it).writeBytes(byteArrayOf(1)) }
        return PointObject(
            "set", "inode/directory", ScratchRef(dir.absolutePath), ObjectState(ObjectKind.COLLECTION),
            metadata = order?.let { mapOf(META_COLLECTION_ORDER to collectionOrderValue(it)) }.orEmpty(),
        )
    }

    @Test
    fun `без знания о порядке страницы идут по имени`() {
        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg", "IMG_3.jpg"), pagesOf(set()).map { it.name })
    }

    @Test
    fun `порядок набора важнее имени файла`() {
        assertEquals(
            listOf("IMG_2.jpg", "IMG_1.jpg", "IMG_3.jpg"),
            pagesOf(set(order = listOf("IMG_2.jpg", "IMG_1.jpg"))).map { it.name },
        )
    }

    @Test
    fun `действия над набором читают порядок из самого набора`() {
        // Один и тот же объект идёт и в «Сканировать в PDF», и в «Объединить в PDF»: порядок
        // страниц не зависит от того, какое из действий его читает.
        val reordered = set(order = listOf("IMG_3.jpg", "IMG_2.jpg", "IMG_1.jpg"))

        assertEquals(listOf("IMG_3.jpg", "IMG_2.jpg", "IMG_1.jpg"), pagesOf(reordered).map { it.name })
        assertEquals(pagesOf(reordered), pagesOf(reordered.copy(id = "та же папка, другой кадр")))
    }
}
