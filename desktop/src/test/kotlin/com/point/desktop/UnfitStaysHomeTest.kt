package com.point.desktop

import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Негодный объект не уезжает с компьютера наружу (#855).
 *
 * `desktop/Inbox.kt` сам помечает пустой файл `Feature.UNUSABLE` при приёме — а резолвер
 * это знание не смотрел и отдавал объект первому подходящему исполнителю, в том числе
 * облачному. Телефон так не делал: правило было записано только в `DefaultResolver`.
 *
 * Конституция: «Приватность важнее удобства». Объект покидает устройства человека ради
 * дела, а тут дела нет — про негодность уже известно.
 */
class UnfitStaysHomeTest {

    private val sent = AtomicInteger(0)

    private inner class CloudReader : Realizer {
        override val capabilityId = CapabilityId("read")
        override val meta = RealizerMeta(kind = RealizerKind.CLOUD)
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
            sent.incrementAndGet()
            return ActionResult.Done("прочитано")
        }
    }

    private inner class LocalReader : Realizer {
        override val capabilityId = CapabilityId("read")
        override val meta = RealizerMeta(priority = 10)
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
            ActionResult.Done("прочитано на месте")
    }

    private fun empty() = ObjectState(ObjectKind.TEXT, features = setOf(Feature.UNUSABLE))

    private fun ordinary() = ObjectState(ObjectKind.TEXT)

    @Test
    fun `пустой файл не отдают облачному исполнителю`() {
        val resolver = DesktopResolver(setOf(CloudReader()))

        val refused = runCatching { resolver.realizerFor(CapabilityId("read"), empty()) }

        assertTrue("объект ушёл наружу", refused.isFailure)
        assertEquals(0, sent.get())
        assertEquals(com.point.core.flow.UNFIT_DEFAULT_REASON, refused.exceptionOrNull()?.message)
    }

    @Test
    fun `местный исполнитель для пустого файла остаётся — отказать можно и здесь`() {
        val resolver = DesktopResolver(setOf(CloudReader(), LocalReader()))

        val chosen = resolver.realizerFor(CapabilityId("read"), empty())

        assertEquals(RealizerKind.LOCAL, chosen.meta.kind)
    }

    @Test
    fun `годный объект облако по-прежнему читает`() = runBlocking {
        val resolver = DesktopResolver(setOf(CloudReader()))

        resolver.realizerFor(CapabilityId("read"), ordinary()).perform(
            PointObject("o", "text/plain", com.point.core.model.ValueRef("x"), ordinary()),
        )

        assertEquals(1, sent.get())
    }

    /**
     * Способность объявляет себя сетевой, даже когда исполнитель зовёт себя местным, —
     * так устроены «Понять», «Перевести» и «Дать ссылку». Знание о сети живёт у неё.
     */
    @Test
    fun `сетевая способность считается уходом наружу, даже если исполнитель зовётся местным`() {
        val resolver = DesktopResolver(
            realizers = setOf(LocalReader()),
            capabilityIsNetwork = { it == CapabilityId("read") },
        )

        val refused = runCatching { resolver.realizerFor(CapabilityId("read"), empty()) }

        assertTrue("сетевая способность отпустила негодный объект", refused.isFailure)
    }
}
