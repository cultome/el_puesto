package com.alephri.elpuesto.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val ElPuestoShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp), // contenedores de icono
    small = RoundedCornerShape(14.dp),      // campos, botones
    medium = RoundedCornerShape(16.dp),     // tarjetas
    large = RoundedCornerShape(18.dp),      // tarjetas grandes
    extraLarge = RoundedCornerShape(24.dp), // hojas inferiores
)
