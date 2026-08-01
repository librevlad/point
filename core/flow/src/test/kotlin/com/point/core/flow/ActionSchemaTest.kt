package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Полнота считается по действию (#260, design v3 §6): у действия есть критические поля,
 * готовность бинарна, и «не хватает только X» называет только то, без чего действие не
 * работает. «Заполнено 6 из 9» — красивая ложь, и её здесь никто не считает.
 */
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
        // Перевозчик и дата прочитаны, трека нет: «не хватает только трек-номер», а не
        // список всего незаполненного — иначе это та же форма из девяти полей.
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
    fun `второй номер страницы (more) виден на поле той же строкой «или»`() {
        // Спор о чтении и второй настоящий номер — для человека один вопрос: «а не то ли это?»
        val facts = mapOf(
            "entity.track" to "20 4514 9154 9395",
            "entity.track.more" to altValue(listOf("20 4514 9154 9395", "20451491549396")),
        )

        val r = track.readiness(facts) as Readiness.Ready

        assertEquals(listOf("20451491549396"), r.present.single().alternatives)
    }

    @Test
    fun `дайджест показывает только действия, к которым документ имеет отношение`() {
        // На скрине посылки нет телефона — «Сохранить контакт» не показывается вовсе,
        // даже как «не готово»: пустой опросник с минусами тем же самым опросником и остался бы.
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
        // Ревью #260: entity.date пишется почти на каждом скриншоте («18:24» переписки — тоже
        // дата, #244), и гейт «хоть одно поле» звал «Отследить отправление» на любой чат.
        assertTrue(actionReadiness(mapOf(META_ENTITY_PREFIX + "date" to "18:24")).isEmpty())
    }

    @Test
    fun `адрес карточку не зовёт ни одному действию — ни контакту, ни маршруту`() {
        // Инвариант #260 дословно, и #262 его НЕ отменяет. Соблазн был сделать адрес якорем
        // маршрута («место и есть предмет маршрута») — щуп ревью показал цену: чек, письмо,
        // счёт и карточка посылки получали «Построить маршрут» все четверо. Адрес упомянут
        // почти везде, а якорь означает «документ ПРО это действие».
        assertTrue(
            "адрес на счёте — место, а не «сохраните контакт» и не «поехали туда»",
            actionReadiness(mapOf(META_ENTITY_PREFIX + "address" to "вул. Хрещатик, 1")).isEmpty(),
        )
    }

    @Test
    fun `адрес на посылке не превращает её в маршрут`() {
        // Тот же щуп на гибридном входе: у карточки Новой Пошты есть и трек, и адрес отделения.
        val rows = actionReadiness(
            mapOf(
                META_ENTITY_TRACK to "20 4514 9154 9395",
                META_ENTITY_PREFIX + "address" to "Відділення №9, Олексіївка",
            ),
        )

        assertEquals(listOf("track-parcel"), rows.map { it.schema.id })
    }

    // --- #262: маршрут и счётчик — действия, которые корпус ждал ---

    @Test
    fun `маршрут готов и по адресу, и по координатам — требование одно, чтений два`() {
        // Готовность считается схемой напрямую: якорь решает, показывать ли строку, а не
        // готово ли действие, — и метрика корпуса ходит именно этой дверью ([scoreCorpus]).
        val route = ACTION_SCHEMAS.single { it.id == "route" }

        val byAddress = route.readiness(mapOf(META_ENTITY_PREFIX + "address" to "Відділення №9, Олексіївка"))
        val byPoint = route.readiness(mapOf(META_ENTITY_GEO to "50.4501, 30.5234"))

        assertTrue(byAddress is Readiness.Ready)
        assertTrue(byPoint is Readiness.Ready)
        // Координаты карточку зовут: пара чисел ничем, кроме точки на карте, не бывает.
        assertTrue(
            actionReadiness(mapOf(META_ENTITY_GEO to "50.4501, 30.5234"))
                .single { it.schema.id == "route" }.readiness is Readiness.Ready,
        )
    }

    @Test
    fun `маршрута без места нет, и «не хватает» называет оба чтения одной строкой`() {
        // «не хватает только адрес, координаты» читалось бы как «нужны оба» — это ложь про
        // требование, у которого два законных чтения.
        val route = ACTION_SCHEMAS.single { it.id == "route" }

        val r = route.readiness(mapOf(META_ENTITY_PREFIX + "date" to "29.07"))

        assertTrue(r is Readiness.Missing)
        assertEquals(listOf("адрес или координаты"), (r as Readiness.Missing).missing.map { it.label })
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
        // Единица без числа — подпись на трубе, а не показание; дата счётчика не касается.
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
        // Замена, указывающая в пустоту, тихо перестала бы закрывать требование, и действие
        // навсегда осталось бы неготовым — молча.
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
}
