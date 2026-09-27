package com.alephri.elpuesto.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Imágenes guardadas en el teléfono, como ARCHIVOS (no blobs en SQLite: Android limita
 * ~2 MB por fila y las fotos crecen). Por ruta del backend ("/images/avatar/{id}/thumb"):
 * `<h>.img` = bytes, `<h>.etag` = huella del servidor (para revalidar con 304) y
 * `<h>.404` = "se sabe que no existe" (p. ej. oficial sin foto: no se pregunta en cada
 * pantalla). La fecha de modificación del `.img` hace de "último uso" para [trim].
 * Vive en filesDir (no en cacheDir): el sistema no la borra por su cuenta — es la
 * experiencia offline — y se vacía al cerrar sesión ([clear]).
 */
internal class ImageStore(context: Context) : ImageCache {

    private val dir = File(context.applicationContext.filesDir, "images").apply { mkdirs() }

    private fun base(path: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun data(path: String) = File(dir, base(path) + ".img")
    private fun etagFile(path: String) = File(dir, base(path) + ".etag")
    private fun missing(path: String) = File(dir, base(path) + ".404")

    /** Bytes guardados (y marca el uso para la limpieza por antigüedad). */
    override fun read(path: String): ByteArray? {
        val f = data(path)
        if (!f.exists()) return null
        return runCatching { f.readBytes().also { f.setLastModified(System.currentTimeMillis()) } }.getOrNull()
    }

    override fun etag(path: String): String? = etagFile(path).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    override fun isMissing(path: String): Boolean = missing(path).exists()

    /** ¿Ya se sabe algo de esta imagen (bytes o "no existe")? Para no re-descargar al precargar. */
    override fun known(path: String): Boolean = data(path).exists() || missing(path).exists()

    @Synchronized
    override fun write(path: String, bytes: ByteArray, etag: String?) {
        val f = data(path)
        val tmp = File(dir, f.name + ".tmp")
        runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            if (etag != null) etagFile(path).writeText(etag) else etagFile(path).delete()
            missing(path).delete()
        }
    }

    @Synchronized
    override fun markMissing(path: String) {
        data(path).delete(); etagFile(path).delete()
        runCatching { missing(path).apply { if (!exists()) createNewFile() else setLastModified(System.currentTimeMillis()) } }
    }

    @Synchronized
    override fun delete(path: String) {
        data(path).delete(); etagFile(path).delete(); missing(path).delete()
    }

    @Synchronized
    override fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Deja el total bajo [maxBytes] borrando primero lo usado hace más tiempo. */
    @Synchronized
    override fun trim(maxBytes: Long) {
        val imgs = dir.listFiles { f -> f.name.endsWith(".img") }?.toMutableList() ?: return
        var total = imgs.sumOf { it.length() }
        if (total <= maxBytes) return
        imgs.sortBy { it.lastModified() }
        for (f in imgs) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
            File(dir, f.name.removeSuffix(".img") + ".etag").delete()
        }
    }
}
