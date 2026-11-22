package com.wisetv.mobile.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisetv.mobile.L
import com.wisetv.mobile.data.db.GroupKind
import com.wisetv.mobile.ui.AppState
import com.wisetv.mobile.ui.CenterMessage
import com.wisetv.mobile.ui.Choice
import com.wisetv.mobile.ui.ChoiceSheet
import com.wisetv.mobile.ui.Pal
import com.wisetv.mobile.ui.Pill
import com.wisetv.mobile.ui.PosterCard
import com.wisetv.mobile.ui.Screen
import com.wisetv.mobile.ui.Spinner
import com.wisetv.mobile.ui.VodCard
import com.wisetv.mobile.ui.player.CastButton

private const val CONTINUE = "__continue"
private const val FAVORITES = "__fav"
private const val RECENT = "__recent"
private const val PLEX_ERROR = "plexerror:"

/** Movies / Series: a category picker on top and a grid of posters. Plex libraries are categories too. */
@Composable
fun VodBrowser(app: AppState, kind: String) {
    val g = app.g
    val pid = app.playlistId
    val playlist by remember(pid) { g.db.playlists().observe(pid) }.collectAsState(initial = null)
    val gen = playlist?.vodGen ?: 0L
    val cats by remember(pid, kind) { g.db.groups().observeVisible(pid, kind) }.collectAsState(initial = emptyList())
    val favKeys by remember(pid) { g.db.favorites().observeKeys(pid) }.collectAsState(initial = emptyList())
    val plexType = if (kind == GroupKind.MOVIE) "movie" else "show"
    val plexServerIds = g.plex.servers.map { it.id }
    val plexEntries by produceState(emptyList<Pair<String, String>>(), plexServerIds) {
        if (!g.plex.hasServer) return@produceState
        val result = runCatching { g.plex.libraries() }.getOrNull() ?: return@produceState
        val several = plexServerIds.size > 1
        value = result.libraries.filter { it.type == plexType }.flatMap {
            val label = if (several) "${it.title} · ${it.serverName}" else "${it.title} · Plex"
            listOf("plex:${it.id}:new" to "$label · New", "plex:${it.id}:all" to label)
        } + result.unreachable.map { "$PLEX_ERROR$it" to "$it · can't connect" }
    }
    val entries = remember(cats, plexEntries) {
        listOf(CONTINUE to "Continue watching", FAVORITES to "Favorites", RECENT to "Recently added") +
            plexEntries + cats.map { it.categoryId to it.name }
    }
    var selected by remember(kind) { mutableStateOf(app.vodCategory[kind] ?: RECENT) }
    var items by remember(kind) { mutableStateOf<List<VodCard>?>(null) }
    var loadError by remember(kind) { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val title = if (kind == GroupKind.MOVIE) "Movies" else "Series"

    LaunchedEffect(selected, gen, pid, playlist != null, if (selected == FAVORITES) favKeys else null, plexEntries) {
        if (playlist == null) return@LaunchedEffect
        app.vodCategory[kind] = selected
        val loaded = try {
            loadCards(app, kind, pid, gen, selected, favKeys)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            loadError = e.message ?: "Couldn't load this category"
            emptyList()
        }
        if (loaded.isNotEmpty()) loadError = null
        if (items != null && items?.firstOrNull()?.id != loaded.firstOrNull()?.id) gridState.scrollToItem(0)
        items = loaded
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Pal.Text, modifier = Modifier.weight(1f))
            CastButton()
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Pill(
                entries.firstOrNull { it.first == selected }?.second ?: "Recently added",
                selected = true,
                onClick = { picker = true },
                trailing = Icons.Filled.ArrowDropDown,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = items
            when {
                playlist != null && gen == 0L && cats.isEmpty() && !g.plex.hasServer -> CenterMessage(
                    if (kind == GroupKind.MOVIE) "No movies yet" else "No series yet",
                    "They appear after the playlist finishes updating. M3U playlists only have movies if the list includes them.",
                )
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                list.isEmpty() -> CenterMessage(
                    when {
                        selected.startsWith(PLEX_ERROR) -> "Can't reach the Plex server “${selected.removePrefix(PLEX_ERROR)}”"
                        loadError != null -> "Couldn't load this"
                        selected == CONTINUE -> "Nothing in progress"
                        selected == FAVORITES -> "No favorites yet"
                        else -> "Nothing here"
                    },
                    when {
                        selected.startsWith(PLEX_ERROR) -> "It may be switched off or no longer shared. Choose servers in Settings › Plex."
                        loadError != null -> loadError
                        selected == FAVORITES -> "Open a title and tap the star."
                        else -> null
                    },
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    state = gridState,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(list, key = { it.id }) { card ->
                        PosterCard(card, onClick = {
                            L.i("vod open ${card.id}")
                            val plex = card.id.takeIf { it.startsWith("plex:") }?.removePrefix("plex:")?.split(':')
                            app.nav.push(
                                when {
                                    plex != null && kind == GroupKind.MOVIE -> Screen.PlexMovie(plex[0], plex[1])
                                    plex != null -> Screen.PlexShow(plex[0], plex[1])
                                    kind == GroupKind.MOVIE -> Screen.MovieDetail(pid, card.id)
                                    else -> Screen.SeriesDetail(pid, card.id)
                                },
                            )
                        })
                    }
                }
            }
        }
    }

    if (picker) {
        ChoiceSheet(
            if (kind == GroupKind.MOVIE) "Movie categories" else "Series categories",
            entries.map { (id, name) -> Choice(name, selected = id == selected) { selected = id } } +
                Choice("Show or hide categories…") { app.nav.push(Screen.ManageGroups(pid, kind, false)) },
            onDismiss = { picker = false },
        )
    }
}

private suspend fun loadCards(app: AppState, kind: String, pid: Long, gen: Long, cat: String, favKeys: List<String>): List<VodCard> {
    val db = app.g.db
    val plex = app.g.plex
    if (cat.startsWith(PLEX_ERROR)) return emptyList()
    if (cat.startsWith("plex:")) {
        // plex:<server>:<library>:<new|all>
        val parts = cat.removePrefix("plex:").split(':')
        val libId = "${parts[0]}:${parts.getOrElse(1) { "" }}"
        val lib = plex.libraries().libraries.firstOrNull { it.id == libId } ?: return emptyList()
        return plex.items(lib, recent = parts.getOrElse(2) { "all" } == "new").map { plexCard(app, it) }
    }
    if (cat == CONTINUE && plex.hasServer) {
        // Local progress first, then what Plex says is half-watched or up next.
        val mine = loadCards(app, kind, pid, gen, "$CONTINUE-local", favKeys)
        val deck = runCatching { plex.onDeck() }.getOrDefault(emptyList()).mapNotNull { item ->
            when {
                kind == GroupKind.MOVIE && item.type == "movie" -> plexCard(app, item)
                kind == GroupKind.SERIES && item.type == "episode" && item.grandparentRatingKey != null -> VodCard(
                    "plex:${item.serverId}:${item.grandparentRatingKey}",
                    item.grandparentTitle ?: item.title,
                    plex.image(item.serverId, item.grandparentThumb ?: item.thumb, 300, 450),
                    null,
                    null,
                )
                else -> null
            }
        }
        return (mine + deck).distinctBy { it.id }
    }
    val c = if (cat == "$CONTINUE-local") CONTINUE else cat
    return if (kind == GroupKind.MOVIE) {
        when (c) {
            CONTINUE -> db.progress().continueMovies(pid).map {
                VodCard(it.refId, it.title, it.poster, null, it.position.toFloat() / it.duration.coerceAtLeast(1))
            }
            FAVORITES -> {
                val prefix = "movie:$pid:"
                val ids = favKeys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
                val rows = ids.chunked(500).flatMap { db.vod().byStreamIds(pid, gen, it) }.associateBy { it.streamId }
                ids.mapNotNull { rows[it] }.map { VodCard(it.streamId, it.name, it.poster, it.rating, null) }
            }
            RECENT -> db.vod().recent(pid, gen, 300).map { VodCard(it.streamId, it.name, it.poster, it.rating, null) }
            else -> db.vod().byCategory(pid, gen, cat).map { VodCard(it.streamId, it.name, it.poster, it.rating, null) }
        }
    } else {
        when (c) {
            CONTINUE -> {
                val keys = db.progress().recentSeriesKeys(pid)
                val rows = keys.chunked(500).flatMap { db.series().bySeriesIds(pid, gen, it) }.associateBy { it.seriesId }
                keys.mapNotNull { rows[it] }.map { VodCard(it.seriesId, it.name, it.cover, it.rating, null) }
            }
            FAVORITES -> {
                val prefix = "series:$pid:"
                val ids = favKeys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
                val rows = ids.chunked(500).flatMap { db.series().bySeriesIds(pid, gen, it) }.associateBy { it.seriesId }
                ids.mapNotNull { rows[it] }.map { VodCard(it.seriesId, it.name, it.cover, it.rating, null) }
            }
            RECENT -> db.series().recent(pid, gen, 300).map { VodCard(it.seriesId, it.name, it.cover, it.rating, null) }
            else -> db.series().byCategory(pid, gen, cat).map { VodCard(it.seriesId, it.name, it.cover, it.rating, null) }
        }
    }.distinctBy { it.id }
}

private fun plexCard(app: AppState, it: com.wisetv.mobile.data.plex.Plex.Item): VodCard = VodCard(
    id = "plex:${it.uid}",
    title = it.title + (it.year?.let { y -> " ($y)" }.orEmpty()),
    image = app.g.plex.image(it.serverId, it.thumb, 300, 450),
    rating = it.rating?.toString(),
    progress = app.g.plex.offsetOf(it).takeIf { o -> o > 0 && it.durationMs > 0 }
        ?.let { o -> o.toFloat() / it.durationMs },
)
