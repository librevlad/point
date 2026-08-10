package com.point.executors

import com.point.core.flow.AppLauncher
import com.point.core.flow.AppTarget
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * У номера — те приложения, что стоят у человека (#466). Список даёт система: ни одного имени
 * стороннего сервиса в коде Point нет.
 */
class PhoneAppsTest {

    private val phone = "+380504327707"

    private val obj = PointObject(
        id = "id",
        mime = "text/plain",
        uri = ScratchRef("/scratch/текст"),
        state = ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PHONE)),
        metadata = mapOf(META_ENTITY_PREFIX + "phone" to phone),
    )

    private val extractor = object : EntityExtractor {
        override suspend fun extract(text: String) = listOf(Entity(EntityType.PHONE, phone))
    }

    private class Phone(val apps: List<AppTarget>) : AppLauncher {
        var opened: Pair<AppTarget, String>? = null
        var asked: String? = null

        override suspend fun handlers(obj: PointObject) = emptyList<AppTarget>()
        override suspend fun handlersForMime(mime: String) = emptyList<AppTarget>()
        override suspend fun launch(target: AppTarget, obj: PointObject) = Unit
        override suspend fun handlersForPhone(phone: String): List<AppTarget> {
            asked = phone
            return apps
        }
        override suspend fun launchWithPhone(target: AppTarget, phone: String) { opened = target to phone }
    }

    private fun app(label: String) = AppTarget(label, "pkg." + label.lowercase(), "pkg.Main")

    @Test fun `единственное приложение открывается сразу`() = runTest {
        val system = Phone(listOf(app("Телефон")))

        val result = PhoneAppsRealizer(extractor, system).perform(obj, null)

        assertTrue(result is ActionResult.Done)
        assertEquals(phone, system.asked)
        assertEquals("Телефон" to phone, system.opened?.let { it.first.label to it.second })
    }

    @Test fun `приложений несколько — выбирает человек, а не мы`() = runTest {
        val system = Phone(listOf(app("Телефон"), app("Сообщения"), app("Определитель")))

        val result = PhoneAppsRealizer(extractor, system).perform(obj, null)

        assertTrue("Point выбрал за человека: $result", result is ActionResult.NeedsInput)
        assertEquals(
            listOf("Телефон", "Сообщения", "Определитель"),
            (result as ActionResult.NeedsInput).suggestions,
        )
    }

    @Test fun `выбранное человеком приложение и открывается`() = runTest {
        val system = Phone(listOf(app("Телефон"), app("Определитель")))

        PhoneAppsRealizer(extractor, system).perform(obj, "Определитель")

        assertEquals("Определитель", system.opened?.first?.label)
    }

    @Test fun `ничего не установлено — сказано словами, без пустого списка`() = runTest {
        val system = Phone(emptyList())

        val result = PhoneAppsRealizer(extractor, system).perform(obj, null)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("нет приложения"))
    }

    @Test fun `в коде Point нет имён чужих сервисов`() {
        val source = java.io.File("src/main/kotlin/com/point/executors/PhoneAppsAction.kt").readText()
        val strangers = listOf("WhatsApp", "Telegram", "Viber", "GetContact", "Truecaller")

        strangers.forEach { name ->
            assertTrue("«$name» попал в код Point", !source.contains(name))
        }
    }
}
