package app.memoria

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The locked folder's store against the emulator's MediaStore, with a random key in place of the phone's lock.
 * Runs on an emulator like `BehaviorTest`, never on the phone.
 */
class VaultStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val added = mutableListOf<Uri>()

    @Before fun unlock() {
        VaultStore.dir(context).run { deleteRecursively(); mkdirs() } // the key's first save makes it in the app
        vaultKeys = VaultKeys(VaultKeys.random())
    }

    @After fun clean() {
        added.forEach { runCatching { resolver.delete(it, null, null) } }
        VaultStore.dir(context).deleteRecursively()
        VaultStore.shareDir(context).deleteRecursively()
        vaultKeys = null
    }

    /** A locked photo holding [bytes], locked from the gallery as the app does it. */
    private fun lockedPhoto(name: String, bytes: ByteArray): VaultItem = runBlocking {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/VaultStoreTest/")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!.also { added += it }
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        VaultStore.lock(context, listOf(Tile(ContentUris.parseId(uri), uri, 0L, null))).items.single()
    }

    // Share hands out a plain copy of each locked file (mockup "Preparing to share"): its own name, type and bytes
    @Test fun share_givesEachFileWithItsNameAndBytes() = runBlocking {
        val files = listOf("vaultstore-a-${System.nanoTime()}.jpg", "vaultstore-b-${System.nanoTime()}.jpg")
            .associateWith { n -> ByteArray(200_000) { (it * 31 + n.length).toByte() } }
        val items = files.map { (name, bytes) -> lockedPhoto(name, bytes) }

        val uris = VaultStore.share(context, items)
        val got = uris.associate { uri ->
            assertEquals("image/jpeg", resolver.getType(uri))
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { it.moveToFirst(); it.getString(0) }
            name to resolver.openInputStream(uri)!!.use { it.readBytes() }
        }
        assertEquals(files.keys, got.keys)
        files.forEach { (name, bytes) -> assertArrayEquals(name, bytes, got[name]) }
    }

    // Cancel while preparing: the copies made so far are deleted, none is left in the cache
    @Test fun share_cancelled_leavesNoCopy() = runBlocking {
        val item = lockedPhoto("vaultstore-${System.nanoTime()}.jpg", ByteArray(1 shl 20) { it.toByte() })
        var reports = 0

        val result = runCatching { VaultStore.share(context, listOf(item), progress = { _, _ -> reports++ }, cancelled = { reports > 3 }) }
        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue(VaultStore.shareDir(context).walk().none { it.isFile })
    }

    // A browser download sits in Download/, a folder the gallery's photo table refuses for a new file
    @Test fun moveOut_photoFromDownload_landsInPictures() = runBlocking {
        val name = "vaultstore-${System.nanoTime()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
        }
        val download = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!.also { added += it }
        resolver.openOutputStream(download)!!.use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val id = ContentUris.parseId(download)
        val batch = VaultStore.lock(context, listOf(Tile(id, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), 0L, null)))
        assertEquals("Download/", batch.items.single().folder)

        assertEquals(1, VaultStore.moveOut(context, batch.items))
        val back = resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns._ID} != ?", arrayOf(name, "$id"), null,
        )!!.use { c -> List(c.count) { c.moveToNext(); ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) to c.getString(1) } }
        added += back.map { it.first }
        assertEquals(listOf("Pictures/Download/"), back.map { it.second })
    }

    // A photo with no EXIF date and none in its name is dated by its file: Move out must keep that date
    @Test fun moveOut_photoWithoutExif_keepsItsDate() = runBlocking {
        val name = "vaultstore-${System.nanoTime()}.png"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/VaultStoreTest/")
        }
        val photo = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!.also { added += it }
        resolver.openOutputStream(photo)!!.use { Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val taken = 1_500_000_000_000L
        val batch = VaultStore.lock(context, listOf(Tile(ContentUris.parseId(photo), photo, taken, null)))
        resolver.delete(photo, null, null)

        assertEquals(1, VaultStore.moveOut(context, batch.items))
        val back = resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(name), null,
        )!!.use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } }
        added += back.map { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
        // The date the grid shows, by the timeline's own rules
        assertEquals(listOf(taken), photosByIds(context, back).map { it.date })
    }
}
