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
            aiKeys.entries.forEach { key ->
                put(AI + key.providerId, key.apiKey)
                put(AI + key.providerId + SAVED, key.savedAt.toString())
                if (key.model.isNotBlank()) put(AI + key.providerId + MODEL, key.model)
                if (key.baseUrl.isNotBlank()) put(AI + key.providerId + URL, key.baseUrl)
            }
            if (speechKey.isNotBlank()) put(SPEECH, speechKey)
            if (ocrKey.isNotBlank()) put(OCR, ocrKey)
            privacy?.let { put(PRIVACY, it.name) }
            sound?.let { put(SOUND, if (it) "yes" else "no") }
        },
    )

    companion object {

        private const val AT = "at"
        private const val AI = "ai."
        private const val MODEL = ".model"
        private const val URL = ".url"
        private const val SAVED = ".at"
        private const val SPEECH = "speech.key"
        private const val OCR = "ocr.key"
        private const val PRIVACY = "privacy"
        private const val SOUND = "sound"

        fun decode(encoded: String): AccountSettings {
            val fields = decodePcMeta(encoded)
            val at = fields[AT]?.toLongOrNull() ?: 0L
            var keys = UserAiKeys.NONE
            fields.keys
                .filter {
                    it.startsWith(AI) && !it.endsWith(MODEL) && !it.endsWith(URL) && !it.endsWith(SAVED)
                }
                .forEach { field ->
                    val provider = field.removePrefix(AI)
                    val apiKey = fields[field].orEmpty()
                    if (apiKey.isNotBlank()) {
                        keys = keys.with(
                            UserAiKey(
                                providerId = provider,
                                apiKey = apiKey,
                                model = fields[field + MODEL].orEmpty(),
                                baseUrl = fields[field + URL].orEmpty(),

                                // Когда ключ вписали, важно для слияния: без своей отметки
                                // приехавший ключ выглядел бы ровесником всей посылки.
                                savedAt = fields[field + SAVED]?.toLongOrNull() ?: at,
                            ),
                        )
                    }
                }
            return AccountSettings(
                aiKeys = keys,
                speechKey = fields[SPEECH].orEmpty(),
                ocrKey = fields[OCR].orEmpty(),
                privacy = fields[PRIVACY]?.let { name -> PrivacyLevel.entries.firstOrNull { it.name == name } },
                sound = fields[SOUND]?.let { it == "yes" },
                at = at,
            )
        }
    }
}
