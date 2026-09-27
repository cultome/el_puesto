import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// La app web de los oficiales (Kotlin/Wasm + Compose Multiplatform): la misma app que
// Android (:app) con almacenamiento y sesión del navegador. Compila a una carpeta de
// archivos estáticos que sirve el backend en /app/ (env WEB_APP_DIR):
//   gradle :webApp:wasmJsBrowserDistribution  → webApp/build/dist/wasmJs/productionExecutable
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Binaryen (optimiza el .wasm de producción) sale del repositorio declarado en
// settings.gradle.kts: el plugin no debe agregar el suyo (FAIL_ON_PROJECT_REPOS).
@OptIn(ExperimentalWasmDsl::class)
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec>().downloadBaseUrl.set(null as String?)
}

// Versión visible de la web (Configuración → Acerca de).
val webVersion: String = providers.gradleProperty("webVersion").getOrElse("0.1.0")
val generatedDir = layout.buildDirectory.dir("generated/buildInfo")
val generateBuildInfo by tasks.registering {
    val version = webVersion
    val out = generatedDir
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/alephri/elpuesto/web/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package com.alephri.elpuesto.web\n\ninternal const val WEB_VERSION = \"$version\"\n")
    }
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("elpuesto")
        browser {
            commonWebpackConfig { outputFileName = "elpuesto.js" }
        }
        binaries.executable()
    }

    compilerOptions {
        optIn.addAll("kotlin.js.ExperimentalWasmJsInterop", "kotlinx.serialization.ExperimentalSerializationApi")
    }

    sourceSets {
        wasmJsMain {
            kotlin.srcDir(generateBuildInfo)
        }
        wasmJsMain.dependencies {
            implementation(project(":app"))
            implementation(libs.cmp.resources)
            implementation(libs.kotlinx.serialization.protobuf)
            // Zonas horarias de kotlinx-datetime en el navegador (America/Mexico_City).
            implementation(npm("@js-joda/timezone", "2.22.0"))
        }
    }
}

compose.resources {
    packageOfResClass = "com.alephri.elpuesto.web.resources"
    publicResClass = false
    generateResClass = always
}
