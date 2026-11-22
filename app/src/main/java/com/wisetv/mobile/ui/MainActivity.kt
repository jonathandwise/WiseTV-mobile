package com.wisetv.mobile.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.wisetv.mobile.App
import com.wisetv.mobile.L
import com.wisetv.mobile.player.PlaybackManager
import com.wisetv.mobile.player.PlaybackService
import com.wisetv.mobile.ui.live.ProgramSheet
import com.wisetv.mobile.ui.player.PlayerScreen
import com.wisetv.mobile.ui.settings.AddPlaylistScreen
import com.wisetv.mobile.ui.settings.HiddenChannelsScreen
import com.wisetv.mobile.ui.settings.ManageGroupsScreen
import com.wisetv.mobile.ui.settings.PlaylistsScreen
import com.wisetv.mobile.ui.vod.MovieDetailScreen
import com.wisetv.mobile.ui.vod.PlexMovieScreen
import com.wisetv.mobile.ui.vod.PlexShowScreen
import com.wisetv.mobile.ui.vod.SeriesDetailScreen
import com.wisetv.mobile.update.Updater
import kotlinx.coroutines.delay

/**
 * The one activity. An AppCompatActivity because the Chromecast button needs one; everything
 * inside is Compose.
 */
class MainActivity : AppCompatActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Connecting to the playback service is what lets it keep playing (and show its
        // notification) once the app is in the background.
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        setContent {
            WiseTheme {
                val scope = rememberCoroutineScope()
                val app = remember { AppState(App.graph, scope) }
                AppRoot(app, onExit = {
                    App.graph.playback.stop()
                    finish()
                })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        App.graph.playback.setAppVisible(true)
        App.graph.playback.unpark()
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        val pm = App.graph.playback
        pm.setAppVisible(false)
        if (!App.graph.settings.backgroundAudio) pm.park()
    }

    override fun onDestroy() {
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}

@Composable
fun AppRoot(app: AppState, onExit: () -> Unit) {
    val g = app.g
    val playlists by g.db.playlists().observeAll().collectAsState(initial = null)
    var lastBack by remember { mutableLongStateOf(0L) }

    // Pick a valid active playlist once the list is known.
    LaunchedEffect(playlists) {
        val list = playlists ?: return@LaunchedEffect
        if (list.isEmpty()) {
            if (app.nav.current != Screen.AddPlaylist) app.nav.resetTo(Screen.AddPlaylist)
            return@LaunchedEffect
        }
        val active = list.firstOrNull { it.id == app.playlistId } ?: list.first()
        app.setActivePlaylist(active.id)
        app.guide.bind(active.id)
    }

    // One-time startup work.
    LaunchedEffect(Unit) {
        g.sync.launchRefreshAll(force = false)
        if (g.settings.autoplayLast && g.playback.nowPlaying.value == null) {
            val ch = g.db.channels().get(g.settings.lastChannelId)
            if (ch != null && ch.playlistId == g.settings.activePlaylistId) app.tune(ch, fullscreen = false)
        }
    }

    // Updates: check now and every few hours; offer to install at a quiet moment.
    val update by g.updater.state.collectAsState()
    var offer by remember { mutableStateOf<Updater.State.Ready?>(null) }
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(Unit) {
        while (true) {
            g.updater.checkAndDownload()
            delay(6 * 3_600_000L)
        }
    }
    val current = app.nav.current
    LaunchedEffect(update, current) {
        val u = update as? Updater.State.Ready ?: return@LaunchedEffect
        if (!g.settings.autoUpdate || !g.updater.shouldOffer(u)) return@LaunchedEffect
        if (current == Screen.Player || current == Screen.AddPlaylist) return@LaunchedEffect
        delay(2_000)
        offer = u
    }

    // Playback messages worth showing outside the player.
    LaunchedEffect(Unit) {
        g.playback.events.collect { ev ->
            if (ev is PlaybackManager.Event.Notice) app.showToast(ev.message)
            if (ev is PlaybackManager.Event.Error && ev.everywhere && app.nav.current != Screen.Player) app.showToast(ev.message)
        }
    }

    BackHandler {
        val cur = app.nav.current
        when {
            app.nav.pop() -> Unit
            cur is Screen.Main && cur.section != Section.LIVE -> app.nav.replaceTop(Screen.Main(Section.LIVE))
            System.currentTimeMillis() - lastBack < 2_500 -> onExit()
            else -> {
                lastBack = System.currentTimeMillis()
                app.showToast("Press Back again to exit")
            }
        }
    }

    // Each screen keeps its scroll position while it stays on the back stack.
    val holder = rememberSaveableStateHolder()
    val liveKeys = remember { HashSet<String>() }
    val depth = app.nav.stack.size
    val screen = app.nav.current
    val entryKey = "$depth|${if (screen is Screen.Main) "main" else screen.toString()}"
    LaunchedEffect(entryKey) {
        L.i("screen $screen")
        liveKeys += entryKey
        val gone = liveKeys.filter { (it.substringBefore('|').toIntOrNull() ?: 0) > depth }
        gone.forEach { holder.removeState(it) }
        liveKeys.removeAll(gone.toSet())
    }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        if (playlists == null) {
            CenterMessage("Loading…")
        } else {
            holder.SaveableStateProvider(entryKey) {
                when (screen) {
                    is Screen.Main -> MainScreen(app, screen.section)
                    Screen.Player -> PlayerScreen(app)
                    is Screen.MovieDetail -> MovieDetailScreen(app, screen.playlistId, screen.streamId)
                    is Screen.SeriesDetail -> SeriesDetailScreen(app, screen.playlistId, screen.seriesId)
                    Screen.AddPlaylist -> AddPlaylistScreen(app, editId = null, firstRun = playlists.isNullOrEmpty())
                    is Screen.EditPlaylist -> AddPlaylistScreen(app, editId = screen.id, firstRun = false)
                    Screen.Playlists -> PlaylistsScreen(app)
                    is Screen.ManageGroups -> ManageGroupsScreen(app, screen.playlistId, screen.kind, screen.firstRun)
                    is Screen.HiddenChannels -> HiddenChannelsScreen(app, screen.playlistId)
                    is Screen.PlexMovie -> PlexMovieScreen(app, screen.serverId, screen.ratingKey)
                    is Screen.PlexShow -> PlexShowScreen(app, screen.serverId, screen.ratingKey)
                }
            }
        }

        app.toast?.let { msg ->
            Text(
                msg,
                fontSize = 14.sp,
                color = Pal.Text,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, bottom = if (screen is Screen.Main) 96.dp else 32.dp)
                    .widthIn(max = 520.dp)
                    .background(Pal.Panel3, RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }

    offer?.let { u ->
        val dismiss = { g.updater.snooze(u); offer = null }
        AlertDialog(
            onDismissRequest = dismiss,
            containerColor = Pal.Panel2,
            title = { Text("WiseTV ${u.info.versionName} is ready") },
            text = {
                Text(
                    (u.info.notes?.let { "$it\n\n" } ?: "") +
                        if (g.updater.hasInstallPermission(activity)) "Install it now? WiseTV restarts afterwards."
                        else "To install updates, Android needs you to allow WiseTV to install apps (asked only once). Turn it on, then come back.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    offer = null
                    g.updater.install(activity, u)
                }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Not now") } },
        )
    }
    app.programMenu?.let { menu -> ProgramSheet(app, menu) { app.programMenu = null } }
}
