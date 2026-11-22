package com.wisetv.mobile.data.xtream

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.wisetv.mobile.data.Http
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Minimal Xtream Codes client. All functions block; call them on an IO dispatcher.
 */
class XtreamApi(
    private val http: Http,
    server: String,
    val username: String,
    val password: String,
    private val userAgent: String?,
) {
    val base: String = normalizeServer(server)

    data class Category(val id: String, val name: String)

    data class AuthInfo(
        val status: String,
        val expiresAt: Long,
        val maxConnections: Int,
        val activeConnections: Int,
        val timezone: String?,
    ) {
        /** Every connection the subscription allows is already being used. */
        val inUse: Boolean get() = activeConnections >= maxConnections
    }

    /** The provider refused because its connection limit is used up (by another app or device). */
    class ConnectionLimitException(max: Int?) : IOException(inUseMessage(max ?: 1))

    data class Episode(
        val id: String,
        val season: Int,
        val number: Int,
        val title: String,
        val ext: String,
        val plot: String?,
        val image: String?,
        val durationSecs: Int?,
    )

    data class SeriesInfo(
        val name: String?,
        val plot: String?,
        val cast: String?,
        val genre: String?,
        val releaseDate: String?,
        val rating: String?,
        val cover: String?,
        val backdrop: String?,
        val episodes: List<Episode>,
    )

    data class VodInfo(
        val plot: String?,
        val genre: String?,
        val cast: String?,
        val director: String?,
        val releaseDate: String?,
        val duration: String?,
        val rating: String?,
        val image: String?,
        val backdrop: String?,
        val ext: String?,
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun apiUrl(action: String? = null, extra: String = ""): String = buildString {
        append(base).append("/player_api.php?username=").append(enc(username))
        append("&password=").append(enc(password))
        if (action != null) append("&action=").append(action)
        append(extra)
    }

    fun epgUrl(): String = "$base/xmltv.php?username=${enc(username)}&password=${enc(password)}"
    fun liveUrl(streamId: String, ext: String) = "$base/live/$username/$password/$streamId.$ext"
    fun movieUrl(streamId: String, ext: String) = "$base/movie/$username/$password/$streamId.$ext"
    fun episodeUrl(episodeId: String, ext: String) = "$base/series/$username/$password/$episodeId.$ext"

    private fun open(url: String): Response {
        val r = http.api.newCall(http.request(url, userAgent)).execute()
        if (!r.isSuccessful) {
            val code = r.code
            r.close()
            when (code) {
                401, 403 -> throw IOException("The server refused the login (HTTP $code). Check the username and password. $OTHER_APP_HINT")
                429, 456, 458, 509 -> throw ConnectionLimitException(null)
                else -> throw IOException("The server returned an error (HTTP $code).")
            }
        }
        return r
    }

    private fun tree(url: String): JsonElement = open(url).use { r ->
        val text = r.body?.string().orEmpty()
        try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            throw IOException("The server didn't return Xtream data. Check the server address.")
        }
    }

    fun authenticate(): AuthInfo {
        val el = tree(apiUrl())
        val root = el.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("Login failed. Check the server address, username and password.")
        val ui = root.obj("user_info") ?: throw IOException("Login failed. Check the server address, username and password.")
        if (ui.str("auth") != "1") {
            val msg = ui.str("message")?.takeIf { it.isNotBlank() }
            if (msg != null && Regex("connection|limit|max", RegexOption.IGNORE_CASE).containsMatchIn(msg)) {
                throw ConnectionLimitException(ui.str("max_connections")?.toIntOrNull())
            }
            throw IOException((msg ?: "Login failed. Check the username and password.") + " " + OTHER_APP_HINT)
        }
        val status = ui.str("status").orEmpty()
        if (status.isNotEmpty() && !status.equals("Active", ignoreCase = true)) {
            throw IOException("Your subscription status is \"$status\".")
        }
        return AuthInfo(
            status = status,
            expiresAt = (ui.str("exp_date")?.toLongOrNull() ?: 0L) * 1000L,
            maxConnections = ui.str("max_connections")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            activeConnections = ui.str("active_cons")?.toIntOrNull() ?: 0,
            timezone = root.obj("server_info")?.str("timezone"),
        )
    }

    fun categories(action: String): List<Category> {
        val el = tree(apiUrl(action))
        if (!el.isJsonArray) return emptyList()
        return el.asJsonArray.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = o.str("category_id") ?: return@mapNotNull null
            Category(id, o.str("category_name")?.trim().orEmpty().ifEmpty { "Category $id" })
        }
    }

    /** Streams a (possibly huge) JSON array, handing each object to [onItem] as flat string fields. */
    fun streamArray(action: String, onItem: (Map<String, String>) -> Unit) {
        open(apiUrl(action)).use { r ->
            val body = r.body ?: return
            JsonReader(body.charStream()).use { jr ->
                jr.isLenient = true
                readItems(jr, onItem)
            }
        }
    }

    fun seriesInfo(seriesId: String): SeriesInfo {
        val el = tree(apiUrl("get_series_info", "&series_id=${enc(seriesId)}"))
        val root = el.takeIf { it.isJsonObject }?.asJsonObject ?: throw IOException("No details for this series.")
        val info = root.obj("info")
        val episodes = ArrayList<Episode>()
        val epsEl = root.get("episodes")
        fun addEp(e: JsonElement, seasonHint: Int?) {
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return
            val id = o.str("id") ?: return
            val epInfo = o.obj("info")
            val season = o.str("season")?.toIntOrNull() ?: seasonHint ?: 1
            episodes += Episode(
                id = id,
                season = season,
                number = o.str("episode_num")?.toIntOrNull() ?: (episodes.count { it.season == season } + 1),
                title = o.str("title")?.trim().orEmpty(),
                ext = o.str("container_extension")?.takeIf { it.isNotBlank() } ?: "mp4",
                plot = epInfo?.str("plot"),
                image = epInfo?.str("movie_image"),
                durationSecs = epInfo?.str("duration_secs")?.toIntOrNull(),
            )
        }
        when {
            epsEl == null || epsEl.isJsonNull -> Unit
            epsEl.isJsonObject -> for ((k, v) in epsEl.asJsonObject.entrySet()) {
                if (v.isJsonArray) v.asJsonArray.forEach { addEp(it, k.toIntOrNull()) } else addEp(v, k.toIntOrNull())
            }
            epsEl.isJsonArray -> epsEl.asJsonArray.forEach { item ->
                if (item.isJsonArray) item.asJsonArray.forEach { addEp(it, null) } else addEp(item, null)
            }
        }
        episodes.sortWith(compareBy({ it.season }, { it.number }))
        return SeriesInfo(
            name = info?.str("name"),
            plot = info?.str("plot"),
            cast = info?.str("cast"),
            genre = info?.str("genre"),
            releaseDate = info?.str("releaseDate") ?: info?.str("release_date"),
            rating = info?.str("rating"),
            cover = info?.str("cover"),
            backdrop = info?.firstString("backdrop_path"),
            episodes = episodes,
        )
    }

    fun vodInfo(vodId: String): VodInfo {
        val el = tree(apiUrl("get_vod_info", "&vod_id=${enc(vodId)}"))
        val root = el.takeIf { it.isJsonObject }?.asJsonObject ?: throw IOException("No details for this movie.")
        val info = root.obj("info")
        val data = root.obj("movie_data")
        return VodInfo(
            plot = info?.str("plot") ?: info?.str("description"),
            genre = info?.str("genre"),
            cast = info?.str("cast") ?: info?.str("actors"),
            director = info?.str("director"),
            releaseDate = info?.str("releasedate") ?: info?.str("release_date"),
            duration = info?.str("duration"),
            rating = info?.str("rating"),
            image = info?.str("movie_image") ?: info?.str("cover_big"),
            backdrop = info?.firstString("backdrop_path"),
            ext = data?.str("container_extension"),
        )
    }

    companion object {
        const val OTHER_APP_HINT = "If this login works in another app or device (WiseTV on your TV, TiviMate), stop watching there first: your provider may allow only one connection at a time."

        /** What to tell the user when the subscription's connections are all taken. */
        fun inUseMessage(max: Int): String =
            "Your provider's " + (if (max == 1) "1 connection is" else "$max connections are") +
                " in use by another app or device (WiseTV or TiviMate on your TV, say). Stop watching there, then try again."

        fun normalizeServer(input: String): String {
            var s = input.trim()
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            for (marker in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")) {
                val idx = s.indexOf(marker, ignoreCase = true)
                if (idx > 0) s = s.substring(0, idx)
            }
            return s.trimEnd('/')
        }

        data class Creds(val server: String, val username: String, val password: String)

        private val getPhp = Regex("""^(https?://[^/?#]+)(?:/[^?#]*)?/get\.php\?(.+)$""", RegexOption.IGNORE_CASE)

        /** Recognises an Xtream "get.php" M3U link and extracts the login. */
        fun credsFromM3uUrl(url: String): Creds? {
            val m = getPhp.find(url.trim()) ?: return null
            val params = m.groupValues[2].split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i).lowercase() to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val u = params["username"]?.takeIf { it.isNotBlank() } ?: return null
            val p = params["password"]?.takeIf { it.isNotBlank() } ?: return null
            return Creds(m.groupValues[1], u, p)
        }

        internal fun readItems(jr: JsonReader, onItem: (Map<String, String>) -> Unit) {
            when (jr.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    jr.beginArray()
                    while (jr.hasNext()) {
                        if (jr.peek() == JsonToken.BEGIN_OBJECT) onItem(readFlat(jr)) else jr.skipValue()
                    }
                    jr.endArray()
                }
                JsonToken.BEGIN_OBJECT -> {
                    // Some panels return an object keyed by id instead of an array.
                    jr.beginObject()
                    while (jr.hasNext()) {
                        jr.nextName()
                        if (jr.peek() == JsonToken.BEGIN_OBJECT) onItem(readFlat(jr)) else jr.skipValue()
                    }
                    jr.endObject()
                }
                else -> jr.skipValue()
            }
        }

        private fun readFlat(jr: JsonReader): Map<String, String> {
            val m = HashMap<String, String>(24)
            jr.beginObject()
            while (jr.hasNext()) {
                val name = jr.nextName()
                when (jr.peek()) {
                    JsonToken.STRING, JsonToken.NUMBER -> m[name] = jr.nextString()
                    JsonToken.BOOLEAN -> m[name] = jr.nextBoolean().toString()
                    JsonToken.NULL -> jr.nextNull()
                    else -> jr.skipValue()
                }
            }
            jr.endObject()
            return m
        }
    }
}

private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.str(name: String): String? {
    val e = get(name) ?: return null
    if (e.isJsonNull || !e.isJsonPrimitive) return null
    return e.asString
}

private fun JsonObject.firstString(name: String): String? {
    val e = get(name) ?: return null
    return when {
        e.isJsonPrimitive -> e.asString
        e.isJsonArray -> (e as JsonArray).firstOrNull { it.isJsonPrimitive }?.asString
        else -> null
    }?.takeIf { it.isNotBlank() }
}
