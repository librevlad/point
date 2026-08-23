package com.point.data

import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.Box
import com.point.core.flow.FrameTransform
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ReadingFramesTest {

    private fun image(metadata: Map<String, String> = emptyMap()) =
        PointObject("id", "image/jpeg", ScratchRef("/tmp/page.jpg"), ObjectState(ObjectKind.IMAGE), metadata)

    private fun sidecar(layer: AtomLayer): String =
        File.createTempFile("atoms", ".tsv").apply {
            deleteOnExit()
            writeText(AtomCodec.encode(layer))
        }.absolutePath

    private fun words(heightPx: Float, count: Int = 12) = (1..count).map {
        Atom("w$it", "слово", Box(0f, it * 100f, 60f, it * 100f + heightPx), 0.9f, "tesseract", "5.3", 0)
    }

    @Test
    fun `высота слова берётся из уже прочитанного слоя`() {
        val ref = sidecar(AtomLayer(words(heightPx = 24f)))

        assertEquals(24, knownTextHeightPx(image(mapOf(META_OCR_ATOMS_REF to ref))))
    }

    @Test
    fun `кадр, который никто не читал, плотности не сообщает`() {
        assertNull(knownTextHeightPx(image()))
    }

    @Test
    fun `пропавший или битый сайдкар не роняет подготовку кадра`() {
        assertNull(knownTextHeightPx(image(mapOf(META_OCR_ATOMS_REF to "/нет/такого.tsv"))))

        val broken = File.createTempFile("atoms", ".tsv").apply { deleteOnExit(); writeText("не дамп вовсе") }
        assertNull(knownTextHeightPx(image(mapOf(META_OCR_ATOMS_REF to broken.absolutePath))))
    }

    @Test
    fun `увеличение прошлого чтения доезжает через сайдкар`() {

        val ref = sidecar(
            AtomLayer(
                words(heightPx = 24f),
                transform = FrameTransform(sample = 1, uprightWidth = 3000, uprightHeight = 2250, upscale = 3),
            ),
        )

        assertEquals(24, knownTextHeightPx(image(mapOf(META_OCR_ATOMS_REF to ref))))
    }
}
