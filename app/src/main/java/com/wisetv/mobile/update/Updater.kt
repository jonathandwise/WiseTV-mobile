package com.wisetv.mobile.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.wisetv.mobile.AppGraph
import com.wisetv.mobile.BuildConfig
import com.wisetv.mobile.DebugProps
import com.wisetv.mobile.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

/**
 * Keeps WiseTV mobile up to date from the releases of the WiseTV-mobile repo on GitHub. Each
 * release carries the APK and a small latest.json describing it.
 *
 * Checks and downloads happen by themselves. Android never lets a sideloaded app replace itself
 * silently, so the last step is the system's "install this update?" screen, which the app offers
 * at a quiet moment (not while watching full screen).
 */
class Updater(private val ctx: Context, private val g: AppGraph) {

    data class Info(val versionCode: Int, val versionName: String, val apkUrl: String, val sha256: String?, val size: Long, val notes: String?)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val checkedAt: Long) : State
        data class Downloading(val info: Info, val percent: Int) : State
        data class Ready(val info: Info, val file: File) : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    private val mutex = Mutex()

    /** Versions whose install screen was already shown during this run of the app (don't nag). */
    private val offered = HashSet<Int>()

    private val feedUrl: String
        get() = DebugProps.get("debug.wisetv.update_url") ?: FEED

    private val dir: File get() = File(ctx.cacheDir, "updates").apply { mkdirs() }

    /** Checks for a newer version and downloads it. Safe to call often; does nothing when busy. */
    suspend fun checkAndDownload(userAsked: Boolean = false) {
        if (!userAsked && !g.settings.autoUpdate) return
        if (!mutex.tryLock()) return
        try {
            withContext(Dispatchers.IO) { doCheck(userAsked) }
        } finally {
            mutex.unlock()
        }
    }

    private fun doCheck(userAsked: Boolean) {
        val current = state.value
        if (current is State.Ready && current.file.exists()) return
        state.value = State.Checking
        val info = try {
            fetchInfo()
        } catch (e: Exception) {
            L.i("update check failed: ${e.message}")
            state.value = if (userAsked) State.Failed("Couldn't check for updates: ${e.message}") else State.Idle
            return
        }
        if (info == null || info.versionCode <= BuildConfig.VERSION_CODE) {
            L.i("update none (installed ${BuildConfig.VERSION_CODE}, latest ${info?.versionCode})")
            state.value = State.UpToDate(System.currentTimeMillis())
            cleanup(keep = null)
            return
        }
        L.i("update available ${info.versionName}")
        val file = File(dir, "WiseTV-mobile-${info.versionCode}.apk")
        try {
            if (!(file.exists() && verify(file, info))) download(info, file)
            state.value = State.Ready(info, file)
            cleanup(keep = file)
            L.i("update ready ${info.versionName}")
        } catch (e: Exception) {
            file.delete()
            L.i("update download failed: ${e.message}")
            state.value = State.Failed("Couldn't download the update: ${e.message}")
        }
    }

    private fun fetchInfo(): Info? {
        val url = feedUrl + (if ('?' in feedUrl) "&" else "?") + "t=" + System.currentTimeMillis() / 60_000
        g.http.api.newCall(g.http.request(url, null)).execute().use { r ->
            if (r.code == 404) return null // nothing published yet
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val o = JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject
            val apk = o.get("apk")?.asString ?: throw IOException("bad update info")
            return Info(
                versionCode = o.get("versionCode").asInt,
                versionName = o.get("versionName")?.asString ?: "?",
                apkUrl = URI(feedUrl).resolve(apk).toString(),
                sha256 = o.get("sha256")?.asString?.lowercase(),
                size = o.get("size")?.asLong ?: 0L,
                notes = o.get("notes")?.asString,
            )
        }
    }

    private fun download(info: Info, file: File) {
        val tmp = File(file.path + ".part")
        g.http.api.newCall(g.http.request(info.apkUrl, null)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: info.size
            var done = 0L
            var lastPct = -1
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            state.value = State.Downloading(info, pct)
                        }
                    }
                }
            }
        }
        if (!verify(tmp, info)) {
            tmp.delete()
            throw IOException("the download was damaged")
        }
        if (!tmp.renameTo(file)) throw IOException("couldn't save the download")
    }

    private fun verify(file: File, info: Info): Boolean {
        val want = info.sha256 ?: return file.length() > 0
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) } == want
    }

    private fun cleanup(keep: File?) {
        dir.listFiles()?.forEach { if (it != keep) it.delete() }
    }

    /** Whether the app should open the install screen by itself right now for [ready]. */
    fun shouldOffer(ready: State.Ready): Boolean = ready.info.versionCode !in offered

    enum class InstallResult { STARTED, NEEDS_PERMISSION, FAILED }

    fun hasInstallPermission(activity: Activity): Boolean =
        Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()

    /** Don't open the install screen by itself again for this version during this run. */
    fun snooze(ready: State.Ready) {
        offered += ready.info.versionCode
    }

    /** Opens Android's install screen for the downloaded update. */
    fun install(activity: Activity, ready: State.Ready): InstallResult {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            // Not marked as offered: once the permission is given, the next check offers it again.
            L.i("update needs install permission")
            runCatching {
                activity.startActivity(
                    Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                )
            }
            return InstallResult.NEEDS_PERMISSION
        }
        offered += ready.info.versionCode
        return try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", ready.file)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(i)
            L.i("update installer opened ${ready.info.versionName}")
            InstallResult.STARTED
        } catch (e: Exception) {
            L.i("update installer failed: ${e.message}")
            InstallResult.FAILED
        }
    }

    companion object {
        const val FEED = "https://github.com/jonathandwise/WiseTV-mobile/releases/latest/download/latest.json"
    }
}
