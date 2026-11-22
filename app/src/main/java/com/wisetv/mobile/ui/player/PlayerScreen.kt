package com.wisetv.mobile.ui.player

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import com.wisetv.mobile.L
import com.wisetv.mobile.data.db.ChannelEntity
import com.wisetv.mobile.player.PlaybackManager
import com.wisetv.mobile.player.PlayerSurface
import com.wisetv.mobile.ui.AppState
import com.wisetv.mobile.ui.Choice
import com.wisetv.mobile.ui.ChoiceSheet
import com.wisetv.mobile.ui.Fmt
import com.wisetv.mobile.ui.Pal
import com.wisetv.mobile.ui.ProgressLine
import com.wisetv.mobile.ui.Spinner
import com.wisetv.mobile.ui.findActivity
import com.wisetv.mobile.ui.live.ChannelList
import com.wisetv.mobile.ui.rememberNow
import kotlinx.coroutines.delay
import kotlin.math.abs

private val RESIZE_MODES = listOf(0 to "Fit", 4 to "Zoom (fill the screen)", 3 to "Stretch")
private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** Full-screen player: landscape, system bars hidden, touch controls. */
@Composable
fun PlayerScreen(app: AppState) {
    val pm = app.g.playback
    val src by pm.nowPlaying.collectAsState()
    val activity = LocalContext.current.findActivity()
    DisposableEffect(Unit) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val ctl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    when (val s = src) {
        null -> {
            Box(Modifier.fillMaxSize().background(Color.Black))
            LaunchedEffect(Unit) {
                delay(300)
                if (pm.nowPlaying.value == null) app.nav.pop()
            }
        }
        else -> FullPlayer(app, s)
    }
}

private enum class Sheet { MAIN, AUDIO, SUBTITLES, SIZE, SPEED }

@Composable
private fun FullPlayer(app: AppState, src: PlaybackManager.Source) {
    val g = app.g
    val pm = g.playback
    val density = LocalDensity.current
    val live = src as? PlaybackManager.Source.Live
    val vod = src as? PlaybackManager.Source.Vod
    val casting by g.cast.active.collectAsState()
    val castName by g.cast.deviceName.collectAsState()
    val buffering by rememberBuffering(pm.player)
    val message by rememberPlaybackMessage(pm)

    var controls by remember { mutableStateOf(true) }
    var touchTick by remember { mutableIntStateOf(0) }
    var resize by remember { mutableIntStateOf(g.settings.resizeMode) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var channelPanel by remember { mutableStateOf(false) }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    var upNext by remember { mutableIntStateOf(-1) }

    var playing by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }

    fun poke() {
        controls = true
        touchTick++
    }

    // Poll position and state (works the same while casting).
    LaunchedEffect(src) {
        while (true) {
            playing = pm.isPlaying || (!g.cast.active.value && pm.player.playWhenReady && pm.player.playbackState != androidx.media3.common.Player.STATE_IDLE)
            pos = pm.positionMs()
            dur = pm.durationMs()
            delay(400)
        }
    }
    // Controls hide after a few seconds, unless paused or a panel is open.
    LaunchedEffect(touchTick, controls, playing, sheet, channelPanel) {
        if (controls && playing && sheet == null && !channelPanel) {
            delay(4_000)
            controls = false
        }
    }
    LaunchedEffect(gestureHint) {
        if (gestureHint != null) {
            delay(900)
            gestureHint = null
        }
    }
    LaunchedEffect(live?.channel?.id) { if (live != null) poke() }
    // End of a title: next episode after a short countdown, or back out.
    LaunchedEffect(Unit) {
        pm.events.collect { ev ->
            if (ev is PlaybackManager.Event.Ended) {
                if (pm.hasNextInQueue()) upNext = 8 else app.nav.pop()
            }
        }
    }
    LaunchedEffect(upNext) {
        if (upNext > 0) {
            delay(1_000)
            upNext--
        } else if (upNext == 0) {
            upNext = -1
            pm.playNextInQueue()
        }
    }
    LaunchedEffect(src) { upNext = -1 }

    BackHandler(enabled = channelPanel) { channelPanel = false }

    fun stepChannel(dir: Int) {
        pm.playNextChannel(dir, app.guide.channels)
        poke()
    }

    fun seekBy(deltaMs: Long) {
        val target = (pm.positionMs() + deltaMs).coerceIn(0, if (dur > 0) dur - 1_000 else Long.MAX_VALUE)
        pm.seekTo(target)
        pos = target
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (casting) {
            CastingBackdrop(src, castName, Modifier.fillMaxSize())
        } else {
            PlayerSurface(pm.player, Modifier.fillMaxSize(), resize)
        }

        // Gestures: tap shows/hides the controls; double-tap the sides skips; swipe up/down changes
        // channel (live); swipe sideways seeks (movies and episodes).
        var dragX by remember { mutableFloatStateOf(0f) }
        var dragY by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(src::class, casting) {
                    detectTapGestures(
                        onTap = { if (controls) controls = false else poke() },
                        onDoubleTap = { off ->
                            if (vod != null) {
                                val fwd = off.x > size.width / 2
                                seekBy(if (fwd) 10_000 else -10_000)
                                gestureHint = if (fwd) "+10 s" else "−10 s"
                            } else {
                                resize = if (resize == 0) 4 else 0
                                g.settings.resizeMode = resize
                                gestureHint = if (resize == 0) "Fit" else "Zoom"
                            }
                        },
                    )
                }
                .pointerInput(src::class, casting) {
                    val threshold = with(density) { 70.dp.toPx() }
                    detectDragGestures(
                        onDragStart = {
                            dragX = 0f
                            dragY = 0f
                        },
                        onDragEnd = {
                            val horizontal = abs(dragX) > abs(dragY)
                            if (live != null && !horizontal && abs(dragY) > threshold && g.settings.swipeChannels) {
                                stepChannel(if (dragY < 0) 1 else -1)
                            } else if (vod != null && horizontal && scrubbing != null) {
                                pm.seekTo(scrubbing!!.toLong())
                                pos = scrubbing!!.toLong()
                            }
                            scrubbing = null
                        },
                        onDragCancel = { scrubbing = null },
                        onDrag = { change, amount ->
                            change.consume()
                            dragX += amount.x
                            dragY += amount.y
                            if (vod != null && dur > 0 && abs(dragX) > abs(dragY)) {
                                // A full screen-width swipe moves 3 minutes.
                                val delta = dragX / size.width * 180_000f
                                val target = (pm.positionMs() + delta).coerceIn(0f, dur.toFloat())
                                scrubbing = target
                                gestureHint = "${if (delta >= 0) "+" else "−"}${Fmt.clock(abs(delta).toLong())}  →  ${Fmt.clock(target.toLong())}"
                                controls = true
                            }
                        },
                    )
                },
        )

        if (buffering && !casting) Spinner(Modifier.align(Alignment.Center))

        gestureHint?.let {
            Text(
                it,
                fontSize = 18.sp,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Pal.Scrim, RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        message?.let {
            Text(
                it,
                fontSize = 15.sp,
                color = Pal.Text,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp, start = 40.dp, end = 40.dp)
                    .background(Pal.Scrim, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Top bar.
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { app.nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        val (t, sub) = when {
                            live != null -> "${live.channel.number}  ${live.channel.name}" to (if (casting) "Playing on ${castName ?: "Chromecast"}" else null)
                            vod != null -> vod.item.title to (vod.item.subtitle ?: if (casting) "Playing on ${castName ?: "Chromecast"}" else null)
                            else -> "" to null
                        }
                        Text(t, fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (sub != null) Text(sub, fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (live != null) {
                        val fav = app.isFavorite(live.channel)
                        IconButton(onClick = { app.toggleFavorite(live.channel); poke() }) {
                            Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, if (fav) "Remove from favorites" else "Add to favorites", tint = if (fav) Pal.Warn else Color.White)
                        }
                    }
                    CastButton()
                    IconButton(onClick = { sheet = Sheet.MAIN }) { Icon(Icons.Filled.Settings, "Options", tint = Color.White) }
                }

                // Centre controls.
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    if (live != null) {
                        RoundButton(Icons.Filled.SkipPrevious, "Previous channel", 52.dp) { stepChannel(-1) }
                    } else {
                        RoundButton(Icons.Filled.Replay10, "Back 10 seconds", 52.dp) { seekBy(-10_000); poke() }
                    }
                    RoundButton(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", 72.dp) {
                        pm.togglePause()
                        playing = !playing
                        poke()
                    }
                    if (live != null) {
                        RoundButton(Icons.Filled.SkipNext, "Next channel", 52.dp) { stepChannel(1) }
                    } else {
                        RoundButton(Icons.Filled.Forward10, "Forward 10 seconds", 52.dp) { seekBy(10_000); poke() }
                    }
                }

                // Bottom: programme info (live) or the seek bar (movies, episodes).
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 10.dp),
                ) {
                    if (live != null) {
                        LiveInfo(app, live.channel, onChannels = { channelPanel = true; poke() }, onPrevious = {
                            pm.previousChannel?.let { app.tune(it, fullscreen = true) }
                            poke()
                        })
                    } else if (vod != null) {
                        val shown = scrubbing?.toLong() ?: pos
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Fmt.clock(shown), fontSize = 13.sp, color = Color.White, modifier = Modifier.width(64.dp))
                            Slider(
                                value = if (dur > 0) (shown.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                                onValueChange = { f ->
                                    scrubbing = f * dur
                                    poke()
                                },
                                onValueChangeFinished = {
                                    scrubbing?.let { pm.seekTo(it.toLong()); pos = it.toLong() }
                                    scrubbing = null
                                },
                                enabled = dur > 0,
                                colors = SliderDefaults.colors(thumbColor = Pal.Accent, activeTrackColor = Pal.Accent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                                modifier = Modifier.weight(1f),
                            )
                            Text(if (dur > 0) Fmt.clock(dur) else "--:--", fontSize = 13.sp, color = Color.White, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                        }
                        if (pm.hasNextInQueue()) {
                            val next = pm.queue.getOrNull(pm.queueIndex + 1)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { pm.playNextInQueue() }) {
                                    Icon(Icons.Filled.SkipNext, null, tint = Color.White)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Next: ${next?.subtitle ?: next?.title ?: "episode"}", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (upNext > 0) {
            val next = pm.queue.getOrNull(pm.queueIndex + 1)
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .width(300.dp)
                    .background(Pal.Panel2, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Text("Up next in $upNext", fontSize = 13.sp, color = Pal.Accent)
                Text(next?.subtitle ?: next?.title ?: "", fontSize = 16.sp, color = Pal.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { upNext = -1; app.nav.pop() }) { Text("Cancel") }
                    TextButton(onClick = { upNext = 0 }) { Text("Play now") }
                }
            }
        }

        if (channelPanel) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable { channelPanel = false })
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(380.dp)
                    .fillMaxHeight()
                    .background(Pal.Bg.copy(alpha = 0.96f)),
            ) {
                Text(app.guide.groupTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Pal.Text, modifier = Modifier.padding(16.dp))
                ChannelList(app, Modifier.weight(1f), onPicked = { channelPanel = false })
            }
        }
    }

    sheet?.let { which -> OptionsSheet(app, which, vod != null, resize, casting, onResize = { resize = it; g.settings.resizeMode = it }, onSheet = { sheet = it }) }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).background(Color(0x66000000), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
private fun LiveInfo(app: AppState, ch: ChannelEntity, onChannels: () -> Unit, onPrevious: () -> Unit) {
    val st = app.guide
    val now by rememberNow(15_000)
    LaunchedEffect(ch.epgId, st.epgGen) { st.ensureLoaded(ch) }
    val list = st.programsOf(ch)
    val cur = list.firstOrNull { it.start <= now && it.stop > now }
    val next = list.firstOrNull { it.start >= (cur?.stop ?: now) }
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(cur?.title ?: "No programme information", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (cur != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text(Fmt.range(cur.start, cur.stop), fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
                    Spacer(Modifier.width(10.dp))
                    ProgressLine((now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1), Modifier.width(160.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("${((cur.stop - now) / 60_000).coerceAtLeast(0)} min left", fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f))
                }
            }
            if (next != null) {
                Text("Next  ${Fmt.time(next.start)}  ${next.title}", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
        if (app.g.playback.previousChannel != null) {
            IconButton(onClick = onPrevious) { Icon(Icons.Filled.History, "Previous channel", tint = Color.White) }
        }
        TextButton(onClick = onChannels) {
            Icon(Icons.AutoMirrored.Filled.List, null, tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Text("Channels", color = Color.White)
        }
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun OptionsSheet(
    app: AppState,
    which: Sheet,
    isVod: Boolean,
    resize: Int,
    casting: Boolean,
    onResize: (Int) -> Unit,
    onSheet: (Sheet?) -> Unit,
) {
    val pm = app.g.playback
    val close = { onSheet(null) }
    when (which) {
        Sheet.MAIN -> ChoiceSheet(
            "Options",
            buildList {
                if (!casting) {
                    add(Choice("Audio", subtitle = pm.trackOptions(C.TRACK_TYPE_AUDIO).firstOrNull { it.selected }?.label ?: "Default") { onSheet(Sheet.AUDIO) })
                    add(Choice("Subtitles", subtitle = pm.trackOptions(C.TRACK_TYPE_TEXT).firstOrNull { it.selected }?.label ?: "Off") { onSheet(Sheet.SUBTITLES) })
                    add(Choice("Picture size", subtitle = RESIZE_MODES.firstOrNull { it.first == resize }?.second ?: "Fit") { onSheet(Sheet.SIZE) })
                    if (isVod) add(Choice("Playback speed", subtitle = "${pm.speed}×") { onSheet(Sheet.SPEED) })
                }
                if (casting) add(Choice("Stop casting", subtitle = "Carry on watching on the phone") { app.g.cast.disconnect() })
            },
            onDismiss = close,
        )
        Sheet.AUDIO -> {
            val opts = pm.trackOptions(C.TRACK_TYPE_AUDIO)
            ChoiceSheet(
                "Audio",
                if (opts.isEmpty()) listOf(Choice("Only one audio track") {}) else opts.map { o -> Choice(o.label, selected = o.selected) { pm.selectTrack(C.TRACK_TYPE_AUDIO, o) } },
                onDismiss = close,
            )
        }
        Sheet.SUBTITLES -> {
            val opts = pm.trackOptions(C.TRACK_TYPE_TEXT)
            ChoiceSheet(
                "Subtitles",
                opts.map { o -> Choice(o.label, selected = o.selected) { pm.selectTrack(C.TRACK_TYPE_TEXT, o) } },
                onDismiss = close,
            )
        }
        Sheet.SIZE -> ChoiceSheet(
            "Picture size",
            RESIZE_MODES.map { (mode, label) -> Choice(label, selected = mode == resize) { onResize(mode); L.i("resize $label") } },
            onDismiss = close,
        )
        Sheet.SPEED -> ChoiceSheet(
            "Playback speed",
            SPEEDS.map { sp -> Choice(if (sp == 1f) "Normal" else "$sp×", selected = pm.speed == sp) { pm.speed = sp } },
            onDismiss = close,
        )
    }
}
