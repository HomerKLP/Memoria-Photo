package app.memoria

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore.Images.Media
import androidx.lifecycle.Lifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground auto-index: when new photos land in MediaStore while the app is open, start an index
 * run on its own. The worker already skips photos it has seen, so a run only does the new ones.
 *
 * No background start (that needs the battery exemption), so the watcher lives only while the App
 * composable is up and the app is at least STARTED.
 *
 * The decision is pure and lives here, away from Android, so `AutoIndexTest` covers it with no phone.
 */
object AutoIndex {
    /** A burst of changes (a big import fires hundreds) waits this long after the last one before a run. */
    const val QUIET_MILLIS = 3000L

    /** One burst of MediaStore changes: [change] on each event, [due] once the stream has gone quiet. */
    class Burst(private val quietMillis: Long = QUIET_MILLIS) {
        private var lastChange = 0L
        private var waiting = false

        fun change(nowMillis: Long) {
            lastChange = nowMillis
            waiting = true
        }

        fun due(nowMillis: Long) = waiting && nowMillis - lastChange >= quietMillis

        /** Clears the burst after a run is started, so the same burst does not start another. */
        fun take() {
            waiting = false
        }
    }

    /**
     * Should a settled change start a run? No when the user cannot see photos, the models are not on
     * the phone, a run is going or queued, the user paused, or no photo was added or deleted (a run would do nothing).
     */
    fun shouldStart(hasPermission: Boolean, modelsReady: Boolean, indexBusy: Boolean, paused: Boolean, changedPhotos: Int): Boolean =
        hasPermission && modelsReady && !indexBusy && !paused && changedPhotos > 0
}

/**
 * Watches MediaStore for new photos and starts an index run when a change settles and the app is
 * open. Close it to stop watching (the App composable does, on dispose).
 */
fun watchNewPhotos(context: Context, lifecycle: Lifecycle): Closeable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val burst = AutoIndex.Burst()
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            burst.change(System.currentTimeMillis())
        }
    }
    context.contentResolver.registerContentObserver(Media.EXTERNAL_CONTENT_URI, true, observer)
    scope.launch {
        var wasOpen = false
        while (isActive) {
            delay(1000)
            // photos taken while the app was closed or away fire no change here, so each open counts as one
            val open = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (open && !wasOpen) burst.change(System.currentTimeMillis())
            wasOpen = open
            // wait for the burst to settle, and only act while the app is actually open
            if (!burst.due(System.currentTimeMillis()) || !open) continue
            // a going run listed its photos before this change, so keep the burst and try again when it ends
            val busy = indexBusy(context)
            if (busy) continue
            burst.take()
            val start = AutoIndex.shouldStart(
                hasPermission = canReadPhotos(context),
                modelsReady = modelsReady(context),
                indexBusy = busy,
                paused = IndexProgress.paused(context),
                changedPhotos = changedPhotos(context),
            )
            if (start) IndexControl.start(context, testOnly = false)
        }
    }
    return Closeable {
        context.contentResolver.unregisterContentObserver(observer)
        scope.cancel()
    }
}

private fun canReadPhotos(context: Context): Boolean {
    return context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
}

private fun modelsReady(context: Context) = File(File(context.getExternalFilesDir(null), "models"), "detector.onnx").exists()

/** A run is going, queued or blocked. On any doubt (WorkManager throws) treat it as busy, so we never double-start. */
private fun indexBusy(context: Context): Boolean = runCatching {
    WorkManager.getInstance(context).getWorkInfosForUniqueWork("index").get()
        .any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
}.getOrDefault(true)

/** Photos the index has not seen yet, plus indexed photos deleted from the phone (a run forgets them). */
private fun changedPhotos(context: Context): Int {
    val indexed = kotlinx.coroutines.runBlocking { Db.get(context).index().photoIds() }.toHashSet()
    val phone = libraryPhotos(context).map { it.id }.toHashSet()
    return phone.count { it !in indexed } + indexed.count { it !in phone }
}
