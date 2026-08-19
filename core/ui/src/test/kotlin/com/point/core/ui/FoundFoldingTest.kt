package com.point.core.ui

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
 * Однородная сотня сворачивается, малый набор остаётся как есть (#1058, решение владельца).
 *
 * 119 узлов «Почта» печатались подряд, и действия начинались после шести прокруток. Знание
 * не урезается — меняется представление.
 */
class FoundFoldingTest {

    private fun node(id: String, kind: ObjectKind) =
        PointObject(id, "text/plain", ValueRef(id), ObjectState(kind))

    @Test fun `сто двадцать почт — одна строка-класс с числом`() {
        val mails = (1..119).map { node("m$it", KIND_EMAIL) }
        val scan = node("scan", ObjectKind.IMAGE)

        val rows = foldFound(mails + scan)

        val group = rows.filterIsInstance<FoundRow.Group>().single()
        assertEquals(KIND_EMAIL, group.kind)
        assertEquals(119, group.items.size)
        assertTrue("скан остался своей строкой", rows.any { it is FoundRow.Single && it.obj.id == "scan" })
        assertEquals("две строки вместо ста двадцати", 2, rows.size)
    }

    @Test fun `малый набор визитки остаётся развёрнутым`() {
        val card = listOf(node("p", KIND_PHONE), node("e", KIND_EMAIL), node("a", ObjectKind.IMAGE))

        val rows = foldFound(card)

        assertEquals(3, rows.size)
        assertTrue(rows.all { it is FoundRow.Single })
    }

    @Test fun `класс встаёт на место первого узла своего вида`() {
        val mixed = listOf(node("first", KIND_PHONE)) +
            (1..10).map { node("m$it", KIND_EMAIL) } +
            listOf(node("last", KIND_PHONE))

        val rows = foldFound(mixed)

        assertTrue(rows[0] is FoundRow.Single)
        assertTrue(rows[1] is FoundRow.Group)
        assertTrue("хвостовой телефон не потерян", rows.count { it is FoundRow.Single } == 2)
    }

    @Test fun `ровно порог — ещё не стена`() {
        val five = (1..FOUND_FOLD_THRESHOLD).map { node("m$it", KIND_EMAIL) }

        assertTrue(foldFound(five).all { it is FoundRow.Single })
    }
}
