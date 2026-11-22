package com.wisetv.mobile.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisetv.mobile.L
import com.wisetv.mobile.data.plex.Plex
import com.wisetv.mobile.ui.AppState
import com.wisetv.mobile.ui.Pal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class PlexDialog { Link, Servers, Account }

@Composable
fun PlexDialogs(app: AppState, which: PlexDialog, onChange: (PlexDialog?) -> Unit) {
    when (which) {
        PlexDialog.Link -> LinkDialog(app, onChange)
        PlexDialog.Servers -> ServersDialog(app, onChange)
        PlexDialog.Account -> AlertDialog(
            onDismissRequest = { onChange(null) },
            containerColor = Pal.Panel2,
            title = { Text("Plex account") },
            text = { Text("Signed in as ${app.g.settings.plexUser}.") },
            confirmButton = { TextButton(onClick = { onChange(PlexDialog.Servers) }) { Text("Choose servers") } },
            dismissButton = {
                TextButton(onClick = {
                    app.g.plex.unlink()
                    onChange(null)
                    app.showToast("Plex unlinked")
                }) { Text("Unlink Plex", color = Pal.Rec) }
            },
        )
    }
}

/** Gets a code from plex.tv; signing in on the Plex page (or entering the code at plex.tv/link) links the account. */
@Composable
private fun LinkDialog(app: AppState, onChange: (PlexDialog?) -> Unit) {
    val plex = app.g.plex
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf<Plex.Pin?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        pin = null
        try {
            val p = plex.startPin()
            pin = p
            L.i("plex pin shown")
            val deadline = System.currentTimeMillis() + 10 * 60_000L
            while (System.currentTimeMillis() < deadline) {
                delay(2_000)
                val token = runCatching { plex.pollPin(p) }.getOrNull() ?: continue
                plex.finishLink(token)
                onChange(PlexDialog.Servers)
                return@LaunchedEffect
            }
            error = "The code expired. Get a new one and try again."
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = "Couldn't reach plex.tv: ${e.message}"
        }
    }

    AlertDialog(
        onDismissRequest = { onChange(null) },
        containerColor = Pal.Panel2,
        title = { Text("Link your Plex account") },
        text = {
            val p = pin
            Column {
                when {
                    error != null -> Text(error.orEmpty(), color = Pal.Warn)
                    p == null -> Text("Getting a code…", color = Pal.Muted)
                    else -> {
                        Text("Sign in on the Plex page, then come back here. WiseTV continues by itself.", color = Pal.Text)
                        Spacer(Modifier.padding(6.dp))
                        Button(
                            onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(plex.authUrl(p)))) } },
                            colors = ButtonDefaults.buttonColors(containerColor = Pal.Accent, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Sign in with Plex", fontWeight = FontWeight.SemiBold) }
                        Spacer(Modifier.padding(6.dp))
                        Text("Or, on any device, go to plex.tv/link and enter:", fontSize = 13.sp, color = Pal.Muted)
                        Text(
                            p.code,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 6.sp,
                            color = Pal.Text,
                            modifier = Modifier.padding(top = 6.dp).background(Pal.Bg, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (error != null) TextButton(onClick = { attempt++ }) { Text("Try again") }
        },
        dismissButton = { TextButton(onClick = { onChange(null) }) { Text("Cancel") } },
    )
}

/** Every Plex server on the account (yours and shared ones), ticked when in use. */
@Composable
private fun ServersDialog(app: AppState, onChange: (PlexDialog?) -> Unit) {
    val plex = app.g.plex
    var servers by remember { mutableStateOf<List<Plex.Server>?>(null) }
    var checked by remember { mutableStateOf(plex.servers.map { it.id }.toSet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    fun apply(chosen: List<Plex.Server>) {
        busy = if (chosen.size == 1) "Connecting to ${chosen[0].name}…" else "Connecting to ${chosen.size} servers…"
        app.scope.launch {
            val results = plex.setServers(chosen)
            onChange(null)
            val failed = results.filterValues { it != null }.keys
            app.showToast(
                when {
                    chosen.isEmpty() -> "Plex is linked, but no servers are in use"
                    failed.isEmpty() && chosen.size == 1 -> "Plex connected: ${chosen[0].name}. Find it in Movies and Series."
                    failed.isEmpty() -> "Plex connected: ${chosen.size} servers. Find them in Movies and Series."
                    failed.size == chosen.size -> "Couldn't reach ${failed.joinToString()}. Check that the server is on."
                    else -> "Plex connected. Couldn't reach ${failed.joinToString()} right now."
                },
            )
        }
    }

    LaunchedEffect(Unit) {
        try {
            val list = plex.accountServers()
            servers = list
            if (plex.servers.isEmpty() && list.isNotEmpty()) apply(list) // first link: use everything
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = "Couldn't get your servers from plex.tv: ${e.message}"
        }
    }

    val list = servers
    AlertDialog(
        onDismissRequest = { onChange(null) },
        containerColor = Pal.Panel2,
        title = { Text("Plex servers") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    busy != null -> Text(busy.orEmpty(), color = Pal.Muted)
                    list == null && error == null -> Text("Looking for servers…", color = Pal.Muted)
                    else -> {
                        Text("Movies and shows from every ticked server appear in Movies, Series and Search.", fontSize = 13.sp, color = Pal.Muted)
                        error?.let { Text(it, color = Pal.Warn) }
                        if (list != null && list.isEmpty()) Text("There are no Plex servers on this account.", color = Pal.Text)
                        list?.forEach { srv ->
                            Row(
                                Modifier.fillMaxWidth().clickable { checked = if (srv.id in checked) checked - srv.id else checked + srv.id }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = srv.id in checked,
                                    onCheckedChange = { checked = if (it) checked + srv.id else checked - srv.id },
                                    colors = CheckboxDefaults.colors(checkedColor = Pal.Accent, checkmarkColor = Color.Black),
                                )
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(srv.name, fontSize = 16.sp, color = Pal.Text)
                                    Text(if (srv.owned) "Yours" else "Shared with you", fontSize = 12.sp, color = Pal.Muted)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (busy == null && !list.isNullOrEmpty()) TextButton(onClick = { apply(list.filter { it.id in checked }) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { onChange(null) }) { Text("Cancel") } },
    )
}
