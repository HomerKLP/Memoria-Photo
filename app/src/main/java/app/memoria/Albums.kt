package app.memoria

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A device folder (MediaStore bucket) with its photos and videos, newest first. */
class Album(val id: Long, val name: String, val tiles: List<Tile>)

/** Device folders, biggest first. [bucket] keeps only that folder. Same date rules as the timeline. */
fun albums(context: Context, bucket: Long? = null): List<Album> {
    val zone = ZoneId.systemDefault()
    val rows = mutableListOf<Triple<Long, String, Tile>>()
    val cols = arrayOf(
        FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATE_TAKEN, FileColumns.DATE_ADDED, FileColumns.DURATION, FileColumns.DATE_MODIFIED,
        FileColumns.DISPLAY_NAME, FileColumns.IS_FAVORITE, FileColumns.BUCKET_ID, FileColumns.BUCKET_DISPLAY_NAME,
    )
    var where = "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})"
    if (bucket != null) where += " AND ${FileColumns.BUCKET_ID} = $bucket"
    val args = Bundle().apply { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where) }
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, args, null)?.use { c ->
        while (c.moveToNext()) {
            val video = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val date = if (c.isNull(2)) fallbackDate(c.getString(6), c.getLong(3), c.getLong(5), zone) else c.getLong(2)
            val tile = Tile(c.getLong(0), ContentUris.withAppendedId(base, c.getLong(0)), date, if (video) c.getLong(4) else null, c.getInt(7) != 0)
            rows += Triple(c.getLong(8), c.getString(9) ?: "Other", tile)
        }
    }
    return rows.groupBy { it.first }
        .map { (id, r) -> Album(id, r[0].second, r.map { it.third }.sortedByDescending { it.date }) }
        .sortedByDescending { it.tiles.size }
}

/** The device folder (bucket) a photo or video sits in, with its display name, for the viewer's More. */
class TileAlbum(val id: Long, val name: String)

fun albumOf(context: Context, id: Long): TileAlbum? {
    val cols = arrayOf(FileColumns.BUCKET_ID, FileColumns.BUCKET_DISPLAY_NAME)
    val args = Bundle().apply { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${FileColumns._ID} = $id") }
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, args, null)?.use { c ->
        if (c.moveToFirst()) return TileAlbum(c.getLong(0), c.getString(1) ?: "Other")
    }
    return null
}

/** An album a photo can be moved to: its name, its MediaStore RELATIVE_PATH, a cover and a count. */
class MoveTarget(val name: String, val path: String, val cover: Tile?, val count: Int)

/**
 * The folders "Move to album" can offer: only DCIM and Pictures, where Android lets a move land, biggest
 * first, without [except] (the photo's own album, since moving there would do nothing). The path is the
 * folder's RELATIVE_PATH, which the move writes back.
 */
fun moveTargets(context: Context, except: Long): List<MoveTarget> {
    val zone = ZoneId.systemDefault()
    val cols = arrayOf(
        FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATE_TAKEN, FileColumns.DATE_ADDED, FileColumns.DURATION, FileColumns.DATE_MODIFIED,
        FileColumns.IS_FAVORITE, FileColumns.BUCKET_ID, FileColumns.BUCKET_DISPLAY_NAME, FileColumns.RELATIVE_PATH,
    )
    val where = "${FileColumns.MEDIA_TYPE} IN (${FileColumns.MEDIA_TYPE_IMAGE}, ${FileColumns.MEDIA_TYPE_VIDEO})" +
        " AND ${FileColumns.BUCKET_ID} != $except" +
        " AND (${FileColumns.RELATIVE_PATH} LIKE 'DCIM/%' OR ${FileColumns.RELATIVE_PATH} LIKE 'Pictures/%')"
    val args = Bundle().apply { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where) }
    val rows = mutableListOf<Triple<Long, Pair<String, String>, Tile>>()
    context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), cols, args, null)?.use { c ->
        while (c.moveToNext()) {
            val video = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val date = if (c.isNull(2)) fallbackDate(c.getString(9), c.getLong(3), c.getLong(5), zone) else c.getLong(2)
            val tile = Tile(c.getLong(0), ContentUris.withAppendedId(base, c.getLong(0)), date, if (video) c.getLong(4) else null, c.getInt(6) != 0)
            rows += Triple(c.getLong(7), (c.getString(8) ?: "Other") to (c.getString(9) ?: ""), tile)
        }
    }
    return rows.groupBy { it.first }
        .map { (_, r) -> MoveTarget(r[0].second.first, r[0].second.second, r.maxByOrNull { it.third.date }?.third, r.size) }
        .sortedByDescending { it.count }
}

/** Moves a photo or video into the folder at [path] (a MediaStore RELATIVE_PATH). Throws SecurityException when the file belongs to another app, so the caller asks Android for consent. */
fun movePhoto(context: Context, tile: Tile, path: String) {
    val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, path) }
    context.contentResolver.update(tile.uri, values, null, null)
}

/** All device folders as covers. Layout per docs/design.html. Split from the load so previews can pass fake data. */
@Composable
fun AlbumsContent(albums: List<Album>?, onBack: () -> Unit = {}, onOpenAlbum: (Long) -> Unit = {}) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TopBar("Albums", albums?.let { a -> "${a.size} albums · ${a.sumOf { it.tiles.size }} items" }, onBack = onBack)
        SectionHead("On this device", hint = "Device folders")
        LazyVerticalGrid(
            GridCells.Fixed(3), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(albums.orEmpty(), key = { it.id }) { CoverTile(it.tiles.first(), it.name, "${it.tiles.size}", Modifier.clickable { onOpenAlbum(it.id) }) }
        }
    }
}

@Composable
fun AlbumsScreen(onBack: () -> Unit, onOpenAlbum: (Long) -> Unit) {
    val context = LocalContext.current
    val albums by cachedProduceState("albums") { withContext(Dispatchers.IO) { albums(context) } }
    AlbumsContent(albums, onBack, onOpenAlbum)
}

/** One device folder: 3-column grid, tap opens the viewer. Split from the load for previews. */
@Composable
fun AlbumContent(album: Album?, onBack: () -> Unit = {}, onOpen: (List<Tile>, Tile) -> Unit = { _, _ -> }) {
    val a = album
    val videos = a?.tiles?.count { it.duration != null } ?: 0
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        // " " while loading: an empty title may measure to nothing and the grid would jump
        TopBar(a?.name ?: " ", a?.let { "${it.tiles.size - videos} photos" + if (videos > 0) " · $videos videos" else "" }, onBack = onBack)
        LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(a?.tiles.orEmpty(), key = { it.id }) { TileImage(it, Modifier.clickable { onOpen(a!!.tiles, it) }) }
        }
    }
}

@Composable
fun AlbumScreen(id: Long, onBack: () -> Unit, onOpen: (List<Tile>, Tile) -> Unit) {
    val context = LocalContext.current
    val album by cachedProduceState("album$id") { withContext(Dispatchers.IO) { albums(context, id) } }
    AlbumContent(album?.firstOrNull(), onBack, onOpen)
}

/** Every video, newest first, grouped by month name, and their total size in bytes. */
class Videos(val months: Map<String, List<Tile>>, val all: List<Tile>, val bytes: Long)

/** All videos by month, the month pinned on top. Layout per docs/design.html. Split from the load for previews. */
@Composable
fun VideosContent(videos: Videos?, onBack: () -> Unit = {}, onOpen: (List<Tile>, Tile) -> Unit = { _, _ -> }) {
    val v = videos
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        val size = v?.bytes?.let { if (it >= 1e9) "%.1f GB".format(it / 1e9) else "%.0f MB".format(it / 1e6) }
        TopBar("Videos", v?.let { "${it.all.size} videos · $size" }, onBack = onBack)
        LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            v?.months?.forEach { (month, tiles) ->
                stickyHeader(key = month) {
                    // background: the pinned month covers the tiles passing under it
                    Text(
                        month, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, top = 20.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                items(tiles, key = { it.id }) { TileImage(it, Modifier.clickable { onOpen(v.all, it) }) }
            }
        }
    }
}

@Composable
fun VideosScreen(onBack: () -> Unit, onOpen: (List<Tile>, Tile) -> Unit) {
    val context = LocalContext.current
    val videos by cachedProduceState("videos") {
        withContext(Dispatchers.IO) {
            val all = albums(context).flatMap { it.tiles }.filter { it.duration != null }.sortedByDescending { it.date }
            var bytes = 0L
            context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media.SIZE), null, null, null)?.use { c ->
                while (c.moveToNext()) bytes += c.getLong(0)
            }
            val month = DateTimeFormatter.ofPattern("LLLL yyyy")
            val zone = ZoneId.systemDefault()
            Videos(all.groupBy { month.format(Instant.ofEpochMilli(it.date).atZone(zone)) }, all, bytes)
        }
    }
    VideosContent(videos, onBack, onOpen)
}
