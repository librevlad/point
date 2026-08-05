package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.KEY_NOTE
import com.point.core.flow.RealizerKind
import com.point.core.flow.SpeechKeyNeed
import com.point.core.flow.SpeechReadiness
import com.point.core.flow.yieldLabel
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что человек читает ДО тапа (#527, #529): чем два похожих действия отличаются и чего стоят.
 *
 * Прогон по экранам показал два места, где Point говорил одно слово о разных вещах. «Скан» и
 * «Скан+» обещали «вернёт картинку» — оба; «В Word» и «В Word+» обещали «вернёт документ Word» —
 * оба. При этом знак «плюс» значил разное: у одного платную модель в сети, у другого местный
 * конвейер без сети и без денег. Выбирать было нечем, и цену выбора человек узнавал тапом.
 *
 * Проверяется здесь дословный текст строки — тот же, что увидит человек. Это намеренно: подпись
 * и есть продукт этого среза, и «какая-нибудь непустая строка» прошла бы и на сломанном экране.
 */
class ActionsSayTheirPriceTest {

    private val image = ObjectState(ObjectKind.IMAGE)
    private val text = ObjectState(ObjectKind.TEXT)

    /** Строка под названием действия — ровно та, что рисует экран объекта. */
    private fun said(cap: Capability, state: ObjectState) =
        yieldLabel(cap.yields(state), cap.intents(state).first())

    // --- #527: двойники говорят разное ---

    @Test
    fun `два скана обещают разное, и по обещанию видно какой зачем`() {
        val plain = said(ScanCapability(), image)
        val colour = said(ScanPlusCapability(), image)

        assertEquals("вернёт чёрно-белую страницу", plain)
        assertEquals("вернёт картинку · дольше, зато на устройстве", colour)
        assertNotEquals("двойники снова неразличимы", plain, colour)
    }

    @Test
    fun `«плюс» у скана убран — этот знак остался за AI-двойником`() {
        // Один знак на двух разных ценах — это не сокращение, а загадка. «Скан+» был местным и
        // бесплатным, «В Word+» — платным и сетевым.
        assertEquals("Скан с цветом", ScanPlusCapability().label(image))
        assertEquals("В Word+", WordPlusCapability(aiKeysReady).label(text))
    }

    @Test
    fun `два «в Word» обещают разное — и сказано, что именно уезжает`() {
        val local = said(WordCapability(), text)
        val cloud = WordPlusCapability(aiKeysReady)

        assertEquals("вернёт документ Word", local)
        assertEquals("вернёт документ Word · текст уйдёт в сервис", said(cloud, text))
        // Слово выбирается по входу: с фотографии в сервис уезжает снимок, а не «текст».
        assertEquals("вернёт документ Word · снимок уйдёт в сервис", said(cloud, image))
    }

    @Test
    fun `местный двойник о сети не заикается`() {
        // Обратная проверка к предыдущей: приписка про сервис обязана стоять только там, где
        // объект правда уходит. Иначе она обесценится на всех строках сразу.
        assertFalse("уйдёт" in said(WordCapability(), image))
        assertFalse("уйдёт" in said(ScanCapability(), image))
        assertFalse("уйдёт" in said(ScanPlusCapability(), image))
    }

    // --- #529: «нужен ключ» видно до тапа ---

    /** Все действия, которые живут ключом модели, — и состояния, в которых они предлагаются. */
    private fun modelActions(keys: com.point.core.flow.AiReadiness) = listOf(
        AiCapability(keys) to image,
        TranslateCapability(keys) to text,
        UnderstandCapability(keys) to text,
        ExcelCapability(keys) to image,
        WordPlusCapability(keys) to text,
        ShoppingListCapability(keys) to ObjectState(ObjectKind.TEXT, setOf(Feature.IS_RECIPE)),
        JobReplyCapability(keys) to ObjectState(ObjectKind.TEXT, setOf(Feature.IS_JOB)),
    )

    @Test
    fun `без ключа каждое действие модели договаривает это в имени`() {
        modelActions(aiKeysMissing).forEach { (cap, state) ->
            assertTrue(
                "«${cap.label(state)}» промолчала о ключе",
                cap.label(state).endsWith(" · $KEY_NOTE"),
            )
        }
    }

    @Test
    fun `с ключом имена остаются именами действий`() {
        assertEquals(
            listOf("AI", "Перевести", "Понять", "В Excel", "В Word+", "Список покупок", "Отклик"),
            modelActions(aiKeysReady).map { (cap, state) -> cap.label(state) },
        )
    }

    @Test
    fun `объявленное действие остаётся действием — имя не подменяется настройками`() {
        // Договор среза: без ключа действие не прячется и не блёкнет, тап по нему ведёт на экран
        // ключей. Значит имя обязано начинаться с прежнего имени.
        modelActions(aiKeysMissing).zip(modelActions(aiKeysReady)).forEach { (without, with) ->
            val short = with.first.label(with.second)
            assertTrue(without.first.label(without.second).startsWith(short))
        }
    }

    @Test
    fun `расшифровка судит по своим ключам, а не по ключу модели`() {
        // Whisper слушает по ключу Groq. Общий ответ «ключа AI нет» приписал бы «нужен ключ»
        // работающему движку — то есть починил бы одну ложь новой.
        val speechReady = SpeechReadiness { emptyList() }
        val speechMissing = SpeechReadiness { listOf(SpeechKeyNeed("Whisper слушает по ключу Groq")) }
        val audio = ObjectState(ObjectKind.AUDIO)

        assertEquals("Расшифровать", TranscribeCapability(speechReady).label(audio))
        assertEquals("Расшифровать · $KEY_NOTE", TranscribeCapability(speechMissing).label(audio))
    }

    @Test
    fun `«Прочитать сильнее» ключа не требует — и не выдумывает требования`() {
        // Цепочка начинается с внешнего глаза, две из трёх ручек которого работают без
        // регистрации: в раздаваемой сборке без единого ключа это действие читает страницы.
        // Приписка отправила бы человека заводить ключ ради того, что и так работает.
        assertFalse(KEY_NOTE in CloudOcrCapability().label(image))
    }

    // --- Подделки для цепочки «Распознать текст» ---
    //
    // Ни одну из них не зовут: у реализаторов спрашивают только объявленный `meta.kind`. Поэтому
    // все они честно падают — вызов любой был бы ошибкой теста, и упасть тут лучше, чем притвориться.

    private val unusedStore = object : com.point.core.flow.ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("не зовут")
        override suspend fun ingestMultiple(sources: List<String>) = error("не зовут")
        override suspend fun put(result: com.point.core.model.ResultObject) = error("не зовут")
        override suspend fun children(collection: com.point.core.model.PointObject, limit: Int) = error("не зовут")
        override suspend fun readText(obj: com.point.core.model.PointObject, limit: Int) = error("не зовут")
        override suspend fun newScratchFile(extension: String) = error("не зовут")
        override suspend fun clear() = Unit
    }

    private val blindRecognizer = object : com.point.core.flow.TextRecognizer {
        override suspend fun recognize(obj: com.point.core.model.PointObject): String = error("не зовут")
    }

    private val closedEye = object : com.point.core.flow.ExternalEye {
        override fun available() = false
        override suspend fun read(obj: com.point.core.model.PointObject) = error("не зовут")
    }

    private val silentLlm = object : com.point.core.flow.LlmClient {
        override suspend fun run(obj: com.point.core.model.PointObject, prompt: String) = error("не зовут")
    }

    private val devicePrivacy = privacyAt(com.point.core.flow.PrivacyLevel.DEVICE_ONLY)

    // --- #558: запасной путь назван до тапа, а не после ---

    @Test
    fun `«Распознать текст» предупреждает о сервисе, потому что запасной путь сетевой`() {
        // Прогон по телефону владельца: подпись обещала просто «вернёт текст», а тап первым делом
        // спрашивал про отправку снимка в сервис. Цена появлялась ПОСЛЕ выбора.
        //
        // Обе половины проверяются вместе, и в этом весь смысл: слева — правда о цепочке, взятая
        // у самих реализаторов (`meta.kind`), справа — слова, которые про неё сказаны. Сделай
        // запасное звено местным — и требование к подписи честно отпадёт; убери слова, оставив
        // сетевое звено, — тест упадёт.
        val chain = listOf(
            DeviceOcrRealizer(unusedStore, blindRecognizer),
            ExternalEyeOcrRealizer(closedEye, unusedStore),
            CloudOcrRealizer(silentLlm, devicePrivacy),
        )
        val hasCloudFallback = chain.any { it.meta.kind != RealizerKind.LOCAL }
        val said = said(OcrCapability(), image)

        assertTrue("первым читает не устройство", chain.first().meta.kind == RealizerKind.LOCAL)
        assertTrue("запасного сетевого пути не стало — проверку пора менять", hasCloudFallback)
        assertEquals("вернёт текст · не выйдет на устройстве — предложит сервис", said)
    }

    @Test
    fun `местное чтение обещано местным — условие названо условием`() {
        // «предложит сервис» не значит «уедет всегда»: устройство читает первым, и наружу уходит
        // только то, что оно не взяло. Приговор вместо условия был бы свежей неправдой.
        val said = said(OcrCapability(), image)

        assertTrue(said, "не выйдет на устройстве" in said)
        assertFalse("сказано как о неизбежном", "уйдёт в сервис" in said)
    }

    @Test
    fun `цена сетевого чтения по-прежнему сказана подписью`() {
        // Эталон, по образцу которого сделаны остальные подписи этого среза.
        assertEquals(
            "вернёт текст · снимок уйдёт в сервис",
            yieldLabel(CloudOcrCapability().yields(image), Intent.UNDERSTAND),
        )
    }
}
