package com.point.desktop

import com.point.core.flow.decodePcMeta
import com.point.core.flow.encodePcMeta
import java.io.File
import java.net.InetAddress

data class PcConfig(
    val name: String,
    val server: String = "",
    val ai: AiConfig = AiConfig(),

    val speech: SpeechConfig = SpeechConfig(),

    val ocr: OcrConfig = OcrConfig(),

    val rightClick: Boolean = true,

    /** Звук прибытия объекта с телефона (#650). Пара к свипу ухода на той стороне. */
    val sound: Boolean = true,
)

class FilePcConfig(private val baseDir: File) {

    private val file: File get() = File(baseDir.apply { mkdirs() }, "config")

    fun load(): PcConfig {
        val stored = runCatching { decodePcMeta(file.readText()) }.getOrDefault(emptyMap())
        val config = PcConfig(
            name = stored["name"] ?: hostName(),
            server = stored["server"].orEmpty(),
            ai = AiConfig(
                key = stored["ai.key"].orEmpty(),
                url = stored["ai.url"].orEmpty().ifBlank { AiConfig.DEFAULT_URL },
                model = stored["ai.model"].orEmpty().ifBlank { AiConfig.DEFAULT_MODEL },
            ),
            ocr = OcrConfig(
                key = stored["ocr.key"].orEmpty(),
                url = stored["ocr.url"].orEmpty().ifBlank { OcrConfig.DEFAULT_URL },
            ),
            rightClick = stored["right.click"] != "no",
            sound = stored["sound"] != "no",
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
            aiKey = config.ai.key,
            speechKey = config.speech.key,
            ocrKey = config.ocr.key,
            at = secretsStamp(),
        )
        val merged = mine.mergedWith(theirs)
        if (merged != mine) {
            val stored = runCatching { decodePcMeta(file.readText()) }.getOrDefault(emptyMap()).toMutableMap()
            if (merged.aiKey.isNotBlank()) stored["ai.key"] = merged.aiKey
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
        val provider = com.point.core.flow.providerForBaseUrl(config.ai.url)
        val keys = if (config.ai.key.isBlank()) {
            com.point.core.flow.UserAiKeys.NONE
        } else {
            com.point.core.flow.UserAiKeys.NONE.with(
                com.point.core.flow.UserAiKey(
                    providerId = provider?.id ?: com.point.core.flow.OWN_SERVICE_ID,
                    apiKey = config.ai.key,
                    model = config.ai.model,
                    baseUrl = if (provider == null) config.ai.url else "",
                    savedAt = at,
                ),
            )
        }
        return com.point.core.flow.AccountSettings(
            aiKeys = keys,
            speechKey = config.speech.key,
            ocrKey = config.ocr.key,
            sound = config.sound,
            at = at,
        )
    }

    /** Приехавшее ложится сюда, и только то, что отличается. */
    @Synchronized
    fun applyAccountSettings(merged: com.point.core.flow.AccountSettings) {
        val config = load()
        val best = merged.aiKeys.mine.firstOrNull()
        val provider = best?.let { key ->
            com.point.core.flow.AI_PROVIDERS.firstOrNull { it.id == key.providerId }
        }
        val next = config.copy(
            ai = config.ai.copy(
                key = best?.apiKey ?: config.ai.key,
                url = best?.baseUrl?.takeIf { it.isNotBlank() } ?: provider?.baseUrl ?: config.ai.url,
                model = best?.model?.takeIf { it.isNotBlank() }
                    ?: provider?.models?.substringBefore(',') ?: config.ai.model,
            ),
            speech = config.speech.copy(key = merged.speechKey.ifBlank { config.speech.key }),
            ocr = config.ocr.copy(key = merged.ocrKey.ifBlank { config.ocr.key }),
            sound = merged.sound ?: config.sound,
        )
        if (next != config) save(next)
        stamp(merged.at)
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
        stored["ai.url"] = config.ai.url
        stored["ai.model"] = config.ai.model

        if (config.rightClick) stored.remove("right.click") else stored["right.click"] = "no"
        if (config.sound) stored.remove("sound") else stored["sound"] = "no"
        listOf(
            "ai.key" to config.ai.key,
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
