package com.mohamed3on.iptv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.mohamed3on.iptv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

/** A verified newer build in the cache. [askedPermission]: Settings was opened, so the row explains why. */
data class UpdateReady(val versionCode: Long, val versionName: String, val apk: File, val askedPermission: Boolean = false)

@Serializable
private class Manifest(val versionCode: Long, val versionName: String, val sha256: String)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Self-update (R15, R16). [check] fetches the version manifest and, when it is newer than [installed],
 * downloads the APK to [dir], verifies its sha256 and that it is this app's own package (KTD11), then
 * publishes [ready]. Nothing installs until [install] is called from the row. Every failure is silent:
 * no row, retried on the next check. Never logs a URL (it carries the token).
 */
class Updater(
    private val context: Context,
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val token: String,
    private val scope: CoroutineScope,
    private val installed: Long = BuildConfig.VERSION_CODE.toLong(),
    private val dir: File = context.cacheDir,
    @Suppress("DEPRECATION")
    private val packageOf: (File) -> String? = { context.packageManager.getPackageArchiveInfo(it.path, 0)?.packageName },
) {
    private val lock = Mutex()
    private val _ready = MutableStateFlow<UpdateReady?>(null)
    val ready: StateFlow<UpdateReady?> = _ready

    /** Called on launch and on resume; a check already in flight makes this a no-op. */
    suspend fun check() {
        if (!lock.tryLock()) return
        try {
            withContext(Dispatchers.IO) { runCatching { fetch() } }
        } finally {
            lock.unlock()
        }
    }

    private fun fetch() {
        val apk = File(dir, "update.apk")
        val manifest = get("version") { json.decodeFromString<Manifest>(it.string()) } ?: return
        if (manifest.versionCode <= installed) {
            apk.delete() // an update that is now installed
            return
        }
        if (_ready.value?.versionCode == manifest.versionCode) return

        val tmp = File(dir, "update.apk.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            get("apk") { body -> DigestInputStream(body.byteStream(), digest).use { input -> tmp.outputStream().use { input.copyTo(it) } } } ?: return
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(manifest.sha256, ignoreCase = true)) return
            if (packageOf(tmp) != context.packageName) return
            if (!tmp.renameTo(apk)) return
            _ready.value = UpdateReady(manifest.versionCode, manifest.versionName, apk)
        } finally {
            tmp.delete()
        }
    }

    /** Runs [read] on a 200 body; null on any other status. */
    private fun <T> get(kind: String, read: (okhttp3.ResponseBody) -> T): T? =
        client.newCall(Request.Builder().url("$baseUrl/api/app/$kind?t=$token").build()).execute().use { res ->
            if (res.isSuccessful) read(res.body) else null
        }

    /**
     * The row was picked. Without the install permission, opens this app's unknown-sources setting
     * first; otherwise hands the APK to a PackageInstaller session, whose confirm screen [InstallReceiver] opens.
     */
    fun install(from: Context) {
        val update = _ready.value ?: return
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            _ready.value = update.copy(askedPermission = true)
            from.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .apply { if (from !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
            return
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val installer = pm.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(context.packageName) }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, update.apk.length()).use { out ->
                        update.apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val callback = PendingIntent.getBroadcast(
                        context, id, Intent(context, InstallReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(callback.intentSender)
                }
            }.onFailure { Log.w(TAG, "install session failed: ${it.javaClass.simpleName}") }
        }
    }
}

/** The PackageInstaller session's result: opens the system confirm screen when it asks for one. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Log.w(TAG, "install failed: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}

private const val TAG = "Updater"
