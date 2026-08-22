package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.ClipboardPayload
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.META_ENTITY_URL
import com.point.core.flow.Realizer
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

class PcOpenRealizer(private val opener: SystemOpener) : Realizer {
    override val capabilityId = CapabilityId("pc-open")
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            opener.open(File(input.uri.value))
            ActionResult.Done("Открыто")
        }.getOrElse { ActionResult.Failure("Открыть нечем: у этого файла нет программы по умолчанию", recoverable = true) }
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
            val saved = target.pickAndSave(File(input.uri.value))
                ?: return ActionResult.Done("Отменено")
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

class PcToPhoneCapability : Capability {
    override val id = CapabilityId("pc-to-phone")

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

    /** Стук телефону: пусть узнает о ждущем сейчас, а не при следующем открытии (#1079). */
    private val knockPhone: suspend () -> Unit = {},
) : Realizer {
    override val capabilityId = CapabilityId("pc-to-phone")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching {
            outbox.add(input)
            runCatching { knockPhone() }

            // Слова не опережают сделанное (#1079): письмо легло в очередь, плашка на
            // телефоне появится, когда он за ним придёт, — а не в момент этого тапа.
            ActionResult.Done("Ждёт телефона: откройте на телефоне главный экран Point и заберите объект")
        }.getOrElse { ActionResult.Failure("Не удалось отправить — проверьте, что на диске есть место", recoverable = true) }
}

/**
 * Текст из PDF на компьютере (#631): та же способность и те же слова, что на телефоне.
 * Скан сюда не доходит — его дверь не рисуется вовсе (`IS_IMAGE_PDF` ставится при приёме),
 * и «Извлечь текст» больше не заканчивается пустотой.
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
        if (text.isBlank()) {
            return ActionResult.Failure("В этом PDF нет текстового слоя — это снимки страниц", recoverable = false)
        }

        // Слой бывает и нечитаемым: у документа своя раскладка шрифта, и кириллица лежит под
        // латинскими кодами. Раньше этот мусор становился текстом объекта, и над ним писалось
        // «ПОНЯЛ» с телефоном и датой, выведенными из бессмыслицы (#933). Так устроены очень
        // многие украинские бухгалтерские PDF — ровно тот корпус, ради которого Point и нужен.
        if (com.point.core.flow.ReadableText.unreadable(text)) {
            val page = runCatching { renderFirstPage(source) }.getOrNull()
                ?: return ActionResult.Failure(UNREADABLE_LAYER, recoverable = true)
            return ActionResult.Success(
                com.point.core.model.ResultObject(
                    type = ObjectKind.IMAGE,
                    mime = "image/png",
                    uri = com.point.core.model.ScratchRef(page.absolutePath),
                    metadata = mapOf("name" to page.name),
                ),
            )
        }
        val out = File(source.parentFile, source.nameWithoutExtension + ".txt")
        return runCatching {
            out.writeText(text)
            ActionResult.Success(
                com.point.core.model.ResultObject(
                    type = ObjectKind.TEXT,
                    mime = "text/plain",
                    uri = com.point.core.model.ScratchRef(out.absolutePath),
                    metadata = mapOf("name" to out.name),
                ),
            )
        }.getOrElse { ActionResult.Failure("Текст не сохранился — проверьте, что на диске есть место", recoverable = true) }
    }

    /** Первая страница картинкой — её и прочитает распознавание, как любой другой кадр. */
    private fun renderFirstPage(source: File): File {
        val out = File(source.parentFile, source.nameWithoutExtension + " — страница.png")
        org.apache.pdfbox.pdmodel.PDDocument.load(source).use { document ->
            val image = org.apache.pdfbox.rendering.PDFRenderer(document).renderImageWithDPI(0, PAGE_DPI)
            javax.imageio.ImageIO.write(image, "png", out)
        }
        return out
    }

    private companion object {
        const val PAGE_DPI = 200f

        const val UNREADABLE_LAYER =
            "Текст в этом PDF нечитаем — у документа своя раскладка шрифта. Прочитать страницу " +
                "снимком не вышло"
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
