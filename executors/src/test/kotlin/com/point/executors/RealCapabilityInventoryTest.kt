package com.point.executors

import com.point.core.flow.capabilities.PdfCapability
import com.point.core.flow.Capability
import com.point.core.flow.LinkedPc
import com.point.core.flow.OfficeAlwaysHere
import com.point.core.flow.PcLinks
import com.point.core.flow.SpeechReadiness
import com.point.core.flow.capabilityInventory
import com.point.core.flow.derivedYield
import com.point.core.flow.inventoryProbes
import com.point.core.flow.yieldLabel
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.data.DocumentTypeInvestigation
import com.point.data.EntityInvestigation
import com.point.data.ExifInvestigation
import com.point.data.GraphRolesInvestigation
import com.point.data.IdentifierInvestigation
import com.point.data.MetadataEntityInvestigation
import com.point.data.OcrInvestigation
import com.point.data.PdfImageInvestigation
import com.point.data.PeriodInvestigation
import com.point.data.QrInvestigation
import com.point.data.TextUrlInvestigation
import com.point.data.VCardInvestigation
import com.point.data.ZipImagesInvestigation
import com.point.executors.di.CapabilityModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RealCapabilityInventoryTest {

    // Общий словарь — ровно тот, что телефон раздаёт через CapabilityModule (#1021): слово о
    // дороге чтения снимка в нём телефонное. Перечисленный руками словарь расходился с боевым
    // набором — держал голое чтение без обещания.
    //
    // Спрашивается он один раз: два вызова подряд расходятся молча, стоит подписи измениться.
    private val shared: Set<Capability> = CapabilityModule.sharedCaps(OfficeAlwaysHere, AccountForTests())

    private val builtIn: List<Capability> = shared.toList() + listOf(
        AiCapability(aiKeysReady), BlurBgCapability(),
        CallCapability(), CloudOcrCapability(), CopyCapability(), CopyCardCapability(),
        CorrectValueCapability(),
        FixErrorsCapability(aiKeysReady), FixErrorsStrongerCapability(aiKeysReady),
        CutoutCapability(), EmailCapability(), EventCapability(),
        ExcelCapability(aiKeysReady), ExtractAllCapability(), FindCapability(),
        JobReplyCapability(aiKeysReady), MapCapability(), MergePdfCapability(),
        OpenCapability(), OpenInCapability(), OpenUrlCapability(),
        PagesCapability(), PcCapability(pairedPc), PhoneAppsCapability(),
        ReadDocumentCapability(),
        ReadQrCapability(), RenewPeriodCapability(), ReplaceBgCapability(),
        SaveAllCapability(), SaveCapability(), SaveContactCapability(), ScanCapability(),
        SlidesCapability(), SpeakCapability(),
        TakeFragmentCapability(), HideAreaCapability(),
        ScanPdfCapability(), ScanPlusCapability(), ShareAllCapability(), ShareCapability(),
        ShootMoreCapability(),
        ShoppingListCapability(aiKeysReady), SmsCapability(), TranscribeCapability(keysReady),
        TranslateCapability(aiKeysReady), UnderstandCapability(aiKeysReady), VCardCapability(), WordCapability(),
        WordPlusCapability(aiKeysReady), CleanMetadataCapability(),
    )

    private val sharedNames: Set<String> = shared.map { it.javaClass.simpleName }.toSet()

    // Исследования продукта — пары «класс и его id». Сверки ниже держат этот список и
    // `DataModule` вместе поимённо (#1256).
    private val investigations: List<Pair<Class<*>, CapabilityId>> = listOf(
        DocumentTypeInvestigation::class.java to DocumentTypeInvestigation.ID,
        EntityInvestigation::class.java to EntityInvestigation.ID,
        ExifInvestigation::class.java to ExifInvestigation.ID,
        GraphRolesInvestigation::class.java to GraphRolesInvestigation.ID,
        IdentifierInvestigation::class.java to IdentifierInvestigation.ID,
        MetadataEntityInvestigation::class.java to MetadataEntityInvestigation.ID,
        OcrInvestigation::class.java to OcrInvestigation.ID,
        PdfImageInvestigation::class.java to PdfImageInvestigation.ID,
        PeriodInvestigation::class.java to PeriodInvestigation.ID,
        QrInvestigation::class.java to QrInvestigation.ID,
        TextUrlInvestigation::class.java to TextUrlInvestigation.ID,
        VCardInvestigation::class.java to VCardInvestigation.ID,
        ZipImagesInvestigation::class.java to ZipImagesInvestigation.ID,
    )

    // Пробы шире базовых видов: кадры извлечённых значений — полноправные объекты,
    // и способность, живущая только на них, обязана быть видна инвентарю.
    private val inventory = capabilityInventory(
        builtIn,
        com.point.core.flow.inventoryProbes(
            com.point.core.model.ObjectKind.entries + com.point.core.flow.EXTRACTED_KINDS,
        ),
    )

    /**
     * Живой дефект 2026-08-08: действие «Распознать текст» и OCR-исследование делили id
     * `ocr`, резолвер группирует реализаторы по id и подсовывал циклу знания реализатор
     * действия — на экране вырастало «исследование вернуло объект вместо знания».
     * Разные вопросы — разные id: пространство исследований не пересекается с действиями.
     */
    @Test
    fun `id исследований не пересекаются с id действий`() {
        val actions = builtIn.map { it.id }.toSet()

        val clashes = investigations
            .filter { (_, id) -> id in actions }
            .map { (cls, id) -> "${cls.simpleName} — «${id.value}»" }

        assertEquals("исследование и действие не смеют делить id", emptyList<String>(), clashes)
    }

    /**
     * Тот же дефект внутри самого пространства знания: два исследования с одним id резолвер
     * сложит в одну кучу, и цикл знания получит чужого реализатора.
     *
     * Заодно это то, что держит пары «класс и его id» честными: списанный копипастой чужой id
     * всплывает здесь двойником, а не молча уводит проверку выше мимо новичка.
     */
    @Test
    fun `два исследования не делят один id`() {
        val twins = investigations.groupBy { (_, id) -> id }
            .filterValues { it.size > 1 }
            .map { (id, pairs) -> "«${id.value}» — ${pairs.map { (cls, _) -> cls.simpleName }}" }

        assertEquals("исследования не смеют делить id", emptyList<String>(), twins)
    }

    /**
     * Исследования сверяются с реестром поимённо — как и способности ниже (#1256). Счётная
     * сверка держалась на совпадении: `exif` из списка выпал, а число всё равно сходилось, и
     * добавить одно исследование, убрав другое, можно было, не тронув счёт, — список молчал бы,
     * а проверки выше шли бы мимо новичка. Имя — не число: разойдётся, и тест назовёт, кого
     * именно не хватает.
     */
    @Test
    fun `исследования взяты те же, что связывает DataModule`() {
        assertEquals(
            boundInvestigations().map { it.simpleName }.sorted(),
            investigations.map { (cls, _) -> cls.simpleName }.sorted(),
        )
    }

    /** Что раздаёт `DataModule`: одно исследование — одна раздача. */
    private fun boundInvestigations(): List<Class<*>> =
        singleCapabilitiesOf(com.point.data.di.DataModule::class.java, com.point.data.di.DataModule.Companion)

    @Test
    fun `таблица — что каждая способность принимает и что возвращает`() {
        val lines = inventory.map { e ->
            val takes = e.accepts.joinToString("·") { it.name }.ifEmpty { "—" }
            val gives = e.yields.joinToString(" / ") { yieldLabel(it).orEmpty() }
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

    private val reshaped: List<CapabilityId> = builtIn.filter { c ->
        inventoryProbes().filter(c::accepts).any { s -> shapeOf(c.yields(s)) != shapeOf(derivedYield(c, s)) }
    }.map { it.id }

    private fun shapeOf(y: ActionYield): String = when (y) {
        is ActionYield.New -> "new"

        ActionYield.None, ActionYield.Copied -> "none"
        is ActionYield.Same -> "same"
        ActionYield.Unknown -> "unknown"
    }

    @Test
    fun `produces разошёлся с реальностью ровно там, где сказано вслух`() {

        // Знание о том же объекте: produces возвращает тот же state, из чего механически
        // следует «ничего не вернёт», — а человеку возвращается понятое. Эти способности
        // говорят о своём выходе сами, и список тут именно для того, чтобы их было видно.
        assertEquals(
            listOf(
                "drop-link", "fix-errors", "fix-errors-stronger", "ocr-cloud", "office", "pdf",
                "read-document", "transcribe", "understand",
            ),
            reshaped.map { it.value }.sorted(),
        )
    }

    private fun kindOfYield(e: com.point.core.flow.CapabilityEntry): String = when {
        e.yields.any { it is ActionYield.New } -> "новый объект"
        e.yields.any { it is ActionYield.Same } -> "тот же"
        e.yields.contains(ActionYield.Unknown) -> "неизвестно"
        else -> "ничего"
    }

    /**
     * Инвентарь сверяется с DI поимённо, а не по счёту (#1256). Счёт сходился по совпадению:
     * `@Provides` из companion-объекта в `declaredMethods` абстрактного класса не попадал, и
     * пропуск в одном месте гасился пропуском в другом — «Очистить метаданные» так и не была
     * проверена ни одним инвариантом этого файла. Списку способностей нельзя разойтись с тем,
     * что раздаёт реестр, молча: расходится — тест называет, кого именно не хватает.
     */
    @Test
    fun `таблица собрана из тех же способностей, что раздаёт DI`() {
        val listed = (builtIn.map { it.javaClass.simpleName }.toSet() - sharedNames).sorted()

        assertEquals(boundSingleCapabilities().map { it.simpleName }.sorted(), listed)
    }

    private fun boundSingleCapabilities(): List<Class<*>> =
        singleCapabilitiesOf(CapabilityModule::class.java, CapabilityModule.Companion)

    /**
     * Что модуль DI раздаёт по одной способности: `@Binds` самого модуля и `@Provides` из его
     * companion-объекта (#1256).
     *
     * Половины модуля мало. Companion — отдельный класс, и в `declaredMethods` модуля его
     * методы не попадают, а уезжает туда как раз то, что `@Binds` завести не может: класс с
     * нативной библиотекой роняет разрешение типов всему модулю KSP. Сверка, читающая только
     * `@Binds`, о такой способности молчит — а пропущенное ею не проходит ни одной проверки
     * этого файла: ни на общий id с действием, ни на двойника внутри своего пространства.
     *
     * Способность из companion со своими зависимостями завести без Hilt нечем — и это не
     * повод пропустить её молча: тест обязан назвать её вслух.
     */
    private fun singleCapabilitiesOf(module: Class<*>, companion: Any): List<Class<*>> {
        val binds = module.declaredMethods
            .filter { it.returnType == Capability::class.java }
            .map { it.parameterTypes.singleOrNull() ?: error("@Binds берёт не одну реализацию: ${it.name}") }

        val provides = companion.javaClass.declaredMethods
            .filter { it.returnType == Capability::class.java }
            .map { method ->
                assertEquals(
                    "способность из companion @Provides со своими зависимостями — сверить её нечем: ${method.name}",
                    0, method.parameterCount,
                )
                method.invoke(companion).javaClass
            }

        return binds + provides
    }

    /**
     * Сверка читает модуль целиком (#1256). Исследования сверялись только по `declaredMethods`
     * самого `DataModule`, то есть по `@Binds`, — ровно та слепота, которую этот срез закрыл
     * для действий: исследование, отданное из companion, в сверку не попадало, и список рядом
     * мог о нём молчать. Молчащий список уводит мимо новичка обе проверки id — ту самую пару,
     * что закрыла живой дефект с общим `ocr`.
     */
    @Test
    fun `сверка видит и привязку модуля, и способность из его companion`() {

        // Ровно то, чем слепота и держалась: в самом модуле метода companion нет.
        assertTrue(
            "пример перестал показывать слепоту — companion-метод виден и в самом модуле",
            HalfSeenModule::class.java.declaredMethods.none { it.name == "saveCap" },
        )

        assertEquals(
            listOf("CopyCapability", "SaveCapability"),
            singleCapabilitiesOf(HalfSeenModule::class.java, HalfSeenModule.Companion)
                .map { it.simpleName }
                .sorted(),
        )
    }

    /** Модуль формы Hilt: одна способность привязана, вторая отдана из companion. */
    private abstract class HalfSeenModule {

        abstract fun copyCap(c: CopyCapability): Capability

        companion object {

            fun saveCap(): Capability = SaveCapability()
        }
    }

    @Test
    fun `каждая способность кому-то предлагается`() {

        val dead = inventory.filter { it.accepts.isEmpty() }

        assertTrue("никому не предлагаются: ${dead.map { it.id.value }}", dead.isEmpty())
    }

    @Test
    fun `каждая способность объявляет свой выход, а подпись — только когда ей есть что сказать`() {
        inventory.forEach { e ->
            assertTrue("${e.id.value} не сказала о выходе ничего", e.yields.isNotEmpty())
            e.yields.forEach { y ->

                // Подписи может не быть вовсе (#629, #582): имя действия уже сказало всё, а
                // выведенная из типа строка только повторяла его. Пустая строка — не подпись,
                // а дырка на экране.
                val said = yieldLabel(y)
                assertTrue("${e.id.value} оставила пустую подпись вместо её отсутствия", said == null || said.isNotBlank())
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
        listOfNotNull(c.label(state), yieldLabel(c.yields(state))).joinToString(" · ")

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

    /**
     * Пересказа офисного файла в PDF больше нет (#403): телефон не выдаёт текстовую выжимку
     * за превращение документа. Обещание способности — обычный PDF, потому что делает его
     * настоящий конвертер на компьютере.
     */
    @Test
    fun `офис в PDF обещает документ, а не выжимку из него`() {

        val office = com.point.core.model.ObjectState(ObjectKind.OFFICE)
        val pdf = PdfCapability()

        assertEquals("В PDF", pdf.label(office))
        assertNull(
            "обещание и вышедшее разошлись",
            com.point.core.flow.yieldSurprise(pdf.yields(office), ObjectKind.PDF, null),
        )
        assertTrue(
            "обещание всё ещё говорит про выжимку: " + yieldLabel(pdf.yields(office)),
            "текстом" !in yieldLabel(pdf.yields(office)).orEmpty(),
        )
    }

    /**
     * Действие, которое отдаёт объект другому устройству, тому устройству не рекламируется
     * (#920). Владелец увидел на экране компьютера: «На компьютер · телефон пока не выполняет
     * просьбы с компьютера · на телефоне». Объект уже на компьютере — предлагать отправить
     * его туда бессмысленно.
     *
     * Сторожил это чтение исходника `PcAction.kt` на подстроку `localOnly = true` (#1248):
     * утверждение о написании файла, а не о поведении. Оно гасло от перестановки строк и не
     * видело телефонный путь, каким идёт продукт — `advertisedActions(registry.all())` над
     * боевым набором способностей. Здесь проверяется тот самый путь.
     */
    @Test
    fun `телефон не объявляет компьютеру «На компьютер», а обычные действия объявляет`() {

        val advertised = com.point.core.flow.advertisedActions(builtIn).map { it.id }

        assertTrue("«На компьютер» снова уедет на компьютер", PcCapability.ID.value !in advertised)
        assertTrue("обычные действия перестали доезжать до компьютера: $advertised", "call" in advertised)
        assertTrue("обычные действия перестали доезжать до компьютера: $advertised", "event" in advertised)
    }

    /**
     * Второе дело того же признака: он гасит человеку вопрос про облако (#1088).
     *
     * «На компьютер» объявлено сетевым честно — до второй машины оно дотягивается через
     * сервер (#569). Сетевая работа обычно поднимает «Отправить в облако?»: это запасной
     * ответ для способностей, чей единственный исполнитель зовёт модель, назвавшись здешним.
     * Для действия своего круга запасной ответ погашен — согласия спрашивать не о чем, объект
     * от человека не уходит.
     *
     * Значит, правду про уход наружу сказать больше некому, кроме исполнителя. Пометь завтра
     * облачную работу этим признаком ради одной лишь нерекламы компьютеру — и вопрос человеку
     * пропадёт молча, а объект уедет к чужим. Здесь проверено обратное: у каждой такой
     * способности исполнитель назвал себя сам — либо вторым устройством человека, либо тем,
     * кто увезёт объект наружу и спросит согласие.
     */
    @Test
    fun `у сетевой способности своего круга уход наружу объявляет исполнитель`() {

        // Не список разрешений, а требование доказательства: способность, попавшая в набор
        // ниже без исполнителя здесь, роняет проверку — назови его и посмотри, что он
        // говорит про уход наружу.
        val performers: Map<CapabilityId, com.point.core.flow.Realizer> = mapOf(
            PcCapability.ID to PcRealizer(pairedPc, silentPc, bytesNotRead),
        )

        val quiet = builtIn.filter { it.meta.localOnly && it.meta.network }
        assertTrue("сверять нечего — таких способностей не осталось вовсе", quiet.isNotEmpty())

        val unnamed = quiet.mapNotNull { cap ->
            val meta = performers[cap.id]?.meta
            when {
                meta == null -> "${cap.id.value} — исполнитель не назван"
                meta.kind == com.point.core.flow.RealizerKind.REMOTE || meta.leavesCircle -> null
                else -> "${cap.id.value} — исполнитель зовёт себя ${meta.kind}"
            }
        }

        assertEquals(
            "вопрос про облако у этой способности погашен, а про уход наружу не сказал никто",
            emptyList<String>(),
            unnamed,
        )
    }

    @Test
    fun `ни одно объявленное телефоном имя не называет чужое устройство`() {

        val advertised = com.point.core.flow.advertisedActions(builtIn)

        // На пустом объявлении «ни одно имя не называет чужое устройство» истинно по
        // определению (#1248): сначала спрашивается, что телефону вообще есть что объявлять —
        // тем же правилом, каким живут запасное объявление и сторожа ниже.
        assertTrue("объявления телефона нет вовсе — сверять нечего", advertised.isNotEmpty())

        val named = advertised.map { it.label }.filter(::namesOtherDevice)

        assertTrue("компьютер увидит в списке действие про чужое устройство: $named", named.isEmpty())
    }

    /**
     * Запасное объявление — то, что телефон говорит о себе, когда собрать список из реестра не
     * вышло. Оно не фильтруется через `advertisedActions` вовсе, поэтому правило проверяется
     * прямо на нём, а каждая строка сверяется с настоящим объявлением целиком — вместе с видом
     * и признаками.
     *
     * Сверка одних имён этого не ловила (#1248): запасное «Позвонить» стояло на любом тексте,
     * а настоящее — на признаке номера. Компьютер по такому списку предлагал позвонить тексту
     * без номера: обещал то, чего телефон не сделает. Годность объекту считает `PcActionFit` по
     * виду и признакам — значит и сверять надо их, а не только слово на кнопке.
     */
    @Test
    fun `запасное объявление живёт по тем же правилам, что и настоящее`() {

        val real = com.point.core.flow.advertisedActions(builtIn)
        val fallback = com.point.core.flow.PHONE_ADVERTISED_FALLBACK

        // Пустой список прошёл бы обе проверки ниже молча: сначала спрашивается, что
        // объявлять телефону вообще есть чем.
        assertTrue("запасного объявления нет вовсе — сверять нечего", fallback.isNotEmpty())

        val named = fallback.map { it.label }.filter(::namesOtherDevice)
        assertTrue("запасное объявление называет чужое устройство: $named", named.isEmpty())

        val stale = fallback.filterNot { it in real }.map { spare ->
            val theirs = real.filter { it.id == spare.id }.map(::said)
            "${spare.id} · запасное ${said(spare)} — у телефона " +
                (theirs.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "нет такого действия")
        }
        assertTrue("запасное объявление отстало от того, что телефон умеет: $stale", stale.isEmpty())
    }

    /** Строка объявления словами: имя, вид, признаки и место — всё, чем живёт чужое действие. */
    private fun said(action: com.point.core.flow.PcRemoteAction): String {
        val kinds = action.kinds.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "любой"
        val features = action.features.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "любые"
        return "«${action.label}» вид $kinds признаки $features место ${action.priority}"
    }

    /** Имя действия называет устройство, которое человеку в этом списке не своё. */
    private fun namesOtherDevice(label: String): Boolean =
        OTHER_DEVICE_WORDS.any { it in label.lowercase() }

    @Test
    fun `разговор с объектом — единственная способность с неизвестным выходом`() {

        val unknown = inventory.filter { it.yields.contains(ActionYield.Unknown) }

        assertEquals(listOf("ai"), unknown.map { it.id.value })
    }

    private companion object {

        /**
         * Слова, называющие устройство. В списке, который телефон шлёт компьютеру, любое из
         * них — про чужое место: «компьютер» человек и так видит перед собой, а «телефон» —
         * та сторона, что этот список прислала.
         *
         * Единственный дом этого списка (#1248): второй такой же стоял в `:core:flow`, на
         * фикстуре с придуманным именем, и от продукта не зависел вовсе.
         */
        val OTHER_DEVICE_WORDS = listOf("компьютер", "телефон", " пк")

        val pairedPc = object : PcLinks {
            override fun current() = LinkedPc("d-pc", "Домашний ПК", "ключ")
            override suspend fun save(pc: LinkedPc) = Unit
            override suspend fun clear() = Unit
        }

        /** Дорога до компьютера: здесь спрашивают только объявленное, отправлять нечего. */
        val silentPc = object : com.point.core.flow.PcTransport {
            override suspend fun send(
                pc: LinkedPc,
                obj: com.point.core.model.PointObject,
                fileName: String,
                meta: Map<String, String>,
                action: String?,
            ) = error("не зовут")

            override suspend fun fetchCaps(pc: LinkedPc) = error("не зовут")
            override suspend fun fetchOutbox(pc: LinkedPc) = error("не зовут")
            override suspend fun downloadOutboxFile(pc: LinkedPc, id: Int, targetPath: String) = error("не зовут")
            override suspend fun ackOutbox(pc: LinkedPc, id: Int) = error("не зовут")
            override suspend fun pushPhoneCaps(pc: LinkedPc, caps: List<com.point.core.flow.PcRemoteAction>) =
                error("не зовут")

            override suspend fun exchangeSecrets(pc: LinkedPc, mine: com.point.core.flow.SharedSecrets) =
                error("не зовут")
        }

        /** Байты объекта здесь не читают: сверяется объявленное, а не работа. */
        val bytesNotRead = object : com.point.core.flow.ObjectStore {
            override suspend fun ingest(sourceUri: String, mime: String) = error("не зовут")
            override suspend fun ingestMultiple(sources: List<String>) = error("не зовут")
            override suspend fun put(
                result: com.point.core.model.ResultObject,
                from: com.point.core.model.PointObject?,
                by: CapabilityId?,
            ) = error("не зовут")

            override suspend fun children(collection: com.point.core.model.PointObject, limit: Int) =
                error("не зовут")

            override suspend fun readText(obj: com.point.core.model.PointObject, limit: Int) = error("не зовут")
            override suspend fun newScratchFile(extension: String) = error("не зовут")
            override suspend fun clear() = Unit
        }

        val keysReady = SpeechReadiness { emptyList() }
    }
}
