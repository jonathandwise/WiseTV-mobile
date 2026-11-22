package com.wisetv.mobile.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.wisetv.mobile.AppGraph
import com.wisetv.mobile.L
import com.wisetv.mobile.data.db.ChannelEntity
import com.wisetv.mobile.player.PlaybackManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Tells the Cast framework to use Google's standard receiver (no custom receiver app needed). */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notification = NotificationOptions.Builder()
            .setTargetActivityClassName("com.wisetv.mobile.ui.MainActivity")
            .build()
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(notification)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(media)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Chromecast: while a cast session is open, [PlaybackManager] sends channels and titles here
 * instead of playing them on the phone. The Chromecast fetches the stream itself, so it uses the
 * provider's connection and the phone's player stays stopped.
 */
class CastManager(private val ctx: Context, private val g: AppGraph) {
    private val main = Handler(Looper.getMainLooper())

    /** Null when this phone has no Google Play services (casting is then simply not offered). */
    var context: CastContext? = null
        private set

    val active = MutableStateFlow(false)
    val deviceName = MutableStateFlow<String?>(null)

    /** Bumped whenever the Chromecast reports a change, so screens showing its state refresh. */
    val tick = MutableStateFlow(0)

    private var session: CastSession? = null
    private var lastPosition = 0L
    private var loadSerial = 0
    private val client: RemoteMediaClient? get() = session?.remoteMediaClient

    fun init() {
        context = try {
            CastContext.getSharedInstance(ctx)
        } catch (e: Exception) {
            L.i("cast unavailable: ${e.message}")
            null
        }
        val c = context ?: return
        c.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        c.sessionManager.currentCastSession?.let { attach(it, handOver = false) }
    }

    private val callback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val c = client ?: return
            if (c.approximateStreamPosition > 0) lastPosition = c.approximateStreamPosition
            val st = c.mediaStatus
            if (st != null && st.playerState == MediaStatus.PLAYER_STATE_IDLE) {
                when (st.idleReason) {
                    MediaStatus.IDLE_REASON_FINISHED -> g.playback.onCastTitleFinished()
                    MediaStatus.IDLE_REASON_ERROR -> {
                        L.i("cast error")
                        g.playback.events.tryEmit(
                            PlaybackManager.Event.Error(
                                "The Chromecast couldn't play this. Some providers' streams and some movie formats (MKV, AVI) can't be cast; it still plays on the phone.",
                                everywhere = true,
                            ),
                        )
                    }
                }
            }
            tick.value++
        }
    }

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, sessionId: String) = attach(s, handOver = true)
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = attach(s, handOver = !active.value)
        override fun onSessionEnding(s: CastSession) {
            client?.approximateStreamPosition?.takeIf { it > 0 }?.let { lastPosition = it }
        }
        override fun onSessionEnded(s: CastSession, error: Int) = detach()
        override fun onSessionStartFailed(s: CastSession, error: Int) {
            L.i("cast start failed $error")
        }
        override fun onSessionStarting(s: CastSession) = Unit
        override fun onSessionResuming(s: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(s: CastSession, error: Int) = Unit
        override fun onSessionSuspended(s: CastSession, reason: Int) = Unit
    }

    private fun attach(s: CastSession, handOver: Boolean) {
        session?.remoteMediaClient?.unregisterCallback(callback)
        session = s
        s.remoteMediaClient?.registerCallback(callback)
        deviceName.value = s.castDevice?.friendlyName ?: "Chromecast"
        L.i("cast session ${deviceName.value}")
        if (!active.value) {
            active.value = true
            if (handOver) g.playback.onCastStarted()
        }
    }

    private fun detach() {
        session?.remoteMediaClient?.unregisterCallback(callback)
        session = null
        deviceName.value = null
        if (active.value) {
            active.value = false
            L.i("cast session ended at ${lastPosition / 1000}s")
            g.playback.onCastSessionEnded(lastPosition)
        }
        lastPosition = 0
    }

    /** Stops casting (the Chromecast goes back to its home screen) and carries on on the phone. */
    fun disconnect() {
        context?.sessionManager?.endCurrentSession(true)
    }

    // ------------------------------------------------------------------ loading

    fun loadLive(ch: ChannelEntity) {
        val (url, mime) = liveStream(ch.url)
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
            putString(MediaMetadata.KEY_TITLE, ch.name)
            putString(MediaMetadata.KEY_SUBTITLE, "Live TV · WiseTV")
            ch.logo?.let { addImage(WebImage(Uri.parse(it))) }
        }
        load(MediaInfo.Builder(url).setStreamType(MediaInfo.STREAM_TYPE_LIVE).setContentType(mime).setMetadata(meta).build(), 0, ++loadSerial)
    }

    fun loadVod(item: PlaybackManager.VodPlayable, startMs: Long) {
        val serial = ++loadSerial
        val meta = MediaMetadata(if (item.kind.endsWith("episode")) MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, item.title)
            item.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            item.poster?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val plexKey = item.plexKey
        val plexServer = item.plexServer
        if (plexKey != null && plexServer != null) {
            // Ask Plex for a version the Chromecast can play (converted by the server if needed).
            g.appScope.launch {
                try {
                    val full = g.plex.metadata(plexServer, plexKey)
                    val pb = g.plex.playback(full, forCast = true)
                    val mime = if (".m3u8" in pb.url) "application/x-mpegURL" else mimeFor(full.media?.container ?: "mp4")
                    main.post {
                        load(MediaInfo.Builder(pb.url).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setContentType(mime).setMetadata(meta).build(), startMs, serial)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    g.playback.events.tryEmit(PlaybackManager.Event.Error("Plex: ${e.message}", everywhere = true))
                }
            }
            return
        }
        val ext = item.url.substringBefore('?').substringAfterLast('.', "mp4")
        load(MediaInfo.Builder(item.url).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setContentType(mimeFor(ext)).setMetadata(meta).build(), startMs, serial)
    }

    private fun load(info: MediaInfo, startMs: Long, serial: Int) {
        if (serial != loadSerial) return // something newer was chosen meanwhile
        val c = client ?: return
        L.i("cast load ${info.contentType} live=${info.streamType == MediaInfo.STREAM_TYPE_LIVE} from=${startMs / 1000}s")
        lastPosition = startMs
        c.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCurrentTime(startMs).build())
    }

    // ------------------------------------------------------------------ control

    val isPlaying: Boolean get() = client?.isPlaying == true
    val isBuffering: Boolean get() = client?.isBuffering == true

    fun positionMs(): Long = client?.approximateStreamPosition ?: lastPosition
    fun durationMs(): Long = client?.streamDuration?.takeIf { it > 0 } ?: 0L

    fun togglePause() {
        client?.togglePlayback()
    }

    fun seek(ms: Long) {
        client?.seek(MediaSeekOptions.Builder().setPosition(ms).build())
    }

    fun stopMedia() {
        client?.stop()
    }

    companion object {
        /**
         * Chromecasts can't play raw MPEG-TS, so Xtream channels are asked for in HLS instead
         * (".../live/user/pass/123.ts" -> ".../123.m3u8"). Other links are passed on as they are.
         */
        fun liveStream(url: String): Pair<String, String> {
            val path = url.substringBefore('?')
            return when {
                path.endsWith(".ts", true) && "/live/" in path -> url.replaceFirst(Regex("""\.ts(\?|$)""", RegexOption.IGNORE_CASE), ".m3u8$1") to "application/x-mpegURL"
                path.endsWith(".mp4", true) -> url to "video/mp4"
                path.endsWith(".ts", true) -> url to "video/mp2t"
                else -> url to "application/x-mpegURL"
            }
        }

        fun mimeFor(ext: String): String = when (ext.lowercase()) {
            "m3u8" -> "application/x-mpegURL"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            else -> "video/mp4"
        }
    }
}
