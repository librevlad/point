package com.point.data

import com.point.core.flow.META_ENTITY_AMOUNT
import com.point.core.flow.provenanceOf
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Знание не выдаёт себя за прочитанное с кадра, если кадра не было (#1024).
 *
 * Человек прислал обычный `.txt`, и сумма из него ложилась в граф с пометкой «распознано»:
 * `entity.amount.src = ocr`. Ни кадра, ни распознавания в этой цепочке не было. Это не
 * косметика — на происхождении строится дальнейшее поведение: «Исправить ошибки» просило
 * модель править «ошибки распознавания» там, где распознавать было нечего (#1023).
 */
class KnowledgeSaysWhereItCameFromTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun textObject(content: String): PointObject {
        val f = File(tmp.root, "bill.txt").apply { writeText(content) }
        return PointObject("bill", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    @Test
    fun `сумма из набранного текста не помечается распознанной с кадра`() = runTest {
        val bill = textObject("Счёт на суму 2873,60 грн от 14.08.2026")

        val facts = IdentifierInvestigationRealizer().let { realizer ->
            (realizer.perform(bill, null) as com.point.core.model.ActionResult.Done).findings!!.metadata
        }

        val where = provenanceOf(facts, META_ENTITY_AMOUNT)
        assertNotEquals("текст объекта выдан за прочтение кадра", Provenance.OCR, where)
        assertEquals(Provenance.TEXT, where)
    }
}
