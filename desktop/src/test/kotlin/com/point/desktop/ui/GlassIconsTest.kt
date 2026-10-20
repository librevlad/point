package com.point.desktop.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.bubbleIcon
import com.point.desktop.repo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Одно действие на двух устройствах узнаётся по одному знаку (макет 19.10.2026).
 *
 * Компьютер рисует знаки действий контуром, телефон — заливкой, и таблица контуров живёт
 * здесь своей копией телефонной. Ключ, заведённый только на телефоне, здесь нарисовался бы
 * запасной молнией — молча, и человек искал бы знакомое действие по чужому знаку.
 */
class GlassIconsTest {

    /** Ключи знаков телефона — ветви его таблицы: иного перечня у них нет. */
    private val phoneKeys: List<String> =
        File(repo, "core/ui/src/shared/kotlin/com/point/core/ui/BubbleIcons.kt").readText()
            .substringAfter("fun bubbleIcon(")
            .substringBefore("fun bubbleColor(")
            .let { table -> Regex("""^\s*"([a-z0-9-]+)" ->""", RegexOption.MULTILINE).findAll(table) }
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun `у каждого знака телефона есть контур на компьютере`() {
        assertTrue("ключей телефона не нашлось — сторож смотрит в пустоту: $phoneKeys", phoneKeys.size > 20)

        val fallback = phoneKeys.filter { lineIcon(it) == Icons.Outlined.Bolt }
        assertTrue("эти знаки на компьютере рисуются запасной молнией: $fallback", fallback.isEmpty())
    }

    /** Контур — тот же знак Material, что заливка телефона, а не другой рисунок. */
    @Test
    fun `контур — тот же знак, что заливка телефона`() {
        val drawnHere = GlassIcons.run { setOf(Link) }
        val other = phoneKeys.filter { key ->
            val line = lineIcon(key)
            line !in drawnHere && line.name.substringAfter('.') != bubbleIcon(key).name.substringAfter('.')
        }
        assertTrue("на компьютере у этих действий другой знак, чем на телефоне: $other", other.isEmpty())
    }

    @Test
    fun `приложение на компьютере зовётся знаком «открыть в», как на телефоне`() {
        assertEquals(lineIcon("open-in"), lineIcon("app:com.example.notes"))
    }

    /** Знак вещи в кольце берётся у марки (#1233): таблица там таблица, а не «документ». */
    @Test
    fun `кольцо вещи различает таблицу и документ`() {
        fun office(name: String, mime: String) = PointObject(
            id = name,
            mime = mime,
            uri = ScratchRef("/tmp/$name"),
            state = ObjectState(ObjectKind.OFFICE),
            metadata = mapOf("name" to name),
        )

        val sheet = heroLine(office("смета.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        val letter = heroLine(office("письмо.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))

        assertEquals(GlassIcons.Table, sheet)
        assertNotEquals("таблица и документ нарисованы одним знаком", sheet, letter)
    }
}
