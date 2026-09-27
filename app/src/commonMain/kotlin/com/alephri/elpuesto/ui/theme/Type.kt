package com.alephri.elpuesto.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Escala tipográfica extraída de los mockups 1a, mapeada al Typography de Material 3.
 * - display*  → Archivo ExtraBold (títulos de pantalla, números hero)
 * - title*    → Archivo Bold (nombres de evento/campeonato, encabezados de sección)
 * - body*     → IBM Plex Sans
 * - label*    → IBM Plex Sans (labels) / IBM Plex Mono para kickers y horas
 */
val ElPuestoTypography = Typography(
    displayLarge = TextStyle(fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 38.sp),
    displayMedium = TextStyle(fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 32.sp),
    displaySmall = TextStyle(fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold, fontSize = 25.sp, lineHeight = 29.sp),

    headlineMedium = TextStyle(fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 25.sp),
    titleLarge = TextStyle(fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 22.sp),
    titleMedium = TextStyle(fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp),

    bodyLarge = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),

    // Label = 12.5sp mayúsculas con tracking; usar para flabel / etiquetas de sección.
    labelLarge = TextStyle(fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontFamily = PlexMonoFamily, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.5.sp),
)
