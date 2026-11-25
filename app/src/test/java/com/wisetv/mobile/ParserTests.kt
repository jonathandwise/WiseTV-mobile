package com.wisetv.mobile

import com.google.gson.stream.JsonReader
import com.wisetv.mobile.data.epg.EpgImporter
import com.wisetv.mobile.data.epg.XmltvParser
import com.wisetv.mobile.data.m3u.M3uParser
import com.wisetv.mobile.data.xtream.XtreamApi
import com.wisetv.mobile.data.plex.Codecs
import com.wisetv.mobile.data.plex.Plex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.BufferedReader
import java.io.StringReader
import java.time.Instant

class M3uParserTest {
    private val sample = """
        #EXTM3U url-tvg="http://epg.example/guide.xml.gz,http://epg2.example/x.xml"
        #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://logo/bbc1.png" group-title="UK, General" tvg-chno="101",BBC One HD
        #EXTVLCOPT:http-user-agent=MyAgent/1.0
        http://server/live/u/p/1001.ts
        #EXTINF:-1 tvg-id="" group-title="Movies",Some Movie (2020)
        http://server/movie/u/p/2002.mkv
        #EXTINF:-1,No Attributes
        #EXTGRP:Misc
        http://server/live/u/p/1003.m3u8

        http://bare.example/stream
    """.trimIndent()

    private fun parse(): Pair<List<String>, List<M3uParser.Entry>> {
        var header = emptyList<String>()
        val entries = ArrayList<M3uParser.Entry>()
        M3uParser().parse(BufferedReader(StringReader(sample)), { header = it }) { entries += it }
        return header to entries
    }

    @Test fun headerEpgUrls() {
        val (header, _) = parse()
        assertEquals(listOf("http://epg.example/guide.xml.gz", "http://epg2.example/x.xml"), header)
    }

    @Test fun attributesAndNames() {
        val (_, e) = parse()
        assertEquals(4, e.size)
        assertEquals("BBC One HD", e[0].name)
        assertEquals("bbc1.uk", e[0].tvgId)
        assertEquals("UK, General", e[0].group)
        assertEquals(101, e[0].number)
        assertEquals("http://logo/bbc1.png", e[0].logo)
        assertEquals("MyAgent/1.0", e[0].userAgent)
        assertEquals("Movies", e[1].group)
        assertEquals("Misc", e[2].group)
        assertNull(e[2].userAgent)
        assertEquals("No Attributes", e[2].name)
        assertEquals("Uncategorized", e[3].group)
    }

    @Test fun classification() {
        val (_, e) = parse()
        assertEquals(M3uParser.Kind.LIVE, M3uParser.classify(e[0].url))
        assertEquals(M3uParser.Kind.MOVIE, M3uParser.classify(e[1].url))
        assertEquals(M3uParser.Kind.SERIES, M3uParser.classify("http://s/series/u/p/55.mp4"))
        assertEquals("mkv", M3uParser.extensionOf(e[1].url))
    }

    @Test fun commaInsideQuotes() {
        assertEquals(27, M3uParser.findNameComma("-1 group-title=\"A, B\" x=\"y\",Name"))
    }
}

class XtreamApiTest {
    @Test fun normalizeServer() {
        assertEquals("http://host:8080", XtreamApi.normalizeServer("host:8080/"))
        assertEquals("https://host", XtreamApi.normalizeServer(" https://host/player_api.php?username=a "))
        assertEquals("http://h.com:80", XtreamApi.normalizeServer("http://h.com:80/get.php?username=u&password=p&type=m3u_plus"))
    }

    @Test fun credsFromGetPhp() {
        val c = XtreamApi.credsFromM3uUrl("http://h.com:8080/get.php?username=joe&password=s3cr%21t&type=m3u_plus&output=ts")
        assertEquals("http://h.com:8080", c?.server)
        assertEquals("joe", c?.username)
        assertEquals("s3cr!t", c?.password)
        assertNull(XtreamApi.credsFromM3uUrl("http://h.com/playlist.m3u"))
    }

    @Test fun streamsArrayWithMixedTypes() {
        val json = """[{"num":1,"name":"A","stream_id":"10","epg_channel_id":null,"tv_archive":0,"x":{"y":1}},
                      {"num":"2","name":"B","stream_id":11,"added":"1700000000","flag":true,"arr":[1,2]}]"""
        val items = ArrayList<Map<String, String>>()
        XtreamApi.readItems(JsonReader(StringReader(json)).apply { isLenient = true }) { items += it }
        assertEquals(2, items.size)
        assertEquals("10", items[0]["stream_id"])
        assertNull(items[0]["epg_channel_id"])
        assertEquals("11", items[1]["stream_id"])
        assertEquals("2", items[1]["num"])
        assertEquals("true", items[1]["flag"])
        assertNull(items[1]["arr"])
    }

    @Test fun streamsObjectKeyedById() {
        val json = """{"1":{"stream_id":"1","name":"A"},"2":{"stream_id":"2","name":"B"}}"""
        val items = ArrayList<Map<String, String>>()
        XtreamApi.readItems(JsonReader(StringReader(json))) { items += it }
        assertEquals(listOf("A", "B"), items.map { it["name"] })
    }
}

class XmltvParserTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="BBC1.uk"><display-name>BBC One</display-name><display-name>BBC 1 HD</display-name><icon src="x"/></channel>
          <programme start="20261025140000 +0000" stop="20261025150000 +0000" channel="BBC1.uk">
            <title lang="en">News &amp; Weather</title>
            <sub-title>Evening edition</sub-title>
            <desc>The latest news.</desc>
            <category>News</category>
            <episode-num system="xmltv_ns">1.4.0/1</episode-num>
            <icon src="http://img/p.jpg" />
            <new />
          </programme>
          <programme start="20261025150000 -0500" channel="BBC1.uk"><title>Late</title></programme>
        </tv>"""

    @Test fun parsesChannelsAndProgrammes() {
        val channels = ArrayList<Pair<String, List<String>>>()
        val progs = ArrayList<XmltvParser.Programme>()
        XmltvParser(KXmlParser()).parse(
            xml.byteInputStream(),
            object : XmltvParser.Sink {
                override fun onChannel(id: String, names: List<String>) { channels += id to names }
                override fun onProgramme(p: XmltvParser.Programme) { progs += p }
            },
        )
        assertEquals(listOf("BBC1.uk" to listOf("BBC One", "BBC 1 HD")), channels)
        assertEquals(2, progs.size)
        val p = progs[0]
        assertEquals("News & Weather", p.title)
        assertEquals("Evening edition", p.subTitle)
        assertEquals("The latest news.", p.desc)
        assertEquals("News", p.category)
        assertEquals("S2 E5", p.episode)
        assertEquals("http://img/p.jpg", p.icon)
        assertTrue(p.isNew)
        assertEquals(Instant.parse("2026-10-25T14:00:00Z").toEpochMilli(), p.start)
        assertEquals(Instant.parse("2026-10-25T15:00:00Z").toEpochMilli(), p.stop)
        // Missing stop defaults to 30 minutes; offset applied.
        assertEquals(Instant.parse("2026-10-25T20:00:00Z").toEpochMilli(), progs[1].start)
        assertEquals(progs[1].start + 30 * 60_000L, progs[1].stop)
    }

    @Test fun timeFormats() {
        assertEquals(Instant.parse("2026-01-02T03:04:05Z").toEpochMilli(), XmltvParser.parseTime("20260102030405"))
        assertEquals(Instant.parse("2026-01-02T01:34:05Z").toEpochMilli(), XmltvParser.parseTime("20260102030405 +0130"))
        assertEquals(Instant.parse("2026-01-02T03:04:00Z").toEpochMilli(), XmltvParser.parseTime("202601020304"))
        assertNull(XmltvParser.parseTime("garbage"))
    }

    @Test fun nameNormalisation() {
        assertEquals(EpgImporter.normalizeName("BBC One HD"), EpgImporter.normalizeName("bbc one"))
    }
}


class CodecsTest {
    private fun media(container: String, v: String, a: String) = Plex.Media(container, v, a, 3000, 720, "/library/parts/1/f")

    @Test fun commonFilesPlayDirectly() {
        assertTrue(Codecs.canDirectPlay(media("mp4", "h264", "aac")))
        assertTrue(Codecs.canDirectPlay(media("mkv", "h264", "mp3")))
    }

    @Test fun unsupportedPartsNeedTheServer() {
        // No device decoders exist in unit tests, so DTS / HEVC count as unsupported here.
        assertTrue(!Codecs.canDirectPlay(media("mkv", "h264", "dca")))
        assertTrue(!Codecs.canDirectPlay(media("avi", "h264", "aac")))
        assertTrue(!Codecs.canDirectPlay(media("mp4", "mpeg4", "aac")))
    }
}


class CastTest {
    @Test fun xtreamLiveBecomesHls() {
        val (url, mime) = com.wisetv.mobile.cast.CastManager.liveStream("http://h:8080/live/u/p/123.ts")
        assertEquals("http://h:8080/live/u/p/123.m3u8", url)
        assertEquals("application/x-mpegURL", mime)
    }

    @Test fun otherLinksUnchanged() {
        assertEquals("http://x/a.m3u8", com.wisetv.mobile.cast.CastManager.liveStream("http://x/a.m3u8").first)
        assertEquals("video/mp4", com.wisetv.mobile.cast.CastManager.liveStream("http://x/a.mp4").second)
    }

    @Test fun castPlayableFiles() {
        assertTrue(Codecs.castCanPlay(Plex.Media("mp4", "h264", "aac", 3000, 720, "/p")))
        assertTrue(!Codecs.castCanPlay(Plex.Media("mkv", "h264", "aac", 3000, 720, "/p")))
        assertTrue(!Codecs.castCanPlay(Plex.Media("mp4", "hevc", "aac", 3000, 720, "/p")))
    }
}

class PresetsTest {
    @Test fun sameListAsWindows() {
        val p = com.wisetv.mobile.data.Presets.all
        assertEquals(13, p.size)
        assertEquals(p.size, p.map { it.id }.toSet().size)
        assertTrue(p.all { it.url.startsWith("https://") && it.url.endsWith(".m3u") })
    }
}

class AddressVariantsTest {
    @Test fun triesOtherSchemeAndBareHost() {
        assertEquals(
            listOf("https://line.example.ru/c", "http://line.example.ru/c", "https://line.example.ru", "http://line.example.ru"),
            com.wisetv.mobile.data.xtream.XtreamApi.addressVariants("https://line.example.ru/c/"),
        )
    }

    @Test fun keepsPortAndDropsDuplicates() {
        assertEquals(
            listOf("http://h.tv:8080", "https://h.tv:8080"),
            com.wisetv.mobile.data.xtream.XtreamApi.addressVariants("h.tv:8080/player_api.php?username=a&password=b"),
        )
    }
}
