package com.point.desktop

import com.point.core.flow.GroqWhisperSpeechToText
import com.point.core.flow.SpeechToText
import com.point.core.flow.TextKeeper
import com.point.core.flow.UrlConnectionHttpFiles
import java.io.File

/**
 * Речь на компьютере (#1379): органы для общего «Расшифровать».
 *
 * Своя способность и свой исполнитель с рукописным клиентом сервиса отсюда ушли — способность
 * и исполнитель теперь общие с телефоном (`TranscribeCapability`, `TranscribeRealizer`), клиент
 * сервиса — общий `GroqWhisperSpeechToText`. Компьютер подставляет в них только своё: ключ и
 * адрес из своего конфига, обычную сеть JVM и место, куда положить слова.
 */
fun pcSpeechEngine(config: () -> SpeechConfig): SpeechToText {
    val first = config()
    return GroqWhisperSpeechToText(
        http = UrlConnectionHttpFiles(),
        apiKey = { config().key },
        // В конфиге компьютера адрес записан до ручки; общий клиент ручку добавляет сам.
        baseUrl = first.url.removeSuffix("/audio/transcriptions").trimEnd('/'),
        model = first.model,
    )
}

/** Слова расшифровки на компьютере ложатся во временный файл — как и до переноса. */
val PcTextInTemp = TextKeeper { _, text ->
    File.createTempFile("pc-voice-", ".txt").apply { writeText(text) }.absolutePath
}

data class SpeechConfig(
    val key: String = "",
    val url: String = DEFAULT_URL,
    val model: String = DEFAULT_MODEL,
) {
    companion object {
        const val DEFAULT_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val DEFAULT_MODEL = "whisper-large-v3"
    }
}
