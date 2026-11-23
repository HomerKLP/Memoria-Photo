package app.memoria

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * App updates from the latest GitHub release (`releases/latest` skips the `models-v1` pre-release). Release builds
 * only. DownloadManager fetches the APK, an installer session puts it in; Android refuses one with another signing key.
 */
object Updates {
    enum class Status { UpToDate, Checking, Failed, New, Downloading, Stopped, Ready }

    /** What the Updates row shows: the newer [version], its APK [size], bytes [done], and when it last [checkedAt]. */
    data class State(val status: Status, val version: String = "", val size: Long = 0, val done: Long = 0, val checkedAt: Long = 0)

    private const val API = "https://api.github.com/repos/HomerKLP/Memoria-Photo/releases/latest"

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private fun dir(context: Context) = context.getExternalFilesDir("updates")
    private fun manager(context: Context) = context.getSystemService(DownloadManager::class.java)
    private fun installed(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    private fun apk(context: Context, version: String) = File(dir(context), "memoria-$version.apk")

    /** "0.10.0" is newer than "0.9.1": compared number by number, not as text. */
    fun newer(latest: String, current: String): Boolean {
        val a = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** The "Check for updates" switch, on by default. */
    fun auto(context: Context) = prefs(context).getBoolean("auto", true)

    fun setAuto(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("auto", on).apply()
        if (on) schedule(context) else WorkManager.getInstance(context).cancelUniqueWork("updates")
    }

    /** The daily check; KEEP, so calling it on every app open does not move the next run. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("updates", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Asks GitHub for the latest release and keeps its version, page and APK. A newer version is marked seen,
     * with a notification when [notify] (the daily check), without one after a tap on Check. False on failure.
     */
    suspend fun check(context: Context, notify: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(API).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = release.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.getString("name").endsWith(".apk") }
            val version = release.getString("tag_name").removePrefix("v")
            val p = prefs(context)
            val edit = p.edit()
                .putString("version", version).putString("page", release.getString("html_url"))
                .putString("url", apk.getString("browser_download_url")).putLong("size", apk.getLong("size"))
                .putLong("checkedAt", System.currentTimeMillis())
            if (version != p.getString("version", null)) edit.putBoolean("stopped", false)
            if (newer(version, installed(context)) && p.getString("notified", null) != version) {
                if (notify) notifyNew(context, version)
                edit.putString("notified", version)
            }
            edit.apply()
        }.onFailure { AppLog.w("update check failed", it) }.isSuccess
    }

    private fun notifyNew(context: Context, version: String) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("updates", "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        notifications.notify(2, Notification.Builder(context, "updates")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("New Memoria version is out")
            .setContentText("$version · Tap to update")
            .setAutoCancel(true)
            // Settings, scrolled to Updates; request code 1, so it is not the index notification's intent
            .setContentIntent(PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java).putExtra("openSettings", true).putExtra("atUpdates", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ))
            .build())
    }

    /** The row's state. A finished download is kept only if it has the full size, else the row says Stopped. */
    fun status(context: Context): State {
        val p = prefs(context)
        val checkedAt = p.getLong("checkedAt", 0)
        val version = p.getString("version", null)
        if (version == null || !newer(version, installed(context))) return State(Status.UpToDate, checkedAt = checkedAt)
        val size = p.getLong("size", 0)
        val id = p.getLong("id", -1)
        if (id >= 0) {
            val (status, done) = manager(context).query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) null
                else c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) to
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
            } ?: (null to 0L)
            // pending, running, or paused while it waits for the network: DownloadManager goes on by itself
            if (status != null && status != DownloadManager.STATUS_SUCCESSFUL && status != DownloadManager.STATUS_FAILED) {
                return State(Status.Downloading, version, size, done, checkedAt)
            }
            // forget the id; DownloadManager.remove would delete a finished APK
            val edit = p.edit().remove("id")
            if (status == DownloadManager.STATUS_SUCCESSFUL && apk(context, version).length() == size) edit.apply()
            else { manager(context).remove(id); edit.putBoolean("stopped", true).apply() }
        }
        return when {
            size > 0 && apk(context, version).length() == size -> State(Status.Ready, version, size, size, checkedAt)
            p.getBoolean("stopped", false) -> State(Status.Stopped, version, size, 0, checkedAt)
            else -> State(Status.New, version, size, 0, checkedAt)
        }
    }

    /** Update and Retry. Any older APK goes first, DownloadManager will not write over a file. */
    fun download(context: Context) {
        val p = prefs(context)
        val version = p.getString("version", null) ?: return
        dir(context)?.listFiles()?.forEach { it.delete() }
        val request = DownloadManager.Request(Uri.parse(p.getString("url", null)))
            .setTitle("Memoria $version").setDestinationInExternalFilesDir(context, "updates", "memoria-$version.apk")
        p.edit().putLong("id", manager(context).enqueue(request)).putBoolean("stopped", false).apply()
    }

    /** Cancel: DownloadManager drops the half file, the row goes back to Update. */
    fun cancel(context: Context) {
        val id = prefs(context).getLong("id", -1)
        if (id >= 0) manager(context).remove(id)
        prefs(context).edit().remove("id").apply()
    }

    /**
     * Writes the APK into an installer session. Once installs from Memoria are allowed, the app updates itself
     * with no screen; before that, [UpdateInstallReceiver] opens Android's confirm screen.
     */
    suspend fun install(context: Context) = withContext(Dispatchers.IO) {
        val version = prefs(context).getString("version", null) ?: return@withContext
        val file = apk(context, version)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = runCatching { installer.createSession(params) }
            .onFailure { AppLog.w("update install failed", it) }.getOrNull() ?: return@withContext
        runCatching {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // mutable: the installer adds its result to the intent
                val result = PendingIntent.getBroadcast(context, 0, Intent(context, UpdateInstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(result.intentSender)
            }
        }.onFailure { installer.abandonSession(id); AppLog.w("update install failed", it) }
    }

    /** The release's GitHub page: what's new. */
    fun openPage(context: Context) {
        val page = prefs(context).getString("page", null) ?: return
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page)))
    }

    /** Drops the APK once that version is installed. */
    fun tidy(context: Context) {
        val version = prefs(context).getString("version", null)
        if (version == null || !newer(version, installed(context))) dir(context)?.listFiles()?.forEach { it.delete() }
    }
}

/** The installer's answer. If it needs a tap, Android's confirm screen opens; a failure only goes to the log. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> AppLog.w("update install failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}

/** The daily check. A failure waits for tomorrow, no retries. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Updates.check(applicationContext, notify = true)
        return Result.success()
    }
}
