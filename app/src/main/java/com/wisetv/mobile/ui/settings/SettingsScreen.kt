package com.wisetv.mobile.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisetv.mobile.BuildConfig
import com.wisetv.mobile.data.RefreshWorker
import com.wisetv.mobile.data.db.GroupKind
import com.wisetv.mobile.ui.AppState
import com.wisetv.mobile.ui.Choice
import com.wisetv.mobile.ui.ChoiceSheet
import com.wisetv.mobile.ui.Fmt
import com.wisetv.mobile.ui.Pal
import com.wisetv.mobile.ui.Screen
import com.wisetv.mobile.ui.SectionHeader
import com.wisetv.mobile.ui.SettingRow
import com.wisetv.mobile.ui.ToggleRow
import com.wisetv.mobile.ui.findActivity
import com.wisetv.mobile.update.Updater
import kotlinx.coroutines.launch

private data class Picker(val title: String, val choices: List<Choice>)

@Composable
fun SettingsScreen(app: AppState) {
    val g = app.g
    val s = g.settings
    val pid = app.playlistId
    val activity = LocalContext.current.findActivity()
    val playlist by remember(pid) { g.db.playlists().observe(pid) }.collectAsState(initial = null)
    val status by g.sync.status.collectAsState()
    val update by g.updater.state.collectAsState()
    var plexDialog by remember { mutableStateOf<PlexDialog?>(null) }
    var picker by remember { mutableStateOf<Picker?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Pal.Text, modifier = Modifier.padding(start = 16.dp, top = 12.dp)) }

        item { SectionHeader("Playlists") }
        item { SettingRow("Playlists", playlist?.name ?: "None", "Add, edit, switch or remove playlists") { app.nav.push(Screen.Playlists) } }
        item { SettingRow("Add a playlist", null, "Xtream Codes, an M3U link, or free channels", icon = Icons.AutoMirrored.Filled.PlaylistAdd) { app.nav.push(Screen.AddPlaylist) } }
        item {
            val p = playlist
            SettingRow(
                "Update playlist and TV guide now",
                null,
                status ?: p?.let {
                    "Channels updated ${if (it.lastSync > 0) "${Fmt.day(it.lastSync)} ${Fmt.time(it.lastSync)}" else "never"}" +
                        "  ·  guide ${if (it.lastEpgSync > 0) "${Fmt.day(it.lastEpgSync)} ${Fmt.time(it.lastEpgSync)}" else "never"}"
                },
                icon = Icons.Filled.Refresh,
            ) {
                g.sync.launchRefreshAll(force = true)
                app.showToast("Updating in the background…")
            }
        }
        item {
            SettingRow("Update automatically every", "${s.refreshHours} hours") {
                picker = Picker("Update automatically every", listOf(6, 12, 24, 48).map { h ->
                    Choice("$h hours", selected = s.refreshHours == h) {
                        s.refreshHours = h
                        RefreshWorker.schedule(g.context, h)
                    }
                })
            }
        }
        item {
            SettingRow("TV guide days", "${s.epgDays} days", "How far ahead the guide is downloaded. Fewer days load faster.") {
                picker = Picker("TV guide days", listOf(2, 3, 4, 7).map { d ->
                    Choice("$d days", selected = s.epgDays == d) {
                        s.epgDays = d
                        app.showToast("Applies at the next guide update")
                    }
                })
            }
        }

        item { SectionHeader("Channels") }
        item { SettingRow("Channel groups", null, "Show or hide groups. Hidden groups aren't loaded into the guide.") { app.nav.push(Screen.ManageGroups(pid, GroupKind.LIVE, false)) } }
        item { SettingRow("Hidden channels", null) { app.nav.push(Screen.HiddenChannels(pid)) } }
        item { SettingRow("Movie categories", null) { app.nav.push(Screen.ManageGroups(pid, GroupKind.MOVIE, false)) } }
        item { SettingRow("Series categories", null) { app.nav.push(Screen.ManageGroups(pid, GroupKind.SERIES, false)) } }

        item { SectionHeader("Plex") }
        if (!g.plex.linked) {
            item { SettingRow("Link your Plex account", null, "Your Plex movies and shows appear in Movies, Series and Search, at home and away") { plexDialog = PlexDialog.Link } }
        } else {
            item { SettingRow("Plex account", s.plexUser) { plexDialog = PlexDialog.Account } }
            item {
                val used = g.plex.servers
                SettingRow(
                    "Plex servers",
                    when (used.size) {
                        0 -> "None"
                        1 -> used[0].name
                        else -> "${used.size} servers"
                    },
                    when {
                        used.isEmpty() -> "Choose which servers to use"
                        used.size > 1 -> used.joinToString { it.name }
                        used[0].local -> "Connected on your home network"
                        else -> "Connected over the internet"
                    },
                ) { plexDialog = PlexDialog.Servers }
            }
            item {
                SettingRow(
                    "Plex quality away from home",
                    if (s.plexRemoteKbps == 0) "Original" else "${s.plexRemoteKbps / 1000} Mbps",
                    "Lower uses less mobile data",
                ) {
                    picker = Picker("Plex quality away from home", listOf(0 to "Original quality", 20_000 to "20 Mbps (1080p)", 8_000 to "8 Mbps (1080p)", 4_000 to "4 Mbps (720p)", 2_000 to "2 Mbps (480p)").map { (k, label) ->
                        Choice(label, selected = s.plexRemoteKbps == k) { s.plexRemoteKbps = k }
                    })
                }
            }
        }

        item { SectionHeader("Playback") }
        item {
            ToggleRow("Keep playing in the background", s.backgroundAudio, "Sound carries on when you leave the app or lock the phone; controls appear in the notification") { s.backgroundAudio = it }
        }
        item { ToggleRow("Swipe up or down to change channel", s.swipeChannels, "In the full-screen player") { s.swipeChannels = it } }
        item { ToggleRow("Play the last channel when WiseTV opens", s.autoplayLast) { s.autoplayLast = it } }
        item {
            SettingRow("Live stream format", if (s.liveFormat == "ts") "MPEG-TS" else "HLS", "Xtream playlists only. Try the other one if channels stutter or won't start.") {
                picker = Picker("Live stream format", listOf("ts" to "MPEG-TS (default)", "m3u8" to "HLS").map { (k, label) ->
                    Choice(label, selected = s.liveFormat == k) {
                        if (s.liveFormat != k) {
                            s.liveFormat = k
                            g.sync.launchRefreshAll(force = true)
                            app.showToast("Updating channels to use $label…")
                        }
                    }
                })
            }
        }

        item { SectionHeader("About") }
        item {
            SettingRow(
                "WiseTV mobile ${BuildConfig.VERSION_NAME}",
                null,
                when (val u = update) {
                    Updater.State.Idle -> "Tap to check for updates"
                    Updater.State.Checking -> "Checking for updates…"
                    is Updater.State.UpToDate -> "Up to date (checked ${Fmt.time(u.checkedAt)})"
                    is Updater.State.Downloading -> "Downloading ${u.info.versionName}… ${u.percent}%"
                    is Updater.State.Ready -> "${u.info.versionName} is ready: tap to install"
                    is Updater.State.Failed -> u.message
                },
            ) {
                when (val u = update) {
                    is Updater.State.Ready -> g.updater.install(activity, u)
                    else -> app.scope.launch { g.updater.checkAndDownload(userAsked = true) }
                }
            }
        }
        item { ToggleRow("Update automatically", s.autoUpdate, "New versions download by themselves; you confirm the install") { s.autoUpdate = it } }
    }

    plexDialog?.let { PlexDialogs(app, it) { next -> plexDialog = next } }
    picker?.let { p -> ChoiceSheet(p.title, p.choices, onDismiss = { picker = null }) }
}
