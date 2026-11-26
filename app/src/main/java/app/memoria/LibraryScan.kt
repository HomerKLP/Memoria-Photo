package app.memoria

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Images.Media
import kotlin.math.min

class MediaPhoto(val id: Long, val path: String, val mime: String, val width: Int, val height: Int, val video: Boolean = false)

fun testPhotos(context: Context): List<MediaPhoto> {
    val photos = mutableListOf<MediaPhoto>()
    val cols = arrayOf(Media._ID, Media.RELATIVE_PATH, Media.DISPLAY_NAME, Media.MIME_TYPE, Media.WIDTH, Media.HEIGHT)
    context.contentResolver.query(Media.EXTERNAL_CONTENT_URI, cols, "${Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/MemoriaTest/%"), null)?.use { c ->
        while (c.moveToNext()) photos += MediaPhoto(c.getLong(0), c.getString(1) + c.getString(2), c.getString(3), c.getInt(4), c.getInt(5))
    }
    return photos
}

/**
 * Every image MediaStore knows about, like Immich's import paths: include all, minus the albums
 * the user skips (Album, More > Don't index). A run forgets the faces of photos that drop out.
 * Videos have their own pass ([libraryVideos]). kindOf sorts camera / other / skip.
 */
fun libraryPhotos(context: Context): List<MediaPhoto> {
    val skipped = IndexProgress.skipped(context)
    val photos = mutableListOf<MediaPhoto>()
    val cols = arrayOf(Media._ID, Media.RELATIVE_PATH, Media.DISPLAY_NAME, Media.MIME_TYPE, Media.WIDTH, Media.HEIGHT, Media.BUCKET_ID)
    context.contentResolver.query(Media.EXTERNAL_CONTENT_URI, cols, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val path = c.getString(1) + c.getString(2)
            if (c.getLong(6) !in skipped) photos += MediaPhoto(c.getLong(0), path, c.getString(3), c.getInt(4), c.getInt(5))
        }
    }
    return photos
}

/** Every video MediaStore knows about, minus the albums the user skips: faces and pets in it join the people and pets built from photos. */
fun libraryVideos(context: Context): List<MediaPhoto> {
    val skipped = IndexProgress.skipped(context)
    val videos = mutableListOf<MediaPhoto>()
    val cols = arrayOf(Media._ID, Media.RELATIVE_PATH, Media.DISPLAY_NAME, Media.MIME_TYPE, Media.WIDTH, Media.HEIGHT, Media.BUCKET_ID)
    val clips = motion(context).clips // their photo holds the same clip, so its faces are already in
    context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val path = c.getString(1) + c.getString(2)
            if (c.getLong(6) !in skipped && c.getLong(0) !in clips) videos += MediaPhoto(c.getLong(0), path, c.getString(3), c.getInt(4), c.getInt(5), video = true)
        }
    }
    return videos
}

/**
 * How many items [libraryPhotos] and [libraryVideos] would return together, without building the lists: row-count reads only.
 * Settings shows it as the total of a run's progress, in the same units as [IndexDao.doneCount]. An unreadable collection counts 0.
 */
fun libraryCount(context: Context): Int {
    val skipped = IndexProgress.skipped(context)
    val selection = if (skipped.isEmpty()) null else "${Media.BUCKET_ID} NOT IN (${skipped.joinToString(",")})"
    val clips = motion(context).clips.keys.takeIf { it.isNotEmpty() }?.let { "${Media._ID} NOT IN (${it.joinToString(",")})" }
    val videos = listOfNotNull(selection, clips).joinToString(" AND ").ifEmpty { null }
    return listOf(Media.EXTERNAL_CONTENT_URI to selection, MediaStore.Video.Media.EXTERNAL_CONTENT_URI to videos).sumOf { (uri, where) ->
        runCatching { context.contentResolver.query(uri, arrayOf(Media._ID), where, null, null)?.use { it.count } ?: 0 }.getOrDefault(0)
    }
}

/** Folders, mime types and tiny images that never enter people grouping. Pure part of [kindOf]. */
internal fun skipsIndex(photo: MediaPhoto): Boolean =
    "screenshot" in photo.path.lowercase() || photo.mime == "image/gif" || min(photo.width, photo.height) in 1 until 200

/** Pure part of [kindOf]: [hasCameraMake] is whether the file carries a camera EXIF make. */
internal fun kindOf(photo: MediaPhoto, hasCameraMake: Boolean): PhotoKind = when {
    skipsIndex(photo) -> PhotoKind.skip
    hasCameraMake -> PhotoKind.camera
    else -> PhotoKind.other
}

/** [kindOf] and the GPS from one EXIF read. Without ACCESS_MEDIA_LOCATION the original won't open: the plain file gives the kind, no GPS. */
fun kindAndGps(context: Context, photo: MediaPhoto, uri: Uri): Pair<PhotoKind, Pair<Double, Double>?> {
    val original = runCatching { context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))!!.use { ExifInterface(it) } }.getOrNull()
    val exif = original ?: context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it) }
    return kindOf(photo, exif.getAttribute(ExifInterface.TAG_MAKE) != null) to original?.let { photoGps(it) }
}
