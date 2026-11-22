package com.wisetv.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MimeTypes
import com.wisetv.mobile.AppGraph
import com.wisetv.mobile.data.plex.Plex
import com.wisetv.mobile.data.Settings
import com.wisetv.mobile.data.db.ChannelEntity
import com.wisetv.mobile.data.db.ProgramEntity
import com.wisetv.mobile.player.PlaybackManager
import com.wisetv.mobile.ui.guide.GuideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Section(val label: String) {
    LIVE("Live TV"),
    MOVIES("Movies"),
    SERIES("Series"),
    SEARCH("Search"),
    SETTINGS("Settings"),
}

sealed interface Screen {
    data class Main(val section: Section) : Screen
    data object Player : Screen
    data class MovieDetail(val playlistId: Long, val streamId: String) : Screen
    data class SeriesDetail(val playlistId: Long, val seriesId: String) : Screen
    data object AddPlaylist : Screen
    data class EditPlaylist(val id: Long) : Screen
    data object Playlists : Screen
    data class ManageGroups(val playlistId: Long, val kind: String, val firstRun: Boolean) : Screen
    data class HiddenChannels(val playlistId: Long) : Screen
    data class PlexMovie(val serverId: String, val ratingKey: String) : Screen
    data class PlexShow(val serverId: String, val ratingKey: String) : Screen
}

class Navigator(start: Screen) {
    val stack = mutableStateListOf(start)
    val current: Screen get() = stack.last()

    fun push(s: Screen) {
        stack.add(s)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun replaceTop(s: Screen) {
        stack[stack.lastIndex] = s
    }

    fun resetTo(s: Screen) {
        stack.clear()
        stack.add(s)
    }
}

data class ProgramMenu(val channel: ChannelEntity, val program: ProgramEntity?)

class AppState(val g: AppGraph, val scope: CoroutineScope) {
    val nav = Navigator(Screen.Main(Section.LIVE))
    var playlistId by mutableLongStateOf(g.settings.activePlaylistId)
        private set
    val guide = GuideState(g, scope)

    var toast by mutableStateOf<String?>(null)
        private set
    var programMenu by mutableStateOf<ProgramMenu?>(null)

    /** Favorite / hidden changes made this session, by channel id, so lists loaded earlier can show them. */
    val favoriteChanges = mutableStateMapOf<Long, Boolean>()

    /** Remembers the selected movie/series category between visits. */
    val vodCategory = mutableStateMapOf<String, String>()

    init {
        g.playback.channelList = { guide.channels }
    }

    fun setActivePlaylist(id: Long) {
        if (id == playlistId) return
        g.settings.activePlaylistId = id
        playlistId = id
        g.settings.lastGroup = Settings.ALL_GROUP
        vodCategory.clear()
        guide.selectGroup(Settings.ALL_GROUP)
        guide.bind(id)
    }

    /** Adds the channel to favorites, or removes it if it's already there. */
    fun toggleFavorite(ch: ChannelEntity) {
        if (ch.id <= 0) return
        scope.launch {
            val fresh = g.db.channels().get(ch.id) ?: return@launch
            val order = g.db.channels().maxFavOrder(fresh.playlistId) + 1
            g.db.channels().setFavorite(fresh.id, !fresh.favorite, order)
            favoriteChanges[fresh.id] = !fresh.favorite
            com.wisetv.mobile.L.i("favorite ${fresh.name} -> ${!fresh.favorite}")
            showToast(if (fresh.favorite) "Removed ${fresh.name} from favorites" else "\u2605 Added ${fresh.name} to favorites")
        }
    }

    fun isFavorite(ch: ChannelEntity): Boolean = ch.id > 0 && (favoriteChanges[ch.id] ?: ch.favorite)

    fun hideChannel(ch: ChannelEntity) {
        if (ch.id <= 0) return
        scope.launch {
            g.db.channels().setHidden(ch.id, true)
            showToast("${ch.name} hidden. Show it again in Settings \u203A Hidden channels.")
        }
    }

    fun showToast(msg: String) {
        toast = msg
        scope.launch {
            delay(4_000)
            if (toast == msg) toast = null
        }
    }

    fun tune(ch: ChannelEntity, fullscreen: Boolean) {
        g.playback.playChannel(ch)
        if (fullscreen && nav.current != Screen.Player) nav.push(Screen.Player)
    }

    fun playVod(item: PlaybackManager.VodPlayable, startMs: Long) {
        g.playback.playVod(item, startMs)
        if (nav.current != Screen.Player) nav.push(Screen.Player)
    }

    /**
     * Plays item [index] of a list of Plex titles (an episode list, or a single movie). The chosen
     * one is looked up in full first, for its subtitle files and up-to-date resume point.
     */
    fun playPlex(items: List<Plex.Item>, index: Int, resume: Boolean) {
        scope.launch {
            try {
                val chosen = items[index]
                val full = runCatching { g.plex.metadata(chosen.serverId, chosen.ratingKey) }.getOrDefault(chosen)
                val playables = items.mapIndexed { i, it -> plexPlayable(if (i == index) full else it) }
                g.playback.setQueue(playables, index)
                val offset = g.plex.offsetOf(full)
                playVod(playables[index], if (resume) offset else 0L)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                showToast("Plex: ${e.message}")
            }
        }
    }

    private suspend fun plexPlayable(it: Plex.Item): PlaybackManager.VodPlayable {
        val pb = g.plex.playback(it)
        val episode = it.type == "episode"
        return PlaybackManager.VodPlayable(
            key = "plex:${it.uid}",
            playlistId = 0,
            kind = if (episode) "plex-episode" else "plex-movie",
            refId = it.ratingKey,
            title = if (episode) it.grandparentTitle ?: it.title else it.title,
            subtitle = if (episode) "S${it.parentIndex ?: 0} E${it.index ?: 0} \u00B7 ${it.title}" else it.year,
            url = pb.url,
            poster = g.plex.image(it.serverId, it.thumb, 300, 450),
            userAgent = null,
            local = true,
            plexKey = it.ratingKey,
            plexServer = it.serverId,
            plexSession = pb.transcodeSession,
            headers = pb.headers,
            subtitles = pb.subtitles.mapNotNull { (url, sub) ->
                val mime = when (sub.codec) {
                    "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
                    "ass", "ssa" -> MimeTypes.TEXT_SSA
                    "vtt", "webvtt" -> MimeTypes.TEXT_VTT
                    else -> return@mapNotNull null
                }
                PlaybackManager.Subtitle(url, mime, sub.language, sub.title)
            },
        )
    }
}
