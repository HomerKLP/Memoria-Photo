package app.memoria

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motion photos lean on Android and Media3 (decisions.md, 2026-11-27): MediaStore flags them, Media3 plays
 * the clip inside the JPEG. These cases fail if either stops doing it. Files: `bench/fixtures.py`, `bench/videofix.py`.
 */
class MotionPhotoTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val added = mutableListOf<Uri>()

    // the player's callbacks write these on the main thread, the test thread reads them
    @Volatile private var tracks: Tracks? = null
    @Volatile private var error: PlaybackException? = null
    @Volatile private var ended = false

    @After fun remove() = added.forEach { context.contentResolver.delete(it, null, null) }

    /** The asset (a JPEG or an MP4) as a MediaStore file, once Android has scanned it. */
    private fun insert(asset: String, name: String = "motion_" + asset.substringAfterLast('/'), folder: String = "Pictures/MotionPhotoTest"): Long {
        val video = asset.startsWith("videos/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
        }
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, values)!!.also { added += it }
        context.contentResolver.openOutputStream(uri)!!.use { out -> instrumentation.context.assets.open(asset).use { it.copyTo(out) } }
        val until = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < until &&
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.WIDTH), null, null, null)?.use { it.moveToFirst() && it.getInt(0) > 0 } != true) Thread.sleep(100)
        return ContentUris.parseId(uri)
    }

    // Only the files URI takes the hidden column; the images URI says "Invalid column".
    private fun specialFormat(id: Long): Int =
        context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf("_special_format"), "_id = $id", null, null)!!
            .use { it.moveToFirst(); it.getInt(0) }

    @Test fun mediaStore_flagsAMotionPhoto_forTheApp() {
        assertEquals(2, specialFormat(insert("fixtures/jpg_o1_motion.jpg")))
        assertEquals(0, specialFormat(insert("fixtures/jpg_o1.jpg")))
    }

    // As on the test phone: the Takeout import put the clip in its own folder, the photo stays in the camera's.
    @Test fun clipOfAMotionPhoto_isHiddenEverywhere_otherVideosStay() {
        val s = System.nanoTime() // fresh names, so an earlier run's files never match
        val photo = insert("fixtures/jpg_o1_motion.jpg", "IMG_$s.jpg", "DCIM/MotionPhotoTest")
        val clip = insert("videos/video_motion.mp4", "IMG_$s.MP4", "Pictures/Clips$s")
        insert("fixtures/jpg_o1.jpg", "PLAIN_$s.jpg", "DCIM/MotionPhotoTest")
        val plainTwin = insert("videos/video_motion.mp4", "PLAIN_$s.mp4", "Pictures/Clips$s") // its photo holds no clip
        insert("fixtures/jpg_o1_motion.jpg", "LONG_$s.jpg", "DCIM/MotionPhotoTest")
        val long = insert("videos/video_land.mp4", "LONG_$s.mp4", "Pictures/Clips$s") // 5 s: longer than a motion clip
        val stay = setOf(plainTwin, long)

        val tiles = timeline(context).filterIsInstance<Tile>()
        val grid = tiles.map { it.id }
        assertTrue("photo in Photos", photo in grid)
        assertTrue("photo marked motion", tiles.single { it.id == photo }.motion)
        assertFalse("video not marked", tiles.single { it.id == plainTwin }.motion)
        assertFalse("clip in Photos", clip in grid)
        assertTrue("other videos in Photos", grid.containsAll(stay))

        val inAlbums = albums(context).flatMap { it.tiles }.map { it.id }
        assertFalse("clip in Albums and Videos", clip in inAlbums)
        assertTrue("other videos in Albums", inAlbums.containsAll(stay))
        assertEquals("the clip's own folder", stay, albums(context, albumOf(context, clip)!!.id).single().tiles.map { it.id }.toSet())
        assertEquals("Move to album count", 2, moveTargets(context, except = -1).single { it.path.startsWith("Pictures/Clips$s") }.count)

        val indexed = libraryVideos(context).map { it.id }
        assertFalse("clip indexed", clip in indexed)
        assertTrue("other videos indexed", indexed.containsAll(stay))
        assertEquals("Settings total", libraryPhotos(context).size + libraryVideos(context).size, libraryCount(context))
    }

    private fun pathOf(id: Long): String =
        context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), "_id = $id", null, null)!!
            .use { it.moveToFirst(); it.getString(0) }

    // A clip goes where its photo goes. The test owns both files, so Android asks nothing.
    @Test fun trashRestoreAndMove_takeTheClip() {
        val s = System.nanoTime()
        val photo = insert("fixtures/jpg_o1_motion.jpg", "IMG_$s.jpg", "DCIM/MotionPhotoTest")
        val clip = insert("videos/video_motion.mp4", "IMG_$s.MP4", "Pictures/Clips$s")
        val tile = timeline(context).filterIsInstance<Tile>().single { it.id == photo }
        val both = listOf(tile.uri, ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, clip))
        assertEquals("trash request", both, withClips(context, listOf(tile)))

        movePhoto(context, tile, "Pictures/Moved$s/")
        assertEquals("photo moved", "Pictures/Moved$s/", pathOf(photo))
        assertEquals("clip moved", "Pictures/Moved$s/", pathOf(clip))

        // what Android's trash dialog does to both
        val trashed = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }
        both.forEach { context.contentResolver.update(it, trashed, null, null) }
        val inTrash = trash(context).map { it.tile }.filter { it.id == photo || it.id == clip }
        assertEquals("Trash shows the photo only", listOf(photo), inTrash.map { it.id })
        assertEquals("restore and delete requests", both, withClips(context, inTrash, trashed = true))
    }

    /** The tracks Media3 finds in the asset, or its error. */
    private fun play(asset: String): Pair<Tracks?, PlaybackException?> {
        val file = File(context.cacheDir, asset.substringAfterLast('/'))
        instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        var player: ExoPlayer? = null
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().also { p ->
                p.addListener(object : Player.Listener {
                    override fun onTracksChanged(t: Tracks) { if (!t.isEmpty) tracks = t }
                    override fun onPlayerError(e: PlaybackException) { error = e }
                    override fun onPlaybackStateChanged(state: Int) { ended = ended || state == Player.STATE_ENDED }
                })
                p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                p.prepare()
                p.play()
            }
        }
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until && tracks == null && error == null && !ended) Thread.sleep(50)
        instrumentation.runOnMainSync { player?.release() }
        return tracks to error
    }

    @Test fun media3_findsTheClipInsideTheJpeg() {
        val (tracks, error) = play("fixtures/jpg_o1_motion.jpg")
        assertNull(error?.errorCodeName, error)
        val video = tracks!!.groups.single { it.type == C.TRACK_TYPE_VIDEO }.getTrackFormat(0)
        assertEquals("320x240", "${video.width}x${video.height}")
        assertTrue("the clip's sound", tracks.isTypeSelected(C.TRACK_TYPE_AUDIO))
    }

    // The viewer then shows a plain photo
    @Test fun media3_findsNoClip_whenTheJpegHasNone() {
        val (tracks, _) = play("fixtures/jpg_o1_motion_novideo.jpg")
        assertFalse(tracks?.isTypeSelected(C.TRACK_TYPE_VIDEO) == true)
    }
}
