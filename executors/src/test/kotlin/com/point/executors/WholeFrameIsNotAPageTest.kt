package com.point.executors

import com.point.core.flow.META_WHOLE_FRAME
import com.point.core.flow.wholeFrameNote
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кадр, на котором страницы не нашли, за выпрямленную страницу не выдаётся (#1333).
 *
 * Своё зрение при неудаче молча возвращало исходник (`detectDocument(...) ?: rgba`), и он
 * уезжал человеку как «скан»: снаружи это было неотличимо от успеха. Кадр решает не картинку,
 * а знание — с кривой страницы находки выходят кашей (#1017, #1007), — поэтому «не нашлось»
 * обязано доехать до человека и до следующего шага (#1041), а не погаснуть вместе со строкой
 * прогресса.
 */
class WholeFrameIsNotAPageTest {

    @Test
    fun `не нашедший страницу скан помечает кадр, а не выдаёт его за страницу`() = runTest {
        val scan = PageScanRealizer(SCAN_PLUS, "scan-plus", eyes(ScannedPage(READY, wholeFrame = true)))

        val result = scan.perform(photo(), null) as ActionResult.Success

        assertTrue(
            "кадр целиком помечен как выпрямленная страница: ${result.result.metadata}",
            !result.result.metadata[META_WHOLE_FRAME].isNullOrBlank(),
        )
    }

    @Test
    fun `выпрямленной странице помечать нечего`() = runTest {
        val scan = PageScanRealizer(SCAN_PLUS, "scan-plus", eyes(ScannedPage(READY, wholeFrame = false)))

        val result = scan.perform(photo(), null) as ActionResult.Success

        assertNull("страницу нашли, а сказано обратное", result.result.metadata[META_WHOLE_FRAME])
    }

    @Test
    fun `о кадре поодиночке и о кадре в наборе сказано одними словами`() = runTest {
        val scan = PageScanRealizer(SCAN_PLUS, "scan-plus", eyes(ScannedPage(READY, wholeFrame = true)))

        val result = scan.perform(photo(), null) as ActionResult.Success

        // «Скан» и «Скан в PDF» — один дефект и одно правило: слова о нём собирает одна
        // функция, иначе два пути назвали бы одно и то же по-разному и разошлись бы молча.
        assertEquals(wholeFrameNote(wholeFrames = 1, pages = 1), result.result.metadata[META_WHOLE_FRAME])
    }

    @Test
    fun `совсем не вышло — поправимый отказ, а не выданный за скан кадр`() = runTest {
        val scan = PageScanRealizer(SCAN_PLUS, "scan-plus", eyes(nothing = true))

        val result = scan.perform(photo(), null)

        assertTrue("обработать не вышло, а исход как у успеха: $result", result is ActionResult.Failure)
        assertTrue("отказ неисправимый — переснять уже не позовут", (result as ActionResult.Failure).recoverable)
    }

    private fun eyes(page: ScannedPage? = null, nothing: Boolean = false) = object : PageScan {
        override suspend fun scan(imagePath: String): ScannedPage? = if (nothing) null else page
    }

    private fun photo() = PointObject("id", "image/jpeg", ScratchRef(PHOTO), ObjectState(ObjectKind.IMAGE))

    private companion object {

        val SCAN_PLUS = ScanPlusCapability.ID

        const val PHOTO = "/tmp/photo.jpg"

        val READY = ScratchRef("/tmp/ready.jpg")
    }
}
