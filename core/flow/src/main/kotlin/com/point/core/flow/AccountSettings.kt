package com.point.core.flow

/**
 * Настройки, которые едут за человеком (#610, решение владельца 10.08.2026).
 *
 * Едут предпочтения, а не знание: Graph между устройствами не синхронизируется, и эта
 * карточка его не тянет. Здесь то, что человек однажды выбрал и не должен выбирать
 * заново на каждом своём устройстве — ключи сервисов, куда можно отправлять и звук.
 *
 * Имя устройства и правый клик сюда не входят: у них отличается сам мир, а не
 * предпочтение человека.
 */
data class AccountSettings(

    /** Ключ на каждый сервис — та же схема, что у телефона (#699). */
    val aiKeys: UserAiKeys = UserAiKeys.NONE,

    val speechKey: String = "",

    val ocrKey: String = "",

    val privacy: PrivacyLevel? = null,

    val sound: Boolean? = null,

    val at: Long = 0L,
) {
    val isEmpty: Boolean
        get() = aiKeys.entries.isEmpty() && speechKey.isBlank() && ocrKey.isBlank() &&
            privacy == null && sound == null

    /**
     * Слияние: побеждает то, что новее, но пустое чужое не стирает своё. Правило то же,
     * что у обмена секретами между устройствами, — расходиться им нельзя.
     */
    fun mergedWith(other: AccountSettings): AccountSettings {
        val newer = other.at > at
        return AccountSettings(
            aiKeys = mergedKeys(other.aiKeys, newer),
            speechKey = pick(speechKey, other.speechKey, newer),
            ocrKey = pick(ocrKey, other.ocrKey, newer),
            privacy = if (other.privacy != null && (privacy == null || newer)) other.privacy else privacy,
            sound = if (other.sound != null && (sound == null || newer)) other.sound else sound,
            at = maxOf(at, other.at),
        )
    }

    private fun mergedKeys(theirs: UserAiKeys, theirsIsNewer: Boolean): UserAiKeys {
        var merged = aiKeys
        theirs.entries.forEach { key ->
            val mine = merged.of(key.providerId)
            if (mine == null || theirsIsNewer) merged = merged.with(key)
        }
        return merged
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
            put(AT, at.toString())
            // Раскладка ключей общая с файлом настроек компьютера (#888).
            putAll(AiKeyFields.of(aiKeys))
            if (speechKey.isNotBlank()) put(SPEECH, speechKey)
            if (ocrKey.isNotBlank()) put(OCR, ocrKey)
            privacy?.let { put(PRIVACY, it.name) }
            sound?.let { put(SOUND, if (it) "yes" else "no") }
        },
    )

    companion object {

        private const val AT = "at"
        private const val SPEECH = "speech.key"
        private const val OCR = "ocr.key"
        private const val PRIVACY = "privacy"
        private const val SOUND = "sound"

        fun decode(encoded: String): AccountSettings {
            val fields = decodePcMeta(encoded)
            val at = fields[AT]?.toLongOrNull() ?: 0L
            return AccountSettings(
                aiKeys = AiKeyFields.from(fields, at),
                speechKey = fields[SPEECH].orEmpty(),
                ocrKey = fields[OCR].orEmpty(),
                privacy = fields[PRIVACY]?.let { name -> PrivacyLevel.entries.firstOrNull { it.name == name } },
                sound = fields[SOUND]?.let { it == "yes" },
                at = at,
            )
        }
    }
}
