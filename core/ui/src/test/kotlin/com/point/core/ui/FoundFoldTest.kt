package com.point.core.ui

import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_EMAIL
import com.point.core.flow.KIND_PHONE
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ValueRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Длинный документ не заливает экран однородными находками (#1015).
 *
 * На скриншоте `1080 × 7200` Point честно нашёл 119 почт — и выложил их подряд: до первого
 * действия пять экранов прокрутки. Однородное сворачивается, малое остаётся как было, и
 * ничего из найденного при этом не теряется.
 */
class FoundFoldTest {

    private fun node(kind: ObjectKind, value: String) = PointObject(
        id = "doc:${kind.name}:$value",
        mime = "text/plain",
        uri = ValueRef(value),
        state = ObjectState(kind),
        metadata = mapOf(com.point.core.flow.META_ENTITY_PREFIX + "value" to value),
    )

    private fun emails(count: Int) = (1..count).map { node(KIND_EMAIL, "tester$it@example.com") }

    @Test
    fun `сто девятнадцать почт занимают одну строку, а не сто девятнадцать`() {
        val groups = foldFound(emails(119))

        assertEquals(1, foundRowCount(groups))
        assertTrue("однородная стопка обязана быть свёрнутой", groups.single().folded)
    }

    @Test
    fun `свёртка называет всё, что в ней лежит`() {
        val group = foldFound(emails(119)).single()

        assertEquals(119, group.items.size)
        assertTrue("число в подписи — то же, что и в стопке", foundGroupLabel(group.kind, group.items.size).contains("119"))
        assertTrue("вид назван словом", foundGroupLabel(group.kind, group.items.size).contains(kindLabel(KIND_EMAIL)))
    }

    @Test
    fun `свёртка ничего не теряет и не переставляет`() {
        val found = emails(30) + node(KIND_PHONE, "+380 66 526 2706")

        val kept = foldFound(found).flatMap { it.items }

        assertEquals(found.map { it.id }.toSet(), kept.map { it.id }.toSet())
        assertEquals(found.size, kept.size)
    }

    @Test
    fun `три почты читаются как есть — сворачивать нечего`() {
        val groups = foldFound(emails(3))

        assertEquals(3, foundRowCount(groups))
        assertTrue("малая стопка остаётся списком", groups.none { it.folded })
    }

    @Test
    fun `сворачивается однородное, а редкое остаётся видимым значением`() {
        val found = emails(40) + node(KIND_PHONE, "+380 66 526 2706") + node(KIND_DATE, "12.08.2026")

        val groups = foldFound(found)

        assertTrue("почта свёрнута", groups.single { it.kind == KIND_EMAIL }.folded)
        assertTrue("телефон виден значением", groups.none { it.kind == KIND_PHONE && it.folded })
        assertTrue("дата видна значением", groups.none { it.kind == KIND_DATE && it.folded })
    }

    @Test
    fun `много разных видов тоже не заливают экран`() {

        // Свёртки по видам мало: три почты, три телефона и три даты — уже девять строк,
        // а видов у длинного документа бывает и больше.
        val found = (1..3).flatMap {
            listOf(
                node(KIND_EMAIL, "tester$it@example.com"),
                node(KIND_PHONE, "+38066526270$it"),
                node(KIND_DATE, "1$it.08.2026"),
                node(ObjectKind.URL, "example.com/$it"),
            )
        }

        assertTrue(
            "строк найденного между объектом и действиями не больше предела",
            foundRowCount(foldFound(found)) <= FOUND_ROWS_MAX,
        )
    }

    @Test
    fun `сколько бы ни нашлось, строк остаётся столько же`() {
        val many = emails(119) + (1..200).map { node(KIND_PHONE, "+38066526$it") }

        assertTrue(foundRowCount(foldFound(many)) <= FOUND_ROWS_MAX)
    }

    @Test
    fun `единичная находка свёрткой не притворяется`() {
        val found = emails(40) + node(KIND_PHONE, "+380 66 526 2706")

        assertTrue(foldFound(found).none { it.folded && it.items.size == 1 })
    }

    /**
     * Правило живёт на экране, а не только в тесте (#840): при перестройке экрана фильтр
     * найденного однажды уже отключили строкой `val visibleFound = found`, и тесты этого
     * не заметили — они звали функцию напрямую.
     */
    @Test
    fun `экран действительно сворачивает найденное`() {
        val screen = java.io.File("src/main/kotlin/com/point/core/ui/FirstScreen.kt").readText()

        assertTrue(
            "свёртка однородных находок снова отключена — экран выкладывает всё подряд",
            screen.contains("foldFound(found)"),
        )
    }
}
