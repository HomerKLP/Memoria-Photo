package app.memoria

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * The face models, downloaded once from the `models-v1` GitHub release by Android's DownloadManager, so the
 * download goes on with the app closed. A file lands as `.part` and gets its real name only once its SHA-256
 * matches, so [modelsReady] never sees a half or broken file. `adb push` into the same folder still works.
 */
object Models {
    enum class Status { Ready, Missing, Downloading, Stopped }

    private const val URL = "https://github.com/HomerKLP/Memoria-Photo/releases/download/models-v1/"
    private class Model(val name: String, val size: Long, val sha256: String)
    private val FILES = listOf(
        Model("detector_raw", 42_198_336, "2c72b06c7776541c515eaf832160e018102be228326c8b98210173a054a4cb66"),
        Model("recognizer", 130_504_752, "651c0272e24f5f074bf76506d7aa4f59f06aade74eb5b44024608fdf7c241c09"),
        Model("quality", 4_045_216, "9914a7b2054e92a6747e4c2672b1400eb8350979388a56ffee7a773d9db6cae6"),
    )
    val TOTAL = FILES.sumOf { it.size }

    private fun prefs(context: Context) = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    private fun dir(context: Context) = File(context.getExternalFilesDir(null), "models")
    private fun manager(context: Context) = context.getSystemService(DownloadManager::class.java)

    /** Download ids of the files still coming, by model name. */
    private fun ids(context: Context): Map<String, Long> =
        FILES.mapNotNull { m -> prefs(context).getLong(m.name, -1).takeIf { it >= 0 }?.let { m.name to it } }.toMap()

    /** Download and Retry: starts every file that is not here and not coming. */
    fun download(context: Context) {
        val coming = ids(context)
        val edit = prefs(context).edit().putBoolean("stopped", false)
        for (m in FILES) {
            if (m.name in coming || File(dir(context), "${m.name}.tflite").exists()) continue
            File(dir(context), "${m.name}.tflite.part").delete() // DownloadManager will not write over a file
            val request = DownloadManager.Request(Uri.parse("$URL${m.name}.tflite"))
                .setTitle("Memoria face models").setDestinationInExternalFilesDir(context, "models", "${m.name}.tflite.part")
            edit.putLong(m.name, manager(context).enqueue(request))
        }
        edit.apply()
    }

    /** Cancel: drops the files still coming, DownloadManager deletes their parts. Finished files stay. */
    fun cancel(context: Context) {
        val ids = ids(context).values
        if (ids.isNotEmpty()) manager(context).remove(*ids.toLongArray())
        prefs(context).edit().clear().putBoolean("stopped", true).apply()
    }

    /** The row's state and the bytes so far: finished files plus the parts still coming. */
    fun status(context: Context): Pair<Status, Long> {
        val here = FILES.filter { File(dir(context), "${it.name}.tflite").exists() }.sumOf { it.size }
        if (here == TOTAL) return Status.Ready to TOTAL
        val ids = ids(context)
        if (ids.isEmpty()) return (if (prefs(context).getBoolean("stopped", false)) Status.Stopped else Status.Missing) to here
        val coming = manager(context).query(DownloadManager.Query().setFilterById(*ids.values.toLongArray()))?.use { c ->
            var sum = 0L
            while (c.moveToNext()) sum += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
            sum
        } ?: 0L
        return Status.Downloading to here + coming
    }

    /**
     * Checks the finished files and gives them their real name; a failed or damaged one is dropped and the row
     * says Stopped. Hashes up to 125 MB, so off the main thread. True when this call made the models ready.
     */
    fun settle(context: Context): Boolean {
        val ids = ids(context)
        if (ids.isEmpty()) return false
        val edit = prefs(context).edit()
        for ((name, id) in ids) {
            val status = manager(context).query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
            }
            // pending, running, or paused while it waits for the network: DownloadManager goes on by itself
            if (status != DownloadManager.STATUS_SUCCESSFUL && status != DownloadManager.STATUS_FAILED && status != null) continue
            val part = File(dir(context), "$name.tflite.part")
            val good = status == DownloadManager.STATUS_SUCCESSFUL && sha256(part) == FILES.first { it.name == name }.sha256
            if (good) part.renameTo(File(dir(context), "$name.tflite")) else edit.putBoolean("stopped", true)
            manager(context).remove(id) // deletes the part if it is still there; null status: cleared in the system's Downloads
            edit.remove(name)
        }
        edit.commit()
        return modelsReady(context)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        runCatching {
            file.inputStream().use { s ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = s.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** "169": sizes in the row are MiB, like the mockup. */
    fun mb(bytes: Long) = (bytes + 524_288) / 1_048_576
}
