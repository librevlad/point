package com.point.executors

import com.point.core.flow.ACTION_SCHEMAS
import com.point.core.flow.ContactInserter
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
            ShareCapability(),
        ),
        policy = DefaultBubblePolicy(),
    )

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

    private fun textObject(text: String, metadata: Map<String, String> = emptyMap()): PointObject {
        val f = File.createTempFile("contact", ".txt").apply { writeText(text); deleteOnExit() }
        return PointObject(
            "id", "text/plain", ScratchRef(f.absolutePath),
            ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_PHONE)),
            metadata = metadata,
        )
    }

    private class FakeContacts : ContactInserter {
        var contact: com.point.core.flow.NewContact? = null
        val phone: String? get() = contact?.phone
        val email: String? get() = contact?.email
        var calls = 0
        override suspend fun insertContact(contact: com.point.core.flow.NewContact) {
            calls++
            this.contact = contact
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
    fun `в карточку контакта едет всё знание о человеке — имя и адрес тоже`() = runTest {

        // #673/#679 (охота 2026-08-09): системный лист открывался с пустым именем,
        // хотя «Олена Ковальчук» напечатана на визитке крупнее всего.
        val contacts = FakeContacts()
        val known = textObject(
            "визитка",
            mapOf(
                "entity.phone" to "+380671234567",
                "entity.email" to "olena@tihiy-dvor.example",
                "entity.address" to "Київ, вулиця Ярославська, 14",
                "graph.role.contact" to "Олена Ковальчук",
            ),
        )

        val result = SaveContactRealizer(extractor(), contacts).perform(known)

        assertEquals("Олена Ковальчук", contacts.contact?.name)
        assertEquals("Київ, вулиця Ярославська, 14", contacts.contact?.address)
        assertEquals("+380671234567", contacts.phone)

        // Карточка ОТКРЫТА — сохранит человек (#674): отмена не должна выглядеть успехом.
        val said = (result as ActionResult.Done).message
        assertTrue("исход обещает больше, чем сделал: «$said»", said.startsWith("Открыл"))
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
