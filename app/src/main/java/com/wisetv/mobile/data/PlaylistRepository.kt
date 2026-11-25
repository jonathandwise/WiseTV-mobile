package com.wisetv.mobile.data

import androidx.room.withTransaction
import com.wisetv.mobile.data.db.AppDatabase
import com.wisetv.mobile.data.db.ChannelEntity
import com.wisetv.mobile.data.db.GroupEntity
import com.wisetv.mobile.data.db.GroupKind
import com.wisetv.mobile.data.db.PlaylistEntity
import com.wisetv.mobile.data.db.PlaylistType
import com.wisetv.mobile.data.db.SeriesEntity
import com.wisetv.mobile.data.db.VodEntity
import com.wisetv.mobile.data.m3u.M3uParser
import com.wisetv.mobile.data.xtream.XtreamApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URI
import com.wisetv.mobile.L

class PlaylistRepository(
    private val db: AppDatabase,
    private val http: Http,
    private val settings: Settings,
) {
    fun xtream(pl: PlaylistEntity) = XtreamApi(http, pl.url, pl.username, pl.password, pl.userAgent)

    /** Set by [addXtream] when the login worked but every connection was already in use: the limit. */
    @Volatile
    var addedWhileInUse: Int? = null
        private set

    /** Signs in to check the details, then saves the playlist. Returns its id. */
    suspend fun addXtream(name: String, server: String, user: String, pass: String, epgUrl: String = "", userAgent: String = ""): Long =
        withContext(Dispatchers.IO) {
            val (api, auth) = loginTryingVariants(server, user.trim(), pass.trim(), userAgent)
            addedWhileInUse = if (auth.inUse) auth.maxConnections else null
            if (auth.inUse) L.i("login ok but connections in use active=${auth.activeConnections} max=${auth.maxConnections}")
            val id = db.playlists().insert(
                PlaylistEntity(
                    name = name.trim().ifBlank { hostOf(api.base) },
                    type = PlaylistType.XTREAM,
                    url = api.base,
                    username = user.trim(),
                    password = pass.trim(),
                    epgUrl = epgUrl.trim(),
                    userAgent = userAgent.trim(),
                    maxConnections = auth.maxConnections,
                    expiresAt = auth.expiresAt,
                    status = auth.status,
                ),
            )
            if (settings.activePlaylistId <= 0L) settings.activePlaylistId = id
            id
        }

    /**
     * Logs in with the address as typed; if that doesn't lead to an Xtream login at all, tries the
     * other of http/https and the bare host (copied addresses often differ in just that).
     * A server that answers and refuses the login isn't retried.
     */
    fun loginTryingVariants(server: String, user: String, pass: String, userAgent: String): Pair<XtreamApi, XtreamApi.AuthInfo> {
        var firstError: Exception? = null
        for (candidate in XtreamApi.addressVariants(server)) {
            val api = XtreamApi(http, candidate, user, pass, userAgent)
            try {
                val auth = api.authenticate()
                if (firstError != null) L.i("xtream login worked at another address scheme=${candidate.substringBefore(':')}")
                return api to auth
            } catch (e: XtreamApi.WrongAddressException) {
                L.i("xtream login failed scheme=${candidate.substringBefore(':')} pathless=${candidate.count { it == '/' } <= 2}: ${e.message?.take(60)}")
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: IOException("Login failed. Check the server address.")
    }

    /** Adds an M3U link. Xtream "get.php" links are upgraded to full Xtream logins (adds movies and series). */
    suspend fun addM3u(name: String, url: String, epgUrl: String = "", userAgent: String = ""): Long {
        val trimmed = url.trim()
        XtreamApi.credsFromM3uUrl(trimmed)?.let { c ->
            try {
                return addXtream(name, c.server, c.username, c.password, epgUrl, userAgent)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Not a working Xtream panel; treat it as a plain M3U link.
            }
        }
        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            throw IOException("The playlist link must start with http:// or https://")
        }
        val id = db.playlists().insert(
            PlaylistEntity(
                name = name.trim().ifBlank { hostOf(trimmed) },
                type = PlaylistType.M3U,
                url = trimmed,
                epgUrl = epgUrl.trim(),
                userAgent = userAgent.trim(),
                maxConnections = 1,
            ),
        )
        if (settings.activePlaylistId <= 0L) settings.activePlaylistId = id
        return id
    }

    /**
     * Adds one of the free playlists ([Presets]) and loads its channels. Returns its id, and whether
     * it was already there. A playlist that fails to load is removed again.
     */
    suspend fun addPreset(p: Presets.Preset, sync: SyncCoordinator): Pair<Long, Boolean> {
        db.playlists().all().firstOrNull { it.type == PlaylistType.M3U && it.url == p.url }?.let { return it.id to true }
        val id = db.playlists().insert(PlaylistEntity(name = p.name, type = PlaylistType.M3U, url = p.url, maxConnections = 10))
        try {
            sync.syncPlaylist(id, withEpg = false)
        } catch (e: Exception) {
            deletePlaylist(id)
            if (e is CancellationException) throw e
            throw IOException("${p.name} couldn't be loaded: ${e.message}")
        }
        if (settings.activePlaylistId <= 0L) settings.activePlaylistId = id
        sync.launchEpg(id)
        return id to false
    }

    suspend fun sync(id: Long, progress: (String) -> Unit) {
        val pl = db.playlists().get(id) ?: return
        when (pl.type) {
            PlaylistType.XTREAM -> syncXtream(pl, progress)
            else -> syncM3u(pl, progress)
        }
        db.playlists().setLastSync(id, System.currentTimeMillis())
    }

    private suspend fun syncXtream(pl: PlaylistEntity, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        val api = xtream(pl)
        progress("Signing in to ${pl.name}…")
        val auth = api.authenticate()
        db.playlists().setAuth(pl.id, auth.maxConnections, auth.expiresAt, auth.status)

        progress("Loading channels…")
        val liveCats = api.categories("get_live_categories")
        val catNames = liveCats.associate { it.id to it.name }
        val builder = ChannelListBuilder(db.channels().allFor(pl.id))
        val ext = settings.liveFormat
        api.streamArray("get_live_streams") { m ->
            val sid = m["stream_id"] ?: return@streamArray
            builder.add(
                ChannelEntity(
                    playlistId = pl.id,
                    streamKey = sid,
                    name = (m["name"] ?: "Channel $sid").trim(),
                    number = m["num"]?.toDoubleOrNull()?.toInt() ?: 0,
                    logo = m["stream_icon"]?.trim()?.takeIf { it.isNotEmpty() },
                    groupName = catNames[m["category_id"]] ?: "Uncategorized",
                    epgId = m["epg_channel_id"]?.trim()?.lowercase().orEmpty(),
                    url = api.liveUrl(sid, ext),
                    userAgent = pl.userAgent.ifBlank { null },
                ),
            )
        }
        val groupOrder = (liveCats.map { it.name } + builder.groupNames()).distinct().filter { it in builder.groupNames() }
        replaceChannels(pl.id, builder.build(), groupOrder)

        val gen = System.currentTimeMillis()
        var vodOk = false
        try {
            progress("Loading movies…")
            val vodCats = api.categories("get_vod_categories")
            val batch = ArrayList<VodEntity>(1000)
            val used = HashSet<String>()
            api.streamArray("get_vod_streams") { m ->
                val sid = m["stream_id"] ?: return@streamArray
                val cat = m["category_id"]?.takeIf { it.isNotBlank() } ?: "0"
                used += cat
                batch += VodEntity(
                    playlistId = pl.id,
                    gen = gen,
                    streamId = sid,
                    name = (m["name"] ?: "Movie $sid").trim(),
                    poster = m["stream_icon"]?.trim()?.takeIf { it.isNotEmpty() },
                    rating = m["rating"]?.takeIf { it.isNotBlank() && it != "0" },
                    categoryId = cat,
                    ext = m["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4",
                    added = (m["added"]?.toLongOrNull() ?: 0L) * 1000L,
                    year = m["year"]?.takeIf { it.isNotBlank() },
                )
                if (batch.size >= 1000) {
                    db.vod().insertAllBlocking(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) db.vod().insertAllBlocking(batch)
            replaceGroups(pl.id, GroupKind.MOVIE, categoriesWithFallback(vodCats, used))

            progress("Loading series…")
            val seriesCats = api.categories("get_series_categories")
            val sBatch = ArrayList<SeriesEntity>(1000)
            val sUsed = HashSet<String>()
            api.streamArray("get_series") { m ->
                val sid = m["series_id"] ?: return@streamArray
                val cat = m["category_id"]?.takeIf { it.isNotBlank() } ?: "0"
                sUsed += cat
                sBatch += SeriesEntity(
                    playlistId = pl.id,
                    gen = gen,
                    seriesId = sid,
                    name = (m["name"] ?: "Series $sid").trim(),
                    cover = m["cover"]?.trim()?.takeIf { it.isNotEmpty() },
                    plot = m["plot"]?.take(600),
                    rating = m["rating"]?.takeIf { it.isNotBlank() && it != "0" },
                    genre = m["genre"],
                    categoryId = cat,
                    lastModified = (m["last_modified"]?.toLongOrNull() ?: 0L) * 1000L,
                    year = m["year"] ?: m["releaseDate"]?.take(4),
                )
                if (sBatch.size >= 1000) {
                    db.series().insertAllBlocking(sBatch)
                    sBatch.clear()
                }
            }
            if (sBatch.isNotEmpty()) db.series().insertAllBlocking(sBatch)
            replaceGroups(pl.id, GroupKind.SERIES, categoriesWithFallback(seriesCats, sUsed))
            vodOk = true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            db.vod().deleteGen(pl.id, gen)
            db.series().deleteGen(pl.id, gen)
        }
        if (vodOk) {
            db.playlists().setVodGen(pl.id, gen)
            db.vod().deleteOtherGens(pl.id, gen)
            db.series().deleteOtherGens(pl.id, gen)
        }
    }

    private suspend fun syncM3u(pl: PlaylistEntity, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        progress("Downloading ${pl.name}…")
        val builder = ChannelListBuilder(db.channels().allFor(pl.id))
        val gen = System.currentTimeMillis()
        val vodBatch = ArrayList<VodEntity>(1000)
        val vodGroups = LinkedHashSet<String>()
        var vodCount = 0
        var headerEpg = ""
        val response = try {
            http.api.newCall(http.request(pl.url, pl.userAgent)).execute()
        } catch (e: IOException) {
            throw IOException(XtreamApi.unreachableMessage(pl.url, e) + " Also check whether the link starts with http:// or https://.")
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw IOException(
                    "Playlist download failed (HTTP ${resp.code})." +
                        if (resp.code == 404) " Check the link is complete, and whether it starts with http:// or https://." else "",
                )
            }
            val reader = BufferedReader(InputStreamReader(Http.maybeGunzip(resp.body!!.byteStream()), Charsets.UTF_8), 64 * 1024)
            M3uParser().parse(reader, onHeader = { headerEpg = it.joinToString("\n") }) { e ->
                when (M3uParser.classify(e.url)) {
                    M3uParser.Kind.LIVE -> builder.add(
                        ChannelEntity(
                            playlistId = pl.id,
                            streamKey = e.url,
                            name = e.name,
                            number = e.number ?: 0,
                            logo = e.logo,
                            groupName = e.group,
                            epgId = e.tvgId.trim().lowercase(),
                            url = e.url,
                            userAgent = e.userAgent ?: pl.userAgent.ifBlank { null },
                        ),
                    )
                    else -> {
                        vodGroups += e.group
                        vodBatch += VodEntity(
                            playlistId = pl.id,
                            gen = gen,
                            streamId = e.url,
                            name = e.name,
                            poster = e.logo,
                            categoryId = e.group,
                            ext = M3uParser.extensionOf(e.url),
                            directUrl = e.url,
                        )
                        vodCount++
                        if (vodBatch.size >= 1000) {
                            db.vod().insertAllBlocking(vodBatch)
                            vodBatch.clear()
                        }
                    }
                }
            }
        }
        if (vodBatch.isNotEmpty()) db.vod().insertAllBlocking(vodBatch)
        if (builder.size == 0 && vodCount == 0) {
            db.vod().deleteGen(pl.id, gen)
            throw IOException("No channels were found in this playlist.")
        }
        db.playlists().setM3uEpg(pl.id, headerEpg)
        replaceChannels(pl.id, builder.build(), builder.groupNames().toList())
        replaceGroups(pl.id, GroupKind.MOVIE, vodGroups.map { XtreamApi.Category(it, it) })
        db.playlists().setVodGen(pl.id, gen)
        db.vod().deleteOtherGens(pl.id, gen)
        db.series().deleteForPlaylist(pl.id)
    }

    private fun categoriesWithFallback(cats: List<XtreamApi.Category>, used: Set<String>): List<XtreamApi.Category> {
        val known = cats.map { it.id }.toHashSet()
        val extra = used.filter { it !in known }.map { XtreamApi.Category(it, if (it == "0") "Uncategorized" else "Other") }
        return cats.filter { it.id in used } + extra
    }

    private suspend fun replaceChannels(pid: Long, channels: List<ChannelEntity>, groups: List<String>) {
        db.withTransaction {
            val hidden = db.groups().allBlocking(pid, GroupKind.LIVE).filter { it.hidden }.map { it.categoryId }.toHashSet()
            db.groups().deleteBlocking(pid, GroupKind.LIVE)
            db.groups().insertAllBlocking(groups.mapIndexed { i, g -> GroupEntity(pid, GroupKind.LIVE, g, g, g in hidden, i) })
            db.channels().deleteForPlaylistBlocking(pid)
            channels.chunked(2000).forEach { db.channels().insertAllBlocking(it) }
        }
    }

    private suspend fun replaceGroups(pid: Long, kind: String, cats: List<XtreamApi.Category>) {
        db.withTransaction {
            val hidden = db.groups().allBlocking(pid, kind).filter { it.hidden }.map { it.categoryId }.toHashSet()
            db.groups().deleteBlocking(pid, kind)
            db.groups().insertAllBlocking(cats.distinctBy { it.id }.mapIndexed { i, c -> GroupEntity(pid, kind, c.id, c.name, c.id in hidden, i) })
        }
    }

    suspend fun deletePlaylist(id: Long) {
        db.withTransaction {
            db.channels().deleteForPlaylist(id)
            db.groups().deleteForPlaylist(id)
            db.programs().deleteForPlaylist(id)
            db.vod().deleteForPlaylist(id)
            db.series().deleteForPlaylist(id)
            db.favorites().deleteForPlaylist(id)
            db.progress().deleteForPlaylist(id)
            db.playlists().delete(id)
        }
        if (settings.activePlaylistId == id) {
            settings.activePlaylistId = db.playlists().all().firstOrNull()?.id ?: 0L
        }
    }

    /** Builds the channel list while keeping ids, favourites and hidden flags from the previous sync. */
    private class ChannelListBuilder(existing: List<ChannelEntity>) {
        private val old = existing.associateBy { it.streamKey }
        private val usedIds = HashSet<Long>()
        private val list = ArrayList<ChannelEntity>()
        private val groups = LinkedHashSet<String>()
        val size get() = list.size

        fun add(c: ChannelEntity) {
            val prev = old[c.streamKey]
            val keepId = prev != null && usedIds.add(prev.id)
            list += c.copy(
                id = if (keepId) prev!!.id else 0,
                favorite = prev?.favorite ?: false,
                favOrder = prev?.favOrder ?: 0,
                hidden = prev?.hidden ?: false,
                number = if (c.number > 0) c.number else list.size + 1,
            )
            groups += c.groupName
        }

        fun groupNames(): Set<String> = groups

        fun build(): List<ChannelEntity> =
            list.withIndex()
                .sortedWith(compareBy({ it.value.number }, { it.index }))
                .mapIndexed { i, iv -> iv.value.copy(sortIndex = i) }
    }

    companion object {
        fun hostOf(url: String): String = try {
            URI(url).host ?: url
        } catch (e: Exception) {
            url
        }
    }
}
