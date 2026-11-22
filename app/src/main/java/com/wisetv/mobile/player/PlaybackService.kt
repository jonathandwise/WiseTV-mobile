package com.wisetv.mobile.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.wisetv.mobile.App
import com.wisetv.mobile.L
import com.wisetv.mobile.ui.MainActivity

/**
 * Keeps sound playing with the app in the background or the screen off, and gives the
 * notification, lock screen, headphones and Bluetooth controls (play/pause, next/previous channel).
 * The player itself belongs to [PlaybackManager]; this only publishes it.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, App.graph.playback.sessionPlayer)
            .setSessionActivity(open)
            .build()
        L.i("media session started")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping WiseTV away from the recent apps stops playback. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        L.i("task removed: stopping playback")
        App.graph.playback.stop()
        stopSelf()
    }

    override fun onDestroy() {
        // Releases the session only; the player stays with PlaybackManager.
        session?.release()
        session = null
        super.onDestroy()
    }
}
