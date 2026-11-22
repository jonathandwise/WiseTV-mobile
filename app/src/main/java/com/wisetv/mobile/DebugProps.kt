package com.wisetv.mobile

/**
 * Test hooks. Values are read from Android "debug.*" system properties, which only adb can set,
 * so no other app on the device can redirect WiseTV. The emulator test uses them to point the
 * app at a pretend Plex and a pretend update server.
 */
object DebugProps {
    fun get(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    val plexTv: String get() = get("debug.wisetv.plex_tv") ?: "https://plex.tv"

    /** Test hook: pretend the picture has frozen, to exercise the automatic fixes. */
    val fakeVideoStall: Boolean get() = get("debug.wisetv.fake_stall") == "1"
}
