package com.point.executors

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Clipboard
import com.point.core.flow.EntityExtractor
import com.point.core.flow.EntityType
import com.point.core.flow.Realizer
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.isFileBacked
import com.point.core.model.PointObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** text / url → clipboard. Terminal (`Done`) — extract-and-paste-elsewhere without leaving Point. */
class CopyCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "copy"
    override val meta = CapabilityMeta(priority = 55)
    override fun label(state: ObjectState) = "Скопировать"
    override fun accepts(state: ObjectState) =
        state.kind == ObjectKind.TEXT || state.kind == ObjectKind.URL ||
            // An extracted object (#222) IS its value — the waybill number the user came for.
            // Копировать is the one action that always makes sense on it.
            !state.kind.isFileBacked
    override fun produces(state: ObjectState) = state // terminal → SEND

    // Копирование объявляет свой исход само: по намерению оно стоит рядом с отправкой и потому
    // получало её обещание — «отправит и вернётся сюда» у действия, которое никуда не отправляет.
    override fun yields(state: ObjectState) = com.point.core.model.ActionYield.Copied

    companion object { val ID = CapabilityId("copy") }
}

class CopyRealizer @Inject constructor(
    private val clipboard: Clipboard,
) : Realizer {
    override val capabilityId = CopyCapability.ID
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = if (input.state.kind.isFileBacked) {
                    File(input.uri.value).takeIf { it.isFile }?.readText()?.trim().orEmpty()
                } else {
                    input.uri.value.trim() // no file behind it: the value is the content (#222)
                }
                if (text.isBlank()) {
                    ActionResult.Failure("Нечего копировать", recoverable = true)
                } else {
                    clipboard.copy(text, "Point")
                    ActionResult.Done("Скопировано")
                }
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось скопировать", recoverable = true) }
        }
}

/** A detected bank-card number → clipboard (digits only). A targeted "right-click" action. */
class CopyCardCapability @Inject constructor() : Capability {
    override val id = ID
    override val icon = "copy"
    override val meta = CapabilityMeta(priority = 17)
    override fun label(state: ObjectState) = "Скопировать карту"
    override fun accepts(state: ObjectState) = state.has(Feature.HAS_CARD)
    override fun produces(state: ObjectState) = state

    /** Та же правда, что у обычного копирования: карта ложится в буфер, а не уходит наружу. */
    override fun yields(state: ObjectState) = com.point.core.model.ActionYield.Copied

    companion object { val ID = CapabilityId("copy-card") }
}

class CopyCardRealizer @Inject constructor(
    private val extractor: EntityExtractor,
    private val clipboard: Clipboard,
) : Realizer {
    override val capabilityId = CopyCardCapability.ID
    override suspend fun perform(input: PointObject, amendment: String?): ActionResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val card = firstEntity(extractor, input, EntityType.PAYMENT_CARD) ?: error("Карта не найдена")
                clipboard.copy(card.filter { it.isDigit() }, "Card")
                ActionResult.Done("Скопирован номер карты")
            }.getOrElse { ActionResult.Failure(it.message ?: "Не удалось скопировать", recoverable = true) }
        }
}
