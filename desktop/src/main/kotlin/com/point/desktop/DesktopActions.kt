package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import java.io.File

/**
 * Desktop side-effects behind seams (same invariant as the phone): the pairs below
 * stay JVM-pure and unit-testable; AWT lives only in the implementations wired in Main.
 */
fun interface SystemOpener { fun open(file: File) }
fun interface FileRevealer { fun reveal(file: File) }
fun interface TextClipboard { fun copy(text: String) }
fun interface SaveTarget { fun pickAndSave(file: File): String? }

/**
 * Печать на принтере компьютера (#291): телефон печатать не умеет, компьютер умеет.
 *
 * Печать уходит на принтер **по умолчанию**, без диалога: тап сделан на телефоне, и всплывший
 * на компьютере модальный диалог человек, стоящий в другой комнате, просто не увидит — работа
 * повиснет в тишине. Поэтому имя принтера [name] возвращается на телефон, чтобы человек видел,
 * куда ушла бумага, а не гадал.
 */
interface Printer {
    /** Имя принтера по умолчанию; `null` — принтера нет, и печатать некуда. */
    fun name(): String?

    /** Отправить на принтер по умолчанию, без вопросов. Так печатается просьба с телефона. */
    fun print(file: File)

    /**
     * Спросить человека и напечатать (#591). `false` — передумал.
     *
     * Диалог системный, а не свой: в нём сразу и принтер, и формат, и страницы, и двусторонняя
     * печать — всё то, чего в собственном списке принтеров не будет, и что через месяц пришлось бы
     * добавлять самим.
     */
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

class PcOpenRealizer(private val opener: SystemOpener) : Realizer {
    override val capabilityId = CapabilityId("pc-open")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            opener.open(File(input.uri.value))
            ActionResult.Done("Открыто")
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть", recoverable = true) }
}

class PcRevealCapability : Capability {
    override val id = CapabilityId("pc-reveal")
    override val icon = "folder"
    override val meta = CapabilityMeta(priority = 20)
    override fun label(state: ObjectState) = "Показать в папке"
    override fun accepts(state: ObjectState) = true
    override fun produces(state: ObjectState) = state
}

class PcRevealRealizer(private val revealer: FileRevealer) : Realizer {
    override val capabilityId = CapabilityId("pc-reveal")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            revealer.reveal(File(input.uri.value))
            ActionResult.Done("Папка открыта")
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось показать", recoverable = true) }
}

class PcCopyCapability : Capability {
    override val id = CapabilityId("pc-copy")
    override val icon = "copy"
    override val meta = CapabilityMeta(priority = 15)
    override fun label(state: ObjectState) =
        if (state.kind == ObjectKind.IMAGE) "Копировать картинку" else "Копировать"

    /**
     * Текст и картинка (#585).
     *
     * Картинка добавлена не для полноты: снимок экрана, сделанный тут же, чаще всего нужен не
     * файлом, а вставкой — в письмо, в задачу, в переписку. Сохранять его на диск ради этого
     * человек не должен.
     */
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.TEXT || state.kind == ObjectKind.IMAGE

    override fun produces(state: ObjectState) = state
}

/**
 * Копирование кладёт в буфер то, ЧЕМ объект является, а не его байты как текст.
 *
 * Картинка, положенная строкой, вставится в письмо кашей из символов; текст, положенный картинкой,
 * не вставится вовсе. Поэтому вид объекта решает форму, а не наоборот.
 */
class PcCopyRealizer(
    private val clipboard: TextClipboard,
    /** Картинка в буфер — своим швом: `TextClipboard` по определению кладёт строки. */
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
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось скопировать", recoverable = true) }
}

class PcSaveAsCapability : Capability {
    override val id = CapabilityId("pc-save-as")
    override val icon = "save"
    override val meta = CapabilityMeta(priority = 30)
    override fun label(state: ObjectState) = "Сохранить в…"
    override fun accepts(state: ObjectState) = true
    override fun produces(state: ObjectState) = state
}

class PcSaveAsRealizer(private val target: SaveTarget) : Realizer {
    override val capabilityId = CapabilityId("pc-save-as")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            val saved = target.pickAndSave(File(input.uri.value))
                ?: return ActionResult.Done("Отменено")
            ActionResult.Done("Сохранено: $saved")
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось сохранить", recoverable = true) }
}

/** yt-dlp behind a seam (#80 v2): availability decides whether «Скачать видео» is
 *  advertised at all; start() fires the download in the background. */
interface VideoDownloader {
    fun available(): Boolean

    /** Launch the download of [url]; true when the process started. */
    fun start(url: String): Boolean
}

/** The real yt-dlp: `yt-dlp -P <downloads> <url>`, fire-and-forget. */
class YtDlpDownloader(private val downloadsDir: File) : VideoDownloader {
    override fun available(): Boolean = runCatching {
        ProcessBuilder("yt-dlp", "--version").start().waitFor() == 0
    }.getOrDefault(false)

    override fun start(url: String): Boolean = runCatching {
        downloadsDir.mkdirs()
        ProcessBuilder("yt-dlp", "-P", downloadsDir.absolutePath, url)
            .redirectErrorStream(true)
            .redirectOutput(File(downloadsDir, "yt-dlp.log"))
            .start()
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
        val url = runCatching { File(input.uri.value).readText() }.getOrDefault("")
            .lineSequence().map(String::trim).firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
            ?: return ActionResult.Failure("В объекте нет ссылки", recoverable = true)
        return if (downloader.start(url)) {
            ActionResult.Done("Скачиваю: $url")
        } else {
            ActionResult.Failure("Не удалось запустить yt-dlp", recoverable = true)
        }
    }
}

/** «На телефон» (#161): drop the object into the outbox — the phone pulls it from
 *  its Home banner. The liquid половина ПК→телефон. */
class PcToPhoneCapability : Capability {
    override val id = CapabilityId("pc-to-phone")
    override val icon = "pc"
    override val meta = CapabilityMeta(priority = 15)
    override fun label(state: ObjectState) = "На телефон"
    override fun accepts(state: ObjectState) = true
    override fun produces(state: ObjectState) = state
}

class PcToPhoneRealizer(private val outbox: Outbox) : Realizer {
    override val capabilityId = CapabilityId("pc-to-phone")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            outbox.add(input)
            ActionResult.Done("Заберите на телефоне — плашка на главном экране")
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось положить в очередь", recoverable = true) }
}


/**
 * Компьютер делает PDF **только из офисного документа** — настоящим конвертером.
 *
 * Общая способность «В PDF» принимает шире: картинку, текст, офис и PDF. До появления
 * [Realizer.accepts] это различие было невыразимо, и намерение приходилось держать у каждого
 * устройства своим, лишь бы компьютер не обещал того, чего не сделает (контракт 06.08.2026, И3).
 */
class PcOfficePdfRealizer(
    private val converter: OfficeToPdf,
    private val outbox: Outbox,
) : Realizer {
    override val capabilityId = com.point.core.flow.capabilities.PdfCapability.ID

    /**
     * Компьютер делает PDF **только из офисного документа** — настоящим конвертером.
     *
     * Общая способность «В PDF» принимает шире: картинку, текст, офис и PDF. До появления
     *  это различие было невыразимо, и намерение приходилось держать у каждого
     * устройства своим — лишь бы компьютер не обещал того, чего не сделает (контракт, И3).
     */
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.OFFICE

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            // Конвертер проверяется В МОМЕНТ работы, а не когда рисовалась кнопка: между тапом на
            // телефоне и работой на компьютере Office могли снести (тот же урок, что у принтера).
            converter.whyUnavailable()?.let {
                return ActionResult.Failure(it, recoverable = true)
            }
            val pdf = converter.convert(File(input.uri.value))
                ?: return ActionResult.Failure(
                    "Компьютер не смог собрать PDF из этого документа",
                    recoverable = true,
                )
            outbox.add(
                input.copy(
                    id = input.id + "-pdf",
                    mime = "application/pdf",
                    uri = com.point.core.model.ScratchRef(pdf.absolutePath),
                    state = ObjectState(ObjectKind.PDF),
                    metadata = input.metadata + ("name" to pdf.name),
                ),
            )
            ActionResult.Done("PDF собран на компьютере — заберите на телефоне")
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось собрать PDF", recoverable = true) }
}

/**
 * «Напечатать на ПК» (#291) — ровно то, за чем человек идёт к компьютеру с телефона в руках.
 *
 * Новой машинерии не нужно: телефон уже превращает каждое рекламируемое компьютером действие
 * в пузырёк ([RemotePcCapability]), а объект уже доезжает до ПК по существующему транспорту.
 * Здесь только пара «что» и «как» на стороне компьютера — и шов [Printer], за которым в
 * `Main` живёт AWT.
 */
class PcPrintCapability : Capability {
    override val id = CapabilityId("pc-print")
    override val icon = "print"
    override val meta = CapabilityMeta(priority = 25)
    override fun label(state: ObjectState) = "Напечатать"
    override fun accepts(state: ObjectState) = true
    override fun produces(state: ObjectState) = state
}

class PcPrintRealizer(private val printer: Printer) : Realizer {
    override val capabilityId = CapabilityId("pc-print")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            // Принтер проверяется В МОМЕНТ печати, а не когда рисовалась кнопка: между тапом
            // на телефоне и работой на компьютере принтер могли отключить или сменить
            // (консилиум, инженер по надёжности — состояние второй машины живёт своей жизнью).
            val target = printer.name()
                ?: return ActionResult.Failure(
                    "На компьютере сейчас нет принтера по умолчанию",
                    recoverable = true,
                )
            // Человек перед экраном — спрашиваем; просьба с телефона — печатаем на принтере по
            // умолчанию, потому что диалог там повиснет и задание не уйдёт вовсе (#591).
            if (com.point.core.flow.askedHere()) {
                if (!printer.printAsking(File(input.uri.value))) {
                    return@runCatching ActionResult.Failure("Печать отменена — задание не ушло", recoverable = true)
                }
            } else {
                printer.print(File(input.uri.value))
            }
            // Мы знаем ровно одно: задание ушло в очередь этого принтера. Включён ли он, есть
            // ли бумага — нам отсюда не видно, и обещать «напечатано» значило бы отчитаться за
            // чужую машину. Человек уйдёт в другую комнату — пусть уходит с правдой.
            ActionResult.Done("В очереди «$target» · проверьте принтер")
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось напечатать", recoverable = true) }
}
