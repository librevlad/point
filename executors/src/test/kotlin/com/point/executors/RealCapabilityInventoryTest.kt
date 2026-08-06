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

/**
 * Инвентаризация **настоящих** способностей (#491) — та самая таблица «кто что принимает и что
 * возвращает», собранная из деклараций, а не написанная руками.
 *
 * Владелец, дословно: «инвентаризовать кто что умеет принимать и возвращать и показывать это
 * пользователю». Написанный от руки список разошёлся бы с правдой на первой же новой
 * способности — тот же довод, по которому Flow Graph выводится, а не хранится.
 *
 * Почему тест живёт здесь, а не в `:core:flow`: только в `:executors` видны сами способности.
 * Соседний тест на подделках проверял бы правила на выдуманных декларациях — то есть свою же
 * выдумку. Тут же он **печатает** таблицу (`--info` или отчёт теста), и её можно читать глазами.
 */
class RealCapabilityInventoryTest {

    /** Всё, что реестр раздаёт через `@IntoSet` в `CapabilityModule`. Синтезированные пары
     *  (приложения устройства #66, действия ПК #80) сюда не входят — их набор зависит от того,
     *  что человек выбирал и к какому компьютеру подключался. */
    private val builtIn: List<Capability> = listOf(
        AiCapability(aiKeysReady), ArchiveCapability(), BlurBgCapability(),
        CallCapability(), CloudOcrCapability(), CopyCapability(), CopyCardCapability(),
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

    private val inventory = capabilityInventory(builtIn)

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

    /** Числа, ради которых инвентаризация и делалась. Печатаются вместе с таблицей. */
    private fun summary(): String = buildString {
        val byYield = inventory.groupingBy { kindOfYield(it) }.eachCount()
        appendLine("--- Итого ---")
        appendLine("способностей: ${inventory.size}")
        appendLine("возвращают новый объект: ${byYield["новый объект"] ?: 0}")
        appendLine("ничего не возвращают (отправят/откроют/покажут): ${byYield["ничего"] ?: 0}")
        appendLine("возвращают тот же объект понятым: ${byYield["тот же"] ?: 0}")
        appendLine("выход неизвестен заранее: ${byYield["неизвестно"] ?: 0}")
        appendLine("сказали о выходе сами (produces не хватило): ${inventory.count { it.declaredOnly }}")
        // Два очень разных случая под одной пометкой, и их стоит различать. Один — `produces`
        // слил разное в одно значение и потому врал по сути. Остальные — сказал правду, но
        // слишком общим словом, и способность уточнила его для человека.
        appendLine("  из них исправили суть (produces сливал разное): ${reshaped.size} ${reshaped.map { it.value }}")
        appendLine("  из них уточнили слово (вид слишком широк): ${inventory.count { it.declaredOnly } - reshaped.size}")
        appendLine("уходят в сеть по объявлению: ${inventory.count { it.network }}")
        appendLine("требуют ключа: ${inventory.count { it.auth }}")
        ObjectKind.entries.forEach { kind ->
            appendLine("принимают ${kind.name}: ${inventory.count { kind in it.accepts }}")
        }
    }

    /**
     * Способности, у которых `produces` врал не словом, а **сутью**: объявленная форма выхода
     * (ничего / тот же / новый / неизвестно) не та, что на самом деле.
     *
     * Это и есть честный ответ на «у скольких `produces` расходится с реальностью» — уточнение
     * существительного («документ» → «таблицу») сюда не считается, там декларация была верной.
     */
    private val reshaped: List<com.point.core.model.CapabilityId> = builtIn.filter { c ->
        inventoryProbes().filter(c::accepts).any { s -> shapeOf(c.yields(s)) != shapeOf(derivedYield(c, s)) }
    }.map { it.id }

    private fun shapeOf(y: ActionYield): String = when (y) {
        is ActionYield.New -> "new"
        // `Copied` — та же ФОРМА исхода, что `None`: нового объекта не будет. Отличается только
        // слово, которым это сказано человеку («ляжет в буфер обмена» вместо «отправит»), а здесь
        // считаются расхождения `produces` с реальностью — уточнение формулировки к ним не
        // относится, как и уточнение существительного у `New`.
        ActionYield.None, ActionYield.Copied -> "none"
        ActionYield.Same -> "same"
        ActionYield.Unknown -> "unknown"
    }

    @Test
    fun `produces разошёлся с реальностью ровно там, где сказано вслух`() {
        // Единственное место, где по одному `produces` человеку сказали бы неправду: «Понять»
        // отдаёт свой же объект (как «Поделиться»), а возвращает его с дописанными фактами.
        assertEquals(listOf("understand"), reshaped.map { it.value })
    }

    private fun kindOfYield(e: com.point.core.flow.CapabilityEntry): String = when {
        e.yields.any { it is ActionYield.New } -> "новый объект"
        e.yields.contains(ActionYield.Same) -> "тот же"
        e.yields.contains(ActionYield.Unknown) -> "неизвестно"
        else -> "ничего"
    }

    // --- Что инвентаризация обязана держать ---

    /**
     * Список выше набран руками — значит он обязан ловить собственное отставание.
     *
     * Иначе завтрашняя способность просто не попадёт в таблицу, и та станет ровно тем, от чего
     * весь срез уходит: написанным от руки списком, который тихо разошёлся с правдой. Считаем по
     * самому модулю: у `@Binds @IntoSet` объявлений тип возврата — `Capability`, и другого способа
     * попасть в реестр встроенной способности нет.
     */
    @Test
    fun `в таблице ровно столько способностей, сколько раздаёт реестр`() {
        // Способности приходят из двух мест, и сторож обязан знать оба, иначе переезд в общий
        // словарь выглядел бы как исчезновение способности. Первое — привязки телефона. Второе —
        // общий словарь (`:core:flow.capabilities`, контракт 06.08.2026 И1): он раздаётся одной
        // привязкой `Set<Capability>`, поэтому в подсчёт методов не попадает вовсе.
        val bound = com.point.executors.di.CapabilityModule::class.java.declaredMethods
            .count { it.returnType == Capability::class.java } +
            com.point.core.flow.capabilities.sharedCapabilities().size

        assertEquals("в CapabilityModule способностей больше, чем в таблице", bound, builtIn.size)
    }

    @Test
    fun `каждая способность кому-то предлагается`() {
        // Ноль принятых видов = способность, которую человек не увидит никогда. Такая либо
        // мертва, либо её гейт написан неверно; и то и другое стоит знать.
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
        // Раздел экрана и подпись строки не имеют права спорить: «вернёт текст» в разделе
        // «Отправить» — это два разных ответа на один вопрос, стоящие рядом.
        val liars = inventory.filter { e ->
            e.yields.any { it is ActionYield.New } && e.intents == setOf(Intent.SEND)
        }

        assertTrue("объявлены отправкой, а возвращают объект: ${liars.map { it.id.value }}", liars.isEmpty())
    }

    @Test
    fun `терминальное не встаёт под «Превратить»`() {
        // Обратная ошибка: «Превратить» обещает новый объект самим названием раздела.
        val liars = inventory.filter { e ->
            e.yields == listOf(ActionYield.None) && e.intents == setOf(Intent.PREPARE)
        }

        assertTrue("объявлены превращением, а не вернут ничего: ${liars.map { it.id.value }}", liars.isEmpty())
    }

    @Test
    fun `вид OFFICE никому не показывается общим словом`() {
        // `OFFICE` — это и документ Word, и таблица Excel. Оставить умолчание значит сказать
        // человеку «вернёт документ» там, где выйдет таблица.
        val vague = inventory.filter { e ->
            e.yields.any { it is ActionYield.New && it.kind == ObjectKind.OFFICE && it.noun == null }
        }

        assertTrue("вернут OFFICE, не сказав что именно: ${vague.map { it.id.value }}", vague.isEmpty())
    }

    @Test
    fun `сетевое объявлено сетевым, и это видно до тапа`() {
        // Тир пузырька (#114) выводится из `network`, поэтому объявление тут — не бумажка:
        // именно оно красит строку кольцом АКЦЕНТ2 «уходит с устройства».
        val cloudIds = setOf("ai", "understand", "excel", "word-plus", "translate", "transcribe", "ocr-cloud")

        cloudIds.forEach { id ->
            val e = inventory.single { it.id.value == id }
            assertTrue("«${e.label}» не объявлена сетевой", e.network)
        }
    }

    // --- Двое рядом не говорят одно и то же (#558) ---

    /** Подпись — ровно то, что человек читает строкой: имя действия и что оно вернёт. */
    private fun signature(c: Capability, state: com.point.core.model.ObjectState) =
        c.label(state) + " · " + yieldLabel(c.yields(state), c.intents(state).first())

    @Test
    fun `у двух действий, предлагаемых на одном объекте, не бывает одинаковой подписи`() {
        // Одинаковая подпись значит «выбирайте вслепую»: за одними и теми же словами прячется
        // разная цена («Скан» и «Скан с цветом», «В Word» и «В Word+» — #527) или разное существо
        // результата («Word в PDF» отдавал пересказ документа — #558). Проверка сильнее, чем
        // «разная цена → разная подпись», и потому её включает: одинаковых подписей нет вовсе.
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
        // Приёмка #558: обещание подписи и то, что реально вышло, — одно слово, а не два похожих.
        // Сверяются обе стороны сразу: измени слово в обещании или в пометке — тест упадёт.
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
        // Жалоба владельца дословно: «Word в PDF молча дал не то, что человек хотел». Пока
        // конвертация — пересказ (её чинит #403), подпись обязана это сказать до тапа.
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
        // Неизвестный выход законен ровно там, где ответ зависит от вопроса человека. Везде
        // ещё «не знаю» означало бы, что способность просто не удосужилась сказать.
        val unknown = inventory.filter { it.yields.contains(ActionYield.Unknown) }

        assertEquals(listOf("ai"), unknown.map { it.id.value })
    }

    private companion object {
        /** Компьютер связан — иначе «На компьютер» не предлагается вовсе и выпадет из таблицы. */
        val pairedPc = object : PcLinks {
            override fun current() = LinkedPc("d-pc", "Домашний ПК", "ключ")
            override suspend fun save(pc: LinkedPc) = Unit
            override suspend fun clear() = Unit
        }

        /** Ключи заданы — иначе имя действия договаривает «нужен ключ», и таблица показала бы
         *  не способность, а состояние настроек. */
        val keysReady = SpeechReadiness { emptyList() }
    }
}
