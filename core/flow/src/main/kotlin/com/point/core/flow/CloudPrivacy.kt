package com.point.core.flow

import com.point.core.model.tr

enum class PrivacyLevel {

    FREE_FIRST,

    NO_TRAINING,

    DEVICE_ONLY,
    ;

    val title: String
        get() = when (this) {
            FREE_FIRST -> tr("Максимум бесплатного")
            NO_TRAINING -> tr("Не учатся на моём")
            DEVICE_ONLY -> tr("Только на этом устройстве")
        }

    val what: String
        get() = when (this) {
            FREE_FIRST ->
                tr("Читают все бесплатные сервисы, какие есть, — так распознаётся больше всего даром. " +
                    "Часть из них учится на присланном и держит его у себя.")
            NO_TRAINING ->
                tr("Наружу — только к тем, кто письменно обещал не учиться на присланном и не хранить его. " +
                    "Их меньше, и трудную страницу они возьмут не всегда.")
            // Правило одно на телефон и компьютер, значит и слова — одни: «с телефона»
            // на экране компьютера читалось как чужая настройка (#893).
            DEVICE_ONLY ->
                tr("Ничего не уходит с устройства. Остаётся то, что Point читает сам, — фото документов " +
                    "ему даются плохо.")
        }

    companion object {

        val DEFAULT = FREE_FIRST

        private const val WAS_EUROPE_ONLY = "EUROPE_ONLY"

        fun of(name: String?): PrivacyLevel = when (name) {
            WAS_EUROPE_ONLY -> NO_TRAINING
            else -> entries.firstOrNull { it.name == name } ?: DEFAULT
        }
    }
}

enum class ReaderPromise {

    NO_TRAINING,

    TRAINS,

    UNKNOWN,
    ;

    val what: String
        get() = when (this) {
            NO_TRAINING -> tr("обещал не учиться на присланном и не хранить его")
            TRAINS -> tr("учится на присланном и держит его у себя")
            UNKNOWN -> tr("про обучение на присланном не сказал ничего")
        }
}

data class ReaderPrivacy(

    val where: String,

    val promise: ReaderPromise = ReaderPromise.UNKNOWN,
)

fun allowedAt(level: PrivacyLevel, privacy: ReaderPrivacy): Boolean = when (level) {
    PrivacyLevel.FREE_FIRST -> true
    PrivacyLevel.NO_TRAINING -> privacy.promise == ReaderPromise.NO_TRAINING
    PrivacyLevel.DEVICE_ONLY -> false
}

fun <T> allowedBy(level: PrivacyLevel, readers: List<T>, privacyOf: (T) -> ReaderPrivacy): List<T> =
    readers.filter { allowedAt(level, privacyOf(it)) }

val AI_CHAIN_PRIVACY = ReaderPrivacy(
    where = tr("чужой сервер"),
    promise = ReaderPromise.UNKNOWN,
)

/**
 * Лучшее, чем внешний сервис вообще бывает: он обещал не учиться на присланном.
 *
 * Мерка для вопроса «пускает ли этот режим наружу хоть кого-нибудь» (#945). Раньше вопрос
 * задавался про [AI_CHAIN_PRIVACY] — цепочку без единого обещания, — и средний режим
 * получался равен «выключить AI целиком», хотя называется иначе.
 */
val PROMISED_SERVICE = ReaderPrivacy(
    where = tr("сервис, обещавший не учиться на присланном"),
    promise = ReaderPromise.NO_TRAINING,
)

/** Пускает ли режим наружу хоть кого-нибудь. */
fun anyoneAllowedAt(level: PrivacyLevel): Boolean = allowedAt(level, PROMISED_SERVICE)

/**
 * Что сервис обещает про присланное (#945).
 *
 * Незнакомый сервис — [ReaderPromise.UNKNOWN]: своё, не объявленное в списке, обещанием не
 * считается.
 */
fun promiseOfService(id: String): ReaderPrivacy =
    AI_PROVIDERS.firstOrNull { it.id == id }?.privacy
        ?: ReaderPrivacy(where = id.ifBlank { tr("сервис") }, promise = ReaderPromise.UNKNOWN)

interface CloudPrivacySettings {
    fun level(): PrivacyLevel
    suspend fun setLevel(level: PrivacyLevel)
}

val PRIVACY_SETTING_TITLE: String get() = tr("Куда можно отправлять")

val PRIVACY_SETTING_HINT: String get() =
    tr("Объект уходит только после вашего тапа, и перед отправкой Point говорит, куда именно. " +
        "Здесь вы выбираете, кому его вообще можно предлагать.")
