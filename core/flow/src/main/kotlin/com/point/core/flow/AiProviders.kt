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

    /**
     * Что сервис обещает про присланное (#945).
     *
     * Обещание — внешний факт: его пишет чужая компания, и оно меняется. Отвечает за него
     * перед человеком Point, поэтому оно объявлено здесь, рядом с адресом и ключом, а не
     * выведено из цены или названия. Не сказано прямо — `UNKNOWN`: молчание сервиса не
     * читается как обещание.
     */
    val promise: ReaderPromise = ReaderPromise.UNKNOWN,

    /** Где это написано и когда читалось: обещание проверяемо, а не запомнено. */
    val promiseSource: String? = null,

    val promiseCheckedAt: String? = null,
) {

    /** Каким читателем этот сервис выглядит для правила приватности. */
    val privacy: ReaderPrivacy get() = ReaderPrivacy(where = name, promise = promise)
}

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

        // Маршрутизатор, а не сервис: присланное уходит тому, к кому он направил, и часть
        // бесплатных конечных точек включается только с разрешением учиться на присланном.
        // Решает это настройка аккаунта человека, а не Point, — значит обещания у нас нет.
        promise = ReaderPromise.UNKNOWN,
        promiseSource = "https://openrouter.ai/docs/guides/privacy/provider-logging",
        promiseCheckedAt = "14.08.2026",
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

        // «Groq is not permitted to use Inputs or Outputs for training or fine-tuning»:
        // сказано в соглашении и не делится на бесплатный и платный уровни.
        promise = ReaderPromise.NO_TRAINING,
        promiseSource = "https://console.groq.com/docs/legal/services-agreement",
        promiseCheckedAt = "14.08.2026",
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

        // На бесплатном уровне присланное идёт на улучшение моделей, пока человек не
        // отказался на платном тарифе. Ключ у Point бесплатный — значит учится.
        promise = ReaderPromise.TRAINS,
        promiseSource = "https://mistral.ai/terms",
        promiseCheckedAt = "14.08.2026",
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

        // На витрине сказано «не видим и не собираем», в самом соглашении об обучении
        // прямо ничего. Реклама обещанием не считается.
        promise = ReaderPromise.UNKNOWN,
        promiseSource = "https://sambanova.ai/cloud-end-user-license-agreement",
        promiseCheckedAt = "14.08.2026",
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

        // Google прямо делит: бесплатный уровень идёт на улучшение продуктов Google,
        // платный — нет. Ключ у Point бесплатный.
        promise = ReaderPromise.TRAINS,
        promiseSource = "https://ai.google.dev/gemini-api/terms",
        promiseCheckedAt = "14.08.2026",
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

        // В условиях об обучении на присланном не сказано ничего.
        promise = ReaderPromise.UNKNOWN,
        promiseSource = "https://inference-docs.cerebras.ai",
        promiseCheckedAt = "14.08.2026",
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

        // У международной службы обещание есть, у материковой — свои правила и свой
        // закон, а по ключу не видно, куда он ведёт. Пока не различаем — молчим.
        promise = ReaderPromise.UNKNOWN,
        promiseSource = "https://z.ai/terms",
        promiseCheckedAt = "14.08.2026",
    ),
    AiProvider(
        id = "nvidia",
        name = "NVIDIA NIM",
        what = "каталог чужих моделей на своих карточках: мелкие отвечают сразу, крупные ждут запуска",
        keyUrl = "https://build.nvidia.com/settings/api-keys",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        models = "meta/llama-3.1-8b-instruct,meta/llama-3.3-70b-instruct",
        freeNote = "бесплатные вызовы после регистрации, предел не назван",
        checkedAt = "18.08.2026",

        // В условиях NVIDIA про обучение на присланном прямо не сказано; молчим, пока не проверено.
        promise = ReaderPromise.UNKNOWN,
        promiseSource = "https://build.nvidia.com/legal/terms-of-use",
        promiseCheckedAt = "18.08.2026",
    ),
    AiProvider(
        id = "modelscope",
        name = "ModelScope",
        what = "большие Qwen3-VL даром — читает фото документа целиком",
        keyUrl = "https://modelscope.cn/my/myaccesstoken",
        baseUrl = "https://api-inference.modelscope.ai/v1",
        models = "Qwen/Qwen3-VL-235B-A22B-Instruct,Qwen/Qwen3-VL-8B-Instruct",
        freeNote = "бесплатно после регистрации, суточный предел вызовов",
        checkedAt = "08.2026",

        // Условия про обучение на присланном не опубликованы.
        promise = ReaderPromise.UNKNOWN,
        promiseSource = "https://modelscope.ai/docs/model-service/API-Inference/limits",
        promiseCheckedAt = "14.08.2026",
    ),

    /**
     * Workers AI живёт под номером аккаунта: адрес собирается из него, поэтому одного ключа
     * мало — без номера провайдера нет (`DataModule.cloudflareModels`).
     */
    AiProvider(
        id = "cloudflare",
        name = "Cloudflare Workers AI",
        what = "бесплатная дневная норма у сети, которая рядом с человеком",
        keyUrl = "https://dash.cloudflare.com/profile/api-tokens",
        baseUrl = "https://api.cloudflare.com/client/v4/accounts",
        models = "@cf/mistralai/mistral-small-3.1-24b-instruct,@cf/meta/llama-3.3-70b-instruct-fp8-fast",
        freeNote = "бесплатная дневная норма",
        checkedAt = "08.2026",

        // «Cloudflare does not use customer content to train any AI models made available
        // on Workers AI» — сказано прямо и без разделения на уровни.
        promise = ReaderPromise.NO_TRAINING,
        promiseSource = "https://developers.cloudflare.com/workers-ai/platform/privacy/",
        promiseCheckedAt = "14.08.2026",
    ),
    AiProvider(
        id = "openai",
        name = "OpenAI",
        what = "платно, зато предсказуемо",
        keyUrl = "https://platform.openai.com/api-keys",
        baseUrl = "https://api.openai.com/v1",
        models = "gpt-4o-mini",

        // Присланное через API не идёт на обучение, пока человек сам не разрешит.
        promise = ReaderPromise.NO_TRAINING,
        promiseSource = "https://openai.com/policies/how-your-data-is-used-to-improve-model-performance/",
        promiseCheckedAt = "14.08.2026",
    ),
    AiProvider(
        id = "anthropic",
        name = "Anthropic Claude",
        what = "платно, сильна в длинных документах",
        keyUrl = "https://console.anthropic.com/settings/keys",

        baseUrl = "https://api.anthropic.com/v1",
        models = "claude-opus-4-8",

        // Присланное через API на обучение не идёт по умолчанию.
        promise = ReaderPromise.NO_TRAINING,
        promiseSource = "https://privacy.claude.com/en/articles/7996868-is-my-data-used-for-model-training",
        promiseCheckedAt = "14.08.2026",
    ),
)

fun providerForBaseUrl(baseUrl: String): AiProvider? =
    AI_PROVIDERS.firstOrNull { it.baseUrl.equals(baseUrl.trim().trimEnd('/'), ignoreCase = true) }

// Объяснение про ключ живёт в одном месте — AI_CHAIN_MORE за «Как это работает» (#1262).
const val AI_KEY_WHY_SHORT: String = "Чтение с фото и распаковка работают и без ключа"
