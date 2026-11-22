package com.wisetv.mobile.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisetv.mobile.data.plex.Codecs
import com.wisetv.mobile.data.plex.Plex
import com.wisetv.mobile.ui.AppState
import com.wisetv.mobile.ui.CenterMessage
import com.wisetv.mobile.ui.Fmt
import com.wisetv.mobile.ui.Pal
import com.wisetv.mobile.ui.Pill
import kotlinx.coroutines.launch

private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

@Composable
private fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): Load<T> {
    val state by produceState<Load<T>>(Load.Loading, *keys) {
        value = try {
            Load.Ok(block())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Load.Err(e.message ?: "Couldn't reach your Plex server")
        }
    }
    return state
}

private fun Plex.Item.offset(app: AppState): Long = app.g.plex.offsetOf(this)

/** Where a title comes from: just "Plex" with one server, the server's name with several. */
private fun source(app: AppState, serverId: String): String =
    if (app.g.plex.servers.size > 1) app.g.plex.server(serverId)?.name ?: "Plex" else "Plex"

/** One line that tells you whether the phone plays the file itself or the server converts it. */
private fun playNote(app: AppState, serverId: String, m: Plex.Media?): String? {
    m ?: return null
    if (!app.g.plex.isLocal(serverId) && app.g.settings.plexRemoteKbps > 0) return "Away from home: converted to ${app.g.settings.plexRemoteKbps / 1000} Mbps"
    if (Codecs.canDirectPlay(m)) return "Plays directly"
    val why = when {
        !Codecs.video(m.videoCodec) -> "${m.videoCodec?.uppercase()} video"
        !Codecs.audio(m.audioCodec) -> "${(if (m.audioCodec == "dca") "DTS" else m.audioCodec)?.uppercase()} audio"
        else -> "${m.container?.uppercase()} file"
    }
    return "Your server converts this ($why)"
}

// ============================================================================ movie

@Composable
fun PlexMovieScreen(app: AppState, serverId: String, ratingKey: String) {
    val plex = app.g.plex
    var refresh by remember { mutableIntStateOf(0) }
    val load = rememberLoad(serverId, ratingKey, refresh) { plex.metadata(serverId, ratingKey) }
    val movie = when (load) {
        Load.Loading -> { CenterMessage("Loading…"); return }
        is Load.Err -> { CenterMessage("Couldn't load this from Plex", load.message); return }
        is Load.Ok -> load.value
    }
    val offset = movie.offset(app)
    val meta = listOfNotNull(
        movie.year,
        movie.durationMs.takeIf { it > 0 }?.let { Fmt.duration(it) },
        movie.contentRating,
        movie.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
        source(app, serverId),
    ).joinToString("  ·  ")
    DetailPage(
        app,
        plex.image(serverId, movie.art ?: movie.thumb, 1280, 720),
        plex.image(serverId, movie.thumb, 300, 450),
        movie.title,
        meta,
        playNote(app, serverId, movie.media),
    ) {
        item {
            Column {
                if (!movie.watched) ResumeBar(offset, movie.durationMs)
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (offset > 60_000) {
                        PlayButton("Resume", { app.playPlex(listOf(movie), 0, resume = true) })
                        SecondaryButton("Start over", { app.playPlex(listOf(movie), 0, resume = false) })
                    } else {
                        PlayButton("Play", { app.playPlex(listOf(movie), 0, resume = false) })
                    }
                    if (!movie.watched) {
                        SecondaryButton("Watched", {
                            app.scope.launch {
                                plex.markWatched(serverId, movie.ratingKey)
                                refresh++
                            }
                        }, icon = Icons.Filled.Check)
                    }
                }
            }
        }
        item { if (movie.genres.isNotEmpty()) Text(movie.genres.take(4).joinToString(", "), fontSize = 13.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 16.dp)) }
        item { BodyText(movie.summary) }
        item {
            Column {
                if (movie.directors.isNotEmpty()) Text("Director: ${movie.directors.joinToString(", ")}", fontSize = 13.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
                if (movie.cast.isNotEmpty()) Text("Cast: ${movie.cast.joinToString(", ")}", fontSize = 13.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
            }
        }
    }
}

// ============================================================================ show

@Composable
fun PlexShowScreen(app: AppState, serverId: String, ratingKey: String) {
    val plex = app.g.plex
    val load = rememberLoad(serverId, ratingKey) { plex.metadata(serverId, ratingKey) to plex.episodes(serverId, ratingKey) }
    val (show, episodes) = when (load) {
        Load.Loading -> { CenterMessage("Loading…"); return }
        is Load.Err -> { CenterMessage("Couldn't load this from Plex", load.message); return }
        is Load.Ok -> load.value
    }
    val seasons = episodes.mapNotNull { it.parentIndex }.distinct().sorted()
    // "Continue" = an episode in progress, else the first unwatched one after the last watched.
    val continueIndex = remember(episodes) {
        val inProgress = episodes.indexOfFirst { it.offset(app) > 60_000 && !it.watched }
        if (inProgress >= 0) inProgress else {
            val lastWatched = episodes.indexOfLast { it.watched }
            episodes.indices.firstOrNull { it > lastWatched && !episodes[it].watched }
        }
    }
    var season by rememberSaveable(seasons) {
        mutableIntStateOf(continueIndex?.let { episodes[it].parentIndex } ?: seasons.firstOrNull() ?: 1)
    }
    val meta = listOfNotNull(
        show.year,
        if (seasons.isNotEmpty()) "${seasons.size} season${if (seasons.size == 1) "" else "s"}" else null,
        "${episodes.count { it.watched }}/${episodes.size} watched",
        source(app, serverId),
    ).joinToString("  ·  ")
    DetailPage(
        app,
        plex.image(serverId, show.art ?: show.thumb, 1280, 720),
        plex.image(serverId, show.thumb, 300, 450),
        show.title,
        meta,
        show.genres.take(3).joinToString(", ").ifBlank { null },
    ) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (episodes.isNotEmpty()) {
                    val ci = continueIndex
                    val label = if (ci != null) {
                        val e = episodes[ci]
                        "${if (e.offset(app) > 60_000) "Continue" else "Play"} S${e.parentIndex} E${e.index}"
                    } else {
                        "Play from the beginning"
                    }
                    PlayButton(label, { app.playPlex(episodes, ci ?: 0, resume = true) })
                }
            }
        }
        item { BodyText(show.summary) }
        if (episodes.isEmpty()) {
            item { Text("No episodes on your server", color = Pal.Muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
        } else {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                    items(seasons) { sn -> Pill(if (sn == 0) "Specials" else "Season $sn", selected = sn == season, onClick = { season = sn }) }
                }
            }
            val seasonEps = episodes.withIndex().filter { it.value.parentIndex == season }
            items(seasonEps, key = { it.value.ratingKey }) { iv ->
                val ep = iv.value
                val off = ep.offset(app)
                EpisodeRow(
                    number = "E${ep.index ?: "?"}",
                    title = ep.title.ifBlank { "Episode ${ep.index}" },
                    plot = ep.summary,
                    image = plex.image(serverId, ep.thumb, 320, 180),
                    right = if (ep.durationMs > 0) Fmt.duration(ep.durationMs) else "",
                    progress = if (off > 0 && ep.durationMs > 0 && !ep.watched) off.toFloat() / ep.durationMs else null,
                    watched = ep.watched && off == 0L,
                    onClick = { app.playPlex(episodes, iv.index, resume = true) },
                )
            }
        }
        item { Spacer(Modifier.padding(8.dp)) }
    }
}
