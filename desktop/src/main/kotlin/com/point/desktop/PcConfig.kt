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

    private fun save(config: PcConfig) {
        // Ключ AI сюда не дописывается пустым: строка `ai.key=` в файле выглядит как «ключ есть,
        // но сломан». Нет ключа — нет и строки, а человек видит образец в подсказке экрана.
        file.writeText(
            encodePcMeta(
                buildMap {
                    put("name", config.name)
                    put("server", config.server)
                    if (config.ai.key.isNotBlank()) put("ai.key", config.ai.key)
                },
            ),
        )
    }

    private fun hostName(): String =
        runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Point PC")
}
