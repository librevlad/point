package com.point.executors

import com.point.core.flow.CodeKind
import com.point.core.flow.ObjectStore
import com.point.core.flow.QrReader
import com.point.core.flow.ScannedCode
import com.point.core.model.ActionResult
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
import org.junit.Test

/**
 * Штрихкод прочитан, цифры скопированы, про товар — ни слова (#445).
 *
 * Штрихкод на упаковке — единственная надпись, которая не врёт, но тринадцать цифр под
 * полосками руками не вобьёшь. Читатель кодов в Point стоял давно и брал только QR.
 *
 * Товарных справочников здесь нет намеренно (решение владельца 05.08.2026): бесплатные базы
 * неполны и стареют, а Point показывал бы чужую уверенность как своё наблюдение.
 */
class BarcodeIsReadAndCopiedTest {

    private val onPackage = "4820001234567"

    private class Eyes(private val found: ScannedCode?) : QrReader {
        override suspend fun decode(imagePath: String) = found?.text
        override suspend fun scan(imagePath: String) = found
    }

    private val scratch = object : ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(
            result: ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) =
            ScratchRef(File.createTempFile("code-", ".$extension").apply { deleteOnExit() }.absolutePath)

        override suspend fun clear() = Unit
    }

    private fun photo(vararg features: Feature) = PointObject(
        id = "pack",
        mime = "image/jpeg",
        uri = ScratchRef("/tmp/pack.jpg"),
        state = ObjectState(ObjectKind.IMAGE, features = features.toSet()),
    )

    private val capability = ReadQrCapability()

    @Test
    fun `у снимка со штрихкодом действие есть`() {
        assertTrue(capability.accepts(photo(Feature.HAS_BARCODE).state))
    }

    @Test
    fun `штрихкод не называется QR`() {
        val said = capability.label(photo(Feature.HAS_BARCODE).state)

        assertFalse("Point говорит про штрихкод «QR» — это неправда о том, что он увидел", "QR" in said)
    }

    @Test
    fun `на QR подпись остаётся прежней`() {
        assertTrue("QR" in capability.label(photo(Feature.HAS_QR).state))
    }

    @Test
    fun `цифры кода становятся объектом, который можно скопировать`() = runTest {
        val outcome = ReadQrRealizer(scratch, Eyes(ScannedCode(onPackage, CodeKind.PRODUCT)))
            .perform(photo(Feature.HAS_BARCODE))

        val success = outcome as ActionResult.Success
        assertEquals(ObjectKind.TEXT, success.result.type)
        assertEquals(onPackage, File(success.result.uri.value).readText())
    }

    @Test
    fun `код не прочитан — так и сказано, без догадок`() = runTest {
        val outcome = ReadQrRealizer(scratch, Eyes(null)).perform(photo(Feature.HAS_BARCODE))

        val failure = outcome as ActionResult.Failure
        assertTrue("отказ выдаёт QR за штрихкод: ${failure.reason}", "QR" !in failure.reason)
        assertTrue("повторить попытку человеку не дают", failure.recoverable)
    }

    @Test
    fun `о самом товаре не сказано ничего`() = runTest {
        val outcome = ReadQrRealizer(scratch, Eyes(ScannedCode(onPackage, CodeKind.PRODUCT)))
            .perform(photo(Feature.HAS_BARCODE)) as ActionResult.Success

        val given = File(outcome.result.uri.value).readText().trim()

        assertTrue("Point договаривает про товар то, чего не видел: $given", given == onPackage)
    }
}
