package com.point.desktop

import com.point.core.flow.Box
import com.point.core.flow.Focus
import com.point.core.flow.GraphState
import com.point.core.flow.withFocus
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** Место, обведённое мышью на снимке, — Focus и на компьютере (#1379). */
class AreaOnPcTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `место на поставленном кадре возвращается в пиксели файла тем же, каким было`() {
        val raw = Box(10f, 20f, 30f, 50f)
        for (turn in listOf(0, 90, 180, 270)) {
            val shown = rawToUpright(raw, turn, 400f, 300f)
            assertEquals("поворот $turn", raw, uprightToRaw(shown, turn, 400f, 300f))
        }
    }

    @Test fun `кадр на боку - верх поставленного кадра лежит в файле слева`() {
        // Файл 400×300, его поворачивают на 90° по часовой: на экране он 300×400. Полоса
        // по верху экрана — это левая полоса файла.
        assertEquals(Box(0f, 0f, 10f, 300f), uprightToRaw(Box(0f, 0f, 300f, 10f), 90, 400f, 300f))
    }

    @Test fun `действия над местом видны, только когда место показано`() {
        val registry = DesktopRegistry(desktopCapabilities())
        val photo = PointObject("p", "image/jpeg", ScratchRef("x.jpg"), ObjectState(ObjectKind.IMAGE))

        val bare = registry.bubblesFor(GraphState(photo)).map { it.capabilityId.value }
        val shown = photo.copy(metadata = withFocus(photo.metadata, Focus("p", region = Box(1f, 1f, 9f, 9f))))
        val focused = registry.bubblesFor(GraphState(shown, focus = Focus("p", region = Box(1f, 1f, 9f, 9f))))
            .map { it.capabilityId.value }

        assertFalse("«Взять фрагмент» без показанного места: $bare", "take-fragment" in bare)
        assertFalse("«Замазать» без показанного места: $bare", "hide-area" in bare)
        assertTrue("нет «Взять фрагмент» над местом: $focused", "take-fragment" in focused)
        assertTrue("нет «Замазать» над местом: $focused", "hide-area" in focused)
    }

    @Test fun `замазанное место больше не то, что было, а остальное нетронуто`() = runBlocking {
        val image = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 40) for (y in 0 until 20) image.setRGB(x, y, (x * 6 shl 16) or (y * 12 shl 8))
        val file = tmp.newFile("снимок.png").also { ImageIO.write(image, "png", it) }

        val hidden = PcImageRedactor().hide(file.absolutePath, listOf(Box(0f, 0f, 12f, 12f)))!!
        val out = ImageIO.read(hidden.bytes.inputStream())

        assertEquals(40, out.width)
        assertNotEquals(image.getRGB(1, 1), out.getRGB(1, 1))
        assertEquals(image.getRGB(30, 15), out.getRGB(30, 15))
        assertEquals("png", hidden.extension)
    }
}
