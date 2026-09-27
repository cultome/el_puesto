package com.alephri.elpuesto.model

/**
 * Reglas de los nombres visibles (oficiales y chats). Viven en `shared` para que la app
 * avise ANTES de enviar y el backend rechace exactamente igual.
 *
 * - Nadie se llama "Tú" (se vería como uno mismo en los chats ajenos) ni "Control" (así
 *   escribe el admin durante un evento), "Sistema", "Admin"…
 * - Sin caracteres invisibles (ancho cero, marcas de dirección, control): con ellos dos
 *   nombres que se ven idénticos serían distintos, o uno se disfrazaría de otro.
 */
object NameRules {
    const val NAME_MIN = 2
    const val NAME_MAX = 60
    const val CHAT_NAME_MAX = 80

    /** Nombres completos que nadie puede usar (comparados sin acentos ni mayúsculas). */
    private val RESERVED = setOf(
        "tu", "yo", "control", "sistema", "admin", "administrador", "administracion",
        "el puesto", "elpuesto", "soporte", "moderacion", "moderador", "oficial", "anonimo",
        "desconocido", "sin nombre",
    )

    /** Primeras palabras que se hacen pasar por la organización ("Control de carrera"). */
    private val RESERVED_FIRST_WORD = setOf(
        "control", "sistema", "admin", "administrador", "administracion", "moderador",
        "moderacion", "soporte",
    )

    /** Espacios internos colapsados y extremos recortados: así se guarda. */
    fun normalize(raw: String): String = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

    /** Motivo por el que [raw] no sirve como nombre de oficial; null = sirve. */
    fun displayNameProblem(raw: String): String? {
        val name = normalize(raw)
        return when {
            hasHiddenChars(raw) -> "El nombre tiene caracteres no permitidos."
            name.length < NAME_MIN -> "El nombre debe tener al menos $NAME_MIN caracteres."
            name.length > NAME_MAX -> "El nombre puede tener hasta $NAME_MAX caracteres."
            name.none { it.isLetter() } -> "El nombre debe tener letras."
            isReserved(name) -> "Ese nombre está reservado; usa tu nombre."
            else -> null
        }
    }

    /** Motivo por el que [raw] no sirve como nombre de chat; null = sirve. */
    fun chatNameProblem(raw: String): String? {
        val name = normalize(raw)
        return when {
            hasHiddenChars(raw) -> "El nombre tiene caracteres no permitidos."
            name.length < NAME_MIN -> "El nombre debe tener al menos $NAME_MIN caracteres."
            name.length > CHAT_NAME_MAX -> "El nombre puede tener hasta $CHAT_NAME_MAX caracteres."
            name.none { it.isLetterOrDigit() } -> "El nombre debe tener letras o números."
            fold(name).split(' ').firstOrNull() in RESERVED_FIRST_WORD -> "Ese nombre está reservado."
            else -> null
        }
    }

    /**
     * Control, formato (ancho cero, marcas bidi), separadores de línea, uso privado y sin
     * asignar. Los emojis (pares sustitutos) sí se permiten.
     */
    fun hasHiddenChars(s: String): Boolean = s.any { c ->
        when (c.category) {
            CharCategory.CONTROL, CharCategory.FORMAT, CharCategory.PRIVATE_USE, CharCategory.UNASSIGNED,
            CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR -> true
            else -> false
        }
    }

    private fun isReserved(name: String): Boolean {
        val f = fold(name)
        return f in RESERVED || f.split(' ').firstOrNull() in RESERVED_FIRST_WORD
    }

    /** Minúsculas, sin acentos y solo letras/dígitos/espacios: "Tú!" → "tu". */
    fun fold(s: String): String = buildString {
        s.lowercase().forEach { c ->
            val base = ACCENTS[c] ?: c
            when {
                base.isLetterOrDigit() -> append(base)
                base.isWhitespace() && isNotEmpty() && last() != ' ' -> append(' ')
            }
        }
    }.trim()

    private val WHITESPACE = Regex("\\s+")
    private val ACCENTS = mapOf(
        'á' to 'a', 'à' to 'a', 'ä' to 'a', 'â' to 'a', 'ã' to 'a',
        'é' to 'e', 'è' to 'e', 'ë' to 'e', 'ê' to 'e',
        'í' to 'i', 'ì' to 'i', 'ï' to 'i', 'î' to 'i',
        'ó' to 'o', 'ò' to 'o', 'ö' to 'o', 'ô' to 'o', 'õ' to 'o',
        'ú' to 'u', 'ù' to 'u', 'ü' to 'u', 'û' to 'u',
        'ñ' to 'n', 'ç' to 'c',
    )
}
