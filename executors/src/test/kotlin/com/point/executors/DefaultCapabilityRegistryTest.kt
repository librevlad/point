package com.point.executors

import com.point.core.flow.FindCapability
import com.point.core.flow.PagesCapability
import com.point.core.flow.TranscribeCapability
import com.point.core.flow.AiCapability
import com.point.core.flow.TranslateCapability

import com.point.core.flow.capabilities.PdfCapability
import com.point.core.flow.capabilities.ArchiveCapability
import com.point.core.flow.capabilities.OfficeCapability
import com.point.core.flow.capabilities.ImageCapability
import com.point.core.flow.capabilities.DropLinkCapability
import com.point.core.flow.capabilities.OcrCapability
import com.point.core.flow.GraphState
import com.point.core.flow.LinkedPc
import com.point.core.flow.META_UNUSABLE_REASON
import com.point.core.flow.PcLinks
import com.point.core.flow.PcRemoteAction
import com.point.core.flow.Latency
import com.point.core.model.BubbleTier
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCapabilityRegistryTest {

    private val registry = DefaultCapabilityRegistry(
        capabilities = setOf(
            ShareCapability(),
            SaveCapability(),
            SaveAllCapability(),
            ShareAllCapability(),
            MergePdfCapability(),
            ScanPdfCapability(),
            OpenCapability(),
            PdfCapability(),
            PagesCapability(),
            ImageCapability(),
            ArchiveCapability(),
            TranslateCapability(aiKeysReady),
            AiCapability(aiKeysReady),
            OpenUrlCapability(),
            OfficeCapability(),
            ScanCapability(),
            OcrCapability(),
            TranscribeCapability { emptyList() },
        ),
        policy = DefaultBubblePolicy(),
    )

    private fun idsFor(state: ObjectState) = idsFor(registry, state)

    private fun idsFor(registry: DefaultCapabilityRegistry, state: ObjectState) =
        registry.bubblesFor(state).map { it.capabilityId.value }.toSet()

    @Test
    fun `image offers image tools (incl scan and OCR) and the universal ones`() {
        val ids = idsFor(ObjectState(ObjectKind.IMAGE))
        assertTrue(ids.containsAll(setOf("share", "save", "ai", "image", "pdf", "scan", "ocr")))
        assertTrue(setOf("archive", "translate", "office").none { it in ids })
    }

    @Test
    fun `bubble order is deterministic and AI comes last`() {
        val order = registry.bubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.capabilityId.value }

        assertEquals(listOf("image", "ocr", "pdf", "scan", "open", "save", "share", "ai"), order)
    }

    @Test
    fun `pdf can be split into a pages collection`() {
        assertTrue("pdf-pages" in idsFor(ObjectState(ObjectKind.PDF)))
        assertFalse("pdf-pages" in idsFor(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `pdf bubble flips its label by direction`() {
        val onPdf = registry.bubblesFor(ObjectState(ObjectKind.PDF)).first { it.capabilityId.value == "pdf" }
        val onImage = registry.bubblesFor(ObjectState(ObjectKind.IMAGE)).first { it.capabilityId.value == "pdf" }
        assertEquals("Извлечь текст", onPdf.title)
        assertEquals("В PDF", onImage.title)
    }

    @Test
    fun `office offers extract-text plus to-PDF plus universal`() {
        val ids = idsFor(ObjectState(ObjectKind.OFFICE))
        assertTrue(ids.containsAll(setOf("office", "pdf", "share", "save", "ai")))
        assertTrue(setOf("translate", "image", "archive").none { it in ids })
    }

    @Test
    fun `голосовое — объект с действиями, а не файл, про который нечего сказать`() {

        val ids = idsFor(ObjectState(ObjectKind.AUDIO))

        assertTrue(ids.containsAll(setOf("transcribe", "share", "save", "open", "ai")))

        assertTrue(setOf("ocr", "scan", "image", "archive", "office", "pdf").none { it in ids })
    }

    @Test
    fun `расшифровка — понимание, и на первый экран она не тащит сеть`() {
        val bubble = registry.bubblesFor(ObjectState(ObjectKind.AUDIO))
            .first { it.capabilityId.value == "transcribe" }

        assertEquals(Intent.UNDERSTAND, bubble.intent)
        assertEquals("сетевое — значит уровень AI, каким бы полезным оно ни было", BubbleTier.AI, bubble.tier)

        // Расшифровка — знание той же записи (#1097): объект остаётся записью с текстом.
        assertEquals(ObjectKind.AUDIO, bubble.expectedNextState?.kind)
        assertTrue(bubble.expectedNextState?.has(com.point.core.model.Feature.HAS_TEXT) == true)
    }

    @Test
    fun `open is offered for files but not for urls or collections`() {
        assertTrue("open" in idsFor(ObjectState(ObjectKind.PDF)))
        assertTrue("open" in idsFor(ObjectState(ObjectKind.UNKNOWN)))

        assertFalse("open" in idsFor(ObjectState(ObjectKind.URL)))
        assertFalse("open" in idsFor(ObjectState(ObjectKind.COLLECTION)))
    }

    @Test
    fun `a collection offers save-all and share-all but hides single-object actions`() {
        val ids = idsFor(ObjectState(ObjectKind.COLLECTION))
        assertTrue(ids.containsAll(setOf("save-all", "share-all", "merge-pdf", "scan-pdf")))

        assertTrue(setOf("share", "save", "ai", "open").none { it in ids })
    }

    @Test
    fun `open-url is feature-gated on text but immediate for a uri-list`() {
        assertFalse("open-url" in idsFor(ObjectState(ObjectKind.TEXT)))
        assertTrue("open-url" in idsFor(ObjectState(ObjectKind.TEXT, setOf(Feature.HAS_URL))))
        assertTrue("open-url" in idsFor(ObjectState(ObjectKind.URL)))
    }

    @Test
    fun `«Дать ссылку» есть у файла, но не у самой ссылки`() {

        val dropRegistry = DefaultCapabilityRegistry(
            capabilities = setOf(DropLinkCapability(), OpenUrlCapability()),
            policy = DefaultBubblePolicy(),
        )

        assertTrue("drop-link" in idsFor(dropRegistry, ObjectState(ObjectKind.PDF)))
        assertTrue("drop-link" in idsFor(dropRegistry, ObjectState(ObjectKind.AUDIO)))
        assertFalse("drop-link" in idsFor(dropRegistry, ObjectState(ObjectKind.URL)))

        assertTrue("open-url" in idsFor(dropRegistry, ObjectState(ObjectKind.URL)))
    }

    @Test
    fun `результат «Дать ссылку» не годится ей же на вход`() {

        // «Выложен» — знание об объекте (#1071): человек остаётся у исходника, а узел
        // ссылки — находка. Родившейся ссылке действие по-прежнему не предлагается.
        val drop = DropLinkCapability()

        assertEquals(ObjectState(ObjectKind.PDF), drop.produces(ObjectState(ObjectKind.PDF)))
        assertFalse("иначе — ссылка на ссылку", drop.accepts(ObjectState(ObjectKind.URL)))
    }

    @Test
    fun `bubble tier derives from meta — cloud is AI, instant local is INSTANT, transforms are SMART`() {
        val bubbles = registry.bubblesFor(ObjectState(ObjectKind.IMAGE))
        fun tier(id: String) = bubbles.first { it.capabilityId.value == id }.tier
        assertEquals(BubbleTier.AI, tier("ai"))
        assertEquals(BubbleTier.INSTANT, tier("share"))
        assertEquals(BubbleTier.SMART, tier("ocr"))
    }

    @Test
    fun `распознавание на устройстве объявлено долгим, а место пузырька не сдвинулось`() {

        assertEquals(Latency.SLOW, OcrCapability().meta.latency)
        assertEquals(
            BubbleTier.SMART,
            registry.bubblesFor(ObjectState(ObjectKind.IMAGE)).first { it.capabilityId.value == "ocr" }.tier,
        )
        assertEquals(
            listOf("image", "ocr", "pdf", "scan", "open", "save", "share", "ai"),
            registry.bubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.capabilityId.value },
        )
    }

    @Test
    fun `each bubble carries its capability's primary intent (for grouping on the object screen)`() {
        val bubbles = registry.bubblesFor(ObjectState(ObjectKind.IMAGE))
        fun intent(id: String) = bubbles.first { it.capabilityId.value == id }.intent
        assertEquals(Intent.UNDERSTAND, intent("ocr"))
        assertEquals(Intent.PREPARE, intent("pdf"))
        assertEquals(Intent.OPEN, intent("open"))
        assertEquals(Intent.SEND, intent("share"))
        assertEquals(Intent.UNDERSTAND, intent("ai"))
    }

    @Test
    fun `byId round-trips`() {
        val bubble = registry.bubblesFor(ObjectState(ObjectKind.TEXT)).first()
        assertEquals(bubble.capabilityId, registry.byId(bubble.capabilityId).id)
    }

    @Test
    fun `an image surfaces translate and open-url as almost-available (needs OCR first)`() {
        val latent = registry.latentBubblesFor(ObjectState(ObjectKind.IMAGE))
        assertTrue("Перевести" in latent.map { it.title })
        assertTrue(latent.all { it.missing.contains("распознайте текст") })
        assertTrue("capped so it informs, not clutters", latent.size <= 2)
    }

    @Test
    fun `text has no almost-available translate — it already accepts`() {
        assertFalse("Перевести" in registry.latentBubblesFor(ObjectState(ObjectKind.TEXT)).map { it.title })
    }

    private class FixedPc(private val pc: LinkedPc?) : PcLinks {
        override fun current() = pc
        override suspend fun save(pc: LinkedPc) = Unit
        override suspend fun clear() = Unit
    }

    private val printerless =
        PcRemoteAction("pc-print", "Напечатать на ПК", unavailable = "на компьютере нет принтера")

    private fun registryWithPc(action: PcRemoteAction, pc: LinkedPc?) = DefaultCapabilityRegistry(
        capabilities = setOf(
            ShareCapability(), SaveCapability(), OpenCapability(), PdfCapability(),
            ImageCapability(), TranslateCapability(aiKeysReady), AiCapability(aiKeysReady), OpenUrlCapability(),
            ScanCapability(), OcrCapability(),
            RemotePcCapability(action, FixedPc(pc)),
        ),
        policy = DefaultBubblePolicy(),
    )

    @Test
    fun `причина «нет принтера» не вытесняется повтором чужой подсказки`() {

        val latent = registryWithPc(printerless, LinkedPc("d-pc", "ПК", "ключ"))
            .latentBubblesFor(ObjectState(ObjectKind.IMAGE))

        assertTrue("объяснение компьютера обязано дойти", "Напечатать на ПК" in latent.map { it.title })
        assertEquals("на компьютере нет принтера", latent.first { it.title == "Напечатать на ПК" }.missing)
        assertEquals("два места — два разных объяснения", latent.size, latent.map { it.missing }.toSet().size)
        assertTrue("список не вырос", latent.size <= 2)
    }

    @Test
    fun `есть принтер — обычная кнопка, а не строка с причиной`() {
        val available = PcRemoteAction("pc-print", "Напечатать на ПК")
        val registry = registryWithPc(available, LinkedPc("d-pc", "ПК", "ключ"))

        assertTrue("pc-do:pc-print" in idsFor(registry, ObjectState(ObjectKind.IMAGE)))
        assertFalse(
            "Напечатать на ПК" in registry.latentBubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.title },
        )
    }

    @Test
    fun `без связи с компьютером не появляется ни кнопки, ни причины`() {

        val registry = registryWithPc(printerless, pc = null)
        val state = ObjectState(ObjectKind.IMAGE)

        assertFalse("pc-do:pc-print" in idsFor(registry, state))
        assertFalse("Напечатать на ПК" in registry.latentBubblesFor(state).map { it.title })
    }

    private fun registryWithoutScanPack() = DefaultCapabilityRegistry(
        capabilities = setOf(
            ScanCapability(), ScanPlusCapability(), OcrCapability(), ShareCapability(),
        ),
        policy = DefaultBubblePolicy(),
        availability = { id -> "нужен пакет обработки снимков".takeIf { id == ScanPlusCapability.ID } },
    )

    @Test
    fun `без пака «Скан с цветом» не встаёт в ряд с работающими`() {

        val ids = idsFor(registryWithoutScanPack(), ObjectState(ObjectKind.IMAGE))

        assertFalse("scan-plus" in ids)
        assertTrue("соседей это не трогает", ids.containsAll(setOf("scan", "ocr", "share")))
    }

    @Test
    fun `вместо ложного обещания — строка с причиной`() {

        val latent = registryWithoutScanPack().latentBubblesFor(ObjectState(ObjectKind.IMAGE))

        assertEquals(listOf("Скан с цветом"), latent.map { it.title })
        assertEquals("нужен пакет обработки снимков", latent.single().missing)
    }

    @Test
    fun `недоступное действие не приводит с собой и своё намерение`() {

        val onlyScanPlus = DefaultCapabilityRegistry(
            capabilities = setOf(ScanPlusCapability(), OcrCapability()),
            policy = DefaultBubblePolicy(),
            availability = { "нужен пакет обработки снимков" },
        )

        assertTrue(onlyScanPlus.bubblesFor(ObjectState(ObjectKind.IMAGE)).isEmpty())
    }

    @Test
    fun `есть чем выполнить — обычная строка действия, а не причина`() {
        val ids = idsFor(registry, ObjectState(ObjectKind.IMAGE))
        val latent = registry.latentBubblesFor(ObjectState(ObjectKind.IMAGE))

        assertTrue("scan" in ids)
        assertFalse("Скан" in latent.map { it.title })
    }

    // ---- #684/#685: годность видна и в подписи действия, а дверь не пропадает. ----

    private fun objectOf(state: ObjectState, metadata: Map<String, String> = emptyMap()) =
        PointObject("id", "text/plain", ScratchRef("/x"), state, metadata)

    /**
     * Негодному объекту читать себя не предлагается (#994, решение владельца 21.08.2026):
     * остальные двери остаются, и каждая несёт причину.
     */
    @Test
    fun `негодный объект — чтения не предлагаются, остальные двери несут причину`() {
        val reason = "Файл пустой — в нём нечего читать"
        val obj = objectOf(ObjectState(ObjectKind.TEXT, setOf(Feature.UNUSABLE)), mapOf(META_UNUSABLE_REASON to reason))

        val bubbles = registry.bubblesFor(GraphState(obj))

        assertTrue("действия остаются в списке", bubbles.isNotEmpty())
        assertTrue("негодному предложено чтение", bubbles.none { it.intent == Intent.UNDERSTAND })
        assertTrue(
            "двери, которые не читают, пропали",
            bubbles.map { it.capabilityId.value }.containsAll(setOf("share", "save", "open")),
        )
        assertTrue(bubbles.all { it.unusableReason == reason })
    }

    /**
     * Битый снимок предлагал «Понять», «Распознать текст», «Прочитать сильнее» и «AI» —
     * четыре способа прочитать то, что прочитать нельзя (#994). Решение владельца сказано
     * про чтения: превращения остаются на месте и несут причину подписью (#582).
     */
    @Test
    fun `битому снимку не предлагают ни распознать, ни AI — открыть и поделиться есть`() {
        val ids = idsOf(unfitImage(com.point.core.flow.READER_NOT_DECODED))

        assertTrue("негодному предложено чтение", setOf("ocr", "ai").none { it in ids })
        assertTrue("двери, которые не читают, пропали", ids.containsAll(setOf("share", "save", "open")))
        assertTrue("превращения ушли вместе с чтениями", ids.containsAll(setOf("scan", "image", "pdf")))
    }

    @Test
    fun `годный снимок по-прежнему читается — фильтр негодного его не касается`() {
        val ids = idsFor(ObjectState(ObjectKind.IMAGE))

        assertTrue(ids.containsAll(setOf("ocr", "ai", "share", "save", "open")))
    }

    /** Снимок, помеченный негодным по сигналу [signal], — так, как это делает предпросмотр. */
    private fun unfitImage(signal: String) = objectOf(
        ObjectState(ObjectKind.IMAGE, setOf(Feature.UNUSABLE)),
        mapOf(META_UNUSABLE_REASON to com.point.core.flow.readerFailure(signal, ObjectKind.IMAGE)),
    )

    private fun idsOf(obj: PointObject) =
        registry.bubblesFor(GraphState(obj)).map { it.capabilityId.value }.toSet()

    /**
     * PDF под паролем. `previewSource` бросает, предпросмотр метит объект негодным без гарда
     * `readerFailureIsFatal` (#1271) — а человеку сказано «Прочитать сейчас не вышло —
     * попробуйте ещё раз». Отнять при этом все чтения значит позвать пробовать и не оставить
     * чем: ни «Прочитать документ», ни «Понять», ни «AI».
     *
     * Дверь снимает сказанное о содержимом, а не голая метка. Ровно сюда же попадает и битый
     * PDF — живой объект #994: `PdfRenderer` бросает на нём `IOException` без слов словаря, и
     * чтения ему пока предлагаются. Героем при этом не становятся — «Извлечь» у негодного не
     * ведёт (`promisesExtraction`). Совсем чтения уйдут, когда #1271 приведёт сигналы
     * предпросмотра к словарю ридера и битый PDF назовут повреждённым: правило здесь править
     * для этого не придётся.
     */
    @Test
    fun `предпросмотр сорвался попыткой — чтения PDF остаются на месте`() {
        val locked = objectOf(
            ObjectState(ObjectKind.PDF, setOf(Feature.UNUSABLE)),
            mapOf(
                META_UNUSABLE_REASON to com.point.core.flow.readerFailure(
                    "Password required or incorrect password",
                    ObjectKind.PDF,
                ),
            ),
        )

        val bubbles = registry.bubblesFor(GraphState(locked))
        val ids = bubbles.map { it.capabilityId.value }

        assertTrue("человека зовут попробовать ещё раз, а пробовать нечем", "ai" in ids)
        assertTrue("причина по-прежнему сказана подписью", bubbles.all { it.unusableReason != null })
    }

    /**
     * Подсказка не переживает саму дверь (#1101): у негодного PDF «Найти в документе» не
     * предлагается — значит и «разложите на страницы» под ним не подсказывается. Иначе экран
     * одной строкой отказывает в чтении, а другой учит к нему готовиться.
     */
    @Test
    fun `негодному не подсказывают, как подготовиться к чтению`() {
        val withFind = DefaultCapabilityRegistry(
            capabilities = setOf(ShareCapability(), OpenCapability(), FindCapability()),
            policy = DefaultBubblePolicy(),
        )
        val broken = objectOf(
            ObjectState(ObjectKind.PDF, setOf(Feature.UNUSABLE)),
            mapOf(
                META_UNUSABLE_REASON to com.point.core.flow.readerFailure(
                    com.point.core.flow.READER_NOT_DECODED,
                    ObjectKind.PDF,
                ),
            ),
        )
        val fine = objectOf(ObjectState(ObjectKind.PDF))

        assertTrue(
            "подсказка чтения пережила само чтение",
            withFind.latentBubblesFor(GraphState(broken)).isEmpty(),
        )
        assertTrue(
            "годному подсказку отняли заодно",
            withFind.latentBubblesFor(GraphState(fine)).isNotEmpty(),
        )
    }

    /**
     * Список по одной форме объекта — не список человеку, а вопрос Discovery «откроет ли это
     * исследование новую дверь» (`DefaultEnrichment`). Годность там судить нечем, и судить не
     * надо: исследование, чья единственная новая дверь — чтение, у негодного объекта иначе
     * молча перестало бы считаться стоящим, и знание, возвращающее чтения, не пришло бы
     * никогда (#1101).
     */
    @Test
    fun `спекулятивный список Discovery негодность не судит`() {
        val ids = idsFor(ObjectState(ObjectKind.IMAGE, setOf(Feature.UNUSABLE)))

        assertTrue("Discovery потеряла чтение как новую дверь", ids.containsAll(setOf("ocr", "ai")))
    }

    /**
     * Эскиз не отрисовался, снимок не декодировался — это состояние операции, а не знание
     * (Конституция §13, ADR-0001 §9). У снимка, чей QR уже прочитан, дверь «Считать QR»
     * обязана остаться: иначе знание есть, а войти в него нечем. Живой путь #1101.
     */
    @Test
    fun `превью не отрисовалось, а QR прочитан — двери чтения остаются`() {
        val withQr = DefaultCapabilityRegistry(
            capabilities = setOf(
                ShareCapability(), OpenCapability(), OcrCapability(),
                ReadQrCapability(), AiCapability(aiKeysReady),
            ),
            policy = DefaultBubblePolicy(),
        )
        val obj = objectOf(
            ObjectState(ObjectKind.IMAGE, setOf(Feature.UNUSABLE, Feature.HAS_QR)),
            mapOf(
                META_UNUSABLE_REASON to com.point.core.flow.readerFailure(
                    com.point.core.flow.READER_NOT_DECODED,
                    ObjectKind.IMAGE,
                ),
                "entity.url" to "https://point.app/x",
            ),
        )

        val ids = withQr.bubblesFor(GraphState(obj)).map { it.capabilityId.value }

        assertTrue("знание о QR есть, а войти в него нечем", "read-qr" in ids)
        assertTrue("прочитанное не вернуло объекту его чтения", ids.containsAll(setOf("ocr", "ai")))
    }

    /** #570: обломок вместо архива — но передать его дальше человек по-прежнему может. */
    @Test
    fun `битым архивом всё ещё есть чем поделиться`() {
        val obj = objectOf(
            ObjectState(ObjectKind.ZIP, setOf(Feature.UNUSABLE)),
            mapOf(META_UNUSABLE_REASON to com.point.core.flow.BROKEN_ARCHIVE_REASON),
        )

        val bubbles = registry.bubblesFor(GraphState(obj))

        assertTrue("share" in bubbles.map { it.capabilityId.value })
    }

    @Test
    fun `обычный объект — у пузырьков причины нет`() {
        val obj = objectOf(ObjectState(ObjectKind.TEXT))

        val bubbles = registry.bubblesFor(GraphState(obj))

        assertTrue(bubbles.isNotEmpty())
        assertTrue(bubbles.all { it.unusableReason == null })
    }

    @Test
    fun `an image offers understand, prepare, open and send`() {
        assertEquals(
            setOf(Intent.UNDERSTAND, Intent.PREPARE, Intent.OPEN, Intent.SEND),
            registry.bubblesFor(ObjectState(ObjectKind.IMAGE)).map { it.intent }.toSet(),
        )
    }

    @Test
    fun `a collection has prepare and send but nothing to understand`() {
        assertEquals(
            setOf(Intent.PREPARE, Intent.SEND),
            registry.bubblesFor(ObjectState(ObjectKind.COLLECTION)).map { it.intent }.toSet(),
        )
    }

    @Test
    fun `pdf capability understands a PDF but prepares from an image`() {
        val pdf = registry.byId(CapabilityId("pdf"))
        assertEquals(setOf(Intent.UNDERSTAND), pdf.intents(ObjectState(ObjectKind.PDF)))
        assertEquals(setOf(Intent.PREPARE), pdf.intents(ObjectState(ObjectKind.IMAGE)))
    }

    @Test
    fun `intent derives from produces — ai understands, share sends, open opens`() {

        assertEquals(setOf(Intent.UNDERSTAND), AiCapability(aiKeysReady).intents(ObjectState(ObjectKind.IMAGE)))
        assertEquals(setOf(Intent.SEND), ShareCapability().intents(ObjectState(ObjectKind.IMAGE)))

        assertEquals(setOf(Intent.OPEN), OpenCapability().intents(ObjectState(ObjectKind.IMAGE)))
        assertEquals(setOf(Intent.OPEN), OpenUrlCapability().intents(ObjectState(ObjectKind.URL)))
    }

    @Test
    fun `a same-kind transform prepares, not sends — scan and compress a photo (issue 33)`() {

        assertEquals(setOf(Intent.PREPARE), ScanCapability().intents(ObjectState(ObjectKind.IMAGE)))
        assertEquals(setOf(Intent.PREPARE), ImageCapability().intents(ObjectState(ObjectKind.IMAGE)))

        assertEquals(setOf(Intent.SEND), ShareCapability().intents(ObjectState(ObjectKind.IMAGE)))
        assertEquals(setOf(Intent.SEND), SaveCapability().intents(ObjectState(ObjectKind.IMAGE)))
    }
}
