package com.point.desktop

import com.point.core.flow.decodePcMeta
import com.point.core.flow.encodePcMeta
import java.io.File
import java.net.InetAddress

/**
 * Что компьютер знает о себе.
 *
 * От прежней записи остались имя и адрес сервера. Токен быстрого пути по локальной сети и порт
 * своего HTTP-сервера ушли вместе с самой локальной сетью (#475): слушать компьютеру больше
 * нечего, а «своё имя в сети» перестало быть его делом.
 *
 * Кто владеет этим компьютером, живёт отдельно — в `~/.point-pc/account` ([FileAccountStore]);
 * ключи — в `~/.point-pc/keys` ([FileDeviceKeys]). Пропуск, ключ и имя машины — разные вещи с
 * разной судьбой.
 *
 * [server] — адрес сервера Point; пусто значит `PointServer.DEFAULT_URL`.
 * [ai] — ключ, адрес и модель для AI-действий (#585). Ключ живёт ТОЛЬКО здесь, на машине
 * человека: в артефакт он не компилируется никогда, и пустой ключ значит «AI-действия молчат».
 */
data class PcConfig(
    val name: String,
    val server: String = "",
    val ai: AiConfig = AiConfig(),
    /** Чем слушать речь (#585) — ключ от ДРУГОГО сервиса: у OpenRouter ручки расшифровки нет. */
    val speech: SpeechConfig = SpeechConfig(),
    /** Чем читать снимки (#585). Ключ необязателен: без него работает демо-уровень сервиса. */
    val ocr: OcrConfig = OcrConfig(),
)

/**
 * Stored in `~/.point-pc/config` using the protocol's own k=v codec — dogfooding
 * [encodePcMeta], zero extra dependencies.
 */
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
            speech = SpeechConfig(
                key = stored["speech.key"].orEmpty(),
                url = stored["speech.url"].orEmpty().ifBlank { SpeechConfig.DEFAULT_URL },
                model = stored["speech.model"].orEmpty().ifBlank { SpeechConfig.DEFAULT_MODEL },
            ),
        )
        if (stored["name"].isNullOrBlank()) save(config)
        return config
    }

    /**
     * Слить приехавшие ключи со своими и записать (#589).
     *
     * Возвращает то, что стало общим, — этот же ответ уезжает обратно на телефон. Правило слияния
     * живёт в [SharedSecrets] и одно на оба устройства: спорить о том, чей ключ настоящий, они
     * будут одинаково.
     */
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

    /** Когда ключи этой машины трогали в последний раз — метка для разрешения спора. */
    private fun secretsStamp(): Long =
        runCatching { decodePcMeta(file.readText())["secrets.at"]?.toLongOrNull() }.getOrNull()
            ?: file.lastModified()

    /**
     * Записать настройки, **не потеряв того, чего не знаешь** (#593).
     *
     * Раньше файл переписывался целиком из трёх полей — имени, адреса сервера и ключа AI. Всё
     * остальное исчезало: ключ расшифровки, ключ чтения, свои адреса и модели, метка ключей. Пока
     * запись случалась один раз при первом запуске, это почти не всплывало; с экраном настроек
     * запись стала обычным делом, и человек, вписавший ключи по образцу из `local.properties.sample`
     * и не указавший имя, потерял бы их на первом же старте.
     *
     * Поэтому не «переписать», а «поправить своё»: читаем, меняем названное, оставляем остальное.
     *
     * Пустой ключ в файл не пишется вовсе: строка `ai.key=` выглядит как «ключ есть, но сломан».
     * Стёртый человеком ключ при этом убирается — иначе стереть его было бы нечем.
     */
    @Synchronized
    fun save(config: PcConfig) {
        val stored = runCatching { decodePcMeta(file.readText()) }.getOrDefault(emptyMap()).toMutableMap()
        stored["name"] = config.name
        stored["server"] = config.server
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
