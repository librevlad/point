package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.PcPairing
import com.point.core.flow.PcPairings
import com.point.core.flow.SpeechReadiness
import com.point.core.flow.capabilityInventory
import com.point.core.flow.derivedYield
import com.point.core.flow.inventoryProbes
import com.point.core.flow.yieldLabel
import com.point.core.model.ActionYield
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import org.junit.Assert.assertEquals
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
        ActionYield.None -> "none"
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
        val bound = com.point.executors.di.CapabilityModule::class.java.declaredMethods
            .count { it.returnType == Capability::class.java }

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

    @Test
    fun `разговор с объектом — единственная способность с неизвестным выходом`() {
        // Неизвестный выход законен ровно там, где ответ зависит от вопроса человека. Везде
        // ещё «не знаю» означало бы, что способность просто не удосужилась сказать.
        val unknown = inventory.filter { it.yields.contains(ActionYield.Unknown) }

        assertEquals(listOf("ai"), unknown.map { it.id.value })
    }

    private companion object {
        /** Компьютер связан — иначе «На компьютер» не предлагается вовсе и выпадет из таблицы. */
        val pairedPc = object : PcPairings {
            override fun current() = PcPairing(host = "192.168.0.2", port = 8765, token = "t")
            override suspend fun save(pairing: PcPairing) = Unit
            override suspend fun clear() = Unit
        }

        /** Ключи заданы — иначе имя действия договаривает «нужен ключ», и таблица показала бы
         *  не способность, а состояние настроек. */
        val keysReady = SpeechReadiness { emptyList() }
    }
}
