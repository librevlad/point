package com.point.core.flow

/**
 * Склейка звуковых кусков в одну запись (#442).
 *
 * Системный чтец читает за раз ограниченный кусок текста, поэтому длинная статья приходит
 * несколькими файлами. Человеку нужен один: «отдать файлом» значит один файл, который
 * играет в системном плеере, а не папка кусков.
 *
 * Просто дописать файлы друг к другу нельзя — у каждого свой заголовок, и плеер услышит
 * только первый кусок. Заголовок берётся один, а звук идёт подряд.
 *
 * Где именно начинается звук, спрашивается у самого файла, а не берётся числом 44. У чтеца
 * на телефоне владельца звук и правда начинался с 44-го байта, но это его привычка, а не
 * правило формата: чужой чтец вправе вписать перед звуком свои поля, и тогда склейка по
 * числу пустила бы этот заголовок в запись щелчком.
 *
 * Логика чистая и проверяется без устройства: ошибка здесь звучит как обрыв на середине.
 */
object Wav {

    private const val RIFF = "RIFF"
    private const val WAVE = "WAVE"
    private const val DATA = "data"
    private const val FIRST_CHUNK = 12

    /** Не WAV или обрезанный файл: склеивать нечего, и молча портить запись нельзя. */
    class NotWav(message: String) : IllegalArgumentException(message)

    /**
     * Собрать одну запись из нескольких.
     *
     * Заголовок берётся у первого куска: у всех кусков он один и тот же — их читал один и
     * тот же голос с одними настройками.
     */
    fun join(parts: List<ByteArray>): ByteArray {
        val sound = parts.filter { it.isNotEmpty() }
        require(sound.isNotEmpty()) { "склеивать нечего" }
        if (sound.size == 1) return sound.first()

        val head = soundStartsAt(sound.first())
        val body = sound.flatMap { part -> part.drop(soundStartsAt(part)) }.toByteArray()

        val out = sound.first().copyOf(head) + body
        writeInt(out, 4, out.size - 8)
        writeInt(out, head - 4, body.size)
        return out
    }

    /**
     * С какого байта в куске идёт звук.
     *
     * Чтец мог не досчитать длину звука в заголовке — хвост из тишины остаётся в записи и
     * слышится паузой между кусками. Отрезать его по объявленной длине было бы точнее, но
     * недосчитанная длина обрезала бы и настоящий звук: пауза безобиднее обрыва.
     */
    private fun soundStartsAt(part: ByteArray): Int {
        if (part.size < FIRST_CHUNK + 8) throw NotWav("кусок короче заголовка: ${part.size} байт")
        if (text(part, 0) != RIFF) throw NotWav("кусок начинается не с RIFF")
        if (text(part, 8) != WAVE) throw NotWav("кусок не WAVE")

        var at = FIRST_CHUNK
        while (at + 8 <= part.size) {
            val size = readInt(part, at + 4)
            if (text(part, at) == DATA) return at + 8
            if (size < 0) throw NotWav("длина поля в куске не читается")
            at += 8 + size + (size and 1)
        }
        throw NotWav("в куске нет звука")
    }

    private fun text(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)

    /** Длины в WAV лежат младшим байтом вперёд. */
    private fun readInt(bytes: ByteArray, at: Int): Int =
        (0..3).sumOf { (bytes[at + it].toInt() and 0xFF) shl (8 * it) }

    private fun writeInt(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[at + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[at + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
