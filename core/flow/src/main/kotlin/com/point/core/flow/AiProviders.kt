package com.point.core.flow

data class AiProvider(
    val id: String,

    val name: String,

    val what: String,

    val keyUrl: String,

    val baseUrl: String,

    val models: String,

    val freeNote: String? = null,

    /**
     * Когда бесплатность проверяли (#575). Список стареет, и знать это нужно — но человеку
     * на глаза дата не выходит: в подписи остаётся цена, а не наша бухгалтерия.
     */
    val checkedAt: String? = null,
)

const val GROQ_PROVIDER_ID = "groq"

const val MISTRAL_PROVIDER_ID = "mistral"

val AI_PROVIDERS: List<AiProvider> = listOf(
    AiProvider(
        id = "openrouter",
        name = "OpenRouter",
        what = "один ключ — десятки моделей разом",
        keyUrl = "https://openrouter.ai/keys",
        baseUrl = "https://openrouter.ai/api/v1",
        models = "google/gemma-4-31b-it:free,openai/gpt-oss-20b:free",
        freeNote = "бесплатные модели есть",
        checkedAt = "08.2026",
    ),
    AiProvider(
        id = GROQ_PROVIDER_ID,
        name = "Groq",
        what = "самый быстрый ответ из бесплатных; им же Point расшифровывает голосовые",
        keyUrl = "https://console.groq.com/keys",
        baseUrl = "https://api.groq.com/openai/v1",
        models = "llama-3.3-70b-versatile,llama-3.1-8b-instant",
        freeNote = "бесплатный уровень с лимитом в минуту",
        checkedAt = "08.2026",
    ),
    AiProvider(
        id = MISTRAL_PROVIDER_ID,
        name = "Mistral",

        what = "лучше всех бесплатных читает фото документа — им Point разбирает страницу",
        keyUrl = "https://console.mistral.ai/api-keys",
        baseUrl = "https://api.mistral.ai/v1",
        models = "pixtral-12b-2409,mistral-medium-latest",
        freeNote = "бесплатный уровень после подтверждения телефона",
        checkedAt = "08.2026",
    ),
    AiProvider(
        id = "sambanova",
        name = "SambaNova",
        what = "понимает фотографии, отвечает стабильно",
        keyUrl = "https://cloud.sambanova.ai/apis",
        baseUrl = "https://api.sambanova.ai/v1",
        models = "gemma-4-31B-it",
        freeNote = "бесплатный уровень",
        checkedAt = "08.2026",
    ),
    AiProvider(
        id = "gemini",
        name = "Google Gemini",
        what = "хорошо понимает фотографии",
        keyUrl = "https://aistudio.google.com/apikey",

        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        models = "gemini-flash-latest,gemini-pro-latest",
        freeNote = "бесплатная квота в сутки",
        checkedAt = "08.2026",
    ),
    AiProvider(
        id = "cerebras",
        name = "Cerebras",
        what = "быстрый текст, картинки не понимает",
        keyUrl = "https://cloud.cerebras.ai",
        baseUrl = "https://api.cerebras.ai/v1",
        models = "gpt-oss-120b",
        freeNote = "бесплатный уровень",
        checkedAt = "08.2026",
    ),
    AiProvider(
        id = "zhipu",
        name = "Z.ai (Zhipu)",

        what = "видит картинки, но часто отвечает «занят»",
        keyUrl = "https://z.ai/manage-apikey/apikey-list",
        baseUrl = "https://api.z.ai/api/paas/v4",
        models = "glm-4.6v-flash",
        freeNote = "бесплатная модель, лимит не назван",
        checkedAt = "08.2026",
    ),
    AiProvider(
        id = "openai",
        name = "OpenAI",
        what = "платно, зато предсказуемо",
        keyUrl = "https://platform.openai.com/api-keys",
        baseUrl = "https://api.openai.com/v1",
        models = "gpt-4o-mini",
    ),
    AiProvider(
        id = "anthropic",
        name = "Anthropic Claude",
        what = "платно, сильна в длинных документах",
        keyUrl = "https://console.anthropic.com/settings/keys",

        baseUrl = "https://api.anthropic.com/v1",
        models = "claude-opus-4-8",
    ),
)

fun providerForBaseUrl(baseUrl: String): AiProvider? =
    AI_PROVIDERS.firstOrNull { it.baseUrl.equals(baseUrl.trim().trimEnd('/'), ignoreCase = true) }

const val AI_KEY_WHY: String =
    "«Понять», «Перевести», «AI» и расшифровку записи делает модель — она работает на " +
        "вашем ключе и вашей квоте. У большинства сервисов ключ бесплатный."

const val AI_KEY_WHY_SHORT: String = "Чтение с фото и распаковка работают и без ключа"
