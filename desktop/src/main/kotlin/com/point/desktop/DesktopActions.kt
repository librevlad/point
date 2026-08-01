package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
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
    fun print(file: File)
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
    override fun label(state: ObjectState) = "Копировать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT
    override fun produces(state: ObjectState) = state
}

class PcCopyRealizer(private val clipboard: TextClipboard) : Realizer {
    override val capabilityId = CapabilityId("pc-copy")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            clipboard.copy(File(input.uri.value).readText())
            ActionResult.Done("Скопировано в буфер")
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
            printer.print(File(input.uri.value))
            // Мы знаем ровно одно: задание ушло в очередь этого принтера. Включён ли он, есть
            // ли бумага — нам отсюда не видно, и обещать «напечатано» значило бы отчитаться за
            // чужую машину. Человек уйдёт в другую комнату — пусть уходит с правдой.
            ActionResult.Done("В очереди «$target» · проверьте принтер")
        }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось напечатать", recoverable = true) }
}
