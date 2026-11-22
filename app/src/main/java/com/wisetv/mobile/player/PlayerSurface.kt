package com.wisetv.mobile.player

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.wisetv.mobile.App

/** Video surface with no built-in controls; the app draws its own. Keeps the screen on while shown. */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(player: Player, modifier: Modifier = Modifier, resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT) {
    DisposableEffect(Unit) {
        App.graph.playback.surfaceAttached()
        onDispose { App.graph.playback.surfaceDetached() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShutterBackgroundColor(Color.BLACK)
                setKeepContentOnPlayerReset(true)
                keepScreenOn = true
                isClickable = false
                isFocusable = false
                this.resizeMode = resizeMode
                this.player = player
            }
        },
        update = { view ->
            if (view.resizeMode != resizeMode) view.resizeMode = resizeMode
            if (view.player !== player) view.player = player
        },
        onRelease = { view -> view.player = null },
        modifier = modifier,
    )
}
