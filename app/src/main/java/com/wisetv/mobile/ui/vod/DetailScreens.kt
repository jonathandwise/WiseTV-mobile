package com.wisetv.mobile.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.wisetv.mobile.data.db.FavoriteEntity
import com.wisetv.mobile.data.db.PlaylistEntity
import com.wisetv.mobile.data.db.PlaylistType
import com.wisetv.mobile.data.db.SeriesEntity
import com.wisetv.mobile.data.db.VodEntity
import com.wisetv.mobile.data.xtream.XtreamApi
import com.wisetv.mobile.player.PlaybackManager
import com.wisetv.mobile.ui.AppState
import com.wisetv.mobile.ui.CenterMessage
import com.wisetv.mobile.ui.Fmt
import com.wisetv.mobile.ui.Pal
import com.wisetv.mobile.ui.Pill
import com.wisetv.mobile.ui.ProgressLine
import com.wisetv.mobile.ui.player.CastButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================ shared layout

/**
 * Title page layout: a backdrop with the poster and title over it, then the page's own rows.
 * Back and the Chromecast button float on top.
 */
@Composable
fun DetailPage(
    app: AppState,
    backdrop: String?,
    poster: String?,
    title: String,
    meta: String,
    note: String? = null,
    content: LazyListScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(250.dp)) {
                    if (backdrop != null) {
                        AsyncImage(backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.45f))
                    }
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x660D1015), Pal.Bg))))
                    Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
                        Box(Modifier.width(104.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Pal.Panel2)) {
                            if (poster != null) AsyncImage(poster, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f).padding(bottom = 4.dp)) {
                            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Pal.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (meta.isNotEmpty()) Text(meta, fontSize = 13.sp, color = Pal.Muted, modifier = Modifier.padding(top = 4.dp))
                            if (note != null) Text(note, fontSize = 12.sp, color = Pal.Accent, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
            content()
        }
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { app.nav.pop() }, modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(50))) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            CastButton()
        }
    }
}

@Composable
fun PlayButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Pal.Accent, contentColor = Color.Black),
        modifier = modifier,
    ) {
        Icon(Icons.Filled.PlayArrow, null)
        Spacer(Modifier.width(6.dp))
        Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        if (icon != null) {
            Icon(icon, null, tint = Pal.Text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = Pal.Text, maxLines = 1)
    }
}

@Composable
fun ResumeBar(position: Long, duration: Long) {
    if (position < 60_000 || duration <= 0) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        ProgressLine(position.toFloat() / duration, Modifier.width(160.dp))
        Text("  ${Fmt.duration(duration - position)} left", fontSize = 13.sp, color = Pal.Muted)
    }
}

@Composable
fun BodyText(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrBlank()) return
    Text(text, fontSize = 15.sp, color = Pal.Text.copy(alpha = 0.9f), modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

/** One episode in a list. */
@Composable
fun EpisodeRow(number: String, title: String, plot: String?, image: String?, right: String, progress: Float?, watched: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Pal.Panel2), contentAlignment = Alignment.Center) {
            if (image != null) {
                AsyncImage(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(number, fontSize = 15.sp, color = Pal.Muted, fontWeight = FontWeight.SemiBold)
            }
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("$number  ·  $title", fontSize = 15.sp, color = Pal.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!plot.isNullOrBlank()) Text(plot, fontSize = 12.sp, color = Pal.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (watched) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Pal.Good, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(if (watched) "Watched" else right, fontSize = 12.sp, color = if (watched) Pal.Good else Pal.Muted)
            }
        }
    }
}

// ============================================================================ movie

@Composable
fun MovieDetailScreen(app: AppState, pid: Long, streamId: String) {
    val g = app.g
    val scope = rememberCoroutineScope()
    val key = "movie:$pid:$streamId"
    val data by produceState<Pair<VodEntity?, PlaylistEntity?>?>(null, pid, streamId) {
        value = g.db.vod().byStreamId(pid, streamId) to g.db.playlists().get(pid)
    }
    val vod = data?.first
    val pl = data?.second
    val info by produceState<XtreamApi.VodInfo?>(null, vod, pl) {
        if (vod != null && pl != null && pl.type == PlaylistType.XTREAM) {
            value = runCatching { withContext(Dispatchers.IO) { g.repo.xtream(pl).vodInfo(streamId) } }.getOrNull()
        }
    }
    val progress by remember(key) { g.db.progress().observe(key) }.collectAsState(initial = null)
    val favKeys by remember(pid) { g.db.favorites().observeKeys(pid) }.collectAsState(initial = emptyList())
    val isFav = key in favKeys

    if (data == null) { CenterMessage("Loading…"); return }
    if (vod == null || pl == null) { CenterMessage("This movie is no longer available"); return }

    fun play(fromStart: Boolean) {
        val ext = info?.ext?.takeIf { it.isNotBlank() } ?: vod.ext
        val url = vod.directUrl ?: g.repo.xtream(pl).movieUrl(streamId, ext)
        val item = PlaybackManager.VodPlayable(
            key = key, playlistId = pid, kind = "movie", refId = streamId, title = vod.name, subtitle = vod.year,
            url = url, poster = vod.poster, userAgent = pl.userAgent.ifBlank { null },
        )
        g.playback.setQueue(listOf(item), 0)
        val p = progress
        app.playVod(item, if (!fromStart && p != null && !p.finished) p.position else 0L)
    }

    val meta = listOfNotNull(
        (info?.releaseDate?.take(4) ?: vod.year)?.takeIf { it.isNotBlank() },
        info?.duration?.takeIf { it.isNotBlank() && it != "00:00:00" },
        (info?.rating ?: vod.rating)?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
    ).joinToString("  ·  ")
    DetailPage(app, info?.backdrop ?: info?.image ?: vod.poster, info?.image ?: vod.poster, vod.name, meta, info?.genre?.takeIf { it.isNotBlank() }) {
        item {
          Column {
            val p = progress
            if (p != null && !p.finished) ResumeBar(p.position, p.duration)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (p != null && !p.finished && p.position > 60_000) {
                    PlayButton("Resume", { play(false) })
                    SecondaryButton("Start over", { play(true) })
                } else {
                    PlayButton("Play", { play(true) })
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    scope.launch { if (isFav) g.db.favorites().remove(key) else g.db.favorites().add(FavoriteEntity(key, pid)) }
                }) {
                    Icon(if (isFav) Icons.Filled.Star else Icons.Filled.StarBorder, if (isFav) "Remove from favorites" else "Add to favorites", tint = if (isFav) Pal.Warn else Pal.Text)
                }
            }
          }
        }
        item { BodyText(info?.plot) }
        item {
          Column {
            info?.director?.takeIf { it.isNotBlank() }?.let { Text("Director: $it", fontSize = 13.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) }
            info?.cast?.takeIf { it.isNotBlank() }?.let { Text("Cast: $it", fontSize = 13.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) }
          }
        }
    }
}

// ============================================================================ series

@Composable
fun SeriesDetailScreen(app: AppState, pid: Long, seriesId: String) {
    val g = app.g
    val scope = rememberCoroutineScope()
    val favKey = "series:$pid:$seriesId"
    val data by produceState<Pair<SeriesEntity?, PlaylistEntity?>?>(null, pid, seriesId) {
        value = g.db.series().bySeriesId(pid, seriesId) to g.db.playlists().get(pid)
    }
    val series = data?.first
    val pl = data?.second
    var loadError by remember { mutableStateOf<String?>(null) }
    val info by produceState<XtreamApi.SeriesInfo?>(null, series, pl) {
        if (series != null && pl != null) {
            try {
                value = withContext(Dispatchers.IO) { g.repo.xtream(pl).seriesInfo(seriesId) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                loadError = e.message ?: "Couldn't load episodes"
            }
        }
    }
    val progressList by remember(pid, seriesId) { g.db.progress().observeSeries(pid, seriesId) }.collectAsState(initial = emptyList())
    val favKeys by remember(pid) { g.db.favorites().observeKeys(pid) }.collectAsState(initial = emptyList())
    val isFav = favKey in favKeys

    if (data == null) { CenterMessage("Loading…"); return }
    if (series == null || pl == null) { CenterMessage("This series is no longer available"); return }

    val episodes = info?.episodes.orEmpty()
    val progressByKey = progressList.associateBy { it.key }
    val lastWatched = progressList.maxByOrNull { it.updatedAt }
    val seasons = episodes.map { it.season }.distinct()
    var season by rememberSaveable(seasons) {
        mutableIntStateOf(lastWatched?.season?.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 1)
    }
    val api = g.repo.xtream(pl)
    val playables = remember(episodes) {
        episodes.map { e ->
            PlaybackManager.VodPlayable(
                key = "ep:$pid:${e.id}", playlistId = pid, kind = "episode", refId = e.id, title = series.name,
                subtitle = "S${e.season} E${e.number}" + if (e.title.isNotBlank()) " · ${e.title}" else "",
                url = api.episodeUrl(e.id, e.ext), poster = series.cover, userAgent = pl.userAgent.ifBlank { null },
                seriesKey = seriesId, season = e.season, episode = e.number,
            )
        }
    }

    fun playAt(index: Int, resume: Boolean) {
        val item = playables.getOrNull(index) ?: return
        g.playback.setQueue(playables, index)
        val p = progressByKey[item.key]
        app.playVod(item, if (resume && p != null && !p.finished) p.position else 0L)
    }

    // "Continue" = last watched episode if unfinished, otherwise the one after it.
    val continueIndex = lastWatched?.let { lw ->
        val i = playables.indexOfFirst { it.key == lw.key }
        when {
            i < 0 -> null
            lw.finished -> if (i + 1 < playables.size) i + 1 else null
            else -> i
        }
    }

    val meta = listOfNotNull(
        (info?.releaseDate?.take(4) ?: series.year)?.takeIf { it.isNotBlank() },
        if (seasons.isNotEmpty()) "${seasons.size} season${if (seasons.size == 1) "" else "s"}" else null,
        (info?.rating ?: series.rating)?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
    ).joinToString("  ·  ")
    DetailPage(app, info?.backdrop ?: series.cover, info?.cover ?: series.cover, series.name, meta, (info?.genre ?: series.genre)?.takeIf { it.isNotBlank() }) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (playables.isNotEmpty()) {
                    val ci = continueIndex
                    if (ci != null) {
                        val e = playables[ci]
                        PlayButton("${if (progressByKey[e.key]?.let { !it.finished && it.position > 60_000 } == true) "Continue" else "Play"} S${e.season} E${e.episode}", { playAt(ci, true) })
                    } else {
                        PlayButton("Play S${playables[0].season} E${playables[0].episode}", { playAt(0, false) })
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    scope.launch { if (isFav) g.db.favorites().remove(favKey) else g.db.favorites().add(FavoriteEntity(favKey, pid)) }
                }) {
                    Icon(if (isFav) Icons.Filled.Star else Icons.Filled.StarBorder, if (isFav) "Remove from favorites" else "Add to favorites", tint = if (isFav) Pal.Warn else Pal.Text)
                }
            }
        }
        item { BodyText(info?.plot ?: series.plot) }
        when {
            loadError != null -> item { Text(loadError.orEmpty(), color = Pal.Warn, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
            info == null -> item { Text("Loading episodes…", color = Pal.Muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
            episodes.isEmpty() -> item { Text("No episodes available", color = Pal.Muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
            else -> {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                        items(seasons) { s -> Pill("Season $s", selected = s == season, onClick = { season = s }) }
                    }
                }
                val seasonEps = playables.withIndex().filter { it.value.season == season }
                items(seasonEps, key = { it.value.key }) { iv ->
                    val ep = episodes[iv.index]
                    val p = progressByKey[iv.value.key]
                    EpisodeRow(
                        number = "E${ep.number}",
                        title = ep.title.ifBlank { "Episode ${ep.number}" },
                        plot = ep.plot,
                        image = ep.image,
                        right = ep.durationSecs?.takeIf { it > 0 }?.let { Fmt.duration(it * 1000L) } ?: "",
                        progress = p?.takeIf { !it.finished && it.duration > 0 }?.let { it.position.toFloat() / it.duration },
                        watched = p?.finished == true,
                        onClick = { playAt(iv.index, true) },
                    )
                }
            }
        }
    }
}
