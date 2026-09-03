package com.point.data

import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.META_ENTITY_ADDRESS
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.model.Feature
import com.point.core.model.ActionResult
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

class EntityInvestigationTest {

    private fun obj(text: String): PointObject {
        val f = File.createTempFile("ent", ".txt").apply { writeText(text); deleteOnExit() }
        return PointObject("id", "text/plain", ScratchRef(f.absolutePath), ObjectState(ObjectKind.TEXT))
    }

    private fun extractor(vararg entities: Entity) = object : EntityExtractor {
        override suspend fun extract(text: String) = entities.toList()
    }

    @Test
    fun `maps phone and email to features, ignores unhandled types`() = runTest {
        val enricher = EntityInvestigationRealizer(
            extractor(
                Entity(EntityType.PHONE, "+380671234567"),
                Entity(EntityType.EMAIL, "a@b.com"),
                Entity(EntityType.MONEY, "$5"),
            ),
        )
        val features = enricher.look(obj("call +380671234567 a@b.com")).features
        assertTrue(Feature.HAS_PHONE in features)
        assertTrue(Feature.HAS_EMAIL in features)
        assertEquals(2, features.size)
    }

    @Test
    fun `maps address, date and card to features`() = runTest {
        val enricher = EntityInvestigationRealizer(
            extractor(
                Entity(EntityType.ADDRESS, "ул. Крещатик, 12"),
                Entity(EntityType.DATE_TIME, "завтра 18:00"),
                Entity(EntityType.PAYMENT_CARD, "4111 1111 1111 1111"),
            ),
        )
        val features = enricher.look(obj("встреча завтра 18:00 ул. Крещатик, 12 карта 4111111111111111")).features
        assertTrue(Feature.HAS_ADDRESS in features)
        assertTrue(Feature.HAS_DATE in features)
        assertTrue(Feature.HAS_CARD in features)
        assertEquals(3, features.size)
    }

    @Test
    fun `календарная дата побеждает отметку времени в роли даты документа`() = runTest {
        val enricher = EntityInvestigationRealizer(
            extractor(
                Entity(EntityType.DATE_TIME, "11:41"),
                Entity(EntityType.DATE_TIME, "30.03"),
            ),
        )

        val delta = enricher.look(obj("Прибула до пункту Сьогоднi, 11:41 Київ - 30.03"))

        assertEquals("30.03", delta.metadata[META_ENTITY_PREFIX + "date"])
        assertTrue(Feature.HAS_DATE in delta.features)
    }

    @Test
    fun `голое время — не дата вовсе, ни фактом, ни признаком`() = runTest {
        // Решение владельца 2026-08-09 (#651): «голое время это никогда не дата,
        // это мусор» — прежний компромисс «знание без узла» (#244) отменён.
        val enricher = EntityInvestigationRealizer(extractor(Entity(EntityType.DATE_TIME, "15:12")))

        val delta = enricher.look(obj("15:12 Встреча с Петром\nвторой этаж"))

        assertEquals(null, delta.metadata[META_ENTITY_PREFIX + "date"])
        assertTrue(Feature.HAS_DATE !in delta.features)
    }

    @Test
    fun `таймстамп переписки — мусор целиком, а не знание без узла`() = runTest {

        // Кадр 03 корпуса- «18:02» у сообщения; решение владельца (#651) строже #244.
        val enricher = EntityInvestigationRealizer(extractor(Entity(EntityType.DATE_TIME, "18:02")))

        val delta = enricher.look(obj("Добрый день 18:02\nСообщение..."))

        assertEquals(null, delta.metadata[META_ENTITY_PREFIX + "date"])
        assertTrue(Feature.HAS_DATE !in delta.features)
        assertTrue(delta.objects.none { it.state.kind == com.point.core.flow.KIND_DATE })
    }

    @Test
    fun `вчера из хрома переписки не рождает объект даты`() = runTest {

        // Кадр 02 корпуса- дословно из фикстуры neg_viber.
        val enricher = EntityInvestigationRealizer(extractor(Entity(EntityType.DATE_TIME, "вчера")))

        val delta = enricher.look(obj("Ремкомплекты отправил\nвчера.\n10:00"))

        assertTrue(delta.objects.none { it.state.kind == com.point.core.flow.KIND_DATE })
    }

    @Test
    fun `фокус на таймстампе — тоже мусор, а не дата области`() {

        // Кадр 11 корпуса- «11:41» из шапки скриншота; правило владельца (#651).
        val delta = focusedDelta(
            obj("Прибула до пункту Сьогоднi, 11:41"),
            listOf(Entity(EntityType.DATE_TIME, "11:41")),
            at = "0,0,10,10",
        )

        assertEquals(null, delta.metadata[META_ENTITY_PREFIX + "date"])
        assertTrue(delta.objects.isEmpty())
    }

    @Test
    fun `один день — один узел даты, побеждает значение с временем`() = runTest {

        // #660 (решение владельца 2026-08-09): на чеке рождались два узла одного дня —
        // «26.04.2026» и «26.04.2026 20:04».
        val enricher = EntityInvestigationRealizer(
            extractor(
                Entity(EntityType.DATE_TIME, "26.04.2026"),
                Entity(EntityType.DATE_TIME, "26.04.2026 20:04"),
            ),
        )

        val delta = enricher.look(obj("Квитанція від 26.04.2026\nДата операції 26.04.2026 20:04"))

        val dates = delta.objects.filter { it.state.kind == com.point.core.flow.KIND_DATE }
        assertEquals("одна дата — один узел: " + dates.map { it.uri.value }, 1, dates.size)
        assertEquals("26.04.2026 20:04", dates.single().uri.value)
    }

    @Test
    fun `разные дни остаются разными узлами`() = runTest {
        val enricher = EntityInvestigationRealizer(
            extractor(
                Entity(EntityType.DATE_TIME, "26.04.2026"),
                Entity(EntityType.DATE_TIME, "28.04.2026"),
            ),
        )

        val delta = enricher.look(obj("з 26.04.2026 по 28.04.2026"))

        assertEquals(2, delta.objects.count { it.state.kind == com.point.core.flow.KIND_DATE })
    }

    @Test
    fun `чужая товарная строка адресом не становится`() = runTest {

        // #632, решение владельца: «проверять правдоподобие адреса». ML Kit звал
        // адресом строку товара из акта, а расширение до строки усиливало ошибку.
        val enricher = EntityInvestigationRealizer(
            extractor(Entity(EntityType.ADDRESS, "Розчинник Уайт-Спірит ХімРезерв 1л")),
        )

        val delta = enricher.look(obj("Акт передачі\nРозчинник Уайт-Спірит ХімРезерв 1л\nКількість: 2"))

        assertEquals(null, delta.metadata[META_ENTITY_ADDRESS])
        assertFalse("догадка разметчика — не признак адреса", Feature.HAS_ADDRESS in delta.features)
    }

    @Test
    fun `адрес с кадра карты находится и тогда, когда извлекатель молчит`() = runTest {
        val enricher = EntityInvestigationRealizer(extractor())

        val delta = enricher.look(obj("Вддлення 1\n© Бритвка, Центральна, 586\nРобочий час"))

        assertEquals("Бритвка, Центральна, 586", delta.metadata[META_ENTITY_ADDRESS])
        assertTrue("без признака пузырёк карты не появится", Feature.HAS_ADDRESS in delta.features)
    }

    @Test
    fun `таблица адреса и пузырька карты не рождает`() = runTest {
        val enricher = EntityInvestigationRealizer(extractor())

        val delta = enricher.look(obj("Товар, одиниця, цна\nЦукор, пачка, 25\nХлб, буханка, 32"))

        assertFalse("таблица — не адрес", META_ENTITY_ADDRESS in delta.metadata)
        assertFalse("без факта нет и признака", Feature.HAS_ADDRESS in delta.features)
    }

    @Test
    fun `правило — второй читатель адреса, а не спорщик`() = runTest {

        val enricher = EntityInvestigationRealizer(extractor(Entity(EntityType.ADDRESS, "вул. Сонячна, 15")))

        val delta = enricher.look(obj("Олексйвка, вул. Сонячна, 15\nБритвка, Центральна, 586"))

        assertEquals("Олексйвка, вул. Сонячна, 15", delta.metadata[META_ENTITY_ADDRESS])
    }

    @Test
    fun `applies to text objects and to anything that has read text`() {
        val investigation = EntityInvestigation()
        assertTrue(investigation.accepts(ObjectState(ObjectKind.TEXT)))
        assertFalse(investigation.accepts(ObjectState(ObjectKind.IMAGE)))

        // #1410: слова записи, страниц и документа — тот же текст; вид объекта не важен.
        assertTrue(investigation.accepts(ObjectState(ObjectKind.AUDIO, setOf(Feature.HAS_TEXT))))
        assertTrue(investigation.accepts(ObjectState(ObjectKind.PDF, setOf(Feature.HAS_TEXT))))
        assertTrue(investigation.accepts(ObjectState(ObjectKind.OFFICE, setOf(Feature.HAS_TEXT))))
        assertFalse("без текста искать нечего", investigation.accepts(ObjectState(ObjectKind.AUDIO)))

        // Снимок читает и разбирает сущности OcrInvestigation тем же заходом — второй проход
        // по тому же слою дал бы те же узлы дважды.
        assertFalse(investigation.accepts(ObjectState(ObjectKind.IMAGE, setOf(Feature.HAS_TEXT))))
    }

    /**
     * Живая охота 03.09.2026 (#1410): запись с датой, телефоном и адресом в словах после
     * расшифровки оставалась без единой сущности — исследователь читал байты WAV.
     */
    @Test
    fun `слова записи читаются из сидекара расшифровки, а не из байтов записи`() = runTest {
        val words = File.createTempFile("said", ".txt").apply {
            writeText("Meeting on 11.09.2026 at 15:00, call +380671234567"); deleteOnExit()
        }
        val wav = File.createTempFile("rec", ".wav").apply { writeBytes(ByteArray(64)); deleteOnExit() }
        val recording = PointObject(
            "rec", "audio/wav", ScratchRef(wav.absolutePath),
            ObjectState(ObjectKind.AUDIO, setOf(Feature.HAS_TEXT)),
            metadata = mapOf(com.point.core.flow.META_OCR_TEXT_REF to words.absolutePath),
        )
        // Извлекатель отвечает только на слова записи: байты WAV он бы не узнал.
        val onWords = object : EntityExtractor {
            override suspend fun extract(text: String) =
                if ("+380671234567" in text) listOf(Entity(EntityType.PHONE, "+380671234567"), Entity(EntityType.DATE_TIME, "11.09.2026")) else emptyList()
        }

        val delta = EntityInvestigationRealizer(onWords).look(recording)

        assertEquals("+380671234567", delta.metadata[META_ENTITY_PREFIX + "phone"])
        assertTrue(Feature.HAS_PHONE in delta.features)
        assertTrue(Feature.HAS_DATE in delta.features)
    }

    @Test
    fun `запись с признаком текста, но без сидекара — срыв операции, а не «не нашлось»`() = runTest {
        val wav = File.createTempFile("rec", ".wav").apply { writeBytes(ByteArray(64)); deleteOnExit() }
        val recording = PointObject(
            "rec", "audio/wav", ScratchRef(wav.absolutePath),
            ObjectState(ObjectKind.AUDIO, setOf(Feature.HAS_TEXT)),
        )

        val result = EntityInvestigationRealizer(extractor()).perform(recording, null)

        assertTrue("знание о тексте было, а читать нечем — это неудача: $result", result is ActionResult.Failure)
    }

    @Test
    fun `blank text yields no features and never calls the extractor`() = runTest {
        val enricher = EntityInvestigationRealizer(extractor(Entity(EntityType.PHONE, "x")))
        assertTrue(enricher.look(obj("   ")).features.isEmpty())
    }

    @Test
    fun `keeps the first value of each entity type as an understood fact`() = runTest {
        val enricher = EntityInvestigationRealizer(
            extractor(
                Entity(EntityType.PHONE, "+380671234567"),
                Entity(EntityType.PHONE, "+380952222222"),
                Entity(EntityType.ADDRESS, "ул. Крещатик, 12"),
            ),
        )
        val meta = enricher.look(obj("звони +380671234567 или +380952222222, адрес ул. Крещатик, 12")).metadata
        assertEquals("+380671234567", meta[META_ENTITY_PREFIX + "phone"])
        assertEquals("ул. Крещатик, 12", meta[META_ENTITY_PREFIX + "address"])
    }

    @Test
    fun `a missing text payload is a failure, not an object without entities`() = runTest {
        val ghost = PointObject("x", "text/plain", ScratchRef("/nowhere/gone.txt"), ObjectState(ObjectKind.TEXT))

        val result = EntityInvestigationRealizer(extractor()).perform(ghost, null)

        assertTrue("нечитаемый payload обязан быть неудачей-" + result, result is ActionResult.Failure)
    }

    @Test
    fun `an engine failure is a failed look, not a text without entities`() = runTest {

        val brokenModel = object : EntityExtractor {
            override suspend fun extract(text: String): List<Entity> =
                error("Failed to load model from /data/app/lib/libtflite.so at offset 0x1f")
        }

        val result = EntityInvestigationRealizer(brokenModel).perform(obj("Звони +380671234567"), null)

        assertTrue("сбой движка обязан быть неудачей операции-" + result, result is ActionResult.Failure)

        // Чужой текст движка человеку не адресован (#1225): своё слово объявляет тот слой,
        // который знает, что случилось, — остальное получает общее слово срыва.
        assertEquals(com.point.core.flow.INVESTIGATION_FAILED, (result as ActionResult.Failure).reason)
    }

    @Test
    fun `an engine that worked and found nothing is an honest empty answer`() = runTest {
        val delta = EntityInvestigationRealizer(extractor()).look(obj("обычный текст без сущностей"))

        assertTrue(delta.features.isEmpty())
        assertTrue(delta.objects.isEmpty())
    }
}
