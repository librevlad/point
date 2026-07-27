package com.point.bot

import com.point.core.flow.CapabilityRegistry
import com.point.core.flow.ObjectClassifier
import com.point.core.flow.Resolver
import com.point.core.model.ActionResult
import com.point.core.model.CapabilityId
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.ScratchRef
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The bot loop (#92): an inbound object becomes the chat's current object; the Flow Graph
 * (capabilities that accept its state) becomes an inline keyboard; a tap runs the realizer
 * and the result becomes the new current object — the flow stack, one message deep.
 */
class BotEngine(
    private val api: TelegramApi,
    private val registry: CapabilityRegistry,
    private val resolver: Resolver,
    private val scratchDir: File,
    private val classifier: ObjectClassifier = ObjectClassifier(),
) {
    private val sessions = ConcurrentHashMap<Long, PointObject>()

    suspend fun onUpdate(update: TgUpdate) {
        update.message?.let { onMessage(it) }
        update.callback?.let { onCallback(it) }
    }

    private suspend fun onMessage(m: TgMessage) {
        val obj = when {
            m.fileId != null -> {
                val target = chatFile(m.chatId, m.fileName ?: "объект")
                if (!api.downloadFile(m.fileId, target)) {
                    api.sendMessage(m.chatId, "Не удалось скачать файл — попробуйте ещё раз")
                    return
                }
                wrap(target, m.mime ?: "application/octet-stream", m.fileName)
            }
            m.text != null -> {
                val target = chatFile(m.chatId, "text.txt")
                target.writeText(m.text)
                wrap(target, "text/plain", null)
            }
            else -> return
        }
        sessions[m.chatId] = obj
        offer(m.chatId, obj, header = "Понял: ${kindLabel(obj.state.kind)}")
    }

    private suspend fun onCallback(cb: TgCallback) {
        api.answerCallback(cb.id)
        val obj = sessions[cb.chatId] ?: run {
            api.sendMessage(cb.chatId, "Пришлите объект заново"); return
        }
        val id = cb.data.removePrefix("cap:").takeIf { it != cb.data } ?: return
        val result = runCatching { resolver.realizerFor(CapabilityId(id)).perform(obj, null) }
            .getOrElse { ActionResult.Failure(it.message ?: "Ошибка", recoverable = true) }
        when (result) {
            is ActionResult.Success -> {
                val next = materialize(result.result)
                sessions[cb.chatId] = next
                deliver(cb.chatId, next)
                offer(cb.chatId, next, header = null)
            }
            is ActionResult.Done -> api.sendMessage(cb.chatId, result.message)
            is ActionResult.Failure -> api.sendMessage(cb.chatId, "⚠️ ${result.reason}")
            is ActionResult.NeedsInput -> api.sendMessage(cb.chatId, result.prompt)
            is ActionResult.NeedsImage -> api.sendMessage(cb.chatId, result.prompt)
        }
    }

    /** Send the object itself: text inline, everything else as a document. */
    private suspend fun deliver(chatId: Long, obj: PointObject) {
        val file = File(obj.uri.value)
        if (obj.state.kind == ObjectKind.TEXT && file.length() < MAX_INLINE) {
            api.sendMessage(chatId, file.readText())
        } else if (file.isFile) {
            api.sendDocument(chatId, file, obj.metadata["name"])
        }
    }

    /** Offer the object's actions as an inline keyboard (the Flow Graph). */
    private suspend fun offer(chatId: Long, obj: PointObject, header: String?) {
        val buttons = registry.bubblesFor(obj.state).map { TgButton(it.title, "cap:${it.capabilityId.value}") }
        if (buttons.isEmpty()) {
            if (header != null) api.sendMessage(chatId, header)
            return
        }
        api.sendMessage(chatId, header ?: "Что дальше?", inlineKeyboard(buttons))
    }

    private fun wrap(file: File, mime: String, name: String?): PointObject {
        val state = classifier.classify(mime, file.length(), name)
        return PointObject(
            id = file.name, mime = mime, uri = ScratchRef(file.absolutePath),
            state = state, metadata = name?.let { mapOf("name" to it) } ?: emptyMap(),
        )
    }

    // The result's features are re-derived from its kind here (v1); richer enrichment
    // (entity re-detection for the next keyboard) is a follow-up slice.
    private fun materialize(result: ResultObject) = PointObject(
        id = File(result.uri.value).name, mime = result.mime, uri = result.uri,
        state = com.point.core.model.ObjectState(result.type), metadata = result.metadata,
    )

    private fun chatFile(chatId: Long, name: String): File {
        val dir = File(scratchDir, chatId.toString()).apply { mkdirs() }
        val safe = name.replace('/', '_').replace('\\', '_').ifBlank { "объект" }
        return File(dir, safe)
    }

    private companion object {
        const val MAX_INLINE = 3_500L // Telegram message text cap is ~4096 chars
    }
}
