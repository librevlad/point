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

/**
 * Ключи в виде полей `имя=значение` — и в посылке между устройствами, и в файле настроек
 * компьютера (#888).
 *
 * Раньше эта раскладка жила только внутри `AccountSettings`, а компьютер хранил один ключ
 * с одним адресом и одной моделью. Из-за этого приехавшая связка из нескольких ключей
 * схлопывалась в самый свежий, и на компьютере оставался один сервис из одиннадцати.
 */
object AiKeyFields {

    const val PREFIX = "ai."
    const val MODEL = ".model"
    const val URL = ".url"
    const val SAVED = ".at"

    /** Одиночный ключ компьютера до #888. */
    const val LEGACY_SINGLE = "ai.key"

    fun of(keys: UserAiKeys): Map<String, String> = buildMap {
        keys.entries.forEach { key ->
            put(PREFIX + key.providerId, key.apiKey)
            put(PREFIX + key.providerId + SAVED, key.savedAt.toString())
            if (key.model.isNotBlank()) put(PREFIX + key.providerId + MODEL, key.model)
            if (key.baseUrl.isNotBlank()) put(PREFIX + key.providerId + URL, key.baseUrl)
        }
    }

    /** `at` — отметка всей посылки: ключ без своей выглядел бы её ровесником. */
    fun from(fields: Map<String, String>, at: Long = 0L): UserAiKeys {
        var keys = UserAiKeys.NONE
        fields.keys
            .filter {
                // `ai.key` — старое одиночное поле компьютера, а не сервис по имени «key»:
                // без этой оговорки при обновлении в списке заводился сервис-призрак (#888).
                it.startsWith(PREFIX) && it != LEGACY_SINGLE &&
                    !it.endsWith(MODEL) && !it.endsWith(URL) && !it.endsWith(SAVED)
            }
            .forEach { field ->
                val apiKey = fields[field].orEmpty()
                if (apiKey.isNotBlank()) {
                    keys = keys.with(
                        UserAiKey(
                            providerId = field.removePrefix(PREFIX),
                            apiKey = apiKey,
                            model = fields[field + MODEL].orEmpty(),
                            baseUrl = fields[field + URL].orEmpty(),
                            savedAt = fields[field + SAVED]?.toLongOrNull() ?: at,
                        ),
                    )
                }
            }
        return keys
    }

    /**
     * Поля тех сервисов, чей ключ убрали: они уходят из файла, а не висят в нём.
     *
     * Считаются только поля, которые эта же раскладка и писала. Чужое рядом не трогается:
     * настройки не должны терять то, о чём они не спрашивали.
     */
    fun stale(stored: Map<String, String>, keys: UserAiKeys): Set<String> {
        val gone = from(stored).entries.map { it.providerId }.filter { keys.of(it) == null }
        return gone.flatMap { id ->
            listOf(PREFIX + id, PREFIX + id + SAVED, PREFIX + id + MODEL, PREFIX + id + URL)
        }.filter { it in stored }.toSet()
    }
}

/**
 * Ключ для расшифровки записи — из той же очереди, а не отдельным полем (#912).
 *
 * На телефоне отдельного «ключа расшифровки» нет вовсе: берётся ключ Groq из очереди.
 * Компьютер спрашивал его вторым полем, и человек, уже вписавший Groq выше, не понимал,
 * что у него просят ещё раз и откуда это взять.
 *
 * Расшифровку умеют Groq и OpenAI: у обоих один и тот же адрес `…/audio/transcriptions`.
 */
fun speechKeyFromChain(keys: UserAiKeys): UserAiKey? =
    SPEECH_PROVIDER_IDS.firstNotNullOfOrNull { id -> keys.of(id) }

val SPEECH_PROVIDER_IDS: List<String> = listOf(GROQ_PROVIDER_ID, "openai")

/** Кто в очереди умеет расшифровывать: этими именами и объясняем, чего не хватает. */
fun speechProviderNames(): String =
    SPEECH_PROVIDER_IDS.mapNotNull { id -> AI_PROVIDERS.firstOrNull { it.id == id }?.name }
        .joinToString(" или ")
