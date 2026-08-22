package com.point.core.flow

import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.RelationType
import com.point.core.model.ValueRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что с чем связано (#1176).
 *
 * Раньше принадлежность выражали два частных правила — «место получателя» (#772) и «чей
 * телефон» (#747). Оба читали одно и то же: блок страницы, в котором стоит значение, и имя
 * стороны в том же блоке. Правило здесь одно и общее, а связь остаётся знанием: её можно
 * записать, слить и показать, а не только подменить ею значение факта.
 */
class BelongingTest {

    /** Настоящая наклейка: 81 слово с телефона, оба отделения и оба имени на странице. */
    private val real = AtomCodec.decode(
        checkNotNull(javaClass.getResourceAsStream("/ocr/np_label.atoms.tsv")) {
            "нет фикстуры наклейки"
        }.bufferedReader().readText(),
    )

    private val senderKey = META_GRAPH_ROLE_PREFIX + "sender"

    private val receiverKey = META_GRAPH_ROLE_PREFIX + "receiver"

    private val carrierKey = META_GRAPH_ROLE_PREFIX + "carrier"

    private val senderName = "Тарасенко"

    private val receiverName = "Лумброван"

    private val parties = mapOf(senderKey to senderName, receiverKey to receiverName)

    private fun onPage(text: String): FieldCandidate =
        FieldCandidate(text, real.findOnPage(text).first().ids)

    /** «Вддиення Net» — то, что движок сделал из «Відділення №1» получателя. */
    private val receiverBranch get() = onPage("Вддиення Net")

    private val senderBranch get() = onPage("Вддтення №14")

    private val phone get() = onPage("С57 636 05 50")

    private fun places(vararg readings: FieldCandidate) =
        real.belongings(mapOf(META_ENTITY_PLACE to readings.toList()), parties)[META_ENTITY_PLACE]
            .orEmpty()

    @Test
    fun `место при отправителе и место при получателе — разные стороны`() {
        assertEquals(
            listOf(senderKey, receiverKey),
            places(senderBranch, receiverBranch).map { it.partyKey },
        )
    }

    @Test
    fun `спор однозначного факта решает сторона, которой документ адресован`() {
        val chosen = chosenByAddressee(META_ENTITY_PLACE, places(senderBranch, receiverBranch))

        assertEquals(receiverBranch.text, chosen?.reading?.text)
    }

    @Test
    fun `порядок прочтений ничего не решает`() {
        val chosen = chosenByAddressee(META_ENTITY_PLACE, places(receiverBranch, senderBranch))

        assertEquals(receiverBranch.text, chosen?.reading?.text)
    }

    @Test
    fun `в блоке получателя два места — выбор не делается`() {
        // «Брипвка» стоит на странице дважды — в шапке и у получателя. Здесь нужна вторая
        // (село назначения рядом с отделением), то есть два места в одном блоке.
        val settlement = FieldCandidate("Брипвка", real.findOnPage("Брипвка").last().ids)

        assertNull(chosenByAddressee(META_ENTITY_PLACE, places(receiverBranch, settlement)))
    }

    @Test
    fun `сторону, которой на странице не видно, связь не называет`() {
        val unseen = mapOf(receiverKey to "Ковальчук Петро")

        assertTrue(real.belongings(mapOf(META_ENTITY_PLACE to listOf(receiverBranch)), unseen).isEmpty())
    }

    @Test
    fun `ролей не назвали — связи нет`() {
        assertTrue(
            real.belongings(mapOf(META_ENTITY_PLACE to listOf(senderBranch, receiverBranch)), emptyMap())
                .isEmpty(),
        )
    }

    @Test
    fun `значение без опоры в словах страницы стороны не получает`() {
        assertEquals(emptyList<PartyReading>(), places(FieldCandidate("Відділення №1")))
    }

    @Test
    fun `номер достаётся тому, в чьей колонке он стоит`() {
        val found = real.belongings(mapOf(META_ENTITY_PHONE to listOf(phone)), parties)

        assertEquals(listOf(senderKey), found.getValue(META_ENTITY_PHONE).map { it.partyKey })
    }

    @Test
    fun `многозначный факт спором не судится — второй номер не спор первого`() {
        val found = real.belongings(mapOf(META_ENTITY_PHONE to listOf(phone)), parties)

        assertNull(chosenByAddressee(META_ENTITY_PHONE, found.getValue(META_ENTITY_PHONE)))
    }

    @Test
    fun `имя, названное моделью при значении, страницей не переписывается`() {
        val named = phone.copy(person = receiverName)

        assertTrue(real.belongings(mapOf(META_ENTITY_PHONE to listOf(named)), parties).isEmpty())
    }

    @Test
    fun `одноколоночная страница хозяина не выдумывает`() {
        val plain = AtomLayer(
            listOf(
                Atom(id = "a0", text = "Іваненко Іван", box = Box(100f, 100f, 400f, 130f)),
                Atom(id = "a1", text = "067 636 05 60", box = Box(100f, 140f, 400f, 170f)),
            ),
        )
        val number = FieldCandidate("067 636 05 60", listOf("a1"))

        assertTrue(
            plain.belongings(
                mapOf(META_ENTITY_PHONE to listOf(number)),
                mapOf(senderKey to "Іваненко Іван"),
            ).isEmpty(),
        )
    }

    @Test
    fun `в блоке две стороны — хозяин не назначается`() {
        val crowd = AtomLayer(
            listOf(
                Atom(id = "a0", text = "Іваненко Іван", box = Box(100f, 100f, 400f, 130f)),
                Atom(id = "a1", text = "Петренко Петро", box = Box(100f, 140f, 400f, 170f)),
                Atom(id = "a2", text = "067 636 05 60", box = Box(100f, 180f, 400f, 210f)),
                Atom(id = "a3", text = "Сидоренко Сидір", box = Box(600f, 100f, 900f, 130f)),
                Atom(id = "a4", text = "Відділення №1", box = Box(600f, 140f, 900f, 170f)),
                Atom(id = "a5", text = "м.Одеса", box = Box(600f, 180f, 900f, 210f)),
            ),
        )
        val number = FieldCandidate("067 636 05 60", listOf("a2"))

        assertTrue(
            crowd.belongings(
                mapOf(META_ENTITY_PHONE to listOf(number)),
                mapOf(
                    senderKey to "Іваненко Іван",
                    receiverKey to "Петренко Петро",
                    carrierKey to "Сидоренко Сидір",
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `телефон человека становится его контактом`() {
        val owned = real.belongings(mapOf(META_ENTITY_PHONE to listOf(phone)), parties)

        assertEquals(
            listOf(PersonContact(senderName, phone.text)),
            personContacts(owned.getValue(META_ENTITY_PHONE), parties),
        )
    }

    @Test
    fun `служба не человек — карточки контакта у неё не заводится`() {
        val service = mapOf(carrierKey to "Нова пошта")
        val owned = listOf(PartyReading(phone, carrierKey))

        assertEquals(emptyList<PersonContact>(), personContacts(owned, service))
    }

    @Test
    fun `принадлежность названа ключом стороны, а не копией её имени`() {
        val facts = mapOf(META_ENTITY_PHONE to phone.text, senderKey to senderName)

        val owned = belongingFacts(facts, mapOf(META_ENTITY_PHONE to listOf(PartyReading(phone, senderKey))))

        assertEquals(mapOf(META_ENTITY_PHONE + META_OF_SUFFIX to senderKey), owned)
        assertEquals(senderName, ownerOf(facts + owned, META_ENTITY_PHONE))
    }

    @Test
    fun `у факта другое значение — принадлежность ему не приписывается`() {
        val facts = mapOf(META_ENTITY_PHONE to "099 111 22 33", senderKey to senderName)

        assertTrue(
            belongingFacts(facts, mapOf(META_ENTITY_PHONE to listOf(PartyReading(phone, senderKey)))).isEmpty(),
        )
    }

    @Test
    fun `связь переживает слияние знания`() {
        val known = mapOf(
            META_ENTITY_PHONE to phone.text,
            META_ENTITY_PHONE + META_OF_SUFFIX to senderKey,
            senderKey to senderName,
        )

        val merged = mergeKnowledge(known, mapOf(META_ENTITY_PHONE to phone.text))

        assertEquals(senderName, ownerOf(merged, META_ENTITY_PHONE))
    }

    @Test
    fun `связь не превращается в спор факта`() {
        val known = mapOf(META_ENTITY_PHONE to phone.text, META_ENTITY_PHONE + META_OF_SUFFIX to senderKey)

        val merged = mergeKnowledge(known, mapOf(META_ENTITY_PHONE + META_OF_SUFFIX to receiverKey))

        assertEquals(phone.text, merged[META_ENTITY_PHONE])
        assertNull(merged[META_ENTITY_PHONE + META_ALT_SUFFIX])
        assertEquals(receiverKey, merged[META_ENTITY_PHONE + META_OF_SUFFIX])
    }

    @Test
    fun `связь не выдаётся человеку за новое знание`() {
        val before = mapOf(META_ENTITY_PHONE to phone.text, senderKey to senderName)

        assertNull(spiralDelta(before, before + (META_ENTITY_PHONE + META_OF_SUFFIX to senderKey)))
    }

    @Test
    fun `найденный номер связан со своей стороной`() {
        val document = PointObject(
            id = "doc",
            mime = "image/jpeg",
            uri = ValueRef("label"),
            state = ObjectState(com.point.core.model.ObjectKind.IMAGE),
        )
        val facts = mapOf(
            META_ENTITY_PHONE to phone.text,
            META_ENTITY_PHONE + META_OF_SUFFIX to senderKey,
            senderKey to senderName,
        )

        val (_, relations) = entityObjects(document, facts, creator = "understand")

        assertTrue(
            relations.contains(
                com.point.core.model.Relation(
                    "doc:phone",
                    RelationType.BELONGS_TO,
                    partyNodeId("doc", senderName),
                ),
            ),
        )
    }

    @Test
    fun `связи не назвали — узел стоит сам по себе`() {
        val document = PointObject(
            id = "doc",
            mime = "image/jpeg",
            uri = ValueRef("label"),
            state = ObjectState(com.point.core.model.ObjectKind.IMAGE),
        )

        val (_, relations) = entityObjects(
            document,
            mapOf(META_ENTITY_PHONE to phone.text, senderKey to senderName),
            creator = "understand",
        )

        assertTrue(relations.none { it.type == RelationType.BELONGS_TO })
    }
}
