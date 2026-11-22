package com.wisetv.mobile.data.m3u

import java.io.BufferedReader

/** Streaming M3U / M3U8 (extended) playlist parser. */
class M3uParser {

    data class Entry(
        val name: String,
        val url: String,
        val attrs: Map<String, String>,
        val group: String,
        val userAgent: String?,
    ) {
        val tvgId: String get() = attrs["tvg-id"].orEmpty()
        val logo: String? get() = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
        val number: Int? get() = attrs["tvg-chno"]?.trim()?.toIntOrNull() ?: attrs["channel-number"]?.trim()?.toIntOrNull()
    }

    enum class Kind { LIVE, MOVIE, SERIES }

    fun parse(reader: BufferedReader, onHeader: (epgUrls: List<String>) -> Unit = {}, onEntry: (Entry) -> Unit) {
        var name: String? = null
        var attrs: Map<String, String> = emptyMap()
        var group: String? = null
        var ua: String? = null
        var first = true
        while (true) {
            val raw = reader.readLine() ?: break
            var line = raw.trim()
            if (first) {
                line = line.removePrefix("﻿")
                first = false
            }
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val a = parseAttrs(line.substring(7))
                    val urls = listOfNotNull(a["url-tvg"], a["x-tvg-url"], a["tvg-url"])
                        .flatMap { it.split(',') }
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                    if (urls.isNotEmpty()) onHeader(urls)
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val body = line.substringAfter(':', "")
                    val comma = findNameComma(body)
                    val attrPart = if (comma >= 0) body.substring(0, comma) else body
                    name = if (comma >= 0) body.substring(comma + 1).trim() else ""
                    attrs = parseAttrs(attrPart)
                    group = attrs["group-title"]
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    if (group.isNullOrBlank()) group = line.substring(8).trim()
                }
                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                    val opt = line.substringAfter(':', "")
                    if (opt.startsWith("http-user-agent=", ignoreCase = true)) ua = opt.substringAfter('=').trim()
                }
                line.startsWith("#") -> Unit
                else -> {
                    val finalName = name?.takeIf { it.isNotBlank() }
                        ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                        ?: line.substringAfterLast('/').substringBefore('?')
                    onEntry(
                        Entry(
                            name = finalName,
                            url = line,
                            attrs = attrs,
                            group = group?.trim()?.takeIf { it.isNotBlank() } ?: "Uncategorized",
                            userAgent = ua?.takeIf { it.isNotBlank() },
                        ),
                    )
                    name = null
                    attrs = emptyMap()
                    group = null
                    ua = null
                }
            }
        }
    }

    companion object {
        private val quotedAttr = Regex("""([A-Za-z0-9_\-]+)\s*=\s*"([^"]*)"""")
        private val bareAttr = Regex("""([A-Za-z0-9_\-]+)=([^\s",]+)""")
        private val videoExt = Regex("""\.(mp4|mkv|avi|mov|m4v|wmv|flv)(\?.*)?$""", RegexOption.IGNORE_CASE)

        fun parseAttrs(s: String): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            quotedAttr.findAll(s).forEach { m[it.groupValues[1].lowercase()] = it.groupValues[2].trim() }
            val rest = quotedAttr.replace(s, " ")
            bareAttr.findAll(rest).forEach { m.putIfAbsent(it.groupValues[1].lowercase(), it.groupValues[2].trim()) }
            return m
        }

        /** Index of the comma separating attributes from the display name, ignoring commas inside quotes. */
        fun findNameComma(s: String): Int {
            var inQuotes = false
            for (i in s.indices) {
                val c = s[i]
                if (c == '"') inQuotes = !inQuotes
                else if (c == ',' && !inQuotes) return i
            }
            return -1
        }

        fun classify(url: String): Kind {
            val u = url.lowercase()
            return when {
                "/series/" in u -> Kind.SERIES
                "/movie/" in u -> Kind.MOVIE
                videoExt.containsMatchIn(u) -> Kind.MOVIE
                else -> Kind.LIVE
            }
        }

        fun extensionOf(url: String): String =
            url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase().ifBlank { "mp4" }
    }
}
