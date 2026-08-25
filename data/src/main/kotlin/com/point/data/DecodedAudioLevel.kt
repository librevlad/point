package com.point.data

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.point.core.flow.AUDIBLE_PEAK
import com.point.core.flow.AudioLevel
import com.point.core.flow.measuredPeak
import com.point.core.model.PointObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Насколько громкой оказалась запись — слушает сам телефон (#1053).
 *
 * Декодер разбирает запись до сэмплов и ищет самый громкий из них. Как только звук найден,
 * слушать дальше незачем — обычная запись с речью меряется за доли секунды; целиком
 * дослушивается только та, в которой звука так и не встретилось, а её мы и не отправим.
 *
 * Не смогли — говорим «не измерили» ([AudioLevel.peak] возвращает `null`), а не «тихо»:
 * незнакомый формат, отказавший декодер и затянувшееся измерение не должны молча отнимать у
 * человека расшифровку. Сюда же относится разбор, не давший ни одного сэмпла: пустая выдача
 * экстрактора и конец потока без единого куска PCM — это несостоявшееся измерение, и оно
 * никогда не выдаётся за тишину (`measuredPeak`, ADR-0001 §9).
 */
class DecodedAudioLevel @Inject constructor() : AudioLevel {

    override suspend fun peak(obj: PointObject): Double? = withContext(Dispatchers.IO) {
        val file = File(obj.uri.value).takeIf { it.isFile } ?: return@withContext null
        runCatching { loudest(file) }.getOrNull()
    }

    private fun loudest(file: File): Double? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            // WAV и прочий PCM уже лежат сэмплами — раскодировать нечего.
            if (mime == RAW) heardRaw(extractor, encodingOf(format)) else decoded(extractor, format, mime)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun heardRaw(extractor: MediaExtractor, encoding: Int): Double? {
        val buffer = ByteBuffer.allocate(CHUNK_BYTES)
        val until = System.currentTimeMillis() + LISTEN_LIMIT_MS
        var peak = 0.0

        // Прошёл ли через уши хоть один сэмпл (#1053): без этого первая же пустая выдача
        // отдавала ноль, и несостоявшееся измерение выдавалось за тишину.
        var heardAny = false
        while (true) {
            buffer.clear()
            val read = extractor.readSampleData(buffer, 0)
            if (read <= 0) return measuredPeak(peak, heardAny)
            peak = maxOf(peak, peakOf(buffer, 0, read, encoding) ?: return null)
            heardAny = true
            if (peak >= AUDIBLE_PEAK) return peak
            if (System.currentTimeMillis() > until) return null
            if (!extractor.advance()) return measuredPeak(peak, heardAny)
        }
    }

    private fun decoded(extractor: MediaExtractor, format: MediaFormat, mime: String): Double? {
        val codec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrNull() ?: return null
        val until = System.currentTimeMillis() + LISTEN_LIMIT_MS
        var encoding = encodingOf(format)
        var peak = 0.0

        // Декодер мог не отдать ни куска PCM — тогда мерить было нечего, и конец потока это
        // не тишина, а «не измерили» (#1053).
        var heardAny = false
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = false
            while (true) {
                if (System.currentTimeMillis() > until) return null
                if (!fed) fed = feed(codec, extractor)

                val index = codec.dequeueOutputBuffer(info, WAIT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    encoding = encodingOf(codec.outputFormat)
                    continue
                }
                if (index < 0) continue

                val heard = codec.getOutputBuffer(index)?.let { peakOf(it, info.offset, info.size, encoding) }
                codec.releaseOutputBuffer(index, false)
                if (heard == null && info.size > 0) return null
                if (info.size > 0) {
                    peak = maxOf(peak, heard ?: 0.0)
                    heardAny = true
                }

                if (peak >= AUDIBLE_PEAK) return peak
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return measuredPeak(peak, heardAny)
            }
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /** Отдать декодеру следующий кусок записи; `true` — запись кончилась, кормить больше нечем. */
    private fun feed(codec: MediaCodec, extractor: MediaExtractor): Boolean {
        val index = codec.dequeueInputBuffer(WAIT_US)
        if (index < 0) return false
        val buffer = codec.getInputBuffer(index) ?: return false
        val read = extractor.readSampleData(buffer, 0)
        if (read < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        codec.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
        extractor.advance()
        return false
    }

    private fun encodingOf(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        else AudioFormat.ENCODING_PCM_16BIT

    /** Самый громкий сэмпл куска, 0..1; `null` — сэмплы записаны незнакомо, судить не берёмся. */
    private fun peakOf(buffer: ByteBuffer, offset: Int, size: Int, encoding: Int): Double? {
        if (size <= 0) return 0.0
        val bytes = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            position(offset)
            limit(offset + size)
        }
        return when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val samples = bytes.asShortBuffer()
                var loudest = 0
                while (samples.hasRemaining()) loudest = maxOf(loudest, abs(samples.get().toInt()))
                loudest / SHORT_FULL_SCALE
            }

            AudioFormat.ENCODING_PCM_FLOAT -> {
                val samples = bytes.asFloatBuffer()
                var loudest = 0.0
                while (samples.hasRemaining()) loudest = maxOf(loudest, abs(samples.get().toDouble()))
                loudest.coerceAtMost(1.0)
            }

            AudioFormat.ENCODING_PCM_8BIT -> {
                var loudest = 0
                while (bytes.hasRemaining()) loudest = maxOf(loudest, abs((bytes.get().toInt() and 0xFF) - 128))
                loudest / BYTE_FULL_SCALE
            }

            else -> null
        }
    }

    private companion object {

        const val RAW = "audio/raw"

        /** Столько ждём измерения; дольше — честнее сказать «не измерили», чем держать человека. */
        const val LISTEN_LIMIT_MS = 4_000L

        const val WAIT_US = 10_000L

        const val CHUNK_BYTES = 64 * 1024

        const val SHORT_FULL_SCALE = 32_768.0

        const val BYTE_FULL_SCALE = 128.0
    }
}
