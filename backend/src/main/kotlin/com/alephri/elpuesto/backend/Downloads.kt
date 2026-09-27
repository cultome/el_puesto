package com.alephri.elpuesto.backend

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Descarga del APK de prueba para teléfonos SIN modo desarrollador (no hay adb): la
 * página `/descargas` y el link fijo `/descargas/el-puesto.apk` sirven SIEMPRE el APK más
 * reciente de [Config.downloadsDir] (`dist/`, lo llena `scripts/build-apk.sh`). Público a
 * propósito: el APK no lleva secretos y entrar a la app exige invitación + aprobación.
 */
private val APK_TYPE = ContentType.parse("application/vnd.android.package-archive")

private fun latestApk(): File? =
    File(Config.downloadsDir).listFiles { f -> f.isFile && f.name.endsWith(".apk") }
        ?.maxByOrNull { it.lastModified() }

/** Versión del APK servido: `dist/VERSION`, que escribe `scripts/build-apk.sh`. */
private fun apkVersion(): String? =
    File(Config.downloadsDir, "VERSION").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

private val FECHA = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("es-MX"))
    .withZone(ZoneId.of("America/Mexico_City"))

fun Route.downloadRoutes() {
    // Producción: la página y los APK viven en S3/CloudFront (Config.downloadsUrl).
    Config.downloadsUrl?.let { url ->
        get("/descargas") { call.respondRedirect(url) }
        get("/descargas/{archivo}") { call.respondRedirect(url) }
        return
    }
    get("/descargas/el-puesto.apk") {
        val apk = latestApk()
        if (apk == null) {
            call.respond(HttpStatusCode.NotFound, ErrorBody("aún no hay un APK armado en ${Config.downloadsDir}/"))
            return@get
        }
        // Nombre estable al guardar: el teléfono siempre descarga "el-puesto.apk".
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "el-puesto.apk").toString(),
        )
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(LocalFileContent(apk, APK_TYPE))
    }
    get("/descargas") {
        val apk = latestApk()
        call.respondText(downloadPage(apk), ContentType.parse("text/html; charset=utf-8"))
    }
}

/**
 * Código de la app, para quien quiera auditarla antes de instalar un APK fuera de Google Play.
 * OJO: el repo debe ser PÚBLICO para que este enlace sirva (hoy es privado).
 */
private const val SOURCE_URL = "https://github.com/cultome/el_puesto"

/**
 * Página de descarga (tema Paddock nocturno, misma estética que la página puente). Además del
 * botón, explica por qué no estamos en Google Play, dónde auditar el código y qué permisos pide
 * la app (lista calcada de AndroidManifest.xml: mantenerla al día si cambian): el oficial está a
 * punto de instalar una app de fuera de la tienda y necesita razones para confiar.
 */
private fun downloadPage(apk: File?): String {
    val download = if (apk == null) {
        "<p class=\"meta\">Aún no hay una versión armada para descargar.</p>"
    } else {
        val built = FECHA.format(Instant.ofEpochMilli(apk.lastModified()))
        val mb = "%.1f".format(Locale.US, apk.length() / 1_048_576.0)
        """
        <p class="meta">${apkVersion()?.let { "Versión $it" } ?: "Versión de prueba"} · armada el $built · $mb MB</p>
        <a class="btn" href="/descargas/el-puesto.apk">Descargar El Puesto&nbsp;&nbsp;↓</a>
        <p class="trust">¿Una app fuera de Google Play? Aquí abajo te explicamos por qué y cómo puedes revisarla.</p>
        """.trimIndent()
    }
    return """
    <!doctype html>
    <html lang="es">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>El Puesto — descarga</title>
      <style>
        body { margin:0; background:#12151C; color:#E8EAF0; font-family:system-ui,Arial,sans-serif;
               display:flex; min-height:100vh; align-items:center; justify-content:center; }
        .card { background:#1E222B; border:1px solid #333949; border-radius:18px; max-width:500px;
                margin:20px; padding:30px; }
        .brand { font-weight:800; letter-spacing:2px; font-size:20px; text-align:center; }
        .sub { font-family:monospace; letter-spacing:4px; font-size:11px; color:#F2B134; margin-top:4px; text-align:center; }
        p { color:#9AA3B5; font-size:14px; line-height:1.55; margin:8px 0 0; }
        .intro { text-align:center; margin-top:16px; }
        .meta { font-family:monospace; font-size:12px; text-align:center; margin-top:20px; }
        .btn { display:block; text-align:center; background:#F2B134; color:#1E222B; font-weight:800; font-size:16px;
               text-decoration:none; padding:15px 24px; border-radius:14px; margin-top:14px; }
        .trust { font-size:13px; text-align:center; margin-top:12px; }
        h2 { font-size:15px; color:#FFFFFF; margin:28px 0 0; padding-top:22px; border-top:1px solid #333949; }
        h2 + p { margin-top:6px; }
        .kicker { font-family:monospace; font-size:11px; letter-spacing:2px; color:#F2B134; margin:30px 0 -14px; }
        .link { display:inline-block; margin-top:10px; font-family:monospace; font-size:13px; color:#F2B134;
                text-decoration:none; border:1px solid rgba(242,177,52,.35); background:rgba(242,177,52,.08);
                padding:8px 12px; border-radius:10px; }
        ul, ol { color:#9AA3B5; font-size:13.5px; line-height:1.6; padding-left:20px; margin:10px 0 0; }
        li + li { margin-top:6px; }
        .warn { margin-top:24px; padding:14px 16px; border-radius:12px; font-size:13.5px; line-height:1.55;
                color:#9AA3B5; background:rgba(240,82,77,.08); border:1px solid rgba(240,82,77,.3); }
        b { color:#E8EAF0; }
      </style>
    </head>
    <body>
      <div class="card">
        <div class="brand">EL PUESTO</div>
        <div class="sub">OFICIALES DE PISTA</div>
        <p class="intro">La app de cabecera del oficial de pista mexicano, hecha por oficiales para oficiales. Se instala desde esta página, no desde Google Play.</p>
        $download

        <p class="kicker">ANTES DE INSTALAR</p>
        <h2>¿Por qué no está en Google Play?</h2>
        <p>Publicar en la tienda de Google tiene costos y tiempos de revisión que, por ahora, preferimos
        ahorrarnos para poder corregir y mejorar la app rápido, de un evento a otro. Por eso la
        distribuimos directamente desde aquí.</p>

        <h2>Puedes revisar lo que instalas</h2>
        <p>El código completo de El Puesto está publicado en GitHub. Tú, o alguien de tu confianza que
        sepa de programación, puede revisar exactamente qué hace la app y qué datos envía.</p>
        <a class="link" href="$SOURCE_URL" rel="noopener">github.com/cultome/el_puesto&nbsp;↗</a>

        <h2>Lo que pide en tu teléfono</h2>
        <ul>
          <li><b>Internet</b> para sincronizar tus datos y saber si hay señal (sin ella, guarda tus cambios y los envía después).</li>
          <li><b>Notificaciones</b> para avisarte de mensajes, cambios en el MbM y tus recordatorios.</li>
          <li><b>Ubicación</b> solo si tú activas «Compartir ubicación», solo durante un evento y solo cerca del circuito (la pista y medio kilómetro alrededor; más lejos, el teléfono deja de enviarla). No pide ubicación en segundo plano: mientras la compartes ves una notificación fija con el botón <b>Pausar</b>. Tu posición no se guarda en nuestro sistema: solo se reenvía a quienes elegiste.</li>
          <li><b>Aviso al encender el teléfono</b> solo para volver a programar tus recordatorios.</li>
        </ul>
        <p>No pide acceso a tus contactos, tus mensajes, tu micrófono ni tus archivos. Las fotos las tomas
        con la cámara o las eliges tú: la app no ve el resto de tu galería.</p>

        <h2>Cómo instalarla</h2>
        <ol>
          <li>Toca <b>Descargar</b> y abre el archivo <b>el-puesto.apk</b> al terminar.</li>
          <li>Si Android lo pide, permite <b>instalar apps de este origen</b> (solo la primera vez).</li>
          <li>Si Play Protect avisa que la app no está verificada, elige <b>Instalar de todas formas</b>. Ese aviso sale con cualquier app que no viene de la tienda.</li>
          <li>Si ya tienes una versión que no se deja actualizar, desinstálala y vuelve a instalar.</li>
        </ol>

        <div class="warn"><b>Descárgala solo de esta página.</b> Si alguien te manda el archivo por WhatsApp
        o por otro medio, no lo instales: pídele el enlace a esta página.</div>
      </div>
    </body>
    </html>
    """.trimIndent()
}
