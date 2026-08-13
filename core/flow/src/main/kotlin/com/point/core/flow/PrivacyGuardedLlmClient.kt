package com.point.core.flow

import com.point.core.model.PointObject
import com.point.core.model.ResultObject

/**
 * Общий шов режима приватности перед выходом наружу.
 *
 * Режим «Только на телефоне» обещает человеку, что ничего не уходит с телефона.
 * Проверка жила внутри одной ветки распознавания, и все остальные — «Понять»,
 * «Перевести», «AI», «В Excel», «В Word», расшифровка речи — отправляли объект
 * в облако вопреки выбранному режиму (#689, охота 2026-08-09).
 *
 * Здесь она стоит на единственной дороге наружу: новый исполнитель с [LlmClient]
 * попадает под режим сам, ничего не зная о нём.
 */
class PrivacyGuardedLlmClient(
    private val inner: LlmClient,
    private val privacy: CloudPrivacySettings,
    /**
     * Мерка режима: пускает ли он наружу хоть кого-нибудь (#945). Кого именно из сервисов
     * пускать, решает цепочка — у каждого своё обещание.
     */
    private val outsidePrivacy: ReaderPrivacy = PROMISED_SERVICE,
) : LlmClient {

    private val allowed: Boolean get() = allowedAt(privacy.level(), outsidePrivacy)

    override suspend fun run(obj: PointObject, prompt: String): ResultObject {
        if (!allowed) error(chainClosedBy(privacy.level()))
        return inner.run(obj, prompt)
    }

    override fun canHandle(obj: PointObject) = inner.canHandle(obj)

    override val strongVision: Boolean get() = inner.strongVision

    // Ключ на месте — действие остаётся видимым и объясняет отказ по тапу, а не
    // исчезает молча: прятать дверь, ради которой выбирают режим, нельзя.
    override val configured: Boolean get() = inner.configured
}

fun chainClosedBy(level: PrivacyLevel): String = when (level) {
    PrivacyLevel.DEVICE_ONLY ->
        "Наружу сейчас не отправляем — это меняется в настройках"
    PrivacyLevel.NO_TRAINING ->
        "Наружу можно только к тем, кто обещал не учиться на присланном, — такого сейчас нет"
    PrivacyLevel.FREE_FIRST -> "Отправка наружу закрыта настройками"
}
