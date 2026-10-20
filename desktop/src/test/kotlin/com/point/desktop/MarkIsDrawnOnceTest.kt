package com.point.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.point.core.ui.drawPointMark

/**
 * Знак Point рисуется в одном месте.
 *
 * Копий было три: иконка окна, мини-знак компактного окна и картинка для панели задач.
 * У мини-знака кольцо оказалось шире и толще остальных, хотя рядом стояла приписка «то же
 * кольцо, что у телефона», — расхождение никто не заметил, потому что заметить было негде.
 *
 * Сторож стоит на классе беды, а не на трёх известных местах: любая новая отрисовка кольца
 * своими цветами уронит тест.
 */
class MarkIsDrawnOnceTest {

    /**
     * Всё, что собирает окно: свои исходники и общий с телефоном каталог, где знак и лежит с тех
     * пор, как его рисует и главный экран телефона.
     */
    private val sources = listOf(File(repo, "desktop/src/main/kotlin"), File(repo, "core/ui/src/shared/kotlin"))
        .flatMap { root -> root.walkTopDown().filter { it.name.endsWith(".kt") }.toList() }

    /** Цвета кольца: по ним видно, что рисуют именно знак, а не что-то своё. */
    private val ringColours = listOf("0xFFEAF0FF", "0xFF9B7BFF", "0xFF00A6FF")

    @Test
    fun `цвета знака названы в одном файле`() {
        val holders = sources.filter { file ->
            val text = file.readText()
            ringColours.all { text.contains(it) }
        }.map { it.name }

        assertEquals(listOf("PointMark.kt"), holders)
    }

    @Test
    fun `доли кольца не переписываются на месте`() {
        val mark = File(sources.first { it.name == "PointMark.kt" }.path).readText()

        assertTrue("доли знака должны лежать в PointMark", mark.contains("RING_OUTER"))
        val others = sources.filter { it.name != "PointMark.kt" }
            .filter { it.readText().contains("Stroke(width = r * 0.") }
            .map { it.name }
        assertTrue("кольцо снова рисуют своими долями: $others", others.isEmpty())
    }

    /**
     * Знак внутри окна — тот же портал, что у иконки (замечание владельца 20.10.2026: «логотип
     * же только наш используй»).
     *
     * Макет окна принёс на место знака свой — тонкое кольцо с точкой, своими цветами, — и оба
     * сторожа выше его не видели: они ищут цвета и доли нашего кольца, а чужой знак не повторяет
     * ни того, ни другого. Поэтому здесь сравнивается нарисованное: знак окна и наш портал,
     * пиксель в пиксель.
     */
    @Test
    fun `знак в окне нарисован нашим порталом, а не своим`() {
        val glyph = render { com.point.core.ui.PointGlyph(size = 64.dp) }
        val mark = render { androidx.compose.foundation.Canvas(Modifier.size(64.dp)) { drawPointMark(field = false) } }

        // Две пустые картинки равны друг другу: сначала — что портал вообще нарисован.
        val painted = (0 until 64).sumOf { y -> (0 until 64).count { x -> mark.getRGB(x, y) ushr 24 > 0 } }
        assertTrue("портал не нарисовался — сравнивать нечего: $painted точек", painted > 300)

        val differ = (0 until 64).sumOf { y -> (0 until 64).count { x -> glyph.getRGB(x, y) != mark.getRGB(x, y) } }
        assertEquals("знак в окне нарисован не нашим порталом — отличаются точки: $differ", 0, differ)
    }

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit): java.awt.image.BufferedImage {
        val scene = androidx.compose.ui.ImageComposeScene(64, 64, androidx.compose.ui.unit.Density(1f)) { content() }
        return try {
            scene.render().toComposeImageBitmap().toAwtImage()
        } finally {
            scene.close()
        }
    }
}
