package com.point.desktop

import com.point.core.flow.INVESTIGATION_FAILED_HEAD
import com.point.core.flow.InvestigationState
import com.point.core.flow.KnownCapabilities
import com.point.core.flow.Realizer
import com.point.core.flow.investigationStateOf
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Сорвавшееся исследование на компьютере доходит до человека (#1272).
 *
 * Автоматическое исследование шло здесь своей укороченной дорогой: `runCatching { … }
 * .getOrNull()` и `(result as? Done)?.findings ?: return`. Отказ исполнителя, «нечем это
 * сделать» от резолвера и исключение исчезали без следа — ни слова человеку, ни следа в
 * знании. То же исследование, нажатое рукой, об отказе докладывало: одна и та же работа
 * вела себя по-разному в зависимости от того, кто её начал.
 *
 * Конституция, инвариант 8: отказ не скрывается. И обратное тоже (§13): сорвавшееся
 * исследование не становится ответом «не найдено» — вопрос остаётся незаданным.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PcSaysWhatHappenedTest {

    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    /** Исполнитель, который отказывает названной причиной, — как «Файла объекта нет на диске». */
    private class Refuses(private val why: String) : Realizer {
        override val capabilityId = KnownCapabilities.ENTITIES
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
            ActionResult.Failure(why, recoverable = true)
    }

    private fun pc(realizers: Set<Realizer>) = DesktopState(

        // Способность объявлена с `investigation = true` — её и запускает приём объекта.
        DesktopRegistry(setOf(PcEntitiesCapability())),
        DesktopResolver(realizers),
        clipboard = { },
        background = dispatcher,
    )

    private fun arrives(name: String): InboxItem {
        val file = temp.newFile(name).apply { writeText("Позвони мне: +380671234567") }
        return InboxItem(
            PointObject(
                "t", "text/plain", ScratchRef(file.absolutePath), ObjectState(ObjectKind.TEXT),
                metadata = mapOf("name" to name),
            ),
        )
    }

    @Test
    fun `отказ исследования, которого никто не заказывал, доходит до человека`() = runTest(dispatcher) {
        val why = "Файла объекта нет на диске"
        val st = pc(setOf(Refuses(why)))

        st.onReceived(arrives("счёт.txt"), ObjectSource.PHONE_RELAY)
        advanceUntilIdle()

        val said = st.message.value.orEmpty()
        assertTrue("причина отказа не дошла до человека: «$said»", said.contains(why))

        // Человек ничего не нажимал: голая причина посреди экрана не сказала бы, о чём она.
        assertTrue("непонятно, к чему эта причина: «$said»", said.startsWith(INVESTIGATION_FAILED_HEAD))
    }

    @Test
    fun `нечем ответить на вопрос — это тоже сказано, а не проглочено`() = runTest(dispatcher) {

        // Исполнителя под объявленный вопрос нет: резолвер отказывает своим NoWayHere.
        val st = pc(emptySet())

        st.onReceived(arrives("договор.txt"), ObjectSource.PHONE_RELAY)
        advanceUntilIdle()

        val said = st.message.value.orEmpty()
        assertTrue("отказ резолвера проглочен: «$said»", said.startsWith(INVESTIGATION_FAILED_HEAD))
        assertTrue("сказано «не вышло» без причины: «$said»", said.length > INVESTIGATION_FAILED_HEAD.length + 2)
    }

    @Test
    fun `сорвавшееся исследование не выдаёт себя за «не найдено»`() = runTest(dispatcher) {
        val st = pc(setOf(Refuses("Разобрать текст не вышло — попробуйте ещё раз")))

        st.onReceived(arrives("счёт.txt"), ObjectSource.PHONE_RELAY)
        advanceUntilIdle()

        val meta = st.items.value.first().obj.metadata
        assertEquals(
            "беда операции переписала знание",
            InvestigationState.NOT_INVESTIGATED,
            investigationStateOf(meta, KnownCapabilities.ENTITIES),
        )
    }

    @Test
    fun `автоматический шаг не пишется в «ПУТЬ»`() = runTest(dispatcher) {
        val st = pc(setOf(Refuses("Разобрать текст не вышло — попробуйте ещё раз")))

        st.onReceived(arrives("счёт.txt"), ObjectSource.PHONE_RELAY)
        advanceUntilIdle()

        val steps = st.journal.value.first().steps
        assertTrue("в «ПУТЬ» попал шаг, которого человек не делал: $steps", steps.isEmpty())
    }

    @Test
    fun `тихое исследование не зажигает индикатор и не перебивает прибытие`() = runTest(dispatcher) {
        lateinit var st: DesktopState
        var indicator: Working? = null
        val watching = object : Realizer {
            override val capabilityId = KnownCapabilities.ENTITIES
            override suspend fun perform(input: PointObject, amendment: String?): ActionResult {

                // Что человек видел бы в окне, пока идёт незаказанная работа.
                indicator = st.working.value
                return ActionResult.Done("Нашёл: телефоны — 1")
            }
        }
        st = pc(setOf(watching))

        st.onReceived(arrives("счёт.txt"), ObjectSource.PHONE_RELAY)
        advanceUntilIdle()

        assertNull("незаказанная работа показалась операцией: $indicator", indicator)
        assertTrue(
            "удача тихого исследования перебила слово о прибытии: «${st.message.value}»",
            st.message.value.orEmpty().contains("счёт.txt"),
        )
    }
}
