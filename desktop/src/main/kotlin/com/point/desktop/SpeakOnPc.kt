package com.point.desktop

import com.point.core.flow.Spoken
import com.point.core.flow.TextToSpeech
import com.point.core.flow.noVoiceForLanguage
import com.point.core.model.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Голос компьютера — для «Озвучить» (#442, #1379).
 *
 * У Windows свои голоса (System.Speech, SAPI): они стоят в системе, ничего не стоят и читают
 * на самом компьютере, поэтому закрытый режим приватности (#924) им не помеха. Ставит их
 * человек в настройках Windows, в разделе речи, — Point их не приносит.
 *
 * Говорит Point с голосом через PowerShell: отдельной библиотеки под это тащить незачем, а
 * PowerShell есть в каждой Windows. Текст и пути едут переменными окружения, а сценарий —
 * закодированной командой: так ни кавычки, ни буквы человека его не ломают.
 *
 * На других системах голосов этот исполнитель не видит, и действие честно говорит, что
 * читать нечем.
 */
class PcTextToSpeech(
    private val windows: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win"),
    private val run: (script: String, env: Map<String, String>) -> Pair<Int, String> = ::runPowerShell,
) : TextToSpeech {

    override suspend fun voices(): List<String> = installed().map { it.language }.distinct()

    override suspend fun speak(
        text: String,
        language: String?,
        into: String,
        onDeviceOnly: Boolean,
        onPart: suspend (Int, Int) -> Unit,
    ): Spoken = withContext(Dispatchers.IO) {
        val voices = installed()
        val voice = if (language == null) {
            voices.firstOrNull()
        } else {
            voices.firstOrNull { it.language == language }
                ?: return@withContext Spoken.Refused(noVoiceForLanguage(Locale.forLanguageTag(language).displayLanguage))
        }

        val words = File.createTempFile("point-speak-", ".txt").apply { writeText(text, Charsets.UTF_8) }
        try {
            val target = File(into)
            target.delete()
            val env = buildMap {
                put("POINT_TTS_TEXT", words.absolutePath)
                put("POINT_TTS_OUT", target.absolutePath)
                voice?.let { put("POINT_TTS_VOICE", it.name) }
            }
            val (code, _) = run(SPEAK_SCRIPT, env)
            if (code != 0 || !target.isFile || target.length() <= WAV_HEADER) {
                target.delete()
                return@withContext Spoken.Refused(BROKE)
            }
            onPart(1, 1)
            Spoken.Done(target.absolutePath)
        } finally {
            words.delete()
        }
    }

    /** Голоса, включённые в Windows: имя и двухбуквенный язык. */
    private suspend fun installed(): List<Voice> = withContext(Dispatchers.IO) {
        if (!windows) return@withContext emptyList()
        val (code, out) = run(VOICES_SCRIPT, emptyMap())
        if (code != 0) return@withContext emptyList()
        out.lines().mapNotNull(::voiceOf)
    }

    private class Voice(val name: String, val language: String)

    private fun voiceOf(line: String): Voice? {
        val name = line.substringBefore('\t').trim()
        val language = line.substringAfter('\t', "").trim().lowercase()
        return if (name.isEmpty() || language.isEmpty()) null else Voice(name, language)
    }

    private companion object {
        val BROKE: String get() = tr("Голос компьютера не дочитал текст — попробуйте ещё раз")

        /** Пустой WAV — один заголовок: голос молча ничего не записал. */
        const val WAV_HEADER = 44L

        val VOICES_SCRIPT = """
            [Console]::OutputEncoding = [Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Speech
            ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            ${'$'}s.GetInstalledVoices() | Where-Object { ${'$'}_.Enabled } | ForEach-Object {
              ${'$'}_.VoiceInfo.Name + [char]9 + ${'$'}_.VoiceInfo.Culture.TwoLetterISOLanguageName
            }
            ${'$'}s.Dispose()
        """.trimIndent()

        val SPEAK_SCRIPT = """
            Add-Type -AssemblyName System.Speech
            ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            if (${'$'}env:POINT_TTS_VOICE) { ${'$'}s.SelectVoice(${'$'}env:POINT_TTS_VOICE) }
            ${'$'}s.SetOutputToWaveFile(${'$'}env:POINT_TTS_OUT)
            ${'$'}s.Speak([IO.File]::ReadAllText(${'$'}env:POINT_TTS_TEXT, [Text.Encoding]::UTF8))
            ${'$'}s.Dispose()
        """.trimIndent()
    }
}

/**
 * Сценарий уходит закодированной командой (UTF-16LE в base64): так PowerShell получает его
 * дословно, без разбора кавычек командной строкой Windows.
 */
private fun runPowerShell(script: String, env: Map<String, String>): Pair<Int, String> = runCatching {
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
        .redirectErrorStream(true)
        .apply { environment().putAll(env) }
        .start()
    val out = StringBuilder()
    val reader = Thread { out.append(process.inputStream.bufferedReader(Charsets.UTF_8).readText()) }
        .apply { isDaemon = true; start() }
    // Статья читается в файл быстрее, чем вслух, но не мгновенно; зависший голос не держит
    // действие вечно.
    if (!process.waitFor(SPEAK_LIMIT_MINUTES, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        return@runCatching -1 to ""
    }
    reader.join(1_000)
    process.exitValue() to out.toString()
}.getOrDefault(-1 to "")

private const val SPEAK_LIMIT_MINUTES = 15L
