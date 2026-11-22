package com.wisetv.mobile.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisetv.mobile.ui.AppState
import com.wisetv.mobile.ui.Fmt
import com.wisetv.mobile.ui.Pal
import com.wisetv.mobile.ui.ProgramMenu
import com.wisetv.mobile.ui.ProgressLine

/** Details of a programme (or just a channel) with what you can do with it. */
@Composable
fun ProgramSheet(app: AppState, menu: ProgramMenu, onDismiss: () -> Unit) {
    val ch = menu.channel
    val p = menu.program
    val now = System.currentTimeMillis()
    val airing = p != null && p.start <= now && p.stop > now
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Pal.Panel) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(ch.logo, ch.number, Modifier.size(width = 56.dp, height = 38.dp).clip(RoundedCornerShape(6.dp)).background(Pal.Panel2).padding(4.dp))
                Spacer(Modifier.width(12.dp))
                Text("${ch.number}  ${ch.name}", fontSize = 14.sp, color = Pal.Accent, fontWeight = FontWeight.SemiBold)
            }
            if (p != null) {
                Text(p.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Pal.Text, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
                Text(
                    "${Fmt.day(p.start)}  ${Fmt.range(p.start, p.stop)}  ·  ${Fmt.duration(p.stop - p.start)}",
                    fontSize = 13.sp,
                    color = Pal.Muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
                if (airing) {
                    ProgressLine((now - p.start).toFloat() / (p.stop - p.start), Modifier.padding(horizontal = 20.dp, vertical = 6.dp).width(180.dp))
                }
                val sub = listOfNotNull(p.episode, p.subtitle, p.category, if (p.isNew) "New" else null).joinToString("  ·  ")
                if (sub.isNotEmpty()) Text(sub, fontSize = 14.sp, color = Pal.Text.copy(alpha = 0.85f), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                if (!p.description.isNullOrBlank()) {
                    Text(p.description, fontSize = 14.sp, color = Pal.Text.copy(alpha = 0.85f), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Action(Icons.Filled.PlayArrow, if (airing || p == null) "Watch now" else "Watch ${ch.name} now") {
                onDismiss()
                app.tune(ch, fullscreen = true)
            }
            if (ch.id > 0) {
                val fav = app.isFavorite(ch)
                Action(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, if (fav) "Remove from favorites" else "Add to favorites") {
                    onDismiss()
                    app.toggleFavorite(ch)
                }
                Action(Icons.Filled.VisibilityOff, "Hide this channel") {
                    onDismiss()
                    app.hideChannel(ch)
                }
            }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Pal.Muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 16.sp, color = Pal.Text)
    }
}
