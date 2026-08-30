package com.point.executors

import com.point.core.flow.GraphState
import com.point.core.flow.META_COLLECTION_ORDER
import com.point.core.flow.ObjectStore
import com.point.core.flow.SOURCE_CAMERA
import com.point.core.flow.collectionOrderValue
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Путь человека из #1042: снял лист — Point предлагает снять ещё страницу, снятое собирается
 * набором, и из набора уже есть «Сканировать в PDF». Режим до съёмки не выбирается.
 */
class ShootMorePagesTest {

    @get:Rule val tmp = TemporaryFolder()

    private val registry = DefaultCapabilityRegistry(
        capabilities = setOf(ShootMoreCapability(), ScanPdfCapability(), ScanCapability()),
        policy = DefaultBubblePolicy(),
    )

    private fun offeredTo(obj: PointObject): Set<String> =
        registry.bubblesFor(GraphState(obj)).map { it.capabilityId.value }.toSet()

    @Test
    fun `после снятого листа Point предлагает снять ещё страницу`() {
        assertTrue(ShootMoreCapability.ID.value in offeredTo(page()))
    }

    @Test
    fun `снимок, на котором Point текста не прочитал, предложения не получает`() {
        val cat = image(features = emptySet())

        assertFalse(ShootMoreCapability.ID.value in offeredTo(cat))
    }

    @Test
    fun `на чужом наборе — распакованном архиве, страницах PDF — предложения нет`() {
        val unpacked = PointObject(
            "zip", "inode/directory", ScratchRef(tmp.newFolder().absolutePath),
            ObjectState(ObjectKind.COLLECTION), mapOf("op" to "archive"),
        )

        assertFalse(ShootMoreCapability.ID.value in offeredTo(unpacked))
    }

    @Test
    fun `по тапу Point просит следующий снимок камерой, а не картинку из галереи`() = runTest {
        val asked = ShootMoreRealizer(store()).perform(page(), null)

        assertTrue(asked.toString(), asked is ActionResult.NeedsImage)
        assertEquals(SOURCE_CAMERA, (asked as ActionResult.NeedsImage).from)
    }

    @Test
    fun `снятое собирается набором в порядке съёмки, и из набора есть Сканировать в PDF`() = runTest {
        val first = page(bytes = 1)

        val set = shot(first, second = 2)

        assertEquals(ObjectKind.COLLECTION, set.type)
        assertEquals(listOf<Byte>(1, 2), pageBytes(set))
        assertTrue(ScanPdfCapability.ID.value in offeredTo(objectOf(set)))
    }

    @Test
    fun `третья страница ложится в тот же набор, а не рождает второй`() = runTest {
        val set = shot(page(bytes = 1), second = 2)

        val grown = shot(objectOf(set), second = 3)

        assertEquals(set.uri, grown.uri)
        assertEquals(listOf<Byte>(1, 2, 3), pageBytes(grown))
    }

    @Test
    fun `предложение стоит и на собранной пачке — иначе третью страницу не снять`() = runTest {
        val set = shot(page(bytes = 1), second = 2)

        assertTrue(ShootMoreCapability.ID.value in offeredTo(objectOf(set)))
    }

    @Test
    fun `перестановка страниц человеком переживает новый снимок`() = runTest {
        val set = objectOf(shot(page(bytes = 1), second = 2))
        val reversed = pagesOf(set).map { it.name }.reversed()

        val grown = objectOf(
            shot(set.copy(metadata = set.metadata + (META_COLLECTION_ORDER to collectionOrderValue(reversed))), 3),
            order = reversed,
        )

        // Порядок, заданный человеком, остаётся первым, а снятое встаёт за ним (#1207).
        assertEquals(listOf<Byte>(2, 1, 3), pagesOf(grown).map { it.readBytes().first() })
    }

    /** Снимок листа: картинка, на которой Point уже прочитал текст. */
    private fun page(bytes: Byte = 1): PointObject = image(setOf(Feature.HAS_TEXT), bytes)

    private fun image(features: Set<Feature>, bytes: Byte = 1): PointObject {
        val file = tmp.newFile("shot-${System.nanoTime()}.jpg").apply { writeBytes(byteArrayOf(bytes)) }
        return PointObject(
            "shot", "image/jpeg", ScratchRef(file.absolutePath),
            ObjectState(ObjectKind.IMAGE, features), mapOf("name" to file.name),
        )
    }

    private suspend fun shot(input: PointObject, second: Byte): ResultObject {
        val next = tmp.newFile("next-${System.nanoTime()}.jpg").apply { writeBytes(byteArrayOf(second)) }
        val done = ShootMoreRealizer(store()).perform(input, next.absolutePath)

        assertTrue(done.toString(), done is ActionResult.Success)
        return (done as ActionResult.Success).result
    }

    private fun objectOf(result: ResultObject, order: List<String> = emptyList()): PointObject = PointObject(
        "set", result.mime, result.uri, ObjectState(ObjectKind.COLLECTION),
        result.metadata + order.takeIf { it.isNotEmpty() }
            ?.let { mapOf(META_COLLECTION_ORDER to collectionOrderValue(it)) }.orEmpty(),
    )

    /** Первый байт каждой страницы по порядку набора: чем снимали, тем и различаем страницы. */
    private fun pageBytes(result: ResultObject): List<Byte> =
        pagesOf(objectOf(result)).map { it.readBytes().first() }

    private fun store() = object : ObjectStore {

        override suspend fun ingest(sourceUri: String, mime: String): PointObject {
            val copy = File(newScratchFile("jpg").value).also { File(sourceUri).copyTo(it) }
            return PointObject(
                "ingested", mime, ScratchRef(copy.absolutePath),
                ObjectState(ObjectKind.IMAGE), mapOf("name" to copy.name),
            )
        }

        override suspend fun ingestMultiple(sources: List<String>) = error("не нужно")

        override suspend fun put(result: ResultObject, from: PointObject?, by: CapabilityId?) = error("не нужно")

        override suspend fun children(collection: PointObject, limit: Int) = error("не нужно")

        override suspend fun readText(obj: PointObject, limit: Int) = error("не нужно")

        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File(scratch, "s-${System.nanoTime()}.$extension").absolutePath)

        override suspend fun clear() = Unit
    }

    private val scratch: File by lazy { tmp.newFolder("scratch") }
}
