package com.point.executors

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import com.point.core.flow.Capability
import com.point.core.flow.ObjectStore
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * COLLECTION of images -> one multi-page PDF. The "объединить страницы" step of the
 * scan flow: several photos (or the pages of a rasterised PDF) become a single
 * document. Non-image entries are skipped; empty result is a recoverable failure.
 */
class MergePdfCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "pdf"
    override fun label(state: ObjectState) = "Объединить в PDF"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.COLLECTION
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.PDF)

    companion object { val ID = CapabilityId("merge-pdf") }
}

class MergePdfRealizer @Inject constructor(
    private val store: ObjectStore,
) : Realizer {
    override val capabilityId = MergePdfCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val files = File(input.uri.value).walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.name.lowercase() }
                    .toList()

                val document = PdfDocument()
                var pages = 0
                for (file in files) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue // skip non-images
                    val page = document.startPage(
                        PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pages + 1).create(),
                    )
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    document.finishPage(page)
                    bitmap.recycle()
                    pages++
                }

                if (pages == 0) {
                    document.close()
                    ActionResult.Failure("В коллекции нет изображений для PDF", recoverable = true)
                } else {
                    val ref = store.newScratchFile("pdf")
                    File(ref.value).outputStream().use { document.writeTo(it) }
                    document.close()
                    ActionResult.Success(
                        ResultObject(
                            ObjectKind.PDF,
                            "application/pdf",
                            ref,
                            mapOf("op" to "merge-pdf", "pages" to pages.toString(), "name" to "документ.pdf"),
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Ошибка объединения в PDF", recoverable = true) }
        }
}
