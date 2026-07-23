package com.point.executors

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

/**
 * text / url → a QR-code image (#85 "превратить в…"). A true type transform: the result is an
 * IMAGE, so the whole image action set (save / share / open) opens on it. On-device, no network.
 */
class QrCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "qr"
    override val meta = CapabilityMeta(priority = 45)
    override fun label(state: ObjectState) = "QR-код"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT || state.kind == ObjectKind.URL
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)
    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("qr") }
}

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
        /** QR byte-mode capacity with ECC M is ~2300 bytes; UTF-8 Cyrillic is 2 bytes/char, so cap
         *  well under that and fail with a clear message rather than a cryptic ZXing overflow. */
        const val MAX_QR_CHARS = 1000
    }
}
