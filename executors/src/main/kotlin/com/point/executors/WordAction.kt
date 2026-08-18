package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.DocxWriter
import com.point.core.flow.Latency
import com.point.core.flow.Realizer
import com.point.core.flow.TextRecognizer
import com.point.core.flow.reportStage
import com.point.core.model.ActionResult
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal fun toParagraphs(text: String): List<String> =
    text.replace("\r\n", "\n").replace("\r", "\n").split("\n")

class WordCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "office"

    override val meta = CapabilityMeta(latency = Latency.SLOW)
    override fun label(state: ObjectState) = "В Word"
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.PDF || state.kind == ObjectKind.TEXT || state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.OFFICE)

    override fun yields(state: ObjectState) = ActionYield.New(ObjectKind.OFFICE, "документ Word")
    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("word") }
}

class WordRealizer @Inject constructor(
    private val known: com.point.core.flow.CurrentKnowledge,
    private val docx: DocxWriter,
    private val recognizer: TextRecognizer,
) : Realizer {
    override val capabilityId = WordCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {

                // Документ собирается из того, что Point уже знает (#1031, #1138): прежде
                // кадр читался заново, и в документ уезжало прочтение хуже того, которое
                // лежало в графе, — с искажёнными цифрами.
                val text = known.textOf(input) ?: when (input.state.kind) {
                    ObjectKind.IMAGE -> {
                        reportStage("Распознаю текст на фото")
                        recognizer.recognize(input)
                    }
                    else -> ""
                }
                if (text.isBlank()) {
                    ActionResult.Failure("Нет текста (возможно, это скан — сначала распознайте текст)", recoverable = true)
                } else {
                    reportStage("Собираю документ")
                    ActionResult.Success(
                        ResultObject(ObjectKind.OFFICE, DOCX_MIME, docx.write(toParagraphs(text)), mapOf("op" to "to-word")),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось создать документ", recoverable = true) }
        }

    private companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
