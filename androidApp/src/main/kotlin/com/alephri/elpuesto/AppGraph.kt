package com.alephri.elpuesto

import android.content.Context
import com.alephri.elpuesto.data.AndroidPrefs
import com.alephri.elpuesto.data.Auth
import com.alephri.elpuesto.data.HttpRepository
import com.alephri.elpuesto.data.ImageStore
import com.alephri.elpuesto.data.KeystoreAuthStore
import com.alephri.elpuesto.data.OfflineRepository
import com.alephri.elpuesto.data.SqlDelightLocalDb

/**
 * Dependencias de proceso (una sola instancia): la UI y el servicio de ubicación comparten
 * el MISMO cliente/caché — dos clientes renovando a la vez romperían la rotación de un
 * solo uso del refresh token.
 */
object AppGraph {
    class Graph(
        val store: KeystoreAuthStore,
        val remote: HttpRepository,
        val repo: OfflineRepository,
        val auth: Auth,
        val platform: AndroidAppPlatform,
    )

    @Volatile private var graph: Graph? = null

    fun get(context: Context): Graph = graph ?: synchronized(this) {
        graph ?: run {
            val app = context.applicationContext
            // Logs de depuración solo en builds de debug (ver logd).
            DebugLog.enabled = BuildConfig.DEBUG
            DebugLog.sink = { tag, msg -> android.util.Log.d(tag, msg) }
            val store = KeystoreAuthStore(app)
            // Envía el access token y, ante 401, renueva con el refresh y reintenta (transparente).
            val remote = HttpRepository(
                baseUrl = BuildConfig.API_BASE_URL,
                accessToken = { store.jwt },
                refreshToken = { store.refresh },
                onRefreshed = { access, refresh -> store.save(access, refresh) },
            )
            val repo = OfflineRepository(
                remote = remote,
                db = SqlDelightLocalDb(app),
                images = ImageStore(app),
                prefs = AndroidPrefs(app),
                platform = AndroidRepoPlatform(app),
            )
            Graph(store, remote, repo, Auth(store, remote), AndroidAppPlatform(app)).also { graph = it }
        }
    }
}
