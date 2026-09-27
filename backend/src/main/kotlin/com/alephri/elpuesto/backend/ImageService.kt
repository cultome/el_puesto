package com.alephri.elpuesto.backend

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Pipeline genérico de imágenes: recibe la imagen (ya recortada por el cliente), genera
 * las variantes (thumb/full, JPEG) y las persiste por (kind, ownerId). Se reusa para
 * avatares de oficiales y, más adelante, logos de campeonatos y circuitos.
 */
object ImageService {

    private val VARIANTS = mapOf("thumb" to 96, "full" to 512)
    const val CONTENT_TYPE = "image/jpeg"

    /**
     * ETag fuerte = huella del contenido. La app guarda las imágenes en el teléfono y
     * revalida con If-None-Match: si no cambió, 304 sin cuerpo (las URLs son fijas por
     * convención — /images/avatar/{id} — así que la huella es la única forma de saberlo).
     */
    fun etag(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return "\"" + digest.take(16).joinToString("") { "%02x".format(it) } + "\""
    }

    /** Procesa y guarda todas las variantes. Devuelve false si los bytes no son una imagen. */
    fun processAndStore(kind: String, ownerId: String, original: ByteArray): Boolean {
        val src = decodeBounded(original) ?: return false
        val square = centerSquare(src)
        transaction {
            VARIANTS.forEach { (variant, size) ->
                val bytes = toJpeg(scale(square, size))
                ImagesT.deleteWhere { (ImagesT.kind eq kind) and (ImagesT.ownerId eq ownerId) and (ImagesT.variant eq variant) }
                ImagesT.insert {
                    it[ImagesT.kind] = kind; it[ImagesT.ownerId] = ownerId; it[ImagesT.variant] = variant
                    it[contentType] = CONTENT_TYPE; it[data] = bytes
                }
            }
        }
        return true
    }

    /**
     * Variante para fotos de bitácora: conserva la proporción (sin recorte cuadrado) y
     * guarda una sola variante `full` (lado mayor ≤ 1280).
     */
    fun processAndStorePhoto(kind: String, ownerId: String, original: ByteArray): Boolean {
        val bytes = preparePhoto(original) ?: return false
        storePrepared(kind, ownerId, bytes)
        return true
    }

    /**
     * Decodifica y re-codifica una foto (proporción original, lado mayor ≤ 1280) SIN
     * guardarla: null si no es una imagen válida. Así se valida antes de crear lo que la usa.
     */
    fun preparePhoto(original: ByteArray, bg: java.awt.Color = java.awt.Color.WHITE): ByteArray? {
        val src = decodeBounded(original) ?: return null
        val maxSide = 1280
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(src.width, src.height))
        val w = maxOf(1, (src.width * scale).toInt())
        val h = maxOf(1, (src.height * scale).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, bg, null)
        g.dispose()
        return toJpeg(out)
    }

    /** Fondo de las fotos de piloto (vienen recortadas con transparencia): el carbón de la app. */
    private val CARBON = java.awt.Color(0x1E, 0x22, 0x2B)
    private const val DRIVER_THUMB = 192

    /**
     * Foto de piloto: `full` = la foto con su proporción (lado mayor ≤ 1280) y `thumb` = un
     * cuadrado de [DRIVER_THUMB] px con la cara — de [face] si la fuente la recorta, o de la
     * parte de arriba de la foto (las de medio cuerpo traen la cabeza arriba al centro).
     * Transparencias sobre [CARBON]. false = alguna no es imagen (no se guarda nada).
     */
    fun storeDriverPhoto(ownerId: String, full: ByteArray, face: ByteArray?): Boolean {
        val fullJpeg = preparePhoto(full, CARBON) ?: return false
        val thumbSrc = if (face != null) decodeBounded(face)?.let(::centerSquare) ?: return false
            else decodeBounded(full)?.let(::topSquare) ?: return false
        val thumbJpeg = toJpeg(scale(thumbSrc, DRIVER_THUMB, CARBON))
        transaction {
            listOf("full" to fullJpeg, "thumb" to thumbJpeg).forEach { (variant, bytes) ->
                ImagesT.deleteWhere { (ImagesT.kind eq "driver") and (ImagesT.ownerId eq ownerId) and (ImagesT.variant eq variant) }
                ImagesT.insert {
                    it[ImagesT.kind] = "driver"; it[ImagesT.ownerId] = ownerId; it[ImagesT.variant] = variant
                    it[contentType] = CONTENT_TYPE; it[data] = bytes
                }
            }
        }
        return true
    }

    fun exists(kind: String, ownerId: String, variant: String): Boolean = transaction {
        ImagesT.selectAll().where { (ImagesT.kind eq kind) and (ImagesT.ownerId eq ownerId) and (ImagesT.variant eq variant) }.count() > 0
    }

    /** Guarda la variante `full` ya preparada con [preparePhoto]. */
    fun storePrepared(kind: String, ownerId: String, bytes: ByteArray) {
        transaction {
            ImagesT.deleteWhere { (ImagesT.kind eq kind) and (ImagesT.ownerId eq ownerId) and (ImagesT.variant eq "full") }
            ImagesT.insert {
                it[ImagesT.kind] = kind; it[ImagesT.ownerId] = ownerId; it[ImagesT.variant] = "full"
                it[contentType] = CONTENT_TYPE; it[data] = bytes
            }
        }
    }

    /** Más que esto no es una foto: es una imagen "bomba" (pesa poco y descomprimida ocupa GB). */
    private const val MAX_PIXELES = 100_000_000L

    /** Las variantes miden ≤ 1280 px: decodificar más grande solo gasta memoria. */
    private const val LADO_DECODIFICADO = 4096

    private val FORMATOS = setOf("jpeg", "jpg", "png", "gif", "bmp")

    /**
     * Decodifica sin confiar en el archivo: lee las dimensiones del encabezado ANTES de
     * descomprimir (rechaza las absurdas y los formatos raros) y decodifica submuestreado,
     * así la memoria queda acotada (~4096² px) sin importar lo que mande el cliente.
     * null = no es una imagen aceptable.
     */
    internal fun decodeBounded(bytes: ByteArray): BufferedImage? = try {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { iis ->
            val readers = ImageIO.getImageReaders(iis)
            if (!readers.hasNext()) return@use null
            val reader = readers.next()
            try {
                if (reader.formatName.lowercase() !in FORMATOS) return@use null
                reader.setInput(iis, true, true)
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELES) return@use null
                val param = reader.defaultReadParam
                val paso = maxOf(1, (maxOf(w, h) + LADO_DECODIFICADO - 1) / LADO_DECODIFICADO)
                if (paso > 1) param.setSourceSubsampling(paso, paso, 0, 0)
                reader.read(0, param)
            } finally {
                reader.dispose()
            }
        }
    } catch (e: Exception) {
        null
    }

    fun get(kind: String, ownerId: String, variant: String): ByteArray? = transaction {
        ImagesT.selectAll()
            .where { (ImagesT.kind eq kind) and (ImagesT.ownerId eq ownerId) and (ImagesT.variant eq variant) }
            .firstOrNull()?.get(ImagesT.data)
    }

    /** Recorte central cuadrado (el cliente ya recorta; esto es red de seguridad). */
    private fun centerSquare(src: BufferedImage): BufferedImage {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        return src.getSubimage(x, y, side, side)
    }

    /** Cuadrado con la mitad del ancho, centrado y pegado arriba: la cabeza en una foto de medio cuerpo. */
    private fun topSquare(src: BufferedImage): BufferedImage {
        val side = minOf(src.width / 2, src.height)
        return src.getSubimage((src.width - side) / 2, minOf(src.height - side, src.height * 3 / 100), side, side)
    }

    private fun scale(src: BufferedImage, size: Int, bg: java.awt.Color = java.awt.Color.WHITE): BufferedImage {
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB) // RGB: JPEG no admite alpha
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, size, size, bg, null)
        g.dispose()
        return out
    }

    private fun toJpeg(img: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }
}
