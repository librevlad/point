package com.point.core.ui

import com.point.core.flow.KIND_PHONE
import com.point.core.flow.META_ENTITY_PHONE
import com.point.core.flow.META_ENTITY_URL
import com.point.core.model.ActionYield
import com.point.core.model.Because
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.model.ValueRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Действия лежат по тому, о чём они: найденное значение со своими продолжениями, понимание
 * вещи, новое из неё и она сама дальше (вещь → смыслы → естественные шаги).
 */
class ActionSectionsTest {

    /** Годный объект: о негодности разговора нет, порядок решают сами действия (#1101). */
    private val text = ObjectState(ObjectKind.TEXT)

    private val phone = Because(META_ENTITY_PHONE, "Телефон", "067 123 4567")
    private val link = Because(META_ENTITY_URL, "Ссылка", "point.leerio.app")

    private val thing = "С текстом"
    private val make = "Сделать из текста"

    // Настоящее чтение обещает результат (#1101): голое Unknown — «вернёт то, что попросите»,
    // и с одним таким «Разобраться» не ведёт.
    private fun bubble(
        title: String,
        intent: Intent,
        yields: ActionYield = ActionYield.Unknown,
        because: Because? = null,
    ) = Bubble("i", title, CapabilityId(title), ObjectState(ObjectKind.TEXT), intent = intent, yields = yields, because = because)

    private fun sections(bubbles: List<Bubble>, state: ObjectState = text, thingIsValue: Boolean = false) =
        actionSections(bubbles, state, thing, thingIsValue, make)

    @Test
    fun `действие ложится к тому, что оно продолжает`() {
        val call = bubble("call", Intent.OPEN, because = phone)
        val read = bubble("ocr", Intent.UNDERSTAND, ActionYield.Same())
        val pdf = bubble("pdf", Intent.PREPARE, ActionYield.New(ObjectKind.PDF))
        val share = bubble("share", Intent.SEND)

        val result = sections(listOf(share, pdf, read, call))

        assertEquals(
            listOf(SectionTopic.VALUE, SectionTopic.UNDERSTAND, SectionTopic.MAKE, SectionTopic.THING),
            result.map { it.topic },
        )
        assertEquals(listOf(call), result[0].items)
        assertEquals(phone.name, result[0].title)
        assertEquals(phone.value, result[0].value)
        assertEquals(UNDERSTAND_TITLE, result[1].title)
        assertEquals(listOf(pdf), result[2].items)
        assertEquals(make, result[2].title)
        assertEquals(listOf(share), result[3].items)
        assertEquals(thing, result[3].title)
    }

    @Test
    fun `действия одного значения собираются в один раздел`() {
        val call = bubble("call", Intent.OPEN, because = phone)
        val sms = bubble("sms", Intent.OPEN, because = phone)
        val contact = bubble("contact", Intent.PREPARE, because = phone)

        val result = sections(listOf(call, bubble("share", Intent.SEND), sms, contact))

        assertEquals(listOf(call, sms, contact), result.first().items)
        assertEquals(2, result.size)
    }

    @Test
    fun `значения идут в порядке, в каком ранжирование ставит их лучшее действие`() {
        val open = bubble("open-url", Intent.OPEN, because = link)
        val call = bubble("call", Intent.OPEN, because = phone)

        assertEquals(listOf(link.key, phone.key), sections(listOf(open, call)).map { it.key })
        assertEquals(listOf(phone.key, link.key), sections(listOf(call, open)).map { it.key })
    }

    /**
     * Поделиться не тонет под свёрткой: вещь дальше и новое из вещи — разные разделы, и выходы
     * самой вещи не сворачиваются, даже когда ранжирование ставит «Поделиться» последним.
     */
    @Test
    fun `поделиться не уходит под свёртку`() {
        val makes = (1..8).map { bubble("make-$it", Intent.PREPARE, ActionYield.New(ObjectKind.PDF)) }
        val exits = listOf("copy", "open-in", "open", "save", "pc").map { bubble(it, Intent.SEND) }
        val share = bubble("share", Intent.SEND)

        val result = sections(makes + exits + share)
        val own = result.first { it.topic == SectionTopic.THING }

        assertTrue(share in own.items.take(shownAtOnce(own)))

        // Превращения по-прежнему сворачиваются: свёртка — против их стены (#879).
        val made = result.first { it.topic == SectionTopic.MAKE }
        assertEquals(LIKELY_COUNT, shownAtOnce(made))
    }

    @Test
    fun `у значения сначала то, чем им пользуются, а исправления после`() {
        val fix = bubble("fix", Intent.UNDERSTAND, ActionYield.Same())

        assertEquals(
            listOf(SectionTopic.VALUE, SectionTopic.UNDERSTAND),
            sections(listOf(fix, bubble("call", Intent.OPEN, because = phone))).map { it.topic },
        )

        // Вещь, которая сама значение: её продолжения и есть продолжения значения.
        assertEquals(
            listOf(SectionTopic.THING, SectionTopic.UNDERSTAND),
            sections(listOf(fix, bubble("call", Intent.OPEN)), thingIsValue = true).map { it.topic },
        )
    }

    @Test
    fun `подсвечено только продолжение найденного`() {
        val call = bubble("call", Intent.OPEN, because = phone)
        val ocr = bubble("ocr", Intent.UNDERSTAND, ActionYield.Same())

        val known = sections(listOf(ocr, call))
        assertTrue(known.first().leads)
        assertFalse(known.drop(1).any { it.leads })

        // У вещи без находок главного действия нет — подсветить значило бы угадать.
        assertFalse(sections(listOf(ocr, bubble("share", Intent.SEND))).any { it.leads })

        assertTrue(sections(listOf(bubble("call", Intent.OPEN)), thingIsValue = true).first().leads)
    }

    @Test
    fun `пустые разделы не показываются`() {
        val result = sections(listOf(bubble("ocr", Intent.UNDERSTAND)))

        assertEquals(listOf(SectionTopic.UNDERSTAND), result.map { it.topic })
    }

    @Test
    fun `empty input yields no sections`() {
        assertTrue(sections(emptyList()).isEmpty())
    }

    @Test
    fun `ничего не спрятано — каждое действие ровно в одном разделе`() {
        val offered = listOf(
            bubble("call", Intent.OPEN, because = phone),
            bubble("open-url", Intent.OPEN, because = link),
            bubble("ocr", Intent.UNDERSTAND),
            bubble("pdf", Intent.PREPARE),
            bubble("share", Intent.SEND),
        )

        val shown = sections(offered).flatMap { it.items }

        assertEquals(offered.size, shown.size)
        assertEquals(offered.toSet(), shown.toSet())
    }

    /** Раздел вещи называет вещь: у таблицы, документа и найденного номера названия разные. */
    @Test
    fun `разделы вещи названы её видом`() {
        fun obj(kind: ObjectKind, mime: String, name: String? = null, features: Set<Feature> = emptySet()) =
            PointObject("o", mime, ScratchRef("/o"), ObjectState(kind, features), listOfNotNull(name?.let { "name" to it }).toMap())

        val kinds = listOf(
            obj(ObjectKind.IMAGE, "image/jpeg"),
            obj(ObjectKind.OFFICE, "application/vnd.ms-excel", "счёт.xls"),
            obj(ObjectKind.OFFICE, "application/msword", "договор.doc"),
            obj(ObjectKind.OFFICE, "application/vnd.ms-powerpoint", "план.ppt", setOf(Feature.IS_PRESENTATION)),
            obj(ObjectKind.URL, "text/uri-list"),
            obj(KIND_PHONE, "text/plain"),
            obj(ObjectKind.UNKNOWN, "application/octet-stream"),
        )

        assertEquals("у двух видов одно имя раздела", kinds.size, kinds.map(::thingTitle).toSet().size)
        assertEquals("у двух видов одно имя нового", kinds.size, kinds.map(::makeTitle).toSet().size)
        kinds.forEach { assertNotEquals(thingTitle(it), makeTitle(it)) }
    }

    @Test
    fun `найденное значение — вещь-значение`() {
        val number = PointObject("n", "text/plain", ValueRef("+380671234567"), ObjectState(KIND_PHONE))
        val photo = PointObject("p", "image/jpeg", ScratchRef("/p"), ObjectState(ObjectKind.IMAGE))

        assertTrue(thingIsValue(number))
        assertFalse(thingIsValue(photo))
    }

    @Test
    fun `короткий раздел показывается целиком`() {

        (0..LIKELY_COUNT + 2).forEach { total ->
            assertEquals("раздел из $total свернулся зря", total, likelyCount(total))
        }
    }

    @Test
    fun `длинный раздел сворачивается до верхних`() {

        assertEquals(LIKELY_COUNT, likelyCount(LIKELY_COUNT + 3))
        assertEquals(LIKELY_COUNT, likelyCount(12))
    }

    @Test
    fun `видно всегда меньше или столько же, сколько есть`() {

        (0..30).forEach { total -> assertTrue(likelyCount(total) in 0..total) }
    }
}
