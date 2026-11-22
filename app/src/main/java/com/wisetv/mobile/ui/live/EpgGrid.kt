package com.wisetv.mobile.ui.live

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.wisetv.mobile.data.db.ChannelEntity
import com.wisetv.mobile.data.db.ProgramEntity
import com.wisetv.mobile.player.PlaybackManager
import com.wisetv.mobile.ui.AppState
import com.wisetv.mobile.ui.Choice
import com.wisetv.mobile.ui.ChoiceSheet
import com.wisetv.mobile.ui.Fmt
import com.wisetv.mobile.ui.Pal
import com.wisetv.mobile.ui.ProgramMenu
import com.wisetv.mobile.ui.Screen
import com.wisetv.mobile.ui.guide.GuideState
import com.wisetv.mobile.ui.rememberNow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

private val ROW_H = 62.dp
private val CHANNEL_W = 86.dp
private val HEADER_H = 34.dp
private const val DP_PER_MIN = 3.4f
private const val MIN = GuideState.MIN
private const val HOUR = GuideState.HOUR
private const val DAY = GuideState.DAY

/**
 * The programme guide: channels down the left, time across. Swipe sideways to move through time
 * (every row moves together), tap what's on now to watch it, tap anything else for details.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpgGrid(app: AppState) {
    val g = app.g
    val st = app.guide
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val now by rememberNow(30_000)
    val playing by g.playback.nowPlaying.collectAsState()
    val playingId = (playing as? PlaybackManager.Source.Live)?.channel?.id

    // The timeline runs from an hour before the current half hour to the end of the guide data.
    val start = remember(st.playlistId, st.epgGen) { GuideState.floor30(System.currentTimeMillis()) - HOUR }
    val days = g.settings.epgDays.coerceIn(1, 14)
    val end = start + days * DAY + 2 * HOUR
    val pxPerMin = with(density) { DP_PER_MIN.dp.toPx() }
    fun xOf(t: Long): Float = (t - start) / MIN.toFloat() * pxPerMin
    val totalPx = xOf(end)
    val totalDp = with(density) { totalPx.toDp() }
    val hs = rememberScrollState(xOf(System.currentTimeMillis() - 20 * MIN).toInt().coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (st.row - 2).coerceAtLeast(0))
    var jumpSheet by remember { mutableStateOf(false) }

    fun scrollToTime(t: Long) {
        scope.launch { hs.animateScrollTo(xOf(t).toInt().coerceIn(0, hs.maxValue)) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportPx = with(density) { (maxWidth - CHANNEL_W).toPx() }.coerceAtLeast(1f)
        // Only programmes near the visible part are drawn; this changes once per screen-width of scrolling.
        val bucket by remember(viewportPx) { derivedStateOf { (hs.value / viewportPx).toInt() } }
        val rangeStart = (bucket - 1) * viewportPx
        val rangeEnd = (bucket + 3) * viewportPx
        val shownDay by remember(start) { derivedStateOf { Fmt.shortDay(start + (hs.value / pxPerMin).toLong() * MIN + 30 * MIN) } }

        Column(Modifier.fillMaxSize()) {
            // Time header.
            Row(Modifier.fillMaxWidth().height(HEADER_H).background(Pal.Panel)) {
                Box(
                    Modifier.width(CHANNEL_W).fillMaxHeight().clickable { jumpSheet = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$shownDay ▾", fontSize = 12.sp, color = Pal.Accent, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hs)) {
                    Box(Modifier.width(totalDp).fillMaxHeight()) {
                        var t = GuideState.floor30(start + (max(0f, rangeStart) / pxPerMin).toLong() * MIN)
                        val last = start + (rangeEnd / pxPerMin).toLong() * MIN
                        while (t <= min(last, end)) {
                            val x = xOf(t).toInt()
                            val midnight = isMidnight(t)
                            Text(
                                if (midnight) Fmt.shortDay(t) else Fmt.time(t),
                                fontSize = 12.sp,
                                color = if (midnight) Pal.Accent else Pal.Muted,
                                maxLines = 1,
                                modifier = Modifier.offset { IntOffset(x + 6, 0) }.align(Alignment.CenterStart),
                            )
                            t += 30 * MIN
                        }
                        Box(Modifier.offset { IntOffset(xOf(now).toInt(), 0) }.width(2.dp).fillMaxHeight().background(Pal.Accent))
                    }
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(st.channels, key = { it.id }) { ch ->
                    GuideRow(
                        app = app,
                        ch = ch,
                        hs = hs,
                        totalDp = totalDp,
                        xOf = ::xOf,
                        rangeStart = rangeStart,
                        rangeEnd = rangeEnd,
                        viewportPx = viewportPx,
                        now = now,
                        isPlaying = ch.id == playingId,
                    )
                }
            }
        }
    }

    if (jumpSheet) {
        val choices = buildList {
            add(Choice("Now") { scrollToTime(System.currentTimeMillis() - 20 * MIN) })
            val dayStart = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            for (d in 0 until days) {
                val base = dayStart + d * DAY
                for ((label, hour) in listOf("morning" to 8, "afternoon" to 13, "evening" to 19)) {
                    val t = base + hour * HOUR
                    if (t + 3 * HOUR < System.currentTimeMillis() || t > end) continue
                    add(Choice("${Fmt.day(t)} $label", subtitle = "From ${Fmt.time(t)}") { scrollToTime(t) })
                }
            }
        }
        ChoiceSheet("Jump to", choices, onDismiss = { jumpSheet = false })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideRow(
    app: AppState,
    ch: ChannelEntity,
    hs: ScrollState,
    totalDp: androidx.compose.ui.unit.Dp,
    xOf: (Long) -> Float,
    rangeStart: Float,
    rangeEnd: Float,
    viewportPx: Float,
    now: Long,
    isPlaying: Boolean,
) {
    val st = app.guide
    val density = LocalDensity.current
    LaunchedEffect(ch.epgId, st.epgGen) { st.ensureLoaded(ch) }
    val programs = st.programsOf(ch)
    val current = programs.firstOrNull { it.start <= now && it.stop > now }

    fun openMenu(p: ProgramEntity?) {
        app.programMenu = ProgramMenu(ch, p)
    }

    fun watch() {
        if (isPlaying) app.nav.push(Screen.Player) else app.tune(ch, fullscreen = false)
    }

    Row(Modifier.fillMaxWidth().height(ROW_H)) {
        // Channel cell.
        Column(
            Modifier
                .width(CHANNEL_W)
                .fillMaxHeight()
                .padding(1.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (isPlaying) Pal.AccentDim else Pal.Panel)
                .combinedClickable(onClick = { watch() }, onLongClick = { openMenu(current) })
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ChannelLogo(ch.logo, ch.number, Modifier.weight(1f).fillMaxWidth())
            Text(
                (if (app.isFavorite(ch)) "★ " else "") + ch.number.toString(),
                fontSize = 11.sp,
                color = if (isPlaying) Pal.Accent else Pal.Muted,
                maxLines = 1,
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hs)) {
            Box(Modifier.width(totalDp).fillMaxHeight()) {
                if (programs.isEmpty()) {
                    // No guide data: one cell that stays in view, named after the channel.
                    val w = with(density) { viewportPx.toDp() }
                    Box(
                        Modifier
                            .offset { IntOffset(hs.value, 0) }
                            .width(w)
                            .fillMaxHeight()
                            .padding(1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Pal.Panel2)
                            .combinedClickable(onClick = { watch() }, onLongClick = { openMenu(null) })
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column {
                            Text(ch.name, fontSize = 14.sp, color = Pal.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (ch.epgId.isEmpty()) "No guide for this channel" else "No programme information", fontSize = 11.sp, color = Pal.Muted, maxLines = 1)
                        }
                    }
                } else {
                    for (p in programs) {
                        val x0 = max(0f, xOf(p.start))
                        val x1 = xOf(p.stop)
                        if (x1 < rangeStart || x0 > rangeEnd || x1 <= x0) continue
                        ProgramCell(
                            p = p,
                            x0 = x0,
                            widthPx = x1 - x0,
                            hs = hs,
                            now = now,
                            highlight = isPlaying && p === current,
                            onClick = { if (p.start <= now && p.stop > now) watch() else openMenu(p) },
                            onLongClick = { openMenu(p) },
                        )
                    }
                }
                Box(Modifier.offset { IntOffset(xOf(now).toInt(), 0) }.width(2.dp).fillMaxHeight().background(Pal.Accent.copy(alpha = 0.8f)))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProgramCell(
    p: ProgramEntity,
    x0: Float,
    widthPx: Float,
    hs: ScrollState,
    now: Long,
    highlight: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val density = LocalDensity.current
    val airing = p.start <= now && p.stop > now
    val past = p.stop <= now
    val w = with(density) { widthPx.toDp() }
    val minText = with(density) { 70.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset(x0.toInt(), 0) }
            .width(w)
            .fillMaxHeight()
            .padding(1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    highlight -> Pal.AccentDim
                    airing -> Pal.Panel3
                    past -> Pal.Past
                    else -> Pal.Panel2
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        // The title slides along so it stays readable when the programme started off-screen.
        Column(
            Modifier
                .offset { IntOffset((hs.value - x0).coerceIn(0f, max(0f, widthPx - minText)).toInt(), 0) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                p.title,
                fontSize = 14.sp,
                color = if (past) Pal.Muted else Pal.Text,
                fontWeight = if (airing) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                Fmt.range(p.start, p.stop),
                fontSize = 11.sp,
                color = Pal.Muted,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        if (airing) {
            val frac = (now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(frac).height(2.dp).background(Pal.Accent))
        }
    }
}

private fun isMidnight(t: Long): Boolean {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
    return c.get(java.util.Calendar.HOUR_OF_DAY) == 0 && c.get(java.util.Calendar.MINUTE) == 0
}
