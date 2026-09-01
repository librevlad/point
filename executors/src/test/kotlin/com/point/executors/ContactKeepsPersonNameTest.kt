package com.point.executors

import com.point.core.flow.UnderstandRealizer

import com.point.core.flow.ContactInserter
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.LlmClient
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.flow.META_OF_SUFFIX
import com.point.core.flow.NewContact
import com.point.core.flow.mergeKnowledge
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Имя человека доезжает из знания объекта в карточку контакта (#993).
 *
 * Живая охота 14.08.2026: «Сохранить контакт» на визитке открывало системную карточку с
 * пустыми First name и Last name, хотя «Олена Ковальчук» напечатана крупнее всего и Point
 * её прочитал. Имя знал только узел человека, рождённый парой «номер | имя», а объект, на
 * котором стоит действие, оставался без имени.
 */
class ContactKeepsPersonNameTest {

    private val designer = "Олена Ковальчук"
    private val phone = "+380 67 123 45 67"
    private val email = "olena@tihiy-dvor.example"
    private val address = "Киев, улица Ярославская, 14"

    private val card = "СТУДИЯ «ТИХИЙ ДВОР»\n$designer\nдизайнер интерьеров\n$phone\n$email\n$address"

    private val sender = "Іваненко Іван"
    private val receiver = "Петренко Петро"
    private val labelPhone = "067 636 05 60"

    @Test
    fun `имя с визитки уезжает в карточку контакта, а не дописывается руками`() = runTest {
        val understood = understand(
            card,
            "CONTACT=$phone | $designer\nEMAIL=$email\nADDRESS=$address\nSUMMARY=Визитка дизайнера",
        )

        val saved = saveContact(understood)

        assertEquals(designer, saved?.name)
        assertEquals(phone, saved?.phone)
        assertEquals(address, saved?.address)
    }

    @Test
    fun `в карточку едет хозяин номера, а не первая сторона документа`() = runTest {
        val label = objectOf(
            "$sender  $receiver\n$labelPhone",
            mapOf(
                META_GRAPH_ROLE_PREFIX + "receiver" to receiver,
                META_GRAPH_ROLE_PREFIX + "sender" to sender,
                META_ENTITY_PREFIX + "phone" to labelPhone,
                META_ENTITY_PREFIX + "phone" + META_OF_SUFFIX to META_GRAPH_ROLE_PREFIX + "sender",
            ),
        )

        assertEquals(sender, saveContact(label)?.name)
    }

    @Test
    fun `хозяин номера — организация — второй стороной карточку не подписывают`() = runTest {

        // Наклейка отправителя-организации: номер принадлежит «ТОВ «Агротрейд»», а вторая
        // сторона документа — человек. Подписать карточку им значило бы отдать чужой номер
        // под чужим именем — ровно та подмена хозяина, ради которой правило и писано.
        val label = objectOf(
            "ТОВ «Агротрейд»  $receiver\n$labelPhone",
            mapOf(
                META_GRAPH_ROLE_PREFIX + "sender" to "ТОВ «Агротрейд»",
                META_GRAPH_ROLE_PREFIX + "receiver" to receiver,
                META_ENTITY_PREFIX + "phone" to labelPhone,
                META_ENTITY_PREFIX + "phone" + META_OF_SUFFIX to META_GRAPH_ROLE_PREFIX + "sender",
            ),
        )

        assertNull(saveContact(label)?.name)
    }

    @Test
    fun `второй виток не заводит тому же человеку вторую сторону`() = runTest {

        // «Понять сильнее»: модель дала пару «номер | имя», но строку роли не повторила.
        // Знание о том, что этот человек — отправитель, уже лежит на объекте, и второй
        // стороной он вставать не должен: один человек, а не две стороны одного объекта.
        val known = objectOf(
            "$sender\n$labelPhone",
            mapOf(META_GRAPH_ROLE_PREFIX + "sender" to sender),
        )

        val again = understood(known, "CONTACT=$labelPhone | $sender")

        assertNull(
            "тот же человек встал второй стороной объекта",
            again.metadata[META_GRAPH_ROLE_PREFIX + "contact"],
        )
    }

    @Test
    fun `организация при номере человеком объекта не становится`() = runTest {

        // Реквизиты фирмы: модель называет при номере саму фирму, и правовая форма в
        // названии видна. Стороной-человеком объекта она вставать не должна — иначе
        // системная карточка контакта открывается строкой «Открыл карточку контакта:
        // ТОВ «Агротрейд»». Номер при этом знание, и он остаётся.
        val firm = "ТОВ «Агротрейд»"
        val firmPhone = "+380 44 123 45 67"
        val understood = understand(
            "$firm\nвідділ продажу\n$firmPhone",
            "CONTACT=$firmPhone | $firm\nSUMMARY=Реквизиты фирмы",
        )

        assertNull(
            "фирма встала стороной-человеком объекта",
            understood.metadata[META_GRAPH_ROLE_PREFIX + "contact"],
        )
        val saved = saveContact(understood)
        assertNull("фирма подписала карточку контакта", saved?.name)
        assertEquals("номер фирмы потерялся вместе с её именем", firmPhone, saved?.phone)
    }

    @Test
    fun `имени, которого нет в прочитанном, объект о себе не узнаёт`() = runTest {

        // Слово модели становится знанием самого объекта и уезжает в системную карточку
        // строкой «Открыл карточку контакта: …». Спрашивается с него столько же, сколько с
        // прочтения поля (#809): на странице этого имени нет — знания нет.
        val understood = understand(card, "CONTACT=$phone | Джон Сміт\nSUMMARY=Визитка")

        assertNull(understood.metadata[META_GRAPH_ROLE_PREFIX + "contact"])
        assertNull(saveContact(understood)?.name)
    }

    @Test
    fun `двое названных без хозяина номера имени не дают — карточка честно пустая`() = runTest {
        val label = objectOf(
            "$sender  $receiver\n$labelPhone",
            mapOf(
                META_GRAPH_ROLE_PREFIX + "receiver" to receiver,
                META_GRAPH_ROLE_PREFIX + "sender" to sender,
                META_ENTITY_PREFIX + "phone" to labelPhone,
            ),
        )

        assertNull(saveContact(label)?.name)
    }

    @Test
    fun `организация именем человека не становится`() = runTest {

        // Удостоверение личности, прогон 14.08.2026: «кто выдал» модель находит, и в поле
        // имени системной карточки уезжала выдавшая документ республика.
        val issuer = "RÉPUBLIQUE FRANÇAISE"
        val id = objectOf(
            "CARTE NATIONALE D'IDENTITÉ\n$phone",
            mapOf(
                META_GRAPH_ROLE_PREFIX + "issuer" to issuer,
                META_ENTITY_PREFIX + "phone" to phone,
            ),
        )

        assertNull(saveContact(id)?.name)
    }

    @Test
    fun `трое подписанных одним именем объект не подписывают`() = runTest {
        val chat = understand(
            "капітан АНДРІЯЩЕНКО Артур +380 66 526 2706\n" +
                "сержант ДУМБРОВАН Олександр +380 96 199 2869\n" +
                "сержант НОВІК Владислав +380 93 242 37 59",
            "CONTACT=+380 66 526 2706 | АНДРІЯЩЕНКО Артур\n" +
                "CONTACT=+380 96 199 2869 | ДУМБРОВАН Олександр\n" +
                "CONTACT=+380 93 242 37 59 | НОВІК Владислав",
        )

        assertNull(
            "объект подписан одним из троих: " + chat.metadata[META_GRAPH_ROLE_PREFIX + "contact"],
            saveContact(chat)?.name,
        )
    }

    /** «Понять» на объекте: знание витка ложится в объект тем же слиянием, что и во флоу. */
    private suspend fun understand(text: String, answer: String): PointObject =
        understood(objectOf(text), answer)

    private suspend fun understood(obj: PointObject, answer: String): PointObject {
        val result = UnderstandRealizer(llm(answer)).perform(obj, null)
        val findings = (result as ActionResult.Done).findings!!
        return obj.copy(metadata = mergeKnowledge(obj.metadata, findings.metadata))
    }

    /** «Сохранить контакт» на том же объекте: что уехало в системную карточку. */
    private suspend fun saveContact(obj: PointObject): NewContact? {
        val inserter = FakeContacts()
        SaveContactRealizer(extractor(), inserter).perform(obj, null)
        return inserter.contact
    }

    private fun objectOf(text: String, metadata: Map<String, String> = emptyMap()): PointObject {
        val f = File.createTempFile("point-card", ".txt").apply { deleteOnExit(); writeText(text) }
        return PointObject("card", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT), metadata)
    }

    private fun llm(answer: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            val f = File.createTempFile("point-ans", ".txt").apply { deleteOnExit(); writeText(answer) }
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(f.absolutePath))
        }
    }

    private fun extractor(vararg entities: Entity) = object : EntityExtractor {
        override suspend fun extract(text: String) = entities.toList()
    }

    private class FakeContacts : ContactInserter {
        var contact: NewContact? = null
        override suspend fun insertContact(contact: NewContact) {
            this.contact = contact
        }
    }
}
