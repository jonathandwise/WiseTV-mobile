package com.wisetv.mobile.data.epg

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Streaming XMLTV parser. Works on files of any size with constant memory. */
class XmltvParser(private val xpp: XmlPullParser) {

    data class Programme(
        val channel: String,
        val start: Long,
        val stop: Long,
        val title: String,
        val subTitle: String?,
        val desc: String?,
        val category: String?,
        val episode: String?,
        val icon: String?,
        val isNew: Boolean,
    )

    interface Sink {
        fun onChannel(id: String, names: List<String>)
        fun onProgramme(p: Programme)
    }

    fun parse(input: InputStream, sink: Sink, shiftMs: Long = 0) {
        xpp.setInput(input, null)
        var ev = xpp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (xpp.name) {
                    "channel" -> readChannel(sink)
                    "programme" -> readProgramme(sink, shiftMs)
                }
            }
            ev = xpp.next()
        }
    }

    private fun readChannel(sink: Sink) {
        val id = xpp.getAttributeValue(null, "id")?.trim().orEmpty()
        val names = ArrayList<String>(2)
        val depth = xpp.depth
        var ev = xpp.next()
        while (ev != XmlPullParser.END_DOCUMENT && !(ev == XmlPullParser.END_TAG && xpp.depth == depth)) {
            if (ev == XmlPullParser.START_TAG && xpp.name == "display-name") {
                val n = readText()
                if (n.isNotEmpty()) names += n
            }
            ev = xpp.next()
        }
        if (id.isNotEmpty()) sink.onChannel(id, names)
    }

    private fun readProgramme(sink: Sink, shiftMs: Long) {
        val channel = xpp.getAttributeValue(null, "channel")?.trim().orEmpty()
        val start = parseTime(xpp.getAttributeValue(null, "start"))
        val stopAttr = parseTime(xpp.getAttributeValue(null, "stop"))
        var title: String? = null
        var sub: String? = null
        var desc: String? = null
        var category: String? = null
        var episodeOnScreen: String? = null
        var episodeNs: String? = null
        var icon: String? = null
        var isNew = false
        val depth = xpp.depth
        var ev = xpp.next()
        while (ev != XmlPullParser.END_DOCUMENT && !(ev == XmlPullParser.END_TAG && xpp.depth == depth)) {
            if (ev == XmlPullParser.START_TAG) {
                when (xpp.name) {
                    "title" -> { val t = readText(); if (title == null && t.isNotEmpty()) title = t }
                    "sub-title" -> { val t = readText(); if (sub == null && t.isNotEmpty()) sub = t }
                    "desc" -> { val t = readText(); if (desc == null && t.isNotEmpty()) desc = t }
                    "category" -> { val t = readText(); if (category == null && t.isNotEmpty()) category = t }
                    "episode-num" -> {
                        val system = xpp.getAttributeValue(null, "system")
                        val t = readText()
                        if (t.isNotEmpty()) {
                            if (system == "xmltv_ns") episodeNs = t
                            else if (system == "onscreen" || episodeOnScreen == null) episodeOnScreen = t
                        }
                    }
                    "icon" -> if (icon == null) icon = xpp.getAttributeValue(null, "src")
                    "new" -> isNew = true
                }
            }
            ev = xpp.next()
        }
        if (channel.isEmpty() || start == null) return
        val stop = stopAttr?.takeIf { it > start } ?: (start + 30 * 60_000L)
        sink.onProgramme(
            Programme(
                channel = channel,
                start = start + shiftMs,
                stop = stop + shiftMs,
                title = title ?: "No title",
                subTitle = sub,
                desc = desc,
                category = category,
                episode = episodeOnScreen ?: episodeNs?.let { formatXmltvNs(it) },
                icon = icon,
                isNew = isNew,
            ),
        )
    }

    /** Reads the text content of the current element and leaves the parser on its end tag. */
    private fun readText(): String {
        val sb = StringBuilder()
        val depth = xpp.depth
        var ev = xpp.next()
        while (ev != XmlPullParser.END_DOCUMENT && !(ev == XmlPullParser.END_TAG && xpp.depth == depth)) {
            if (ev == XmlPullParser.TEXT) sb.append(xpp.text)
            ev = xpp.next()
        }
        return sb.toString().trim()
    }

    companion object {
        /** Parses XMLTV times such as "20261025140000 +0000" or "20261025140000". */
        fun parseTime(s: String?): Long? {
            if (s == null) return null
            val t = s.trim()
            if (t.length < 12) return null
            return try {
                val year = t.substring(0, 4).toInt()
                val month = t.substring(4, 6).toInt()
                val day = t.substring(6, 8).toInt()
                val hour = t.substring(8, 10).toInt()
                val minute = t.substring(10, 12).toInt()
                val second = if (t.length >= 14 && t[12].isDigit()) t.substring(12, 14).toInt() else 0
                val rest = t.substring(if (t.length >= 14 && t[12].isDigit()) 14 else 12).trim()
                val offset = parseOffset(rest)
                LocalDateTime.of(year, month, day, hour, minute, second).toEpochSecond(offset) * 1000L
            } catch (e: Exception) {
                null
            }
        }

        private fun parseOffset(s: String): ZoneOffset {
            if (s.length < 5) return ZoneOffset.UTC
            val sign = when (s[0]) {
                '+' -> 1
                '-' -> -1
                else -> return ZoneOffset.UTC
            }
            val digits = s.substring(1).replace(":", "")
            if (digits.length < 4) return ZoneOffset.UTC
            val h = digits.substring(0, 2).toIntOrNull() ?: return ZoneOffset.UTC
            val m = digits.substring(2, 4).toIntOrNull() ?: return ZoneOffset.UTC
            return ZoneOffset.ofHoursMinutes(sign * h, sign * m)
        }

        /** "0.4.0/1" -> "S1 E5" (xmltv_ns numbers are zero-based). */
        fun formatXmltvNs(s: String): String? {
            val parts = s.split('.')
            if (parts.size < 2) return null
            val season = parts[0].substringBefore('/').trim().toIntOrNull()?.plus(1)
            val ep = parts[1].substringBefore('/').trim().toIntOrNull()?.plus(1)
            return when {
                season != null && ep != null -> "S$season E$ep"
                ep != null -> "E$ep"
                season != null -> "S$season"
                else -> null
            }
        }
    }
}
