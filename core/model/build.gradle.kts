// :core:model — PURE Kotlin. No Android APIs allowed here (keeps the domain
// model trivially unit-testable). Enforced by using the kotlin-jvm plugin, not
// the android plugins.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
