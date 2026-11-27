package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ResultObject
import com.point.core.model.tr

/**
 * AI без своего ключа: пробные действия через сервер Point (#477).
 *
 * Владелец 27.11.2026, на модель «Pro за AI без ключа» и первым шагом пробную квоту: «Да, так
 * и делаем». Свежий Point без ключа почти ничего не умел — «без настроенных ИИ моделей
 * программа неюзабельна по сути». Теперь вошедший в аккаунт человек получает несколько
 * AI-действий даром, а свой ключ остаётся первым в цепочке и идёт мимо сервера.
 *
 * Сервер отвечает голосом OpenAI, поэтому исполнитель — тот же [OpenAiCompatibleClient], только
 * ключом служит пропуск устройства, а модель выбирает сервер. Наверху у сервера лишь сервисы,
 * которые письменно обещают не учиться на присланном (`relay/point_server/ai.py`): отсюда и
 * обещание [POINT_AI_PRIVACY].
 */
const val POINT_AI_SERVICE_ID = "point"

/** Модель называет сервер; телефон шлёт метку, чтобы запрос оставался запросом OpenAI. */
private const val POINT_AI_MODEL = "point-trial"

val POINT_AI_PRIVACY: ReaderPrivacy
    get() = ReaderPrivacy(where = "Point", promise = ReaderPromise.NO_TRAINING)

/** Что сервер сказал о пробе: есть ли она у него вообще и сколько действий осталось. */
data class PointAiTrial(val available: Boolean, val left: Int, val trial: Int)

/** Память о пробе: спрашивается у сервера при его ответе о круге, а не перед каждым действием. */
interface PointAiQuota {
    fun current(): PointAiTrial?
    fun remember(trial: PointAiTrial)
}

class MemoryPointAiQuota : PointAiQuota {
    @Volatile private var value: PointAiTrial? = null
    override fun current(): PointAiTrial? = value
    override fun remember(trial: PointAiTrial) { value = trial }
}

/** Проба доступна: человек в аккаунте, у сервера есть ключи и действия ещё остались. */
fun PointAiQuota.usable(signedIn: Boolean): Boolean =
    signedIn && current()?.let { it.available && it.left > 0 } == true

val POINT_AI_TRIAL_OVER: String
    get() = tr("Пробные AI-действия кончились — {0}", AI_KEY_HINT)

class PointAiLlmClient(
    private val account: () -> PointAccount?,
    private val serverUrl: String,
    private val http: HttpJson,
    private val store: ObjectStore,
    private val quota: PointAiQuota,
    private val frames: FrameForModel = FrameForModel.NONE,
) : LlmClient {

    override val serviceId: String = POINT_AI_SERVICE_ID

    override val configured: Boolean
        get() = serverUrl.isNotBlank() && quota.usable(account() != null)

    // Снимок без кадра — сожжённое пробное действие: как и у ключа человека (#1239).
    override fun canHandle(obj: PointObject): Boolean =
        !obj.mime.startsWith("image/") || frames !== FrameForModel.NONE

    override suspend fun run(obj: PointObject, prompt: String): ResultObject {
        val pass = account()?.deviceToken ?: error(POINT_AI_TRIAL_OVER)
        val client = OpenAiCompatibleClient(
            http,
            store,
            OpenAiProvider(
                label = "Point",
                baseUrl = serverUrl.trimEnd('/') + "/ai/v1",
                apiKey = pass,
                model = POINT_AI_MODEL,
                vision = true,
                id = POINT_AI_SERVICE_ID,
            ),
            frames,
        )
        val result = try {
            client.run(obj, prompt)
        } catch (e: AiServiceRefusal) {
            // 402 — пробные действия кончились: дальше только свой ключ. Память об этом
            // нужна сразу, иначе следующее действие снова пошло бы на сервер за отказом.
            if (e.status == 402) {
                quota.current()?.let { quota.remember(it.copy(left = 0)) }
                throw AiServiceRefusal(POINT_AI_SERVICE_ID, 402, POINT_AI_TRIAL_OVER)
            }
            throw e
        }
        quota.current()?.let { quota.remember(it.copy(left = (it.left - 1).coerceAtLeast(0))) }
        return result
    }
}
