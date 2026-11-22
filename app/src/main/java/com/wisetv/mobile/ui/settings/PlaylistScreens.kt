package com.wisetv.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisetv.mobile.data.db.GroupKind
import com.wisetv.mobile.data.db.PlaylistEntity
import com.wisetv.mobile.data.db.PlaylistType
import com.wisetv.mobile.ui.AppState
import com.wisetv.mobile.ui.CenterMessage
import com.wisetv.mobile.ui.Choice
import com.wisetv.mobile.ui.ChoiceSheet
import com.wisetv.mobile.ui.Fmt
import com.wisetv.mobile.ui.Pal
import com.wisetv.mobile.ui.Pill
import com.wisetv.mobile.ui.Screen
import com.wisetv.mobile.ui.Section
import com.wisetv.mobile.ui.TopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun describe(p: PlaylistEntity): String = listOfNotNull(
    if (p.type == PlaylistType.XTREAM) "Xtream Codes" else "M3U",
    if (p.expiresAt > 0) "expires ${Fmt.date(p.expiresAt)}" else null,
    if (p.type == PlaylistType.XTREAM) "${p.maxConnections} connection${if (p.maxConnections == 1) "" else "s"}" else null,
    if (p.lastSync > 0) "updated ${Fmt.day(p.lastSync)}" else "not loaded yet",
).joinToString("  ·  ")

// ============================================================================ playlists

@Composable
fun PlaylistsScreen(app: AppState) {
    val g = app.g
    val playlists by g.db.playlists().observeAll().collectAsState(initial = emptyList())
    var open by remember { mutableStateOf<PlaylistEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<PlaylistEntity?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding()) {
        TopBar("Playlists", onBack = { app.nav.pop() }) {
            IconButton(onClick = { app.nav.push(Screen.AddPlaylist) }) { Icon(Icons.Filled.Add, "Add playlist", tint = Pal.Accent) }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(playlists, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { open = p }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (p.id == app.playlistId) Pal.Accent else Pal.Text)
                        Text(describe(p), fontSize = 12.sp, color = Pal.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (p.id == app.playlistId) Icon(Icons.Filled.CheckCircle, "In use", tint = Pal.Accent)
                }
            }
        }
    }

    open?.let { p ->
        ChoiceSheet(
            p.name,
            buildList {
                if (p.id != app.playlistId) {
                    add(Choice("Use this playlist", icon = Icons.Filled.CheckCircle) {
                        app.setActivePlaylist(p.id)
                        app.nav.resetTo(Screen.Main(Section.LIVE))
                    })
                }
                add(Choice("Update now", icon = Icons.Filled.Refresh) {
                    scope.launch {
                        try {
                            g.sync.syncPlaylist(p.id, withEpg = true)
                            app.showToast("${p.name} updated")
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            app.showToast("Update failed: ${e.message}")
                        }
                    }
                })
                add(Choice("Edit", icon = Icons.Filled.Edit) { app.nav.push(Screen.EditPlaylist(p.id)) })
                add(Choice("Delete", danger = true, icon = Icons.Filled.Delete) { confirmDelete = p })
            },
            onDismiss = { open = null },
            subtitle = describe(p),
        )
    }
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Pal.Panel2,
            title = { Text("Delete ${p.name}?") },
            text = { Text("Its channels, guide, favorites and watch progress are removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        val cur = g.playback.nowPlaying.value
                        if (cur != null) g.playback.stop()
                        g.repo.deletePlaylist(p.id)
                        val next = g.settings.activePlaylistId
                        if (next > 0) app.setActivePlaylist(next)
                        app.showToast("Playlist deleted")
                    }
                }) { Text("Delete", color = Pal.Rec) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

// ============================================================================ groups

@Composable
fun ManageGroupsScreen(app: AppState, pid: Long, kind: String, firstRun: Boolean) {
    val g = app.g
    val scope = rememberCoroutineScope()
    val groups by remember(pid, kind) { g.db.groups().observe(pid, kind) }.collectAsState(initial = emptyList())
    val counts by produceState(emptyMap<String, Int>(), pid, kind) {
        value = if (kind == GroupKind.LIVE) g.db.channels().groupCounts(pid).associate { it.name to it.cnt } else emptyMap()
    }
    var changed by remember { mutableStateOf(false) }

    fun done() {
        if (kind == GroupKind.LIVE && (firstRun || changed)) g.sync.launchEpg(pid)
        if (firstRun) {
            app.nav.resetTo(Screen.Main(Section.LIVE))
            app.showToast("Loading the TV guide in the background…")
        } else {
            app.nav.pop()
        }
    }

    val what = when (kind) {
        GroupKind.LIVE -> "channel groups"
        GroupKind.MOVIE -> "movie categories"
        else -> "series categories"
    }
    val visible = groups.count { !it.hidden }

    Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding()) {
        TopBar(if (firstRun) "Choose your groups" else "Show or hide $what", onBack = if (firstRun) null else ({ done() })) {
            TextButton(onClick = { done() }) { Text("Done", color = Pal.Accent, fontWeight = FontWeight.SemiBold) }
        }
        Text(
            if (firstRun) "Untick the groups you don't watch: the guide loads faster and takes less space. You can change this later in Settings."
            else "$visible of ${groups.size} shown.",
            fontSize = 13.sp,
            color = Pal.Muted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Show all", selected = false, onClick = { changed = true; scope.launch { g.db.groups().setAllHidden(pid, kind, false) } })
            Pill("Hide all", selected = false, onClick = { changed = true; scope.launch { g.db.groups().setAllHidden(pid, kind, true) } })
        }
        if (groups.isEmpty()) {
            CenterMessage("Nothing here yet")
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(groups, key = { it.categoryId }) { grp ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                changed = true
                                scope.launch { g.db.groups().setHidden(pid, kind, grp.categoryId, !grp.hidden) }
                            }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = !grp.hidden,
                            onCheckedChange = {
                                changed = true
                                scope.launch { g.db.groups().setHidden(pid, kind, grp.categoryId, !it) }
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Pal.Accent, checkmarkColor = Color.Black),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(grp.name, fontSize = 15.sp, color = Pal.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        counts[grp.categoryId]?.let { Text("$it", fontSize = 12.sp, color = Pal.Muted, modifier = Modifier.padding(end = 8.dp)) }
                    }
                }
            }
        }
    }
}

// ============================================================================ hidden channels

@Composable
fun HiddenChannelsScreen(app: AppState, pid: Long) {
    val g = app.g
    val scope = rememberCoroutineScope()
    val hidden by remember(pid) { g.db.channels().observeHidden(pid) }.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding()) {
        TopBar("Hidden channels", onBack = { app.nav.pop() }, subtitle = "Tap a channel to show it again")
        if (hidden.isEmpty()) {
            CenterMessage("No hidden channels", "Hide a channel by pressing and holding it in Live TV.")
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(hidden, key = { it.id }) { ch ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            scope.launch { g.db.channels().setHidden(ch.id, false) }
                            app.showToast("${ch.name} is back in Live TV")
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${ch.number}", fontSize = 13.sp, color = Pal.Muted, modifier = Modifier.width(52.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, fontSize = 15.sp, color = Pal.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(ch.groupName, fontSize = 12.sp, color = Pal.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
