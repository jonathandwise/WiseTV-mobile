package com.wisetv.mobile

import android.app.Application
import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.wisetv.mobile.cast.CastManager
import com.wisetv.mobile.data.Http
import com.wisetv.mobile.data.PlaylistRepository
import com.wisetv.mobile.data.RefreshWorker
import com.wisetv.mobile.data.Settings
import com.wisetv.mobile.data.SyncCoordinator
import com.wisetv.mobile.data.db.AppDatabase
import com.wisetv.mobile.data.epg.EpgImporter
import com.wisetv.mobile.data.plex.Plex
import com.wisetv.mobile.player.PlaybackManager
import com.wisetv.mobile.ui.Fmt
import com.wisetv.mobile.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        Fmt.init(this)
        graph = AppGraph(this)
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
                .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
                .okHttpClient(graph.http.api)
                .respectCacheHeaders(false)
                .crossfade(true)
                .build(),
        )
        RefreshWorker.schedule(this, graph.settings.refreshHours)
        graph.cast.init()
    }

    companion object {
        lateinit var instance: App
            private set
        val graph: AppGraph get() = instance.graph
    }
}

/** Simple manual dependency container. */
class AppGraph(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(context)
    val db = AppDatabase.build(context)
    val http = Http()
    val repo = PlaylistRepository(db, http, settings)
    val epg = EpgImporter(db, http, settings)
    val sync = SyncCoordinator(appScope, db, repo, epg, settings)
    val playback by lazy { PlaybackManager(context, this) }
    val cast by lazy { CastManager(context, this) }
    val updater by lazy { Updater(context, this) }
    val plex by lazy { Plex(http, settings) }
}
