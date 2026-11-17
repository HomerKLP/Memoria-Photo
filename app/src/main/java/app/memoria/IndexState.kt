package app.memoria

import android.content.Context
import android.os.BatteryManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.MutableStateFlow

/** Total and start time of the current index run, so the UI can show a progress bar and time left. */
object IndexProgress {
    private fun prefs(context: Context) = context.getSharedPreferences("index", Context.MODE_PRIVATE)
    fun start(context: Context, total: Int, base: Int) {
        prefs(context).edit().putInt("total", total).putInt("base", base).putLong("started", System.currentTimeMillis()).putBoolean("paused", false).apply()
    }
    fun total(context: Context) = prefs(context).getInt("total", 0)
    fun base(context: Context) = prefs(context).getInt("base", 0)
    fun startedAt(context: Context) = prefs(context).getLong("started", 0L)
    fun paused(context: Context) = prefs(context).getBoolean("paused", false)
    fun setPaused(context: Context, paused: Boolean) = prefs(context).edit().putBoolean("paused", paused).apply()
    fun testOnly(context: Context) = prefs(context).getBoolean("testOnly", false)
    fun setTestOnly(context: Context, testOnly: Boolean) = prefs(context).edit().putBoolean("testOnly", testOnly).apply()
    fun chargingOnly(context: Context) = prefs(context).getBoolean("chargingOnly", false)
    fun setChargingOnly(context: Context, on: Boolean) = prefs(context).edit().putBoolean("chargingOnly", on).apply()
    /** Albums (MediaStore bucket ids) left out of the index. */
    fun skipped(context: Context): Set<Long> = prefs(context).getStringSet("skipped", null).orEmpty().map { it.toLong() }.toSet()
    fun setSkipped(context: Context, bucket: Long, on: Boolean) {
        val ids = skipped(context).map { "$it" }.toSet()
        prefs(context).edit().putStringSet("skipped", if (on) ids + "$bucket" else ids - "$bucket").apply()
    }
    /** Done: clears the run's total, base and start time, keeps the paused and scope flags. */
    fun finish(context: Context) = prefs(context).edit().remove("total").remove("base").remove("started").apply()
}

/** A queued run that only waits for the charger, so the progress row says so instead of hiding. */
fun waitsForCharger(context: Context) =
    IndexProgress.chargingOnly(context) && !context.getSystemService(BatteryManager::class.java).isCharging

/** Starts, pauses and resumes the index work. Pausing cancels the worker; already-saved photos are skipped on resume. */
object IndexControl {
    private const val WORK = "index"
    fun start(context: Context, testOnly: Boolean) {
        IndexProgress.setTestOnly(context, testOnly)
        IndexProgress.setPaused(context, false)
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<IndexWorker>().setInputData(workDataOf("testOnly" to testOnly))
                // unplugging stops the worker, WorkManager starts it again on the next charge
                .setConstraints(Constraints.Builder().setRequiresCharging(IndexProgress.chargingOnly(context)).build()).build(),
        )
    }
    fun pause(context: Context) {
        IndexProgress.setPaused(context, true)
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
    fun resume(context: Context) = start(context, IndexProgress.testOnly(context))
}

/** Goes up after each savePeople and each fix in the viewer, so Library and People reload. */
val peopleSaved = MutableStateFlow(0)

/** Rough time left: elapsed per photo times the photos left. Null until at least one photo is done. */
fun etaMillis(started: Long, done: Int, total: Int): Long? {
    if (started <= 0L || done <= 0 || total <= done) return null
    return (System.currentTimeMillis() - started) / done * (total - done)
}

fun formatEta(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(1)
    return when {
        s < 60 -> "${s}s left"
        s < 3600 -> "${s / 60}m left"
        else -> "${s / 3600}h ${(s % 3600) / 60}m left"
    }
}
