package com.point.executors

import com.point.core.flow.capabilities.QrCapability
import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.QrEncoder
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class QrRealizer @Inject constructor(
    private val qr: QrEncoder,
) : Realizer {
    override val capabilityId = QrCapability.ID

    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = File(input.uri.value).takeIf { it.exists() }?.readText()?.trim().orEmpty()
                when {
                    text.isBlank() -> ActionResult.Failure("Нет текста для QR-кода", recoverable = true)
                    text.length > MAX_QR_CHARS ->
                        ActionResult.Failure("Слишком длинный текст для QR-кода", recoverable = true)
                    else -> ActionResult.Success(
                        ResultObject(ObjectKind.IMAGE, "image/png", qr.encode(text), mapOf("op" to "qr")),
                    )
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось создать QR-код", recoverable = true) }
        }

    private companion object {

        const val MAX_QR_CHARS = 1000
    }
}
