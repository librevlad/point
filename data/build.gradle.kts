import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

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

        buildConfigField("String", "GEMINI_API_KEY", "\"\"")

        buildConfigField("String", "RELAY_URL", "\"\"")

        buildConfigField("String", "GEMINI_MODELS", prop("GEMINI_MODELS", "gemini-flash-latest,gemini-flash-lite-latest,gemini-pro-latest"))

        buildConfigField("String", "ANTHROPIC_API_KEY", "\"\"")
        buildConfigField("String", "ANTHROPIC_BASE_URL", prop("ANTHROPIC_BASE_URL", "https://api.anthropic.com"))
        buildConfigField("String", "CLAUDE_MODEL", prop("CLAUDE_MODEL", "claude-opus-4-8"))

        buildConfigField("String", "OPENAI_API_KEY", "\"\"")
        buildConfigField("String", "OPENAI_BASE_URL", prop("OPENAI_BASE_URL", "https://api.openai.com/v1"))
        buildConfigField("String", "OPENAI_MODELS", prop("OPENAI_MODELS", "gpt-4o-mini"))

        buildConfigField("String", "OPENROUTER_API_KEY", "\"\"")
        buildConfigField("String", "OPENROUTER_BASE_URL", prop("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"))
        buildConfigField("String", "OPENROUTER_MODELS", prop("OPENROUTER_MODELS", "google/gemma-4-31b-it:free,google/gemma-4-26b-a4b-it:free,openai/gpt-oss-20b:free"))
        buildConfigField("String", "GROQ_API_KEY", "\"\"")
        buildConfigField("String", "GROQ_BASE_URL", prop("GROQ_BASE_URL", "https://api.groq.com/openai/v1"))

        buildConfigField("String", "GROQ_MODELS", prop("GROQ_MODELS", "llama-3.3-70b-versatile,openai/gpt-oss-120b,llama-3.1-8b-instant,qwen/qwen3.6-27b"))

        buildConfigField("String", "GROQ_WHISPER_MODEL", prop("GROQ_WHISPER_MODEL", "whisper-large-v3-turbo"))
        buildConfigField("String", "MISTRAL_API_KEY", "\"\"")
        buildConfigField("String", "MISTRAL_BASE_URL", prop("MISTRAL_BASE_URL", "https://api.mistral.ai/v1"))

        buildConfigField("String", "MISTRAL_MODELS", prop("MISTRAL_MODELS", "pixtral-12b-2409,mistral-medium-latest,mistral-small-latest"))
        buildConfigField("String", "CEREBRAS_API_KEY", "\"\"")
        buildConfigField("String", "CEREBRAS_BASE_URL", prop("CEREBRAS_BASE_URL", "https://api.cerebras.ai/v1"))

        buildConfigField("String", "CEREBRAS_MODELS", prop("CEREBRAS_MODELS", "gpt-oss-120b,gemma-4-31b"))

        buildConfigField("String", "SAMBANOVA_API_KEY", "\"\"")
        buildConfigField("String", "SAMBANOVA_BASE_URL", prop("SAMBANOVA_BASE_URL", "https://api.sambanova.ai/v1"))
        buildConfigField("String", "SAMBANOVA_MODELS", prop("SAMBANOVA_MODELS", "gemma-4-31B-it"))

        buildConfigField("String", "ZHIPU_API_KEY", "\"\"")
        buildConfigField("String", "ZHIPU_BASE_URL", prop("ZHIPU_BASE_URL", "https://api.z.ai/api/paas/v4"))
        buildConfigField("String", "ZHIPU_MODELS", prop("ZHIPU_MODELS", "glm-4.6v-flash"))

        // Workers AI живёт под номером аккаунта: адрес собирается из него, поэтому без
        // номера провайдера нет, даже если ключ задан.
        buildConfigField("String", "CLOUDFLARE_API_KEY", "\"\"")
        buildConfigField("String", "CLOUDFLARE_ACCOUNT_ID", "\"\"")
        buildConfigField("String", "CLOUDFLARE_BASE_URL", prop("CLOUDFLARE_BASE_URL", "https://api.cloudflare.com/client/v4/accounts"))
        buildConfigField("String", "CLOUDFLARE_MODELS", prop("CLOUDFLARE_MODELS", "@cf/mistralai/mistral-small-3.1-24b-instruct,@cf/meta/llama-3.3-70b-instruct-fp8-fast"))

        // Домен важен: .cn отвечает на токен 401, международный .ai — работает.
        buildConfigField("String", "MODELSCOPE_API_KEY", "\"\"")
        buildConfigField("String", "MODELSCOPE_BASE_URL", prop("MODELSCOPE_BASE_URL", "https://api-inference.modelscope.ai/v1"))
        buildConfigField("String", "MODELSCOPE_MODELS", prop("MODELSCOPE_MODELS", "Qwen/Qwen3-VL-235B-A22B-Instruct,Qwen/Qwen3-VL-8B-Instruct"))

        buildConfigField("String", "GITHUB_API_KEY", "\"\"")
        buildConfigField("String", "GITHUB_BASE_URL", prop("GITHUB_BASE_URL", "https://models.github.ai/inference"))
        buildConfigField("String", "GITHUB_MODELS", prop("GITHUB_MODELS", ""))

        buildConfigField("String", "UNSTRUCTURED_API_KEY", "\"\"")
        buildConfigField("String", "UNSTRUCTURED_API_URL", prop("UNSTRUCTURED_API_URL", "https://api.unstructuredapp.io/general/v0/general"))
        buildConfigField("String", "LLAMA_CLOUD_API_KEY", "\"\"")
        buildConfigField("String", "LLAMA_CLOUD_BASE_URL", prop("LLAMA_CLOUD_BASE_URL", "https://api.cloud.llamaindex.ai"))
        buildConfigField("String", "LLAMA_CLOUD_TIER", prop("LLAMA_CLOUD_TIER", "cost_effective"))

        buildConfigField("String", "OVH_API_KEY", "\"\"")
        buildConfigField("String", "OVH_BASE_URL", prop("OVH_BASE_URL", "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1"))
        buildConfigField("String", "OVH_MODEL", prop("OVH_MODEL", "Qwen2.5-VL-72B-Instruct"))

        buildConfigField("String", "OCRSPACE_API_KEY", "\"\"")
        buildConfigField("String", "OCRSPACE_URL", prop("OCRSPACE_URL", "https://api.ocr.space/parse/image"))
    }

    buildTypes {
        debug {

            buildConfigField("String", "GEMINI_API_KEY", prop("GEMINI_API_KEY"))
            buildConfigField("String", "RELAY_URL", prop("RELAY_URL"))
            buildConfigField("String", "ANTHROPIC_API_KEY", prop("ANTHROPIC_API_KEY"))
            buildConfigField("String", "OPENAI_API_KEY", prop("OPENAI_API_KEY"))
            buildConfigField("String", "OPENROUTER_API_KEY", prop("OPENROUTER_API_KEY"))
            buildConfigField("String", "GROQ_API_KEY", prop("GROQ_API_KEY"))
            buildConfigField("String", "MISTRAL_API_KEY", prop("MISTRAL_API_KEY"))
            buildConfigField("String", "CEREBRAS_API_KEY", prop("CEREBRAS_API_KEY"))
            buildConfigField("String", "SAMBANOVA_API_KEY", prop("SAMBANOVA_API_KEY"))
            buildConfigField("String", "ZHIPU_API_KEY", prop("ZHIPU_API_KEY"))
            buildConfigField("String", "OCRSPACE_API_KEY", prop("OCRSPACE_API_KEY"))
            buildConfigField("String", "CLOUDFLARE_API_KEY", prop("CLOUDFLARE_API_KEY"))
            buildConfigField("String", "CLOUDFLARE_ACCOUNT_ID", prop("CLOUDFLARE_ACCOUNT_ID"))
            buildConfigField("String", "MODELSCOPE_API_KEY", prop("MODELSCOPE_API_KEY"))
            buildConfigField("String", "GITHUB_API_KEY", prop("GITHUB_API_KEY"))
            buildConfigField("String", "UNSTRUCTURED_API_KEY", prop("UNSTRUCTURED_API_KEY"))
            buildConfigField("String", "LLAMA_CLOUD_API_KEY", prop("LLAMA_CLOUD_API_KEY"))
            buildConfigField("String", "OVH_API_KEY", prop("OVH_API_KEY"))
        }

        create("dogfood") {
            initWith(getByName("release"))
            buildConfigField("String", "RELAY_URL", prop("RELAY_URL"))
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pdfbox.android)
    implementation(libs.commons.compress)
    implementation(libs.tukaani.xz)
    implementation(libs.junrar)
    implementation(libs.tesseract4android)
    implementation(libs.onnxruntime)
    implementation(libs.mlkit.entity.extraction)
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.subject.segmentation)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.zxing.core)
    implementation(libs.androidx.security.crypto)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
}
