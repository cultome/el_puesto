package com.alephri.elpuesto.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary

/**
 * Markdown ligero (indicaciones de convocatorias, novedades de una versión): `### título`,
 * viñetas `- ` y `**negritas**`; una línea por párrafo.
 */
@Composable
fun MarkdownText(md: String) {
    Column {
        md.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.isBlank() -> Spacer(Modifier.height(8.dp))
                line.startsWith("### ") -> {
                    Spacer(Modifier.height(6.dp))
                    Text(line.removePrefix("### "), fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Amber, modifier = Modifier.padding(bottom = 4.dp))
                }
                line.startsWith("- ") -> Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", color = TextMut, fontSize = 13.sp)
                    Text(inlineBold(line.removePrefix("- ")), fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextPrimary)
                }
                else -> Text(inlineBold(line), fontFamily = PlexSansFamily, fontSize = 13.sp, color = TextPrimary, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

private fun inlineBold(text: String): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextHi)) { append(part) } else append(part)
    }
}
