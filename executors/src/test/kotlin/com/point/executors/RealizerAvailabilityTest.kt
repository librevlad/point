package com.point.executors

import com.point.core.flow.Realizer
import com.point.core.flow.RealizerMeta
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.PointObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «Есть ли на этом телефоне чем это выполнить» (#528) — половина правды, которой первому экрану
 * не хватало.
 *
 * Живой случай: у «Скана с цветом» весь смысл в цветном конвейере OpenCV, запасного пути нет, и
 * без загруженного пака выполнять действие нечем. Человек узнавал об этом ПОСЛЕ тапа — отказом
 * «Действие недоступно», то есть заплатив за обещание, которого никто не собирался выполнять.
 */
class RealizerAvailabilityTest {

    private class Fake(
        id: String,
        private val available: Boolean = true,
        private val reason: String? = null,
        priority: Int = 50,
    ) : Realizer {
        override val capabilityId = CapabilityId(id)
        override val meta = RealizerMeta(priority = priority)
        override fun isAvailable() = available
        override fun unavailableReason() = reason
        override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
            error("не запускается в этой проверке")
    }

    private fun availability(vararg realizers: Realizer) = RealizerAvailability(realizers.toSet())

    @Test
    fun `выполнять нечем, и оно сказало почему`() {
        val blocked = availability(Fake("scan-plus", available = false, reason = "нужен пакет обработки снимков"))

        assertEquals("нужен пакет обработки снимков", blocked.blockerFor(CapabilityId("scan-plus")))
    }

    @Test
    fun `есть хотя бы один живой реализатор — мешать нечему`() {
        // Так устроен обычный «Скан»: OpenCV-тир может погаснуть, но чистый фильтр рядом жив, и
        // человек падения пака не замечает вовсе.
        val chain = availability(
            Fake("scan", available = false, reason = "нужен пакет обработки снимков", priority = 10),
            Fake("scan", available = true, priority = 90),
        )

        assertNull(chain.blockerFor(CapabilityId("scan")))
    }

    @Test
    fun `молчащий гейт оставляет действие на экране`() {
        // Иначе правка завела бы новую тишину вместо починенной старой: действие исчезало бы
        // без единого слова, и спросить «почему» человеку было бы не у чего.
        val silent = availability(Fake("ocr-cloud", available = false))

        assertNull(silent.blockerFor(CapabilityId("ocr-cloud")))
    }

    @Test
    fun `у способности без реализаторов ничего не выдумывается`() {
        // Такую надо чинить в модуле, а не прятать от человека: тап и так упрётся в честную
        // ошибку резолвера, и она куда громче тихого исчезновения строки.
        assertNull(availability().blockerFor(CapabilityId("нет такого")))
    }

    @Test
    fun `объясняет себя предпочтительный реализатор, а не случайный`() {
        // Иначе причина менялась бы от запуска к запуску — множество не хранит порядка.
        val two = availability(
            Fake("scan-plus", available = false, reason = "нужен пакет обработки снимков", priority = 10),
            Fake("scan-plus", available = false, reason = "второй голос", priority = 90),
        )

        assertEquals("нужен пакет обработки снимков", two.blockerFor(CapabilityId("scan-plus")))
    }

    @Test
    fun `«Скан с цветом» умеет объяснить свою тишину`() {
        // Сторож против возврата: запасного пути у него нет, поэтому молчащий гейт здесь
        // означал бы ровно ту ложь, ради которой заведён #528.
        val realizer = ScanPlusRealizer(store = FakeStore)

        assertEquals("нужен пакет обработки снимков", realizer.unavailableReason())
    }

    private object FakeStore : com.point.core.flow.ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("unused")
        override suspend fun ingestMultiple(sources: List<String>) = error("unused")
        override suspend fun put(result: com.point.core.model.ResultObject) = error("unused")
        override suspend fun children(collection: PointObject, limit: Int) = error("unused")
        override suspend fun readText(obj: PointObject, limit: Int) = error("unused")
        override suspend fun newScratchFile(extension: String) = com.point.core.model.ScratchRef("/tmp/s.$extension")
        override suspend fun clear() = Unit
    }
}
