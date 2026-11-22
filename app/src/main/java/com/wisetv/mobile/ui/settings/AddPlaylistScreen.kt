package com.wisetv.mobile.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wisetv.mobile.L
import com.wisetv.mobile.R
import com.wisetv.mobile.data.Presets
import com.wisetv.mobile.data.db.GroupKind
import com.wisetv.mobile.data.db.PlaylistType
import com.wisetv.mobile.data.xtream.XtreamApi
import com.wisetv.mobile.ui.AppState
import com.wisetv.mobile.ui.BusyDialog
import com.wisetv.mobile.ui.Pal
import com.wisetv.mobile.ui.Pill
import com.wisetv.mobile.ui.Screen
import com.wisetv.mobile.ui.Section
import com.wisetv.mobile.ui.Spinner
import com.wisetv.mobile.ui.TopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FREE = "free"

/** Add (or edit) a playlist: Xtream Codes login, an M3U link, or one of the free channel lists. */
@Composable
fun AddPlaylistScreen(app: AppState, editId: Long?, firstRun: Boolean) {
    val g = app.g
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(PlaylistType.XTREAM) }
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var m3u by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var ua by remember { mutableStateOf("") }
    var shift by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val editing = editId != null

    LaunchedEffect(editId) {
        val p = editId?.let { g.db.playlists().get(it) } ?: return@LaunchedEffect
        type = p.type
        name = p.name
        if (p.type == PlaylistType.XTREAM) {
            server = p.url
            user = p.username
            pass = p.password
        } else {
            m3u = p.url
        }
        epg = p.epgUrl
        ua = p.userAgent
        shift = p.epgShiftMinutes
    }

    fun submit() {
        error = null
        if (type == PlaylistType.XTREAM && (server.isBlank() || user.isBlank() || pass.isBlank())) {
            error = "Enter the server address, username and password."
            return
        }
        if (type == PlaylistType.M3U && m3u.isBlank()) {
            error = "Enter the M3U link."
            return
        }
        scope.launch {
            var createdId: Long? = null
            try {
                if (editId != null) {
                    val old = g.db.playlists().get(editId) ?: return@launch
                    if (type == PlaylistType.XTREAM) {
                        busy = "Checking your login…"
                        val api = XtreamApi(g.http, server, user, pass, ua)
                        withContext(Dispatchers.IO) { api.authenticate() }
                    }
                    g.db.playlists().update(
                        old.copy(
                            name = name.ifBlank { old.name },
                            url = if (type == PlaylistType.XTREAM) XtreamApi.normalizeServer(server) else m3u.trim(),
                            username = user.trim(),
                            password = pass.trim(),
                            epgUrl = epg.trim(),
                            userAgent = ua.trim(),
                            epgShiftMinutes = shift,
                        ),
                    )
                    busy = "Updating channels…"
                    g.sync.syncPlaylist(editId, withEpg = true) { busy = it }
                    app.showToast("Playlist saved")
                    app.nav.pop()
                    return@launch
                }
                busy = if (type == PlaylistType.XTREAM) "Checking your login…" else "Adding playlist…"
                val id = if (type == PlaylistType.XTREAM) {
                    g.repo.addXtream(name, server, user, pass, epg, ua).also {
                        g.repo.addedWhileInUse?.let { max -> app.showToast(XtreamApi.inUseMessage(max)) }
                    }
                } else {
                    g.repo.addM3u(name, m3u, epg, ua)
                }
                createdId = id
                busy = "Loading channels, movies and series…"
                g.sync.syncPlaylist(id, withEpg = false) { busy = it }
                app.setActivePlaylist(id)
                app.nav.resetTo(Screen.ManageGroups(id, GroupKind.LIVE, firstRun = true))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                error = e.message ?: "Something went wrong."
                // A playlist whose first load failed is removed so it can be re-added cleanly.
                createdId?.let { id ->
                    if (g.db.channels().visibleOnce(id).isEmpty()) g.repo.deletePlaylist(id)
                }
            } finally {
                busy = null
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Pal.Bg).statusBarsPadding().imePadding()) {
        if (!firstRun) TopBar(if (editing) "Edit playlist" else "Add a playlist", onBack = { if (!app.nav.pop()) app.nav.resetTo(Screen.Main(Section.LIVE)) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (firstRun) {
                Spacer(Modifier.height(16.dp))
                Image(painterResource(R.drawable.logo_lockup_light), contentDescription = "WiseTV", modifier = Modifier.height(56.dp))
                Text(
                    "Welcome! Add your IPTV playlist to get started, or try some free channels.",
                    fontSize = 15.sp,
                    color = Pal.Muted,
                    modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
                )
            }
            if (!editing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    Pill("Xtream Codes", type == PlaylistType.XTREAM, { type = PlaylistType.XTREAM; error = null })
                    Pill("M3U link", type == PlaylistType.M3U, { type = PlaylistType.M3U; error = null })
                    Pill("Free channels", type == FREE, { type = FREE; error = null })
                }
            }
            if (type == FREE) {
                FreePlaylists(app, firstRun)
                return@Column
            }
            Field("Playlist name (optional)", name, { name = it })
            if (type == PlaylistType.XTREAM) {
                Field("Server address", server, { server = it }, hint = "e.g. http://example.com:8080", keyboard = KeyboardType.Uri)
                Field("Username", user, { user = it })
                Field("Password", pass, { pass = it }, secret = true)
            } else {
                Field("M3U link", m3u, { m3u = it }, hint = "http://…", keyboard = KeyboardType.Uri)
            }
            Field(
                "Extra TV guide (XMLTV) link (optional)",
                epg,
                { epg = it },
                hint = if (type == PlaylistType.XTREAM) "Your provider's guide is used automatically" else "Used if the playlist doesn't include one",
                keyboard = KeyboardType.Uri,
            )
            if (editing) {
                Field("User agent (optional)", ua, { ua = it }, hint = "Only if your provider requires one")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Text("Guide time shift", fontSize = 15.sp, color = Pal.Text, modifier = Modifier.weight(1f))
                    Pill("−", false, { shift = (shift - 60).coerceAtLeast(-12 * 60) })
                    Text("${if (shift >= 0) "+" else ""}${shift / 60} h", fontSize = 15.sp, color = Pal.Accent, modifier = Modifier.padding(horizontal = 12.dp))
                    Pill("+", false, { shift = (shift + 60).coerceAtMost(12 * 60) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { submit() },
                colors = ButtonDefaults.buttonColors(containerColor = Pal.Accent, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text(if (editing) "Save" else "Add playlist", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            error?.let { Text(it, fontSize = 14.sp, color = Pal.Warn, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(24.dp))
        }
    }

    busy?.let { BusyDialog(it, "Large playlists can take a minute.") }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    hint: String? = null,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = if (hint != null) ({ Text(hint, color = Pal.Muted) }) else null,
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Pal.Accent, unfocusedBorderColor = Pal.Line, cursorColor = Pal.Accent, focusedLabelColor = Pal.Accent),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** One-tap free channel playlists (the same list as WiseTV for Windows). */
@Composable
private fun FreePlaylists(app: AppState, firstRun: Boolean) {
    val g = app.g
    val scope = rememberCoroutineScope()
    val playlists by g.db.playlists().observeAll().collectAsState(initial = emptyList())
    val have = playlists.filter { it.type == PlaylistType.M3U }.map { it.url }.toSet()
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val errors = remember { mutableStateMapOf<String, String>() }

    fun add(p: Presets.Preset) {
        busy[p.id] = true
        errors.remove(p.id)
        scope.launch {
            try {
                val (id, existed) = g.repo.addPreset(p, g.sync)
                L.i("preset added ${p.id} existed=$existed")
                if (!existed) app.showToast("${p.name} added. Its TV guide loads in the background.")
                if (firstRun || app.playlistId <= 0) app.setActivePlaylist(id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                errors[p.id] = e.message ?: "Couldn't add ${p.name}"
            } finally {
                busy[p.id] = false
            }
        }
    }

    Text(
        "Free, ad-supported channels. Add as many as you like; switch between playlists from the Groups menu in Live TV.",
        fontSize = 13.sp,
        color = Pal.Muted,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    for (p in Presets.all) {
        val added = p.url in have
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !added && busy[p.id] != true) { add(p) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.name, fontSize = 16.sp, color = Pal.Text)
                Text(p.note, fontSize = 12.sp, color = Pal.Muted)
                errors[p.id]?.let { Text(it, fontSize = 12.sp, color = Pal.Warn) }
            }
            Spacer(Modifier.width(12.dp))
            when {
                added -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Pal.Accent, modifier = Modifier.size(18.dp))
                    Text(" Added", fontSize = 13.sp, color = Pal.Accent)
                }
                busy[p.id] == true -> Spinner(size = 22.dp)
                else -> Row(
                    Modifier.background(Pal.Panel2, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, tint = Pal.Text, modifier = Modifier.size(16.dp))
                    Text(" Add", fontSize = 13.sp, color = Pal.Text)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    val anyBusy = busy.values.any { it }
    Button(
        onClick = { app.nav.resetTo(Screen.Main(Section.LIVE)) },
        enabled = !anyBusy && playlists.isNotEmpty(),
        colors = ButtonDefaults.buttonColors(containerColor = Pal.Accent, contentColor = Color.Black),
        modifier = Modifier.fillMaxWidth().height(50.dp),
    ) { Text(if (anyBusy) "Adding…" else "Done: go to Live TV", fontWeight = FontWeight.SemiBold) }
    Spacer(Modifier.height(24.dp))
}
