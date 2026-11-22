package com.wisetv.mobile

import android.util.Log

/** One log tag for the whole app, so `adb logcat -s WiseTV` tells the story of a session. */
object L {
    fun i(msg: String) {
        runCatching { Log.i("WiseTV", msg) }
    }
}
