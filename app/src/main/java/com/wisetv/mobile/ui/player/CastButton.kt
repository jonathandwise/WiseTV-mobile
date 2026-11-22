package com.wisetv.mobile.ui.player

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.wisetv.mobile.App
import com.wisetv.mobile.L

/**
 * The standard Chromecast button: it appears when a Chromecast is on the same Wi-Fi, opens the
 * device picker, and shows when casting. Nothing is shown on phones without Google Play services.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    if (App.graph.cast.context == null) return
    AndroidView(
        factory = { ctx ->
            MediaRouteButton(ctx).apply {
                try {
                    CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, this)
                } catch (e: Exception) {
                    L.i("cast button: ${e.message}")
                }
            }
        },
        modifier = modifier.size(48.dp),
    )
}
