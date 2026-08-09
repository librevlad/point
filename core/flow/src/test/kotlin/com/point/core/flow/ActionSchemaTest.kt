package com.point.core.flow

import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSchemaTest {

    private val track = ActionSchema(
        id = "t",
        label = "Отследить отправление",
        fields = listOf(
            FieldSpec("entity.track", "трек-номер", critical = true),
            FieldSpec("graph.role.carrier", "перевозчик"),
            FieldSpec("entity.date", "дата"),
        ),
    )

    @Test
    fun `критическое поле есть — действие готово, побочные не мешают`() {
        val r = track.readiness(mapOf("entity.track" to "20 4514 9154 9395"))

        assertTrue(r is Readiness.Ready)
        assertEquals("20 4514 9154 9395", (r as Readiness.Ready).present.single().value)
    }

    @Test
    fun `критического нет — не готово, и не хватает называется только критическое`() {

        val r = track.readiness(mapOf("graph.role.carrier" to "Нова Пошта", "entity.date" to "29.07"))

        assertTrue(r is Readiness.Missing)
        assertEquals(listOf("трек-номер"), (r as Readiness.Missing).missing.map { it.label })
        assertEquals(2, r.present.size)
    }

    @Test
    fun `пустое значение — не значение`() {
        assertTrue(track.readiness(mapOf("entity.track" to "  ")) is Readiness.Missing)
    }

    @Test
    fun `спорное чтение видно на поле — готовность не прячет спор`() {
        val facts = mapOf(
            "entity.track" to "20 4514 9154 9395",
            "entity.track.alt" to altValue(listOf("20 4514 9154 9395", "20 4514 9154 9396")),
        )

        val r = track.readiness(facts) as Readiness.Ready

        assertEquals(listOf("20 4514 9154 9396"), r.present.single().alternatives)
    }

    @Test
    fun `одно доказательство — предположение и оно видно, не судили — не врём`() {
        val judged = track.readiness(
            mapOf("entity.track" to "99 9999 9999 9995", "entity.track.ev" to "semantic"),
        ) as Readiness.Ready
        val confirmed = track.readiness(
            mapOf("entity.track" to "20 4514 9154 9395", "entity.track.ev" to "semantic,geometric"),
        ) as Readiness.Ready
        val unjudged = track.readiness(mapOf("entity.track" to "20 4514 9154 9395")) as Readiness.Ready

        assertTrue(judged.present.single().assumption)
        assertFalse(confirmed.present.single().assumption)
        assertFalse("улики не считались — маркера нет", unjudged.present.single().assumption)
    }

    @Test
    fun `второй номер страницы (more) — «ещё», а не «или» — это другой объект`() {

        // Живой прогон S6 (2026-08-09): «ещё значения» вида — другие объекты,
        // не спор прочтений одного. Раньше more показывались строкой «или».
        val facts = mapOf(
            "entity.track" to "20 4514 9154 9395",
            "entity.track.more" to altValue(listOf("20 4514 9154 9395", "20451491549396")),
        )

        val r = track.readiness(facts) as Readiness.Ready

        assertTrue(r.present.single().alternatives.isEmpty())
        assertEquals(listOf("20451491549396"), r.present.single().extras)
    }

    @Test
    fun `дайджест показывает только действия, к которым документ имеет отношение`() {

        val rows = actionReadiness(mapOf(META_ENTITY_TRACK to "20 4514 9154 9395"))

        assertEquals(listOf("track-parcel"), rows.map { it.schema.id })
        assertTrue(rows.single().readiness is Readiness.Ready)
    }

    @Test
    fun `неготовое действие видно, когда прочитано его якорное поле`() {
        val rows = actionReadiness(mapOf(META_GRAPH_ROLE_PREFIX + "carrier" to "Нова Пошта"))

        val parcel = rows.single { it.schema.id == "track-parcel" }
        assertTrue(parcel.readiness is Readiness.Missing)
    }

    @Test
    fun `универсальное поле карточку не зовёт — чат с таймстемпом не про посылку`() {

        assertTrue(actionReadiness(mapOf(META_ENTITY_PREFIX + "date" to "18:24")).isEmpty())
    }

    @Test
    fun `адрес карточку не зовёт ни одному действию — ни контакту, ни маршруту`() {

        assertTrue(
            "адрес на счёте — место, а не «сохраните контакт» и не «поехали туда»",
            actionReadiness(mapOf(META_ENTITY_PREFIX + "address" to "вул. Хрещатик, 1")).isEmpty(),
        )
    }

    @Test
    fun `адрес на посылке не превращает её в маршрут`() {

        val rows = actionReadiness(
            mapOf(
                META_ENTITY_TRACK to "20 4514 9154 9395",
                META_ENTITY_PREFIX + "address" to "Відділення №9, Олексіївка",
            ),
        )

        assertEquals(listOf("track-parcel"), rows.map { it.schema.id })
    }

    @Test
    fun `маршрут готов и по адресу, и по координатам — требование одно, чтений два`() {

        val route = ACTION_SCHEMAS.single { it.id == "route" }

        val byAddress = route.readiness(mapOf(META_ENTITY_PREFIX + "address" to "Відділення №9, Олексіївка"))
        val byPoint = route.readiness(mapOf(META_ENTITY_GEO to "50.4501, 30.5234"))

        assertTrue(byAddress is Readiness.Ready)
        assertTrue(byPoint is Readiness.Ready)

        assertTrue(
            actionReadiness(mapOf(META_ENTITY_GEO to "50.4501, 30.5234"))
                .single { it.schema.id == "route" }.readiness is Readiness.Ready,
        )
    }

    @Test
    fun `маршрута без места нет, и «не хватает» называет оба чтения одной строкой`() {

        val route = ACTION_SCHEMAS.single { it.id == "route" }

        val r = route.readiness(mapOf(META_ENTITY_PREFIX + "date" to "29.07"))

        assertTrue(r is Readiness.Missing)
        assertEquals(listOf("адрес, координаты или место"), (r as Readiness.Missing).missing.map { it.label })
    }

    @Test
    fun `показание счётчика готово по самому показанию — единица весит почти ноль`() {
        val rows = actionReadiness(
            mapOf(META_ENTITY_METER to "20842", META_ENTITY_METER_UNIT to "кВт·ч"),
        )

        val meter = rows.single { it.schema.id == "meter-reading" }
        assertTrue(meter.readiness is Readiness.Ready)
        assertEquals("20842", (meter.readiness as Readiness.Ready).present.single { it.spec.critical }.value)
    }

    @Test
    fun `якорь счётчика не срабатывает на чужом документе`() {

        assertTrue(actionReadiness(mapOf(META_ENTITY_METER_UNIT to "м³")).isEmpty())
        assertTrue(
            actionReadiness(mapOf(META_ENTITY_TRACK to "20 4514 9154 9395"))
                .none { it.schema.id == "meter-reading" || it.schema.id == "route" },
        )
    }

    @Test
    fun `без показания действие не готово, и не хватает именно показания`() {
        val meter = ACTION_SCHEMAS.single { it.id == "meter-reading" }

        val r = meter.readiness(mapOf(META_ENTITY_METER_UNIT to "кВт·ч", META_ENTITY_PREFIX + "date" to "29.07"))

        assertTrue(r is Readiness.Missing)
        assertEquals(listOf("показание"), (r as Readiness.Missing).missing.map { it.label })
    }

    @Test
    fun `замена объявлена только на критическое поле своей же схемы`() {

        ACTION_SCHEMAS.forEach { schema ->
            schema.fields.mapNotNull { it.insteadOf }.forEach { key ->
                val target = schema.fields.singleOrNull { it.key == key }
                assertTrue("${schema.id}: замена ссылается на несуществующее поле $key", target != null)
                assertTrue("${schema.id}: заменять можно только критическое поле", target!!.critical)
            }
        }
    }

    @Test
    fun `почта — якорь контакта, письмо без телефона зовёт «Сохранить контакт»`() {
        val rows = actionReadiness(mapOf(META_ENTITY_PREFIX + "email" to "olena@example.com"))

        val contact = rows.single { it.schema.id == "save-contact" }
        assertTrue(contact.readiness is Readiness.Missing)
    }

    @Test
    fun `пустой документ не получает ни одной строки готовности`() {
        assertTrue(actionReadiness(emptyMap()).isEmpty())
    }

    @Test
    fun `контакт готов по телефону — почта и адрес весят почти ноль`() {
        val rows = actionReadiness(mapOf(META_ENTITY_PREFIX + "phone" to "+380671234567"))

        val contact = rows.single { it.schema.id == "save-contact" }
        assertTrue(contact.readiness is Readiness.Ready)
    }

    @Test
    fun `в каждой схеме есть критическое поле — схема без критического всегда готова и лжёт`() {
        ACTION_SCHEMAS.forEach { schema ->
            assertTrue("${schema.id} должна иметь критическое поле", schema.fields.any { it.critical })
        }
    }

    @Test
    fun `ключи полей — ключи метаданных, а не собственный мир значений`() {
        val known = listOf(META_ENTITY_PREFIX, META_GRAPH_ROLE_PREFIX)
        ACTION_SCHEMAS.flatMap { it.fields }.forEach { field ->
            assertTrue(
                "${field.key} должен быть фактом объекта",
                known.any { field.key.startsWith(it) },
            )
        }
    }

    @Test
    fun `место назначения закрывает маршрут наравне с адресом и координатами`() {

        val rows = actionReadiness(mapOf(META_ENTITY_PLACE to "Відділення №21"))

        val route = rows.single { it.schema.id == "route" }
        assertTrue(route.readiness is Readiness.Ready)
    }

    @Test
    fun `место — якорь, а адрес нет`() {

        val route = ACTION_SCHEMAS.single { it.id == "route" }

        assertTrue(route.fields.single { it.key == META_ENTITY_PLACE }.anchor)
        assertFalse(route.fields.single { it.key.endsWith("address") }.anchor)
    }

    @Test
    fun `перевод готов по карте и сумме — двух критических полей ровно два`() {
        val rows = actionReadiness(
            mapOf(
                META_ENTITY_PREFIX + "card" to "4111 1111 1111 1111",
                META_ENTITY_AMOUNT to "300",
                META_ENTITY_AMOUNT_CURRENCY to "грн",
            ),
        )

        val pay = rows.single { it.schema.id == "pay-by-requisites" }
        assertTrue(pay.readiness is Readiness.Ready)
    }

    @Test
    fun `карта без суммы — не перевод, и не хватает именно суммы`() {

        val pay = ACTION_SCHEMAS.single { it.id == "pay-by-requisites" }

        val r = pay.readiness(mapOf(META_ENTITY_PREFIX + "card" to "4111 1111 1111 1111"))

        assertTrue(r is Readiness.Missing)
        assertEquals(listOf("сумма"), (r as Readiness.Missing).missing.map { it.label })
    }

    @Test
    fun `сумма без карты — не перевод, и не хватает именно карты`() {
        val pay = ACTION_SCHEMAS.single { it.id == "pay-by-requisites" }

        val r = pay.readiness(mapOf(META_ENTITY_AMOUNT to "300", META_ENTITY_AMOUNT_CURRENCY to "грн"))

        assertTrue(r is Readiness.Missing)
        assertEquals(listOf("карта"), (r as Readiness.Missing).missing.map { it.label })
    }

    @Test
    fun `сумма карточку не зовёт — цена на чеке не просьба перевести`() {

        assertTrue(
            actionReadiness(mapOf(META_ENTITY_AMOUNT to "500", META_ENTITY_AMOUNT_CURRENCY to "грн"))
                .isEmpty(),
        )
    }

    @Test
    fun `квитанция готова и по номеру, и по ссылке — требование одно, чтений два`() {
        val receipt = ACTION_SCHEMAS.single { it.id == "forward-receipt" }

        val byNumber = receipt.readiness(mapOf(META_ENTITY_RECEIPT to "AB12-CD34-EF56-GH78"))
        val byLink = receipt.readiness(mapOf(META_ENTITY_PREFIX + "url" to "https://check.bank.example/p/NaXzz"))

        assertTrue(byNumber is Readiness.Ready)
        assertTrue(byLink is Readiness.Ready)
    }

    @Test
    fun `без квитанции и ссылки действие не готово, и строка называет оба чтения`() {
        val receipt = ACTION_SCHEMAS.single { it.id == "forward-receipt" }

        val r = receipt.readiness(mapOf(META_ENTITY_AMOUNT to "500"))

        assertTrue(r is Readiness.Missing)
        assertEquals(
            listOf("номер квитанции или ссылка на квитанцию"),
            (r as Readiness.Missing).missing.map { it.label },
        )
    }

    @Test
    fun `ссылка карточку не зовёт — ссылка есть на каждом втором скриншоте`() {
        assertTrue(
            actionReadiness(mapOf(META_ENTITY_PREFIX + "url" to "https://anyimage.io/contact.php"))
                .none { it.schema.id == "forward-receipt" },
        )

        assertTrue(
            actionReadiness(mapOf(META_ENTITY_RECEIPT to "AB12-CD34-EF56-GH78"))
                .single { it.schema.id == "forward-receipt" }.readiness is Readiness.Ready,
        )
    }

    @Test
    fun `ответить готово по адресу почты — черновик полем схемы не является`() {
        val reply = ACTION_SCHEMAS.single { it.id == "reply" }

        assertTrue(reply.readiness(mapOf(META_ENTITY_PREFIX + "email" to "liz@example.com")) is Readiness.Ready)
    }

    @Test
    fun `без адреса отвечать некуда, и не хватает именно адреса`() {
        val reply = ACTION_SCHEMAS.single { it.id == "reply" }

        val r = reply.readiness(mapOf(META_ENTITY_SUBJECT to "Refund for service"))

        assertTrue(r is Readiness.Missing)
        assertEquals(listOf("адрес почты"), (r as Readiness.Missing).missing.map { it.label })
    }

    @Test
    fun `карточку ответа зовёт тема, а не почта`() {

        assertTrue(
            actionReadiness(mapOf(META_ENTITY_PREFIX + "email" to "olena@example.com"))
                .none { it.schema.id == "reply" },
        )
        val rows = actionReadiness(
            mapOf(
                META_ENTITY_SUBJECT to "Refund for service",
                META_ENTITY_PREFIX + "email" to "liz@example.com",
            ),
        )
        assertTrue(rows.single { it.schema.id == "reply" }.readiness is Readiness.Ready)
    }

    @Test
    fun `номер карты на экране показывается хвостом, а значение остаётся дословным`() {

        val pay = ACTION_SCHEMAS.single { it.id == "pay-by-requisites" }
        val r = pay.readiness(
            mapOf(META_ENTITY_PREFIX + "card" to "4111 1111 1111 1111", META_ENTITY_AMOUNT to "300"),
        ) as Readiness.Ready

        val card = r.present.single { it.spec.key.endsWith("card") }
        assertEquals("4111 1111 1111 1111", card.value)
        assertEquals("•• 1111", maskedForScreen(card.spec.key, card.value))
        assertEquals("300", maskedForScreen(META_ENTITY_AMOUNT, "300"))
    }

    @Test
    fun `без всякого «куда» маршрут не готов, и строка называет все чтения`() {
        val r = ACTION_SCHEMAS.single { it.id == "route" }
            .readiness(mapOf(META_ENTITY_PREFIX + "date" to "29.07")) as Readiness.Missing

        val labels = r.missing.joinToString(" ") { it.label }
        assertTrue(labels.contains("адрес"))
        assertTrue(labels.contains("координаты") || labels.contains("место"))
    }

    private fun bubbleOf(id: String) = Bubble(
        icon = id,
        title = id,
        capabilityId = CapabilityId(id),
        expectedNextState = ObjectState(ObjectKind.IMAGE),
    )

    @Test
    fun `готовая строка запускается тем же пузырём, что стоит в списке действий`() {

        val row = actionReadiness(mapOf(META_ENTITY_PREFIX + "phone" to "+380504327707"))
            .single { it.schema.id == "save-contact" }

        val runner = row.runner(listOf(bubbleOf("map"), bubbleOf("save-contact")))

        assertEquals(CapabilityId("save-contact"), runner?.capabilityId)
    }

    @Test
    fun `действия объекту не предложили — строка кнопкой не притворяется`() {

        val row = actionReadiness(mapOf(META_ENTITY_GEO to "50.4501, 30.5234"))
            .single { it.schema.id == "route" }

        assertTrue(row.readiness is Readiness.Ready)
        assertNull(row.runner(emptyList()))
        assertEquals(CapabilityId("map"), row.runner(listOf(bubbleOf("map")))?.capabilityId)
    }

    @Test
    fun `неготовая строка остаётся раскрытием «чего не хватает», а не запуском`() {
        val row = actionReadiness(mapOf(META_ENTITY_PREFIX + "email" to "olena@example.com"))
            .single { it.schema.id == "save-contact" }

        assertTrue(row.readiness is Readiness.Missing)
        assertNull(row.runner(listOf(bubbleOf("save-contact"))))
    }

    @Test
    fun `действие без реализации кнопкой не становится — глагол без действия и был находкой`() {

        val tails = setOf("track-parcel", "meter-reading", "pay-by-requisites")
        val all = ACTION_SCHEMAS.map { it.id to it.runs }.toMap()

        assertEquals(tails, all.filterValues { it == null }.keys)
        val parcel = actionReadiness(mapOf(META_ENTITY_TRACK to "20 4514 9154 9395")).single()
        assertTrue(parcel.readiness is Readiness.Ready)
        assertNull(parcel.runner(listOf(bubbleOf("share"), bubbleOf("copy"))))
    }

    @Test
    fun `альтернатива, неотличимая от значения, спором не показывается`() {

        // Живой прогон 2026-08-09: «дата — или: 26.04.2026 26.04.2026» — спор
        // одинаковых на взгляд значений. Равенство меряется той же нормализацией,
        // что и merge, а не буквальной строкой.
        val row = actionReadiness(
            mapOf(
                META_ENTITY_RECEIPT to "PPA5-0M79",
                META_ENTITY_PREFIX + "date" to "26.04.2026",
                META_ENTITY_PREFIX + "date" + META_ALT_SUFFIX to "26.04.2026 ",
            ),
        ).single { it.schema.id == "forward-receipt" }

        val date = (row.readiness as Readiness.Ready).present.single { it.spec.key.endsWith("date") }
        assertTrue("хвост-пробел — не второе прочтение", date.alternatives.isEmpty())
    }

    @Test
    fun `дата с временем того же дня — та же дата, а не ещё одна`() {

        // Скрин владельца 2026-08-09: «дата — ещё: 26.04.2026 20:04» при значении
        // «26.04.2026». Даты равны по календарному дню; слипшееся «26.04.2026
        // 26.04.2026» из старого знания тоже гаснет — его день совпадает.
        val row = actionReadiness(
            mapOf(
                META_ENTITY_RECEIPT to "PPA5-0M79",
                META_ENTITY_PREFIX + "date" to "26.04.2026",
                META_ENTITY_PREFIX + "date" + META_ALT_SUFFIX to "26.04.2026 26.04.2026",
                META_ENTITY_PREFIX + "date" + META_MORE_SUFFIX to altValue(listOf("26.04.2026 20:04")),
            ),
        ).single { it.schema.id == "forward-receipt" }

        val date = (row.readiness as Readiness.Ready).present.single { it.spec.key.endsWith("date") }
        assertTrue("тот же день — не спор: ${date.alternatives}", date.alternatives.isEmpty())
        assertTrue("тот же день — не «ещё»: ${date.extras}", date.extras.isEmpty())
    }

    @Test
    fun `другой день остаётся честным спором`() {
        val row = actionReadiness(
            mapOf(
                META_ENTITY_RECEIPT to "PPA5-0M79",
                META_ENTITY_PREFIX + "date" to "26.04.2026",
                META_ENTITY_PREFIX + "date" + META_ALT_SUFFIX to "28.04.2026",
            ),
        ).single { it.schema.id == "forward-receipt" }

        val date = (row.readiness as Readiness.Ready).present.single { it.spec.key.endsWith("date") }
        assertEquals(listOf("28.04.2026"), date.alternatives)
    }

    @Test
    fun `значение из спора не повторяется «ещё»-значением`() {

        // #652 (кейс 24): «телефон — или: №2, №3» и «телефон — ещё: №2, №3» — одни
        // и те же номера двумя списками. Спор и «ещё» не пересекаются.
        val row = actionReadiness(
            mapOf(
                META_ENTITY_PREFIX + "phone" to "+380671111111",
                META_ENTITY_PREFIX + "phone" + META_ALT_SUFFIX to "+380672222222",
                META_ENTITY_PREFIX + "phone" + META_MORE_SUFFIX to
                    altValue(listOf("+380672222222", "+380673333333")),
            ),
        ).single { it.schema.id == "save-contact" }

        val phone = (row.readiness as Readiness.Ready).present.single()
        assertEquals(listOf("+380672222222"), phone.alternatives)
        assertEquals(listOf("+380673333333"), phone.extras)
    }

    @Test
    fun `или-ещё под действием — только про собственное значение действия`() {

        // Скрин владельца 2026-08-09: один спор даты печатался под тремя действиями —
        // «это непонятно и неюзабельно». Спор вспомогательного поля живёт на узле.
        val present = listOf(
            FieldReading(FieldSpec("entity.card", "карта", critical = true), "•• 5427", alternatives = listOf("•• 7189")),
            FieldReading(FieldSpec("entity.date", "дата"), "26.04.2026", alternatives = listOf("28.04.2026")),
            FieldReading(FieldSpec("entity.amount", "сумма", critical = true), "500", extras = listOf("300")),
        )

        assertEquals(listOf("entity.card"), ownDisputes(present).map { it.spec.key })
        assertEquals(listOf("entity.amount"), ownExtras(present).map { it.spec.key })
    }

    @Test
    fun `переслать квитанцию — настоящая дверь, объект уходит шарингом`() {

        // Живой прогон 2026-08-09: «✓ Переслать квитанцию PPA5…» обещал готовое,
        // а двери не было. Пересылка квитанции = поделиться самим объектом.
        assertEquals(CapabilityId("share"), ACTION_SCHEMAS.single { it.id == "forward-receipt" }.runs)

        val row = actionReadiness(mapOf(META_ENTITY_RECEIPT to "PPA5-0M79-APX4-5X6H"))
            .single { it.schema.id == "forward-receipt" }
        assertEquals("share", row.runner(listOf(bubbleOf("share")))?.capabilityId?.value)
    }

    @Test
    fun `объявленное действие названо идентификатором возможности, а не именем схемы`() {

        assertEquals(CapabilityId("email"), ACTION_SCHEMAS.single { it.id == "reply" }.runs)
        assertEquals(CapabilityId("map"), ACTION_SCHEMAS.single { it.id == "route" }.runs)
    }

    @Test
    fun `карточка готовности называет ключевое значение — по нему и узнаётся дубль`() {

        val shown = readinessShownFacts(
            mapOf(
                META_ENTITY_PREFIX + "phone" to "+380 67 123 45 67",
                META_ENTITY_PREFIX + "email" to "olena@tihiy-dvor.example",
                META_ENTITY_PREFIX + "address" to "Київ, вулиця Ярославська, 14",
            ),
        )

        assertEquals(mapOf(META_ENTITY_PREFIX + "phone" to "+380 67 123 45 67"), shown)
    }

    @Test
    fun `неготовое действие ничего не называет — прятать ниже нечего`() {

        assertTrue(readinessShownFacts(mapOf(META_GRAPH_ROLE_PREFIX + "carrier" to "Нова Пошта")).isEmpty())
        assertTrue(readinessShownFacts(emptyMap()).isEmpty())
    }

    @Test
    fun `названо ровно одно значение на готовое действие — побочные поля не прячутся`() {

        val shown = readinessShownFacts(
            mapOf(
                META_ENTITY_PREFIX + "card" to "4111 1111 1111 1111",
                META_ENTITY_AMOUNT to "300",
            ),
        )

        assertEquals(mapOf(META_ENTITY_PREFIX + "card" to "4111 1111 1111 1111"), shown)
    }

    @Test
    fun `названное значение — то же самое, что печатает строка карточки`() {

        val facts = mapOf(META_ENTITY_TRACK to "20 4514 9154 9395", META_GRAPH_ROLE_PREFIX + "carrier" to "Нова Пошта")
        val row = actionReadiness(facts).single { it.schema.id == "track-parcel" }

        assertEquals(row.shownField()?.value, readinessShownFacts(facts)[META_ENTITY_TRACK])
        assertNull(
            "у неготовой строки печатать нечего",
            actionReadiness(mapOf(META_GRAPH_ROLE_PREFIX + "carrier" to "Нова Пошта")).single().shownField(),
        )
    }
}
