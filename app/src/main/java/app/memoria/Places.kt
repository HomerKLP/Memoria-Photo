package app.memoria

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import kotlin.math.abs
import kotlin.math.cos

/** GeoNames towns of 1000+ people, city districts left out (decisions.md). `assets/towns.tsv` comes from `bench/places.py towns`. */
class Towns(val id: IntArray, val name: Array<String>, val lat: FloatArray, val lon: FloatArray) {
    /** Index of the nearest town. */
    // ponytail: linear scan of ~140k towns, ~1 ms a photo; a lat/lon grid if it ever shows in the index time
    fun nearest(la: Double, lo: Double): Int {
        val k = cos(Math.toRadians(la)) // flat-earth distance: fine at town distances, and only the order matters
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in id.indices) {
            val dy = lat[i] - la
            val dx = abs(lon[i] - lo).let { if (it > 180) 360 - it else it } * k
            val d = dy * dy + dx * dx
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    companion object {
        fun parse(lines: List<String>): Towns {
            val rows = lines.filter { it.isNotEmpty() }.map { it.split('\t') }
            return Towns(
                IntArray(rows.size) { rows[it][0].toInt() }, Array(rows.size) { rows[it][1] },
                FloatArray(rows.size) { rows[it][2].toFloat() }, FloatArray(rows.size) { rows[it][3].toFloat() },
            )
        }

        fun load(context: Context) = parse(context.assets.open("towns.tsv").bufferedReader().readLines())
    }
}

/** Photo GPS from the original file (MediaStore hides it since Android 10), null if it has none. Blocking. */
fun photoGps(context: Context, uri: Uri): Pair<Double, Double>? {
    val exif = runCatching { context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))!!.use { ExifInterface(it) } }.getOrNull() ?: return null
    val ll = FloatArray(2)
    if (!exif.getLatLong(ll) || ll[0] == 0f && ll[1] == 0f) return null // some cameras write 0,0 when they have no GPS
    return ll[0].toDouble() to ll[1].toDouble()
}
