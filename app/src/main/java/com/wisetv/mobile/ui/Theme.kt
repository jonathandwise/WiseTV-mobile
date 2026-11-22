package com.wisetv.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The WiseTV colours, shared with the Fire TV and Windows apps. */
object Pal {
    val Bg = Color(0xFF0D1015)
    val Panel = Color(0xFF151A21)
    val Panel2 = Color(0xFF1E242D)
    val Panel3 = Color(0xFF28303B)
    val Line = Color(0xFF2A313B)
    val Text = Color(0xFFE8EDF2)
    val Muted = Color(0xFF8C96A4)
    val Accent = Color(0xFF2EC4E6)
    val AccentDim = Color(0xFF14445A)
    val Rec = Color(0xFFE5484D)
    val Warn = Color(0xFFF5A524)
    val Good = Color(0xFF46C28E)
    val Past = Color(0xFF11151B)
    val Scrim = Color(0xE60D1015)
}

@Composable
fun WiseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pal.Accent,
            onPrimary = Color.Black,
            primaryContainer = Pal.AccentDim,
            onPrimaryContainer = Pal.Text,
            secondary = Pal.Accent,
            onSecondary = Color.Black,
            secondaryContainer = Pal.AccentDim,
            onSecondaryContainer = Pal.Text,
            background = Pal.Bg,
            onBackground = Pal.Text,
            surface = Pal.Panel,
            onSurface = Pal.Text,
            surfaceVariant = Pal.Panel2,
            onSurfaceVariant = Pal.Muted,
            surfaceContainer = Pal.Panel,
            surfaceContainerHigh = Pal.Panel2,
            surfaceContainerHighest = Pal.Panel3,
            surfaceContainerLow = Pal.Panel,
            surfaceContainerLowest = Pal.Bg,
            outline = Pal.Line,
            outlineVariant = Pal.Line,
            error = Pal.Rec,
        ),
        content = content,
    )
}
