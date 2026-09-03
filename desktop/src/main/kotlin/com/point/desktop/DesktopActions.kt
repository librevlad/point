package com.point.desktop

import com.point.core.flow.TEXT_NOT_KEPT
import com.point.core.flow.BrowserOpener
import com.point.core.flow.Capability
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.META_ENTITY_URL
import com.point.core.flow.Realizer
import com.point.core.flow.fileHead
import com.point.core.flow.uriListAddress
import com.point.core.flow.uriListAddressOf
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.isFileBacked
import java.io.File

fun interface SystemOpener { fun open(file: File) }
fun interface FileRevealer { fun reveal(file: File) }
fun interface TextClipboard { fun copy(text: String) }
fun interface SaveTarget { fun pickAndSave(file: File): String? }

interface Printer {

    fun name(): String?

    fun print(file: File)

    fun printAsking(file: File): Boolean = run { print(file); true }
}

class PcOpenCapability : Capability {
    override val id = CapabilityId("pc-open")
    override val icon = "open"
    override val meta = CapabilityMeta(priority = 10)
    override fun label(state: ObjectState) = "Открыть"
    override fun accepts(state: ObjectState) = true
    override fun produces(state: ObjectState) = state
}

/** Сколько содержимого спрашивать, проверяя «это и есть адрес»: любому адресу хватает. */
private const val ADDRESS_CHARS = 8 * 1024

/**
 * Адрес, которым объект является сам (#1087).
 *
 * Правило узкое: ссылка — это объект вида URL либо объект, чьё содержимое целиком и есть
 * адрес (строка-ссылка с телефона приезжает файлом `.txt` и видом «Текст»). Одного ключа
 * `entity.url` мало: тот же ключ извлечение сущностей ставит любому объекту, внутри которого
 * адрес просто встретился, — снимку с QR, документу с адресом в тексте. Найденный внутри
 * адрес открывается своим действием у своего узла, а снимок остаётся снимком.
 *
 * Разбор здесь свой не заводится: адрес из содержимого читает то же единственное правило
 * `text/uri-list`, что и при рождении объекта (#999) — два правила разъезжаются молча.
 */
internal fun knownLink(input: PointObject): String? = when (input.state.kind) {

    // Объект-ссылка: адрес он знает знанием графа — у узла от QR файла может не быть вовсе, —
    // либо называет его собственным содержимым.
    ObjectKind.URL -> input.metadata[META_ENTITY_URL]?.takeIf(String::isNotBlank)
        ?: uriListAddressOf(input.uri.value)

    // Не ссылка по виду — ссылкой считается, только когда в содержимом нет ничего, кроме адреса.
    ObjectKind.TEXT -> fileHead(input.uri.value, ADDRESS_CHARS).trim()
        .let { text -> uriListAddress(text)?.takeIf { it == text } }

    else -> null
}

/**
 * Одна дверь в браузер компьютера (#1087): «Открыть» со ссылкой, «Открыть в браузере» и вход
 * в аккаунт зовут её же.
 *
 * Отказ дверь не глотает: раньше она гасила исключение у себя, и человеку писалось «Открыто
 * в браузере» над не открывшимся браузером.
 */
class SystemBrowser(
    private val browse: (java.net.URI) -> Unit = { java.awt.Desktop.getDesktop().browse(it) },
) : BrowserOpener {
    override fun open(url: String) = browse(java.net.URI(url))
}

/** Одна дорога в браузер компьютера: и у «Открыть», и у «Открыть в браузере» исход один (#1087). */
internal fun openInBrowser(browser: BrowserOpener, url: String): ActionResult = runCatching {
    browser.open(url)
    ActionResult.Done("Открыто в браузере")
}.getOrElse { ActionResult.Failure("Браузер не открылся — откройте ссылку вручную из буфера", recoverable = true) }

class PcOpenRealizer(
    private val opener: SystemOpener,
    private val browser: BrowserOpener,
) : Realizer {
    override val capabilityId = CapabilityId("pc-open")

    // #1087: открывается смысл, а не файл. Объект, который сам и есть ссылка, уходит в
    // браузер компьютера; системному обработчику по расширению файл достаётся во всех
    // остальных случаях — в том числе когда адрес внутри объекта просто найден. Действие
    // следует из знания графа: строка-ссылка с телефона открывалась на ПК текстовым
    // редактором.
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        knownLink(input)?.let { url -> return openInBrowser(browser, url) }
        return runCatching {
            opener.open(File(input.uri.value))
            ActionResult.Done("Открыто")
        }.getOrElse { ActionResult.Failure("Открыть нечем: у этого файла нет программы по умолчанию", recoverable = true) }
    }
}

class PcRevealCapability : Capability {
    override val id = CapabilityId("pc-reveal")
    override val icon = "folder"
    override val meta = CapabilityMeta(priority = 20)
    override fun label(state: ObjectState) = "Показать в папке"

    // Файловое действие — только файловому виду (#655, слова владельца:
    // «открыть ссылку в папке тоже не очень логично»).
    override fun accepts(state: ObjectState) = state.kind.isFileBacked
    override fun produces(state: ObjectState) = state
}

class PcRevealRealizer(private val revealer: FileRevealer) : Realizer {
    override val capabilityId = CapabilityId("pc-reveal")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            revealer.reveal(File(input.uri.value))
            ActionResult.Done("Папка открыта")
        }.getOrElse { ActionResult.Failure("Проводник не открылся — файл лежит в папке Point", recoverable = true) }
}

class PcCopyCapability : Capability {
    override val id = CapabilityId("pc-copy")
    override val icon = "copy"
    override val meta = CapabilityMeta(priority = 15)
    override fun label(state: ObjectState) =
        if (state.kind == ObjectKind.IMAGE) "Копировать картинку" else "Копировать"

    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.TEXT || state.kind == ObjectKind.IMAGE

    override fun produces(state: ObjectState) = state
}

class PcCopyRealizer(
    private val clipboard: TextClipboard,

    private val imageClipboard: ((ClipboardPayload) -> Unit)? = null,
) : Realizer {
    override val capabilityId = CapabilityId("pc-copy")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            val file = File(input.uri.value).takeIf(File::isFile)
                ?: return ActionResult.Failure("Файла объекта нет на диске", recoverable = false)
            if (input.state.kind == ObjectKind.IMAGE) {
                val put = imageClipboard
                    ?: return ActionResult.Failure("Этот компьютер не умеет класть картинку в буфер", recoverable = false)
                put(ClipboardPayload(input.mime, file.name, file.readBytes()))
                ActionResult.Done("Картинка в буфере — вставьте куда нужно")
            } else {
                clipboard.copy(file.readText())
                ActionResult.Done("Скопировано в буфер")
            }
        }.getOrElse { ActionResult.Failure("Скопировать не вышло — попробуйте ещё раз", recoverable = true) }
}

class PcSaveAsCapability : Capability {
    override val id = CapabilityId("pc-save-as")
    override val icon = "save"
    override val meta = CapabilityMeta(priority = 30)
    override fun label(state: ObjectState) = "Сохранить в…"

    // Файловое действие — только файловому виду (#655).
    override fun accepts(state: ObjectState) = state.kind.isFileBacked
    override fun produces(state: ObjectState) = state
}

class PcSaveAsRealizer(private val target: SaveTarget) : Realizer {
    override val capabilityId = CapabilityId("pc-save-as")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            // Человек закрыл системное окно: слово отмены — объявленное, одно на оба
            // устройства (#1073). По нему телефон узнаёт, что говорить не о чем, и гасит
            // обещание тихо; настоящее «Сохранено: <путь>» он человеку повторяет.
            val saved = target.pickAndSave(File(input.uri.value))
                ?: return ActionResult.Done(com.point.core.flow.PC_CANCELLED)
            ActionResult.Done("Сохранено: $saved")
        }.getOrElse { ActionResult.Failure("Сохранить не вышло — выберите другую папку", recoverable = true) }
}

interface VideoDownloader {
    fun available(): Boolean

    fun start(url: String): Boolean
}

class YtDlpDownloader(
    private val downloadsDir: File,

    /** Скачанное — объект, а не файл «где-то» (PC3): по завершении оно приходит в ленту. */
    private val onDownloaded: (List<File>) -> Unit = {},
) : VideoDownloader {
    override fun available(): Boolean = runCatching {
        ProcessBuilder("yt-dlp", "--version").start().waitFor() == 0
    }.getOrDefault(false)

    override fun start(url: String): Boolean = runCatching {
        downloadsDir.mkdirs()
        val before = downloadsDir.list()?.toSet() ?: emptySet()
        val process = ProcessBuilder("yt-dlp", "-P", downloadsDir.absolutePath, url)
            .redirectErrorStream(true)
            .redirectOutput(File(downloadsDir, "yt-dlp.log"))
            .start()
        Thread({
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            val fresh = downloadsDir.listFiles().orEmpty()
                .filter { it.isFile && it.name !in before }
                .filterNot { it.name == "yt-dlp.log" || it.name.endsWith(".part") }
            runCatching { onDownloaded(if (code == 0) fresh else emptyList()) }
        }, "yt-dlp-watch").apply { isDaemon = true }.start()
        true
    }.getOrDefault(false)
}

class PcDownloadCapability : Capability {
    override val id = CapabilityId("pc-download")
    override val icon = "open"
    override val meta = CapabilityMeta(priority = 40)
    override fun label(state: ObjectState) = "Скачать видео"
    override fun accepts(state: ObjectState) = state.kind == com.point.core.model.ObjectKind.URL
    override fun produces(state: ObjectState) = state
}

class PcDownloadRealizer(private val downloader: VideoDownloader) : Realizer {
    override val capabilityId = CapabilityId("pc-download")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {

        // Адрес спрашивается у знания объекта, а не у байтов заново (#999): ссылка приходит
        // сюда со знанием `entity.url`, а если его нет — читается тем же единственным
        // правилом разбора `text/uri-list`, что и при рождении объекта. Своего разбора здесь
        // больше нет: два правила разъезжаются молча.
        val url = input.metadata[META_ENTITY_URL]?.takeIf(String::isNotBlank)
            ?: uriListAddressOf(input.uri.value)
            ?: return ActionResult.Failure("Здесь нет ссылки — скачивать нечего", recoverable = true)
        return if (downloader.start(url)) {
            ActionResult.Done("Скачиваю: $url")
        } else {
            ActionResult.Failure("Не удалось запустить yt-dlp", recoverable = true)
        }
    }
}

/**
 * Имя способности «На телефон» — одно на дверь, исполнителя и журнал (#1108).
 *
 * Компьютер дописывает в «ПУТЬ» правду про непроснувшийся телефон уже после того, как шаг
 * записан, и находит шаг по этому имени. Набранное третьей строкой, оно разъехалось бы молча.
 */
const val PC_TO_PHONE = "pc-to-phone"

class PcToPhoneCapability : Capability {
    override val id = CapabilityId(PC_TO_PHONE)

    // Знак показывает, куда уйдёт объект, а не где нажали (#1094): телефон, не компьютер.
    override val icon = "phone"

    // Локальное действие компьютера: телефону его не рекламируем — там оно
    // звучало «На телефон на ПК» и не значило ничего (живой прогон 2026-08-09).
    override val meta = CapabilityMeta(priority = 15, localOnly = true)
    override fun label(state: ObjectState) = "На телефон"
    override fun accepts(state: ObjectState) = true
    override fun produces(state: ObjectState) = state
}

class PcToPhoneRealizer(
    private val outbox: Outbox,

    /**
     * Стук телефону: пусть узнает о ждущем сейчас, а не при следующем открытии (#1079).
     *
     * Стук зовёт за конкретным объектом (#1108): не пришёл телефон — правда о нём ложится
     * в «ПУТЬ» этого объекта, а не гаснет вместе с плашкой на экране.
     */
    private val knockPhone: suspend (PointObject) -> Unit = {},
) : Realizer {
    override val capabilityId = CapabilityId(PC_TO_PHONE)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            outbox.add(input)
            runCatching { knockPhone(input) }

            // Слова не опережают сделанное (#1079): письмо легло в очередь, плашка на
            // телефоне появится, когда он за ним придёт, — а не в момент этого тапа.
            ActionResult.Done("Ждёт телефона: откройте на телефоне главный экран Point и заберите объект")
        }.getOrElse { ActionResult.Failure("Не удалось отправить — проверьте, что на диске есть место", recoverable = true) }
}

/**
 * Текст из PDF на компьютере (#631): та же способность и те же слова, что на телефоне.
 * Скан сюда не доходит — его дверь не рисуется вовсе (`IS_IMAGE_PDF` ставится при приёме),
 * и «Извлечь текст» больше не заканчивается пустотой.
 *
 * Текст ложится знанием на сам документ (#995): второго объекта рядом не появляется, и
 * обещание способности («текст документа») на компьютере значит ровно то же, что на телефоне.
 * Документ с подменённой раскладкой шрифта (#933) сюда тоже не доходит: слой, который нельзя
 * прочитать, текстовым слоем не является, и такой PDF метится сканом ещё при приёме.
 */
class PcPdfTextRealizer(
    private val pdf: PdfText,
) : Realizer {
    override val capabilityId = com.point.core.flow.capabilities.PdfCapability.ID

    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.PDF && !state.has(com.point.core.model.Feature.IS_IMAGE_PDF)

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        val source = File(input.uri.value)
        val text = pdf.of(source)
            ?: return ActionResult.Failure("Компьютер не смог открыть этот PDF", recoverable = true)
        if (com.point.core.flow.pdfLayerUnusable(text)) {
            return ActionResult.Failure(com.point.core.flow.capabilities.NO_READABLE_PDF_LAYER, recoverable = false)
        }

        val out = keepTextBesideDocument(source, text)
            ?: return ActionResult.Failure(TEXT_NOT_KEPT, recoverable = true)
        return ActionResult.Done(
            com.point.core.flow.capabilities.TEXT_IS_WITH_DOCUMENT,
            com.point.core.model.Findings(
                features = setOf(com.point.core.model.Feature.HAS_TEXT),
                metadata = mapOf(com.point.core.flow.META_OCR_TEXT_REF to out.absolutePath),
            ),
        )
    }
}

class PcOfficePdfRealizer(
    private val converter: OfficeToPdf,
) : Realizer {
    override val capabilityId = com.point.core.flow.capabilities.PdfCapability.ID

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.OFFICE

    override fun unavailableReason(): String? = converter.whyUnavailable()

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {

            converter.whyUnavailable()?.let {
                return ActionResult.Failure(it, recoverable = true)
            }
            val pdf = converter.convert(File(input.uri.value))
                ?: return ActionResult.Failure(
                    "Компьютер не смог собрать PDF из этого документа",
                    recoverable = true,
                )

            // Результат появляется здесь, на компьютере (PC3/P4 — раньше он уезжал только
            // в очередь телефона, и на самом ПК PDF было не открыть). Телефону при его
            // команде тот же Success уедет ответом, а поздний — очередью компьютера.
            ActionResult.Success(
                com.point.core.model.ResultObject(
                    type = ObjectKind.PDF,
                    mime = "application/pdf",
                    uri = com.point.core.model.ScratchRef(pdf.absolutePath),
                    metadata = mapOf("name" to pdf.name),
                ),
            )
        }.getOrElse { ActionResult.Failure("PDF не собрался — закройте документ, если он открыт в Office, и повторите", recoverable = true) }
}

class PcPrintCapability : Capability {
    override val id = CapabilityId("pc-print")
    override val icon = "print"
    override val meta = CapabilityMeta(priority = 25)
    override fun label(state: ObjectState) = "Напечатать"

    // Печатается файл — не ссылка и не узел знания (#655).
    override fun accepts(state: ObjectState) = state.kind.isFileBacked
    override fun produces(state: ObjectState) = state
}

class PcPrintRealizer(private val printer: Printer) : Realizer {
    override val capabilityId = CapabilityId("pc-print")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {

            val target = printer.name()
                ?: return ActionResult.Failure(
                    "На компьютере сейчас нет принтера по умолчанию",
                    recoverable = true,
                )

            if (com.point.core.flow.askedHere()) {
                if (!printer.printAsking(File(input.uri.value))) {
                    return@runCatching ActionResult.Failure("Печать отменена — задание не ушло", recoverable = true)
                }
            } else {
                printer.print(File(input.uri.value))
            }

            ActionResult.Done("Отправлено на печать · $target")
        }.getOrElse { ActionResult.Failure("Напечатать не вышло — проверьте, что принтер включён", recoverable = true) }
}
