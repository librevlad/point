package com.point.core.ui

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Стрелки перестановки — у набора страниц, а не у любого списка файлов (#1207). */
class ReorderablePagesTest {

    private fun item(kind: ObjectKind, name: String) =
        PointObject(name, "x/y", ScratchRef("/$name"), ObjectState(kind), mapOf("name" to name))

    @Test
    fun `несколько снимков — страницы, их можно переставлять`() {
        assertTrue(reorderablePages(listOf(item(ObjectKind.IMAGE, "1.jpg"), item(ObjectKind.IMAGE, "2.jpg"))))
    }

    @Test
    fun `снимки среди прочих файлов всё равно страницы`() {
        assertTrue(
            reorderablePages(
                listOf(item(ObjectKind.IMAGE, "1.jpg"), item(ObjectKind.PDF, "a.pdf"), item(ObjectKind.IMAGE, "2.jpg")),
            ),
        )
    }

    @Test
    fun `один снимок или набор без снимков переставлять нечего`() {
        assertFalse(reorderablePages(listOf(item(ObjectKind.IMAGE, "1.jpg"))))
        assertFalse(reorderablePages(listOf(item(ObjectKind.TEXT, "a.txt"), item(ObjectKind.PDF, "b.pdf"))))
        assertFalse(reorderablePages(emptyList()))
    }
}
