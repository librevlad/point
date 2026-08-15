package com.point.core.flow

enum class PrivacyLevel {

    FREE_FIRST,

    NO_TRAINING,

    DEVICE_ONLY,
    ;

    val title: String
        get() = when (this) {
            FREE_FIRST -> "Максимум бесплатного"
            NO_TRAINING -> "Не учатся на моём"
            DEVICE_ONLY -> "Только на этом устройстве"
        }

    val what: String
        get() = when (this) {
            FREE_FIRST ->
                "Читают все бесплатные сервисы, какие есть, — так распознаётся больше всего даром. " +
                    "Часть из них учится на присланном и держит его у себя."
            NO_TRAINING ->
                "Наружу — только к тем, кто письменно обещал не учиться на присланном и не хранить его. " +
                    "Их меньше, и трудную страницу они возьмут не всегда."
            // Правило одно на телефон и компьютер, значит и слова — одни: «с телефона»
            // на экране компьютера читалось как чужая настройка (#893).
            DEVICE_ONLY ->
                "Ничего не уходит с устройства. Остаётся то, что Point читает сам, — фото документов " +
                    "ему даются плохо."
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

/**
 * Сводка приватности одной строкой — на корневом экране настроек (#1003).
 *
 * Тумблер и уровень — два разных параметра, и по отдельности каждый честен. Слитые в одну
 * фразу, они читались противоречием: «Облако разрешено · Только на этом устройстве». Если
 * только на этом устройстве — в каком смысле разрешено?
 *
 * Строка описывает поведение, а не устройство настроек: при `DEVICE_ONLY` наружу не уходит
 * ничего, и это единственное, что человеку важно знать отсюда. Выключенное облако называется
 * своими словами платформы — там противоречия нет.
 */
fun privacySummary(cloud: String, cloudAllowed: Boolean, level: PrivacyLevel): String =
    if (cloudAllowed && level == PrivacyLevel.DEVICE_ONLY) level.title else "$cloud · ${level.title}"

enum class ReaderPromise {

    NO_TRAINING,

    TRAINS,

    UNKNOWN,
    ;

    val what: String
        get() = when (this) {
            NO_TRAINING -> "обещал не учиться на присланном и не хранить его"
            TRAINS -> "учится на присланном и держит его у себя"
            UNKNOWN -> "про обучение на присланном не сказал ничего"
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
    where = "чужой сервер",
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
    where = "сервис, обещавший не учиться на присланном",
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
        ?: ReaderPrivacy(where = id.ifBlank { "сервис" }, promise = ReaderPromise.UNKNOWN)

interface CloudPrivacySettings {
    fun level(): PrivacyLevel
    suspend fun setLevel(level: PrivacyLevel)
}

const val PRIVACY_SETTING_TITLE = "Куда можно отправлять"

const val PRIVACY_SETTING_HINT =
    "Объект уходит только после вашего тапа, и перед отправкой Point говорит, куда именно. " +
        "Здесь вы выбираете, кому его вообще можно предлагать."
