package com.point.desktop

import com.point.core.flow.BrowserOpener
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

class PcUnzipRealizer(private val revealer: FileRevealer) : Realizer {
    override val capabilityId = com.point.core.flow.capabilities.ArchiveCapability.ID

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
                ActionResult.Failure("Распаковать не вышло — попробуйте ещё раз или откройте архив проводником", recoverable = true)
            }
        }

    private fun freeName(base: File): File {
        if (!base.exists()) return base
        var n = 2
        while (File(base.parentFile, base.name + " ($n)").exists()) n++
        return File(base.parentFile, base.name + " ($n)")
    }
}

class PcOpenLinkCapability : Capability {
    override val id = CapabilityId("pc-open-link")
    override val icon = "link"
    override val meta = CapabilityMeta(priority = 5)
    override fun label(state: ObjectState) = "Открыть в браузере"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.URL
    override fun produces(state: ObjectState) = state
}

class PcOpenLinkRealizer(private val browser: BrowserOpener) : Realizer {
    override val capabilityId = CapabilityId("pc-open-link")

    // Где у объекта живёт адрес — знает `knownLink`, один на весь компьютер (#1087):
    // здесь читался только файл, и узел ссылки, знающий адрес знанием, оставался без неё.
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult {
        val url = knownLink(input)
            ?: return ActionResult.Failure("В объекте нет ссылки, которую можно открыть", recoverable = false)
        return openInBrowser(browser, url)
    }
}
