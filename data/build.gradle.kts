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
        // Alternative provider (OpenAI-compatible: OpenAI, OpenRouter, local...).
        buildConfigField("String", "OPENAI_API_KEY", prop("OPENAI_API_KEY"))
        buildConfigField("String", "OPENAI_BASE_URL", prop("OPENAI_BASE_URL", "https://api.openai.com/v1"))
        buildConfigField("String", "OPENAI_MODEL", prop("OPENAI_MODEL", "gpt-4o-mini"))
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
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
