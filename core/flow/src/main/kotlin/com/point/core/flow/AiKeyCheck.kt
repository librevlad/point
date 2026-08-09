package com.point.core.flow

interface AiKeyCheck {

    suspend fun check(config: UserAiConfig): KeyProbe
}

data class KeyProbe(
    val status: Int? = null,

    val reply: String? = null,

    val error: String? = null,
)

sealed interface KeyVerdict {

    data class Works(val reply: String) : KeyVerdict

    data class Refused(val what: String, val fix: String) : KeyVerdict
}

const val KEY_PROBE_PROMPT: String = "Ответь одним словом: готово"

fun keyVerdict(probe: KeyProbe): KeyVerdict {
    val status = probe.status
        ?: return KeyVerdict.Refused(
            what = "Не дозвонились до сервиса",
            fix = "Похоже, нет связи — или адрес сервиса набран с опечаткой. " +
                "Проверьте интернет и нажмите «Проверить и включить» ещё раз.",
        )
    return when {
        status in 200..299 -> {
            val reply = probe.reply?.trim().orEmpty()
            if (reply.isEmpty()) {
                KeyVerdict.Refused(
                    what = "Ключ приняли, но ответа не прислали",
                    fix = "Обычно дело в имени модели. Выберите сервис в списке выше — имя подставится само.",
                )
            } else {
                KeyVerdict.Works(reply.take(PROOF_CHARS))
            }
        }
        status == 401 || status == 403 -> KeyVerdict.Refused(
            what = "Ключ не подошёл",
            fix = "Скопируйте ключ целиком, без пробелов по краям, и проверьте, что он от того сервиса, " +
                "который выбран выше.",
        )
        status == 402 -> KeyVerdict.Refused(
            what = "Сервис просит оплату",
            fix = "У этого ключа нет бесплатного доступа. Возьмите ключ у другого сервиса из списка выше — " +
                "там отмечено, где бесплатный уровень есть.",
        )
        status == 404 -> KeyVerdict.Refused(
            what = "Сервис не знает такой модели",
            fix = "Выберите сервис в списке выше — адрес и имя модели подставятся сами.",
        )

        status == 429 -> KeyVerdict.Refused(
            what = "Ключ верный, но квота на сейчас исчерпана",
            fix = "Бесплатный лимит кончился. Подождите несколько минут и нажмите «Проверить и " +
                "включить» ещё раз — или возьмите ключ у другого сервиса из списка выше и " +
                "впишите его сюда вместо этого.",
        )
        status == 400 -> KeyVerdict.Refused(
            what = "Сервис не понял запрос",
            fix = "Чаще всего это имя модели. Выберите сервис в списке выше, чтобы оно подставилось само.",
        )
        status in 500..599 -> KeyVerdict.Refused(
            what = "Сервис сейчас не отвечает",
            fix = "Это не про ваш ключ — у сервиса неполадки. Попробуйте через несколько минут.",
        )
        else -> KeyVerdict.Refused(
            what = "Сервис отказал (код $status)",
            fix = detailOrHint(probe.error),
        )
    }
}

private fun detailOrHint(error: String?): String {
    val said = error?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    return if (said.isEmpty()) {
        "Что именно не так, сервис не сказал. Попробуйте другой сервис из списка выше."
    } else {
        "Сервис ответил: ${said.take(DETAIL_CHARS)}"
    }
}

fun withoutKey(text: String, key: String): String {
    val secret = key.trim()
    if (secret.length < MIN_SECRET) return text
    return text.replace(secret, "…")
}

fun looksLikeApiKey(text: String?): Boolean {
    val candidate = text?.trim().orEmpty()
    if (candidate.length !in MIN_KEY..MAX_KEY) return false
    return candidate.none { it.isWhitespace() } && candidate.all { it.code in 0x21..0x7E }
}

fun maskedKey(apiKey: String): String {
    val key = apiKey.trim()
    if (key.isEmpty()) return ""
    if (key.length < MIN_MASKABLE) return "•".repeat(key.length.coerceAtMost(MASK_DOTS))
    return "${key.take(MASK_EDGE)}…${key.takeLast(MASK_EDGE)}"
}

private const val MIN_MASKABLE = 12
private const val MASK_EDGE = 4
private const val MASK_DOTS = 8

private const val PROOF_CHARS = 80

private const val DETAIL_CHARS = 160

private const val MIN_SECRET = 8

private const val MIN_KEY = 16
private const val MAX_KEY = 400
