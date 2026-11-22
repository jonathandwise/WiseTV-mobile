package com.wisetv.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

fun Context.findActivity(): Activity {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    throw IllegalStateException("No activity")
}

/** The current time, refreshed every [intervalMs]. */
@Composable
fun rememberNow(intervalMs: Long = 30_000): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            delay(intervalMs - System.currentTimeMillis() % intervalMs.coerceAtLeast(1000))
        }
    }
    return now
}

@Composable
fun CenterMessage(title: String, subtitle: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 18.sp, color = Pal.Text, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, fontSize = 14.sp, color = Pal.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    CircularProgressIndicator(modifier.size(size), color = Pal.Accent, strokeWidth = 3.dp)
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = Pal.Accent) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Pal.Line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(color))
    }
}

/** A plain top bar: back arrow, title, and optional actions on the right. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Pal.Bg).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Pal.Text) }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Pal.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = Pal.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.SemiBold,
        color = Pal.Accent,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp),
    )
}

/** A tappable settings row: title, optional explanation below, optional value on the right. */
@Composable
fun SettingRow(
    title: String,
    value: String? = null,
    subtitle: String? = null,
    danger: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (danger) Pal.Rec else Pal.Muted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = if (danger) Pal.Rec else Pal.Text)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 13.sp, color = Pal.Muted, modifier = Modifier.padding(top = 2.dp))
        }
        if (value != null) {
            Text(value, fontSize = 14.sp, color = Pal.Accent, textAlign = TextAlign.End, modifier = Modifier.padding(start = 12.dp).width(140.dp), maxLines = 2)
        }
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = Pal.Text)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 13.sp, color = Pal.Muted, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Pal.Accent),
        )
    }
}

/** One choice in a [ChoiceSheet]. */
data class Choice(val label: String, val selected: Boolean = false, val danger: Boolean = false, val subtitle: String? = null, val icon: ImageVector? = null, val onPick: () -> Unit)

/** A bottom sheet with a title and a list of choices; picking one closes it. */
@Composable
fun ChoiceSheet(title: String?, choices: List<Choice>, onDismiss: () -> Unit, subtitle: String? = null, header: (@Composable ColumnScope.() -> Unit)? = null) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Pal.Panel) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            if (title != null) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Pal.Text, modifier = Modifier.padding(horizontal = 20.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Pal.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            header?.invoke(this)
            Spacer(Modifier.height(8.dp))
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(choices) { _, c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onDismiss(); c.onPick() }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (c.icon != null) {
                            Icon(c.icon, null, tint = if (c.danger) Pal.Rec else Pal.Muted, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(16.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                c.label,
                                fontSize = 16.sp,
                                color = when {
                                    c.danger -> Pal.Rec
                                    c.selected -> Pal.Accent
                                    else -> Pal.Text
                                },
                                fontWeight = if (c.selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (c.subtitle != null) Text(c.subtitle, fontSize = 13.sp, color = Pal.Muted)
                        }
                        if (c.selected) Icon(Icons.Filled.Check, null, tint = Pal.Accent)
                    }
                }
            }
        }
    }
}

/** A blocking "please wait" box. */
@Composable
fun BusyDialog(message: String, detail: String? = null) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Row(
            Modifier.background(Pal.Panel2, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spinner(size = 32.dp)
            Spacer(Modifier.width(18.dp))
            Column {
                Text(message, fontSize = 16.sp, color = Pal.Text)
                if (detail != null) Text(detail, fontSize = 13.sp, color = Pal.Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Poster tile for movies and series. */
data class VodCard(val id: String, val title: String, val image: String?, val rating: String?, val progress: Float?)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(card: VodCard, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Pal.Panel2)) {
            if (card.image != null) {
                AsyncImage(model = card.image, contentDescription = card.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                    Text(card.title, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 4, color = Pal.Text)
                }
            }
            card.rating?.toDoubleOrNull()?.takeIf { it > 0 }?.let {
                Text(
                    "★ %.1f".format(it),
                    fontSize = 10.sp,
                    color = Pal.Text,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Pal.Scrim, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            card.progress?.let {
                ProgressLine(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(5.dp))
            }
        }
        Text(card.title, fontSize = 12.sp, color = Pal.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
fun Divider() = HorizontalDivider(color = Pal.Line, thickness = 0.5.dp)

/** Small rounded label used for group / category / season chips. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: ImageVector? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Pal.Accent else Pal.Panel2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, fontSize = 14.sp, color = if (selected) Color.Black else Pal.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            Icon(trailing, null, tint = if (selected) Color.Black else Pal.Muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun PrimaryButtonText(text: String) = Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
