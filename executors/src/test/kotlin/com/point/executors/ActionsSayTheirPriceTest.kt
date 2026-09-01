package com.point.executors

import com.point.core.flow.CloudOcrCapability
import com.point.core.flow.CloudOcrRealizer
import com.point.core.flow.DeviceOcrRealizer
import com.point.core.flow.ExternalEyeOcrRealizer

import com.point.core.flow.JobReplyCapability
import com.point.core.flow.ShoppingListCapability
import com.point.core.flow.WordCapability
import com.point.core.flow.WordPlusCapability

import com.point.core.flow.AiCapability
import com.point.core.flow.TranslateCapability
import com.point.core.flow.UnderstandCapability

import com.point.core.flow.ExcelCapability
import com.point.core.flow.capabilities.OcrCapability
import com.point.core.flow.Capability
import com.point.core.flow.KEY_NOTE
import com.point.core.flow.OfficeAlwaysHere
import com.point.core.flow.RealizerKind
import com.point.core.flow.SpeechKeyNeed
import com.point.core.flow.SpeechReadiness
import com.point.core.flow.yieldLabel
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.executors.di.CapabilityModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionsSayTheirPriceTest {

    private val image = ObjectState(ObjectKind.IMAGE)
    private val text = ObjectState(ObjectKind.TEXT)

    // Подписи может не быть вовсе (#629, #582) — для проверок «сказано / не сказано» это то
    // же самое, что не сказать ничего.
    private fun said(cap: Capability, state: ObjectState) =
        yieldLabel(cap.yields(state)).orEmpty()

    @Test
    fun `два скана обещают разное, и по обещанию видно какой зачем`() {
        val plain = said(ScanCapability(), image)
        val colour = said(ScanPlusCapability(), image)

        assertEquals("чёрно-белую страницу", plain)
        assertEquals("картинку · дольше, зато на устройстве", colour)
        assertNotEquals("двойники снова неразличимы", plain, colour)
    }

    @Test
    fun `«плюс» у скана убран — этот знак остался за AI-двойником`() {

        assertEquals("Скан с цветом", ScanPlusCapability().label(image))
        assertEquals("В Word+", WordPlusCapability(aiKeysReady).label(text))
    }

    @Test
    fun `два «в Word» обещают разное — и сказано, что именно уезжает`() {
        val local = said(WordCapability(), text)
        val cloud = WordPlusCapability(aiKeysReady)

        assertEquals("документ Word", local)
        assertEquals("документ Word · текст уйдёт в сервис", said(cloud, text))

        assertEquals("документ Word · снимок уйдёт в сервис", said(cloud, image))
    }

    @Test
    fun `местный двойник о сети не заикается`() {

        assertFalse("уйдёт" in said(WordCapability(), image))
        assertFalse("уйдёт" in said(ScanCapability(), image))
        assertFalse("уйдёт" in said(ScanPlusCapability(), image))
    }

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

        modelActions(aiKeysMissing).zip(modelActions(aiKeysReady)).forEach { (without, with) ->
            val short = with.first.label(with.second)
            assertTrue(without.first.label(without.second).startsWith(short))
        }
    }

    @Test
    fun `расшифровка судит по своим ключам, а не по ключу модели`() {

        val speechReady = SpeechReadiness { emptyList() }
        val speechMissing = SpeechReadiness { listOf(SpeechKeyNeed("Whisper слушает по ключу Groq")) }
        val audio = ObjectState(ObjectKind.AUDIO)

        assertEquals("Расшифровать", TranscribeCapability(speechReady).label(audio))
        assertEquals("Расшифровать · $KEY_NOTE", TranscribeCapability(speechMissing).label(audio))
    }

    @Test
    fun `«Прочитать сильнее» ключа не требует — и не выдумывает требования`() {

        assertFalse(KEY_NOTE in CloudOcrCapability().label(image))
    }

    private val unusedStore = object : com.point.core.flow.ObjectStore {
        override suspend fun ingest(sourceUri: String, mime: String) = error("не зовут")
        override suspend fun ingestMultiple(sources: List<String>) = error("не зовут")
        override suspend fun put(
            result: com.point.core.model.ResultObject,
            from: com.point.core.model.PointObject?,
            by: com.point.core.model.CapabilityId?,
        ) = error("не зовут")
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

    // Слово о дороге чтения — телефона, а не общего словаря (#1021). Проверяется не способность,
    // собранная руками с нужным словом, а та, которую телефон и правда раздаёт: общий словарь
    // в том виде, в каком его кладёт в набор CapabilityModule.
    private val phoneOcr: Capability =
        CapabilityModule.sharedCaps(OfficeAlwaysHere, AccountForTests()).first { it.id == OcrCapability.ID }

    @Test
    fun `«Распознать текст» предупреждает о сервисе, потому что запасной путь сетевой`() {

        val chain = listOf(
            DeviceOcrRealizer(unusedStore, blindRecognizer),
            ExternalEyeOcrRealizer(closedEye, unusedStore),
            CloudOcrRealizer(silentLlm, devicePrivacy),
        )
        val hasCloudFallback = chain.any { it.meta.kind != RealizerKind.LOCAL }
        val said = said(phoneOcr, image)

        assertTrue("первым читает не устройство", chain.first().meta.kind == RealizerKind.LOCAL)
        assertTrue("запасного сетевого пути не стало — проверку пора менять", hasCloudFallback)
        assertEquals("текст · сначала на телефоне, потом спрошу про сервис", said)
    }

    @Test
    fun `местное чтение обещано местным — условие названо условием`() {

        val said = said(phoneOcr, image)

        assertTrue(said, "сначала на телефоне" in said)
        assertFalse("сказано как о неизбежном", "уйдёт в сервис" in said)
    }

    @Test
    fun `цена сетевого чтения по-прежнему сказана подписью`() {

        assertEquals(
            "текст точнее · снимок уйдёт в сервис",
            yieldLabel(CloudOcrCapability().yields(image)),
        )
    }
}
