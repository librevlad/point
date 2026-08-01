package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.Entitlements
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Согласие на облако судится по РЕАЛИЗАТОРАМ, а не по объявленной способности.
 *
 * Живая дыра, найденная разбором внешних сервисов: «Распознать текст» объявлена локальной
 * и бесплатной, а за ней цепочка, где на неудаче движка объект уходит в облако. На корпусе
 * владельца движок не справляется на шести кадрах из двадцати двух — путь обычный, не редкий.
 */
class ConsentByRealizersTest {

    private class Cap(id: String) : Capability {
        override val id = CapabilityId(id)
        override val icon = "x"
        override fun label(state: ObjectState) = "тест"
        override fun accepts(state: ObjectState) = true
        override fun produces(state: ObjectState) = state
    }

    private class R(id: String, kind: RealizerKind) : Realizer {
        override val capabilityId = CapabilityId(id)
        override val meta = RealizerMeta(kind = kind)
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
            ActionResult.Done("ok")
    }

    private fun resolver(vararg realizers: Realizer): DefaultResolver {
        val caps = realizers.mapTo(mutableSetOf<Capability>()) { Cap(it.capabilityId.value) }
        val registry = DefaultCapabilityRegistry(caps, DefaultBubblePolicy())
        return DefaultResolver(realizers.toSet(), registry, Entitlements { true })
    }

    @Test
    fun `локальная цепочка объект не увозит`() {
        val r = resolver(R("ocr", RealizerKind.LOCAL))

        assertFalse(r.leavesDevice(CapabilityId("ocr")))
    }

    @Test
    fun `один облачный запасной в цепочке — уже увозит`() {
        // Ровно случай «Распознать текст»: локальный первый, облачный вторым.
        val r = resolver(R("ocr", RealizerKind.LOCAL), R("ocr", RealizerKind.CLOUD))

        assertTrue(r.leavesDevice(CapabilityId("ocr")))
    }

    @Test
    fun `удалённый путь на компьютер — тоже уход с устройства`() {
        val r = resolver(R("pc", RealizerKind.REMOTE))

        assertTrue(r.leavesDevice(CapabilityId("pc")))
    }

    @Test
    fun `неизвестная способность не объявляется увозящей на пустом месте`() {
        val r = resolver(R("ocr", RealizerKind.LOCAL))

        assertFalse(r.leavesDevice(CapabilityId("нет-такой")))
    }
}
