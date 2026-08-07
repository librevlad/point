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
            DEVICE_ONLY -> "Только на телефоне"
        }

    val what: String
        get() = when (this) {
            FREE_FIRST ->
                "Читают все бесплатные сервисы, какие есть, — так распознаётся больше всего даром. " +
                    "Часть из них учится на присланном и держит его у себя."
            NO_TRAINING ->
                "Наружу — только к тем, кто письменно обещал не учиться на присланном и не хранить его. " +
                    "Их меньше, и трудную страницу они возьмут не всегда."
            DEVICE_ONLY ->
                "Ничего не уходит с телефона. Остаётся то, что он читает сам, — фото документов ему даются плохо."
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
    where = "сервер AI-провайдера",
    promise = ReaderPromise.UNKNOWN,
)

interface CloudPrivacySettings {
    fun level(): PrivacyLevel
    suspend fun setLevel(level: PrivacyLevel)
}

const val PRIVACY_SETTING_TITLE = "Куда можно отправлять"

const val PRIVACY_SETTING_HINT =
    "Объект уходит только после вашего тапа, и перед отправкой Point говорит, куда именно. " +
        "Здесь вы выбираете, кому его вообще можно предлагать."
