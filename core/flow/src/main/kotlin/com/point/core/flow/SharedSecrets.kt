package com.point.core.flow

data class SharedSecrets(
    val aiKey: String = "",
    val speechKey: String = "",
    val ocrKey: String = "",
    val at: Long = 0L,
) {
    val isEmpty: Boolean get() = aiKey.isBlank() && speechKey.isBlank() && ocrKey.isBlank()

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
