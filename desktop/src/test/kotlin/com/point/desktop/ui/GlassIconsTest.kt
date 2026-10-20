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
import com.point.core.ui.GlassIcons
import com.point.core.ui.heroLine

/**
 * Одно действие на двух устройствах узнаётся по одному знаку (макет 19.10.2026).
 *
 * Компьютер рисовал знаки действий контуром и держал таблицу контуров своей копией телефонной:
 * ключ, заведённый только на телефоне, здесь рисовался запасной молнией. С 20.10.2026 телефон
 * тоже рисует контуром, и таблица одна (`bubbleIcon` в общем каталоге). Сторож держит то, что
 * от копии осталось бедой: ни один ключ таблицы не рисуется запасным знаком.
 */
class GlassIconsTest {

    /** Ключи знаков — ветви общей таблицы: иного перечня у них нет. */
    private val keys: List<String> =
        File(repo, "core/ui/src/shared/kotlin/com/point/core/ui/BubbleIcons.kt").readText()
            .substringAfter("fun bubbleIcon(")
            .substringBefore("fun bubbleColor(")
            .let { table -> Regex("""^\s*"([a-z0-9-]+)" ->""", RegexOption.MULTILINE).findAll(table) }
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun `ни один знак действия не рисуется запасной молнией`() {
        assertTrue("ключей не нашлось — сторож смотрит в пустоту: $keys", keys.size > 20)

        val fallback = keys.filter { bubbleIcon(it) == Icons.Outlined.Bolt }
        assertTrue("эти знаки рисуются запасной молнией: $fallback", fallback.isEmpty())
    }

    /** Копия таблицы контуров не завелась снова — ни на компьютере, ни рядом с ним. */
    @Test
    fun `таблица знаков одна`() {
        val copies = File(repo, "desktop/src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" && it.readText().contains("fun lineIcon(") }
            .map { it.name }
            .toList()

        assertTrue("знаки действий снова переписаны своей таблицей: $copies", copies.isEmpty())
    }

    @Test
    fun `приложение зовётся знаком «открыть в»`() {
        assertEquals(bubbleIcon("open-in"), bubbleIcon("app:com.example.notes"))
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
