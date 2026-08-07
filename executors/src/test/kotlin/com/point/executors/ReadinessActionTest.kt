package com.point.executors

import com.point.core.flow.ACTION_SCHEMAS
import com.point.core.flow.ContactInserter
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_ENTITY_SUBJECT
import com.point.core.flow.Readiness
import com.point.core.flow.actionReadiness
import com.point.core.flow.runner
import com.point.core.model.ActionResult
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadinessActionTest {

    private val registry = DefaultCapabilityRegistry(
        capabilities = setOf(
            CallCapability(),
            SmsCapability(),
            EmailCapability(),
            MapCapability(),
            EventCapability(),
            SaveContactCapability(),
            VCardCapability(),
        ),
        policy = DefaultBubblePolicy(),
    )

    private fun bubbles(vararg features: Feature) =
        registry.bubblesFor(ObjectState(ObjectKind.TEXT, features.toSet()))

    @Test
    fun `каждое объявленное схемой действие есть среди возможностей`() {
        val known = registry.bubblesFor(
            ObjectState(
                ObjectKind.TEXT,
                setOf(Feature.HAS_PHONE, Feature.HAS_EMAIL, Feature.HAS_ADDRESS),
            ),
        ).map { it.capabilityId }.toSet()

        ACTION_SCHEMAS.mapNotNull { it.runs }.forEach { id ->
            assertTrue("схема объявила несуществующее действие $id", id in known)
        }
    }

    @Test
    fun `контакт с телефона — строка карточки и пузырь это одно действие`() {
        val row = actionReadiness(mapOf(META_ENTITY_PREFIX + "phone" to "+380504327707"))
            .single { it.schema.id == "save-contact" }

        val runner = row.runner(bubbles(Feature.HAS_PHONE))

        assertNotNull("«✓ Сохранить контакт» обязано запускаться тапом", runner)
        assertEquals("Сохранить контакт", runner!!.title)
    }

    @Test
    fun `ответ по адресу почты запускает письмо`() {
        val row = actionReadiness(
            mapOf(META_ENTITY_SUBJECT to "Refund", META_ENTITY_PREFIX + "email" to "liz@example.com"),
        ).single { it.schema.id == "reply" }

        assertEquals("Написать письмо", row.runner(bubbles(Feature.HAS_EMAIL))?.title)
    }

    @Test
    fun `маршрут по одним координатам кнопкой не становится`() {

        val row = actionReadiness(mapOf(com.point.core.flow.META_ENTITY_GEO to "50.4501, 30.5234"))
            .single { it.schema.id == "route" }

        assertTrue(row.readiness is Readiness.Ready)
        assertNull(row.runner(bubbles(Feature.HAS_DATE)))
    }

    private fun textObject(text: String, metadata: Map<String, String> = emptyMap()): PointObject {
        val f = File.createTempFile("contact", ".txt").apply { writeText(text); deleteOnExit() }
        return PointObject(
            "id", "text/plain", ScratchRef(f.absolutePath),
            ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PHONE)),
            metadata = metadata,
        )
    }

    private class FakeContacts : ContactInserter {
        var phone: String? = null
        var email: String? = null
        var calls = 0
        override suspend fun insertContact(phone: String?, email: String?) {
            calls++
            this.phone = phone
            this.email = email
        }
    }

    private fun extractor(vararg entities: Entity) = object : EntityExtractor {
        override suspend fun extract(text: String) = entities.toList()
    }

    @Test
    fun `сохранить контакт открывает системный экран с телефоном и почтой`() = runTest {
        val contacts = FakeContacts()

        val result = SaveContactRealizer(
            extractor(
                Entity(EntityType.PHONE, "+380504327707"),
                Entity(EntityType.EMAIL, "olena@example.com"),
            ),
            contacts,
        ).perform(textObject("+380504327707 olena@example.com"))

        assertTrue(result is ActionResult.Done)
        assertEquals("+380504327707", contacts.phone)
        assertEquals("olena@example.com", contacts.email)
    }

    @Test
    fun `сохраняется тот номер, который человек видел на строке`() = runTest {

        val contacts = FakeContacts()

        SaveContactRealizer(extractor(Entity(EntityType.PHONE, "+380671111111")), contacts)
            .perform(
                textObject(
                    "склад +380671111111, менеджер +380504327707",
                    metadata = mapOf(META_ENTITY_PREFIX + "phone" to "+380504327707"),
                ),
            )

        assertEquals("+380504327707", contacts.phone)
    }

    @Test
    fun `нечего сохранять — честный отказ, а не пустой экран контакта`() = runTest {
        val contacts = FakeContacts()

        val result = SaveContactRealizer(extractor(), contacts).perform(textObject("ни номера, ни почты"))

        assertTrue(result is ActionResult.Failure)
        assertEquals(0, contacts.calls)
    }

    @Test
    fun `на присланной vCard второго действия про контакт не появляется`() {

        val onVCard = ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_VCARD, Feature.HAS_PHONE))

        assertTrue(VCardCapability().accepts(onVCard))
        assertTrue(!SaveContactCapability().accepts(onVCard))
        assertTrue(SaveContactCapability().accepts(ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PHONE))))
    }
}
