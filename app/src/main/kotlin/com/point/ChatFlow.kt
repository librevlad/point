package com.point

import com.point.core.model.Bubble
import com.point.core.model.CapabilityId
import com.point.core.model.ChatRole
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Разговор про объект: своё состояние и своя незавершённая работа (#833).
 *
 * Жил внутри `FlowViewModel` — 2480 строк и 36 зависимостей, где правка любого экрана трогала
 * один файл. Решение владельца: «по одной теме за раз, начать с разговора».
 *
 * Здесь держится то, что принадлежит разговору: идущий вопрос к модели и правила его отмены.
 * Сами реплики и ответы по-прежнему собирает `ChatTalk` — это разные вещи: одна знает, что
 * сказать, другая — когда бросить начатое.
 *
 * `FlowViewModel` о внутренностях не знает: он даёт способ прочитать и записать состояние
 * разговора и способ сделать действие, а всё остальное происходит здесь.
 */
class ChatFlow(
    private val talk: ChatTalk,
    private val scope: CoroutineScope,

    /** Текущий разговор и запись нового: состояние экрана принадлежит экрану, не разговору. */
    private val chat: () -> ChatState?,
    private val setChat: (ChatState?, Boolean) -> Unit,

    /** Как называется действие, которое модель предложила: спрашивается у реестра. */
    private val labelOf: (CapabilityId, ObjectState) -> String?,

    /** Значок предложенного действия — тот же, что у него в списке объекта. */
    private val iconOf: (CapabilityId) -> String,

    /** Предложенное действие делается обычным путём, как из списка объекта (#804). */
    private val runBubble: (Bubble) -> Unit,

    /** Забранный ответ становится объектом и встаёт в «Недавнее». */
    private val keepAnswer: (PointObject) -> Unit,

    private val onSuccess: () -> Unit,
    private val onFailure: (String) -> Unit,
) {

    private var work: Job? = null

    fun open(obj: PointObject) {
        setChat(talk.opened(obj, chat()), true)
    }

    /**
     * Уход человека завершает разговор (#794, решение владельца 11.08.2026: «назад → ai =
     * заново»).
     *
     * Прежде разговор сворачивался и следующий вход возвращал ту же переписку — а вместе с
     * ней исчезал стартовый экран с вариантами вопросов: единственное место, где сказано, о
     * чём вообще можно спросить объект. Забранные ответы при этом не теряются: они уже стали
     * объектами и живут в «Недавнем».
     *
     * Уход — отказ от работы, а не согласие ждать её в пустоте (#668): заданный вопрос
     * отменяется вместе с разговором, и облачный вызов не оплачивается после ухода.
     */
    fun close() {
        stopWork()
        setChat(null, false)
    }

    fun send(text: String) {
        val started = chat() ?: return
        val message = text.trim()
        if (message.isEmpty() || started.pending) return
        val history = started.messages
        setChat(talk.said(started, message), true)
        work?.cancel()
        work = scope.launch {
            val answered = talk.answered(chat() ?: started, message, history, labelOf)
            work = null
            if (chat() != null) setChat(answered, true)
        }
    }

    /** Человек передумал ждать ответ: вопрос снимается, переписка остаётся. */
    fun cancelMessage() {
        val running = work ?: return
        work = null
        running.cancel()
        chat()?.let { setChat(talk.stopped(it), true) }
    }

    /**
     * Тап по предложенному действию — обычный путь действия (#804): та же цена, тот же
     * исполнитель, тот же результат, что из списка объекта. Разговор при этом закрывается:
     * он кончился делом, а не репликой.
     */
    fun runOffer() {
        val started = chat() ?: return
        val offer = started.offer ?: return
        val obj = started.obj
        stopWork()
        setChat(null, false)
        runBubble(Bubble(iconOf(offer.capabilityId), offer.title, offer.capabilityId, obj.state))
    }

    fun takeAnswer() {
        val started = chat() ?: return
        if (started.pending) return
        val answer = started.messages.lastOrNull { it.role == ChatRole.ASSISTANT }
            ?.text?.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            val obj = runCatching { talk.answerObject(started, answer) }.getOrNull()
            if (obj == null) {
                onFailure(ANSWER_NOT_KEPT)
                return@launch
            }
            onSuccess()
            setChat(null, false)
            keepAnswer(obj)
        }
    }

    private fun stopWork() {
        work?.cancel()
        work = null
    }

    companion object {
        const val ANSWER_NOT_KEPT = "Не удалось забрать ответ"
    }
}
