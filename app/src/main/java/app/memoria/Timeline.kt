package app.memoria

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min

/** A row of the Photos grid: a month or day [Header], or a [Tile]. */
sealed interface TimelineItem

/** A photo or video in the timeline. duration is set for videos only. favorite is MediaStore's own flag. ratio is width / height as shown, 0 if unknown. */
data class Tile(val id: Long, val uri: Uri, val date: Long, val duration: Long?, val favorite: Boolean = false, val ratio: Float = 0f) : TimelineItem

/** Width / height as shown: MediaStore keeps the stored size, so a 90 or 270 turn swaps it. */
fun shownRatio(width: Int, height: Int, orientation: Int): Float = when {
    width <= 0 || height <= 0 -> 0f
    orientation % 180 == 0 -> width.toFloat() / height
    else -> height.toFloat() / width
}

class Header(val key: String, val text: String, val month: Boolean) : TimelineItem

private val nameDate = Regex("(?<!\\d)((?:19|20)\\d{6})(?:[_-](\\d{6}))?(?!\\d)")
private val nameFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

/** No EXIF date: the date in the file name (IMG_20250901_003222, IMG-20250308-WA0005), else the older of added and modified. */
fun fallbackDate(name: String, added: Long, modified: Long, zone: ZoneId): Long {
    val file = min(added, modified) * 1000
    val m = nameDate.find(name) ?: return file
    return runCatching { LocalDateTime.parse(m.groupValues[1] + m.groupValues[2].ifEmpty { "000000" }, nameFormat).atZone(zone).toInstant().toEpochMilli() }
        .getOrDefault(file)
}

/**
 * All photos and videos, newest first, with month and day headers between them.
 * App folders (`Android/media`, `Android/data`: WhatsApp, Telegram) show only in Albums and Favorites.
 * favoritesOnly keeps MediaStore's own favorite flag (`QUERY_ARG_MATCH_FAVORITE`), so nothing is copied.
 */
fun timeline(context: Context, favoritesOnly: Boolean = false): List<TimelineItem> {
    val zone = ZoneId.systemDefault()
    val tiles = mutableListOf<Tile>()
    val cols = arrayOf(FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATE_TAKEN, FileColumns.DATE_ADDED, FileColumns.DURATION, FileColumns.DATE_MODIFIED, FileColumns.DISPLAY_NAME, FileColumns.IS_FAVORITE, FileColumns.WIDTH, FileColumns.HEIGHT, FileColumns.ORIENTATION)
    val where = "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})" +
        if (favoritesOnly) "" else " AND ${FileColumns.RELATIVE_PATH} NOT LIKE 'Android/media/%' AND ${FileColumns.RELATIVE_PATH} NOT LIKE 'Android/data/%'"
    val args = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
        if (favoritesOnly) putInt(MediaStore.QUERY_ARG_MATCH_FAVORITE, MediaStore.MATCH_ONLY)
    }
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, args, null)?.use { c ->
        while (c.moveToNext()) {
            val video = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val date = if (c.isNull(2)) fallbackDate(c.getString(6), c.getLong(3), c.getLong(5), zone) else c.getLong(2)
            tiles += Tile(c.getLong(0), ContentUris.withAppendedId(base, c.getLong(0)), date, if (video) c.getLong(4) else null, c.getInt(7) != 0, shownRatio(c.getInt(8), c.getInt(9), c.getInt(10)))
        }
    }
    val today = LocalDate.now()
    val monthName = DateTimeFormatter.ofPattern("LLLL yyyy")
    val dayName = DateTimeFormatter.ofPattern("EEE, d MMM")
    val out = mutableListOf<TimelineItem>()
    var month: YearMonth? = null
    var day: LocalDate? = null
    for (t in tiles.sortedByDescending { it.date }) {
        val d = Instant.ofEpochMilli(t.date).atZone(zone).toLocalDate()
        if (YearMonth.from(d) != month) {
            month = YearMonth.from(d)
            out += Header("m$month", monthName.format(d), true)
        }
        if (d != day) {
            day = d
            out += Header("d$d", if (d == today) "Today" else if (d == today.minusDays(1)) "Yesterday" else dayName.format(d), false)
        }
        out += t
    }
    return out
}

/** A trashed photo or video, and the days it has left before Android deletes it for good. */
class Trashed(val tile: Tile, val daysLeft: Int)

/** Everything in the phone's own trash, the fewest days left last. Our app never keeps its own trash. */
fun trash(context: Context): List<Trashed> {
    val cols = arrayOf(FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DURATION, FileColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_EXPIRES, FileColumns.WIDTH, FileColumns.HEIGHT, FileColumns.ORIENTATION)
    val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
    val out = mutableListOf<Trashed>()
    val now = System.currentTimeMillis()
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, args, null)?.use { c ->
        while (c.moveToNext()) {
            val video = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val id = c.getLong(0)
            // DATE_EXPIRES is in seconds, every other MediaStore date is in milliseconds
            val daysLeft = if (c.isNull(4)) 0 else ((c.getLong(4) * 1000 - now).coerceAtLeast(0) / 86_400_000L).toInt()
            out += Trashed(Tile(id, ContentUris.withAppendedId(base, id), c.getLong(3), if (video) c.getLong(2) else null, ratio = shownRatio(c.getInt(5), c.getInt(6), c.getInt(7))), daysLeft)
        }
    }
    return out.sortedByDescending { it.daysLeft }
}

/** The given MediaStore ids as tiles, photos and videos, newest first, with the same date rules as the timeline. Ids MediaStore no longer has are dropped. */
fun photosByIds(context: Context, ids: List<Long>): List<Tile> {
    if (ids.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val out = mutableListOf<Tile>()
    val cols = arrayOf(FileColumns._ID, FileColumns.DATE_TAKEN, FileColumns.DATE_ADDED, FileColumns.DURATION, FileColumns.DATE_MODIFIED, FileColumns.DISPLAY_NAME, FileColumns.IS_FAVORITE, FileColumns.WIDTH, FileColumns.HEIGHT, FileColumns.ORIENTATION, FileColumns.MEDIA_TYPE)
    // videos too: a person, pet or place grid shows the videos they are in
    val where = "${FileColumns._ID} IN (${ids.joinToString(",")}) AND ${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})"
    val args = Bundle().apply { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where) }
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, args, null)?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val collection = if (c.getInt(10) == FileColumns.MEDIA_TYPE_VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val date = if (c.isNull(1)) fallbackDate(c.getString(5), c.getLong(2), c.getLong(4), zone) else c.getLong(1)
            out += Tile(id, ContentUris.withAppendedId(collection, id), date, if (c.isNull(3)) null else c.getLong(3), c.getInt(6) != 0, shownRatio(c.getInt(7), c.getInt(8), c.getInt(9)))
        }
    }
    return out.sortedByDescending { it.date }
}
