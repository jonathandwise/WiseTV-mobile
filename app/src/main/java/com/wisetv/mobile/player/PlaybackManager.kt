package com.wisetv.mobile.player

import com.wisetv.mobile.data.db.PlaylistType
import com.wisetv.mobile.data.xtream.XtreamApi
import kotlinx.coroutines.Dispatchers
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.wisetv.mobile.DebugProps
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.wisetv.mobile.AppGraph
import com.wisetv.mobile.L
import com.wisetv.mobile.data.db.ChannelEntity
import com.wisetv.mobile.data.db.ProgressEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Owns the single ExoPlayer instance shared by the mini player and the full-screen player.
 * While a Chromecast is connected, the same calls send the title to the Chromecast instead.
 * Main thread only.
 */
@OptIn(UnstableApi::class)
class PlaybackManager(private val ctx: Context, private val g: AppGraph) {

    sealed interface Source {
        data class Live(val channel: ChannelEntity) : Source
        data class Vod(val item: VodPlayable) : Source
    }

    /** An external subtitle file to offer alongside the video. */
    data class Subtitle(val url: String, val mime: String, val language: String?, val label: String?)

    /**
     * A movie, a series episode or a Plex title.
     * [local] means it doesn't use the IPTV provider's connection (Plex).
     */
    data class VodPlayable(
        val key: String,
        val playlistId: Long,
        val kind: String,
        val refId: String,
        val title: String,
        val subtitle: String?,
        val url: String,
        val poster: String?,
        val userAgent: String?,
        val seriesKey: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val local: Boolean = false,
        val plexKey: String? = null,
        val plexServer: String? = null,
        val plexSession: String? = null,
        val subtitles: List<Subtitle> = emptyList(),
        /** Sent with every request for this title (Plex needs its sign-in on each video chunk). */
        val headers: Map<String, String> = emptyMap(),
    )

    sealed interface Event {
        /** [everywhere]: also worth a pop-up message outside the player (in the guide preview). */
        data class Error(val message: String, val everywhere: Boolean = false) : Event
        data object Ended : Event
        data class Notice(val message: String) : Event
    }

    data class TrackOption(val label: String, val group: Tracks.Group?, val index: Int, val selected: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    val nowPlaying = MutableStateFlow<Source?>(null)
    val events = MutableSharedFlow<Event>(extraBufferCapacity = 8)

    /** The live channel watched before the current one, for "previous channel". */
    var previousChannel: ChannelEntity? = null
        private set

    var queue: List<VodPlayable> = emptyList()
        private set
    var queueIndex: Int = -1
        private set

    private var currentUrl: String? = null
    private var currentUa: String? = null
    private var currentLive = false
    private var retries = 0
    private var triedHls = false   // the HLS fallback has been attempted for this stream
    private var hlsActive = false  // this stream is currently being played as HLS
    private val hlsUrls = HashSet<String>()
    private var progressJob: Job? = null
    private var backgroundSource: Source? = null

    /** Names of the decoders in use, for the Stream info page (null audio = passthrough). */
    var videoDecoderName: String? = null
        private set
    var audioDecoderName: String? = null
        private set
    val passthroughEnabled: Boolean = g.settings.audioPassthrough

    private val renderers = object : DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                // Without passthrough, Dolby audio is decoded here and sent as plain PCM, which keeps
                // the audio clock (and so the video timing) reliable on Fire TV.
                .setAudioCapabilities(if (passthroughEnabled) AudioCapabilities.getCapabilities(context) else AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
    }.setEnableDecoderFallback(true)
        .setMediaCodecSelector { mime, secure, tunneling ->
            val infos = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
            // The software decoder is slower but copes with streams some TV chips stall on.
            if (activeFix and FIX_SOFTWARE != 0 && mime.startsWith("video/")) infos.sortedBy { if (it.softwareOnly) 0 else 1 } else infos
        }

    val player: ExoPlayer = ExoPlayer.Builder(ctx, renderers)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setHandleAudioBecomingNoisy(true)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
                .build(),
        )
        .build()

    // ---------------------------------------------------------------- picture fixes

    /**
     * Some streams make a TV's hardware decoder stop after a few frames (picture frozen, sound
     * playing). Each channel or title can have fixes: [FIX_READING] reads the stream without
     * splitting it into pictures ourselves, [FIX_SOFTWARE] uses the software video decoder.
     * They're set from Stream info, or tried in turn automatically when the picture freezes.
     */
    private val fixes: MutableMap<String, Int> = g.settings.videoFixes.split(';').mapNotNull {
        val k = it.substringBefore('=', "")
        val v = it.substringAfter('=', "").toIntOrNull()
        if (k.isNotEmpty() && v != null) k to v else null
    }.toMap(LinkedHashMap())

    @Volatile
    var activeFix = 0
        private set
    private var fixKey: String? = null
    private var lastRendered = -1
    private var frozenSecs = 0
    private var lastAutoFix = 0L
    private var connCheckDone = false  // asked the provider how many connections are in use, for this tune
    private var connBlocked = false    // another app or device has the connection: stop retrying
    /** Fixes already tried on each stream since it was tuned to. */
    private val tried = HashMap<String, MutableSet<Int>>()
    /** When an automatic fix was applied (0 = none pending), to learn from it once the picture keeps moving. */
    private var autoFixAt = 0L

    /** A fix that worked on one channel becomes the starting point for the rest of its playlist. */
    private fun playlistKey(src: Source?): String? = (src as? Source.Live)?.let { "p${it.channel.playlistId}" }

    private fun keyFor(src: Source?): String? = when (src) {
        is Source.Live -> "c" + (src.channel.playlistId.toString() + src.channel.url).hashCode().toUInt().toString(36)
        is Source.Vod -> "v" + src.item.key.hashCode().toUInt().toString(36)
        null -> null
    }

    /** Sets the fixes for what's playing now and restarts it with them. */
    fun setFix(mask: Int, auto: Boolean = false) {
        val key = fixKey ?: return
        // Stored even when 0, so a channel can opt out of its playlist's fix.
        fixes.remove(key)
        fixes[key] = mask
        // A choice made by hand is the new starting point for any automatic tries.
        if (auto) tried.getOrPut(key) { mutableSetOf() } += mask else tried[key] = mutableSetOf(mask)
        while (fixes.size > 300) fixes.remove(fixes.keys.first())
        g.settings.videoFixes = fixes.entries.joinToString(";") { "${it.key}=${it.value}" }
        L.i("video fix set $key fix=$mask")
        val url = currentUrl ?: return
        val pos = if (currentLive) 0 else player.currentPosition
        player.stop()   // so the video decoder is chosen again
        start(url, currentUa, pos, currentLive, forceHls = hlsActive, isRetry = true)
    }

    private val frameWatch = object : Runnable {
        override fun run() {
            main.postDelayed(this, 1_000)
            val c = player.videoDecoderCounters
            if (player.playbackState != Player.STATE_READY || !player.playWhenReady || player.videoFormat == null || c == null) {
                lastRendered = -1
                frozenSecs = 0
                return
            }
            c.ensureUpdated()
            val out = c.renderedOutputBufferCount + c.droppedBufferCount + c.skippedOutputBufferCount
            frozenSecs = if (out == lastRendered || DebugProps.fakeVideoStall) frozenSecs + 1 else 0
            lastRendered = out
            if (autoFixAt > 0 && frozenSecs == 0 && System.currentTimeMillis() - autoFixAt > 20_000) {
                autoFixAt = 0
                playlistKey(nowPlaying.value)?.let { pk ->
                    if (fixes[pk] != activeFix) {
                        fixes[pk] = activeFix
                        g.settings.videoFixes = fixes.entries.joinToString(";") { "${it.key}=${it.value}" }
                        L.i("video fix learned playlist=${pk.drop(1)} fix=$activeFix")
                    }
                }
            }
            if (frozenSecs >= 4) {
                frozenSecs = 0
                onPictureFrozen(c.queuedInputBufferCount, out)
            }
        }
    }

    private fun onPictureFrozen(queued: Int, out: Int) {
        val done = fixKey?.let { tried.getOrPut(it) { mutableSetOf(activeFix) } } ?: mutableSetOf(activeFix)
        val next = listOf(0, FIX_READING, FIX_SOFTWARE, FIX_READING or FIX_SOFTWARE).firstOrNull { it !in done }
        L.i("video stalled decoder=$videoDecoderName queued=$queued out=$out fix=$activeFix -> ${next ?: "none left"}")
        val now = System.currentTimeMillis()
        if (next == null || now - lastAutoFix < 3_000) return
        lastAutoFix = now
        val what = when {
            next and FIX_SOFTWARE != 0 -> "the software video decoder"
            next == FIX_READING -> "another way of reading the stream"
            else -> "the standard settings"
        }
        events.tryEmit(Event.Notice("The picture froze \u2014 trying $what"))
        setFix(next, auto = true)
        autoFixAt = System.currentTimeMillis()
    }

    private val stallCheck = Runnable {
        val src = nowPlaying.value
        if (src is Source.Live && player.playWhenReady && player.playbackState == Player.STATE_BUFFERING) {
            L.i("player stalled, reconnecting")
            scheduleRetry(src)
        }
    }

    init {
        main.postDelayed(frameWatch, 1_000)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoderName = decoderName
                L.i("video decoder $decoderName")
            }

            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                audioDecoderName = decoderName
                L.i("audio decoder $decoderName")
            }

            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                L.i("video format ${format.sampleMimeType} ${format.codecs} ${format.width}x${format.height} ${format.frameRate}fps")
            }

            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                audioDecoderName = null
                L.i("audio format ${format.sampleMimeType} ${format.channelCount}ch ${format.sampleRate}Hz passthrough=$passthroughEnabled")
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                L.i("dropped $droppedFrames video frames in ${elapsedMs}ms")
            }
        })
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleError(error)

            override fun onPlaybackStateChanged(state: Int) {
                L.i("player state=${stateName(state)} live=$currentLive size=${player.videoSize.width}x${player.videoSize.height}")
                main.removeCallbacks(stallCheck)
                when (state) {
                    Player.STATE_READY -> {
                        retries = 0
                        if (hlsActive) currentUrl?.let { hlsUrls += it }
                    }
                    Player.STATE_BUFFERING -> if (currentLive) main.postDelayed(stallCheck, 20_000)
                    Player.STATE_ENDED -> when (val src = nowPlaying.value) {
                        is Source.Live -> scheduleRetry(src)
                        is Source.Vod -> {
                            saveProgress(finished = true)
                            events.tryEmit(Event.Ended)
                        }
                        null -> Unit
                    }
                    else -> Unit
                }
            }
        })
    }

    private fun stateName(s: Int) = when (s) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> s.toString()
    }

    // ---------------------------------------------------------------- live

    fun playChannel(ch: ChannelEntity, force: Boolean = false) {
        val cur = nowPlaying.value
        if (!force && cur is Source.Live && cur.channel.id == ch.id && (casting || player.playbackState != Player.STATE_IDLE)) return
        endCurrent()
        if (cur is Source.Live && cur.channel.id != ch.id) previousChannel = cur.channel
        nowPlaying.value = Source.Live(ch)
        if (ch.id > 0) g.settings.lastChannelId = ch.id
        if (casting) {
            L.i("cast channel=${ch.name}")
            stopLocal()
            g.cast.loadLive(ch)
            return
        }
        L.i("tune channel=${ch.name}")
        start(ch.url, ch.userAgent, 0, live = true)
    }

    fun playNextChannel(dir: Int, list: List<ChannelEntity>) {
        val cur = (nowPlaying.value as? Source.Live)?.channel ?: return
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == cur.id }
        val next = list[((if (i < 0) 0 else i) + dir).mod(list.size)]
        playChannel(next)
    }

    // ---------------------------------------------------------------- movies, episodes

    fun setQueue(items: List<VodPlayable>, index: Int) {
        queue = items
        queueIndex = index
    }

    fun playVod(item: VodPlayable, startPosMs: Long) {
        endCurrent()
        val idx = queue.indexOfFirst { it.key == item.key }
        if (idx >= 0) queueIndex = idx
        nowPlaying.value = Source.Vod(item)
        startProgressSaver()
        if (casting) {
            L.i("cast kind=${item.kind} title=${item.title}")
            stopLocal()
            g.cast.loadVod(item, startPosMs)
            return
        }
        L.i("play kind=${item.kind} title=${item.title} [${item.subtitle.orEmpty()}] from=${startPosMs / 1000}s")
        start(item.url, item.userAgent, startPosMs, live = false)
    }

    fun playNextInQueue(): Boolean {
        val next = queue.getOrNull(queueIndex + 1) ?: return false
        playVod(next, 0)
        return true
    }

    fun hasNextInQueue(): Boolean = nowPlaying.value is Source.Vod && queueIndex + 1 < queue.size

    // ---------------------------------------------------------------- control

    fun stop() {
        endCurrent()
        stopLocal()
        if (casting) g.cast.stopMedia()
        nowPlaying.value = null
    }

    private fun stopLocal() {
        main.removeCallbacks(stallCheck)
        player.stop()
        player.clearMediaItems()
    }

    /**
     * The app went to the background (or the screen turned off) and background audio is off:
     * live TV stops, so the provider connection is freed, but the channel is remembered;
     * a movie or episode just pauses.
     */
    fun park() {
        if (casting) return
        when (val src = nowPlaying.value) {
            is Source.Live -> {
                backgroundSource = src
                stop()
            }
            is Source.Vod -> {
                saveProgress()
                player.pause()
            }
            null -> Unit
        }
    }

    /** Resumes the channel stopped by [park], if any. */
    fun unpark() {
        val src = backgroundSource as? Source.Live ?: return
        backgroundSource = null
        if (nowPlaying.value == null) playChannel(src.channel, force = true)
    }

    /** In the background with sound only: stop decoding (and, for adaptive streams, downloading) the picture. */
    fun setVideoEnabled(enabled: Boolean) {
        val disabled = C.TRACK_TYPE_VIDEO in player.trackSelectionParameters.disabledTrackTypes
        if (disabled == !enabled) return
        L.i("video ${if (enabled) "on" else "off (background audio)"}")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }

    // Video is only decoded while a picture is on screen: with the app in the background, or on a
    // tab without the player, live TV and titles carry on as sound only.
    private var surfaces = 0
    private var appVisible = true
    private val videoCheck = Runnable { setVideoEnabled(appVisible && surfaces > 0) }

    fun surfaceAttached() {
        surfaces++
        main.removeCallbacks(videoCheck)
        videoCheck.run()
    }

    fun surfaceDetached() {
        surfaces = (surfaces - 1).coerceAtLeast(0)
        main.removeCallbacks(videoCheck)
        main.postDelayed(videoCheck, 1_500)
    }

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        main.removeCallbacks(videoCheck)
        if (visible) main.postDelayed(videoCheck, 300) else videoCheck.run()
    }

    val isPlaying: Boolean get() = if (casting) g.cast.isPlaying else player.isPlaying

    fun togglePause() {
        if (casting) {
            g.cast.togglePause()
            return
        }
        if (player.playbackState == Player.STATE_IDLE && nowPlaying.value != null) {
            // Stopped by an error or after reconnecting gave up: start again.
            retries = 0
            connBlocked = false
            currentUrl?.let { start(it, currentUa, if (currentLive) 0 else player.currentPosition, currentLive, forceHls = hlsActive, isRetry = true) }
            return
        }
        player.playWhenReady = !player.playWhenReady
    }

    fun positionMs(): Long = if (casting) g.cast.positionMs() else player.currentPosition
    fun durationMs(): Long = if (casting) g.cast.durationMs() else player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    fun seekTo(ms: Long) {
        val target = ms.coerceAtLeast(0)
        if (casting) g.cast.seek(target) else player.seekTo(target)
    }

    // ---------------------------------------------------------------- Chromecast hand-over

    private val casting: Boolean get() = g.cast.active.value

    /** A Chromecast session started: move whatever is playing to it. */
    fun onCastStarted() {
        val src = nowPlaying.value ?: return
        val pos = player.currentPosition
        stopLocal()
        when (src) {
            is Source.Live -> g.cast.loadLive(src.channel)
            is Source.Vod -> g.cast.loadVod(src.item, pos)
        }
    }

    /** The Chromecast session ended: carry on here from where it got to. */
    fun onCastSessionEnded(lastPositionMs: Long) {
        when (val src = nowPlaying.value) {
            is Source.Live -> start(src.channel.url, src.channel.userAgent, 0, live = true)
            is Source.Vod -> {
                // Ready to carry on here, but paused: you may have just switched the TV off.
                start(src.item.url, src.item.userAgent, lastPositionMs, live = false)
                player.playWhenReady = false
            }
            null -> Unit
        }
    }

    /** Called by the cast manager when the Chromecast finished a title. */
    fun onCastTitleFinished() {
        if (nowPlaying.value is Source.Vod) {
            saveProgress(finished = true)
            events.tryEmit(Event.Ended)
        }
    }

    // ---------------------------------------------------------------- media session

    /**
     * The player the lock screen, notification, headphones and Bluetooth controls talk to.
     * "Next" / "previous" change channel during live TV and move through the episode list otherwise.
     */
    var channelList: () -> List<ChannelEntity> = { emptyList() }

    val sessionPlayer: Player = object : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

        override fun seekToNext() = next(1)
        override fun seekToNextMediaItem() = next(1)
        override fun seekToPrevious() = next(-1)
        override fun seekToPreviousMediaItem() = next(-1)

        private fun next(dir: Int) {
            when (nowPlaying.value) {
                is Source.Live -> playNextChannel(dir, channelList())
                is Source.Vod -> if (dir > 0) playNextInQueue() else player.seekTo(0)
                null -> Unit
            }
        }
    }

    var speed: Float
        get() = player.playbackParameters.speed
        set(v) = player.setPlaybackSpeed(v)

    fun trackOptions(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        var anySelected = false
        for (group in player.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val sel = group.isTrackSelected(i)
                anySelected = anySelected || sel
                out += TrackOption(trackLabel(group.getTrackFormat(i), out.size), group, i, sel)
            }
        }
        if (type == C.TRACK_TYPE_TEXT) out.add(0, TrackOption("Off", null, -1, !anySelected))
        return out
    }

    fun selectTrack(type: Int, opt: TrackOption) {
        val b = player.trackSelectionParameters.buildUpon()
        val group = opt.group
        if (group == null) {
            b.setTrackTypeDisabled(type, true)
        } else {
            b.setTrackTypeDisabled(type, false)
            b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, opt.index))
        }
        player.trackSelectionParameters = b.build()
    }

    private fun trackLabel(f: Format, n: Int): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            Locale.forLanguageTag(it).displayLanguage.takeIf { d -> d.isNotBlank() } ?: it
        }
        val channels = when (f.channelCount) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> null
        }
        val codec = f.sampleMimeType?.substringAfter('/')?.uppercase()
        return listOfNotNull(f.label, lang, channels, codec).distinct().joinToString(" · ").ifBlank { "Track ${n + 1}" }
    }

    // ---------------------------------------------------------------- internals

    private fun start(
        url: String,
        ua: String?,
        startPos: Long,
        live: Boolean,
        forceHls: Boolean = false,
        isRetry: Boolean = false,
        subtitles: List<Subtitle> = (nowPlaying.value as? Source.Vod)?.item?.subtitles.orEmpty(),
        headers: Map<String, String> = (nowPlaying.value as? Source.Vod)?.item?.headers.orEmpty(),
    ) {
        currentUrl = url
        currentUa = ua
        currentLive = live
        fixKey = keyFor(nowPlaying.value)
        activeFix = fixKey?.let { fixes[it] } ?: playlistKey(nowPlaying.value)?.let { fixes[it] } ?: 0
        if (!isRetry) {
            connCheckDone = false
            connBlocked = false
            autoFixAt = 0
            fixKey?.let { tried[it] = mutableSetOf(activeFix) }
        }
        lastRendered = -1
        frozenSecs = 0
        if (activeFix != 0) L.i("video fix in use fix=$activeFix")
        val hls = forceHls || url in hlsUrls
        hlsActive = hls
        if (!isRetry) {
            retries = 0
            triedHls = hls
            if (player.playbackParameters.speed != 1f) player.setPlaybackSpeed(1f)
        }
        val httpFactory = OkHttpDataSource.Factory(g.http.player).setUserAgent(g.http.ua(ua)).setDefaultRequestProperties(headers)
        val dataSource = DefaultDataSource.Factory(ctx, httpFactory)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            if (activeFix and FIX_READING != 0) DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
            else DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
        val factory = DefaultMediaSourceFactory(dataSource, extractors)
        val item = MediaItem.Builder().setUri(url).setMediaMetadata(metadataFor(nowPlaying.value))
        if (hls || url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (subtitles.isNotEmpty()) {
            item.setSubtitleConfigurations(
                subtitles.map {
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(it.url))
                        .setMimeType(it.mime)
                        .setLanguage(it.language)
                        .setLabel(it.label)
                        .build()
                },
            )
        }
        player.setMediaSource(factory.createMediaSource(item.build()), startPos.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
    }

    private fun metadataFor(src: Source?): MediaMetadata {
        val b = MediaMetadata.Builder()
        when (src) {
            is Source.Live -> b.setTitle(src.channel.name).setArtist("Live TV").setArtworkUri(src.channel.logo?.let { Uri.parse(it) })
            is Source.Vod -> b.setTitle(src.item.title).setArtist(src.item.subtitle).setArtworkUri(src.item.poster?.let { Uri.parse(it) })
            null -> Unit
        }
        return b.build()
    }

    private fun handleError(error: PlaybackException) {
        L.i("player error=${error.errorCodeName} cause=${error.cause?.javaClass?.simpleName}: ${error.cause?.message}")
        val src = nowPlaying.value ?: return
        val url = currentUrl ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        // Some providers answer a ".ts" address with an HLS playlist: retry once as HLS.
        // (A stream that merely cut out is "malformed", not "unrecognized": that one is just retried.)
        val unrecognized = error.cause is UnrecognizedInputFormatException ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        val notHls = error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
        if (hlsActive && notHls) {
            // It wasn't HLS after all (or no longer is): go back to treating it as plain video.
            hlsActive = false
            hlsUrls -= url
        }
        if (!triedHls && unrecognized && !url.startsWith("file:")) {
            triedHls = true
            start(url, currentUa, 0, currentLive, forceHls = true, isRetry = true)
            return
        }
        if (src is Source.Live) {
            scheduleRetry(src)
            return
        }
        events.tryEmit(Event.Error(friendly(error)))
    }

    private fun scheduleRetry(src: Source.Live) {
        main.removeCallbacks(stallCheck)
        if (connBlocked) return
        if (retries >= 8) {
            events.tryEmit(Event.Error("This channel isn't responding. Try another channel or try again later."))
            return
        }
        retries++
        // Checked only after several refusals: a provider can briefly count our own previous stream too.
        if (retries == 3 && !connCheckDone) checkConnections(src)
        // The first retry is silent: providers often refuse a new stream for a moment after a channel change.
        if (retries > 1) events.tryEmit(Event.Error("Reconnecting…"))
        val url = currentUrl ?: return
        main.postDelayed({
            if (nowPlaying.value == src && currentUrl == url && !connBlocked) start(url, currentUa, 0, live = true, forceHls = hlsActive, isRetry = true)
        }, if (retries == 1) 800L else 1_500L * retries)
    }

    /**
     * A channel keeps being refused: ask the provider whether its connections are all in use. If so,
     * another app or device (often TiviMate left running in the background) has it, so say that
     * instead of retrying.
     */
    private fun checkConnections(src: Source.Live) {
        connCheckDone = true
        val pid = src.channel.playlistId
        g.appScope.launch(Dispatchers.IO) {
            val pl = g.db.playlists().get(pid) ?: return@launch
            if (pl.type != PlaylistType.XTREAM) return@launch
            val max = try {
                val auth = g.repo.xtream(pl).authenticate()
                L.i("connections active=${auth.activeConnections} max=${auth.maxConnections}")
                if (auth.inUse) auth.maxConnections else return@launch
            } catch (e: XtreamApi.ConnectionLimitException) {
                pl.maxConnections
            } catch (e: Exception) {
                return@launch
            }
            main.post {
                if (nowPlaying.value != src || connBlocked) return@post
                connBlocked = true
                player.stop()
                L.i("connection in use by another app max=$max")
                events.tryEmit(Event.Error(XtreamApi.inUseMessage(max), everywhere = true))
            }
        }
    }

    private fun friendly(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused this stream. Your connection limit may be reached."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "Couldn't connect to the server."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This title isn't on the server any more."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> "This phone can't decode this video format."
        else -> "Playback error: ${e.errorCodeName}"
    }

    /** Saves where the current title got to and tells Plex to stop converting it. */
    private fun endCurrent() {
        saveProgress(stopping = true)
        stopProgressSaver()
        val item = (nowPlaying.value as? Source.Vod)?.item
        val server = item?.plexServer
        item?.plexSession?.let { session -> if (server != null) g.appScope.launch { g.plex.stopTranscode(server, session) } }
    }

    private fun startProgressSaver() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(10_000)
                if (isPlaying) saveProgress()
            }
        }
    }

    private fun stopProgressSaver() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun saveProgress(finished: Boolean = false, stopping: Boolean = false) {
        val src = nowPlaying.value as? Source.Vod ?: return
        val dur = durationMs().takeIf { it > 0 } ?: return
        val pos = if (finished) dur else positionMs()
        val item = src.item
        val plexKey = item.plexKey
        val plexServer = item.plexServer
        if (plexKey != null && plexServer != null) {
            // Plex keeps its own resume points, shared with every Plex app.
            val state = if (stopping || finished) "stopped" else if (isPlaying) "playing" else "paused"
            val done = finished || pos >= dur * 0.95
            g.appScope.launch {
                g.plex.timeline(plexServer, plexKey, state, pos, dur, item.plexSession)
                if (done && (stopping || finished)) g.plex.markWatched(plexServer, plexKey)
            }
            return
        }
        val entity = ProgressEntity(
            key = item.key,
            playlistId = item.playlistId,
            kind = item.kind,
            refId = item.refId,
            title = item.title,
            poster = item.poster,
            position = pos,
            duration = dur,
            updatedAt = System.currentTimeMillis(),
            seriesKey = item.seriesKey,
            season = item.season,
            episodeNum = item.episode,
            finished = finished || pos >= dur * 0.95,
        )
        g.appScope.launch { g.db.progress().upsert(entity) }
    }

    companion object {
        const val FIX_READING = 1
        const val FIX_SOFTWARE = 2
    }
}
