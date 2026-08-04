// :app — DI wiring, Share entry Activity, Compose host, navigation stack.
// The only module that knows every other module.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.point"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.point"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.2.0"

        // Limit native ABIs so the Tesseract .so libraries don't bloat the APK.
        // arm64 covers all modern phones; armeabi-v7a keeps older 32-bit ones.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Release signing (#11): keystore path/passwords come from git-ignored
    // local.properties (see local.properties.sample). Without them the release build
    // falls back to the debug key, so `assembleRelease` always works locally and on CI;
    // a real store upload requires the real keystore.
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val releaseStore = localProps.getProperty("RELEASE_STORE_FILE")?.let { rootProject.file(it) }

    signingConfigs {
        if (releaseStore != null && releaseStore.exists()) {
            create("release") {
                storeFile = releaseStore
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // #11: a store build is shrunk and obfuscated; keep-rules live in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }

        // «Свой» вариант: то, что владелец обновляет себе с сайта.
        //
        // Подпись — релизная: обновление ставится ПОВЕРХ и сохраняет всё, что человек накопил.
        // Разные подписи означали бы удаление приложения на каждое обновление.
        //
        // Минификации нет намеренно: она стоит минуты на каждой сборке, а смысл этой сборки —
        // быстро отдавать свежее. Ужимать код ради размера будет релиз, который идёт людям.
        create("dogfood") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            // Своя сборка едет на свой телефон — и часто по мобильной сети. Треть её веса это
            // машинный код распознавания под старые 32-битные процессоры, которого на этом
            // телефоне не коснётся ничто. Публичному релизу так делать нельзя, здесь — можно:
            // адресат известен.
            //
            // Именно выбрасыванием, а не `ndk.abiFilters`: тот управляет своей сборкой native-кода,
            // а весь наш native приходит готовым из чужих библиотек — и мимо фильтра.
            packaging { jniLibs { excludes += "**/armeabi-v7a/**" } }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Экраны под тестом (#114): Robolectric поднимает настоящий Android в JVM, поэтому ему нужны
    // ресурсы приложения — без этого тема и строки не находятся, и экран не рисуется.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:flow"))
    implementation(project(":core:ui"))
    implementation(project(":data"))
    implementation(project(":executors"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // #114: до сих пор ни один тест не создавал экран — поворот, «назад» и потерянный колбэк были
    // невидимы для CI по построению. Robolectric даёт Android в JVM (эмулятор не нужен),
    // compose-ui-test — нажатие на узлы и пересоздание экрана.
    //
    // Только debug: активити-хост для экрана приносит `ui-test-manifest`, а он есть лишь в
    // debug-манифесте — в релизном ему делать нечего. Поэтому и сами экранные тесты живут в
    // `src/testDebug` (остальные тесты как гонялись на всех вариантах, так и гоняются).
    testDebugImplementation(libs.robolectric)
    testDebugImplementation(platform(libs.androidx.compose.bom))
    testDebugImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
