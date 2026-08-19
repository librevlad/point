package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.ObjectStore
import com.point.core.flow.QrReader
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class ReadQrCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "qr-scan"
    // Отвечает на вопрос чтения кода: содержимое уже показано — действие уходит вниз (#1119).
    override val meta = CapabilityMeta(priority = 22, answers = com.point.core.model.CapabilityId("qr-content"))
    /** Код называется тем, что он есть: «Считать QR» на QR, «Скопировать цифры кода» на штрихкоде. */
    override fun label(state: ObjectState) =
        if (state.has(Feature.HAS_BARCODE) && !state.has(Feature.HAS_QR)) "Скопировать цифры кода" else "Считать QR"

    override fun accepts(state: ObjectState) = state.has(Feature.HAS_QR) || state.has(Feature.HAS_BARCODE)
    override fun produces(state: ObjectState) = ObjectState(com.point.core.model.ObjectKind.TEXT)
    override fun intents(state: ObjectState) = setOf(Intent.UNDERSTAND)

    companion object { val ID = CapabilityId("read-qr") }
}

class ReadQrRealizer @Inject constructor(
    private val store: ObjectStore,
    private val reader: QrReader,
) : Realizer {
    override val capabilityId = ReadQrCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val found = reader.scan(input.uri.value)
                val text = found?.text
                if (text.isNullOrBlank()) {
                    // Не прочитан — так и сказано, без догадок (приёмка #445.3).
                    ActionResult.Failure(
                        if (input.state.has(Feature.HAS_BARCODE)) "Код не прочитан" else "QR-код не распознан",
                        recoverable = true,
                    )
                } else {
                    val ref = store.newScratchFile("txt")
                    File(ref.value).writeText(text)
                    ActionResult.Success(
                        ResultObject(
                            com.point.core.model.ObjectKind.TEXT,
                            "text/plain",
                            ref,
                            mapOf("op" to "read-qr"),
                        ),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось прочитать код", recoverable = true) }
        }
}
