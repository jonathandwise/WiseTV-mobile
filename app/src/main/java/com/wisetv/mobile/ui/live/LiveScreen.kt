package com.wisetv.mobile.ui.live

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisetv.mobile.data.Settings
import com.wisetv.mobile.player.PlaybackManager
import com.wisetv.mobile.ui.AppState
import com.wisetv.mobile.ui.CenterMessage
import com.wisetv.mobile.ui.Choice
import com.wisetv.mobile.ui.ChoiceSheet
import com.wisetv.mobile.ui.Pal
import com.wisetv.mobile.ui.Pill
import com.wisetv.mobile.ui.Screen
import com.wisetv.mobile.ui.player.CastButton
import com.wisetv.mobile.ui.player.MiniPlayer

/**
 * Live TV: the playing channel at the top, then the guide (or a plain channel list) for the
 * chosen group. Tapping a channel plays it up top; tapping the picture goes full screen.
 */
@Composable
fun LiveScreen(app: AppState) {
    val g = app.g
    val st = app.guide
    val playlists by g.db.playlists().observeAll().collectAsState(initial = emptyList())
    val playlist = playlists.firstOrNull { it.id == app.playlistId }
    val now by g.playback.nowPlaying.collectAsState()
    val status by g.sync.status.collectAsState()
    var groupSheet by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Turning the phone sideways while a channel plays goes full screen.
    var wasLandscape by remember { mutableStateOf(landscape) }
    LaunchedEffect(landscape) {
        if (landscape && !wasLandscape && now is PlaybackManager.Source.Live) app.nav.push(Screen.Player)
        wasLandscape = landscape
    }

    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { groupSheet = true }) {
                Text("Live TV", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Pal.Text)
                Text(
                    status ?: (playlist?.name ?: ""),
                    fontSize = 12.sp,
                    color = if (status != null) Pal.Accent else Pal.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { g.settings.liveView = if (g.settings.liveView == "guide") "list" else "guide" }) {
                Icon(
                    if (g.settings.liveView == "guide") Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = if (g.settings.liveView == "guide") "Show as a list" else "Show the guide",
                    tint = Pal.Text,
                )
            }
            CastButton()
        }
    }

    val groupsRow: @Composable () -> Unit = {
        val fixed = listOf(Settings.ALL_GROUP to "All", Settings.FAV_GROUP to "★ Favorites")
        val custom = st.groupId !in setOf(Settings.ALL_GROUP, Settings.FAV_GROUP)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            item {
                Pill(
                    if (custom) st.groupTitle else "Groups",
                    selected = custom,
                    onClick = { groupSheet = true },
                    trailing = Icons.Filled.ArrowDropDown,
                )
            }
            items(fixed) { (id, label) ->
                Pill(label, selected = st.groupId == id, onClick = { st.selectGroup(id) })
            }
            // The first few groups for quick switching; the rest are in the Groups sheet.
            items(st.groups.take(12).filter { it.categoryId != st.groupId }) { grp ->
                Pill(grp.name, selected = false, onClick = { st.selectGroup(grp.categoryId) })
            }
        }
    }

    val body: @Composable () -> Unit = {
        when {
            playlist == null -> CenterMessage("Loading…")
            st.channels.isEmpty() && st.groupId == Settings.FAV_GROUP -> CenterMessage(
                "No favorite channels yet",
                "Press and hold a channel, then choose Add to favorites.",
            )
            st.channels.isEmpty() && status != null -> CenterMessage("Loading channels\u2026")
            st.channels.isEmpty() -> CenterMessage("No channels here", "Get the latest channel list from your provider.") {
                Pill("Update now", selected = true, onClick = { g.sync.launchRefreshAll(force = true) }, trailing = Icons.Filled.Refresh)
            }
            g.settings.liveView == "list" -> ChannelList(app)
            else -> EpgGrid(app)
        }
    }

    if (landscape) {
        Row(Modifier.fillMaxSize().background(Pal.Bg)) {
            Column(Modifier.width(320.dp).fillMaxHeight()) {
                header()
                if (now != null) MiniPlayer(app, Modifier.padding(horizontal = 12.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                groupsRow()
                Box(Modifier.weight(1f)) { body() }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().background(Pal.Bg)) {
            header()
            if (now != null) MiniPlayer(app, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            groupsRow()
            Box(Modifier.weight(1f)) { body() }
        }
    }

    if (groupSheet) {
        val choices = buildList {
            add(Choice("All channels", selected = st.groupId == Settings.ALL_GROUP) { st.selectGroup(Settings.ALL_GROUP) })
            add(Choice("★ Favorites", selected = st.groupId == Settings.FAV_GROUP) { st.selectGroup(Settings.FAV_GROUP) })
            st.groups.forEach { grp -> add(Choice(grp.name, selected = st.groupId == grp.categoryId) { st.selectGroup(grp.categoryId) }) }
            if (playlists.size > 1) {
                playlists.filter { it.id != app.playlistId }.forEach { p ->
                    add(Choice("Switch to ${p.name}", subtitle = "Playlist") { app.setActivePlaylist(p.id) })
                }
            }
            add(Choice("Show or hide groups…", subtitle = "Choose which groups appear here") { app.nav.push(Screen.ManageGroups(app.playlistId, com.wisetv.mobile.data.db.GroupKind.LIVE, false)) })
        }
        ChoiceSheet(playlist?.name ?: "Groups", choices, onDismiss = { groupSheet = false })
    }
}

/** Channel logo, or its number when there's no logo. */
@Composable
fun ChannelLogo(logo: String?, number: Int, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (logo != null) {
            coil.compose.AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(number.toString(), fontSize = 15.sp, color = Pal.Muted, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun Dot(modifier: Modifier = Modifier) {
    Spacer(modifier.size(6.dp).background(Pal.Accent, androidx.compose.foundation.shape.CircleShape))
}
