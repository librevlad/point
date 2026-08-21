package com.point.core.ui

import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стрелки перестановки — у набора страниц, а не у любого списка файлов (#1207). Страница —
 * то, что читается в таблицу и поодиночке: снимок, PDF, текст.
 */
class ReorderablePagesTest {

    private fun item(kind: ObjectKind, name: String) =
        PointObject(name, "x/y", ScratchRef("/$name"), ObjectState(kind), mapOf("name" to name))

    @Test
    fun `несколько снимков — страницы, их можно переставлять`() {
        assertTrue(reorderablePages(listOf(item(ObjectKind.IMAGE, "1.jpg"), item(ObjectKind.IMAGE, "2.jpg"))))
    }

    @Test
    fun `снимок, PDF и текст — страницы одного набора, даже среди прочих файлов`() {
        assertTrue(
            reorderablePages(
                listOf(item(ObjectKind.IMAGE, "1.jpg"), item(ObjectKind.ZIP, "a.zip"), item(ObjectKind.PDF, "2.pdf")),
            ),
        )
        assertTrue(reorderablePages(listOf(item(ObjectKind.TEXT, "a.txt"), item(ObjectKind.TEXT, "b.txt"))))
    }

    @Test
    fun `одна страница или набор без страниц — переставлять нечего`() {
        assertFalse(reorderablePages(listOf(item(ObjectKind.IMAGE, "1.jpg"))))
        assertFalse(reorderablePages(listOf(item(ObjectKind.IMAGE, "1.jpg"), item(ObjectKind.AUDIO, "b.ogg"))))
        assertFalse(reorderablePages(listOf(item(ObjectKind.ZIP, "a.zip"), item(ObjectKind.OFFICE, "b.docx"))))
        assertFalse(reorderablePages(emptyList()))
    }
}
