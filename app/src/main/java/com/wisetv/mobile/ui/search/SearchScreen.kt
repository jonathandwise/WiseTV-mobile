package com.wisetv.mobile.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.wisetv.mobile.L
import com.wisetv.mobile.data.Settings
import com.wisetv.mobile.data.db.ChannelEntity
import com.wisetv.mobile.data.db.ProgramEntity
import com.wisetv.mobile.data.db.SeriesEntity
import com.wisetv.mobile.data.db.VodEntity
import com.wisetv.mobile.data.plex.Plex
import com.wisetv.mobile.ui.AppState
import com.wisetv.mobile.ui.CenterMessage
import com.wisetv.mobile.ui.Fmt
import com.wisetv.mobile.ui.Pal
import com.wisetv.mobile.ui.ProgramMenu
import com.wisetv.mobile.ui.Screen
import com.wisetv.mobile.ui.SectionHeader
import com.wisetv.mobile.ui.Spinner
import kotlinx.coroutines.delay

private data class Results(
    val channels: List<ChannelEntity>,
    val programs: List<Pair<ProgramEntity, ChannelEntity>>,
    val movies: List<VodEntity>,
    val series: List<SeriesEntity>,
    val plex: List<Plex.Item> = emptyList(),
) {
    val isEmpty get() = channels.isEmpty() && programs.isEmpty() && movies.isEmpty() && series.isEmpty() && plex.isEmpty()
}

private sealed interface Item {
    data class Header(val text: String) : Item
    data class Ch(val c: ChannelEntity) : Item
    data class Prog(val p: ProgramEntity, val c: ChannelEntity) : Item
    data class Movie(val v: VodEntity) : Item
    data class Show(val s: SeriesEntity) : Item
    data class PlexHit(val p: Plex.Item) : Item
}

/** Search channels, what's on, movies, series and Plex at once. */
@Composable
fun SearchScreen(app: AppState) {
    val g = app.g
    val pid = app.playlistId
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Results?>(null) }
    var busy by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(readHistory(g.settings)) }
    val focus = LocalFocusManager.current

    LaunchedEffect(query, pid) {
        val q = query.trim()
        if (q.length < 2) {
            results = null
            return@LaunchedEffect
        }
        busy = true
        delay(250)
        val pl = g.db.playlists().get(pid)
        val gen = pl?.epgGen ?: 0L
        val vodGen = pl?.vodGen ?: 0L
        val now = System.currentTimeMillis()
        val chans = g.db.channels().search(pid, q)
        val progs = g.db.programs().search(pid, gen, q, now).mapNotNull { p ->
            g.db.channels().firstByEpg(pid, p.epgKey)?.let { p to it }
        }
        val plexHits = if (g.plex.hasServer) runCatching { g.plex.search(q) }.getOrDefault(emptyList()) else emptyList()
        results = Results(chans, progs, g.db.vod().search(pid, vodGen, q), g.db.series().search(pid, vodGen, q), plexHits)
        busy = false
    }

    fun saveQuery(text: String) {
        if (text.trim().length >= 2) history = saveSearch(g.settings, text.trim())
    }

    val rows = remember(results) {
        val r = results ?: return@remember emptyList<Item>()
        buildList {
            if (r.channels.isNotEmpty()) { add(Item.Header("Channels")); r.channels.forEach { add(Item.Ch(it)) } }
            if (r.programs.isNotEmpty()) { add(Item.Header("On TV")); r.programs.forEach { add(Item.Prog(it.first, it.second)) } }
            if (r.movies.isNotEmpty()) { add(Item.Header("Movies")); r.movies.forEach { add(Item.Movie(it)) } }
            if (r.series.isNotEmpty()) { add(Item.Header("Series")); r.series.forEach { add(Item.Show(it)) } }
            if (r.plex.isNotEmpty()) { add(Item.Header("Plex")); r.plex.forEach { add(Item.PlexHit(it)) } }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("Search", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Pal.Text, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Channels, programmes, movies, series") },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Pal.Muted) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear", tint = Pal.Muted) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { saveQuery(query); focus.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Pal.Accent, unfocusedBorderColor = Pal.Line, cursorColor = Pal.Accent),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        when {
            query.trim().length < 2 && history.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item { SectionHeader("Recent searches") }
                items(history) { h ->
                    Row(
                        Modifier.fillMaxWidth().clickable { query = h; saveQuery(h) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.History, null, tint = Pal.Muted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(h, fontSize = 16.sp, color = Pal.Text)
                    }
                }
                item {
                    Text(
                        "Clear search history",
                        fontSize = 14.sp,
                        color = Pal.Accent,
                        modifier = Modifier.clickable {
                            g.settings.searchHistory = ""
                            history = emptyList()
                        }.padding(16.dp),
                    )
                }
            }
            query.trim().length < 2 -> CenterMessage("Search everything", "Type at least two letters.")
            results == null -> Spinner(Modifier.padding(32.dp).align(Alignment.CenterHorizontally))
            results?.isEmpty == true -> CenterMessage("Nothing found for “${query.trim()}”")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(rows) { row ->
                    when (row) {
                        is Item.Header -> SectionHeader(row.text)
                        is Item.Ch -> ResultRow(
                            (if (app.isFavorite(row.c)) "★ " else "") + row.c.name,
                            "${row.c.number} · ${row.c.groupName}",
                            row.c.logo,
                            onLongClick = { app.programMenu = ProgramMenu(row.c, app.guide.programAt(row.c, System.currentTimeMillis())) },
                        ) {
                            saveQuery(query)
                            app.tune(row.c, fullscreen = true)
                        }
                        is Item.Prog -> ResultRow(
                            row.p.title,
                            "${Fmt.day(row.p.start)} ${Fmt.range(row.p.start, row.p.stop)} · ${row.c.name}",
                            row.c.logo,
                        ) {
                            saveQuery(query)
                            app.programMenu = ProgramMenu(row.c, row.p)
                        }
                        is Item.Movie -> ResultRow(row.v.name, listOfNotNull("Movie", row.v.year).joinToString(" · "), row.v.poster, poster = true) {
                            saveQuery(query)
                            app.nav.push(Screen.MovieDetail(pid, row.v.streamId))
                        }
                        is Item.Show -> ResultRow(row.s.name, listOfNotNull("Series", row.s.year).joinToString(" · "), row.s.cover, poster = true) {
                            saveQuery(query)
                            app.nav.push(Screen.SeriesDetail(pid, row.s.seriesId))
                        }
                        is Item.PlexHit -> {
                            val p = row.p
                            val (title, sub) = when (p.type) {
                                "movie" -> p.title to listOfNotNull("Movie", p.year, "Plex").joinToString(" · ")
                                "show" -> p.title to listOfNotNull("Series", p.year, "Plex").joinToString(" · ")
                                else -> "${p.grandparentTitle ?: ""}: ${p.title}" to "Episode · S${p.parentIndex} E${p.index} · Plex"
                            }
                            ResultRow(title, sub, g.plex.image(p.serverId, p.thumb ?: p.grandparentThumb, 120, 180), poster = true) {
                                saveQuery(query)
                                L.i("search plex ${p.type} ${p.title}")
                                when (p.type) {
                                    "movie" -> app.nav.push(Screen.PlexMovie(p.serverId, p.ratingKey))
                                    "show" -> app.nav.push(Screen.PlexShow(p.serverId, p.ratingKey))
                                    else -> p.grandparentRatingKey?.let { app.nav.push(Screen.PlexShow(p.serverId, it)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val HISTORY_SIZE = 10

private fun readHistory(s: Settings): List<String> = s.searchHistory.split('\n').filter { it.isNotBlank() }

private fun saveSearch(s: Settings, text: String): List<String> {
    val list = (listOf(text) + readHistory(s).filterNot { it.equals(text, ignoreCase = true) }).take(HISTORY_SIZE)
    s.searchHistory = list.joinToString("\n")
    return list
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(title: String, sub: String, image: String?, poster: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val box = if (poster) Modifier.size(width = 40.dp, height = 60.dp) else Modifier.size(width = 56.dp, height = 38.dp)
        androidx.compose.foundation.layout.Box(box.clip(RoundedCornerShape(6.dp)).background(Pal.Panel2)) {
            if (image != null) {
                AsyncImage(
                    image,
                    null,
                    contentScale = if (poster) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(if (poster) 0.dp else 4.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = Pal.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Pal.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
