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

    @Synchronized
    fun save(config: PcConfig) {
        val stored = runCatching { decodePcMeta(file.readText()) }.getOrDefault(emptyMap()).toMutableMap()
        stored["name"] = config.name
        stored["server"] = config.server

        if (config.rightClick) stored.remove("right.click") else stored["right.click"] = "no"
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
