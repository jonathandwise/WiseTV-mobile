package com.wisetv.mobile.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.wisetv.mobile.player.PlaybackManager
import com.wisetv.mobile.player.PlayerSurface
import com.wisetv.mobile.ui.AppState
import com.wisetv.mobile.ui.Pal
import com.wisetv.mobile.ui.Screen
import com.wisetv.mobile.ui.Spinner
import com.wisetv.mobile.ui.rememberNow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** True while the player is waiting for data. */
@Composable
fun rememberBuffering(player: Player): State<Boolean> {
    val state = remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.value = playbackState == Player.STATE_BUFFERING
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    return state
}

/** The latest playback error, cleared after a while. */
@Composable
fun rememberPlaybackMessage(pm: PlaybackManager): State<String?> {
    val msg = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        pm.events.collect { ev ->
            if (ev is PlaybackManager.Event.Error) {
                msg.value = ev.message
                launch {
                    delay(if (ev.everywhere) 20_000 else 6_000)
                    if (msg.value == ev.message) msg.value = null
                }
            }
        }
    }
    val src by pm.nowPlaying.collectAsState()
    LaunchedEffect(src) { msg.value = null }
    return msg
}

/** The picture at the top of the Live TV tab. Tap it for full screen. */
@Composable
fun MiniPlayer(app: AppState, modifier: Modifier = Modifier) {
    val pm = app.g.playback
    val src by pm.nowPlaying.collectAsState()
    val s = src ?: return
    val casting by app.g.cast.active.collectAsState()
    val castName by app.g.cast.deviceName.collectAsState()
    val buffering by rememberBuffering(pm.player)
    val message by rememberPlaybackMessage(pm)
    var playing by remember { mutableStateOf(true) }
    LaunchedEffect(s) {
        while (true) {
            playing = pm.isPlaying || pm.player.playWhenReady && !app.g.cast.active.value
            delay(500)
        }
    }
    val now by rememberNow(30_000)
    val (title, line) = when (s) {
        is PlaybackManager.Source.Live -> {
            val ch = s.channel
            LaunchedEffect(ch.epgId, app.guide.epgGen) { app.guide.ensureLoaded(ch) }
            "${ch.number}  ${ch.name}" to (app.guide.programAt(ch, now)?.title ?: "Live")
        }
        is PlaybackManager.Source.Vod -> s.item.title to (s.item.subtitle ?: "")
    }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black),
    ) {
        if (casting) {
            CastingBackdrop(s, castName, Modifier.fillMaxSize())
        } else {
            PlayerSurface(pm.player, Modifier.fillMaxSize())
            if (buffering) Spinner(Modifier.align(Alignment.Center), size = 32.dp)
        }
        // Touches go to a layer above the video view, so the tap always lands.
        Box(Modifier.fillMaxSize().clickable { app.nav.push(Screen.Player) })
        message?.let {
            Text(
                it,
                fontSize = 13.sp,
                color = Pal.Text,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(16.dp).background(Pal.Scrim, RoundedCornerShape(8.dp)).padding(10.dp),
            )
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(start = 12.dp, end = 2.dp, top = 18.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, color = Pal.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(line, fontSize = 14.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { pm.togglePause(); playing = !playing }) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = Color.White)
            }
            IconButton(onClick = { app.nav.push(Screen.Player) }) {
                Icon(Icons.Filled.Fullscreen, contentDescription = "Full screen", tint = Color.White)
            }
            IconButton(onClick = { pm.stop() }) {
                Icon(Icons.Filled.Close, contentDescription = "Stop", tint = Color.White)
            }
        }
    }
}

/** Shown instead of the picture while casting: the artwork and where it's playing. */
@Composable
fun CastingBackdrop(src: PlaybackManager.Source, deviceName: String?, modifier: Modifier = Modifier) {
    val image = when (src) {
        is PlaybackManager.Source.Live -> src.channel.logo
        is PlaybackManager.Source.Vod -> src.item.poster
    }
    Box(modifier.background(Pal.Panel), contentAlignment = Alignment.Center) {
        if (image != null) {
            AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.18f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Cast, null, tint = Pal.Accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("Playing on ${deviceName ?: "Chromecast"}", fontSize = 16.sp, color = Pal.Text)
        }
    }
}
