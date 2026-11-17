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
    context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val path = c.getString(1) + c.getString(2)
            if (c.getLong(6) !in skipped) videos += MediaPhoto(c.getLong(0), path, c.getString(3), c.getInt(4), c.getInt(5), video = true)
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
    return listOf(Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).sumOf { uri ->
        runCatching { context.contentResolver.query(uri, arrayOf(Media._ID), selection, null, null)?.use { it.count } ?: 0 }.getOrDefault(0)
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

fun kindOf(context: Context, photo: MediaPhoto, uri: Uri): PhotoKind =
    kindOf(photo, context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it).getAttribute(ExifInterface.TAG_MAKE) } != null)
