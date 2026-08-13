package com.point.data

import android.content.Context
import android.speech.tts.TextToSpeech as SystemVoice
import android.speech.tts.UtteranceProgressListener
import com.point.core.flow.NO_VOICE_TEXT
import com.point.core.flow.Spoken
import com.point.core.flow.TextToSpeech
import com.point.core.flow.Wav
import com.point.core.flow.noOfflineVoice
import com.point.core.flow.noVoiceForLanguage
import com.point.core.flow.speechParts
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Голос самого устройства (#442).
 *
 * Читает системный синтезатор: он уже стоит в телефоне, ничего не стоит и не выходит в сеть.
 * Голоса ставит человек в настройках системы — Point их не приносит и не может: это часть
 * устройства, а не наша.
 *
 * Читает синтезатор кусками: у него есть предел на один заход, и длинная статья в него не
 * влезает. Куски склеиваются в одну запись — человеку нужен один файл, а не папка.
 */
class AndroidTextToSpeech @Inject constructor(
    @ApplicationContext private val context: Context,
) : TextToSpeech {

    override suspend fun voices(): List<String> = withVoice { voice ->
        runCatching {
            voice.availableLanguages.orEmpty().map { it.language }.distinct()
        }.getOrDefault(emptyList())
    } ?: emptyList()

    override suspend fun speak(
        text: String,
        language: String?,
        into: String,
        onDeviceOnly: Boolean,
        onPart: suspend (Int, Int) -> Unit,
    ): Spoken {
        val said = withVoice { voice ->
            val locale = language?.let(Locale::forLanguageTag) ?: Locale.getDefault()
            val set = voice.setLanguage(locale)
            if (set == SystemVoice.LANG_MISSING_DATA || set == SystemVoice.LANG_NOT_SUPPORTED) {
                return@withVoice Spoken.Refused(noVoiceForLanguage(locale.displayLanguage))
            }

            // Режим закрыт — читает только голос, живущий на устройстве (#924). Голос по
            // умолчанию система нередко выбирает серверный, и тогда текст человека уезжает
            // в чужой сервис без спроса.
            if (onDeviceOnly && !takeOfflineVoice(voice, locale)) {
                return@withVoice Spoken.Refused(noOfflineVoice(locale.displayLanguage))
            }

            val limit = runCatching { SystemVoice.getMaxSpeechInputLength() }.getOrDefault(3_900)
            val parts = speechParts(text, limit)
            if (parts.isEmpty()) return@withVoice Spoken.Refused("Читать нечего")

            val pieces = File(into).parentFile ?: File(".")
            val sound = mutableListOf<ByteArray>()
            parts.forEachIndexed { index, part ->
                val piece = File(pieces, "speak-$index.wav")
                if (!writeOne(voice, part, piece, index)) {
                    pieces.listFiles { f -> f.name.startsWith("speak-") }?.forEach { it.delete() }
                    return@withVoice Spoken.Refused(BROKE)
                }
                sound += piece.readBytes()
                piece.delete()
                onPart(index + 1, parts.size)
            }

            File(into).writeBytes(Wav.join(sound))
            Spoken.Done(into)
        }
        return said ?: Spoken.Refused(NO_VOICE_TEXT)
    }

    /**
     * Взять голос, который читает на самом устройстве (#924).
     *
     * `isNetworkConnectionRequired` голос объявляет сам; не установленный до конца
     * (`FEATURE_NOT_INSTALLED`) тоже уйдёт в сеть за данными. `false` — такого голоса на
     * этом языке нет.
     */
    private fun takeOfflineVoice(voice: SystemVoice, locale: Locale): Boolean {
        val offline = runCatching {
            voice.voices.orEmpty()
                .filter { it.locale.language == locale.language }
                .filterNot { it.isNetworkConnectionRequired }
                .filterNot { SystemVoice.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
                .minByOrNull { it.latency }
        }.getOrNull() ?: return false
        return runCatching { voice.setVoice(offline) == SystemVoice.SUCCESS }.getOrDefault(false)
    }

    /**
     * Один кусок текста в один файл. `false` — синтезатор не справился.
     *
     * Ответа ждём не вечно: движок может не ответить вовсе, и тогда человек смотрит на
     * крутящийся круг без конца. Названный отказ лучше бесконечного ожидания.
     */
    private suspend fun writeOne(voice: SystemVoice, text: String, into: File, index: Int): Boolean =
        withTimeoutOrNull(PATIENCE_MS) { awaitOne(voice, text, into, index) } ?: false

    private suspend fun awaitOne(voice: SystemVoice, text: String, into: File, index: Int): Boolean =
        suspendCoroutine { go ->
            val mark = "point-$index"
            voice.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) {
                    if (id == mark) go.resume(into.length() > 0)
                }

                @Deprecated("Заменён вариантом с кодом ошибки", ReplaceWith("onError(id, -1)"))
                override fun onError(id: String?) {
                    if (id == mark) go.resume(false)
                }

                override fun onError(id: String?, code: Int) {
                    if (id == mark) go.resume(false)
                }
            })
            val started = voice.synthesizeToFile(text, null, into, mark)
            if (started != SystemVoice.SUCCESS) go.resume(false)
        }

    /**
     * Поднять синтезатор, сделать дело, погасить.
     *
     * Держать его включённым всё время работы Point незачем: он занимает звук и живёт
     * своей жизнью, а читаем мы редко. `null` — синтезатора на устройстве нет вовсе.
     */
    private suspend fun <T> withVoice(work: suspend (SystemVoice) -> T): T? {
        val voice = suspendCancellableCoroutine<SystemVoice?> { go ->
            var engine: SystemVoice? = null
            engine = SystemVoice(context) { status ->
                if (go.isActive) go.resume(if (status == SystemVoice.SUCCESS) engine else null) { _, _, _ -> }
            }
        } ?: return null

        return try {
            work(voice)
        } finally {
            runCatching { voice.stop() }
            runCatching { voice.shutdown() }
        }
    }

    private companion object {
        const val BROKE = "Голос устройства не ответил — прочитать целиком не вышло"

        /** Сколько ждём один кусок. Синтез идёт быстрее речи, минуты хватает с запасом. */
        const val PATIENCE_MS = 60_000L
    }
}
