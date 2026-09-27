package com.alephri.elpuesto.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut

/**
 * Estado vacío con intención (mismo lenguaje que los de MbM y Bitácora): glifo en un
 * círculo, qué pasa y qué se puede hacer. Un vacío nunca queda como un texto suelto ni
 * como pantalla en blanco. [compact] para pestañas y secciones dentro de una pantalla.
 */
@Composable
fun EmptyState(
    icon: LineIcon,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    tint: Color = Amber,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val circle = if (compact) 64.dp else 92.dp
    Column(
        modifier.fillMaxWidth().padding(top = if (compact) 28.dp else 52.dp, bottom = if (compact) 20.dp else 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(circle).clip(RoundedCornerShape(circle / 2))
                .background(tint.copy(alpha = 0.10f).compositeOver(Panel))
                .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(circle / 2)),
            contentAlignment = Alignment.Center,
        ) { LineIconView(icon, tint, size = circle * 0.42f, strokeWidth = 1.8f) }
        Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
        Text(
            title, fontFamily = ArchivoFamily, fontWeight = FontWeight.ExtraBold,
            fontSize = if (compact) 16.sp else 19.sp, color = TextHi, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            body, fontFamily = PlexSansFamily, fontSize = if (compact) 12.5.sp else 13.5.sp, color = TextMut,
            textAlign = TextAlign.Center, lineHeight = if (compact) 18.sp else 20.sp,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                actionLabel.uppercase(),
                fontFamily = PlexMonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp,
                color = tint, letterSpacing = 1.sp,
                modifier = Modifier.clip(RoundedCornerShape(100.dp))
                    .background(tint.copy(alpha = 0.10f))
                    .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(100.dp))
                    .clickable { onAction() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
