package com.point.desktop

import com.point.core.flow.AUDIBLE_PEAK
import com.point.core.flow.AudioLevel
import com.point.core.flow.measuredPeak
import com.point.core.model.PointObject
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Насколько громкой оказалась запись — слушает сам компьютер (#1312).
 *
 * Правило одно на оба устройства: запись, в которой нечего слушать, в сервис не едет
 * (#1053). Само правило общее и лежит в `:core:flow` — здесь только измеритель за тем же
 * интерфейсом, что и телефонный [com.point.data.DecodedAudioLevel].
 *
 * Java разбирает сама WAV, AU и AIFF. Остальное — mp3, ogg, m4a — ей незнакомо, и ответ
 * тогда «не измерили» (`null`), а не «тихо»: незнакомый формат никогда не отнимает у
 * человека расшифровку (ADR-0001 §9). Своего декодера ради этого не заводится: цифровая
 * тишина, ради которой всё и слушается, приходит записью с диска, а не сжатым потоком.
 *
 * Как только звук найден, слушать дальше незачем — целиком дослушивается только та запись,
 * в которой звука так и не встретилось, а её мы и не отправим.
 */
class JvmAudioLevel : AudioLevel {

    override suspend fun peak(obj: PointObject): Double? = withContext(Dispatchers.IO) {
        val file = File(obj.uri.value).takeIf { it.isFile } ?: return@withContext null
        runCatching { loudest(file) }.getOrNull()
    }

    private fun loudest(file: File): Double? {
        AudioSystem.getAudioInputStream(file).use { source ->
            val pcm = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                source.format.sampleRate.takeIf { it > 0 } ?: return null,
                BITS,
                source.format.channels.takeIf { it > 0 } ?: return null,
                source.format.channels * (BITS / 8),
                source.format.sampleRate.takeIf { it > 0 } ?: return null,
                false,
            )
            if (!AudioSystem.isConversionSupported(pcm, source.format)) return null

            AudioSystem.getAudioInputStream(pcm, source).use { samples ->
                val chunk = ByteArray(CHUNK_BYTES)
                val until = System.currentTimeMillis() + LISTEN_LIMIT_MS
                var peak = 0.0

                // Прошёл ли через уши хоть один сэмпл: без этого пустой поток отдавал бы
                // ноль, и несостоявшееся измерение выдавалось бы за тишину (#1053).
                var heardAny = false
                while (true) {
                    val read = samples.read(chunk)
                    if (read <= 0) return measuredPeak(peak, heardAny)
                    peak = maxOf(peak, peakOf(chunk, read))
                    heardAny = true
                    if (peak >= AUDIBLE_PEAK) return peak
                    if (System.currentTimeMillis() > until) return null
                }
            }
        }
    }

    /** Самый громкий сэмпл куска, 0..1. Кусок — 16-битный PCM little-endian. */
    private fun peakOf(chunk: ByteArray, size: Int): Double {
        var loudest = 0
        var at = 0
        while (at + 1 < size) {
            val sample = (chunk[at].toInt() and 0xFF) or (chunk[at + 1].toInt() shl 8)
            loudest = maxOf(loudest, abs(sample.toShort().toInt()))
            at += 2
        }
        return loudest / FULL_SCALE
    }

    private companion object {

        const val BITS = 16

        /** Столько ждём измерения; дольше — честнее сказать «не измерили», чем держать человека. */
        const val LISTEN_LIMIT_MS = 4_000L

        const val CHUNK_BYTES = 64 * 1024

        const val FULL_SCALE = 32_768.0
    }
}
