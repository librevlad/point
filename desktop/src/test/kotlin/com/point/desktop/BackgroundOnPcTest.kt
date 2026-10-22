package com.point.desktop

import com.point.core.flow.BlurBgRealizer
import com.point.core.flow.CutoutRealizer
import com.point.core.flow.ReplaceBgRealizer
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Фон на компьютере (#1379): модель исполняется по-настоящему — на снимке, где красный круг
 * лежит на светлом фоне.
 */
class BackgroundOnPcTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun openCv() = assumeTrue("OpenCV для этой машины не загрузилась", PcOpenCv.available)

    private val store by lazy { PcScratchStore(tmp.newFolder()) }
    private val remover by lazy { PcBackgroundRemover(store) }
    private val compositor by lazy { PcImageCompositor(store) }

    private fun picture(name: String, width: Int, height: Int, draw: java.awt.Graphics2D.() -> Unit): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { draw(); dispose() }
        return tmp.newFile(name).also { ImageIO.write(image, "png", it) }
    }

    private fun ballOnTable() = picture("мяч.png", 800, 600) {
        color = Color(225, 230, 235); fillRect(0, 0, 800, 600)
        color = Color(200, 30, 30); fillOval(260, 160, 280, 280)
    }

    private fun photo(file: File) = PointObject("p", "image/png", ScratchRef(file.absolutePath), ObjectState(ObjectKind.IMAGE))

    @Test fun `объект остаётся, фон становится прозрачным`() = runBlocking {
        val result = CutoutRealizer(remover).perform(photo(ballOnTable()), null)

        val cut = ImageIO.read(File((result as ActionResult.Success).result.uri.value))
        assertEquals("объект стал прозрачным", 255, cut.getRGB(400, 300) ushr 24)
        assertEquals("фон остался", 0, cut.getRGB(10, 10) ushr 24)
    }

    @Test fun `на ровной заливке объекта нет — и так и сказано, а не пустая картинка`() = runBlocking {
        val grey = picture("пусто.png", 400, 300) { color = Color(128, 128, 128); fillRect(0, 0, 400, 300) }

        val result = CutoutRealizer(remover).perform(photo(grey), null)

        assertTrue("$result", result is ActionResult.Failure)
    }

    @Test fun `размытый фон — снимок того же размера`() = runBlocking {
        val result = BlurBgRealizer(remover, compositor).perform(photo(ballOnTable()), null)

        val made = ImageIO.read(File((result as ActionResult.Success).result.uri.value))
        assertEquals(800, made.width)
        assertEquals(600, made.height)
    }

    @Test fun `новый фон выбирают окном файлов, и объект встаёт на него`() = runBlocking {
        val green = picture("фон.png", 300, 200) { color = Color(20, 120, 40); fillRect(0, 0, 300, 200) }
        var asked: String? = null
        val replace = PcPicksImage(ReplaceBgRealizer(store, remover, compositor)) { prompt -> asked = prompt; green.absolutePath }

        val result = replace.perform(photo(ballOnTable()), null)

        val made = ImageIO.read(File((result as ActionResult.Success).result.uri.value))
        assertTrue("окно файлов не открыли", asked != null)
        assertEquals("угол не зелёный", 0x147828, made.getRGB(10, 10) and 0xFFFFFF)
        assertTrue("объект пропал", (made.getRGB(400, 300) shr 16 and 0xFF) > 150)
        assertTrue("файл человека пропал", green.isFile)
    }

    @Test fun `фон не выбрали — шаг назван, а не повис`() = runBlocking {
        val replace = PcPicksImage(ReplaceBgRealizer(store, remover, compositor)) { null }

        val result = replace.perform(photo(ballOnTable()), null)

        assertTrue("$result", result is ActionResult.Failure)
    }

    @Test fun `без OpenCV действие недоступно и названо почему`() {
        val realizer = PcNeedsOpenCv(CutoutRealizer(remover)) { false }

        assertTrue(!realizer.isAvailable())
        assertTrue(realizer.unavailableReason() != null)
    }
}
