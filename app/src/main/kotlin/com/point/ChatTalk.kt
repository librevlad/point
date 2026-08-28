package com.point

import com.point.core.flow.AiChatResponder
import com.point.core.flow.CurrentKnowledge
import com.point.core.flow.MAX_CHAT_CONTENT
import com.point.core.flow.ObjectStore
import com.point.core.model.CapabilityId
import com.point.core.model.ChatMessage
import com.point.core.model.ChatRole
import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.Provenance
import com.point.executors.AiCapability
import com.point.executors.aiSuggestions
import com.point.executors.aiTransformTarget
import java.io.File

/**
 * Разговор об объекте: правила переписки без экрана и без модели представления (#833).
 *
 * Жил внутри `FlowViewModel` вместе с приёмом объекта, действиями, настройками, аккаунтом и
 * связкой с компьютером — 2480 строк на один класс. Решение владельца 12.08.2026: «По одной
 * теме за раз, начать с разговора».
 *
 * Здесь только то, что происходит в переписке. Дела, которые из неё вытекают, — выполнить
 * предложенное действие, забрать ответ объектом — делает тот, кто владеет стеком объектов:
 * разговор их называет, но не исполняет.
 */
class ChatTalk @javax.inject.Inject constructor(
    private val responder: AiChatResponder,
    private val store: ObjectStore,

    /** О чём идёт разговор: текущее знание объекта, а не исходный файл (#1138, #1241). */
    private val knowledge: CurrentKnowledge,
) {

    /** Начало разговора: прежняя переписка о том же объекте продолжается, чужая — нет. */
    fun opened(obj: PointObject, kept: ChatState?): ChatState =
        kept?.takeIf { it.obj.id == obj.id }
            ?: ChatState(obj = obj, suggestions = aiSuggestions(obj.state.kind))

    /** Человек сказал: реплика встаёт в переписку, ответ ещё идёт. */
    fun said(chat: ChatState, text: String): ChatState = chat.copy(
        messages = chat.messages + ChatMessage(ChatRole.USER, text),
        pending = true,
        notice = null,
        offer = null,
    )

    /**
     * Ответ разговора (#804): узнанная просьба сделать вещь приходит действием, всё
     * остальное — словами модели. Разговор вещей не делает сам.
     */
    suspend fun answered(
        chat: ChatState,
        message: String,
        history: List<ChatMessage>,

        /** Как называется действие для этого объекта; `null` — такого действия здесь нет. */
        actionTitle: (CapabilityId, ObjectState) -> String?,
    ): ChatState {
        val target = aiTransformTarget(message)
        if (target != null) {
            val title = actionTitle(target, chat.obj.state)
            if (title != null) return chat.copy(pending = false, offer = ChatOffer(target, title))
        }
        // Содержимое объекта уходит модели вместе с вопросом (#780) — и добывается один раз
        // на разговор (#1241): объект за разговор не меняется, а каждая реплика заново
        // разбирала весь PDF и целиком поднимала прочтение ради одних и тех же первых
        // символов. Спрашивается текущее знание объекта, а не исходный файл: прочитанный
        // кадр и текстовый слой документа уже лежат в Graph.
        val known = chat.content ?: knownTextOf(chat.obj)

        var failed = false
        val reply = runCatching { responder.reply(chat.obj, known.takeIf(String::isNotBlank), history, message) }
            .getOrElse {
                if (it is kotlinx.coroutines.CancellationException) throw it
                failed = true
                "Не получилось ответить: ${it.message ?: "ошибка"}"
            }
        return heard(chat.copy(content = known), reply, failed)
    }

    /** Не добылось — разговор идёт без содержимого, а не срывается. */
    private suspend fun knownTextOf(obj: PointObject): String =
        runCatching { knowledge.textOf(obj, MAX_CHAT_CONTENT) }
            .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else null }
            .orEmpty()

    /** Слово модели встало в переписку. */
    fun heard(chat: ChatState, text: String, failed: Boolean = false): ChatState = chat.copy(
        messages = chat.messages + ChatMessage(ChatRole.ASSISTANT, text, failed = failed),
        pending = false,
        notice = null,
    )

    /** Человек прервал ответ: работа остановлена, и это сказано (#668). */
    fun stopped(chat: ChatState): ChatState = chat.copy(pending = false, notice = ANSWER_STOPPED)

    /**
     * Ответ становится объектом — с происхождением: это слово модели, а не прочитанное
     * с кадра.
     */
    suspend fun answerObject(chat: ChatState, answer: String): PointObject {
        val ref = store.newScratchFile("md")
        File(ref.value).writeText(answer)
        return PointObject(
            id = "chat-${chat.obj.id}-${chat.messages.size}",
            mime = "text/markdown",
            uri = ref,
            state = ObjectState(ObjectKind.TEXT, features = setOf(Feature.HAS_TEXT)),
            metadata = mapOf("name" to ANSWER_NAME),
            provenance = Provenance.MODEL,
            sourceObjects = listOf(chat.obj.id),
            creatorAction = AiCapability.ID.value,
        )
    }

    companion object {
        const val ANSWER_NAME = "Ответ AI"
        const val ANSWER_STOPPED = "Ответ остановлен"
    }
}
