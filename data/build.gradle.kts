import java.util.Properties

// :data — implementations of the side-effect contracts: ObjectStore (scratch
// store, copy-in, cleanup) and LlmClient (Gemini). Android library + Hilt.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Ключи читаются один раз на модуль и подставляются ТОЛЬКО в debug (см. buildTypes ниже).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(key: String, default: String = "") = "\"${localProps.getProperty(key, default)}\""

android {
    namespace = "com.point.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // Ключи: local.properties -> BuildConfig, и ТОЛЬКО в debug.
        //
        // Инвариант «ни один секрет не попадает в раздаваемый артефакт» был записан в
        // CLAUDE.md, но код ему не соответствовал: buildConfigField в defaultConfig
        // применяется ко ВСЕМ вариантам, поэтому релизная сборка запекала те же живые
        // ключи. Поймано разбором публично выложенного APK (v0.2.0): внутри лежали
        // GitHub-токен и два ключа к моделям. Теперь defaultConfig объявляет поля
        // ПУСТЫМИ, а настоящие значения подставляет только buildTypes.debug —
        // release физически не видит local.properties.
        buildConfigField("String", "GEMINI_API_KEY", "\"\"")

        // Relay (#161 v2): the app-wide shared secret for the blind relay (sent as X-Point-App). The
        // relay URL itself travels in the pairing (QR ?r=), so only the secret is build-baked here.
        buildConfigField("String", "RELAY_URL", "\"\"")
        buildConfigField("String", "RELAY_APP_SECRET", "\"\"")
        // Gemini models tried in order. Pro стоял первым — «лучшее, если доступно», и это
        // ничего не стоило, пока бесплатная квота Pro не кончилась насовсем: замер на живой
        // ведомости (02.08.2026) даёт от него 429 за 14 с — плату берёт каждое действие, а
        // читает всё равно flash. Поэтому первым идёт тот, кто отвечает; Pro остаётся в
        // хвосте — вернётся квота или чужой ключ, и очередь снова начнётся с сильного.
        buildConfigField("String", "GEMINI_MODELS", prop("GEMINI_MODELS", "gemini-flash-latest,gemini-flash-lite-latest,gemini-pro-latest"))
        // Claude (Anthropic) — fallback after Gemini. Native Messages API.
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"\"")
        buildConfigField("String", "ANTHROPIC_BASE_URL", prop("ANTHROPIC_BASE_URL", "https://api.anthropic.com"))
        buildConfigField("String", "CLAUDE_MODEL", prop("CLAUDE_MODEL", "claude-opus-4-8"))
        // Alternative provider (OpenAI-compatible: OpenAI, OpenRouter, local...).
        buildConfigField("String", "OPENAI_API_KEY", "\"\"")
        buildConfigField("String", "OPENAI_BASE_URL", prop("OPENAI_BASE_URL", "https://api.openai.com/v1"))
        buildConfigField("String", "OPENAI_MODELS", prop("OPENAI_MODELS", "gpt-4o-mini"))

        // Free OpenAI-compatible providers (#32 — "все бесплатные по максимуму"). Each
        // activates only when its key is present; *_MODELS is a comma-separated fallback
        // list, so a single key (esp. OpenRouter) yields several free models chained.
        // Vision-capable models lead so "Понять" works on a photo.
        buildConfigField("String", "OPENROUTER_API_KEY", "\"\"")
        buildConfigField("String", "OPENROUTER_BASE_URL", prop("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"))
        buildConfigField("String", "OPENROUTER_MODELS", prop("OPENROUTER_MODELS", "google/gemma-4-31b-it:free,google/gemma-4-26b-a4b-it:free,openai/gpt-oss-20b:free"))
        buildConfigField("String", "GROQ_API_KEY", "\"\"")
        buildConfigField("String", "GROQ_BASE_URL", prop("GROQ_BASE_URL", "https://api.groq.com/openai/v1"))
        buildConfigField("String", "GROQ_MODELS", prop("GROQ_MODELS", "llama-3.3-70b-versatile,openai/gpt-oss-120b,llama-3.1-8b-instant"))
        buildConfigField("String", "MISTRAL_API_KEY", "\"\"")
        buildConfigField("String", "MISTRAL_BASE_URL", prop("MISTRAL_BASE_URL", "https://api.mistral.ai/v1"))
        // Mistral — из бесплатных единственный, кто на замере прочитал плотную ведомость
        // целиком: pixtral за 33 с, medium за 27 с, оба нашли все 27 артикулов. medium
        // добавлен по этому замеру, а не по названию.
        buildConfigField("String", "MISTRAL_MODELS", prop("MISTRAL_MODELS", "pixtral-12b-2409,mistral-medium-latest,mistral-small-latest"))
        buildConfigField("String", "CEREBRAS_API_KEY", "\"\"")
        buildConfigField("String", "CEREBRAS_BASE_URL", prop("CEREBRAS_BASE_URL", "https://api.cerebras.ai/v1"))
        // gemma-4-31b здесь текстовая: у Cerebras картинок нет. Имя же выглядит зрячим —
        // и фото уходило на эндпойнт, который его не принимает. Оставлен только текст.
        buildConfigField("String", "CEREBRAS_MODELS", prop("CEREBRAS_MODELS", "gpt-oss-120b"))
        // GitHub Models — превью закрыто: на 02.08.2026 любой запрос отвечает
        // 410 github_models_retirement_brownout. Ключ можно оставить в local.properties, но
        // по умолчанию моделей нет — иначе каждое действие платит два таймаута за мёртвый
        // сервис. Вернут — вписать список обратно одной строкой в local.properties.
        buildConfigField("String", "GITHUB_API_KEY", "\"\"")
        buildConfigField("String", "GITHUB_BASE_URL", prop("GITHUB_BASE_URL", "https://models.github.ai/inference"))
        buildConfigField("String", "GITHUB_MODELS", prop("GITHUB_MODELS", ""))

        // Второй читатель страницы (#280) — только БЕСПЛАТНОЕ. Unstructured: 15 000 страниц/мес,
        // на странице тарифов прямо «No card required». LlamaParse: 10 000 кредитов/мес, про
        // карту первоисточник молчит — обещания за него нет, и пустой ключ означает «слоя нет».
        // Azure Document Intelligence сюда не приехал сознательно — он требует карту, а тезис
        // проекта: на 402/429 идём к следующему провайдеру, а не в кассу.
        // Ключи — пустые здесь и живые только в debug (buildTypes ниже); адреса не секрет.
        // UNSTRUCTURED_API_URL вынесен параметром не для красоты — у аккаунта бывает свой адрес.
        buildConfigField("String", "UNSTRUCTURED_API_KEY", "\"\"")
        buildConfigField("String", "UNSTRUCTURED_API_URL", prop("UNSTRUCTURED_API_URL", "https://api.unstructuredapp.io/general/v0/general"))
        buildConfigField("String", "LLAMA_CLOUD_API_KEY", "\"\"")
        buildConfigField("String", "LLAMA_CLOUD_BASE_URL", prop("LLAMA_CLOUD_BASE_URL", "https://api.cloud.llamaindex.ai"))
        buildConfigField("String", "LLAMA_CLOUD_TIER", prop("LLAMA_CLOUD_TIER", "cost_effective"))

        // Внешний глаз (#280) — второй в очереди после Mistral OCR. OVH отдаёт зрячую модель
        // БЕЗ ключа и регистрации (замер 04.08.2026: 15/15 на кириллице), поэтому ключ здесь
        // необязателен и живёт наравне с остальными: задан — поднимает лимиты, пуст — читатель
        // всё равно работает. Единственный такой в списке, и потому он единственный, кто
        // остаётся живым в раздаваемой сборке без единого ключа.
        buildConfigField("String", "OVH_API_KEY", "\"\"")
        buildConfigField("String", "OVH_BASE_URL", prop("OVH_BASE_URL", "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1"))
        buildConfigField("String", "OVH_MODEL", prop("OVH_MODEL", "Qwen2.5-VL-72B-Instruct"))
    }

    buildTypes {
        debug {
            // Только отладочная сборка читает local.properties — раздаётся она вручную и
            // осознанно; всё, что уходит людям, собирается release-вариантом и ключей не несёт.
            buildConfigField("String", "GEMINI_API_KEY", prop("GEMINI_API_KEY"))
            buildConfigField("String", "RELAY_URL", prop("RELAY_URL"))
            buildConfigField("String", "RELAY_APP_SECRET", prop("RELAY_APP_SECRET"))
            buildConfigField("String", "ANTHROPIC_API_KEY", prop("ANTHROPIC_API_KEY"))
            buildConfigField("String", "OPENAI_API_KEY", prop("OPENAI_API_KEY"))
            buildConfigField("String", "OPENROUTER_API_KEY", prop("OPENROUTER_API_KEY"))
            buildConfigField("String", "GROQ_API_KEY", prop("GROQ_API_KEY"))
            buildConfigField("String", "MISTRAL_API_KEY", prop("MISTRAL_API_KEY"))
            buildConfigField("String", "CEREBRAS_API_KEY", prop("CEREBRAS_API_KEY"))
            buildConfigField("String", "GITHUB_API_KEY", prop("GITHUB_API_KEY"))
            buildConfigField("String", "UNSTRUCTURED_API_KEY", prop("UNSTRUCTURED_API_KEY"))
            buildConfigField("String", "LLAMA_CLOUD_API_KEY", prop("LLAMA_CLOUD_API_KEY"))
            buildConfigField("String", "OVH_API_KEY", prop("OVH_API_KEY"))
        }

        // «Свой» вариант (#403/#161): сборка, которую владелец ставит себе с сайта.
        //
        // Ключей моделей здесь НЕТ — они личные и платные, и раздаваемому артефакту не место их
        // нести (инвариант «ни один секрет не попадает в раздаваемый артефакт», пойманный на
        // v0.2.0). Свой ключ человек вводит в приложении.
        //
        // А вот релей едет: без его адреса и общего секрета связь телефона с компьютером не
        // работает вовсе, а именно она — смысл этой сборки. Секрет релея не даёт доступа к
        // содержимому: релей слепой, он умеет только «положить» и «забрать» запечатанное письмо.
        create("dogfood") {
            initWith(getByName("release"))
            buildConfigField("String", "RELAY_URL", prop("RELAY_URL"))
            buildConfigField("String", "RELAY_APP_SECRET", prop("RELAY_APP_SECRET"))
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:flow"))

    implementation(libs.androidx.core.ktx) // FileProvider
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pdfbox.android) // PDF text extraction
    implementation(libs.commons.compress) // tar/gz/bz2/xz archives
    implementation(libs.tukaani.xz) // 7z / xz (LZMA) support for commons-compress
    implementation(libs.junrar) // rar archives
    implementation(libs.tesseract4android) // on-device OCR (Tesseract 5, rus+eng)
    implementation(libs.mlkit.entity.extraction) // on-device entity detection (phone/email/…)
    implementation(libs.mlkit.barcode) // on-device QR read (robust on photos, bundled offline model)
    implementation(libs.mlkit.subject.segmentation) // on-device subject cutout → transparent PNG
    implementation(libs.kotlinx.coroutines.play.services) // await() for ML Kit Tasks
    implementation(libs.zxing.core) // QR encode (pure Java → BitMatrix → Bitmap)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json) // real org.json so history/journal tests run on JVM
}
