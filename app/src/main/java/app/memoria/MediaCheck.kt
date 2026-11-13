package app.memoria

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone sweep of ROADMAP Phase 9 (mockup #devtools): every file in `files/media/` opens through the
 * app's own code (grid tile, viewer, index decode, player) and is compared with its `media.tsv` row, so a
 * format the emulator gets right and the phone does not shows up here. Never the user's photos.
 */

/** One file's verdict: pass, fail, or skipped because the phone has no decoder for its codec. */
class MediaCheckResult(val file: String, val result: String, val step: String? = null, val reason: String? = null)

class MediaCheckReport(val files: List<MediaCheckResult>, val decoders: List<String>, val millis: Long) {
    val passed get() = files.count { it.result == "pass" }
    val failed get() = files.count { it.result == "fail" }
    val skipped get() = files.count { it.result == "skipped" }
}

/** What Dev tools shows: [report] null while the check runs ([done] of [total]), or an [error] instead. */
class MediaCheckState(val done: Int = 0, val total: Int = 0, val report: MediaCheckReport? = null, val error: String? = null)

/** The report's place under the app's files, as the mockup writes it. */
const val mediaCheckPath = "…/files/media_check.json"

/**
 * Always three lines, so the block keeps its height (mockup: Not run yet / Checking 23 of 55… / the result).
 * Failed names stop at two; the report has them all.
 */
fun mediaCheckText(state: MediaCheckState?): String {
    val last = when {
        state?.error != null -> state.error
        state == null -> "Not run yet"
        state.report == null -> "Checking ${state.done} of ${state.total}…"
        else -> {
            val r = state.report
            val s = r.millis / 1000
            listOfNotNull(
                "${r.passed} of ${r.files.size} fine",
                r.failed.takeIf { it > 0 }?.let { "$it failed" },
                r.skipped.takeIf { it > 0 }?.let { "$it skipped" },
                "%d:%02d".format(s / 60, s % 60),
            ).joinToString(" · ")
        }
    }
    val names = state?.report?.files?.filter { it.result == "fail" }?.map { it.file.substringAfterLast('/') } ?: emptyList()
    val failed = when {
        names.isEmpty() -> "—"
        names.size <= 2 -> names.joinToString(", ")
        else -> names.take(2).joinToString(", ") + ", +${names.size - 2}"
    }
    return "Last    $last\nFailed  $failed\nReport  ${if (state?.report != null) mediaCheckPath else "—"}"
}

/** The rows of `<dir>/media.tsv`, the same table the emulator tests read (`app/src/androidTest/assets/`). */
fun readMediaTable(dir: File): List<Map<String, String>> {
    val lines = File(dir, "media.tsv").readLines().filter { it.isNotBlank() }
    val head = lines.first().split('\t')
    return lines.drop(1).map { head.zip(it.split('\t')).toMap() }
}

/**
 * Opens every row of `media.tsv` from [dir] the way the gallery does, one file at a time, and writes the
 * report next to it (`<app files>/media_check.json`). Photos: the grid tile, the viewer and the index
 * decode. Videos: the poster, a few seconds in the player and a seek. [progress] gets (file, total) per file.
 */
suspend fun checkMedia(context: Context, dir: File, progress: (Int, Int) -> Unit = { _, _ -> }): MediaCheckReport {
    val start = System.nanoTime()
    val rows = readMediaTable(dir)
    val results = mutableListOf<MediaCheckResult>()
    for ((i, row) in rows.withIndex()) {
        progress(i + 1, rows.size)
        val name = row["file"]!!
        val file = File(dir, name)
        results += try {
            when {
                !file.isFile -> MediaCheckResult(name, "fail", null, "missing, run make push-media")
                row["expect"] == "upright" || row["expect"] == "opens" -> photoCheck(context, file, row)
                else -> videoCheck(context, file, row)
            }
        } catch (e: Exception) {
            MediaCheckResult(name, "fail", null, (e.message ?: e.javaClass.simpleName).lineSequence().first())
        }
    }
    val report = MediaCheckReport(results, decoders(), (System.nanoTime() - start) / 1_000_000)
    File(dir.parentFile, "media_check.json").writeText(report.toJson())
    return report
}

/** The three decodes the app does for a photo: the grid tile, the viewer and the index (640 px). */
private fun photoCheck(context: Context, file: File, row: Map<String, String>): MediaCheckResult {
    val uri = Uri.fromFile(file)
    val tile = Tile(1, uri, 0L, null)
    val steps = listOf<Pair<String, () -> Bitmap>>(
        "tile" to { tileBitmap(context, tile) },
        "viewer" to { viewerPhoto(context, tile) },
        "index" to { loadPhoto(context.contentResolver, uri, 640) },
    )
    for ((step, load) in steps) {
        val problem = comparePhoto(row, load())
        if (problem != null) return MediaCheckResult(row["file"]!!, "fail", step, problem)
    }
    return MediaCheckResult(row["file"]!!, "pass")
}

private fun comparePhoto(row: Map<String, String>, bitmap: Bitmap): String? = when (row["expect"]) {
    "upright" -> {
        val want = uprightCorners[row["orientation"]!!.toIntOrNull()] ?: "RGBY"
        val got = cornerString(bitmap)
        if (got == want) null else "corners $got, want $want (${bitmap.width}x${bitmap.height})"
    }
    else -> {
        val want = shapeOf(row)
        val got = bitmap.width.toFloat() / bitmap.height
        if (abs(got - want) <= want * 0.05f) null else "shape %.2f, want %.2f (${bitmap.width}x${bitmap.height})".format(got, want)
    }
}

internal val videoMimes = mapOf("H.264" to "video/avc", "HEVC" to "video/hevc", "VP9" to "video/x-vnd.on2.vp9", "AV1" to "video/av01", "H.263" to "video/3gpp")

/** True when this device has any decoder for [mime]: an emulator has none for some codecs, a phone has almost all. */
internal fun hasDecoder(mime: String): Boolean =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
    }

private suspend fun videoCheck(context: Context, file: File, row: Map<String, String>): MediaCheckResult {
    val name = row["file"]!!
    val codec = row["codec"]!!
    videoMimes[codec]?.let { if (!hasDecoder(it)) return MediaCheckResult(name, "skipped", null, "no $codec decoder") }
    val tile = Tile(1, Uri.fromFile(file), 0L, 60_000L)
    // the still the viewer shows while the video opens, and after a failed start
    val poster = videoPoster(context, tile)
    if (row["expect"] != "card") {
        if (poster == null && row["expect"] == "plays") return MediaCheckResult(name, "fail", "poster", "no poster frame")
        if (poster != null) {
            val want = shapeOf(row)
            val got = poster.width.toFloat() / poster.height
            if (abs(got - want) > 0.03f) return MediaCheckResult(name, "fail", "poster", "shape %.2f, want %.2f".format(got, want))
        }
    }
    return playCheck(context, tile, row)
}

/** What the player's callbacks say; written on the main thread, read here. */
private class MediaProbe {
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var state = Player.STATE_IDLE
    @Volatile var error: PlaybackException? = null
    @Volatile var sound = false // an audio track was picked: one the phone cannot decode plays silent, no error
}

/** A real ExoPlayer on an offscreen surface (a player with no surface never decodes), played, seeked, judged like `VideoPlayTest`. */
private suspend fun playCheck(context: Context, tile: Tile, row: Map<String, String>): MediaCheckResult {
    val name = row["file"]!!
    val probe = MediaProbe()
    var surface: Surface? = null
    var texture: SurfaceTexture? = null
    val player = withContext(Dispatchers.Main) {
        val t = SurfaceTexture(0)
        texture = t
        val s = Surface(t)
        surface = s
        ExoPlayer.Builder(context).build().also { p ->
            // silent: the clips have sound, and the check reads the audio track it picks, not the speaker
            p.volume = 0f
            p.setVideoSurface(s)
            p.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) {
                    probe.width = size.width
                    probe.height = size.height
                }

                override fun onPlayerError(e: PlaybackException) {
                    probe.error = e
                }

                override fun onTracksChanged(tracks: Tracks) {
                    probe.sound = tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    probe.state = state
                }
            })
            p.setMediaItem(MediaItem.fromUri(tile.uri))
            p.prepare()
            p.play()
        }
    }
    // ready or a decoder error; a slow phone gets 15 s
    val readyBy = System.currentTimeMillis() + 15_000
    while (System.currentTimeMillis() < readyBy && probe.error == null && probe.state != Player.STATE_READY) delay(50)
    // a seek, like a thumb on the player's line
    if (probe.error == null) {
        val duration = withContext(Dispatchers.Main) { player.duration }
        if (duration != C.TIME_UNSET && duration > 0) {
            withContext(Dispatchers.Main) { player.seekTo(duration / 2) }
            val seekBy = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < seekBy && probe.error == null && probe.state != Player.STATE_READY) delay(50)
            delay(300)
        }
    }
    withContext(Dispatchers.Main) {
        player.release()
        surface?.release()
        texture?.release()
    }
    val error = probe.error
    val ready = probe.state == Player.STATE_READY
    return when (row["expect"]) {
        "card" -> when {
            error != null -> MediaCheckResult(name, "pass")
            ready -> MediaCheckResult(name, "fail", "play", "played, the row expects the card")
            else -> MediaCheckResult(name, "fail", "play", "never became ready")
        }
        // only the decoder may refuse it (the 4xxx codes), as in VideoPlayTest; a file or container error is a real bug
        "plays or card" -> if (error != null) {
            if (error.errorCode in 4000..4999) MediaCheckResult(name, "pass", "play", "decoder refused (${error.errorCodeName}), as the row allows")
            else MediaCheckResult(name, "fail", "play", error.errorCodeName)
        } else shapeAndSound(row, probe, sound = false)
        else -> if (error != null) MediaCheckResult(name, "fail", "play", error.errorCodeName) else shapeAndSound(row, probe, sound = true)
    }
}

private fun shapeAndSound(row: Map<String, String>, probe: MediaProbe, sound: Boolean): MediaCheckResult {
    val name = row["file"]!!
    if (probe.width <= 0 || probe.height <= 0) return MediaCheckResult(name, "fail", "play", "no picture")
    val want = shapeOf(row)
    val got = videoAspectOf(probe.width, probe.height)
    if (abs(got - want) > 0.01f) return MediaCheckResult(name, "fail", "play", "shape %.2f, want %.2f".format(got, want))
    if (sound && probe.sound != (row["sound"] == "yes")) {
        return MediaCheckResult(name, "fail", "play", if (probe.sound) "plays sound, the row says none" else "no sound, the row says yes")
    }
    return MediaCheckResult(name, "pass")
}

private fun shapeOf(row: Map<String, String>): Float = row["shape"]!!.split(':').let { it[0].toFloat() / it[1].toFloat() }

/** Every MIME type this phone decodes, video, image and audio: what tells a phone skip from a real bug. */
fun decoders(): List<String> =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        .flatMap { it.supportedTypes.toList() }.distinct().sorted()

fun MediaCheckReport.toJson(): String = JSONObject().apply {
    put("soc", Build.SOC_MODEL)
    put("sdk", Build.VERSION.SDK_INT)
    put("millis", millis)
    put("decoders", JSONArray(decoders))
    put("files", JSONArray(files.map { r ->
        JSONObject().put("file", r.file).put("result", r.result).also { o ->
            r.step?.let { o.put("step", it) }
            r.reason?.let { o.put("reason", it) }
        }
    }))
}.toString(2)

/** What each EXIF orientation tag shows, as the colours at top-left, top-right, bottom-left, bottom-right of a picture stored R G / B Y. From the EXIF spec, not from what Android returns. */
val uprightCorners = mapOf(1 to "RGBY", 2 to "GRYB", 3 to "YBGR", 4 to "BYRG", 5 to "RBGY", 6 to "BRYG", 7 to "YGBR", 8 to "GYRB")

private val cornerColors = mapOf('R' to intArrayOf(230, 30, 30), 'G' to intArrayOf(30, 200, 30), 'B' to intArrayOf(30, 30, 230), 'Y' to intArrayOf(240, 220, 30))

/** The four quarter colours of a fixture: "RGBY" for a picture stored as R G / B Y, whatever the file says. */
fun cornerString(bitmap: Bitmap): String {
    val b = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    return listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3).map { (x, y) ->
        val p = b.getPixel(b.width * x / 4, b.height * y / 4)
        fun d(c: IntArray) = (c[0] - (p shr 16 and 255)).let { it * it } + (c[1] - (p shr 8 and 255)).let { it * it } + (c[2] - (p and 255)).let { it * it }
        cornerColors.minBy { (_, c) -> d(c) }.key
    }.joinToString("")
}
