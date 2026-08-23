plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.point.executors"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Объявление компьютера телефону снято в файл там, где оно и рождается, — в `:desktop`
    // (#1094). Телефонные тесты берут его с classpath: путь от рабочего каталога зависел от
    // того, откуда запустили, и пропажа файла читалась бы как «компьютер ничего не умеет».
    sourceSets.getByName("test").resources.srcDir(rootProject.file("desktop/src/test/resources"))
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:model"))
    api(project(":core:flow"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.opencv)

    // #1004: :data нужен только тестам — исполнители его не импортируют,
    // и схема модулей «стрелки только вниз» не даёт им начать.
    testImplementation(project(":data"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
}
