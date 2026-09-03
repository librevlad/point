package com.point.core.flow

import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Режим YOLO (#795, решение владельца 12.08.2026 «делаем сразу и включаем по умолчанию»):
 * человек заранее сказал «делай лучшее». Обычный порядок бережёт телефон, этот — берёт тот
 * путь, который даёт лучший результат.
 */
class YoloTakesTheStrongPathTest {

    private class Path(
        name: String,
        kind: RealizerKind,
        priority: Int = 50,
        leavesCircle: Boolean = kind == RealizerKind.CLOUD,
    ) : Realizer {
        override val capabilityId = CapabilityId("read")
        override val meta = RealizerMeta(priority = priority, kind = kind, leavesCircle = leavesCircle)
        val name = name
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
            ActionResult.Done(name)
    }

    private val onPhone = Path("телефон", RealizerKind.LOCAL, priority = 10)

    /** Компьютер круга делает работу у себя: результат тот же, что у телефона (#1415). */
    private val onComputer = Path("компьютер", RealizerKind.REMOTE, priority = 40)

    /** Компьютер круга отправит объект дальше, в чужой сервис — как облако, но через соседа. */
    private val throughComputer = Path("компьютер-наружу", RealizerKind.REMOTE, priority = 40, leavesCircle = true)

    private val inCloud = Path("облако", RealizerKind.CLOUD, priority = 90)

    private val anything = ObjectState(ObjectKind.IMAGE)

    private fun names(chosen: List<Realizer>) = chosen.map { (it as Path).name }

    private fun yolo(on: Boolean) = object : YoloMode {
        override fun enabled() = on
        override suspend fun setEnabled(enabled: Boolean) = Unit
    }

    @Test
    fun `без режима первым идёт бережный путь`() {
        val chosen = DefaultExecutionPolicy().choose(anything, listOf(inCloud, onPhone, onComputer))

        assertEquals(listOf("телефон", "компьютер", "облако"), names(chosen))
    }

    @Test
    fun `в режиме первым идёт сильный путь`() {
        val policy = DefaultExecutionPolicy(yolo(on = true))

        val chosen = policy.choose(anything, listOf(onPhone, throughComputer, inCloud))

        assertEquals(listOf("облако", "компьютер-наружу", "телефон"), names(chosen))
    }

    /**
     * #1415: до #1407 компьютер не объявлял «Страницы», «Слайды», «Найти» — телефон делал их
     * сам; объявил — и в режиме каждое уезжало на компьютер только за то, что он сосед, хотя
     * результат у него ровно тот же. Сильный путь — у того, кто дотягивается до сильного
     * движка снаружи; сосед, работающий у себя, идёт после телефона, как и без режима.
     */
    @Test
    fun `компьютер, делающий работу у себя, не сильнее телефона — телефон идёт первым`() {
        val policy = DefaultExecutionPolicy(yolo(on = true))

        val chosen = policy.choose(anything, listOf(onComputer, onPhone))

        assertEquals(listOf("телефон", "компьютер"), names(chosen))
    }

    @Test
    fun `сильный путь — тот, кто отправит наружу, а не всякий, кто не здешний`() {
        val policy = DefaultExecutionPolicy(yolo(on = true))

        val chosen = policy.choose(anything, listOf(onComputer, onPhone, throughComputer, inCloud))

        assertEquals(listOf("облако", "компьютер-наружу", "телефон", "компьютер"), names(chosen))
    }

    @Test
    fun `запасной путь остаётся — режим меняет порядок, а не состав`() {
        val all = listOf(onPhone, onComputer, inCloud)

        val chosen = DefaultExecutionPolicy(yolo(on = true)).choose(anything, all)

        assertEquals("телефон никуда не делся — он последний, а не выброшен", 3, chosen.size)
    }

    @Test
    fun `негодный исполнитель не поднимается вверх только за то, что он облачный`() {
        val closedCloud = object : Realizer {
            override val capabilityId = CapabilityId("read")
            override val meta = RealizerMeta(kind = RealizerKind.CLOUD)
            override fun isAvailable() = false
            override suspend fun perform(input: PointObject, amendment: String?) = ActionResult.Done("облако")
        }

        val chosen = DefaultExecutionPolicy(yolo(on = true)).choose(anything, listOf(onPhone, closedCloud))

        assertEquals(listOf("телефон"), names(chosen))
    }
}
