package com.wisetv.mobile.data.epg

import android.util.Xml
import com.wisetv.mobile.L
import com.wisetv.mobile.data.Http
import com.wisetv.mobile.data.Settings
import com.wisetv.mobile.data.db.AppDatabase
import com.wisetv.mobile.data.db.PlaylistEntity
import com.wisetv.mobile.data.db.PlaylistType
import com.wisetv.mobile.data.db.ProgramEntity
import com.wisetv.mobile.data.xtream.XtreamApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException

class EpgImporter(
    private val db: AppDatabase,
    private val http: Http,
    private val settings: Settings,
) {
    fun urlsFor(pl: PlaylistEntity): List<String> {
        val urls = ArrayList<String>()
        fun addAll(text: String) = text.split('\n', ',', ' ', '\t')
            .map { it.trim() }
            .filter { it.startsWith("http", ignoreCase = true) }
            .forEach { urls += it }
        addAll(pl.epgUrl)
        if (pl.type == PlaylistType.XTREAM) {
            urls += XtreamApi(http, pl.url, pl.username, pl.password, pl.userAgent).epgUrl()
        } else {
            addAll(pl.m3uEpgUrl)
        }
        return urls.distinct()
    }

    /**
     * Downloads every guide source for the playlist and swaps in the new programmes atomically
     * (by generation number) so the guide never shows up empty while updating.
     */
    suspend fun import(pl: PlaylistEntity, progress: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val urls = urlsFor(pl)
        if (urls.isEmpty()) return@withContext 0

        val channels = db.channels().visibleOnce(pl.id)
        val wanted = HashSet<String>()
        val byName = HashMap<String, MutableList<Long>>()
        for (c in channels) {
            if (c.epgId.isNotEmpty()) wanted += c.epgId
            else byName.getOrPut(normalizeName(c.name)) { ArrayList() } += c.id
        }
        if (wanted.isEmpty() && byName.isEmpty()) return@withContext 0

        val gen = System.currentTimeMillis()
        val now = gen
        val from = now - 2 * HOUR
        val to = now + settings.epgDays.coerceIn(1, 14) * DAY
        val filledBefore = HashSet<String>()
        var count = 0
        var anySuccess = false
        var lastError: Exception? = null

        for ((i, url) in urls.withIndex()) {
            progress(if (urls.size > 1) "Downloading TV guide ${i + 1} of ${urls.size}…" else "Downloading TV guide…")
            val filledNow = HashSet<String>()
            try {
                http.api.newCall(http.request(url, pl.userAgent)).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Guide download failed (HTTP ${resp.code})")
                    val input = Http.maybeGunzip(resp.body!!.byteStream())
                    val batch = ArrayList<ProgramEntity>(1000)
                    val xpp = Xml.newPullParser().apply {
                        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                        // Provider guides often contain sloppy XML (stray "&", unknown entities).
                        runCatching { setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
                    }
                    var fromThisSource = 0
                    try {
                    XmltvParser(xpp).parse(
                        input,
                        object : XmltvParser.Sink {
                            override fun onChannel(id: String, names: List<String>) {
                                val key = id.lowercase()
                                if (key in wanted || byName.isEmpty()) return
                                val ids = ArrayList<Long>()
                                for (n in names) byName.remove(normalizeName(n))?.let { ids += it }
                                if (ids.isNotEmpty()) {
                                    db.channels().setEpgIdBlocking(ids, key)
                                    wanted += key
                                }
                            }

                            override fun onProgramme(p: XmltvParser.Programme) {
                                val key = p.channel.lowercase()
                                if (key !in wanted || key in filledBefore) return
                                if (p.stop <= from || p.start >= to) return
                                filledNow += key
                                batch += ProgramEntity(
                                    playlistId = pl.id,
                                    gen = gen,
                                    epgKey = key,
                                    start = p.start,
                                    stop = p.stop,
                                    title = p.title.take(200),
                                    subtitle = p.subTitle?.take(200),
                                    description = p.desc?.take(600),
                                    category = p.category?.take(60),
                                    episode = p.episode?.take(30),
                                    icon = p.icon,
                                    isNew = p.isNew,
                                )
                                fromThisSource++
                                if (batch.size >= 1000) {
                                    db.programs().insertAllBlocking(batch)
                                    count += batch.size
                                    batch.clear()
                                    if (count % 25_000 == 0) progress("Loading TV guide… ${count / 1000}k programmes")
                                }
                            }
                        },
                        shiftMs = pl.epgShiftMinutes * 60_000L,
                    )
                    } catch (e: Exception) {
                        // A guide that breaks part-way through is still useful: keep what was read.
                        if (e is CancellationException || fromThisSource == 0) throw e
                        L.i("epg partial source=${i + 1} programmes=$fromThisSource error=${e.message}")
                    }
                    if (batch.isNotEmpty()) {
                        db.programs().insertAllBlocking(batch)
                        count += batch.size
                    }
                    anySuccess = true
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
            }
            filledBefore += filledNow
        }

        if (!anySuccess) {
            db.programs().deleteGen(pl.id, gen)
            throw lastError ?: IOException("Guide download failed")
        }
        L.i("epg done playlist=${pl.id} programmes=$count")
        db.playlists().setEpgGen(pl.id, gen, now)
        db.programs().deleteOtherGens(pl.id, gen)
        count
    }

    companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR

        fun normalizeName(s: String): String =
            s.lowercase()
                .replace(Regex("""\b(hd|fhd|uhd|4k|sd|hevc|h265)\b"""), "")
                .replace(Regex("""[^a-z0-9]"""), "")
    }
}
