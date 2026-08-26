package com.point.executors

import com.point.core.flow.AiReadiness
import com.point.core.flow.FIX_TEXT_NOT_APPLIED
import com.point.core.flow.GraphState
import com.point.core.flow.LlmClient
import com.point.core.flow.META_ALT_SUFFIX
import com.point.core.flow.META_ENTITY_PREFIX
import com.point.core.flow.META_GRAPH_ROLE_PREFIX
import com.point.core.flow.META_ENTITY_TRACK
import com.point.core.flow.META_OCR_TEXT_REF
import com.point.core.model.ActionResult
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * «Исправить ошибки» и «Исправить сильнее» (#666). Обе двери — знание того же объекта;
 * разница ровно одна: во вторую уходит и сам снимок.
 *
 * У текстового объекта первая ступень проверяет сам текст (#1023): он и есть знание.
 */
class FixErrorsRealizerTest {

    @get:Rule val temp = TemporaryFolder()

    private var lastObject: PointObject? = null
    private var lastPrompt: String? = null

    private fun llm(answer: String) = object : LlmClient {
        override suspend fun run(obj: PointObject, prompt: String): ResultObject {
            lastObject = obj
            lastPrompt = prompt
            val f = File.createTempFile("fix-", ".txt").apply { deleteOnExit(); writeText(answer) }
            return ResultObject(ObjectKind.TEXT, "text/plain", ScratchRef(f.absolutePath))
        }
    }

    private fun fixer(answer: String) = FixErrorsRealizer(llm(answer), testKnowledge(), FileBackedStore)

    private val sender = META_GRAPH_ROLE_PREFIX + "sender"

    private fun photo(metadata: Map<String, String> = mapOf(sender to "Паринкн")) =
        PointObject("img", "image/jpeg", ScratchRef("/tmp/скан.jpg"), ObjectState(ObjectKind.IMAGE), metadata)

    /** Текст из живого прогона владельца (17.08.2026): пять опечаток и одна дата. */
    private val typed = "Превет, Иван! Эт тестовый тектс с пятью ашибками и опичатками, проверка 17.08.2026."

    private fun text(body: String = typed, metadata: Map<String, String> = mapOf(META_ENTITY_PREFIX + "date" to "17.08.2026")) =
        PointObject(
            "t", "text/plain",
            ScratchRef(temp.newFile().apply { writeText(body) }.absolutePath),
            ObjectState(ObjectKind.TEXT), metadata,
        )

    private val ready = AiReadiness { true }

    @Test
    fun `опечатка исправлена, прежнее значение осталось следом`() = runTest {
        val result = fixer("1 = Паринкін").perform(photo(), null)

        assertTrue("ожидалось знание, вышло: $result", result is ActionResult.Done)
        val done = result as ActionResult.Done
        assertEquals("Исправлено: 1", done.message)
        assertEquals("Паринкін", done.findings!!.metadata[sender])
        assertTrue("след прежнего значения потерян", done.findings!!.metadata.containsKey(sender + META_ALT_SUFFIX))
    }

    @Test
    fun `исправление — знание того же объекта, нового объекта не рождается`() = runTest {
        val done = fixer("1 = Паринкін").perform(photo(), null) as ActionResult.Done

        assertTrue("понимание не смеет плодить объекты", done.findings!!.objects.isEmpty())
    }

    @Test
    fun `нечего править — это исход, а не отказ и не пустая правка`() = runTest {
        val result = fixer("NONE").perform(photo(), null)

        val done = result as ActionResult.Done
        assertEquals("Ошибок не нашлось — знание оставлено как было", done.message)
        assertNull("пустая правка не должна ехать знанием", done.findings)
    }

    @Test
    fun `первая ступень снимок наружу не отправляет`() = runTest {
        fixer("NONE").perform(photo(), null)

        assertFalse(
            "наружу ушёл снимок, хотя просили проверить только знание: ${lastObject!!.mime}",
            lastObject!!.mime.startsWith("image/"),
        )
    }

    @Test
    fun `«сильнее» отправляет сам снимок и говорит об этом модели`() = runTest {
        FixErrorsStrongerRealizer(llm("NONE")).perform(photo(), null)

        assertTrue("снимок обязан уйти на сверку", lastObject!!.mime.startsWith("image/"))
        assertTrue("модель не предупреждена, что источник приложен", "снимком" in lastPrompt!!)
    }

    /**
     * Живой путь #1032: фото накладной Укрпошты, OCR прочёл «Експрес-накладна № …» со сбитой
     * последней цифрой, и «Понять» взяло 13-значный номер по слову рядом. Человек жмёт
     * «Исправить ошибки» (или «Исправить сильнее», где модель сверяет знание со снимком) — правка
     * обязана лечь. Текст объекта на этом пути не правится, и по старой странице исправленное
     * число не подтверждается ничем: единственный путь починки номера закрывался молча, а
     * «Отследить отправление» продолжало уходить по неверному номеру.
     */
    @Test
    fun `правка накладной над значениями ложится, а не гасится старой страницей`() = runTest {
        val was = "8806923102858"
        val now = "8806923102859"
        val page = temp.newFile().apply { writeText("Експрес-накладна № $was") }
        val parcel = photo(mapOf(META_ENTITY_TRACK to was, META_OCR_TEXT_REF to page.absolutePath))

        val first = fixer("1 = $now").perform(parcel, null) as ActionResult.Done
        val stronger = FixErrorsStrongerRealizer(llm("1 = $now")).perform(parcel, null) as ActionResult.Done

        assertEquals("«Исправить ошибки» оставила старый номер", now, first.findings!!.metadata[META_ENTITY_TRACK])
        assertEquals("«Исправить сильнее» оставила старый номер", now, stronger.findings!!.metadata[META_ENTITY_TRACK])
        assertTrue("человеку сказали, что править было нечего: ${first.message}", "не нашлось" !in first.message)
    }

    /**
     * Тот же живой путь дальше (#1032). Первая дверь правку положила, но сидекар она не
     * трогает — на странице по-прежнему стоит прочитанное число. Обе двери остаются на экране,
     * и человек жмёт «Исправить сильнее», где модель видит сам снимок и цифру чинит
     * по-настоящему. Прежнего прочтения на странице уже нет, и вторая правка выбрасывалась
     * молча: «Ошибок не нашлось», а «Отследить отправление» продолжало уходить по неверному
     * номеру первой двери.
     */
    @Test
    fun `правка поверх правки ложится, а не гаснет на прежней странице`() = runTest {
        val read = "8806923102858"
        val once = "8806923102859"
        val right = "8806923102851"
        val page = temp.newFile().apply { writeText("Експрес-накладна № $read") }
        val parcel = photo(mapOf(META_ENTITY_TRACK to read, META_OCR_TEXT_REF to page.absolutePath))

        val first = fixer("1 = $once").perform(parcel, null) as ActionResult.Done
        val fixedOnce = parcel.copy(metadata = parcel.metadata + first.findings!!.metadata)

        val stronger = FixErrorsStrongerRealizer(llm("1 = $right")).perform(fixedOnce, null) as ActionResult.Done

        assertEquals("вторая правка погасла на старой странице", right, stronger.findings?.metadata?.get(META_ENTITY_TRACK))
        assertTrue("человеку сказали, что править было нечего: ${stronger.message}", "не нашлось" !in stronger.message)
    }

    @Test
    fun `подтверждённое человеком в модель не уходит`() = runTest {
        val confirmed = photo(
            mapOf(sender to "Паринкн", sender + com.point.core.flow.META_SOURCE_SUFFIX to Provenance.HUMAN.wire),
        )

        val result = fixer("1 = Паринкін").perform(confirmed, null)

        assertTrue("исправлять было нечего — слово человека трогать нельзя", result is ActionResult.Failure)
        assertNull("до модели дело дойти не должно", lastPrompt)
    }

    @Test
    fun `дверь появляется только там, где есть что исправлять`() {
        val cap = FixErrorsCapability(ready)
        val withKnowledge = GraphState(photo())
        val bare = GraphState(photo(metadata = emptyMap()))

        assertTrue(cap.accepts(withKnowledge))
        assertFalse("на объекте без знания двери быть не должно", cap.accepts(bare))
    }

    @Test
    fun `«сильнее» предлагается только там, где есть источник для сверки`() {
        val cap = FixErrorsStrongerCapability(ready)

        assertTrue(cap.accepts(GraphState(photo())))
        assertFalse("у текста сверять знание не с чем", cap.accepts(GraphState(text())))
    }

    @Test
    fun `платное, сетевое, требующее ключа — обе двери`() {
        listOf(FixErrorsCapability(ready).meta, FixErrorsStrongerCapability(ready).meta).forEach { meta ->
            assertTrue(meta.network)
            assertTrue(meta.auth)
            assertEquals(com.point.core.flow.Cost.PAID, meta.cost)
        }
    }

    // ---- Текстовый объект (#1023): проверяется сам текст, он и есть знание ----

    @Test
    fun `у текста в модель уходит сам текст, а не список значений`() = runTest {
        fixer("NONE").perform(text(), null)

        assertTrue("текст не дошёл до модели: $lastPrompt", typed in lastPrompt!!)
        assertFalse("модели снова выдали сводку значений вместо текста", "1 = 17.08.2026" in lastPrompt!!)
    }

    @Test
    fun `исправленный текст ложится знанием того же объекта, дельта видна в итоге`() = runTest {
        val result = fixer("Превет = Привет\nтектс = текст").perform(text(), null)

        assertTrue("ожидалось знание, вышло: $result", result is ActionResult.Done)
        val done = result as ActionResult.Done
        val landed = done.findings!!.metadata[META_OCR_TEXT_REF]
        assertTrue("исправленный текст не стал прочтением объекта", landed != null)
        val read = File(landed!!).readText()
        assertTrue("правка не легла в текст: $read", "Привет, Иван! Эт тестовый текст с пятью ашибками" in read)
        assertTrue("нового объекта быть не должно", done.findings!!.objects.isEmpty())
        listOf("Превет", "Привет", "тектс", "текст").forEach { word ->
            assertTrue("в итоге не видно дельты «$word»: ${done.message}", word in done.message)
        }
    }

    @Test
    fun `текст без правок — «ошибок не нашлось» сказано про проверенный текст`() = runTest {
        val done = fixer("NONE").perform(text(), null) as ActionResult.Done

        assertTrue("модель текста не видела, а итог — приговор тексту", typed in lastPrompt!!)
        assertNull("пустая правка не должна ехать знанием", done.findings)
        assertTrue("без правок итог не может звучать как «исправлено»: ${done.message}", "Исправлено" !in done.message)
    }

    @Test
    fun `повторная правка идёт поверх уже исправленного текста, а не исходника`() = runTest {
        val earlier = temp.newFile().apply { writeText("Привет, Иван! Эт тестовый текст.") }
        val once = text(metadata = mapOf(META_OCR_TEXT_REF to earlier.absolutePath))

        fixer("NONE").perform(once, null)

        assertTrue("в модель ушёл исходник, а не текущее прочтение: $lastPrompt", "Привет, Иван! Эт тестовый текст." in lastPrompt!!)
        assertFalse("исходник с уже исправленными опечатками не должен уходить снова", typed in lastPrompt!!)
    }

    @Test
    fun `дверь на тексте открыта и без единого значения — текст и есть знание`() {
        val cap = FixErrorsCapability(ready)

        assertTrue(cap.accepts(GraphState(text(metadata = emptyMap()))))
    }

    @Test
    fun `правки предложены, но ни одна не легла — это срыв операции, а не «ошибок не нашлось»`() = runTest {
        // Модель процитировала фрагмент не так, как он стоит в тексте.
        val result = fixer("Привет, Иван = Привет, Иван!\nтекстс = текст").perform(text(), null)

        assertTrue("срыв правки выдан за знание: $result", result is ActionResult.Failure)
        val failure = result as ActionResult.Failure
        assertTrue("повторить можно — срыв не окончательный", failure.recoverable)
        assertTrue("«не нашлось» про текст, в котором нашлось: ${failure.reason}", "не нашлось" !in failure.reason)
        assertEquals(FIX_TEXT_NOT_APPLIED, failure.reason)
    }

    @Test
    fun `значения, вычитанные из текста, следуют за его правкой`() = runTest {
        val cut = text(
            body = "Відправник: Паринкн, 01.12.2020",
            metadata = mapOf(sender to "Паринкн", META_ENTITY_PREFIX + "date" to "01.12.2020"),
        )

        val corrected = "Паринкін"

        val done = fixer("Паринкн = $corrected").perform(cut, null) as ActionResult.Done

        assertEquals(corrected, done.findings!!.metadata[sender])
        assertTrue("след прежнего значения потерян", done.findings!!.metadata.containsKey(sender + META_ALT_SUFFIX))
        assertFalse("значение, которого правка не касалась, не трогается", done.findings!!.metadata.containsKey(META_ENTITY_PREFIX + "date"))
        assertTrue("исправленный текст лёг прочтением", File(done.findings!!.metadata.getValue(META_OCR_TEXT_REF)).readText().startsWith("Відправник: Паринкін"))
    }

    /**
     * Живой путь #1032: у текстового объекта накладная берётся по слову-подписи рядом, и
     * страница, на которой это слово стоит, — сам текст. Без неё правка знания молча
     * выбрасывалась гейтом формы, человек читал «Исправлено», а «Отследить отправление»
     * уходило по старому номеру.
     */
    @Test
    fun `накладная со словом-подписью едет за правкой текста, а не остаётся старым номером`() = runTest {
        val was = "8806923102858"
        val now = "8806923102859"
        val parcel = text(
            body = "Експрес-накладна № $was",
            metadata = mapOf(META_ENTITY_TRACK to was),
        )

        val done = fixer("$was = $now").perform(parcel, null) as ActionResult.Done

        assertEquals("отслеживание осталось бы по старому номеру", now, done.findings!!.metadata[META_ENTITY_TRACK])
        assertEquals(now, File(done.findings!!.metadata.getValue(META_OCR_TEXT_REF)).readText().takeLast(now.length))
    }

    @Test
    fun `длинный текст уходит в модель окном, правка ложится на весь текст, итог называет проверенную часть`() = runTest {
        val long = "Превет, Иван! " + (1..5000).joinToString(" ") { "слово$it" } + " Превет ещё раз."

        val done = fixer("Превет = Привет").perform(text(body = long), null) as ActionResult.Done

        assertTrue("в модель ушёл весь текст без предела: ${lastPrompt!!.length}", lastPrompt!!.length < long.length)
        assertFalse("хвост текста в запрос не влезал и не должен был", "слово5000" in lastPrompt!!)
        val landed = File(done.findings!!.metadata.getValue(META_OCR_TEXT_REF)).readText()
        assertTrue("хвост текста потерян при записи прочтения", landed.endsWith("Привет ещё раз."))
        assertTrue("итог молчит о том, что проверено не всё: ${done.message}", "проверено начало" in done.message)
    }
}
