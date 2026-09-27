import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization) // DTOs propios (p. ej. el stream de cambios)
    alias(libs.plugins.sqldelight)
}

// Base local con MIGRACIONES: los teléfonos se actualizan encima (no reinstalan), así que
// cambiar ElPuesto.sq exige un N.sqm (ver src/main/sqldelight/README.md). Cada compilación
// verifica que N.db + las migraciones den exactamente el esquema de ElPuesto.sq.
sqldelight {
    databases {
        create("Database") {
            packageName.set("com.alephri.elpuesto.db")
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

android {
    namespace = "com.alephri.elpuesto"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.alephri.elpuesto"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 9
        versionName = "1.5.0"

        // URL del API. Default = loopback del emulador. Para teléfono físico / ngrok:
        //   gradle :androidApp:assembleDebug -PapiBaseUrl=https://xxxx.ngrok-free.app
        val apiBaseUrl = (project.findProperty("apiBaseUrl") as String?) ?: "http://10.0.2.2:8080"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        // Dónde revisa la app si hay versión nueva (el version.json de scripts/publicar-app.sh,
        // que lo pasa al compilar). Vacío = sin actualizaciones desde la app. Para probar en el
        // emulador: -PupdatesUrl=http://10.0.2.2:8099/version.json (con un APK de debug).
        val updatesUrl = (project.findProperty("updatesUrl") as String?) ?: ""
        buildConfigField("String", "UPDATES_URL", "\"$updatesUrl\"")
        // http en claro solo para el backend local (10.0.2.2); release habla https.
        manifestPlaceholders["usesCleartext"] = "true"
    }

    // Keystore de DEBUG versionado (no es secreto): toda máquina firma igual, así los
    // teléfonos de prueba aceptan `adb install -r` sin desinstalar (misma firma).
    // La de RELEASE (APK de producción) vive FUERA del repo: ~/.config/el-puesto/
    // release.properties (storeFile, storePassword, keyAlias, keyPassword) u otra ruta con
    // -PreleaseSigning=…; sin ella, assembleRelease sale sin firmar.
    val releaseSigning = (project.findProperty("releaseSigning") as String?)?.let(::file)
        ?: File(System.getProperty("user.home"), ".config/el-puesto/release.properties")
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseSigning.isFile) {
            val p = Properties().apply { releaseSigning.inputStream().use { load(it) } }
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            manifestPlaceholders["usesCleartext"] = "false"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Ver :app — el detector de LiveData de androidx.lifecycle 2.9 truena con el lint de AGP 8.7.
    lint { disable += "NullSafeMutableLiveData" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Sin migración no hay APK: olvidar el N.sqm truena aquí y no en el teléfono del oficial.
tasks.named("preBuild") { dependsOn("verifySqlDelightMigration") }

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // La app común (pantallas, repositorio, cliente HTTP; Compose Multiplatform).
    implementation(project(":app"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.serialization.json) // version.json de las actualizaciones, recordatorios

    // Compartir ubicación: FusedLocationProvider (Play Services) en un servicio en primer plano
    implementation(libs.play.services.location)

    // Caché offline local
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.coroutines)
}
