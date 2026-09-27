plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
            }
        }
    }
    // JVM target so the Ktor backend can reuse the same contracts.
    jvm()
    // Web (Kotlin/Wasm): la app web usa los mismos contratos.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.kotlinx.serialization.json)
            // api: los tipos de kotlinx.datetime aparecen en el API público del modelo,
            // así que se exponen transitivamente a los consumidores (androidApp, backend).
            api(libs.kotlinx.datetime)
        }
    }
}

android {
    namespace = "com.alephri.elpuesto.shared"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
