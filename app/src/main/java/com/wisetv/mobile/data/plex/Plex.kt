package com.wisetv.mobile.data.plex

import android.media.MediaCodecList
import android.os.Build
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wisetv.mobile.BuildConfig
import com.wisetv.mobile.DebugProps
import com.wisetv.mobile.L
import com.wisetv.mobile.data.Http
import com.wisetv.mobile.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Plex client: links the account with a code (plex.tv/link), then uses every server the user
 * picks: their own and ones friends have shared. For each server it finds the best way in (home
 * network first, then internet, then Plex relay), browses libraries, and works out how to play
 * each file: as-is when the phone (or Chromecast) can decode it, otherwise converted by that server.
 * Network functions are suspending and run on IO.
 */
class Plex(private val http: Http, private val s: Settings) {

    data class Pin(val id: Long, val code: String)

    data class Connection(val uri: String, val local: Boolean, val relay: Boolean)

    /** A server as listed by plex.tv for this account. */
    data class Server(
        val name: String,
        val id: String,
        val accessToken: String,
        val owned: Boolean,
        val connections: List<Connection>,
    )

    /** A server the user chose to use, with the address that last worked. */
    data class SavedServer(
        val id: String,
        val name: String,
        val token: String,
        val owned: Boolean,
        val uri: String = "",
        val local: Boolean = true,
    )

    data class Library(val serverId: String, val serverName: String, val key: String, val title: String, val type: String) {
        val id: String get() = "$serverId:$key"
    }

    /** Libraries from every server that answered, plus the names of those that didn't. */
    data class Libraries(val libraries: List<Library>, val unreachable: List<String>)

    data class Media(
        val container: String?,
        val videoCodec: String?,
        val audioCodec: String?,
        val bitrateKbps: Int,
        val height: Int,
        val partKey: String?,
    )

    data class SubtitleFile(val key: String, val codec: String, val language: String?, val title: String?)

    data class Item(
        val serverId: String,
        val ratingKey: String,
        val type: String,
        val title: String,
        val year: String?,
        val summary: String?,
        val thumb: String?,
        val art: String?,
        val rating: Double?,
        val addedAt: Long,
        val durationMs: Long,
        val viewOffsetMs: Long,
        val viewCount: Int,
        val index: Int?,
        val parentIndex: Int?,
        val grandparentTitle: String?,
        val grandparentRatingKey: String?,
        val grandparentThumb: String?,
        val parentTitle: String? = null,
        val parentRatingKey: String? = null,
        val parentThumb: String? = null,
        val genres: List<String>,
        val directors: List<String>,
        val cast: List<String>,
        val contentRating: String?,
        val leafCount: Int,
        val viewedLeafCount: Int,
        val media: Media?,
        val subtitles: List<SubtitleFile>,
    ) {
        val watched: Boolean get() = viewCount > 0 && viewOffsetMs == 0L

        /** Unique across servers (rating keys are only unique within one server). */
        val uid: String get() = "$serverId:$ratingKey"
    }

    /** How to play an item: the address, and whether the server converts it. */
    data class Playback(
        val url: String,
        val transcodeSession: String?,
        val subtitles: List<Pair<String, SubtitleFile>>,
        val headers: Map<String, String>,
    )


    val linked: Boolean get() = s.plexToken.isNotEmpty()

    /** The servers in use. Backed by settings, so screens update when the choice changes. */
    val servers: List<SavedServer>
        get() {
            migrateSingleServer()
            val json = s.plexServersJson
            if (json.isBlank()) return emptyList()
            return runCatching {
                JsonParser.parseString(json).asJsonArray.map {
                    val o = it.asJsonObject
                    SavedServer(
                        id = o.str("id").orEmpty(),
                        name = o.str("name").orEmpty(),
                        token = o.str("token").orEmpty(),
                        owned = o.bool("owned"),
                        uri = o.str("uri").orEmpty(),
                        local = o.bool("local"),
                    )
                }
            }.getOrNull().orEmpty()
        }

    val hasServer: Boolean get() = linked && servers.isNotEmpty()

    fun server(id: String): SavedServer? = servers.firstOrNull { it.id == id }

    /** Versions before multi-server support kept one server in separate settings. */
    private fun migrateSingleServer() {
        if (s.plexServersJson.isNotBlank() || s.plexServerId.isEmpty()) return
        val one = SavedServer(s.plexServerId, s.plexServerName, s.plexServerToken, true, s.plexServerUri, s.plexServerLocal)
        s.plexServersJson = encode(listOf(one))
        s.plexServerId = ""
    }

    private val saveLock = Any()

    private fun save(list: List<SavedServer>) = synchronized(saveLock) { s.plexServersJson = encode(list) }

    private fun encode(list: List<SavedServer>): String = JsonArray().apply {
        list.forEach { srv ->
            add(
                JsonObject().apply {
                    addProperty("id", srv.id)
                    addProperty("name", srv.name)
                    addProperty("token", srv.token)
                    addProperty("owned", srv.owned)
                    addProperty("uri", srv.uri)
                    addProperty("local", srv.local)
                },
            )
        }
    }.toString()

    private fun update(id: String, change: (SavedServer) -> SavedServer) = synchronized(saveLock) {
        save(servers.map { if (it.id == id) change(it) else it })
    }

    private val clientId: String
        get() = s.plexClientId.ifEmpty { UUID.randomUUID().toString().also { s.plexClientId = it } }

    private val quick: OkHttpClient = http.api.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val connLocks = ConcurrentHashMap<String, Mutex>()
    private var cachedLibs: Pair<Long, Libraries>? = null
    private val cachedItems = ConcurrentHashMap<String, Pair<Long, List<Item>>>()

    /** Positions reported during this run (by [Item.uid]), newer than what a server may have answered yet. */
    val recentOffsets = ConcurrentHashMap<String, Long>()

    fun offsetOf(item: Item): Long = recentOffsets[item.uid] ?: item.viewOffsetMs

    // ------------------------------------------------------------------ plumbing

    private fun headers(b: Request.Builder, token: String?): Request.Builder {
        b.header("Accept", "application/json")
            .header("X-Plex-Client-Identifier", clientId)
            .header("X-Plex-Product", PRODUCT)
            .header("X-Plex-Version", BuildConfig.VERSION_NAME)
            .header("X-Plex-Platform", "Android")
            .header("X-Plex-Platform-Version", Build.VERSION.RELEASE ?: "9")
            .header("X-Plex-Device", Build.MODEL ?: "Android")
            .header("X-Plex-Device-Name", "WiseTV mobile (${Build.MODEL ?: "Android"})")
            .header("User-Agent", "WiseTV/${BuildConfig.VERSION_NAME}")
        if (!token.isNullOrEmpty()) b.header("X-Plex-Token", token)
        return b
    }

    private fun get(client: OkHttpClient, url: String, token: String?): JsonElement {
        val req = headers(Request.Builder().url(url), token).build()
        client.newCall(req).execute().use { r ->
            if (r.code == 401) throw PlexAuthException()
            if (!r.isSuccessful) throw IOException("Plex answered HTTP ${r.code}")
            val text = r.body?.string().orEmpty()
            return if (text.isBlank()) JsonObject() else JsonParser.parseString(text)
        }
    }

    private fun send(method: String, url: String, token: String?) {
        val body = if (method == "GET") null else ByteArray(0).toRequestBody("application/json".toMediaType())
        val req = headers(Request.Builder().url(url).method(method, body), token).build()
        http.api.newCall(req).execute().use { }
    }

    class PlexAuthException : IOException("Plex no longer accepts the saved sign-in. Link your account again in Settings.")

    // ------------------------------------------------------------------ account

    suspend fun startPin(): Pin = withContext(Dispatchers.IO) {
        val req = headers(Request.Builder().url("${DebugProps.plexTv}/api/v2/pins?strong=false"), null)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        http.api.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("plex.tv answered HTTP ${r.code}")
            val o = JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject
            Pin(o.get("id").asLong, o.get("code").asString)
        }
    }

    /** A page where signing in to Plex approves [pin] directly, without typing the code. */
    fun authUrl(pin: Pin): String =
        "https://app.plex.tv/auth#?clientID=${URLEncoder.encode(clientId, "UTF-8")}&code=${URLEncoder.encode(pin.code, "UTF-8")}" +
            "&context%5Bdevice%5D%5Bproduct%5D=$PRODUCT"

    /** Returns the account token once the code has been entered at plex.tv/link, else null. */
    suspend fun pollPin(pin: Pin): String? = withContext(Dispatchers.IO) {
        val o = get(http.api, "${DebugProps.plexTv}/api/v2/pins/${pin.id}", null).asJsonObject
        o.str("authToken")
    }

    suspend fun finishLink(token: String) = withContext(Dispatchers.IO) {
        val user = runCatching { get(http.api, "${DebugProps.plexTv}/api/v2/user", token).asJsonObject }.getOrNull()
        s.plexToken = token
        s.plexUser = user?.str("username") ?: user?.str("title") ?: "Plex account"
        L.i("plex linked user=${s.plexUser}")
    }

    fun unlink() {
        s.plexToken = ""
        s.plexUser = ""
        s.plexServerId = ""
        save(emptyList())
        invalidate()
    }

    /** Every server on the account: the user's own and ones shared with them. */
    suspend fun accountServers(): List<Server> = withContext(Dispatchers.IO) {
        val arr = get(http.api, "${DebugProps.plexTv}/api/v2/resources?includeHttps=1&includeRelay=1", s.plexToken).asJsonArray
        arr.mapNotNull { e ->
            val o = e.asJsonObject
            if (o.str("provides")?.contains("server") != true) return@mapNotNull null
            val conns = o.arr("connections").map { c ->
                val co = c.asJsonObject
                Connection(co.str("uri").orEmpty(), co.bool("local"), co.bool("relay"))
            } + o.arr("connections").mapNotNull { c ->
                // Plain address too: some home routers refuse to resolve Plex's *.plex.direct names.
                val co = c.asJsonObject
                val addr = co.str("address") ?: return@mapNotNull null
                if (!co.bool("local") || co.bool("relay")) return@mapNotNull null
                Connection("http://$addr:${co.str("port") ?: "32400"}", local = true, relay = false)
            }
            Server(
                name = o.str("name") ?: "Plex server",
                id = o.str("clientIdentifier") ?: return@mapNotNull null,
                accessToken = o.str("accessToken") ?: s.plexToken,
                owned = o.bool("owned"),
                connections = conns.filter { it.uri.isNotEmpty() }.distinctBy { it.uri },
            )
        }.sortedWith(compareByDescending<Server> { it.owned }.thenBy { it.name.lowercase(Locale.US) })
    }

    /**
     * Uses exactly [chosen] from now on and connects to each one (in parallel).
     * Returns, per server name, null when it connected or the reason it couldn't.
     */
    suspend fun setServers(chosen: List<Server>): Map<String, String?> {
        val keep = servers.associateBy { it.id }
        save(chosen.map { srv -> SavedServer(srv.id, srv.name, srv.accessToken, srv.owned, keep[srv.id]?.uri ?: "", keep[srv.id]?.local ?: true) })
        invalidate()
        L.i("plex servers selected: ${chosen.joinToString { it.name }}")
        return coroutineScope {
            chosen.map { srv ->
                async {
                    srv.name to try {
                        connection(srv.id, srv)
                        null
                    } catch (e: Exception) {
                        e.message ?: "couldn't connect"
                    }
                }
            }.awaitAll().toMap()
        }
    }

    // ------------------------------------------------------------------ connections

    /** The working address of one server, finding one if needed. */
    private suspend fun connection(serverId: String, known: Server? = null): Connection =
        connLocks.getOrPut(serverId) { Mutex() }.withLock {
            val saved = server(serverId) ?: throw IOException("That Plex server is no longer in use.")
            if (saved.uri.isNotEmpty()) return@withLock Connection(saved.uri, saved.local, false)
            val fresh = known ?: accountServers().firstOrNull { it.id == serverId }
                ?: throw IOException("The Plex server “${saved.name}” isn't on your account any more.")
            val token = fresh.accessToken.ifEmpty { saved.token }
            val ordered = fresh.connections.sortedBy {
                when {
                    it.local && !it.relay -> 0
                    !it.relay -> 1
                    else -> 2
                }
            }
            for (c in ordered) {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { get(quick, "${c.uri}/identity", token) }.isSuccess
                }
                L.i("plex try ${c.uri} local=${c.local} relay=${c.relay} ok=$ok")
                if (ok) {
                    update(serverId) { it.copy(token = token, uri = c.uri, local = c.local) }
                    L.i("plex connected ${saved.name} via ${c.uri} local=${c.local}")
                    return@withLock c
                }
            }
            throw IOException("Can't reach the Plex server “${saved.name}”. Is it switched on?")
        }

    /** Runs [block] against one server, finding a new address once if the saved one stopped working. */
    private suspend fun <T> onServer(serverId: String, block: (base: String, token: String) -> T): T {
        if (!linked) throw IOException("Link Plex in Settings first.")
        val c = connection(serverId)
        return try {
            withContext(Dispatchers.IO) { block(c.uri, server(serverId)?.token.orEmpty()) }
        } catch (e: PlexAuthException) {
            throw e
        } catch (e: IOException) {
            L.i("plex ${c.uri} failed (${e.message}); looking for another address")
            update(serverId) { it.copy(uri = "") }
            val c2 = connection(serverId)
            withContext(Dispatchers.IO) { block(c2.uri, server(serverId)?.token.orEmpty()) }
        }
    }

    /** Runs [block] on every server at once; a server that fails is left out of the result. */
    private suspend fun <T> onAll(block: suspend (SavedServer) -> List<T>): List<T> = coroutineScope {
        servers.map { srv -> async { runCatching { block(srv) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
    }

    fun isLocal(serverId: String): Boolean = server(serverId)?.local ?: true

    // ------------------------------------------------------------------ browsing

    suspend fun libraries(force: Boolean = false): Libraries {
        val c = cachedLibs
        if (!force && c != null && System.currentTimeMillis() - c.first < 10 * 60_000L) return c.second
        val all = servers
        val results = coroutineScope {
            all.map { srv ->
                async {
                    srv to runCatching {
                        onServer(srv.id) { base, tok ->
                            get(http.api, "$base/library/sections", tok).container().arr("Directory").map {
                                val o = it.asJsonObject
                                Library(srv.id, srv.name, o.str("key").orEmpty(), o.str("title") ?: "Library", o.str("type").orEmpty())
                            }.filter { it.type == "movie" || it.type == "show" }
                        }
                    }
                }
            }.awaitAll()
        }
        val libs = Libraries(
            libraries = results.flatMap { it.second.getOrDefault(emptyList()) },
            unreachable = results.filter { it.second.isFailure }.map { it.first.name },
        )
        if (libs.unreachable.isEmpty()) cachedLibs = System.currentTimeMillis() to libs
        return libs
    }

    /** Every title in a library, A to Z, or the newest additions when [recent]. */
    suspend fun items(lib: Library, recent: Boolean): List<Item> {
        val key = "${lib.id}|$recent"
        cachedItems[key]?.let { if (System.currentTimeMillis() - it.first < 5 * 60_000L) return it.second }
        val list = onServer(lib.serverId) { base, tok ->
            val path = if (recent) "recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=100" else "all"
            get(http.api, "$base/library/sections/${lib.key}/$path", tok).container().arr("Metadata").map { parseItem(lib.serverId, it.asJsonObject) }
        }.let { if (lib.type == "show") asShows(it) else it }
        cachedItems[key] = System.currentTimeMillis() to list
        return list
    }

    /**
     * A TV library's "recently added" list holds new seasons and episodes, not shows. Turns each
     * into its show (newest first, once per show) so it opens like any other show.
     */
    private fun asShows(items: List<Item>): List<Item> = items.mapNotNull { it ->
        when (it.type) {
            "show" -> it
            "season" -> it.parentRatingKey?.let { rk ->
                it.copy(ratingKey = rk, type = "show", title = it.parentTitle ?: it.title, thumb = it.parentThumb ?: it.thumb, year = null, rating = null)
            }
            "episode" -> it.grandparentRatingKey?.let { rk ->
                it.copy(ratingKey = rk, type = "show", title = it.grandparentTitle ?: it.title, thumb = it.grandparentThumb ?: it.thumb, year = null, rating = null)
            }
            else -> null
        }?.copy(viewOffsetMs = 0, durationMs = 0)
    }.distinctBy { it.ratingKey }

    suspend fun metadata(serverId: String, ratingKey: String): Item = onServer(serverId) { base, tok ->
        val m = get(http.api, "$base/library/metadata/$ratingKey", tok).container().arr("Metadata").firstOrNull()
            ?: throw IOException("This title is no longer on that Plex server.")
        parseItem(serverId, m.asJsonObject)
    }

    suspend fun episodes(serverId: String, showKey: String): List<Item> = onServer(serverId) { base, tok ->
        get(http.api, "$base/library/metadata/$showKey/allLeaves", tok).container().arr("Metadata").map { parseItem(serverId, it.asJsonObject) }
    }

    /** "Continue watching" from every server: half-watched titles and next episodes. */
    suspend fun onDeck(): List<Item> = onAll { srv ->
        onServer(srv.id) { base, tok ->
            get(http.api, "$base/library/onDeck", tok).container().arr("Metadata").map { parseItem(srv.id, it.asJsonObject) }
        }
    }

    suspend fun search(query: String): List<Item> = onAll { srv ->
        onServer(srv.id) { base, tok ->
            val q = URLEncoder.encode(query, "UTF-8")
            get(http.api, "$base/hubs/search?query=$q&limit=15", tok).container().arr("Hub").flatMap { h ->
                h.asJsonObject.arr("Metadata").map { parseItem(srv.id, it.asJsonObject) }
            }.filter { it.type == "movie" || it.type == "show" || it.type == "episode" }
        }
    }

    fun invalidate() {
        cachedLibs = null
        cachedItems.clear()
    }

    /** A small, server-resized poster or still, so the phone doesn't download full-size art. */
    fun image(serverId: String, path: String?, width: Int, height: Int): String? {
        val srv = server(serverId) ?: return null
        if (path.isNullOrEmpty() || srv.uri.isEmpty()) return null
        val u = URLEncoder.encode(path, "UTF-8")
        return "${srv.uri}/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1&url=$u&X-Plex-Token=${srv.token}"
    }

    // ------------------------------------------------------------------ playback

    /** Works out the best way to play [item]: the file as-is when possible, converted otherwise. */
    suspend fun playback(item: Item, forCast: Boolean = false): Playback {
        val conn = connection(item.serverId)
        val base = conn.uri
        val tok = server(item.serverId)?.token.orEmpty()
        val local = conn.local
        val m = item.media
        val limit = if (local) 0 else s.plexRemoteKbps
        val playable = if (forCast) m != null && Codecs.castCanPlay(m) else m != null && Codecs.canDirectPlay(m)
        val direct = m?.partKey != null && playable && (limit == 0 || m.bitrateKbps in 1..limit)
        val subs = item.subtitles.map { sub -> "$base${sub.key}?X-Plex-Token=$tok" to sub }
        if (direct) {
            L.i("plex play direct ${item.title} ${m?.container}/${m?.videoCodec}/${m?.audioCodec}")
            return Playback("$base${m!!.partKey}?X-Plex-Token=$tok", null, subs, playHeaders(tok, null))
        }
        val session = UUID.randomUUID().toString()
        val profile = "add-transcode-target(type=videoProfile&context=streaming&protocol=hls&container=mpegts" +
            "&videoCodec=${if (Codecs.hevc && !forCast) "h264,hevc" else "h264"}&audioCodec=${if (forCast) "aac" else "aac,ac3,eac3"})" +
            "+add-limitation(scope=videoAudioCodec&scopeName=*&type=upperBound&name=audio.channels&value=6)"
        val q = linkedMapOf(
            "hasMDE" to "1",
            "path" to "/library/metadata/${item.ratingKey}",
            "mediaIndex" to "0",
            "partIndex" to "0",
            "protocol" to "hls",
            "fastSeek" to "1",
            "directPlay" to "0",
            "directStream" to "1",
            "directStreamAudio" to "1",
            "copyts" to "1",
            "subtitles" to "auto",
            "location" to if (local) "lan" else "wan",
            "session" to session,
            "X-Plex-Session-Identifier" to session,
            "X-Plex-Client-Identifier" to clientId,
            "X-Plex-Product" to PRODUCT,
            "X-Plex-Platform" to "Android",
            "X-Plex-Device" to if (forCast) "Chromecast" else (Build.MODEL ?: "Android"),
            "X-Plex-Client-Profile-Extra" to profile,
            "X-Plex-Token" to tok,
        )
        if (forCast) q["X-Plex-Client-Identifier"] = "$clientId-cast"
        if (limit > 0) {
            q["maxVideoBitrate"] = limit.toString()
            q["videoBitrate"] = limit.toString()
            q["videoResolution"] = when {
                limit >= 12_000 -> "1920x1080"
                limit >= 4_000 -> "1280x720"
                else -> "854x480"
            }
        }
        val qs = q.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        L.i("plex play transcode cast=$forCast ${item.title} ${m?.container}/${m?.videoCodec}/${m?.audioCodec} limit=$limit")
        return Playback("$base/video/:/transcode/universal/start.m3u8?$qs", session, emptyList(), playHeaders(tok, session))
    }

    private fun playHeaders(token: String, session: String?): Map<String, String> = buildMap {
        put("X-Plex-Token", token)
        put("X-Plex-Client-Identifier", clientId)
        put("X-Plex-Product", PRODUCT)
        put("X-Plex-Platform", "Android")
        if (session != null) put("X-Plex-Session-Identifier", session)
    }

    /** Tells the server where playback is, so other Plex apps resume from the same spot. */
    suspend fun timeline(serverId: String, ratingKey: String, state: String, positionMs: Long, durationMs: Long, session: String?) {
        recentOffsets["$serverId:$ratingKey"] = if (positionMs >= durationMs * 0.95) 0L else positionMs
        runCatching {
            onServer(serverId) { base, tok ->
                send(
                    "GET",
                    "$base/:/timeline?ratingKey=$ratingKey&key=%2Flibrary%2Fmetadata%2F$ratingKey&state=$state" +
                        "&time=$positionMs&duration=$durationMs" + (session?.let { "&X-Plex-Session-Identifier=$it" } ?: ""),
                    tok,
                )
            }
        }
    }

    suspend fun markWatched(serverId: String, ratingKey: String) {
        recentOffsets["$serverId:$ratingKey"] = 0L
        runCatching {
            onServer(serverId) { base, tok -> send("GET", "$base/:/scrobble?key=$ratingKey&identifier=com.plexapp.plugins.library", tok) }
        }
        cachedItems.clear()
    }

    suspend fun stopTranscode(serverId: String, session: String) {
        runCatching { onServer(serverId) { base, tok -> send("GET", "$base/video/:/transcode/universal/stop?session=$session", tok) } }
    }

    // ------------------------------------------------------------------ parsing

    private fun parseItem(serverId: String, o: JsonObject): Item {
        val mediaO = o.arr("Media").firstOrNull()?.asJsonObject
        val partO = mediaO?.arr("Part")?.firstOrNull()?.asJsonObject
        val subs = partO?.arr("Stream")?.mapNotNull { st ->
            val so = st.asJsonObject
            if (so.str("streamType") != "3") return@mapNotNull null
            val key = so.str("key") ?: return@mapNotNull null // only separate subtitle files have a key
            SubtitleFile(key, so.str("codec").orEmpty().lowercase(Locale.US), so.str("languageCode") ?: so.str("language"), so.str("displayTitle") ?: so.str("title"))
        }.orEmpty()
        return Item(
            serverId = serverId,
            ratingKey = o.str("ratingKey").orEmpty(),
            type = o.str("type").orEmpty(),
            title = o.str("title").orEmpty(),
            year = o.str("year"),
            summary = o.str("summary")?.takeIf { it.isNotBlank() },
            thumb = o.str("thumb"),
            art = o.str("art"),
            rating = (o.str("audienceRating") ?: o.str("rating"))?.toDoubleOrNull(),
            addedAt = (o.str("addedAt")?.toLongOrNull() ?: 0L) * 1000L,
            durationMs = o.str("duration")?.toLongOrNull() ?: 0L,
            viewOffsetMs = o.str("viewOffset")?.toLongOrNull() ?: 0L,
            viewCount = o.str("viewCount")?.toIntOrNull() ?: 0,
            index = o.str("index")?.toIntOrNull(),
            parentIndex = o.str("parentIndex")?.toIntOrNull(),
            grandparentTitle = o.str("grandparentTitle"),
            grandparentRatingKey = o.str("grandparentRatingKey"),
            grandparentThumb = o.str("grandparentThumb"),
            parentTitle = o.str("parentTitle"),
            parentRatingKey = o.str("parentRatingKey"),
            parentThumb = o.str("parentThumb"),
            genres = o.tags("Genre"),
            directors = o.tags("Director"),
            cast = o.tags("Role").take(6),
            contentRating = o.str("contentRating"),
            leafCount = o.str("leafCount")?.toIntOrNull() ?: 0,
            viewedLeafCount = o.str("viewedLeafCount")?.toIntOrNull() ?: 0,
            media = mediaO?.let {
                Media(
                    container = (partO?.str("container") ?: it.str("container"))?.lowercase(Locale.US),
                    videoCodec = it.str("videoCodec")?.lowercase(Locale.US),
                    audioCodec = it.str("audioCodec")?.lowercase(Locale.US),
                    bitrateKbps = it.str("bitrate")?.toIntOrNull() ?: 0,
                    height = it.str("height")?.toIntOrNull() ?: 0,
                    partKey = partO?.str("key"),
                )
            },
            subtitles = subs,
        )
    }

    companion object {
        const val PRODUCT = "WiseTV"

        private fun JsonElement.container(): JsonObject =
            (this as? JsonObject)?.get("MediaContainer")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

        internal fun JsonObject.str(name: String): String? {
            val e = get(name) ?: return null
            if (e.isJsonNull || !e.isJsonPrimitive) return null
            return e.asString
        }

        private fun JsonObject.bool(name: String): Boolean {
            val e = get(name) ?: return false
            return e.isJsonPrimitive && (e.asString == "true" || e.asString == "1")
        }

        private fun JsonObject.arr(name: String): JsonArray =
            get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

        private fun JsonObject.tags(name: String): List<String> =
            arr(name).mapNotNull { (it as? JsonObject)?.str("tag") }
    }
}

/** What this phone can play without help. Checked against the device's actual decoders. */
object Codecs {
    private val decoders: Set<String> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
                .flatMap { it.supportedTypes.toList() }.map { it.lowercase(Locale.US) }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun has(mime: String) = mime in decoders

    val hevc: Boolean get() = has("video/hevc")

    private val containers = setOf("mp4", "m4v", "mov", "mkv", "webm", "ts", "mpegts")

    fun video(codec: String?): Boolean = when (codec) {
        "h264", "avc1" -> true
        "hevc", "h265", "hvc1" -> has("video/hevc")
        "vp9" -> has("video/x-vnd.on2.vp9")
        "av1" -> has("video/av01")
        "mpeg2video" -> has("video/mpeg2")
        else -> false
    }

    fun audio(codec: String?): Boolean = when (codec) {
        null -> true
        "aac", "mp3", "flac", "opus", "vorbis", "pcm" -> true
        "ac3" -> has("audio/ac3")
        "eac3" -> has("audio/eac3")
        "truehd" -> has("audio/true-hd")
        "dca", "dts" -> has("audio/vnd.dts")
        else -> false
    }

    fun canDirectPlay(m: Plex.Media): Boolean =
        m.container in containers && video(m.videoCodec) && audio(m.audioCodec)

    /** What a Chromecast's standard receiver plays as a file: MP4/WebM with H.264 (or VP9) and AAC/MP3/Opus. */
    fun castCanPlay(m: Plex.Media): Boolean =
        m.container in setOf("mp4", "m4v", "mov", "webm") &&
            m.videoCodec in setOf("h264", "avc1", "vp9") &&
            (m.audioCodec == null || m.audioCodec in setOf("aac", "mp3", "opus", "vorbis"))
}
