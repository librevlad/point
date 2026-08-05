package com.point.desktop

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Архив на компьютере: распаковать и показать в папке (#585).
 *
 * На телефоне распаковка отдаёт коллекцию объектов — там это и нужно, потому что дальше человек
 * работает с каждым файлом в самом Point. На компьютере распаковка означает другое: файлы должны
 * оказаться **в папке**, где их ждут проводник, редактор и всё остальное. Поэтому здесь она
 * кончается открытым каталогом, а не списком внутри Point.
 *
 * Zip хватает: он же .docx, .xlsx, .pptx, .apk, .jar и почти всё, что приходит почтой. Rar и 7z
 * требуют чужих библиотек — на телефоне они есть, здесь их заводить не за что, и действие честно
 * скажет, что не смогло, вместо того чтобы распаковать половину.
 */
class PcUnzipCapability : Capability {
    override val id = CapabilityId("pc-unzip")
    override val icon = "unzip"
    override val meta = CapabilityMeta(priority = 22, latency = Latency.FAST)
    override fun label(state: ObjectState) = "Распаковать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.ZIP
    override fun produces(state: ObjectState) = state
}

class PcUnzipRealizer(private val revealer: FileRevealer) : Realizer {
    override val capabilityId = CapabilityId("pc-unzip")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val archive = File(input.uri.value).takeIf(File::isFile)
                    ?: return@withContext ActionResult.Failure("Файла архива нет на диске", recoverable = false)
                val target = File(archive.parentFile, archive.nameWithoutExtension).let(::freeName)
                target.mkdirs()
                var files = 0
                ZipInputStream(archive.inputStream().buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        // Путь из архива — не наше дело, а чужое: `../../..` в имени вывел бы
                        // распаковку за пределы каталога. Проверяется каждый файл, а не первый.
                        val out = File(target, entry.name).canonicalFile
                        if (!out.path.startsWith(target.canonicalFile.path + File.separator)) {
                            return@withContext ActionResult.Failure(
                                "В архиве файл с путём наружу — распаковка остановлена",
                                recoverable = false,
                            )
                        }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                            files++
                        }
                        zip.closeEntry()
                    }
                }
                if (files == 0) {
                    target.delete()
                    return@withContext ActionResult.Failure(
                        "Пустой архив или формат, который компьютер не открывает (rar, 7z)",
                        recoverable = false,
                    )
                }
                revealer.reveal(File(target, target.listFiles()?.firstOrNull()?.name ?: "."))
                ActionResult.Done("Распаковано файлов: $files — папка открыта")
            }.getOrElse {
                ActionResult.Failure(it.message ?: "Не удалось распаковать", recoverable = true)
            }
        }

    /** Соседняя папка с тем же именем — чужая: распаковка не затирает то, что уже лежало. */
    private fun freeName(base: File): File {
        if (!base.exists()) return base
        var n = 2
        while (File(base.parentFile, base.name + " ($n)").exists()) n++
        return File(base.parentFile, base.name + " ($n)")
    }
}

/**
 * Открыть ссылку в браузере (#585).
 *
 * `pc-open` открывает **файл** системным приложением, и для объекта-ссылки это не работает: файла
 * с таким содержимым нет, есть строка внутри. Самый частый случай — ссылка приехала с телефона, и
 * человек хочет увидеть её на большом экране; ради этого Point на ПК и ставят.
 */
class PcOpenLinkCapability : Capability {
    override val id = CapabilityId("pc-open-link")
    override val icon = "link"
    override val meta = CapabilityMeta(priority = 5)
    override fun label(state: ObjectState) = "Открыть в браузере"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.URL
    override fun produces(state: ObjectState) = state
}

class PcOpenLinkRealizer(private val browser: (String) -> Unit) : Realizer {
    override val capabilityId = CapabilityId("pc-open-link")

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult = runCatching {
        val link = File(input.uri.value).takeIf(File::isFile)?.readText()?.trim().orEmpty()
        val url = link.lineSequence().firstOrNull { it.startsWith("http", ignoreCase = true) }?.trim()
            ?: return ActionResult.Failure("В объекте нет ссылки, которую можно открыть", recoverable = false)
        browser(url)
        ActionResult.Done("Открыто в браузере")
    }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось открыть ссылку", recoverable = true) }
}
