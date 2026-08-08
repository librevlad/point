package com.point.executors

import com.point.core.flow.capabilities.PdfCapability
import com.point.core.flow.capabilities.QrCapability
import com.point.core.flow.capabilities.ArchiveCapability
import com.point.core.flow.capabilities.OfficeCapability
import com.point.core.flow.capabilities.ImageCapability
import com.point.core.flow.capabilities.DropLinkCapability
import com.point.core.flow.capabilities.OFFICE_PDF_SUBSTANCE
import com.point.core.flow.capabilities.OcrCapability
import com.point.core.flow.Capability
import com.point.core.flow.LinkedPc
import com.point.core.flow.PcLinks
import com.point.core.flow.SpeechReadiness
import com.point.core.flow.capabilityInventory
import com.point.core.flow.derivedYield
import com.point.core.flow.inventoryProbes
import com.point.core.flow.yieldLabel
import com.point.core.model.ActionYield
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RealCapabilityInventoryTest {

    private val builtIn: List<Capability> = listOf(
        AiCapability(aiKeysReady), ArchiveCapability(), BlurBgCapability(),
        CallCapability(), CloudOcrCapability(), CopyCapability(), CopyCardCapability(),
        CorrectValueCapability(),
        CutoutCapability(), DropLinkCapability(), EmailCapability(), EventCapability(),
        ExcelCapability(aiKeysReady), ExtractAllCapability(), FindCapability(), ImageCapability(),
        JobReplyCapability(aiKeysReady), MapCapability(), MergePdfCapability(), OcrCapability(),
        OfficeCapability(), OpenCapability(), OpenInCapability(), OpenUrlCapability(),
        PagesCapability(), PcCapability(pairedPc), PdfCapability(),
        QrCapability(), ReadQrCapability(), RenewPeriodCapability(), ReplaceBgCapability(),
        SaveAllCapability(), SaveCapability(), SaveContactCapability(), ScanCapability(),
        ScanPdfCapability(), ScanPlusCapability(), ShareAllCapability(), ShareCapability(),
        ShoppingListCapability(aiKeysReady), SmsCapability(), TranscribeCapability(keysReady),
        TranslateCapability(aiKeysReady), UnderstandCapability(aiKeysReady), VCardCapability(), WordCapability(),
        WordPlusCapability(aiKeysReady),
    )

    // Пробы шире базовых видов: кадры извлечённых значений — полноправные объекты,
    // и способность, живущая только на них, обязана быть видна инвентарю.
    private val inventory = capabilityInventory(
        builtIn,
        com.point.core.flow.inventoryProbes(
            com.point.core.model.ObjectKind.entries + com.point.core.flow.EXTRACTED_KINDS,
        ),
    )

    @Test
    fun `таблица — что каждая способность принимает и что возвращает`() {
        val lines = inventory.map { e ->
            val takes = e.accepts.joinToString("·") { it.name }.ifEmpty { "—" }
            val gives = e.yields.joinToString(" / ") { yieldLabel(it, e.intents.first()) }
            val marks = buildList {
                if (e.network) add("сеть")
                if (e.paid) add("платно")
                if (e.auth) add("ключ")
                if (e.declaredOnly) add("сказала сама")
            }.joinToString(",").ifEmpty { "—" }
            "%-14s %-18s %-24s %-40s %s".format(e.id.value, e.label, takes, gives, marks)
        }
        println("=== Инвентаризация способностей (#491) — ${inventory.size} штук ===")
        lines.forEach(::println)
        println(summary())

        assertEquals("собраны не все способности реестра", builtIn.size, inventory.size)
    }

    private fun summary(): String = buildString {
        val byYield = inventory.groupingBy { kindOfYield(it) }.eachCount()
        appendLine("--- Итого ---")
        appendLine("способностей: ${inventory.size}")
        appendLine("возвращают новый объект: ${byYield["новый объект"] ?: 0}")
        appendLine("ничего не возвращают (отправят/откроют/покажут): ${byYield["ничего"] ?: 0}")
        appendLine("возвращают тот же объект понятым: ${byYield["тот же"] ?: 0}")
        appendLine("выход неизвестен заранее: ${byYield["неизвестно"] ?: 0}")
        appendLine("сказали о выходе сами (produces не хватило): ${inventory.count { it.declaredOnly }}")

        appendLine("  из них исправили суть (produces сливал разное): ${reshaped.size} ${reshaped.map { it.value }}")
        appendLine("  из них уточнили слово (вид слишком широк): ${inventory.count { it.declaredOnly } - reshaped.size}")
        appendLine("уходят в сеть по объявлению: ${inventory.count { it.network }}")
        appendLine("требуют ключа: ${inventory.count { it.auth }}")
        ObjectKind.entries.forEach { kind ->
            appendLine("принимают ${kind.name}: ${inventory.count { kind in it.accepts }}")
        }
    }

    private val reshaped: List<com.point.core.model.CapabilityId> = builtIn.filter { c ->
        inventoryProbes().filter(c::accepts).any { s -> shapeOf(c.yields(s)) != shapeOf(derivedYield(c, s)) }
    }.map { it.id }

    private fun shapeOf(y: ActionYield): String = when (y) {
        is ActionYield.New -> "new"

        ActionYield.None, ActionYield.Copied -> "none"
        ActionYield.Same -> "same"
        ActionYield.Unknown -> "unknown"
    }

    @Test
    fun `produces разошёлся с реальностью ровно там, где сказано вслух`() {

        assertEquals(listOf("understand"), reshaped.map { it.value })
    }

    private fun kindOfYield(e: com.point.core.flow.CapabilityEntry): String = when {
        e.yields.any { it is ActionYield.New } -> "новый объект"
        e.yields.contains(ActionYield.Same) -> "тот же"
        e.yields.contains(ActionYield.Unknown) -> "неизвестно"
        else -> "ничего"
    }

    @Test
    fun `в таблице ровно столько способностей, сколько раздаёт реестр`() {

        val bound = com.point.executors.di.CapabilityModule::class.java.declaredMethods
            .count { it.returnType == Capability::class.java } +
            com.point.core.flow.capabilities.sharedCapabilities().size

        assertEquals("в CapabilityModule способностей больше, чем в таблице", bound, builtIn.size)
    }

    @Test
    fun `каждая способность кому-то предлагается`() {

        val dead = inventory.filter { it.accepts.isEmpty() }

        assertTrue("никому не предлагаются: ${dead.map { it.id.value }}", dead.isEmpty())
    }

    @Test
    fun `каждая способность говорит, что вернёт, и говорит это словами`() {
        inventory.forEach { e ->
            assertTrue("${e.id.value} не сказала о выходе ничего", e.yields.isNotEmpty())
            e.yields.forEach { y ->
                val said = yieldLabel(y, e.intents.first())
                assertTrue("${e.id.value} промолчала о выходе", said.isNotBlank())
            }
        }
    }

    @Test
    fun `обещание вернуть объект не встаёт под «Отправить»`() {

        val liars = inventory.filter { e ->
            e.yields.any { it is ActionYield.New } && e.intents == setOf(Intent.SEND)
        }

        assertTrue("объявлены отправкой, а возвращают объект: ${liars.map { it.id.value }}", liars.isEmpty())
    }

    @Test
    fun `терминальное не встаёт под «Превратить»`() {

        val liars = inventory.filter { e ->
            e.yields == listOf(ActionYield.None) && e.intents == setOf(Intent.PREPARE)
        }

        assertTrue("объявлены превращением, а не вернут ничего: ${liars.map { it.id.value }}", liars.isEmpty())
    }

    @Test
    fun `вид OFFICE никому не показывается общим словом`() {

        val vague = inventory.filter { e ->
            e.yields.any { it is ActionYield.New && it.kind == ObjectKind.OFFICE && it.noun == null }
        }

        assertTrue("вернут OFFICE, не сказав что именно: ${vague.map { it.id.value }}", vague.isEmpty())
    }

    @Test
    fun `сетевое объявлено сетевым, и это видно до тапа`() {

        val cloudIds = setOf("ai", "understand", "excel", "word-plus", "translate", "transcribe", "ocr-cloud")

        cloudIds.forEach { id ->
            val e = inventory.single { it.id.value == id }
            assertTrue("«${e.label}» не объявлена сетевой", e.network)
        }
    }

    private fun signature(c: Capability, state: com.point.core.model.ObjectState) =
        c.label(state) + " · " + yieldLabel(c.yields(state), c.intents(state).first())

    @Test
    fun `у двух действий, предлагаемых на одном объекте, не бывает одинаковой подписи`() {

        val clashes = inventoryProbes().flatMap { state ->
            builtIn.filter { it.accepts(state) }
                .groupBy { signature(it, state) }
                .filterValues { it.size > 1 }
                .map { (said, caps) -> "${state.kind}: ${caps.map { c -> c.id.value }} — «$said»" }
        }

        assertTrue(clashes.distinct().joinToString("\n"), clashes.isEmpty())
    }

    @Test
    fun `пересказ офисного файла помечен тем же словом, каким обещан`() {

        val office = com.point.core.model.ObjectState(ObjectKind.OFFICE)
        val out = retoldFromOffice(
            com.point.core.model.ActionResult.Success(
                com.point.core.model.ResultObject(
                    ObjectKind.PDF, "application/pdf", com.point.core.model.ScratchRef("/x"),
                ),
            ),
        ) as com.point.core.model.ActionResult.Success

        assertEquals(OFFICE_PDF_SUBSTANCE, out.result.metadata[com.point.core.flow.META_YIELD_NOUN])
        assertNull(
            "обещание и вышедшее разошлись",
            com.point.core.flow.yieldSurprise(
                PdfCapability().yields(office),
                ObjectKind.PDF,
                out.result.metadata[com.point.core.flow.META_YIELD_NOUN],
            ),
        )
    }

    @Test
    fun `отказ пересказом не помечается — помечать нечего`() {
        val refused = com.point.core.model.ActionResult.Failure("нет текста", recoverable = true)

        assertSame(refused, retoldFromOffice(refused))
    }

    @Test
    fun `офисный файл обещает не «PDF», а то, что в нём будет`() {

        val office = com.point.core.model.ObjectState(ObjectKind.OFFICE)
        val pdf = PdfCapability()

        assertEquals("В PDF", pdf.label(office))
        assertEquals(
            "вернёт PDF с текстом документа · без оформления",
            yieldLabel(pdf.yields(office), pdf.intents(office).first()),
        )
    }

    @Test
    fun `разговор с объектом — единственная способность с неизвестным выходом`() {

        val unknown = inventory.filter { it.yields.contains(ActionYield.Unknown) }

        assertEquals(listOf("ai"), unknown.map { it.id.value })
    }

    private companion object {

        val pairedPc = object : PcLinks {
            override fun current() = LinkedPc("d-pc", "Домашний ПК", "ключ")
            override suspend fun save(pc: LinkedPc) = Unit
            override suspend fun clear() = Unit
        }

        val keysReady = SpeechReadiness { emptyList() }
    }
}
