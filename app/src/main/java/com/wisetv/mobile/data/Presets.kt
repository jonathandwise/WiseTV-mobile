package com.wisetv.mobile.data

/** Free channel playlists offered on the Add playlist screen (the same list as WiseTV for Windows). */
object Presets {
    data class Preset(val id: String, val name: String, val url: String, val note: String)

    val all = listOf(
        Preset("iptv-org", "IPTV-org (worldwide)", "https://iptv-org.github.io/iptv/index.m3u", "Thousands of free public channels from around the world. Large: takes a while to load."),
        Preset("samsung", "Samsung TV Plus", "https://apsattv.com/ssungusa.m3u", "Free channels from Samsung TV Plus (US)"),
        Preset("xumo", "XUMO", "https://www.apsattv.com/xumo.m3u", "Free channels from XUMO Play"),
        Preset("localnow", "Local Now", "https://www.apsattv.com/localnow.m3u", "Local news, weather and free channels"),
        Preset("lg", "LG Channels", "https://www.apsattv.com/lg.m3u", "May no longer be updated"),
        Preset("uslg", "LG US Channels", "https://www.apsattv.com/uslg.m3u", "Free channels from LG Channels (US)"),
        Preset("roku", "Roku Channel", "https://www.apsattv.com/rok.m3u", "Free live channels from The Roku Channel"),
        Preset("redbox", "Redbox TV", "https://www.apsattv.com/redbox.m3u", "Free channels from Redbox"),
        Preset("distro", "DistroTV", "https://www.apsattv.com/distro.m3u", "Free international and niche channels"),
        Preset("xiaomi", "Xiaomi", "https://www.apsattv.com/xiaomi.m3u", "Free channels from Xiaomi TV+"),
        Preset("tablo", "Tablo", "https://www.apsattv.com/tablo.m3u", "Free channels from Tablo"),
        Preset("vizio", "Vizio TV", "https://www.apsattv.com/vizio.m3u", "Free channels from Vizio WatchFree+"),
        Preset("sports", "Sports", "https://www.apsattv.com/sportstv.m3u", "Free sports channels"),
    )
}
