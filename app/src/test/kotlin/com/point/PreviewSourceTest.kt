package com.point

import com.point.core.flow.PdfRasterizer
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which file the header preview is decoded from (#114): an image shows itself,
 *  a PDF shows its first page, everything else keeps the kind icon. */
class PreviewSourceTest {

    private fun obj(kind: ObjectKind) =
        PointObject("id", "x", ScratchRef("/scratch/object.bin"), ObjectState(kind))

    private fun rasterizer(first: String?, fail: Boolean = false) = object : PdfRasterizer {
        override suspend fun rasterize(obj: PointObject) = ScratchRef("/pages")
        override suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef? {
            if (fail) error("broken pdf")
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
    fun `a broken or empty pdf yields no preview instead of failing`() = runTest {
        assertNull(previewSource(obj(ObjectKind.PDF), rasterizer(null)))
        assertNull(previewSource(obj(ObjectKind.PDF), rasterizer(null, fail = true)))
    }

    @Test
    fun `non-visual kinds keep the icon`() = runTest {
        assertNull(previewSource(obj(ObjectKind.TEXT), rasterizer("/scratch/page1.jpg")))
        assertNull(previewSource(obj(ObjectKind.ZIP), rasterizer("/scratch/page1.jpg")))
    }
}
