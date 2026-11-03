package app.memoria

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import java.io.File
import java.time.LocalDateTime

class MemoriaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.start(this)
    }
}

/**
 * Logcat, and in debug builds also `memoria.log` in the app's files folder: some phones drop an app's logcat lines.
 * Read it with `adb shell cat /sdcard/Android/data/app.memoria.debug/files/memoria.log`.
 * At 1 MB it moves to `memoria.log.1`, so the last 1-2 MB stay.
 */
object AppLog {
    private const val TAG = "Memoria"
    private const val MAX_BYTES = 1_000_000L
    private var file: File? = null

    fun start(context: Context) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) file = File(context.getExternalFilesDir(null), "memoria.log")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        i("app start: $version, Android ${Build.VERSION.SDK_INT}, chip ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
    }

    fun i(msg: String) { Log.i(TAG, msg); write("I", msg, null) }
    fun w(msg: String, e: Throwable? = null) { Log.w(TAG, msg, e); write("W", msg, e) }

    @Synchronized
    private fun write(level: String, msg: String, e: Throwable?) {
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) f.renameTo(File(f.path + ".1"))
            f.appendText("${LocalDateTime.now().toString().replace('T', ' ')} $level $msg${e?.let { " | $it" }.orEmpty()}\n")
        } catch (_: Exception) {
            // a full disk or a missing folder must never stop the index
        }
    }
}
