package com.point.core.flow

import com.point.core.model.tr

/**
 * Одно обращение к сервису: адрес, ключ и модель. Не хранилище — хранилище
 * держит ключ на каждый сервис ([UserAiKeys], #699).
 */
data class UserAiConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,

    val savedAt: Long = 0L,
) {
    companion object {

        val DEFAULT = UserAiConfig(
            apiKey = "",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "google/gemma-4-31b-it:free",
        )
    }
}

val AI_KEY_HINT: String get() = tr("задайте свой ключ")

val SETTINGS_TITLE: String get() = tr("Настройки")

/**
 * Заголовки разделов настроек — один дом на все модули (#1249).
 *
 * Дверь и её разделы человек открывает и на телефоне, и в окне компьютера. Слова совпадали
 * случайно: телефон брал их из констант, компьютер набирал теми же буквами у себя. Переименуй
 * раздел в одном месте — второе окно осталось бы со старым именем, и перешедший за компьютер
 * искал бы раздел под другим названием.
 */
val KEY_SECTION_TITLE: String get() = tr("Ключи AI")

val PRIVACY_SECTION_TITLE: String get() = tr("Отправка и приватность")

val MEMORY_TITLE: String get() = tr("Что Point помнит")

/**
 * Политика конфиденциальности — строка раздела «Отправка и приватность» на обоих устройствах
 * (#1487). Play требует, чтобы она открывалась из самого приложения, а не только из карточки
 * магазина; человеку она отвечает на тот же вопрос, с которым он пришёл в этот раздел.
 */
val PRIVACY_POLICY_TITLE: String get() = tr("Политика конфиденциальности")

val PRIVACY_POLICY_WHAT: String get() =
    tr("Что Point хранит, кому отдаёт объект и как всё удалить. Страница на сайте Point.")

interface UserKeyStore {

    fun keys(): UserAiKeys

    suspend fun save(key: UserAiKey)

    suspend fun forget(providerId: String)

    suspend fun clear()
}

val KEY_SETTINGS_CALL: String get() = tr("Задайте ключ в настройках")

fun refusalNeedsKey(reason: String): Boolean =
    KEY_REFUSAL_MARKS.any { reason.contains(it, ignoreCase = true) }

private val KEY_REFUSAL_MARKS = listOf(AI_KEY_HINT, KEY_SETTINGS_CALL)
