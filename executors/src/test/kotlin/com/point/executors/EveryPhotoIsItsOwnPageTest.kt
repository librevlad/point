package com.point.executors

import com.point.core.flow.COLLECTION_ITEMS_LIMIT
import com.point.core.flow.META_COLLECTION_ORDER
import com.point.core.flow.collectionContent
import com.point.core.flow.collectionOrder
import com.point.core.flow.collectionOrderValue
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * «Сканировать в PDF» и «Объединить в PDF» не схлопывают набор (#1002, решение владельца
 * 21.08.2026 — «A, страница на фото»): каждому снимку набора — своя страница, и в PDF
 * уходит весь набор целиком.
 *
 * Что именно проверяется — состав страниц (`pagesOf`), то есть список, из которого
 * `imagesToPdf` делает страницы по одной на снимок. Само рисование — Android `PdfDocument`,
 * на JVM его не позвать.
 *
 * Порядок страниц уже охраняет `CollectionPagesOrderTest` (#1207), и первая проверка его
 * повторяет: новое в ней одно — список страниц PDF сходится со списком, который для экрана
 * строит тот же вызов `collectionContent`. Сам `ObjectStore.children` отсюда не позвать: он
 * живёт в `:data` и тянет `Context` с `MimeTypeMap`, а Robolectric в `:executors` нет.
 * Значит, расхождение экрана и PDF ловится, только если уедет `pagesOf`; уход самого
 * `children` — другой предел показа, фильтр по `PAGE_KINDS` — эта проверка не увидит.
 *
 * Новое здесь — вторая и третья проверки: предел показа `COLLECTION_ITEMS_LIMIT` и вложенную
 * папку набора на пути в PDF не охранял никто.
 */
class EveryPhotoIsItsOwnPageTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `страницы PDF идут тем же списком, что collectionContent строит для экрана`() {
        // Человек переставил страницы на экране — порядок стал знанием набора. Список на
        // экране строит `ObjectStore.children` этим же вызовом `collectionContent`, и PDF
        // обязан прочитать порядок из того же набора, иначе страницы лягут не так, как их
        // человеку показали (режим отказа GRF-021).
        //
        // Порядок человека нарочно расходится с порядком имён: сойдись они, проверка прошла
        // бы и на сортировке по имени, не доказав ничего.
        val collection = collection(
            "вторая.jpg", "первая.jpg", "третья.jpg",
            order = listOf("третья.jpg", "первая.jpg"),
        )

        val shown = collectionContent(
            entries = File(collection.uri.value).walkTopDown(),
            isFile = { it.isFile },
            name = { it.name },
            order = collectionOrder(collection.metadata),
        ).shown.map { it.name }

        assertEquals(listOf("третья.jpg", "первая.jpg", "вторая.jpg"), shown)
        assertEquals(shown, pagesOf(collection).map { it.name })
    }

    @Test
    fun `в PDF уходит весь набор, а не первый экран списка`() {
        // Экран набора показывает первые COLLECTION_ITEMS_LIMIT штук — это предел показа, а
        // не предел набора. Взять его же в PDF значило бы молча потерять хвост снимков.
        val all = (1..COLLECTION_ITEMS_LIMIT + 1).map { "снимок-%04d.jpg".format(it) }

        assertEquals(all, pagesOf(collection(*all.toTypedArray())).map { it.name })
    }

    @Test
    fun `вложенная папка набора — не страница, а её снимки — страницы`() {
        // Набор из распакованного архива приходит с папками внутри. Папка нарисоваться не
        // может, а каждый снимок из неё — такая же страница, как снимки рядом.
        val collection = collection("снимок.jpg")
        File(File(collection.uri.value), "папка")
            .apply { mkdirs() }
            .let { File(it, "внутри.jpg").writeBytes(BYTES) }

        assertEquals(listOf("внутри.jpg", "снимок.jpg"), pagesOf(collection).map { it.name })
    }

    private fun collection(vararg names: String, order: List<String> = emptyList()): PointObject {
        val dir = tmp.newFolder("набор")
        names.forEach { File(dir, it).writeBytes(BYTES) }
        return PointObject(
            id = "набор",
            mime = "inode/directory",
            uri = ScratchRef(dir.absolutePath),
            state = ObjectState(ObjectKind.COLLECTION),
            metadata = if (order.isEmpty()) {
                emptyMap()
            } else {
                mapOf(META_COLLECTION_ORDER to collectionOrderValue(order))
            },
        )
    }

    private companion object {

        /** Страницы здесь только считают и упорядочивают — байты нужны, чтобы файл был файлом. */
        val BYTES = byteArrayOf(1)
    }
}
