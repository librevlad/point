package com.point.core.flow

/**
 * Ключи сервисов, общие для устройств одного человека (#589).
 *
 * Ключ AI человек заводит один раз — а устройств у него два, и вводить его дважды незачем. Между
 * своими устройствами такой ключ едет по тому же запечатанному каналу, что и объекты: сервер
 * видит шифротекст и содержимого не знает.
 *
 * Здесь только правила — что за ключи и как их сливать. Отправка живёт в транспорте, хранение —
 * на каждом устройстве своё (шифрованные prefs на телефоне, файл конфига на ПК).
 *
 * [at] — когда эти ключи в последний раз менял человек. Нужен ровно для одного: когда ключ есть на
 * обоих устройствах и они разные, выигрывает тот, который человек вписал позже. Без метки победил
 * бы тот, кто первым дозвонился, — то есть случайность.
 */
data class SharedSecrets(
    val aiKey: String = "",
    val speechKey: String = "",
    val ocrKey: String = "",
    val at: Long = 0L,
) {
    val isEmpty: Boolean get() = aiKey.isBlank() && speechKey.isBlank() && ocrKey.isBlank()

    /**
     * Слить со ключами другого устройства.
     *
     * Правило по каждому ключу отдельно: **непустое побеждает пустое**, а если непустые оба и они
     * разные — побеждает более свежий. Раздельно, потому что ключи разных сервисов человек заводит
     * в разное время: свежий ключ речи не должен затирать давний ключ AI, приехав с ним в одном
     * письме.
     */
    fun mergedWith(other: SharedSecrets): SharedSecrets {
        val newer = other.at > at
        return SharedSecrets(
            aiKey = pick(aiKey, other.aiKey, newer),
            speechKey = pick(speechKey, other.speechKey, newer),
            ocrKey = pick(ocrKey, other.ocrKey, newer),
            at = maxOf(at, other.at),
        )
    }

    private fun pick(mine: String, theirs: String, theirsIsNewer: Boolean): String = when {
        theirs.isBlank() -> mine
        mine.isBlank() -> theirs
        mine == theirs -> mine
        theirsIsNewer -> theirs
        else -> mine
    }

    /** В строку для письма. Тот же кодек, что у остального протокола, — своего формата не заводим. */
    fun encode(): String = encodePcMeta(
        buildMap {
            if (aiKey.isNotBlank()) put(AI, aiKey)
            if (speechKey.isNotBlank()) put(SPEECH, speechKey)
            if (ocrKey.isNotBlank()) put(OCR, ocrKey)
            put(AT, at.toString())
        },
    )

    companion object {
        const val AI = "ai.key"
        const val SPEECH = "speech.key"
        const val OCR = "ocr.key"
        private const val AT = "at"

        fun decode(encoded: String): SharedSecrets {
            val map = runCatching { decodePcMeta(encoded) }.getOrDefault(emptyMap())
            return SharedSecrets(
                aiKey = map[AI].orEmpty(),
                speechKey = map[SPEECH].orEmpty(),
                ocrKey = map[OCR].orEmpty(),
                at = map[AT]?.toLongOrNull() ?: 0L,
            )
        }
    }
}
