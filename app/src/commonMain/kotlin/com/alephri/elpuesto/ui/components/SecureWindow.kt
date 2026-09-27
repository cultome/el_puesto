package com.alephri.elpuesto.ui.components

import androidx.compose.runtime.Composable

/**
 * Mientras esta pieza está en pantalla, sin capturas ni grabación de pantalla (Android:
 * FLAG_SECURE, y la miniatura de "recientes" en blanco). Para la información de
 * emergencia. En la web no hay forma de impedirlo: no hace nada.
 */
@Composable
expect fun SecureWindow(enabled: Boolean = true)
