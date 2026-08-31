package com.point.data

import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.Box
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.CloudScope
import com.point.core.flow.CollectionContent
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.FrameTransform
import com.point.core.flow.INCOMPLETE_TIMEOUT
import com.point.core.flow.InvestigationState
import com.point.core.flow.PrivacyConsent
import com.point.core.flow.Realizer
import com.point.core.flow.Resolver
import com.point.core.flow.investigationStateOf
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_READ_PREPARED
import com.point.core.flow.READ_PREPARED_STRAIGHTENED
import com.point.core.flow.META_READ_UPSCALE
import com.point.core.flow.META_READING_MODE
import com.point.core.flow.ReadingMode
import com.point.core.flow.PageQuad
import com.point.core.flow.ObjectStore
import com.point.core.flow.OcrClock
import com.point.core.flow.PaperWhitener
import com.point.core.flow.Spot
import com.point.core.flow.StraightFrame
import com.point.core.flow.StraightenedFrame
import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.LatentBubble
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OcrInvestigationTest {

    private val image = PointObject("id", "image/png", ScratchRef("/tmp/shot.png"), ObjectState(ObjectKind.IMAGE))

    private fun recognizer(
        text: String,
        fail: Boolean = false,
        atoms: List<Atom> = emptyList(),
        incomplete: String? = null,
    ) = object : AtomRecognizer {
        override suspend fun read(obj: PointObject): AtomLayer {
            if (fail) error("engine crashed")
            return AtomLayer(atoms, readerText = text.ifBlank { null }, incomplete = incomplete)
        }
    }

    private fun extractor(vararg entities: Entity) = object : EntityExtractor {
        override suspend fun extract(text: String) = entities.toList()
    }

    /** Кадр не выпрямляется: в этих проверках речь о самом чтении (#1041). */
    private val asIs = StraightFrame { null }

    /** И не выбеливается — первая ступень того же захода (#1046). */
    private val noWhitening = PaperWhitener { null }

    /** Часы стоят: здесь заходу меряют ходы, а не время (#861, #1046). */
    private val stoppedClock = OcrClock { 0L }

    private class FakeStore : ObjectStore {
        val created = mutableListOf<File>()
        override suspend fun newScratchFile(extension: String): ScratchRef {
            val f = File.createTempFile("ocr", ".$extension").apply { deleteOnExit() }
            created += f
            return ScratchRef(f.absolutePath)
        }
        override suspend fun ingest(sourceUri: String, mime: String) = throw UnsupportedOperationException()
        override suspend fun ingestMultiple(sources: List<String>) = throw UnsupportedOperationException()
        override suspend fun put(
            result: ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = throw UnsupportedOperationException()
        override suspend fun children(collection: PointObject, limit: Int) = CollectionContent.empty<PointObject>()
        override suspend fun readText(obj: PointObject, limit: Int) = ""
        override suspend fun clear() {}
    }

    /**
     * Настоящий здесь сам цикл знания, а не его окружение (#1270): [DefaultEnrichment]
     * прогоняет настоящие [OcrInvestigation] и [OcrInvestigationRealizer] — от кадра до
     * состояния знания. Реестр, Resolver и согласие — двойники на один шаг: они лишь
     * доводят цикл до чтения и о самом исходе ничего не решают.
     */
    private fun enrichmentOf(reading: Realizer): DefaultEnrichment {
        val registry = object : CapabilityRegistry {

            // Прочитанное открывает новую дверь — иначе дорогое чтение и не запускается.
            override fun bubblesFor(state: ObjectState) =
                if (state.features.isEmpty()) emptyList()
                else listOf(Bubble("icon", "Прочитанное", CapabilityId("read"), state))

            override fun latentBubblesFor(state: ObjectState) = emptyList<LatentBubble>()
            override fun byId(id: CapabilityId) = throw UnsupportedOperationException()
            override fun all() = listOf<Capability>(OcrInvestigation())
        }
        val resolver = object : Resolver {
            override fun realizerFor(capabilityId: CapabilityId) = reading
            override fun realizerFor(capabilityId: CapabilityId, state: ObjectState) = reading
            override fun leavesDevice(capabilityId: CapabilityId) = false
        }
        val consent = object : PrivacyConsent {
            override suspend fun allowed(scope: CloudScope) = false
            override suspend fun allow(scope: CloudScope) = Unit
            override suspend fun revoke(scope: CloudScope) = Unit
        }
        return DefaultEnrichment(registry, resolver, consent, com.point.core.flow.DEFAULT_PHONE_REGION)
    }

    private val realText = "Встреча завтра в 18:00, ул. Крещатик, 12. Звони +380671234567, детали https://point.app/x"

    /** Дословный ответ движка с кадра, на котором читать нечего: ни одного живого слова. */
    private val garbage =
        "; i= © © - O = & E =. are © = E oS 2 (a9) ous © E pa ae Pl ans BS &§ я OE в > 3EE:"

    private val garbageAtom = Atom("w0", "©", Box(0f, 0f, 5f, 5f), 0.2f)

    @Test
    fun `flags entities on the image and keeps the recognised text as a sidecar`() = runTest {
        val store = FakeStore()
        val enricher = OcrInvestigationRealizer(
            store,
            recognizer(realText),
            extractor(
                Entity(EntityType.PHONE, "+380671234567"),
                Entity(EntityType.DATE_TIME, "завтра в 18:00"),
                Entity(EntityType.ADDRESS, "ул. Крещатик, 12"),
            ),
            asIs,
            noWhitening,
            stoppedClock,
        )
        val delta = enricher.look(image)

        assertTrue(Feature.HAS_PHONE in delta.features)
        assertTrue(Feature.HAS_DATE in delta.features)
        assertTrue(Feature.HAS_ADDRESS in delta.features)
        val ref = delta.metadata[META_OCR_TEXT_REF]
        assertNotNull("recognised text must be kept for reuse", ref)
        assertEquals(realText, File(ref!!).readText())
    }

    @Test
    fun `flags a link found in the recognised text`() = runTest {
        val enricher = OcrInvestigationRealizer(FakeStore(), recognizer(realText), extractor(), asIs, noWhitening, stoppedClock)
        assertTrue(Feature.HAS_URL in enricher.look(image).features)
    }

    @Test
    fun `keeps entity values and the found link as understood facts`() = runTest {
        val enricher = OcrInvestigationRealizer(
            FakeStore(),
            recognizer(realText),
            extractor(Entity(EntityType.PHONE, "+380671234567")),
            asIs,
            noWhitening,
            stoppedClock,
        )
        val meta = enricher.look(image).metadata
        assertEquals("+380671234567", meta[META_ENTITY_PREFIX + "phone"])
        assertEquals("https://point.app/x", meta[META_ENTITY_PREFIX + "url"])
    }

    @Test
    fun `показание счётчика и координаты снимаются с фотографии, а не только с текста`() = runTest {

        val page = "Особовий рахунок 305412\n" +
            "Показання лічильника електроенергії 20842 кВт·ч\n" +
            "Точка обліку 50.4501, 30.5234"
        val enricher = OcrInvestigationRealizer(FakeStore(), recognizer(page), extractor(), asIs, noWhitening, stoppedClock)

        val meta = enricher.look(image).metadata

        assertEquals("20842", meta["entity.meter"])
        assertEquals("кВт·ч", meta["entity.meter.unit"])
        assertEquals("50.4501, 30.5234", meta["entity.geo"])
    }

    @Test
    fun `discards gibberish from features but keeps the atom evidence`() = runTest {
        val store = FakeStore()
        val enricher = OcrInvestigationRealizer(store, recognizer(garbage, atoms = listOf(garbageAtom)), extractor(Entity(EntityType.PHONE, "x")), asIs, noWhitening, stoppedClock)
        val delta = enricher.look(image)

        assertEquals(setOf(Feature.HAS_WORD_LAYER), delta.features)

        assertEquals(setOf(META_OCR_ATOMS_REF, META_READING_MODE), delta.metadata.keys)
        assertEquals(ReadingMode.HANDWRITTEN.name, delta.metadata[META_READING_MODE])
        assertEquals(listOf(garbageAtom), AtomCodec.decode(File(delta.metadata[META_OCR_ATOMS_REF]!!).readText()).atoms)
    }

    /**
     * Пустые руки — не находка (#1270).
     *
     * Путь человека: снимок принят, фоновое исследование прочитало кадр и не разобрало на
     * нём ни слова. Координаты слов остаются уликой — по ним «Найти» встаёт на строку, — но
     * прочитано не было ничего, и вопрос «что написано на снимке» обязан остаться отвеченным
     * честно: смотрели, не нашлось (#1067, #1135). Прежде ссылка на слой засчитывалась за
     * находку, вопрос закрывался «найдено», и переспросить его было нечем: дорогое
     * исследование больше не запускается, а «Распознать текст» уходит как уже отвеченное.
     */
    @Test
    fun `слой слов без единого прочитанного слова не закрывает вопрос находкой`() = runTest {
        val reading = OcrInvestigationRealizer(FakeStore(), recognizer(garbage, atoms = listOf(garbageAtom)), extractor(), asIs, noWhitening, stoppedClock)

        val last = enrichmentOf(reading).enrich(image).toList().last()

        assertTrue("улика слоя слов потеряна", Feature.HAS_WORD_LAYER in last.features)
        assertEquals(
            InvestigationState.NOT_FOUND,
            investigationStateOf(last.metadata, OcrInvestigation.ID),
        )
    }

    /**
     * Слой, каким его отдаёт читатель телефона: слова с их местом и уверенностью.
     *
     * Оба захода делает один и тот же читатель, и атомы приходят с обоих (#1041): пара
     * «атомы против голого текста» бывает только там, где читает облако.
     */
    private fun readLayer(text: String, confidence: Float) = AtomLayer(
        text.split(" ").filter { it.isNotBlank() }.mapIndexed { i, word ->
            Atom("w$i", word, Box(10f, i * 30f, 10f + word.length * 10f, i * 30f + 20f), confidence)
        },
        readerText = text,
    )

    /** Кадр, снятый под углом: сырой читается кашей, выпрямленный — как надо (#1041). */
    private fun crookedReading(straightPath: String) = object : AtomRecognizer {
        override suspend fun read(obj: PointObject) =
            if (obj.uri.value == straightPath) {
                readLayer(realText, confidence = 0.92f)
            } else {
                AtomLayer(listOf(garbageAtom), readerText = garbage)
            }
    }

    /**
     * Плохое чтение — не тупик, а повод выпрямить кадр и прочитать снова (#1041).
     *
     * Путь человека: он поделился счётом, снятым под углом. Первый заход по сырому кадру
     * отдаёт кашу, и раньше на этом всё кончалось — выпрямить кадр он должен был догадаться
     * сам: нажать «Скан», войти в родившуюся картинку и прочитать уже её. Знание после этого
     * оставалось на другом объекте, а не на снимке, которым он поделился.
     *
     * Теперь второй заход делает сам Point, и лучшее чтение ложится знанием того же снимка:
     * телефон со счёта ищется в объекте, которым поделились.
     */
    @Test
    fun `плохо прочитанный кадр выпрямляется и читается снова — знанием того же снимка`() = runTest {
        val straight = "/tmp/straight.jpg"
        val enricher = OcrInvestigationRealizer(
            FakeStore(),
            crookedReading(straight),
            extractor(Entity(EntityType.PHONE, "+380671234567")),
            StraightFrame { copyOf(straight) },
            noWhitening,
            stoppedClock,
        )

        val delta = enricher.look(image)

        assertEquals(realText, File(delta.metadata[META_OCR_TEXT_REF]!!).readText())
        assertTrue("найденное со второго захода — знание того же снимка", Feature.HAS_PHONE in delta.features)

        // Слой слов — часть принятого чтения, а не проигравшего ему. Каша с сырого кадра
        // словами объекта не остаётся, а места слов выпрямленной копии на снимке человека
        // нет — и слоя у такого чтения нет вовсе (#1013, #1332).
        assertFalse("каша с сырого кадра осталась словами объекта", META_OCR_ATOMS_REF in delta.metadata)
        assertFalse(Feature.HAS_WORD_LAYER in delta.features)
    }

    /**
     * Найденное со второго захода знает своё место на снимке (#1332).
     *
     * Путь человека: он снял счёт под углом, Point выпрямил кадр и прочитал его как надо. По
     * прочитанному человек тапает найденный телефон — и подсветка обязана встать на самом
     * снимке, там, куда он смотрит, а не в координатах копии, которой он никогда не видел.
     *
     * Копия родилась из четырёхугольника страницы, растянутого в прямоугольник; тем же ходом
     * в обратную сторону слова и возвращаются. Углы известны — слой слов у объекта есть, и
     * места слов лежат внутри страницы на снимке.
     */
    @Test
    fun `слова со второго захода стоят на снимке человека, а не на копии`() = runTest {
        val straight = "/tmp/straight.jpg"
        val store = FakeStore()
        val page = PageQuad(Spot(200f, 100f), Spot(600f, 100f), Spot(600f, 900f), Spot(200f, 900f))
        val enricher = OcrInvestigationRealizer(
            store,
            crookedReading(straight),
            extractor(Entity(EntityType.PHONE, "+380671234567")),
            StraightFrame { StraightenedFrame(straight, page, width = 400, height = 800) },
            noWhitening,
            stoppedClock,
        )

        val delta = enricher.look(image)

        val ref = delta.metadata[META_OCR_ATOMS_REF]
        assertTrue("слов у чтения не осталось, хотя углы страницы известны", ref != null)
        assertTrue(Feature.HAS_WORD_LAYER in delta.features)
        val words = AtomCodec.decode(File(ref!!).readText())
        assertTrue("слой пуст", words.atoms.isNotEmpty())
        words.atoms.forEach { atom ->
            assertTrue(
                "слово «${atom.text}» встало мимо страницы на снимке: ${atom.box}",
                atom.box.left >= 199f && atom.box.right <= 601f &&
                    atom.box.top >= 99f && atom.box.bottom <= 901f,
            )
        }
        assertTrue(
            "слова остались в координатах копии, а не снимка",
            words.atoms.any { it.box.left > 200f },
        )
    }

    /**
     * Дальше по пути человека читается принятое чтение, а не проигравшее ему (#1041).
     *
     * За текстом объекта действия ходят одним входом (#1138), и слой слов в нём — сам текст
     * объекта: «Понять» проверяет им прочитанное моделью («нет в тексте — нет знания», #809)
     * и показывает модели страницу его блоками, «В Word» строит из него документ, «В Excel»
     * адресует по нему ячейки.
     *
     * Пока слой оставался от сырого кадра, а текст приходил с выпрямленного, объект нёс два
     * разных чтения двух разных кадров: человек, нажав «Понять» над выпрямленным счётом,
     * получал ровно ту кашу, ради ухода от которой кадр и выпрямляли.
     */
    @Test
    fun `слова объекта не спорят с его текстом после второго захода`() = runTest {
        val straight = "/tmp/straight.jpg"
        val store = FakeStore()
        val enricher = OcrInvestigationRealizer(
            store,
            crookedReading(straight),
            extractor(Entity(EntityType.PHONE, "+380671234567")),
            StraightFrame { copyOf(straight) },
            noWhitening,
            stoppedClock,
        )

        val read = image.copy(metadata = image.metadata + enricher.look(image).metadata)
        val known = com.point.core.flow.GraphKnowledge(
            store,
            object : com.point.core.flow.PdfTextExtractor {
                override suspend fun extractText(obj: PointObject, atMost: Int?) = ""
            },
        )
        val words = known.layerOf(read)?.text

        assertEquals("знанием стало не лучшее чтение", realText, known.textOf(read))
        assertTrue(
            "слой слов спорит с текстом объекта, а «Понять», «В Word» и «В Excel» читают именно его-" + words,
            words == null || words == realText,
        )
    }

    /** Тот же путь целиком: вопрос «что написано на снимке» закрывается находкой (#1041). */
    @Test
    fun `выпрямленный кадр отвечает на вопрос о снимке, а не оставляет пустые руки`() = runTest {
        val straight = "/tmp/straight.jpg"
        val reading = OcrInvestigationRealizer(
            FakeStore(),
            crookedReading(straight),
            extractor(Entity(EntityType.PHONE, "+380671234567")),
            StraightFrame { copyOf(straight) },
            noWhitening,
            stoppedClock,
        )

        val last = enrichmentOf(reading).enrich(image).toList().last()

        assertEquals(
            InvestigationState.FOUND,
            investigationStateOf(last.metadata, OcrInvestigation.ID),
        )
    }

    /** Ровный кадр ничего не теряет и ничего не платит: второго захода у него нет (#1041). */
    @Test
    fun `прочитанный кадр не выпрямляется`() = runTest {
        var asked = 0
        val enricher = OcrInvestigationRealizer(
            FakeStore(),
            recognizer(realText),
            extractor(),
            StraightFrame { asked++; null },
            noWhitening,
            stoppedClock,
        )

        enricher.look(image)

        assertEquals("выпрямлять прочитанное незачем", 0, asked)
    }

    /**
     * И выпрямленный кадр прочитался кашей — вопрос остаётся открытым (#988, #1041).
     *
     * Второе прочтение не лучше первого, и объявлять находкой то же самое, от чего уходили,
     * нельзя: на бессмыслице дальше строились бы действия.
     */
    @Test
    fun `каша и после выпрямления не становится знанием`() = runTest {
        val enricher = OcrInvestigationRealizer(
            FakeStore(),
            recognizer(garbage, atoms = listOf(garbageAtom)),
            extractor(Entity(EntityType.PHONE, "+380671234567")),
            StraightFrame { copyOf("/tmp/straight.jpg") },
            noWhitening,
            stoppedClock,
        )

        val delta = enricher.look(image)

        assertFalse("каша объявлена текстом снимка", Feature.HAS_TEXT in delta.features)
        assertFalse("каша сохранена чтением объекта", META_OCR_TEXT_REF in delta.metadata)
    }

    /**
     * Длинный счёт, прочитанный почти целиком, но неуверенно (#1041).
     *
     * Больше половины слов — догадки движка, из-за них медианная уверенность ниже порога
     * годности, и знанием такое чтение не станет. Но прочитано на нём почти всё, и терять
     * это ради десяти слов из угла нельзя.
     */
    private fun weaklyReadReceipt(): AtomLayer {
        val words = listOf(
            "Супермаркет", "Сільпо", "вул", "Хрещатик", "12", "Чек", "0042", "молоко",
            "селянське", "27.50", "хліб", "житній", "18.90", "кава", "мелена", "142.00",
            "сир", "твердий", "231.40", "вода", "негазована", "16.20", "Разом", "436.00",
            "Готівка", "500.00", "Решта", "64.00", "Дякуємо", "Каса",
        )
        // Догадок больше половины: медианная уверенность ложится на них, и по ней чтение
        // признаётся плохим — ровно тот повод, по которому кадр и выпрямляется.
        val guessed = words.size * 3 / 5
        return AtomLayer(
            words.mapIndexed { i, word ->
                Atom(
                    "w$i",
                    word,
                    Box(10f, i * 30f, 10f + word.length * 10f, i * 30f + 20f),
                    if (i < guessed) 0.35f else 0.9f,
                )
            },
        )
    }

    /**
     * Второй заход беднее первого — знанием остаётся первое чтение (#1041).
     *
     * Путь человека: он поделился длинным счётом. Первый заход прочитал его почти целиком,
     * но неуверенно, и по этой неуверенности чтение признано плохим — кадр выпрямляется.
     * На выпрямлении границей листа стал не лист, а чек внутри кадра — обычный промах поиска
     * границ, — и второй заход отдал чистые десять слов из угла.
     *
     * Взять последнее чтение значило бы молча потерять почти весь счёт и закрыть вопрос
     * «что написано на снимке» углом страницы. Карточка требует лучшего чтения, а не
     * второго: полнее — первое, и знанием становится оно.
     */
    @Test
    fun `второй заход беднее первого — знанием он не становится`() = runTest {
        val straight = "/tmp/straight.jpg"
        val corner = "Дякуємо за покупку! Чекаємо знову. Каса 12 Зміна 3 Термінал 44"
        val reading = object : AtomRecognizer {
            override suspend fun read(obj: PointObject) =
                if (obj.uri.value == straight) readLayer(corner, confidence = 0.9f)
                else weaklyReadReceipt()
        }
        val enricher = OcrInvestigationRealizer(
            FakeStore(),
            reading,
            extractor(Entity(EntityType.PHONE, "+380671234567")),
            StraightFrame { copyOf(straight) },
            noWhitening,
            stoppedClock,
        )

        val delta = enricher.look(image)

        assertFalse("угол кадра объявлен текстом снимка", Feature.HAS_TEXT in delta.features)
        assertFalse("угол кадра сохранён чтением объекта", META_OCR_TEXT_REF in delta.metadata)
        assertFalse("найденное в углу выдано за знание о счёте", Feature.HAS_PHONE in delta.features)
        assertTrue("улика первого чтения потеряна", Feature.HAS_WORD_LAYER in delta.features)
    }

    /**
     * Снимок без текста за чужую кривизну не платит (#1041).
     *
     * Фото кота, селфи, кадр видео — самый частый объект в Point, и текста на нём нет вовсе.
     * Пустое чтение — тоже плохое чтение, и без этого условия каждый такой снимок уходил бы
     * в выпрямление и второе полное чтение. «Текст был, но не дался» отличимо от «текста
     * нет» единственным сигналом: движок увидел на кадре хоть что-то.
     */
    @Test
    fun `снимок без текста не выпрямляется`() = runTest {
        var asked = 0
        val enricher = OcrInvestigationRealizer(
            FakeStore(),
            recognizer(""),
            extractor(),
            StraightFrame { asked++; null },
            noWhitening,
            stoppedClock,
        )

        enricher.look(image)

        assertEquals("выпрямлять кадр, на котором движок не увидел ни слова, незачем", 0, asked)
    }

    /**
     * Оборванное чтение — не плохое чтение, а недочитанное (#1041, ADR-0001 §9).
     *
     * Кадр, не уложившийся в срок, отдаёт снятые до обрыва атомы и обрывок текста — по виду
     * то же плохое чтение, что и каша. Но второй заход стоит выпрямления и ещё одного полного
     * чтения того же кадра, то есть вдвое больше срока, в который уже не уложились, и судить
     * по недочитанному о кривизне кадра нечем.
     */
    @Test
    fun `оборванное чтение кадр не выпрямляет`() = runTest {
        var asked = 0
        val enricher = OcrInvestigationRealizer(
            FakeStore(),
            recognizer(garbage, atoms = listOf(garbageAtom), incomplete = INCOMPLETE_TIMEOUT),
            extractor(),
            StraightFrame { asked++; null },
            noWhitening,
            stoppedClock,
        )

        enricher.look(image)

        assertEquals("недочитанный кадр заплатил за второй заход", 0, asked)
    }

    /**
     * Текст пришёл с кадра, которого человек не видел — и это записано (#1041).
     *
     * Тот же след работы, что и чтение увеличенного кадра (`read.upscale`): без него понять
     * происхождение знания нечем — оно выглядит прочитанным с того снимка, которым
     * поделились. Человеку эта пометка не показывается: provenance внутренний.
     */
    @Test
    fun `чтение с выпрямленного кадра видно в метаданных объекта`() = runTest {
        val straight = "/tmp/straight.jpg"
        val enricher = OcrInvestigationRealizer(
            FakeStore(),
            crookedReading(straight),
            extractor(),
            StraightFrame { copyOf(straight) },
            noWhitening,
            stoppedClock,
        )

        assertEquals(READ_PREPARED_STRAIGHTENED, enricher.look(image).metadata[META_READ_PREPARED])
    }

    @Test
    fun `чтение с кадра человека пометки о подготовке не оставляет`() = runTest {
        val delta = OcrInvestigationRealizer(FakeStore(), recognizer(realText), extractor(), asIs, noWhitening, stoppedClock).look(image)

        assertFalse(META_READ_PREPARED in delta.metadata)
    }

    @Test
    fun `persists the atom layer as a sidecar next to the text`() = runTest {
        val store = FakeStore()
        val atoms = listOf(
            Atom("w0", "20", Box(10f, 10f, 30f, 24f), 0.9f, "tesseract", "5.3", 0),
            Atom("w1", "4514", Box(34f, 10f, 80f, 24f), 0.8f, "tesseract", "5.3", 0),
        )
        val enricher = OcrInvestigationRealizer(store, recognizer(realText, atoms = atoms), extractor(), asIs, noWhitening, stoppedClock)
        val delta = enricher.look(image)

        val ref = delta.metadata[META_OCR_ATOMS_REF]
        assertNotNull("the atom layer must survive as evidence", ref)
        assertEquals(atoms, AtomCodec.decode(File(ref!!).readText()).atoms)
        assertEquals(realText, File(delta.metadata[META_OCR_TEXT_REF]!!).readText())

        assertTrue(Feature.HAS_WORD_LAYER in delta.features)
    }

    @Test
    fun `увеличение кадра перед чтением видно в метаданных объекта`() = runTest {
        val enlarged = object : AtomRecognizer {
            override suspend fun read(obj: PointObject) = AtomLayer(
                listOf(Atom("w0", "11004", Box(30f, 30f, 90f, 60f), 0.9f)),
                readerText = realText,
                transform = FrameTransform(sample = 1, uprightWidth = 3000, uprightHeight = 2250, upscale = 3),
            )
        }
        val delta = OcrInvestigationRealizer(FakeStore(), enlarged, extractor(), asIs, noWhitening, stoppedClock).look(image)

        assertEquals("3", delta.metadata[META_READ_UPSCALE])
    }

    @Test
    fun `неувеличенный кадр не оставляет пометки об увеличении`() = runTest {
        val delta = OcrInvestigationRealizer(FakeStore(), recognizer(realText), extractor(), asIs, noWhitening, stoppedClock).look(image)

        assertFalse(META_READ_UPSCALE in delta.metadata)
    }

    @Test
    fun `две сущности одного фото различимы и по id, и по месту на странице`() = runTest {
        val atoms = listOf(
            Atom("w1", "тел:", Box(10f, 20f, 60f, 40f), confidence = 0.99f),
            Atom("w2", "+380671234567", Box(70f, 20f, 260f, 40f), confidence = 0.99f),
            Atom("w3", "почта:", Box(10f, 120f, 80f, 140f), confidence = 0.99f),
            Atom("w4", "hello@example.org", Box(90f, 120f, 300f, 140f), confidence = 0.99f),
        )
        val enricher = OcrInvestigationRealizer(
            FakeStore(),
            recognizer("тел: +380671234567\nпочта: hello@example.org", atoms = atoms),
            extractor(
                Entity(EntityType.PHONE, "+380671234567"),
                Entity(EntityType.EMAIL, "hello@example.org"),
            ),
            asIs,
            noWhitening,
            stoppedClock,
        )
        val delta = enricher.look(image)

        val phone = delta.objects.single { it.state.kind == com.point.core.flow.KIND_PHONE }
        val email = delta.objects.single { it.state.kind == com.point.core.flow.KIND_EMAIL }
        val phoneAt = phone.metadata[com.point.core.flow.META_AT_REGION]
        val emailAt = email.metadata[com.point.core.flow.META_AT_REGION]

        assertTrue("телефон обязан знать своё место", phoneAt != null)
        assertTrue("почта обязана знать своё место", emailAt != null)
        assertTrue("места разных сущностей различны", phoneAt != emailAt)
        assertTrue("id разных сущностей различны", phone.id != email.id)

        val box = com.point.core.flow.regionOfWire(phoneAt)!!
        assertTrue("регион телефона лежит в его строке, а не в строке почты", box.top < 120f)
    }

    @Test
    fun `значение без однозначного места остаётся без региона, а не с выдуманным`() = runTest {

        val atoms = listOf(
            Atom("w1", "+380671234567", Box(10f, 20f, 210f, 40f), confidence = 0.99f),
            Atom("w2", "+380671234567", Box(10f, 220f, 210f, 240f), confidence = 0.99f),
        )
        val enricher = OcrInvestigationRealizer(
            FakeStore(),
            recognizer("+380671234567\n+380671234567", atoms = atoms),
            extractor(Entity(EntityType.PHONE, "+380671234567")),
            asIs,
            noWhitening,
            stoppedClock,
        )
        val delta = enricher.look(image)

        val phone = delta.objects.single { it.state.kind == com.point.core.flow.KIND_PHONE }
        assertTrue(
            "двусмысленное место честнее не называть",
            com.point.core.flow.META_AT_REGION !in phone.metadata,
        )
    }

    @Test
    fun `blank recognition yields nothing but the honest reading mode`() = runTest {

        val enricher = OcrInvestigationRealizer(FakeStore(), recognizer("   "), extractor(), asIs, noWhitening, stoppedClock)
        val delta = enricher.look(image)
        assertTrue(delta.features.isEmpty())
        assertEquals(ReadingMode.HANDWRITTEN.name, delta.metadata[META_READING_MODE])
        assertEquals(setOf(META_READING_MODE), delta.metadata.keys)
    }

    @Test
    fun `an engine failure is a failure, not an empty reading — a crash is not handwriting`() = runTest {

        val enricher = OcrInvestigationRealizer(FakeStore(), recognizer("", fail = true), extractor(), asIs, noWhitening, stoppedClock)
        val result = enricher.perform(image, null)

        assertTrue("сбой движка обязан остаться сбоем, а не пустым знанием-" + result,
            result is com.point.core.model.ActionResult.Failure)
    }

    @Test
    fun `чтение, отрезанное по времени без единого слова, — неудача, а не пустая страница`() = runTest {
        val timedOut = object : AtomRecognizer {
            override suspend fun read(obj: PointObject) =
                AtomLayer(emptyList(), incomplete = INCOMPLETE_TIMEOUT)
        }
        val result = OcrInvestigationRealizer(FakeStore(), timedOut, extractor(), asIs, noWhitening, stoppedClock).perform(image, null)

        assertTrue("обрыв без находок обязан быть неудачей-" + result,
            result is com.point.core.model.ActionResult.Failure)
    }

    @Test
    fun `таймаут с уже прочитанными словами оставляет частичное знание`() = runTest {
        val atoms = listOf(Atom("w1", "+380671234567", Box(10f, 20f, 210f, 40f), confidence = 0.99f))
        val partial = recognizer("+380671234567", atoms = atoms, incomplete = INCOMPLETE_TIMEOUT)
        val delta = OcrInvestigationRealizer(FakeStore(), partial, extractor(), asIs, noWhitening, stoppedClock).look(image)

        assertTrue("частичное чтение — всё ещё знание", delta.metadata.isNotEmpty())

        assertTrue("режим чтения при обрыве не угадывается",
            META_READING_MODE !in delta.metadata)
    }

    @Test
    fun `нераскрывшийся снимок — знание о негодности объекта, а не провал операции`() = runTest {

        // #684/#685: «decode failed» — это про сам объект, не про попытку сейчас. Такое
        // знание остаётся с объектом (Feature.UNUSABLE) и после этого тапа — не гаснет
        // разовым отказом, как раньше.
        val broken = recognizer("", incomplete = "decode failed")
        val delta = OcrInvestigationRealizer(FakeStore(), broken, extractor(), asIs, noWhitening, stoppedClock).look(image)

        assertEquals(setOf(Feature.UNUSABLE), delta.features)

        // Причина ридера не теряется — но и не выходит к человеку чужим языком:
        // «decode failed» на экране был жаргоном (#686), теперь это только журнал.
        val reason = delta.metadata[com.point.core.flow.META_UNUSABLE_REASON]
        assertNotNull("что-то сказано", reason)
        assertTrue("что-то сказано", reason!!.isNotBlank())
        assertTrue("без латиницы платформы", reason.none { it in 'a'..'z' || it in 'A'..'Z' })
    }

    @Test
    fun `не изображение вовсе — та же негодность, что и битые байты`() = runTest {
        val notAnImage = recognizer("", incomplete = "not an image")
        val delta = OcrInvestigationRealizer(FakeStore(), notAnImage, extractor(), asIs, noWhitening, stoppedClock).look(image)

        assertEquals(setOf(Feature.UNUSABLE), delta.features)
    }

    @Test
    fun `движок не завёлся — неудача операции, объект не порочится навсегда`() = runTest {

        // В отличие от битых байт — это про попытку сейчас (устройство, движок), а не про
        // содержимое: закрывать путь наружу навсегда здесь нельзя (#684/#685).
        val enginedown = recognizer("", incomplete = "engine init failed")
        val result = OcrInvestigationRealizer(FakeStore(), enginedown, extractor(), asIs, noWhitening, stoppedClock).perform(image, null)

        assertTrue(result is com.point.core.model.ActionResult.Failure)

        // #1258: и слова обязаны говорить то же самое. Человеку сообщали «Файл не открылся —
        // он повреждён или это не изображение», и он шёл переснимать или удалял «битую»
        // фотографию, хотя не завёлся наш движок.
        val said = (result as com.point.core.model.ActionResult.Failure).reason
        assertFalse("виноват объявлен файл человека: $said", said.contains("повреждён"))
        assertEquals(com.point.core.flow.READ_NOT_NOW, said)
    }

    @Test
    fun `частичный слой таймаута сохраняет улики, но не режим чтения`() = runTest {
        val atoms = listOf(Atom("w0", "20", Box(10f, 10f, 30f, 24f), 0.9f, "tesseract", "5.3", 0))
        val partial = object : AtomRecognizer {
            override suspend fun read(obj: PointObject) =
                AtomLayer(atoms, readerText = realText, incomplete = INCOMPLETE_TIMEOUT)
        }
        val delta = OcrInvestigationRealizer(FakeStore(), partial, extractor(), asIs, noWhitening, stoppedClock).look(image)

        assertEquals(atoms, AtomCodec.decode(File(delta.metadata[META_OCR_ATOMS_REF]!!).readText()).atoms)
        assertEquals(realText, File(delta.metadata[META_OCR_TEXT_REF]!!).readText())
        assertFalse(META_READING_MODE in delta.metadata)
    }

    @Test
    fun `applies only to images and declares slow, labelled work`() {
        val enricher = OcrInvestigationRealizer(FakeStore(), recognizer(""), extractor(), asIs, noWhitening, stoppedClock)
        assertTrue(OcrInvestigation().accepts(ObjectState(ObjectKind.IMAGE)))
        assertFalse(OcrInvestigation().accepts(ObjectState(ObjectKind.TEXT)))
        val declared = OcrInvestigation()
        assertEquals(com.point.core.flow.Latency.SLOW, declared.meta.latency)
        assertTrue(declared.meta.investigation)
        assertFalse(declared.label(ObjectState(ObjectKind.IMAGE)).isBlank())
        assertTrue(Feature.HAS_PHONE in declared.meta.mayYield)
    }
    /**
     * Выпрямленная копия без обратного хода — как страница, расправленная по линиям
     * разлиновки: место найденного с неё не возвращается, и слов у чтения не остаётся (#1332).
     */
    private fun copyOf(path: String) = StraightenedFrame(path, page = null, width = 0, height = 0)

}
