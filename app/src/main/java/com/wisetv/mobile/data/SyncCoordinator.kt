package com.wisetv.mobile.data

import com.wisetv.mobile.L
import com.wisetv.mobile.data.db.AppDatabase
import com.wisetv.mobile.data.epg.EpgImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialises playlist / guide updates and publishes a status line for the UI. */
class SyncCoordinator(
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val repo: PlaylistRepository,
    private val epg: EpgImporter,
    private val settings: Settings,
) {
    private val mutex = Mutex()
    val status = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    /** Used right after adding a playlist; errors are thrown to the caller. */
    suspend fun syncPlaylist(id: Long, withEpg: Boolean, progress: (String) -> Unit = {}) {
        mutex.withLock {
            try {
                repo.sync(id) { status.value = it; progress(it) }
                L.i("sync done playlist=$id")
                if (withEpg) importEpg(id, progress)
            } finally {
                status.value = null
            }
        }
    }

    fun launchEpg(id: Long): Job = scope.launch {
        mutex.withLock {
            try {
                importEpg(id) {}
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                report("Couldn't load the TV guide: ${e.message}")
            } finally {
                status.value = null
            }
        }
    }

    fun launchRefreshAll(force: Boolean): Job = scope.launch { refreshAll(force) }

    suspend fun refreshAll(force: Boolean) {
        val now = System.currentTimeMillis()
        val interval = settings.refreshHours * 3_600_000L
        for (pl in db.playlists().all()) {
            val stale = now - pl.lastSync > interval || now - pl.lastEpgSync > interval
            if (!force && !stale) continue
            mutex.withLock {
                try {
                    repo.sync(pl.id) { status.value = it }
                    importEpg(pl.id) {}
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    report("Couldn't update ${pl.name}: ${e.message}")
                } finally {
                    status.value = null
                }
            }
        }
    }

    private suspend fun importEpg(id: Long, progress: (String) -> Unit) {
        val pl = db.playlists().get(id) ?: return
        status.value = "Loading TV guide…"
        epg.import(pl) { status.value = it; progress(it) }
    }

    private fun report(msg: String) {
        error.value = msg
        scope.launch {
            delay(12_000)
            if (error.value == msg) error.value = null
        }
    }
}
