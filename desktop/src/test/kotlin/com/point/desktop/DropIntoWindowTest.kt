package com.point.desktop

import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Жест мышью на компьютере (#546): что Point берёт из брошенного, что говорит
 * человеку и почему окно у трея не исчезает из-под руки.
 *
 * Само перетаскивание проверяется живой рукой на компьютере — здесь проверено
 * решение, которое этот жест принимает.
 */
class DropIntoWindowTest {

    private class Brought(
        private val data: Map<DataFlavor, Any?>,
        private val broken: Set<DataFlavor> = emptySet(),
    ) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = data.keys.toTypedArray()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in data

        override fun getTransferData(flavor: DataFlavor): Any? {
            if (flavor in broken) throw IOException("канал перетаскивания оборвался")
            return data[flavor]
        }
    }

    private fun files(vararg names: String) = names.map { File(it) }

    private class Heard {
        val files = mutableListOf<List<File>>()
        val texts = mutableListOf<String>()
        val pictures = mutableListOf<BufferedImage>()
        val said = mutableListOf<String>()

        fun take(brought: Dropped): Boolean = takeDropped(
            brought,
            files = { files += it },
            text = { texts += it },
            picture = { pictures += it },
            say = { said += it },
        )
    }

    @Test
    fun `брошенные файлы берутся все — принесённое не пропадает`() {
        val brought = readDropped(
            Brought(mapOf(DataFlavor.javaFileListFlavor to files("счёт.pdf", "фото.jpg", "текст.txt"))),
        )

        val heard = Heard()
        assertTrue("перетаскивание обязано считаться принятым", heard.take(brought))
        assertEquals(3, heard.files.single().size)
    }

    @Test
    fun `пачка названа словами и остаётся списком, а не открывается за человека`() {
        val brought = readDropped(
            Brought(mapOf(DataFlavor.javaFileListFlavor to files("а.pdf", "б.pdf", "в.pdf"))),
        )

        assertTrue("пачка обязана лечь списком", droppedAsBatch(brought))
        val heard = Heard()
        heard.take(brought)

        assertEquals("Взял 3 файла — они в списке", heard.said.single())
    }

    @Test
    fun `один файл открывается сам и отдельных слов не требует`() {
        val brought = readDropped(Brought(mapOf(DataFlavor.javaFileListFlavor to files("счёт.pdf"))))

        assertFalse(droppedAsBatch(brought))
        val heard = Heard()
        heard.take(brought)

        assertTrue("про один файл сказано лишнее - ${heard.said}", heard.said.isEmpty())
        assertNull(filesTakenMessage(1))
    }

    @Test
    fun `число файлов согласовано со словом`() {
        assertTrue(filesTakenMessage(2)!!.contains("2 файла"))
        assertTrue(filesTakenMessage(5)!!.contains("5 файлов"))
        assertTrue(filesTakenMessage(11)!!.contains("11 файлов"))
        assertTrue(filesTakenMessage(21)!!.contains("21 файл "))
    }

    @Test
    fun `брошенный текст становится текстом`() {
        val brought = readDropped(Brought(mapOf(DataFlavor.stringFlavor to "Оплатите счёт 4411")))

        val heard = Heard()
        assertTrue(heard.take(brought))
        assertEquals("Оплатите счёт 4411", heard.texts.single())
    }

    @Test
    fun `картинка со страницы берётся пикселями, а не адресом ссылки`() {
        val picture = BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB)
        val brought = readDropped(
            Brought(
                mapOf(
                    DataFlavor.imageFlavor to picture,
                    DataFlavor.stringFlavor to "https://example.org/фото.jpg",
                ),
            ),
        )

        val heard = Heard()
        assertTrue("картинка обязана стать объектом, а не молчанием", heard.take(brought))
        assertTrue("человек принёс изображение, а не адрес", heard.texts.isEmpty())
        assertEquals(8, heard.pictures.single().width)
    }

    @Test
    fun `неготовые пиксели доводятся до снимка, который ляжет на диск`() {
        val scaled: Image = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
            .getScaledInstance(20, 10, Image.SCALE_FAST)

        val ready = asBufferedImage(scaled)

        assertEquals(20, ready?.width)
        assertEquals(10, ready?.height)
    }

    @Test
    fun `неподдерживаемое названо словами, а не молчанием`() {
        val html = DataFlavor("text/html; class=java.lang.String", "HTML")
        val brought = readDropped(Brought(mapOf(html to "<b>кусок страницы</b>")))

        val heard = Heard()
        assertFalse("чужое не притворяется принятым", heard.take(brought))
        val why = heard.said.single()

        assertTrue("отказ ничего не советует - «$why»", why.contains("файлом"))
        assertTrue("человеку показали внутренности системы - «$why»", "text/html" !in why)
    }

    @Test
    fun `оборванное чтение — отказ словами, а не тишина`() {
        val brought = readDropped(
            Brought(
                data = mapOf(DataFlavor.javaFileListFlavor to files("счёт.pdf")),
                broken = setOf(DataFlavor.javaFileListFlavor),
            ),
        )

        val heard = Heard()
        assertFalse(heard.take(brought))
        assertEquals(DROP_UNREADABLE, heard.said.single())
    }

    @Test
    fun `пустое перетаскивание не притворяется объектом`() {
        val heard = Heard()
        heard.take(readDropped(Brought(mapOf(DataFlavor.javaFileListFlavor to emptyList<File>()))))
        heard.take(readDropped(Brought(mapOf(DataFlavor.stringFlavor to "   "))))

        assertTrue("пустое стало объектом", heard.files.isEmpty() && heard.texts.isEmpty())
        assertEquals(listOf(DROP_EMPTY, DROP_EMPTY), heard.said)
    }

    @Test
    fun `окно не прячется, пока над ним тянут файл`() {
        assertFalse(
            "флайаут исчез из-под руки — бросать стало некуда",
            flyoutHides(focused = false, dragging = true, keptOpen = false, asking = false),
        )
    }

    @Test
    fun `окно не прячется, пока человек попросил его остаться`() {
        assertFalse(
            "человек ушёл за файлом, а окно закрылось",
            flyoutHides(focused = false, dragging = false, keptOpen = true, asking = false),
        )
    }

    @Test
    fun `человек ушёл в другое окно — флайаут ушёл`() {
        assertTrue(flyoutHides(focused = false, dragging = false, keptOpen = false, asking = false))
        assertFalse(
            "окно с вопросом согласия закрывать нельзя",
            flyoutHides(focused = false, dragging = false, keptOpen = false, asking = true),
        )
        assertFalse(
            "окно в руках человека не прячется",
            flyoutHides(focused = true, dragging = false, keptOpen = false, asking = false),
        )
    }
}
