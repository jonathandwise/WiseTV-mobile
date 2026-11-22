package com.wisetv.mobile.ui.guide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.wisetv.mobile.AppGraph
import com.wisetv.mobile.data.Settings
import com.wisetv.mobile.data.db.ChannelEntity
import com.wisetv.mobile.data.db.GroupEntity
import com.wisetv.mobile.data.db.GroupKind
import com.wisetv.mobile.data.db.ProgramEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** State of the Live TV tab (channels of the chosen group, guide data) that survives navigating away and back. */
class GuideState(private val g: AppGraph, private val scope: CoroutineScope) {
    var playlistId by mutableLongStateOf(0L)
        private set
    var groupId by mutableStateOf(g.settings.lastGroup)
        private set
    var channels by mutableStateOf<List<ChannelEntity>>(emptyList())
        private set
    var groups by mutableStateOf<List<GroupEntity>>(emptyList())
        private set
    var epgGen by mutableLongStateOf(0L)
        private set

    var row by mutableIntStateOf(0)
    var focusTime by mutableLongStateOf(System.currentTimeMillis())
    var windowStart by mutableLongStateOf(floor30(System.currentTimeMillis()))

    val programs = mutableStateMapOf<String, List<ProgramEntity>>()
    private val loadOrder = ArrayDeque<String>()
    private val loading = HashSet<String>()
    private var job: Job? = null
    private var pendingFocusChannel: Long? = null

    val groupTitle: String
        get() = when (groupId) {
            Settings.ALL_GROUP -> "All channels"
            Settings.FAV_GROUP -> "Favorites"
            else -> groupId
        }

    fun bind(pid: Long) {
        if (pid == playlistId && job != null) return
        job?.cancel()
        playlistId = pid
        channels = emptyList()
        programs.clear()
        loading.clear()
        loadOrder.clear()
        row = 0
        if (pid <= 0) return
        job = scope.launch {
            launch {
                g.db.playlists().observe(pid).collect { pl ->
                    val gen = pl?.epgGen ?: 0L
                    if (gen != epgGen) {
                        epgGen = gen
                        programs.clear()
                        loading.clear()
                        loadOrder.clear()
                    }
                }
            }
            launch {
                g.db.groups().observeVisible(pid, GroupKind.LIVE).collect { groups = it }
            }
            launch {
                snapshotFlow { groupId }
                    .flatMapLatest { gid ->
                        when (gid) {
                            Settings.ALL_GROUP -> g.db.channels().observeVisible(pid)
                            Settings.FAV_GROUP -> g.db.channels().observeFavorites(pid)
                            else -> g.db.channels().observeGroup(pid, gid)
                        }
                    }
                    .collect { list ->
                        val selectedId = channels.getOrNull(row)?.id
                        channels = list
                        val want = pendingFocusChannel ?: selectedId
                        val idx = if (want != null) list.indexOfFirst { it.id == want } else -1
                        row = when {
                            idx >= 0 -> idx
                            row >= list.size -> (list.size - 1).coerceAtLeast(0)
                            else -> row
                        }
                        if (idx >= 0) pendingFocusChannel = null
                    }
            }
        }
    }

    fun selectGroup(id: String) {
        if (id == groupId) return
        groupId = id
        g.settings.lastGroup = id
        row = 0
    }

    /** Moves the selection to a channel (e.g. the one playing) once the list contains it. */
    fun focusChannel(id: Long) {
        val idx = channels.indexOfFirst { it.id == id }
        if (idx >= 0) row = idx else pendingFocusChannel = id
    }

    fun ensureLoaded(ch: ChannelEntity) {
        val key = ch.epgId
        if (key.isEmpty() || programs.containsKey(key) || !loading.add(key)) return
        val gen = epgGen
        val pid = playlistId
        scope.launch {
            val now = System.currentTimeMillis()
            val list = g.db.programs().forChannel(pid, gen, key, now - 3 * HOUR, now + 15 * DAY)
            if (gen == epgGen && pid == playlistId) {
                programs[key] = list
                loadOrder.addLast(key)
                while (loadOrder.size > MAX_CACHED) {
                    val old = loadOrder.removeFirst()
                    programs.remove(old)
                    loading.remove(old)
                }
            }
        }
    }

    fun programsOf(ch: ChannelEntity?): List<ProgramEntity> = ch?.let { programs[it.epgId] }.orEmpty()

    fun programAt(ch: ChannelEntity?, t: Long): ProgramEntity? = programsOf(ch).firstOrNull { it.start <= t && it.stop > t }

    fun selectedChannel(): ChannelEntity? = channels.getOrNull(row)
    fun selectedProgram(): ProgramEntity? = programAt(selectedChannel(), focusTime)

    // ------------------------------------------------------------ navigation

    fun moveRow(delta: Int) {
        if (channels.isEmpty()) return
        row = (row + delta).coerceIn(0, channels.size - 1)
        val now = System.currentTimeMillis()
        if (focusTime < now) focusTime = now
    }

    fun moveRight(windowMinutes: Int) {
        val now = System.currentTimeMillis()
        val list = programsOf(selectedChannel())
        val cur = list.firstOrNull { it.start <= focusTime && it.stop > focusTime }
        val next = if (cur != null) list.firstOrNull { it.start >= cur.stop } else list.firstOrNull { it.start > focusTime }
        val target = next?.start ?: (floor30(focusTime) + 30 * MIN)
        if (target > now + 14 * DAY) return
        focusTime = target
        ensureVisible(target, windowMinutes)
    }

    /** Returns false when the selection is already on the programme airing now (caller leaves the grid). */
    fun moveLeft(windowMinutes: Int): Boolean {
        val now = System.currentTimeMillis()
        val list = programsOf(selectedChannel())
        val cur = list.firstOrNull { it.start <= focusTime && it.stop > focusTime }
        val curStart = cur?.start ?: floor30(focusTime)
        if (curStart <= now) {
            focusTime = now
            windowStart = floor30(now)
            return false
        }
        val prev = list.lastOrNull { it.stop <= curStart }
        val target = when {
            prev != null && prev.stop > now -> maxOf(prev.start, now)
            else -> maxOf(now, curStart - 30 * MIN)
        }
        focusTime = target
        ensureVisible(target, windowMinutes)
        return true
    }

    fun pageTime(deltaMs: Long, windowMinutes: Int) {
        val now = System.currentTimeMillis()
        focusTime = (focusTime + deltaMs).coerceIn(now, now + 14 * DAY)
        windowStart = maxOf(floor30(now), floor30(focusTime) - 30 * MIN)
        ensureVisible(focusTime, windowMinutes)
    }

    /** Keeps the grid anchored to the present as time passes while the guide is left open. */
    fun tick(now: Long) {
        if (focusTime < now) focusTime = now
        if (windowStart < floor30(now)) windowStart = floor30(now)
    }

    fun resetToNow() {
        val now = System.currentTimeMillis()
        focusTime = now
        windowStart = floor30(now)
    }

    private fun ensureVisible(t: Long, windowMinutes: Int) {
        val now = System.currentTimeMillis()
        val winEnd = windowStart + windowMinutes * MIN
        if (t < windowStart) {
            windowStart = maxOf(floor30(now), floor30(t))
        } else if (t >= winEnd - 20 * MIN) {
            windowStart = maxOf(floor30(now), floor30(t) - 30 * MIN)
        }
    }

    companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
        private const val MAX_CACHED = 400
        fun floor30(t: Long): Long = t - (t % (30 * MIN))
    }
}
