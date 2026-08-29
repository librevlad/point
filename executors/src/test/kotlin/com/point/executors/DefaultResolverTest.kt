package com.point.executors

import com.point.core.flow.META_UNUSABLE_REASON
import com.point.core.flow.Realizer
import com.point.core.flow.RealizerKind
import com.point.core.flow.RealizerMeta
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultResolverTest {

    private val registry = DefaultCapabilityRegistry(
        capabilities = setOf(AiCapability(aiKeysReady), SaveCapability()),
        policy = DefaultBubblePolicy(),
    )

    private fun realizer(
        id: String,
        priority: Int = 50,
        kind: RealizerKind = RealizerKind.LOCAL,
        available: Boolean = true,
        done: String = "x",
        result: ActionResult? = null,
    ) = object : Realizer {
        override val capabilityId = CapabilityId(id)
        override val meta = RealizerMeta(priority, kind)
        override fun isAvailable() = available
        override suspend fun perform(input: PointObject, amendment: String?) =
            result ?: ActionResult.Done(done)
    }

    private fun resolver(realizers: Set<Realizer>) = DefaultResolver(realizers, registry)

    private fun obj() = PointObject("x", "text/plain", ScratchRef("/x"), ObjectState(ObjectKind.TEXT))

    @Test
    fun `the lowest-priority available realizer is tried first`() = runTest {
        val local = realizer("ocr", priority = 10, done = "local")
        val cloud = realizer("ocr", priority = 90, kind = RealizerKind.CLOUD, done = "cloud")
        val result = resolver(setOf(cloud, local)).realizerFor(CapabilityId("ocr")).perform(obj())
        assertEquals("local", (result as ActionResult.Done).message)
    }

    @Test
    fun `multiple available realizers fall through a recoverable failure to the next`() = runTest {
        val local = realizer("ocr", priority = 10, result = ActionResult.Failure("miss", recoverable = true))
        val cloud = realizer("ocr", priority = 90, kind = RealizerKind.CLOUD, done = "cloud")
        val result = resolver(setOf(local, cloud)).realizerFor(CapabilityId("ocr")).perform(obj())
        assertTrue(result is ActionResult.Done)
        assertEquals("cloud", (result as ActionResult.Done).message)
    }

    @Test
    fun `preferred but unavailable falls through to the next available`() {
        val local = realizer("ocr", priority = 10, available = false)
        val cloud = realizer("ocr", priority = 90, kind = RealizerKind.CLOUD, available = true)
        assertSame(cloud, resolver(setOf(local, cloud)).realizerFor(CapabilityId("ocr")))
    }

    /**
     * Цену исполнитель объявляет приоритетом, и она решает первой — дешёвое и быстрое впереди
     * дорогого. Правило близости (сначала я, потом мой компьютер, потом чужой сервис, #1088)
     * разбирает уже равных по цене, а вида, который сам по себе отменял бы цену, нет.
     */
    @Test
    fun `цена решает раньше близости — дешёвый чужой сервис впереди дорогого своего`() = runTest {

        val cheap = realizer("ai", priority = 10, kind = RealizerKind.CLOUD, done = "по приоритету 10")
        val dear = realizer("ai", priority = 90, kind = RealizerKind.LOCAL, done = "по приоритету 90")

        val result = resolver(setOf(dear, cheap)).realizerFor(CapabilityId("ai")).perform(obj())

        assertEquals("по приоритету 10", (result as ActionResult.Done).message)
    }

    @Test
    fun `реализация, не берущаяся за этот объект, в выбор не идёт`() = runTest {

        val narrow = object : Realizer {
            override val capabilityId = CapabilityId("pdf")
            override val meta = com.point.core.flow.RealizerMeta(priority = 10)
            override fun accepts(state: com.point.core.model.ObjectState) =
                state.kind == com.point.core.model.ObjectKind.OFFICE
            override suspend fun perform(input: com.point.core.model.PointObject, amendment: String?) =
                ActionResult.Done("узкая")
        }
        val wide = realizer("pdf", priority = 90, done = "широкая")
        val r = resolver(setOf(narrow, wide))

        val office = r.realizerFor(CapabilityId("pdf"), com.point.core.model.ObjectState(com.point.core.model.ObjectKind.OFFICE))
        val image = r.realizerFor(CapabilityId("pdf"), com.point.core.model.ObjectState(com.point.core.model.ObjectKind.IMAGE))

        assertEquals("узкая", (office.perform(obj()) as ActionResult.Done).message)
        assertEquals("широкая", (image.perform(obj()) as ActionResult.Done).message)
    }

    @Test
    fun `when none is available it returns the top-ranked so perform surfaces the error`() {
        val local = realizer("ocr", priority = 10, available = false)
        val cloud = realizer("ocr", priority = 90, kind = RealizerKind.CLOUD, available = false)
        assertSame(local, resolver(setOf(cloud, local)).realizerFor(CapabilityId("ocr")))
    }

    @Test
    fun `unknown capability id refuses in words, not with an identifier`() = runTest {
        val result = resolver(setOf(realizer("ocr"))).realizerFor(CapabilityId("nope")).perform(obj())

        assertTrue(result is ActionResult.Failure)
        assertEquals(com.point.core.flow.NO_WAY_HERE_REASON, (result as ActionResult.Failure).reason)
    }

    /**
     * Платного контура в Point нет (#1253): `Cost.PAID` читается как «стоит денег или квоты»,
     * а не «продаётся человеку». Дорогая способность выполняется по нажатию так же, как
     * бесплатная, — нигде не остаётся ворот, которые некому открыть.
     */
    @Test
    fun `дорогая способность выполняется по нажатию так же, как бесплатная`() = runTest {
        val paid = resolver(setOf(realizer("ai", done = "real AI"))).realizerFor(AiCapability.ID).perform(obj())
        val free = resolver(setOf(realizer("save", done = "saved"))).realizerFor(SaveCapability.ID).perform(obj())

        assertTrue("дорогая способность отказала вместо работы", paid is ActionResult.Done)
        assertTrue("бесплатная способность отказала вместо работы", free is ActionResult.Done)
    }

    // ---- #684/#685: годность — часть состояния, а не проверка внутри исполнителя. ----

    @Test
    fun `негодный объект — местный кандидат ещё пробует сам, до облака дело не доходит`() = runTest {
        val local = realizer("ocr", priority = 10, result = ActionResult.Failure("не разобрал", recoverable = true))
        val cloud = realizer("ocr", priority = 90, kind = RealizerKind.CLOUD, done = "cloud")
        val unusable = ObjectState(ObjectKind.IMAGE, setOf(Feature.UNUSABLE))

        val result = resolver(setOf(local, cloud)).realizerFor(CapabilityId("ocr"), unusable).perform(obj())

        assertEquals(
            "местный отказ остаётся местным — не подменяется сетевым",
            "не разобрал",
            (result as ActionResult.Failure).reason,
        )
    }

    @Test
    fun `у способности только внешний исполнитель — негодный объект получает мгновенный отказ`() = runTest {
        var cloudCalled = false
        val cloud = object : Realizer {
            override val capabilityId = CapabilityId("ocr-cloud")
            override val meta = RealizerMeta(priority = 10, kind = RealizerKind.CLOUD)
            override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
                cloudCalled = true
                return ActionResult.Done("cloud")
            }
        }
        val unusable = ObjectState(ObjectKind.IMAGE, setOf(Feature.UNUSABLE))
        val reason = "Файл не открылся — он повреждён или это не изображение"
        val broken = obj().copy(metadata = mapOf(META_UNUSABLE_REASON to reason))

        val result = resolver(setOf(cloud)).realizerFor(CapabilityId("ocr-cloud"), unusable).perform(broken)

        assertFalse("внешний исполнитель не должен был вызываться", cloudCalled)
        assertEquals(reason, (result as ActionResult.Failure).reason)
    }

    @Test
    fun `без явной причины в метаданных — всё равно человеческий отказ, не пусто`() = runTest {
        val cloud = realizer("ocr-cloud", kind = RealizerKind.CLOUD, done = "cloud")
        val unusable = ObjectState(ObjectKind.IMAGE, setOf(Feature.UNUSABLE))

        val result = resolver(setOf(cloud)).realizerFor(CapabilityId("ocr-cloud"), unusable).perform(obj())

        assertTrue((result as ActionResult.Failure).reason.isNotBlank())
    }

    @Test
    fun `негодный объект — сетевая способность гасится, даже если её реализатор назвался местным`() = runTest {

        // AiRealizer в реальном коде тоже RealizerKind.LOCAL по умолчанию, хотя зовёт модель —
        // AiCapability честно объявляет network=true, и резолвер обязан поверить способности,
        // а не только пометке конкретного реализатора.
        val misnamed = realizer("ai", kind = RealizerKind.LOCAL, done = "модель ответила")
        val unusable = ObjectState(ObjectKind.IMAGE, setOf(Feature.UNUSABLE))

        val result = resolver(setOf(misnamed)).realizerFor(AiCapability.ID, unusable).perform(obj())

        assertTrue("сеть не должна была выполниться — способность объявлена сетевой", result is ActionResult.Failure)
    }

    @Test
    fun `негодный объект — местная и не сетевая способность по-прежнему работает`() = runTest {
        val unusable = ObjectState(ObjectKind.IMAGE, setOf(Feature.UNUSABLE))

        val result = resolver(setOf(realizer("save", done = "saved")))
            .realizerFor(SaveCapability.ID, unusable)
            .perform(obj())

        assertEquals("сохранение не спрашивает годность содержимого", "saved", (result as ActionResult.Done).message)
    }

    @Test
    fun `обычный объект — выбор реализатора не меняется вовсе`() = runTest {
        val local = realizer("ocr", priority = 10, done = "local")
        val cloud = realizer("ocr", priority = 90, kind = RealizerKind.CLOUD, done = "cloud")
        val ok = ObjectState(ObjectKind.IMAGE)

        val result = resolver(setOf(cloud, local)).realizerFor(CapabilityId("ocr"), ok).perform(obj())

        assertEquals("local", (result as ActionResult.Done).message)
    }
}
