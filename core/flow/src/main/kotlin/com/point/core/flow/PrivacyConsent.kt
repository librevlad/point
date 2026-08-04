package com.point.core.flow

/**
 * Что именно человек разрешает, отпуская объект с устройства (#114).
 *
 * Один флаг на всё был неправдой: разрешив «Понять» (объект уходит модели и возвращается
 * результатом), человек тем же тапом навсегда разрешал «Дать ссылку» — а это другая цена, файл
 * ложится на сервер **открытым**. Разные обещания требуют разных «да».
 */
enum class CloudScope {
    /** Объект уходит сервису и возвращается результатом. Спрашивается один раз: уровень
     *  приватности человек задаёт настройкой, а не допросом на каждый тап (`VISION-MODELS.md`). */
    MODELS,

    /**
     * Файл ложится на сервер открытым: заберёт любой, кому переслали ссылку.
     *
     * Своё «да» на каждый файл. Не из подозрительности к человеку, а потому что цена относится к
     * КОНКРЕТНОМУ файлу: разрешение, данное однажды на договор, не может отвечать за паспорт,
     * выложенный через неделю. И только так цена называется ДО отправки, а не после неё.
     */
    PUBLIC_LINK,
}

/** Что из этого запоминается. Публичная ссылка — никогда: см. [CloudScope.PUBLIC_LINK]. */
fun remembersConsent(scope: CloudScope): Boolean = scope == CloudScope.MODELS

/**
 * Consent to send the user's object to a cloud service. Cloud actions (AI, Перевести,
 * В Excel — anything with [CapabilityMeta.network]) must not leave the device before
 * the user has agreed. This is a store-policy requirement and a trust matter,
 * not a nicety: without consent, nothing is uploaded.
 */
interface PrivacyConsent {
    suspend fun allowed(scope: CloudScope): Boolean

    suspend fun allow(scope: CloudScope)

    /** Забрать разрешение обратно (#114): согласие, которое нельзя отозвать, — не согласие. */
    suspend fun revoke(scope: CloudScope)
}
