package com.point.core.flow

/**
 * Где человеку взять ключ и что он за это получит.
 *
 * До этого экран настроек просил три вещи — ключ, модель и «endpoint (base URL)» — и предполагал,
 * что человек знает их наизусть. Это знание разработчика, а не пользователя: адрес и имя модели
 * должны подставляться выбором, а не набираться руками.
 *
 * Каталог живёт в `:core:flow` (чистый Kotlin) и потому проверяется тестом: ссылка, ведущая в
 * никуда, обнаружится здесь, а не в руках у человека, которому нужен ключ прямо сейчас.
 *
 * **Про «бесплатно».** Бесплатные уровни протухают быстрее, чем пишется код: провайдер закрывает
 * доступ, меняет лимиты или требует карту. Поэтому здесь не обещание, а наблюдение с датой — и
 * когда оно устареет, это будет видно по дате, а не по разочарованию.
 */
data class AiProvider(
    val id: String,
    /** Как называется у себя на сайте — чтобы человек узнал его на странице. */
    val name: String,
    /** Что это даёт, словами продукта. */
    val what: String,
    /** Страница, где выдают ключ. */
    val keyUrl: String,
    /** Адрес API — подставляется сам, человеку его знать незачем. */
    val baseUrl: String,
    /** Модели по умолчанию, первая — основная. */
    val models: String,
    /** Что известно про бесплатный доступ и когда это проверялось. */
    val freeNote: String? = null,
)

/**
 * Порядок не алфавитный: сверху то, с чего человеку проще начать. OpenRouter первым, потому что
 * один его ключ открывает сразу несколько бесплатных моделей.
 */
val AI_PROVIDERS: List<AiProvider> = listOf(
    AiProvider(
        id = "openrouter",
        name = "OpenRouter",
        what = "один ключ — десятки моделей разом",
        keyUrl = "https://openrouter.ai/keys",
        baseUrl = "https://openrouter.ai/api/v1",
        models = "google/gemma-4-31b-it:free,openai/gpt-oss-20b:free",
        freeNote = "бесплатные модели есть (проверено 08.2026)",
    ),
    AiProvider(
        id = "groq",
        name = "Groq",
        what = "самый быстрый ответ из бесплатных",
        keyUrl = "https://console.groq.com/keys",
        baseUrl = "https://api.groq.com/openai/v1",
        models = "llama-3.3-70b-versatile,llama-3.1-8b-instant",
        freeNote = "бесплатный уровень с лимитом в минуту (проверено 08.2026)",
    ),
    AiProvider(
        id = "mistral",
        name = "Mistral",
        what = "лучше прочих бесплатных читает плотные документы",
        keyUrl = "https://console.mistral.ai/api-keys",
        baseUrl = "https://api.mistral.ai/v1",
        models = "pixtral-12b-2409,mistral-medium-latest",
        freeNote = "бесплатный уровень после подтверждения телефона (проверено 08.2026)",
    ),
    AiProvider(
        id = "gemini",
        name = "Google Gemini",
        what = "хорошо понимает фотографии",
        keyUrl = "https://aistudio.google.com/apikey",
        baseUrl = "https://generativelanguage.googleapis.com",
        models = "gemini-flash-latest,gemini-pro-latest",
        freeNote = "бесплатная квота в сутки (проверено 08.2026)",
    ),
    AiProvider(
        id = "cerebras",
        name = "Cerebras",
        what = "быстрый текст, картинки не понимает",
        keyUrl = "https://cloud.cerebras.ai",
        baseUrl = "https://api.cerebras.ai/v1",
        models = "gpt-oss-120b",
        freeNote = "бесплатный уровень (проверено 08.2026)",
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
        baseUrl = "https://api.anthropic.com",
        models = "claude-opus-4-8",
    ),
)

/** Какой провайдер соответствует уже сохранённому адресу — чтобы экран открылся на нужном. */
fun providerForBaseUrl(baseUrl: String): AiProvider? =
    AI_PROVIDERS.firstOrNull { it.baseUrl.equals(baseUrl.trim().trimEnd('/'), ignoreCase = true) }
