package com.point

import com.point.core.flow.PdfRasterizer
import com.point.core.flow.READER_NO_PAGES
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewSourceTest {

    private fun obj(kind: ObjectKind) =
        PointObject("id", "x", ScratchRef("/scratch/object.bin"), ObjectState(kind))

    private fun rasterizer(first: String?, fails: String? = null) = object : PdfRasterizer {
        override suspend fun rasterize(obj: PointObject) = ScratchRef("/pages")
        override suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef? {
            if (fails != null) error(fails)
            return first?.let { ScratchRef(it) }
        }
    }

    @Test
    fun `an image previews its own file`() = runTest {
        assertEquals("/scratch/object.bin", previewSource(obj(ObjectKind.IMAGE), rasterizer(null)))
    }

    @Test
    fun `a pdf previews its rendered first page`() = runTest {
        assertEquals("/scratch/page1.jpg", previewSource(obj(ObjectKind.PDF), rasterizer("/scratch/page1.jpg")))
    }

    @Test
    fun `нечего показать — это просто нет предпросмотра`() = runTest {
        assertNull(previewSource(obj(ObjectKind.PDF), rasterizer(null)))
    }

    /**
     * #570: причину, по которой страницы не вышло, глотать нельзя — из неё человеку
     * достаются слова «в документе нет ни одной страницы», а не общее «файл не открылся».
     */
    @Test
    fun `причина, по которой страницы не вышло, не тонет по дороге`() = runTest {
        val thrown = runCatching { previewSource(obj(ObjectKind.PDF), rasterizer(null, fails = READER_NO_PAGES)) }

        assertEquals(READER_NO_PAGES, thrown.exceptionOrNull()?.message)
    }

    @Test
    fun `non-visual kinds keep the icon`() = runTest {
        assertNull(previewSource(obj(ObjectKind.TEXT), rasterizer("/scratch/page1.jpg")))
        assertNull(previewSource(obj(ObjectKind.ZIP), rasterizer("/scratch/page1.jpg")))
    }
}
