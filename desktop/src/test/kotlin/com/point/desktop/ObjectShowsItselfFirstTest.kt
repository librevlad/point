package com.point.desktop

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Объект на компьютере сначала называет себя, а потом показывает содержимое (#898).
 *
 * Текстовый объект открывался стеной сырого текста в четырнадцать строк: вид, знание и
 * действия оставались за нижним краем окна, и человек видел не объект Point, а кусок файла. На
 * телефоне порядок другой: портал и вид → знание → текст → действия.
 */
class ObjectShowsItselfFirstTest {

    private val scene = File("src/main/kotlin/com/point/desktop/ui/ObjectScene.kt").readText()

    private val screen = File("src/main/kotlin/com/point/desktop/ui/ObjectPane.kt").readText()

    @Test
    fun `наверху стоит портал, а не текст`() {
        val portal = scene.substringAfter("internal fun PortalPreview(").substringBefore("\n}")

        assertTrue("текст снова наверху", !portal.contains("ObjectKind.TEXT"))
        assertTrue("снимок должен остаться наверху — он сам себе опознание",
            portal.contains("ObjectKind.IMAGE"))
    }

    @Test
    fun `текст стоит после знания и до действий`() {
        val body = screen.substringAfter("internal fun CompactObject(").substringBefore("private fun ListRow")

        val knowledge = body.indexOf("Knowledge(")
        val preview = body.indexOf("            Preview(item)")
        val actions = body.indexOf("state.actionsFor(item)")

        assertTrue("текста нет вовсе", preview > 0)
        assertTrue("текст стоит выше знания", knowledge < preview)
        assertTrue("текст стоит ниже действий", preview < actions)
    }

    @Test
    fun `свёрнутое превью не занимает весь экран окна`() {
        val body = scene.substringAfter("internal fun Preview(").substringBefore("internal fun Knowledge(")

        // Раскрытый текст занимает столько, сколько нужно, — его раскрыл человек (#1086).
        // Держать нужно свёрнутый: он приходит без спроса.
        assertTrue("свёрнутый текст больше ничем не ограничен", body.contains("else PREVIEW_LINES"))

        val limit = Regex("""PREVIEW_LINES = (\d+)""").find(scene)?.groupValues?.get(1)?.toInt() ?: 0

        assertTrue("превью снова во весь экран: $limit", limit in 1..8)
    }
}
