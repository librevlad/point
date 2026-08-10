package com.point.core.flow

/**
 * Ключ человека к одному сервису (#699). Раньше ключ был ровно один на всё
 * приложение, и вопрос «какие ключи каких моделей вбиты» не имел ответа даже
 * теоретически. Теперь ключ принадлежит сервису.
 *
 * Пустые [baseUrl] и [model] значат «как у сервиса в списке» — человеку не
 * приходится их набирать.
 */
data class UserAiKey(
    val providerId: String,
    val apiKey: String,
    val model: String = "",
    val baseUrl: String = "",
    val savedAt: Long = 0L,
)

/** Сервис, адрес которого человек вписал сам — в списке известных его нет. */
const val OWN_SERVICE_ID = "own"

const val OWN_SERVICE_NAME = "Свой адрес"

const val OWN_SERVICE_WHAT = "адрес, который вы вписали сами"

/** Ключи человека — по одному на сервис. */
data class UserAiKeys(val entries: List<UserAiKey> = emptyList()) {

    fun of(providerId: String): UserAiKey? =
        entries.firstOrNull { it.providerId == providerId && it.apiKey.isNotBlank() }

    fun keyFor(providerId: String): String = of(providerId)?.apiKey?.trim().orEmpty()

    /** Свои ключи в том порядке, в каком Point обращается к сервисам. */
    val mine: List<UserAiKey>
        get() = entries.filter { it.apiKey.isNotBlank() }.sortedBy { order(it.providerId) }

    fun with(key: UserAiKey): UserAiKeys {
        val clean = key.copy(
            apiKey = key.apiKey.trim(),
            model = key.model.trim(),
            baseUrl = key.baseUrl.trim(),
        )
        val rest = entries.filterNot { it.providerId == clean.providerId }
        return if (clean.apiKey.isEmpty()) UserAiKeys(rest) else UserAiKeys(rest + clean)
    }

    fun without(providerId: String): UserAiKeys =
        UserAiKeys(entries.filterNot { it.providerId == providerId })

    companion object {
        val NONE = UserAiKeys()

        private fun order(providerId: String): Int =
            AI_PROVIDERS.indexOfFirst { it.id == providerId }.takeIf { it >= 0 } ?: AI_PROVIDERS.size
    }
}

/**
 * Обращение к сервису на ключе человека: адрес и модель берутся из списка,
 * пока человек не задал свои.
 */
fun aiCall(key: UserAiKey): UserAiConfig {
    val provider = AI_PROVIDERS.firstOrNull { it.id == key.providerId }
    return UserAiConfig(
        apiKey = key.apiKey.trim(),
        baseUrl = key.baseUrl.trim().ifBlank { provider?.baseUrl.orEmpty() },
        model = key.model.trim().ifBlank { provider?.models?.substringBefore(',').orEmpty() },
        savedAt = key.savedAt,
    )
}

fun UserAiKeys.callFor(providerId: String): UserAiConfig? = of(providerId)?.let(::aiCall)

/**
 * Перенос единственного старого ключа в схему «ключ на сервис»: при обновлении
 * человек не должен потерять то, что уже вписал.
 */
fun keysFromSingleKey(config: UserAiConfig?): UserAiKeys {
    val key = config?.apiKey?.trim().orEmpty()
    if (key.isEmpty()) return UserAiKeys.NONE
    val known = providerForBaseUrl(config!!.baseUrl)
    return UserAiKeys.NONE.with(
        UserAiKey(
            providerId = known?.id ?: OWN_SERVICE_ID,
            apiKey = key,
            model = config.model,
            baseUrl = if (known == null) config.baseUrl else "",
            savedAt = config.savedAt,
        ),
    )
}

/** Ключи, зашитые в сборку: ими Point работает, пока своего ключа у человека нет. */
interface BuiltInAiKeys {

    fun key(providerId: String): String

    fun have(): Set<String>
}

fun encodeUserAiKeys(keys: UserAiKeys): String = keys.entries.joinToString("\n") {
    listOf(it.providerId, it.apiKey, it.model, it.baseUrl, it.savedAt.toString()).joinToString("\t")
}

fun decodeUserAiKeys(text: String?): UserAiKeys {
    val lines = text?.lineSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()
    val entries = lines.mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < FIELDS) return@mapNotNull null
        val id = parts[0].trim()
        val key = parts[1].trim()
        if (id.isEmpty() || key.isEmpty()) return@mapNotNull null
        UserAiKey(
            providerId = id,
            apiKey = key,
            model = parts[2].trim(),
            baseUrl = parts[3].trim(),
            savedAt = parts[4].trim().toLongOrNull() ?: 0L,
        )
    }
    return UserAiKeys(entries)
}

private const val FIELDS = 5
