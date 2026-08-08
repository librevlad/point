package com.point.data

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.Findings
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

class ZipImagesInvestigation @Inject constructor() : Capability {

    override val id = ID

    override val icon = ""

    override val meta = CapabilityMeta(
        investigation = true,
        latency = Latency.FAST,
        mayYield = setOf(Feature.ZIP_OF_IMAGES),
    )

    override fun label(state: ObjectState) = ""

    override fun accepts(state: ObjectState) = state.kind == ObjectKind.ZIP

    override fun produces(state: ObjectState) = state

    companion object {

        val ID = com.point.core.model.CapabilityId("zip-images")
    }
}

class ZipImagesInvestigationRealizer @Inject constructor() : Realizer {

    override val capabilityId = ZipImagesInvestigation.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        runCatching { findings(input) }.fold(
            onSuccess = { ActionResult.Done("", it) },

            onFailure = { ActionResult.Failure(it.message ?: FAILED, recoverable = true) },
        )

    private suspend fun findings(obj: PointObject): Findings = withContext(Dispatchers.IO) {
        var files = 0
        var images = 0

        // Именно ZipFile, а не потоковый скан: поток молча принимает обрезанный архив за
        // короткий, и «не смогли прочитать» неотличимо от «прочитали». Центральный каталог
        // на битом или обрезанном архиве честно падает — это Failure операции (ADR-0001 §9).
        ZipFile(File(obj.uri.value)).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements() && files < MAX_SCAN) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    files++
                    if (isImage(entry.name)) images++
                }
            }
        }
        if (files > 0 && images == files) Findings(setOf(Feature.ZIP_OF_IMAGES)) else Findings()
    }

    private fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    private companion object {
        const val MAX_SCAN = 200
        val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    }
}

private const val FAILED = "исследование не удалось"
