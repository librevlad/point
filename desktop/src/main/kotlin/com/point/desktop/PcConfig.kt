package com.point.desktop

import com.point.core.flow.decodePcMeta
import com.point.core.flow.encodePcMeta
import java.io.File
import java.net.InetAddress

data class PcConfig(
    val name: String,
    val server: String = "",

    /**
     * Ключ на каждый сервис — та же схема, что на телефоне (#888). Раньше здесь лежал один
     * `AiConfig`: приехавшая связка схлопывалась в самый свежий ключ, и на компьютере
     * оставался один сервис из одиннадцати.
     */
    val aiKeys: com.point.core.flow.UserAiKeys = com.point.core.flow.UserAiKeys.NONE,

    val speech: SpeechConfig = SpeechConfig(),

    val ocr: OcrConfig = OcrConfig(),

    val rightClick: Boolean = true,

    /** Звук прибытия объекта с телефона (#650). Пара к свипу ухода на той стороне. */
    val sound: Boolean = true,

    /**
     * Куда человеку можно отправлять объект (#893). Выбор ехал между устройствами и раньше,
     * но компьютер его не хранил и не спрашивал: выбранное на телефоне «только на этом
     * устройстве» здесь ничего не значило, и запись всё равно уходила на чужой сервер.
     */
    val privacy: com.point.core.flow.PrivacyLevel = com.point.core.flow.PrivacyLevel.DEFAULT,
)

class FilePcConfig(private val baseDir: File) {

    private val file: File get() = File(baseDir.apply { mkdirs() }, "config")

    fun load(): PcConfig {
        val stored = runCatching { decodePcMeta(file.readText()) }.getOrDefault(emptyMap())
        val config = PcConfig(
            name = stored["name"] ?: hostName(),
            server = stored["server"].orEmpty(),
            // Единственный старый ключ не теряется при обновлении: он встаёт своему сервису.
            aiKeys = com.point.core.flow.AiKeyFields.from(stored, stamp()).let { keys ->
                if (keys.entries.isNotEmpty()) keys else oldSingleKey(stored)
            },
            ocr = OcrConfig(
                key = stored["ocr.key"].orEmpty(),
                url = stored["ocr.url"].orEmpty().ifBlank { OcrConfig.DEFAULT_URL },
            ),
            rightClick = stored["right.click"] != "no",
            sound = stored["sound"] != "no",
            privacy = com.point.core.flow.PrivacyLevel.of(stored["privacy"]),
            speech = SpeechConfig(
                key = stored["speech.key"].orEmpty(),
                url = stored["speech.url"].orEmpty().ifBlank { SpeechConfig.DEFAULT_URL },
                model = stored["speech.model"].orEmpty().ifBlank { SpeechConfig.DEFAULT_MODEL },
            ),
        )
        if (stored["name"].isNullOrBlank()) save(config)
        return config
    }

    @Synchronized
    fun mergeSecrets(theirs: com.point.core.flow.SharedSecrets): com.point.core.flow.SharedSecrets {
        val config = load()
        val mine = com.point.core.flow.SharedSecrets(
            // Старый путь возит один ключ — самый свежий. Всё остальное едет `AccountSettings`.
            aiKey = config.aiKeys.mine.maxByOrNull { it.savedAt }?.apiKey.orEmpty(),
            speechKey = config.speech.key,
            ocrKey = config.ocr.key,
            at = secretsStamp(),
        )
        val merged = mine.mergedWith(theirs)
        if (merged != mine) {
            val stored = runCatching { decodePcMeta(file.readText()) }.getOrDefault(emptyMap()).toMutableMap()
            if (merged.aiKey.isNotBlank() && config.aiKeys.mine.none { it.apiKey == merged.aiKey }) {
                val provider = com.point.core.flow.AI_PROVIDERS.first()
                stored.putAll(
                    com.point.core.flow.AiKeyFields.of(
                        config.aiKeys.with(
                            com.point.core.flow.UserAiKey(
                                providerId = provider.id,
                                apiKey = merged.aiKey,
                                savedAt = merged.at,
                            ),
                        ),
                    ),
                )
            }
            if (merged.speechKey.isNotBlank()) stored["speech.key"] = merged.speechKey
            if (merged.ocrKey.isNotBlank()) stored["ocr.key"] = merged.ocrKey
            stored["secrets.at"] = merged.at.toString()
            file.writeText(encodePcMeta(stored))
        }
        return merged
    }

    private fun secretsStamp(): Long =
        runCatching { decodePcMeta(file.readText())["secrets.at"]?.toLongOrNull() }.getOrNull()
            ?: file.lastModified()

    /**
     * Настройки компьютера в общем виде — том, в каком они едут за человеком (#610).
     *
     * Своё у компьютера сюда не идёт: имя устройства и правый клик остаются здесь, потому
     * что у них отличается сам мир, а не предпочтение человека.
     */
    @Synchronized
    fun accountSettings(): com.point.core.flow.AccountSettings {
        val config = load()
        val at = stamp()
        return com.point.core.flow.AccountSettings(
            // Едут все ключи, а не самый свежий из них (#888).
            aiKeys = config.aiKeys,
            speechKey = config.speech.key,
            ocrKey = config.ocr.key,
            privacy = config.privacy,
            sound = config.sound,
            at = at,
        )
    }

    /** Приехавшее ложится сюда, и только то, что отличается. */
    @Synchronized
    fun applyAccountSettings(merged: com.point.core.flow.AccountSettings) {
        val config = load()

        // Приехавшая связка ложится целиком: каждый ключ своему сервису. Раньше здесь
        // брался только первый — на компьютере из одиннадцати оставался один (#888).
        val next = config.copy(
            aiKeys = merged.aiKeys.mine.fold(config.aiKeys) { keys, key -> keys.with(key) },
            speech = config.speech.copy(key = merged.speechKey.ifBlank { config.speech.key }),
            ocr = config.ocr.copy(key = merged.ocrKey.ifBlank { config.ocr.key }),
            sound = merged.sound ?: config.sound,
            privacy = merged.privacy ?: config.privacy,
        )
        if (next != config) save(next)
        stamp(merged.at)
    }

    /**
     * Ключ, вписанный до #888, — один на всех, с адресом сервиса. Он встаёт тому сервису,
     * чей это адрес, а свой адрес человека остаётся своим сервисом: терять вписанное при
     * обновлении нельзя.
     */
    private fun oldSingleKey(stored: Map<String, String>): com.point.core.flow.UserAiKeys {
        val key = stored["ai.key"].orEmpty()
        if (key.isBlank()) return com.point.core.flow.UserAiKeys.NONE
        return com.point.core.flow.keysFromSingleKey(
            com.point.core.flow.UserAiConfig(
                apiKey = key,
                baseUrl = stored["ai.url"].orEmpty(),
                model = stored["ai.model"].orEmpty(),
                savedAt = stamp(),
            ),
        )
    }

    private fun stamp(): Long =
        runCatching { decodePcMeta(file.readText())["settings.at"]?.toLongOrNull() }.getOrNull() ?: 0L

    private fun stamp(at: Long) {
        val stored = runCatching { decodePcMeta(file.readText()) }.getOrDefault(emptyMap()).toMutableMap()
        stored["settings.at"] = at.toString()
        file.writeText(encodePcMeta(stored))
    }

    @Synchronized
    fun save(config: PcConfig) {
        val stored = runCatching { decodePcMeta(file.readText()) }.getOrDefault(emptyMap()).toMutableMap()
        stored["name"] = config.name
        stored["server"] = config.server

        // Ключи лежат по сервисам, той же раскладкой, что едет между устройствами (#888).
        // Убранный ключ уходит из файла, а не остаётся висеть полем.
        com.point.core.flow.AiKeyFields.stale(stored, config.aiKeys).forEach(stored::remove)
        stored.remove(com.point.core.flow.AiKeyFields.LEGACY_SINGLE)
        stored.putAll(com.point.core.flow.AiKeyFields.of(config.aiKeys))

        if (config.rightClick) stored.remove("right.click") else stored["right.click"] = "no"
        if (config.sound) stored.remove("sound") else stored["sound"] = "no"
        stored["privacy"] = config.privacy.name
        listOf(
            "speech.key" to config.speech.key,
            "ocr.key" to config.ocr.key,
        ).forEach { (key, value) ->
            if (value.isBlank()) stored.remove(key) else stored[key] = value
        }
        file.writeText(encodePcMeta(stored))
    }

    private fun hostName(): String =
        runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Point PC")
}

/**
 * Ключ AI на компьютере: сам ключ, адрес и модель (#610).
 *
 * Жил рядом с самодельным клиентом `DesktopLlmClient`; клиента не стало (#828 — он не
 * использовался ни одной строкой), а настройка осталась и переехала туда, где ей место.
 */
data class AiConfig(
    val key: String = "",
    val url: String = DEFAULT_URL,
    val model: String = DEFAULT_MODEL,
) {
    companion object {

        const val DEFAULT_URL = "https://openrouter.ai/api/v1/chat/completions"

        const val DEFAULT_MODEL = "meta-llama/llama-3.3-70b-instruct:free"
    }
}

/**
 * Чем расшифровывать запись на компьютере (#912).
 *
 * Ключ берётся из общей очереди — Groq или OpenAI, — как и на телефоне. Отдельное поле
 * «Ключ расшифровки речи» осталось только затем, чтобы не потерять то, что человек уже
 * вписал в него раньше: оно живёт в файле и в настройках больше не спрашивается.
 */
fun speechCall(config: PcConfig): SpeechConfig {
    val fromChain = com.point.core.flow.speechKeyFromChain(config.aiKeys) ?: return config.speech
    val provider = com.point.core.flow.AI_PROVIDERS.firstOrNull { it.id == fromChain.providerId }
    return config.speech.copy(
        key = fromChain.apiKey,
        url = (provider?.baseUrl?.trimEnd('/') ?: return config.speech) + "/audio/transcriptions",
    )
}
