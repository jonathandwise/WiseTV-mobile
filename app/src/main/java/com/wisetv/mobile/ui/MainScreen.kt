package com.wisetv.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.wisetv.mobile.data.db.GroupKind
import com.wisetv.mobile.player.PlaybackManager
import com.wisetv.mobile.ui.live.LiveScreen
import com.wisetv.mobile.ui.search.SearchScreen
import com.wisetv.mobile.ui.settings.SettingsScreen
import com.wisetv.mobile.ui.vod.VodBrowser
import kotlinx.coroutines.delay

private fun Section.icon(): ImageVector = when (this) {
    Section.LIVE -> Icons.Filled.LiveTv
    Section.MOVIES -> Icons.Filled.Movie
    Section.SERIES -> Icons.Filled.VideoLibrary
    Section.SEARCH -> Icons.Filled.Search
    Section.SETTINGS -> Icons.Filled.Settings
}

/** The tabs: content on top, a "now playing" bar when something plays off-screen, and the bottom navigation. */
@Composable
fun MainScreen(app: AppState, section: Section) {
    val holder = rememberSaveableStateHolder()
    val now by app.g.playback.nowPlaying.collectAsState()
    Column(Modifier.fillMaxSize().background(Pal.Bg)) {
        Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
            holder.SaveableStateProvider(section.name) {
                when (section) {
                    Section.LIVE -> LiveScreen(app)
                    Section.MOVIES -> VodBrowser(app, GroupKind.MOVIE)
                    Section.SERIES -> VodBrowser(app, GroupKind.SERIES)
                    Section.SEARCH -> SearchScreen(app)
                    Section.SETTINGS -> SettingsScreen(app)
                }
            }
        }
        // On the Live TV tab the picture itself is shown; elsewhere a compact bar.
        val src = now
        if (src != null && section != Section.LIVE) NowPlayingBar(app, src)
        NavigationBar(containerColor = Pal.Panel, tonalElevation = 0.dp) {
            for (s in Section.entries) {
                NavigationBarItem(
                    selected = s == section,
                    onClick = { if (s != section) app.nav.replaceTop(Screen.Main(s)) },
                    icon = { Icon(s.icon(), contentDescription = null) },
                    label = { Text(s.label, fontSize = 11.sp, maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = Pal.Accent,
                        indicatorColor = Pal.Accent,
                        unselectedIconColor = Pal.Muted,
                        unselectedTextColor = Pal.Muted,
                    ),
                )
            }
        }
    }
}

/** What's playing, with play/pause and stop; tap to open the player. */
@Composable
fun NowPlayingBar(app: AppState, src: PlaybackManager.Source) {
    val pm = app.g.playback
    val casting by app.g.cast.active.collectAsState()
    val castName by app.g.cast.deviceName.collectAsState()
    var playing by remember { mutableStateOf(pm.isPlaying) }
    LaunchedEffect(src) {
        while (true) {
            playing = pm.isPlaying
            delay(500)
        }
    }
    val (title, sub, image) = when (src) {
        is PlaybackManager.Source.Live -> Triple(src.channel.name, "Live TV", src.channel.logo)
        is PlaybackManager.Source.Vod -> Triple(src.item.title, src.item.subtitle, src.item.poster)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Pal.Panel2)
            .clickable { app.nav.push(Screen.Player) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 56.dp, height = 40.dp).clip(RoundedCornerShape(6.dp)).background(Pal.Panel3), contentAlignment = Alignment.Center) {
            if (image != null) {
                AsyncImage(image, null, contentScale = if (src is PlaybackManager.Source.Live) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(if (src is PlaybackManager.Source.Live) 4.dp else 0.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Pal.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = if (casting) "Playing on ${castName ?: "Chromecast"}" else sub
            if (!line.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (casting) {
                        Icon(Icons.Filled.Cast, null, tint = Pal.Accent, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(line, fontSize = 12.sp, color = if (casting) Pal.Accent else Pal.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        IconButton(onClick = { pm.togglePause(); playing = !playing }) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = Pal.Text)
        }
        IconButton(onClick = { pm.stop() }) {
            Icon(Icons.Filled.Close, contentDescription = "Stop", tint = Pal.Muted)
        }
    }
    Spacer(Modifier.height(0.dp))
}
