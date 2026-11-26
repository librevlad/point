package com.point.core.flow

import com.point.core.model.tr

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

    /**
     * С чего начинается ключ этого сервиса: по началу Point узнаёт, чей ключ вставлен, и
     * человеку не приходится искать строку сервиса по всему списку.
     *
     * Только приметы, которые сервис ставит сам и которых нет у соседей по списку. Голое
     * `sk-` носят ключи многих сервисов — по нему Point не гадает, а спрашивает человека.
     */
    val keyPrefixes: List<String> = emptyList(),

    /** Что ещё, кроме ключа, нужно для адреса. `null` — адрес [baseUrl] полный. */
    val account: AiAccount? = null,
) {

    /** Каким читателем этот сервис выглядит для правила приватности. */
    val privacy: ReaderPrivacy get() = ReaderPrivacy(where = name, promise = promise)

    /** Видит ли картинки модель сервиса — та же примета, по которой цепочка шлёт ей снимок. */
    val sees: Boolean get() = models.split(',').any { isVisionModel(it.trim()) }
}

/**
 * Номер аккаунта, без которого адрес сервиса не собрать.
 *
 * Хранится не номер, а готовый адрес: цепочке и проверке нужен адрес, а поле на экране
 * показывает номер, вынутый из него обратно.
 */
data class AiAccount(

    /** Как поле называется у человека. */
    val label: String,

    /** Где номер искать — одной фразой под полем. */
    val where: String,

    /** Хвост адреса после номера. */
    val tail: String,
)

/** Адрес сервиса с номером аккаунта: `…/accounts/<номер>/ai/v1`. */
fun AiProvider.urlWithAccount(number: String): String =
    "${baseUrl.trimEnd('/')}/${number.trim().trim('/')}${account?.tail.orEmpty()}"

/** Номер аккаунта, записанный в адресе; пусто, если адрес не этого вида. */
fun AiProvider.accountIn(url: String): String {
    val tail = account?.tail ?: return ""
    val head = baseUrl.trimEnd('/') + "/"
    val full = url.trim().trimEnd('/')
    if (!full.startsWith(head, ignoreCase = true) || !full.endsWith(tail)) return ""
    return full.substring(head.length, full.length - tail.length).trim('/')
}

const val GROQ_PROVIDER_ID = "groq"

const val MISTRAL_PROVIDER_ID = "mistral"

val AI_PROVIDERS: List<AiProvider> = listOf(
    AiProvider(
        id = "openrouter",
        name = "OpenRouter",
        what = tr("один ключ — десятки моделей разом"),
        keyUrl = "https://openrouter.ai/keys",
        baseUrl = "https://openrouter.ai/api/v1",
        // Живой запрос 26.11.2026: gpt-oss-20b бесплатной больше нет (404), обе gemma-4 упирались
        // в предел у поставщика (429). Зрячая Nemotron отвечала сразу и читала накладную целиком.
        models = "nvidia/nemotron-3.5-super-vl:free,google/gemma-4-26b-a4b-it:free",
        freeNote = tr("бесплатные модели есть"),
        checkedAt = "26.11.2026",

        // Маршрутизатор, а не сервис: присланное уходит тому, к кому он направил, и часть
        // бесплатных конечных точек включается только с разрешением учиться на присланном.
        // Решает это настройка аккаунта человека, а не Point, — значит обещания у нас нет.
        promise = ReaderPromise.UNKNOWN,
        promiseSource = "https://openrouter.ai/docs/guides/privacy/provider-logging",
        promiseCheckedAt = "26.11.2026",
        keyPrefixes = listOf("sk-or-"),
    ),
    AiProvider(
        id = GROQ_PROVIDER_ID,
        name = "Groq",
        what = tr("самый быстрый ответ из бесплатных; им же Point расшифровывает голосовые"),
        keyUrl = "https://console.groq.com/keys",
        baseUrl = "https://api.groq.com/openai/v1",
        // Живой запрос 26.11.2026: qwen3.6-27b снята, на её месте qwen3.8-27b — и она видит:
        // накладную на картинке прочитала дословно за полсекунды. Первой стоит она: по ключу
        // человека Point зовёт первую модель списка, и картинку ей тоже показывают.
        models = "qwen/qwen3.8-27b,openai/gpt-oss-120b",
        freeNote = tr("бесплатный уровень с лимитом в минуту"),
        checkedAt = "26.11.2026",

        // «Groq is not permitted to use Inputs or Outputs for training or fine-tuning»:
        // сказано в соглашении и не делится на бесплатный и платный уровни.
        promise = ReaderPromise.NO_TRAINING,
        promiseSource = "https://console.groq.com/docs/legal/services-agreement",
        promiseCheckedAt = "26.11.2026",
        keyPrefixes = listOf("gsk_"),
    ),
    AiProvider(
        id = MISTRAL_PROVIDER_ID,
        name = "Mistral",

        what = tr("хорошо читает фото документа — им Point разбирает страницу"),
        keyUrl = "https://console.mistral.ai/api-keys",
        baseUrl = "https://api.mistral.ai/v1",
        models = "pixtral-12b-2409,mistral-medium-latest",

        // Бесплатного доступа больше нет: 26.11.2026 ключ на тарифе Free получил отказ
        // «You need a subscription to access this API» (402) от каждой модели, а включение
        // оплаты по факту просит карту.
        checkedAt = "26.11.2026",

        // В бесплатном режиме присланное идёт на обучение («we may use your data (input and
        // output) to train»), на платном — по настройке аккаунта, а Labs- и Preview-модели учатся
        // всегда. Какой режим у ключа, по нему не видно — пишем то, что хуже для человека.
        promise = ReaderPromise.TRAINS,
        promiseSource = "https://help.mistral.ai/en/articles/347617-do-you-use-my-user-data-to-train-your-artificial-intelligence-models",
        promiseCheckedAt = "26.11.2026",
    ),
    AiProvider(
        id = "sambanova",
        name = "SambaNova",
        what = tr("понимает фотографии, отвечает стабильно"),
        keyUrl = "https://cloud.sambanova.ai/apis",
        baseUrl = "https://api.sambanova.ai/v1",
        models = "gemma-4-31B-it",

        // Бесплатного уровня больше нет: 26.11.2026 — «A payment method is required» (402).
        checkedAt = "26.11.2026",

        // На витрине сказано «не видим и не собираем», в самом соглашении об обучении
        // прямо ничего, а «query logs» разрешено брать на улучшение продуктов. Реклама
        // обещанием не считается.
        promise = ReaderPromise.UNKNOWN,
        promiseSource = "https://sambanova.ai/cloud-end-user-license-agreement",
        promiseCheckedAt = "26.11.2026",
    ),
    AiProvider(
        id = "gemini",
        name = "Google Gemini",
        what = tr("хорошо понимает фотографии"),
        keyUrl = "https://aistudio.google.com/apikey",

        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        // Живой запрос 26.11.2026: flash-lite прочитала накладную, flash была перегружена (503),
        // у pro бесплатная квота нулевая (429). Первой — та, что отвечает.
        models = "gemini-flash-lite-latest,gemini-flash-latest",
        freeNote = tr("бесплатная квота в сутки"),
        checkedAt = "26.11.2026",

        // Google прямо делит: бесплатный уровень идёт на улучшение продуктов Google,
        // платный — нет. Ключ у Point бесплатный.
        // Для людей из ЕЭЗ, Швейцарии и Великобритании условия платного уровня действуют и на
        // бесплатную квоту; где человек, Point не знает — пишем худшее.
        promise = ReaderPromise.TRAINS,
        promiseSource = "https://aistudio.google.com/docs/terms",
        promiseCheckedAt = "26.11.2026",
        keyPrefixes = listOf("AIza"),
    ),
    AiProvider(
        id = "cerebras",
        name = "Cerebras",
        what = tr("быстрый текст, картинки не понимает"),
        keyUrl = "https://cloud.cerebras.ai",
        baseUrl = "https://api.cerebras.ai/v1",
        models = "gpt-oss-120b",

        // Бесплатного уровня больше нет: 26.11.2026 — «Payment required» (402).
        checkedAt = "26.11.2026",

        // «does not grant Cerebras the right to use Service Content for the purpose of training
        // or fine-tuning models» — в условиях, которые прямо распространяются на api.cerebras.ai.
        // Прежде стояла ссылка на документацию, где о данных не было ни слова.
        promise = ReaderPromise.NO_TRAINING,
        promiseSource = "https://www.cerebras.ai/terms-of-service",
        promiseCheckedAt = "26.11.2026",
        keyPrefixes = listOf("csk-"),
    ),
    AiProvider(
        id = "zhipu",
        name = "Z.ai (Zhipu)",

        what = tr("видит картинки, но часто отвечает «занят»"),
        keyUrl = "https://z.ai/manage-apikey/apikey-list",
        baseUrl = "https://api.z.ai/api/paas/v4",
        // Живой запрос 26.11.2026: glm-4.6v-flash прочитала накладную (около 9 с).
        models = "glm-4.6v-flash",
        freeNote = tr("бесплатная модель, лимит не назван"),
        checkedAt = "26.11.2026",

        // Для API условия говорят «will not use your User Content for developing or improving
        // Services unless you explicitly agree» — без слова «обучение», а в дополнительных
        // условиях та же фраза стоит в скобках черновика. Такое обещание защиту не ослабляет:
        // молчим, пока не сказано прямо.
        promise = ReaderPromise.UNKNOWN,
        promiseSource = "https://docs.z.ai/legal-agreement/terms-of-use",
        promiseCheckedAt = "26.11.2026",
    ),
    AiProvider(
        id = "nvidia",
        name = "NVIDIA NIM",
        what = tr("каталог чужих моделей на своих карточках: мелкие отвечают сразу, крупные ждут запуска"),
        keyUrl = "https://build.nvidia.com/settings/api-keys",
        baseUrl = "https://integrate.api.nvidia.com/v1",
        // Обе llama сняты 26.08.2026 (410 Gone). Живой запрос 26.11.2026: зрячая Nemotron
        // прочитала накладную за 3 с, большая текстовая ответила за секунду.
        models = "nvidia/nemotron-3.5-super-vl-120b-a12b,nvidia/nemotron-3-ultra-550b-a55b",
        freeNote = tr("бесплатные вызовы после регистрации, предел не назван"),
        checkedAt = "26.11.2026",

        // Условия пробного доступа (§3.3): NVIDIA собирает «User Content and Generated Content
        // to improve NVIDIA products and services, including AI models». Соседний §2.2 обещает
        // обратное; где условия спорят сами с собой, верим худшему.
        promise = ReaderPromise.TRAINS,
        promiseSource = "https://assets.ngc.nvidia.com/products/api-catalog/legal/NVIDIA%20API%20Trial%20Terms%20of%20Service.pdf",
        promiseCheckedAt = "26.11.2026",
        keyPrefixes = listOf("nvapi-"),
    ),
    AiProvider(
        id = "modelscope",
        name = "ModelScope",
        what = tr("большие открытые модели даром — Qwen и InternVL"),
        keyUrl = "https://modelscope.cn/my/myaccesstoken",
        baseUrl = "https://api-inference.modelscope.ai/v1",

        // 26.11.2026: Qwen3-VL из списка моделей сервиса пропали (GET /models), обе стоящие здесь
        // в нём есть. Ответ модели не проверен: сервис отвечает только подтверждённому аккаунту
        // («ModelScope Magicubes is only available to verified accounts»), а ключ владельца не
        // подтверждён — подтверждение и названо в цене.
        models = "Qwen/Qwen3.8-27B,OpenGVLab/InternVL3_5-241B-A28B",
        freeNote = tr("бесплатно после подтверждения аккаунта, суточный предел вызовов"),
        checkedAt = "26.11.2026",

        // Условия использования: присланное, кроме персональных данных, идёт «to develop and
        // improve our machine-learning and artificial-intelligence technologies».
        promise = ReaderPromise.TRAINS,
        promiseSource = "https://www.modelscope.ai/protocol/terms-of-service",
        promiseCheckedAt = "26.11.2026",
    ),

    /**
     * Workers AI живёт под номером аккаунта: адрес собирается из него, поэтому одного ключа
     * мало — без номера провайдера нет (`DataModule.cloudflareModels`).
     */
    AiProvider(
        id = "cloudflare",
        name = "Cloudflare Workers AI",
        what = tr("бесплатная дневная норма у сети, которая рядом с человеком"),
        keyUrl = "https://dash.cloudflare.com/profile/api-tokens",
        baseUrl = "https://api.cloudflare.com/client/v4/accounts",
        // Живой запрос 26.11.2026: обе модели ответили, mistral-small прочитала накладную.
        models = "@cf/mistralai/mistral-small-3.1-24b-instruct,@cf/meta/llama-3.3-70b-instruct-fp8-fast",
        freeNote = tr("бесплатная дневная норма"),
        checkedAt = "26.11.2026",

        // «Cloudflare does not use Customer Content processed by Workers AI to train AI models»
        // — сказано прямо и без разделения на уровни.
        promise = ReaderPromise.NO_TRAINING,
        promiseSource = "https://developers.cloudflare.com/workers-ai/platform/data-usage/",
        promiseCheckedAt = "26.11.2026",

        // Со своим ключом Workers AI молчал вовсе: адрес без номера аккаунта ведёт в никуда,
        // а поля под номер не было — ключ «не подходил», хотя был верным.
        account = AiAccount(
            label = tr("Номер аккаунта (Account ID)"),
            where = tr("Он стоит в адресе страницы после входа: dash.cloudflare.com/<номер>/…"),
            tail = "/ai/v1",
        ),
    ),
    /**
     * Cohere — по сверке с ключами JARVIS 26.11.2026 (владелец: «перебери ии из джарвиса,
     * возможно там есть новые»). Из новых он один отвечает без карты: остальные (Vercel,
     * OpenCode Zen, Ollama Cloud, Hugging Face) бесплатно не отвечают, GitHub Models закрыт.
     */
    AiProvider(
        id = "cohere",
        name = "Cohere",
        what = tr("видит фотографии и документы, отвечает быстро"),
        keyUrl = "https://dashboard.cohere.com/api-keys",

        // Адрес, совместимый с OpenAI. Живой запрос 26.11.2026: зрячая command-a-vision
        // прочитала накладную за 2 с, текстовая command-a ответила.
        baseUrl = "https://api.cohere.ai/compatibility/v1",
        models = "command-a-vision-07-2025,command-a-03-2025",
        freeNote = tr("пробный ключ бесплатно, не для коммерческого использования"),
        checkedAt = "26.11.2026",

        // С пробного ключа присланное идёт на «Research and Model Improvement» — «to improve the
        // performance and safety of Cohere Products». С платным решает переключатель аккаунта.
        promise = ReaderPromise.TRAINS,
        promiseSource = "https://cohere.com/privacy",
        promiseCheckedAt = "26.11.2026",
    ),
    AiProvider(
        id = "openai",
        name = "OpenAI",
        what = tr("платно, зато предсказуемо"),
        keyUrl = "https://platform.openai.com/api-keys",
        baseUrl = "https://api.openai.com/v1",
        models = "gpt-4o-mini",

        // Присланное через API не идёт на обучение, пока человек сам не разрешит.
        promise = ReaderPromise.NO_TRAINING,
        promiseSource = "https://openai.com/policies/how-your-data-is-used-to-improve-model-performance/",
        promiseCheckedAt = "26.11.2026",
        keyPrefixes = listOf("sk-proj-", "sk-svcacct-"),
    ),
    AiProvider(
        id = "anthropic",
        name = "Anthropic Claude",
        what = tr("платно, сильна в длинных документах"),
        keyUrl = "https://console.anthropic.com/settings/keys",

        baseUrl = "https://api.anthropic.com/v1",
        models = "claude-opus-5-5",

        // Присланное через API на обучение не идёт по умолчанию.
        promise = ReaderPromise.NO_TRAINING,
        promiseSource = "https://privacy.claude.com/en/articles/7996868-is-my-data-used-for-model-training",
        promiseCheckedAt = "26.11.2026",
        keyPrefixes = listOf("sk-ant-"),
    ),
)

fun providerForBaseUrl(baseUrl: String): AiProvider? =
    AI_PROVIDERS.firstOrNull { it.baseUrl.equals(baseUrl.trim().trimEnd('/'), ignoreCase = true) }

/**
 * Чей это ключ — по его началу. Узнаём только по приметам, которые сервис ставит сам; ключ
 * без приметы — `null`, и тогда Point спрашивает человека, а не гадает: ключ одного сервиса,
 * отправленный на пробу другому, — это чужой секрет в чужих руках.
 */
fun providerOfKey(key: String?): AiProvider? {
    val text = key?.trim().orEmpty()
    if (text.isEmpty()) return null
    return AI_PROVIDERS.firstOrNull { provider -> provider.keyPrefixes.any { text.startsWith(it) } }
}

/**
 * Куда идти за ключом, когда действие его ждёт.
 *
 * Список равноправных строк — выбор, различия в котором человек понимать не обязан
 * (P1): Point предлагает очевидное сам. Это первый в очереди сервис, где ключ бесплатный,
 * адрес не просит ничего, кроме ключа, режим «Куда можно отправлять» его пускает, а модель
 * умеет то, что нужно вещи: запись расшифровывают не все, снимок видят не все. Своего
 * ключа к нему у человека ещё нет — иначе идти за ключом было бы незачем.
 */
fun keyErrandService(
    keys: UserAiKeys,
    kind: com.point.core.model.ObjectKind? = null,
    level: PrivacyLevel = PrivacyLevel.DEFAULT,
): AiProvider? = AI_PROVIDERS.firstOrNull { provider ->
    val able = when (kind) {
        com.point.core.model.ObjectKind.AUDIO -> provider.id in SPEECH_PROVIDER_IDS
        com.point.core.model.ObjectKind.IMAGE -> provider.sees
        else -> true
    }
    able && provider.freeNote != null && provider.account == null &&
        allowedAt(level, provider.privacy) && keys.of(provider.id) == null
}

// Объяснение про ключ живёт в одном месте — AI_CHAIN_MORE за «Как это работает» (#1262).
val AI_KEY_WHY_SHORT: String get() = tr("Чтение с фото и распаковка работают и без ключа")
