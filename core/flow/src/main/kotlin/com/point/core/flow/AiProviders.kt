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
 * Groq назван отдельно, потому что его ключ включает не только чат: у него есть ручка расшифровки
 * (Whisper), и её надо уметь спросить по имени, а не по строке, набранной в двух местах (#467).
 */
const val GROQ_PROVIDER_ID = "groq"

/**
 * Mistral назван отдельно по той же причине: у него есть **специальная ручка чтения страницы**
 * (`/ocr`), и она по замеру бьёт его же зрячий чат — 15/15 против 13/15 (#490). Ключ человека
 * обязан доходить до неё, а не только до чата: в раздаваемой сборке встроенных ключей нет вовсе, и
 * без этого сильнейший читатель не включался бы там никогда (#493).
 */
const val MISTRAL_PROVIDER_ID = "mistral"

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
        id = GROQ_PROVIDER_ID,
        name = "Groq",
        what = "самый быстрый ответ из бесплатных; им же Point расшифровывает голосовые",
        keyUrl = "https://console.groq.com/keys",
        baseUrl = "https://api.groq.com/openai/v1",
        models = "llama-3.3-70b-versatile,llama-3.1-8b-instant",
        freeNote = "бесплатный уровень с лимитом в минуту (проверено 08.2026)",
    ),
    AiProvider(
        id = MISTRAL_PROVIDER_ID,
        name = "Mistral",
        // Не «читает документы», а именно то, ради чего его стоит завести: его отдельной ручкой
        // чтения Point разбирает снимок страницы, и она замерена лучшей из бесплатных (#490).
        what = "лучше всех бесплатных читает фото документа — им Point разбирает страницу",
        keyUrl = "https://console.mistral.ai/api-keys",
        baseUrl = "https://api.mistral.ai/v1",
        models = "pixtral-12b-2409,mistral-medium-latest",
        freeNote = "бесплатный уровень после подтверждения телефона (проверено 08.2026)",
    ),
    AiProvider(
        id = "sambanova",
        name = "SambaNova",
        what = "понимает фотографии, отвечает стабильно",
        keyUrl = "https://cloud.sambanova.ai/apis",
        baseUrl = "https://api.sambanova.ai/v1",
        models = "gemma-4-31B-it",
        freeNote = "бесплатный уровень (проверено 08.2026)",
    ),
    AiProvider(
        id = "gemini",
        name = "Google Gemini",
        what = "хорошо понимает фотографии",
        keyUrl = "https://aistudio.google.com/apikey",
        // Именно OpenAI-совместимая дверь Google, а не корень домена: ключ человека ходит через
        // `OpenAiCompatibleClient`, который дописывает `/chat/completions`. С голым доменом
        // получался адрес, которого нет, — и выбравший Gemini упирался в отказ, не сделав ничего
        // неправильно. Нашла это живая проверка ключа (#465): молчаливое «Сохранить» такое не ловит.
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
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
        id = "zhipu",
        name = "Z.ai (Zhipu)",
        // Цена названа при варианте, а не в сноске: замер дал два ответа из шести — «перегружено».
        what = "видит картинки, но часто отвечает «занят»",
        keyUrl = "https://z.ai/manage-apikey/apikey-list",
        baseUrl = "https://api.z.ai/api/paas/v4",
        models = "glm-4.6v-flash",
        freeNote = "бесплатная модель, лимит не назван (проверено 08.2026)",
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
        // По той же причине, что у Gemini: OpenAI-совместимая дверь живёт на `/v1`, а не в корне.
        baseUrl = "https://api.anthropic.com/v1",
        models = "claude-opus-4-8",
    ),
)

/** Какой провайдер соответствует уже сохранённому адресу — чтобы экран открылся на нужном. */
fun providerForBaseUrl(baseUrl: String): AiProvider? =
    AI_PROVIDERS.firstOrNull { it.baseUrl.equals(baseUrl.trim().trimEnd('/'), ignoreCase = true) }

/**
 * Зачем вообще ключ — сказанное ДО отказа, а не после (#465).
 *
 * Свежепоставленный Point почти ничего не умеет из того, ради чего его ставят, и узнавал об этом
 * человек в худший момент: когда действие уже провалилось. Слова живут здесь, в одном месте, чтобы
 * «Недавнее» и экран ключа говорили одно и то же: два текста об одном разъезжаются на первой же
 * правке, и человек читает разные обещания на соседних экранах.
 */
const val AI_KEY_WHY: String =
    "«Понять», «Перевести», «Спросить AI» и расшифровку записи делает модель — она работает на " +
        "вашем ключе и вашей квоте. У большинства сервисов ключ бесплатный."

/** Короткий довод для «Недавнего»: что именно молчит, пока ключа нет. */
const val AI_KEY_WHY_SHORT: String =
    "Без ключа не работают «Понять», «Перевести», «Спросить AI» и расшифровка записи."
