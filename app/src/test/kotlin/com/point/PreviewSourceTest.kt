package com.point

import com.point.core.flow.PdfRasterizer
import com.point.core.flow.READER_NO_PAGES
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSourceTest {

    private fun obj(kind: ObjectKind, path: String = "/scratch/object.bin") =
        PointObject("id", "x", ScratchRef(path), ObjectState(kind))

    /** Снимок, который и правда лежит на диске: у предпросмотра он один — сам файл объекта. */
    private fun realImage() = java.io.File.createTempFile("point-shot", ".jpg")
        .apply { deleteOnExit(); writeBytes(ByteArray(8)) }

    private fun rasterizer(first: String?, fails: String? = null) = object : PdfRasterizer {
        override suspend fun rasterize(obj: PointObject) = ScratchRef("/pages")
        override suspend fun rasterizeFirstPage(obj: PointObject): ScratchRef? {
            if (fails != null) error(fails)
            return first?.let { ScratchRef(it) }
        }
    }

    @Test
    fun `an image previews its own file`() = runTest {
        val shot = realImage()

        assertEquals(shot.absolutePath, previewSource(obj(ObjectKind.IMAGE, shot.absolutePath), rasterizer(null)))
    }

    /**
     * Пропавший файл — про попытку, а не про негодный объект (#812, #1271).
     *
     * Живая беда: объект остался открытым, а его копия ушла вместе со scratch. `BitmapFactory`
     * на пропавшем пути молча отдаёт `null` — ровно то же, что на неразобранных байтах, — и
     * предпросмотр объявлял целый снимок повреждённым: «Файл не открылся — он повреждён или
     * это не изображение», а снять метку негодности в сеансе нечем. У PDF так не выходило
     * никогда: там о пропаже говорит открытие файла. Теперь и у снимка говорит.
     */
    @Test
    fun `исчезнувший снимок называет пропажу, а не молчит мёртвым путём`() = runTest {
        val gone = realImage().apply { delete() }

        val thrown = runCatching { previewSource(obj(ObjectKind.IMAGE, gone.absolutePath), rasterizer(null)) }

        assertTrue(
            "предпросмотр пошёл читать путь, которого нет: " + thrown.getOrNull(),
            thrown.exceptionOrNull() is java.io.FileNotFoundException,
        )
        assertFalse(
            "пропажу назвали словом ридера — снимок объявят повреждённым",
            com.point.core.flow.readerFailureIsFatal(thrown.exceptionOrNull()?.message),
        )
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
