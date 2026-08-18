package com.point.data

import com.point.core.flow.Entity
import com.point.core.flow.EntityType
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_SOURCE_SUFFIX
import com.point.core.flow.factCandidate
import com.point.core.flow.unwrapped
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кандидат становится знанием, только заслужив смысл (#1139).
 *
 * Прежде между «нашли строку» и «это факт объекта» не было ничего: значение попадало в знание
 * как есть, и побеждало то, что встретилось первым. Отсюда телефон из номера дома (#1017),
 * немецкий номер из товарного штрихкода (#1062) и скобки внутри значения (#1064).
 */
class CandidateEarnsItsMeaningTest {

    private val phone = META_ENTITY_PREFIX + "phone"

    private fun source(kind: ObjectKind = ObjectKind.TEXT) =
        PointObject("src", "text/plain", ScratchRef("/tmp/src"), ObjectState(kind))

    @Test fun `цепочка цифр из адреса и штрихкода телефоном не становится`() {
        assertNull("номер дома со слипшимся номером магазина", factCandidate(phone, "908771 1329"))
        assertNull("товарный штрихкод EAN-13", factCandidate(phone, "4820000000017"))
        assertNull("штрихкод с чека", factCandidate(phone, "042200002675"))
        assertEquals("настоящий номер не должен пострадать", "918-682-1561", factCandidate(phone, "918-682-1561"))
    }

    @Test fun `обёртка вокруг значения в него не входит`() {
        assertEquals("+380 67 123 45 67", unwrapped("(+380 67 123 45 67)"))
        assertEquals("+380 50 987 65 43", unwrapped("[+380 50 987 65 43]"))
        assertEquals("(067) 123-45-67", unwrapped("(067) 123-45-67"))
    }

    @Test fun `номер в скобках — тот же факт, а не второй`() {
        val delta = entityDelta(
            source(),
            listOf(
                Entity(EntityType.PHONE, "(+380 67 123 45 67)"),
                Entity(EntityType.PHONE, "+380 67 123 45 67"),
            ),
        )

        assertEquals("скобки уехали внутрь значения", "+380 67 123 45 67", delta.metadata[phone])
        assertEquals("один номер — один узел", 1, delta.objects.count { it.metadata.containsKey(phone) })
    }

    @Test fun `знание с кадра называет своё происхождение`() {
        val delta = entityDelta(
            source(ObjectKind.IMAGE),
            listOf(Entity(EntityType.PHONE, "+380 67 123 45 67")),
            source_ = Provenance.OCR,
        )

        assertEquals(Provenance.OCR.wire, delta.metadata[phone + META_SOURCE_SUFFIX])
    }

    @Test fun `знание из набранного текста чтением с кадра не притворяется`() {
        val delta = entityDelta(
            source(),
            listOf(Entity(EntityType.PHONE, "+380 67 123 45 67")),
        )

        val said = delta.metadata[phone + META_SOURCE_SUFFIX]
        assertTrue("происхождение не названо вовсе", said != null)
        assertTrue("набранный текст выдан за прочитанный кадр", said != Provenance.OCR.wire)
    }
}
