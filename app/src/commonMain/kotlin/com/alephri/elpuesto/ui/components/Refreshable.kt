package com.alephri.elpuesto.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.alephri.elpuesto.data.freshReads
import com.alephri.elpuesto.ui.theme.Amber
import com.alephri.elpuesto.ui.theme.Panel
import kotlinx.coroutines.launch

/**
 * Pull-to-refresh estándar de la app: envuelve el contenido scrolleable de una pantalla
 * y ejecuta [onRefresh] (recarga real desde el servidor, con [freshReads]: nada sale de
 * la caché) al jalar hacia abajo. El
 * indicador usa el tema Paddock (panel + ámbar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        state = state,
        onRefresh = {
            scope.launch {
                refreshing = true
                // Jalar = "tráelo del servidor": aun las cargas caché-primero van a la red.
                try { freshReads { onRefresh() } } finally { refreshing = false }
            }
        },
        modifier = modifier,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                containerColor = Panel,
                color = Amber,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
    ) {
        Box { content() }
    }
}
