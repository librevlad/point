// :core:flow — PURE Kotlin contracts (Executor, ExecutorRegistry, ObjectStore,
// LlmClient) + Flow Graph derivation. No Android APIs: side-effect boundaries
// are declared here and implemented in :data / :executors.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
