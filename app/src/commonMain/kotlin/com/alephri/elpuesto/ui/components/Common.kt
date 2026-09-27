package com.alephri.elpuesto.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.AmberHi
import com.alephri.elpuesto.ui.theme.AmberLo
import com.alephri.elpuesto.ui.theme.ArchivoFamily
import com.alephri.elpuesto.ui.theme.Bg
import com.alephri.elpuesto.ui.theme.Border
import com.alephri.elpuesto.ui.theme.BorderStrong
import com.alephri.elpuesto.ui.theme.Danger
import com.alephri.elpuesto.ui.theme.DangerHi
import com.alephri.elpuesto.ui.theme.Divider
import com.alephri.elpuesto.ui.theme.Live
import com.alephri.elpuesto.ui.theme.OnAmber
import com.alephri.elpuesto.ui.theme.Panel
import com.alephri.elpuesto.ui.theme.PanelAlt
import com.alephri.elpuesto.ui.theme.Placeholder
import com.alephri.elpuesto.ui.theme.PlexMonoFamily
import com.alephri.elpuesto.ui.theme.PlexSansFamily
import com.alephri.elpuesto.ui.theme.TextFaint
import com.alephri.elpuesto.ui.theme.TextHi
import com.alephri.elpuesto.ui.theme.TextMut
import com.alephri.elpuesto.ui.theme.TextPrimary
import com.alephri.elpuesto.ui.theme.TextSub

@Composable
fun Avatar(
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    bg: Color = Amber,
    textColor: Color = OnAmber,
) {
    Box(modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        // Iniciales (2) a tamaño normal; etiquetas más largas ("11.7", "IFRT12") se achican.
        val scale = when {
            initials.length <= 2 -> 0.34f
            initials.length <= 4 -> 0.27f
            else -> 0.21f
        }
        Text(
            initials,
            fontFamily = ArchivoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * scale).sp,
            color = textColor,
            maxLines = 1,
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Brush.horizontalGradient(listOf(AmberHi, AmberLo)) else SolidColor(BorderStrong))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = OnAmber, fontFamily = PlexSansFamily, fontWeight = FontWeight.Bold, fontSize = 15.5.sp)
    }
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    /** Tope de caracteres (el del servidor, ver `TextLimits`): lo que pase se corta. */
    maxLength: Int? = null,
    /** Con [maxLength]: muestra "N/máx" cuando ya va cerca del tope (textos largos). */
    showCounter: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val counter = maxLength?.takeIf { showCounter && value.length >= it * 8 / 10 }
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .border(1.5.dp, Border, RoundedCornerShape(14.dp))
            // Todo el recuadro enfoca el campo (no solo el texto/placeholder).
            .clickable(interactionSource = interaction, indication = null) { focus.requestFocus() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { v -> onValueChange(if (maxLength != null) v.take(maxLength) else v) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(end = if (counter != null) 64.dp else 0.dp).focusRequester(focus),
            textStyle = TextStyle(color = TextPrimary, fontFamily = PlexSansFamily, fontSize = 16.sp),
            cursorBrush = SolidColor(Amber),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, color = Placeholder, fontFamily = PlexSansFamily, fontSize = 16.sp)
                }
                inner()
            },
        )
        if (counter != null) {
            Text(
                "${value.length}/$counter",
                fontFamily = PlexMonoFamily, fontSize = 11.sp,
                color = if (value.length >= counter) Amber else Placeholder,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

private val BLOOD_TYPES = listOf("O+", "O-", "A+", "A-", "B+", "B-", "AB+", "AB-")

/** Selector de tipo de sangre (valores fijos) con panel desplegable de ancho completo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BloodTypeField(value: String, onValueChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Panel)
                .border(1.5.dp, Border, RoundedCornerShape(14.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value.ifBlank { "Selecciona" },
                color = if (value.isBlank()) Placeholder else TextPrimary,
                fontFamily = PlexSansFamily, fontSize = 16.sp, modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "▲" else "▼", color = Amber, fontSize = 13.sp)
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BLOOD_TYPES.forEach { bt ->
                    val on = bt == value
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Amber else Panel)
                            .border(1.dp, Border, RoundedCornerShape(10.dp))
                            .clickable { onValueChange(bt); expanded = false }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        Text(bt, fontFamily = PlexMonoFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (on) OnAmber else TextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onTrailingClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, fontFamily = ArchivoFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextHi)
        if (trailing != null) {
            val m = if (onTrailingClick != null) {
                Modifier.clip(RoundedCornerShape(6.dp)).clickable { onTrailingClick() }.padding(4.dp)
            } else Modifier
            Text(trailing, modifier = m, color = Amber, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
        }
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Panel)
            .border(1.dp, Border, RoundedCornerShape(16.dp))
            .padding(padding),
        content = content,
    )
}

@Composable
fun Tag(
    text: String,
    container: Color = PanelAlt,
    contentColor: Color = TextSub,
    border: Color = BorderStrong,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(container)
            .border(1.dp, border, RoundedCornerShape(100.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = contentColor, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

@Composable
fun AmberTag(text: String) =
    Tag(text, container = Amber.copy(alpha = 0.14f), contentColor = Amber, border = Amber.copy(alpha = 0.26f))

@Composable
fun LiveChip() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Live))
        Text("EN CURSO", color = Live, fontFamily = PlexSansFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
    }
}

@Composable
fun ConnectionBanner(online: Boolean) {
    // Al recuperar la conexión se muestra un "de vuelta en línea" transitorio (2.5s):
    // el usuario siempre sabe en qué modo opera sin una franja permanente.
    var showBack by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var wasOffline by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(online) {
        if (!online) {
            wasOffline = true
            showBack = false
        } else if (wasOffline) {
            wasOffline = false
            showBack = true
            kotlinx.coroutines.delay(2_500)
            showBack = false
        }
    }
    when {
        !online -> Box(
            Modifier.fillMaxWidth().background(Danger.copy(alpha = 0.14f)).padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Sin conexión · mostrando datos guardados", color = DangerHi, fontFamily = PlexSansFamily, fontSize = 12.sp)
        }
        showBack -> Box(
            Modifier.fillMaxWidth().background(Live.copy(alpha = 0.14f)).padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("De vuelta en línea · tiempo real activo", color = Live, fontFamily = PlexSansFamily, fontSize = 12.sp)
        }
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(PanelAlt)
            .border(1.dp, Border, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, opt ->
            val sel = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Amber else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(opt, color = if (sel) OnAmber else TextMut, fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun BottomBar(selected: String, onSelect: (String) -> Unit = {}) {
    val items = listOf("Inicio", "Agenda", "Chats")
    // Barra plana con iconos de línea y etiquetas en mono mayúsculas; activo en ámbar.
    // El fondo llega hasta abajo y el contenido respeta la barra de navegación de Android.
    Column(Modifier.fillMaxWidth().background(Bg).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            items.forEach { label ->
                val on = label == selected
                val tint = if (on) Amber else TextFaint
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(label) }
                        .padding(horizontal = 14.dp, vertical = 2.dp),
                ) {
                    LineIconView(navIcon(label), tint, size = 26.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        label.uppercase(), color = tint, fontFamily = PlexMonoFamily,
                        fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.sp,
                    )
                }
            }
        }
    }
}

/** Iconos de la barra: los de línea del sistema (ver [LineIcon]), con motivo de pista. */
private fun navIcon(label: String): LineIcon = when (label) {
    "Inicio" -> LineIcon.GARAGE
    "Agenda" -> LineIcon.CALENDAR_CHECKERED
    else -> LineIcon.RADIO
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Amber)
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .clickable { onBack() },
        contentAlignment = Alignment.Center,
    ) {
        Text("‹", color = TextPrimary, fontSize = 24.sp)
    }
}

/** Botón de barra superior con glyph (engrane, etc.), mismo tamaño/estilo que [BackButton]. */
@Composable
fun TopBarIconButton(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = TextPrimary, fontSize = 18.sp)
    }
}
