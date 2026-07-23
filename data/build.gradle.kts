import java.util.Properties

// :data — implementations of the side-effect contracts: ObjectStore (scratch
// store, copy-in, cleanup) and LlmClient (Gemini). Android library + Hilt.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.point.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // Gemini key: local.properties -> BuildConfig.GEMINI_API_KEY.
        // Never hard-coded, never committed (local.properties is git-ignored).
        val localProps = Properties()
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) {
            localFile.inputStream().use { localProps.load(it) }
        }
        fun prop(key: String, default: String = "") =
            "\"${localProps.getProperty(key, default)}\""

        buildConfigField("String", "GEMINI_API_KEY", prop("GEMINI_API_KEY"))
        // Gemini models tried in order (aliases track a serving free model; the pinned
        // gemini-2.0-flash 429s with zero free quota, so it is NOT the default).
        buildConfigField("String", "GEMINI_MODELS", prop("GEMINI_MODELS", "gemini-flash-latest,gemini-flash-lite-latest"))
        // Claude (Anthropic) — fallback after Gemini. Native Messages API.
        buildConfigField("String", "ANTHROPIC_API_KEY", prop("ANTHROPIC_API_KEY"))
        buildConfigField("String", "ANTHROPIC_BASE_URL", prop("ANTHROPIC_BASE_URL", "https://api.anthropic.com"))
        buildConfigField("String", "CLAUDE_MODEL", prop("CLAUDE_MODEL", "claude-opus-4-8"))
        // Alternative provider (OpenAI-compatible: OpenAI, OpenRouter, local...).
        buildConfigField("String", "OPENAI_API_KEY", prop("OPENAI_API_KEY"))
        buildConfigField("String", "OPENAI_BASE_URL", prop("OPENAI_BASE_URL", "https://api.openai.com/v1"))
        buildConfigField("String", "OPENAI_MODELS", prop("OPENAI_MODELS", "gpt-4o-mini"))

        // Free OpenAI-compatible providers (#32 — "все бесплатные по максимуму"). Each
        // activates only when its key is present; *_MODELS is a comma-separated fallback
        // list, so a single key (esp. OpenRouter) yields several free models chained.
        // Vision-capable models lead so "Понять" works on a photo.
        buildConfigField("String", "OPENROUTER_API_KEY", prop("OPENROUTER_API_KEY"))
        buildConfigField("String", "OPENROUTER_BASE_URL", prop("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1"))
        buildConfigField("String", "OPENROUTER_MODELS", prop("OPENROUTER_MODELS", "google/gemma-4-31b-it:free,google/gemma-4-26b-a4b-it:free,openai/gpt-oss-20b:free"))
        buildConfigField("String", "GROQ_API_KEY", prop("GROQ_API_KEY"))
        buildConfigField("String", "GROQ_BASE_URL", prop("GROQ_BASE_URL", "https://api.groq.com/openai/v1"))
        buildConfigField("String", "GROQ_MODELS", prop("GROQ_MODELS", "llama-3.3-70b-versatile,openai/gpt-oss-120b,llama-3.1-8b-instant"))
        buildConfigField("String", "MISTRAL_API_KEY", prop("MISTRAL_API_KEY"))
        buildConfigField("String", "MISTRAL_BASE_URL", prop("MISTRAL_BASE_URL", "https://api.mistral.ai/v1"))
        buildConfigField("String", "MISTRAL_MODELS", prop("MISTRAL_MODELS", "mistral-small-latest,pixtral-12b-2409"))
        buildConfigField("String", "CEREBRAS_API_KEY", prop("CEREBRAS_API_KEY"))
        buildConfigField("String", "CEREBRAS_BASE_URL", prop("CEREBRAS_BASE_URL", "https://api.cerebras.ai/v1"))
        buildConfigField("String", "CEREBRAS_MODELS", prop("CEREBRAS_MODELS", "gpt-oss-120b,gemma-4-31b"))
        // GitHub Models — free during preview, generous; needs a GitHub PAT (models scope).
        buildConfigField("String", "GITHUB_API_KEY", prop("GITHUB_API_KEY"))
        buildConfigField("String", "GITHUB_BASE_URL", prop("GITHUB_BASE_URL", "https://models.github.ai/inference"))
        buildConfigField("String", "GITHUB_MODELS", prop("GITHUB_MODELS", "openai/gpt-4o-mini,openai/gpt-4o"))
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
    implementation(libs.kotlinx.coroutines.play.services) // await() for ML Kit Tasks
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json) // real org.json so history/journal tests run on JVM
}
