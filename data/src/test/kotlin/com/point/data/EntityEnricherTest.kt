package com.point.data

import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.META_ENTITY_ADDRESS
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The enricher maps on-device entities to features; ML Kit itself is faked (pure JVM). */
class EntityEnricherTest {

    private fun obj(text: String): PointObject {
        val f = File.createTempFile("ent", ".txt").apply { writeText(text); deleteOnExit() }
        return PointObject("id", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    private fun extractor(vararg entities: Entity) = object : EntityExtractor {
        override suspend fun extract(text: String) = entities.toList()
    }

    @Test
    fun `maps phone and email to features, ignores unhandled types`() = runTest {
        val enricher = EntityEnricher(
            extractor(
                Entity(EntityType.PHONE, "+380671234567"),
                Entity(EntityType.EMAIL, "a@b.com"),
                Entity(EntityType.MONEY, "$5"), // not yet actionable → ignored
            ),
        )
        val features = enricher.enrich(obj("call +380671234567 a@b.com")).features
        assertTrue(Feature.HAS_PHONE in features)
        assertTrue(Feature.HAS_EMAIL in features)
        assertEquals(2, features.size)
    }

    @Test
    fun `maps address, date and card to features`() = runTest {
        val enricher = EntityEnricher(
            extractor(
                Entity(EntityType.ADDRESS, "ул. Крещатик, 12"),
                Entity(EntityType.DATE_TIME, "завтра 18:00"),
                Entity(EntityType.PAYMENT_CARD, "4111 1111 1111 1111"),
            ),
        )
        val features = enricher.enrich(obj("встреча завтра 18:00 ул. Крещатик, 12 карта 4111111111111111")).features
        assertTrue(Feature.HAS_ADDRESS in features)
        assertTrue(Feature.HAS_DATE in features)
        assertTrue(Feature.HAS_CARD in features)
        assertEquals(3, features.size)
    }

    /**
     * Живой баг (#244): на кадре посылки Новой Пошты единственное место «Дата» занимало время
     * статуса — `11:41` из «Прибула до пункту… Сьогодні, 11:41», — вытесняя настоящую дату
     * `30.03`, которая на том же кадре есть. Порядок сущностей от движка обратный нужному,
     * а место одно (`putIfAbsent`), поэтому роль присуждается ранжированием.
     */
    @Test
    fun `календарная дата побеждает отметку времени в роли даты документа`() = runTest {
        val enricher = EntityEnricher(
            extractor(
                Entity(EntityType.DATE_TIME, "11:41"),
                Entity(EntityType.DATE_TIME, "30.03"),
            ),
        )

        val delta = enricher.enrich(obj("Прибула до пункту Сьогоднi, 11:41 Київ - 30.03"))

        assertEquals("30.03", delta.metadata[META_ENTITY_PREFIX + "date"])
        assertTrue(Feature.HAS_DATE in delta.features)
    }

    /**
     * Регрессия, которую дала первая версия правки #244: голое время отсеивалось прямо в
     * `isPlausible`, и вместе с ложной «Датой» пропадал признак `HAS_DATE`, а с ним пузырёк
     * «Создать событие» на заметке, где время и есть содержание (случай #233).
     *
     * Когда календарной даты на кадре нет, отметка времени остаётся датой объекта: это не
     * ложь, а единственное, что движок увидел.
     */
    @Test
    fun `заметка со временем не теряет ни дату, ни признак`() = runTest {
        val enricher = EntityEnricher(extractor(Entity(EntityType.DATE_TIME, "15:12")))

        val delta = enricher.enrich(obj("15:12 Встреча с Петром\nвторой этаж"))

        assertEquals("15:12", delta.metadata[META_ENTITY_PREFIX + "date"])
        assertTrue(Feature.HAS_DATE in delta.features)
    }

    /**
     * Кадр 12 корпуса (#262): на скриншоте карты Новой Пошты адрес отделения стоит в
     * распознанном тексте целиком — «Бритвка, Центральна, 586», — а извлекатель сущностей молчит:
     * улицу здесь называют без слова «вулиця», а дом ставят последним через запятую. Адрес
     * проваливался в пол вместе с единственным действием кадра.
     */
    @Test
    fun `адрес с кадра карты находится и тогда, когда извлекатель молчит`() = runTest {
        val enricher = EntityEnricher(extractor())

        val delta = enricher.enrich(obj("Вддлення 1\n© Бритвка, Центральна, 586\nРобочий час"))

        assertEquals("Бритвка, Центральна, 586", delta.metadata[META_ENTITY_ADDRESS])
        assertTrue("без признака пузырёк карты не появится", Feature.HAS_ADDRESS in delta.features)
    }

    /**
     * Ревью #262: правило формы читает адрес там, где извлекатель молчит, — но молчит он и на
     * таблице. Строка «слово, слово, небольшое число» — это ряд прайса и любая строка CSV, а
     * `text/csv` для Point обычный текстовый объект (разбор таблиц — самое частое, чем его
     * кормят). Ложный адрес отсюда уезжал в «Point понял», в пузырёк карты и в граф отдельным
     * узлом. Ни факта, ни признака на таком тексте быть не должно.
     */
    @Test
    fun `таблица адреса и пузырька карты не рождает`() = runTest {
        val enricher = EntityEnricher(extractor())

        val delta = enricher.enrich(obj("Товар, одиниця, цна\nЦукор, пачка, 25\nХлб, буханка, 32"))

        assertFalse("таблица — не адрес", META_ENTITY_ADDRESS in delta.metadata)
        assertFalse("без факта нет и признака", Feature.HAS_ADDRESS in delta.features)
    }

    @Test
    fun `правило — второй читатель адреса, а не спорщик`() = runTest {
        // Два писателя одного ключа в одной волне обогащения гонялись бы за значение, и человек
        // видел бы то один адрес, то другой. Поэтому правило работает только по молчанию первого.
        val enricher = EntityEnricher(extractor(Entity(EntityType.ADDRESS, "вул. Сонячна, 15")))

        val delta = enricher.enrich(obj("Олексйвка, вул. Сонячна, 15\nБритвка, Центральна, 586"))

        assertEquals("Олексйвка, вул. Сонячна, 15", delta.metadata[META_ENTITY_ADDRESS])
    }

    @Test
    fun `applies only to text objects`() {
        val enricher = EntityEnricher(extractor())
        assertTrue(enricher.appliesTo(ObjectState(ObjectKind.TEXT)))
        assertFalse(enricher.appliesTo(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `blank text yields no features and never calls the extractor`() = runTest {
        val enricher = EntityEnricher(extractor(Entity(EntityType.PHONE, "x")))
        assertTrue(enricher.enrich(obj("   ")).features.isEmpty())
    }

    @Test
    fun `keeps the first value of each entity type as an understood fact`() = runTest {
        val enricher = EntityEnricher(
            extractor(
                Entity(EntityType.PHONE, "+380671234567"),
                Entity(EntityType.PHONE, "+380952222222"), // a second phone — the first one wins
                Entity(EntityType.ADDRESS, "ул. Крещатик, 12"),
            ),
        )
        val meta = enricher.enrich(obj("звони +380671234567 или +380952222222, адрес ул. Крещатик, 12")).metadata
        assertEquals("+380671234567", meta[META_ENTITY_PREFIX + "phone"])
        assertEquals("ул. Крещатик, 12", meta[META_ENTITY_PREFIX + "address"])
    }
}
