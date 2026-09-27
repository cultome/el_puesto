import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// La app de los oficiales, COMÚN a Android y web (Compose Multiplatform): pantallas, tema,
// repositorio offline-first y cliente HTTP. Lo propio de cada plataforma (servicios de
// Android, SQLDelight, Keystore; almacenamiento del navegador) vive en :androidApp y
// :webApp, detrás de las interfaces de `data/Platform*.kt` y `ui/platform/`.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        optIn.addAll(
            "kotlinx.serialization.ExperimentalSerializationApi",
            "kotlin.io.encoding.ExperimentalEncodingApi",
            "kotlin.uuid.ExperimentalUuidApi",
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            api(libs.cmp.runtime)
            api(libs.cmp.foundation)
            api(libs.cmp.ui)
            api(libs.cmp.animation)
            api(libs.cmp.material3)
            implementation(libs.cmp.resources)
            api(libs.jb.lifecycle.runtime.compose)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.protobuf)
            implementation(libs.kotlinx.serialization.json)
            api(libs.ktor3.client.core)
            implementation(libs.ktor3.client.websockets)
        }
        androidMain.dependencies {
            implementation(libs.ktor3.client.okhttp)
            implementation(libs.androidx.activity.compose)
            // Selección + recorte de imagen (avatar, imagen de chat)
            implementation(libs.image.cropper)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor3.client.js)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.alephri.elpuesto.app"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // El lint de AGP 8.7 truena con el detector de LiveData de androidx.lifecycle 2.9 (lo trae
    // Compose Multiplatform): "Found class KaCallableMemberCall, but interface was expected".
    // No usamos LiveData.
    lint { disable += "NullSafeMutableLiveData" }
}

compose.resources {
    packageOfResClass = "com.alephri.elpuesto.resources"
    publicResClass = false
    generateResClass = always
}
