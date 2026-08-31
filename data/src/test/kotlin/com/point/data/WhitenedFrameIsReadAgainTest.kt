package com.point.data

import com.point.core.flow.Atom
import com.point.core.flow.AtomCodec
import com.point.core.flow.AtomLayer
import com.point.core.flow.AtomRecognizer
import com.point.core.flow.Box
import com.point.core.flow.CollectionContent
import com.point.core.flow.Entity
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.FrameTransform
import com.point.core.flow.INCOMPLETE_TIMEOUT
import com.point.core.flow.META_OCR_ATOMS_REF
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.flow.META_READ_PREPARED
import com.point.core.flow.OCR_READ_BUDGET_MS
import com.point.core.flow.ObjectStore
import com.point.core.flow.OcrClock
import com.point.core.flow.PaperWhitener
import com.point.core.flow.READ_PREPARED_STRAIGHTENED
import com.point.core.flow.READ_PREPARED_WHITENED
import com.point.core.flow.StraightFrame
import com.point.core.flow.StraightenedFrame
import com.point.core.flow.WhitenedFrame
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Свет и белая бумага на пути человека (#1046).
 *
 * Человек поделился фотографией акта, снятой с рук при окне сбоку. Тень и градиент по листу —
 * и движок не разбирает ни слова: вопрос «что написано на снимке» остаётся без ответа, хотя
 * выбеливать бумагу Point умеет давно. Умение было заперто в действии «Скан», которое рождает
 * новый объект: чтобы получить чтение, человеку надо было догадаться нажать «Скан», войти в
 * появившуюся картинку и прочитать уже её — а знание оставалось на ней, не на его снимке.
 *
 * Теперь ровный свет — первая ступень того же второго захода, который завёл #1041, и берётся
 * она только тогда, когда первое чтение не вышло.
 */
class WhitenedFrameIsReadAgainTest {

    private val photo =
        PointObject("id", "image/jpeg", ScratchRef("/tmp/akt-145.jpg"), ObjectState(ObjectKind.IMAGE))

    private val act = "АКТ здачі-приймання робіт № 145 від 12.05.2026, сума 4800,00 грн, " +
        "виконавець ТОВ «Промінь», телефон +380671234567"

    /** Дословный ответ движка с кадра, на котором читать нечего: ни одного живого слова. */
    private val mush =
        "; i= © © - O = & E =. are © = E oS 2 (a9) ous © E pa ae Pl ans BS &§ я OE в > 3EE:"

    private val whitenedPath = "/tmp/akt-145-white.jpg"

    private val straightPath = "/tmp/akt-145-straight.jpg"

    /**
     * Движок, отвечающий разным кадрам по-разному. Кто его спрашивал — видно по [asked].
     *
     * Время он двигает сам: [spends] — часы захода, [eachReadMs] — сколько предела съедает
     * одно чтение. Так проверяется предел, а не выдерживается срок: ждать в тесте столько же,
     * сколько ждёт человек, значит проверять часы часами.
     */
    private class Engine(
        private val byPath: Map<String, AtomLayer>,
        private val onShot: AtomLayer,
        private val spends: MovingClock? = null,
        private val eachReadMs: Long = 0L,
    ) : AtomRecognizer {

        val asked = mutableListOf<String>()

        override suspend fun read(obj: PointObject): AtomLayer {
            asked += obj.uri.value
            spends?.let { it.at += eachReadMs }
            return byPath[obj.uri.value] ?: onShot
        }
    }

    /** Часы, которые двигает движок, а не время. */
    private class MovingClock : OcrClock {

        var at = 0L

        override fun nowMs(): Long = at
    }

    private class Whitener(private val frame: WhitenedFrame?) : PaperWhitener {

        var asks = 0

        override suspend fun whitened(path: String): WhitenedFrame? {
            asks++
            return frame
        }
    }

    private class Straightener(private val path: String?) : StraightFrame {

        var asks = 0

        override suspend fun of(path: String): StraightenedFrame? {
            asks++
            return this.path?.let { StraightenedFrame(it, page = null, width = 0, height = 0) }
        }
    }

    private fun read(text: String, atoms: List<Atom> = emptyList(), transform: FrameTransform? = null) =
        AtomLayer(atoms, readerText = text, transform = transform)

    private fun word(box: Box) = Atom("w0", "145", box, 0.9f, "tesseract", "5.3", 0)

    /**
     * Слова акта так, как их отдал бы движок с выбеленного кадра: первое стоит в [first].
     *
     * Их несколько не для красоты — чтением считается то, из которого человек получит больше
     * живого (#1041), и одинокое слово проиграло бы даже каше.
     */
    private fun actWords(first: Box) = listOf(
        Atom("w0", "145", first, 0.9f, "tesseract", "5.3", 0),
        Atom("w1", "здачі-приймання", Box(70f, 20f, 200f, 40f), 0.9f, "tesseract", "5.3", 0),
        Atom("w2", "Промінь", Box(70f, 50f, 160f, 70f), 0.9f, "tesseract", "5.3", 0),
        Atom("w3", "4800,00", Box(170f, 50f, 250f, 70f), 0.9f, "tesseract", "5.3", 0),
    )

    private val asIs = StraightFrame { null }

    /** Часы стоят: там, где речь не о пределе, заходу меряют ходы, а не время. */
    private val stoppedClock = OcrClock { 0L }

    private class Store : ObjectStore {
        override suspend fun newScratchFile(extension: String): ScratchRef =
            ScratchRef(File.createTempFile("read", ".$extension").apply { deleteOnExit() }.absolutePath)
        override suspend fun ingest(sourceUri: String, mime: String) = throw UnsupportedOperationException()
        override suspend fun ingestMultiple(sources: List<String>) = throw UnsupportedOperationException()
        override suspend fun put(
            result: ResultObject,
            from: PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = throw UnsupportedOperationException()
        override suspend fun children(collection: PointObject, limit: Int) = CollectionContent.empty<PointObject>()
        override suspend fun readText(obj: PointObject, limit: Int) = ""
        override suspend fun clear() = Unit
    }

    private fun phones(vararg found: String) = object : EntityExtractor {
        override suspend fun extract(text: String) = found.map { Entity(EntityType.PHONE, it) }
    }

    /**
     * Тот же снимок, то же исследование — и текст акта становится знанием самого снимка,
     * а не отдельной картинки, до которой человеку ещё надо додуматься.
     */
    @Test
    fun `нечитаемое фото акта прочитано после выбеливания, и знание легло на сам снимок`() = runTest {
        val engine = Engine(mapOf(whitenedPath to read(act)), onShot = read(mush))

        val delta = OcrInvestigationRealizer(
            Store(),
            engine,
            phones("+380671234567"),
            asIs,
            Whitener(WhitenedFrame(whitenedPath, shrink = 1)),
            stoppedClock,
        ).look(photo)

        assertTrue("текст акта обязан стать знанием снимка", Feature.HAS_TEXT in delta.features)
        assertEquals(act, File(delta.metadata.getValue(META_OCR_TEXT_REF)).readText())
        assertTrue("телефон со второго захода — знание того же снимка", Feature.HAS_PHONE in delta.features)
        assertEquals(listOf(photo.uri.value, whitenedPath), engine.asked)
    }

    /**
     * Ровный свет слова не двигает — и они возвращаются на снимок вместе с прочитанным.
     *
     * Этим ступень и отличается от выпрямления (#1041): там координаты уезжают вместе с
     * геометрией и уликой остаётся первое чтение, а здесь по словам второго захода
     * по-прежнему встают подсветка найденного, `at.region` у знания и вырезка ячейки.
     */
    @Test
    fun `слова с выбеленной копии ложатся уликой в координатах снимка`() = runTest {
        val onCopy = FrameTransform(sample = 1, uprightWidth = 1000, uprightHeight = 750)
        val engine = Engine(
            mapOf(whitenedPath to read(act, actWords(Box(10f, 20f, 60f, 40f)), onCopy)),
            onShot = read(mush),
        )

        val delta = OcrInvestigationRealizer(
            Store(),
            engine,
            phones(),
            asIs,
            Whitener(WhitenedFrame(whitenedPath, shrink = 2)),
            stoppedClock,
        ).look(photo)

        assertTrue("слой слов обязан дожить до знания", Feature.HAS_WORD_LAYER in delta.features)
        val words = AtomCodec.decode(File(delta.metadata.getValue(META_OCR_ATOMS_REF)).readText())
        assertEquals(Box(20f, 40f, 120f, 80f), words.atoms.first().box)
        assertEquals(2, words.transform?.sample)
    }

    /**
     * Первое чтение оборвал собственный предел времени — второго захода нет (#861, #1046).
     *
     * Ни выбеливание, ни выпрямление часов не чинят: кадр, уже съевший весь предел чтения,
     * получил бы ещё столько же и отдал бы человеку тот же ответ вдвое позже. Предел, который
     * человек чувствует, обязан остаться одним числом.
     */
    @Test
    fun `оборванное по времени чтение второго захода не получает`() = runTest {
        val cutShort = AtomLayer(listOf(word(Box(1f, 1f, 2f, 2f))), readerText = mush, incomplete = INCOMPLETE_TIMEOUT)
        val engine = Engine(
            mapOf(whitenedPath to read(act), straightPath to read(act)),
            onShot = cutShort,
        )
        val whitener = Whitener(WhitenedFrame(whitenedPath, shrink = 1))
        val straightener = Straightener(straightPath)

        OcrInvestigationRealizer(Store(), engine, phones(), straightener, whitener, stoppedClock).look(photo)

        assertEquals("выбеливание не чинит часы", 0, whitener.asks)
        assertEquals("выпрямление не чинит часы", 0, straightener.asks)
        assertEquals(listOf(photo.uri.value), engine.asked)
    }

    /**
     * Часы у захода одни, и меряют они заход, а не отдельное чтение (#861, #1046).
     *
     * Читатель отмеряет свой предел каждому чтению заново и о прошлых ступенях не знает: кадр,
     * чьё первое чтение съело предел целиком и всё-таки дочиталось само, получал подготовку и
     * ещё столько же — человек ждал вдвое дольше того же ответа. Пометки `INCOMPLETE_TIMEOUT`
     * на таком чтении нет, и по ней это не поймать.
     */
    @Test
    fun `съевшее весь предел чтение второго захода не получает`() = runTest {
        val clock = MovingClock()
        val engine = Engine(
            mapOf(whitenedPath to read(act)),
            onShot = read(mush),
            spends = clock,
            eachReadMs = OCR_READ_BUDGET_MS,
        )
        val whitener = Whitener(WhitenedFrame(whitenedPath, shrink = 1))

        OcrInvestigationRealizer(Store(), engine, phones(), asIs, whitener, clock).look(photo)

        assertEquals("предел захода съеден первым чтением", 0, whitener.asks)
        assertEquals(listOf(photo.uri.value), engine.asked)
    }

    /**
     * Третьего чтения человек не ждёт (#861, #1046).
     *
     * Ступеней у захода две, и каждая кончается чтением. Без общих часов заход стоил бы трёх
     * пределов подряд, а тап человека ждёт под потолком действия в десять минут
     * (`ACTION_CEILING_MS`): вместо ответа он получил бы «не уложилось».
     */
    @Test
    fun `выпрямление не начинается, когда часы захода вышли`() = runTest {
        val clock = MovingClock()
        val engine = Engine(
            mapOf(whitenedPath to read(mush), straightPath to read(act)),
            onShot = read(mush),
            spends = clock,
            eachReadMs = OCR_READ_BUDGET_MS * 2 / 3,
        )
        val straightener = Straightener(straightPath)

        val delta = OcrInvestigationRealizer(
            Store(),
            engine,
            phones(),
            straightener,
            Whitener(WhitenedFrame(whitenedPath, shrink = 1)),
            clock,
        ).look(photo)

        assertEquals("два чтения уже съели предел захода", 0, straightener.asks)
        assertEquals(listOf(photo.uri.value, whitenedPath), engine.asked)
        assertFalse("прочитать так и не вышло — вопрос остаётся открытым", Feature.HAS_TEXT in delta.features)
    }

    /**
     * Ступени идут от бережной к решительной: выпрямление трогают только тогда, когда ровного
     * света не хватило. Иначе снимок платил бы уехавшими словами там, где мог не платить.
     */
    @Test
    fun `ровный свет пробуется раньше выпрямления`() = runTest {
        val engine = Engine(
            mapOf(whitenedPath to read(act), straightPath to read(act)),
            onShot = read(mush),
        )
        val straightener = Straightener(straightPath)

        val delta = OcrInvestigationRealizer(
            Store(),
            engine,
            phones(),
            straightener,
            Whitener(WhitenedFrame(whitenedPath, shrink = 1)),
            stoppedClock,
        ).look(photo)

        assertEquals("выпрямлять было незачем", 0, straightener.asks)
        assertEquals(READ_PREPARED_WHITENED, delta.metadata[META_READ_PREPARED])
    }

    /**
     * Ровный свет не помог и упёрся в предел времени — выпрямления следом не будет (#861).
     *
     * Иначе человек ждал бы втрое дольше ради того же ответа «не разобрал»: часы у захода
     * одни, и правило у них одно на все ступени.
     */
    @Test
    fun `оборвавшийся по времени выбеленный кадр не тянет за собой выпрямление`() = runTest {
        val engine = Engine(
            mapOf(
                whitenedPath to AtomLayer(emptyList(), readerText = mush, incomplete = INCOMPLETE_TIMEOUT),
                straightPath to read(act),
            ),
            onShot = read(mush),
        )
        val straightener = Straightener(straightPath)

        val delta = OcrInvestigationRealizer(
            Store(),
            engine,
            phones(),
            straightener,
            Whitener(WhitenedFrame(whitenedPath, shrink = 1)),
            stoppedClock,
        ).look(photo)

        assertEquals("часы у захода одни на обе ступени", 0, straightener.asks)
        assertFalse("оборванное чтение знанием не становится", Feature.HAS_TEXT in delta.features)
    }

    /** Ровного света не хватило — кадр выпрямляется, и это видно по следу чтения (#1041). */
    @Test
    fun `не помог ровный свет — кадр выпрямляется`() = runTest {
        val engine = Engine(
            mapOf(whitenedPath to read(mush), straightPath to read(act)),
            onShot = read(mush),
        )

        val delta = OcrInvestigationRealizer(
            Store(),
            engine,
            phones(),
            Straightener(straightPath),
            Whitener(WhitenedFrame(whitenedPath, shrink = 1)),
            stoppedClock,
        ).look(photo)

        assertEquals(act, File(delta.metadata.getValue(META_OCR_TEXT_REF)).readText())
        assertEquals(READ_PREPARED_STRAIGHTENED, delta.metadata[META_READ_PREPARED])
    }

    /**
     * Ровный кадр, прочитанный с первого раза, не платит ничего — и своим кадром себя не
     * называет: подготовки не было (#1046).
     */
    @Test
    fun `кадр, прочитанный с первого раза, не выбеливается`() = runTest {
        val whitener = Whitener(WhitenedFrame(whitenedPath, shrink = 1))
        val engine = Engine(mapOf(whitenedPath to read("сюда не доходим")), onShot = read(act))

        val delta = OcrInvestigationRealizer(Store(), engine, phones(), asIs, whitener, stoppedClock).look(photo)

        assertEquals(0, whitener.asks)
        assertEquals(listOf(photo.uri.value), engine.asked)
        assertNull(delta.metadata[META_READ_PREPARED])
    }

    /**
     * Подготовленный кадр тоже не прочитался — вопрос остаётся открытым, а не закрывается
     * находкой (#988). След подготовки при этом находкой не считается: он про ход, а не про
     * прочитанное.
     */
    @Test
    fun `каша и после выбеливания не становится знанием`() = runTest {
        val engine = Engine(mapOf(whitenedPath to read(mush)), onShot = read(mush))

        val delta = OcrInvestigationRealizer(
            Store(),
            engine,
            phones("+380671234567"),
            asIs,
            Whitener(WhitenedFrame(whitenedPath, shrink = 1)),
            stoppedClock,
        ).look(photo)

        assertFalse("каша объявлена текстом снимка", Feature.HAS_TEXT in delta.features)
        assertFalse("каша сохранена чтением объекта", META_OCR_TEXT_REF in delta.metadata)
        assertNull("непомогшая подготовка следа не оставляет", delta.metadata[META_READ_PREPARED])
    }

    /** Выбеливать нечем — читается только сам снимок, и это не отказ. */
    @Test
    fun `без выбеливателя чтение остаётся прежним`() = runTest {
        val engine = Engine(mapOf(whitenedPath to read(act)), onShot = read(mush))

        val delta = OcrInvestigationRealizer(Store(), engine, phones(), asIs, Whitener(null), stoppedClock).look(photo)

        assertFalse(Feature.HAS_TEXT in delta.features)
        assertEquals(listOf(photo.uri.value), engine.asked)
    }

    /** Улика слов после второго захода остаётся при снимке, а копия объектом не становится. */
    @Test
    fun `выбеленная копия объектом не становится`() = runTest {
        val engine = Engine(
            mapOf(whitenedPath to read(act, actWords(Box(10f, 20f, 60f, 40f)))),
            onShot = read(mush),
        )

        val delta = OcrInvestigationRealizer(
            Store(),
            engine,
            phones("+380671234567"),
            asIs,
            Whitener(WhitenedFrame(whitenedPath, shrink = 1)),
            stoppedClock,
        ).look(photo)

        assertFalse("выбеленная копия объектом не становится", delta.objects.any { it.uri.value == whitenedPath })
    }
}
