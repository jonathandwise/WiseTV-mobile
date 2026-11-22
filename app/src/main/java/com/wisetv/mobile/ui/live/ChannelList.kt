package com.wisetv.mobile.ui.live

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisetv.mobile.data.db.ChannelEntity
import com.wisetv.mobile.player.PlaybackManager
import com.wisetv.mobile.ui.AppState
import com.wisetv.mobile.ui.Fmt
import com.wisetv.mobile.ui.Pal
import com.wisetv.mobile.ui.ProgramMenu
import com.wisetv.mobile.ui.ProgressLine
import com.wisetv.mobile.ui.Screen
import com.wisetv.mobile.ui.rememberNow

/** Channels with what's on now and next: the simpler alternative to the guide grid. */
@Composable
fun ChannelList(app: AppState, modifier: Modifier = Modifier, onPicked: (() -> Unit)? = null) {
    val st = app.guide
    val now by rememberNow(30_000)
    val playing by app.g.playback.nowPlaying.collectAsState()
    val playingId = (playing as? PlaybackManager.Source.Live)?.channel?.id
    val start = st.channels.indexOfFirst { it.id == playingId }.takeIf { it >= 0 } ?: st.row
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    LazyColumn(state = listState, modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        items(st.channels, key = { it.id }) { ch ->
            ChannelRow(app, ch, now, ch.id == playingId) {
                if (onPicked != null) {
                    app.tune(ch, fullscreen = false)
                    onPicked()
                } else if (ch.id == playingId) {
                    app.nav.push(Screen.Player)
                } else {
                    app.tune(ch, fullscreen = false)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(app: AppState, ch: ChannelEntity, now: Long, isPlaying: Boolean, onClick: () -> Unit) {
    val st = app.guide
    LaunchedEffect(ch.epgId, st.epgGen) { st.ensureLoaded(ch) }
    val programs = st.programsOf(ch)
    val cur = programs.firstOrNull { it.start <= now && it.stop > now }
    val next = programs.firstOrNull { it.start >= (cur?.stop ?: now) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isPlaying) Pal.AccentDim.copy(alpha = 0.6f) else Pal.Bg)
            .combinedClickable(onClick = onClick, onLongClick = { app.programMenu = ProgramMenu(ch, cur) })
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelLogo(
            ch.logo,
            ch.number,
            Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(6.dp)).background(Pal.Panel2).padding(4.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${ch.number}  ${ch.name}",
                    fontSize = 13.sp,
                    color = if (isPlaying) Pal.Accent else Pal.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (app.isFavorite(ch)) Text("  ★", fontSize = 12.sp, color = Pal.Warn)
            }
            Text(
                cur?.title ?: ch.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Pal.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (cur != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    ProgressLine((now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1), Modifier.width(70.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        next?.let { "Next ${Fmt.time(it.start)} · ${it.title}" } ?: "Until ${Fmt.time(cur.stop)}",
                        fontSize = 12.sp,
                        color = Pal.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
