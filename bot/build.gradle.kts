import java.util.Properties

// Point as a Telegram bot (#92): a pure-JVM app reusing the Android-free core
// (:core:model, :core:flow) untouched — the same bet that gave us :desktop, third proof.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("com.point.bot.MainKt")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:flow"))
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.json:json:20240303")
    // Text/URL → QR PNG, a pure-JVM local action the bot can run without the LLM.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Secrets live in local.properties (git-ignored), injected into `run` as system
// properties — never compiled into artifacts, same discipline as the app's BuildConfig.
val localProps = Properties()
val lpFile = rootProject.file("local.properties")
if (lpFile.exists()) lpFile.inputStream().use { localProps.load(it) }

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    for (key in listOf("TELEGRAM_BOT_TOKEN", "GEMINI_API_KEY", "GEMINI_MODELS")) {
        val value = localProps.getProperty(key)
        if (value != null) systemProperty(key, value)
    }
}
