import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.contentnegotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.serialization.protobuf)
    implementation(libs.ktor.server.statuspages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)

    // Persistencia
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.jakarta.mail)
    implementation(libs.jsoup) // fuentes de posiciones que solo publican HTML (WEC)
}

application {
    mainClass.set("com.alephri.elpuesto.backend.ApplicationKt")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
