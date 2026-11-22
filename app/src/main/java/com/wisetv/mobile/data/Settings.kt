package com.wisetv.mobile.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * App preferences. Every value is backed by Compose state, so screens that read a setting
 * update by themselves when it changes.
 */
class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("wisetv", Context.MODE_PRIVATE)

    private class Pref<T>(initial: T, private val save: (T) -> Unit) : ReadWriteProperty<Any?, T> {
        private val state = mutableStateOf(initial)
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            save(value)
        }
    }

    private fun int(key: String, def: Int): ReadWriteProperty<Any?, Int> =
        Pref(p.getInt(key, def)) { p.edit().putInt(key, it).apply() }

    private fun long(key: String, def: Long): ReadWriteProperty<Any?, Long> =
        Pref(p.getLong(key, def)) { p.edit().putLong(key, it).apply() }

    private fun bool(key: String, def: Boolean): ReadWriteProperty<Any?, Boolean> =
        Pref(p.getBoolean(key, def)) { p.edit().putBoolean(key, it).apply() }

    private fun str(key: String, def: String): ReadWriteProperty<Any?, String> =
        Pref(p.getString(key, def) ?: def) { p.edit().putString(key, it).apply() }

    var activePlaylistId by long("active_playlist", 0)
    var lastChannelId by long("last_channel", 0)
    var lastGroup by str("last_group", ALL_GROUP)

    /** "ts" or "m3u8" for Xtream live streams. */
    var liveFormat by str("live_format", "ts")
    var epgDays by int("epg_days", 4)
    var refreshHours by int("refresh_hours", 12)
    var autoplayLast by bool("autoplay_last", false)

    /** PlayerView resize mode: 0 fit, 3 fill (stretch), 4 zoom. */
    var resizeMode by int("resize_mode", 0)

    /** Keep the sound playing when the app is in the background or the screen is off. */
    var backgroundAudio by bool("background_audio", true)

    /** Live TV tab layout: "guide" (programme grid) or "list" (channels with what's on now). */
    var liveView by str("live_view", "guide")

    /** Swipe up / down on the full-screen player changes channel. */
    var swipeChannels by bool("swipe_channels", true)

    var autoUpdate by bool("auto_update", true)

    /** Send Dolby (AC3/E-AC3) audio as-is to a connected receiver (HDMI/USB-C adapters). Read when the app starts. */
    var audioPassthrough by bool("audio_passthrough", false)

    /** Picture fixes per channel or title: "key=mask;key=mask" (1 = alternative stream reading, 2 = software decoder). */
    var videoFixes by str("video_fixes", "")

    /** The last searches, newest first, one per line. */
    var searchHistory by str("search_history", "")

    // Plex. The account token comes from plex.tv; each server has its own access token.
    var plexToken by str("plex_token", "")
    var plexUser by str("plex_user", "")
    var plexClientId by str("plex_client_id", "")
    var plexServerId by str("plex_server_id", "")
    var plexServerName by str("plex_server_name", "")
    var plexServerToken by str("plex_server_token", "")
    var plexServerUri by str("plex_server_uri", "")
    var plexServerLocal by bool("plex_server_local", true)

    /** The Plex servers in use (own and shared), as JSON. Replaces the single-server fields above. */
    var plexServersJson by str("plex_servers", "")

    /** Video bitrate limit in kbps for Plex away from home (0 = original quality). */
    var plexRemoteKbps by int("plex_remote_kbps", 8000)

    companion object {
        const val ALL_GROUP = "__all__"
        const val FAV_GROUP = "__fav__"
    }
}
