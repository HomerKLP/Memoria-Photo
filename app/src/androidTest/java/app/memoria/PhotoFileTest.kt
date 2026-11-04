package app.memoria

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.IOException
import kotlin.math.min

/**
 * Bugs that depend on the photo file (bugs.md "Mirrored HEIC", ROADMAP Phase 9): every fixture in
 * `assets/fixtures/` is put in MediaStore and shown through the app's real paths, the grid tile, the
 * viewer decode and the index decode. A made file is a picture with red, green, blue and yellow corners
 * (top-left, top-right, bottom-left, bottom-right), so the corner colours tell how it was turned.
 * Made by `bench/fixtures.py`, the real ones come from `bench/fixtures.tsv`. A new file is a new case.
 * Runs on an emulator like `BehaviorTest`, never on the phone.
 */
@RunWith(Parameterized::class)
class PhotoFileTest(private val name: String) {

    companion object {
        // Real photos (no known corners): the shape the photo must come out with.
        private val real = mapOf("real/dng_canon60d.dng" to 960f / 540f)

        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun files(): List<String> =
            InstrumentationRegistry.getInstrumentation().context.assets.list("fixtures").orEmpty().filter { "." in it }.sorted() + real.keys
    }

    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private var added: Tile? = null

    // From the EXIF spec, not from what Android returns: what the viewer must show for each orientation
    // (corners as top-left top-right bottom-left bottom-right) of a picture stored as R G / B Y.
    private val upright = mapOf(1 to "RGBY", 2 to "GRYB", 3 to "YBGR", 4 to "BYRG", 5 to "RBGY", 6 to "BRYG", 7 to "YGBR", 8 to "GYRB")
    private val colors = mapOf('R' to intArrayOf(230, 30, 30), 'G' to intArrayOf(30, 200, 30), 'B' to intArrayOf(30, 30, 230), 'Y' to intArrayOf(240, 220, 30))

    // junk tags (0, 9), no tag, and PNG / WebP show as stored
    private val expected: String? = if (name in real) null else upright[Regex("_o(\\d)").find(name)?.groupValues?.get(1)?.toInt()] ?: "RGBY"

    @After fun removeTestPhoto() {
        added?.let { resolver.delete(it.uri, null, null) }
    }

    /** The fixture as the app sees any photo: a MediaStore row, with the tile built by the app's own query. */
    private fun tile(): Tile {
        val bytes = try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/$name").use { it.readBytes() }
        } catch (e: IOException) {
            fail("$name is missing from assets/fixtures: run `uv run bench/fixtures.py`")
            throw e
        }
        val ext = name.substringAfterLast('.')
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "photofile_" + name.substringAfterLast('/'))
            put(MediaStore.MediaColumns.MIME_TYPE, mapOf("jpg" to "image/jpeg", "dng" to "image/x-adobe-dng")[ext] ?: "image/$ext")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PhotoFileTest")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        // MediaStore reads the size and orientation a moment after the file is written
        val until = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < until &&
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.WIDTH), null, null, null)?.use { it.moveToFirst() && it.getInt(0) > 0 } != true) Thread.sleep(100)
        return photosByIds(context, listOf(ContentUris.parseId(uri))).single().also { added = it }
    }

    /** The picture's colour at the middle of each quarter: top-left, top-right, bottom-left, bottom-right. */
    private fun pixels(bitmap: Bitmap): List<Int> {
        val b = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        return listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3).map { (x, y) -> b.getPixel(b.width * x / 4, b.height * y / 4) }
    }

    private fun corners(bitmap: Bitmap): String = pixels(bitmap).map { p ->
        colors.minBy { (_, c) -> (c[0] - (p shr 16 and 255)).let { it * it } + (c[1] - (p shr 8 and 255)).let { it * it } + (c[2] - (p and 255)).let { it * it } }.key
    }.joinToString("")

    private fun assertUpright(where: String, bitmap: Bitmap) {
        val shape = real[name]
        if (shape == null) assertEquals("$where of $name (${bitmap.width}x${bitmap.height}, pixels ${pixels(bitmap).joinToString(" ") { "%06x".format(it and 0xffffff) }})", expected, corners(bitmap))
        else assertEquals("$where of $name (${bitmap.width}x${bitmap.height}), width / height", shape, bitmap.width.toFloat() / bitmap.height, shape * 0.05f)
    }

    /** The picture the grid tile draws: `Thumb` keeps it in the `thumbs` cache once it is loaded. */
    private fun gridBitmap(tile: Tile): Bitmap {
        thumbs.remove(tile.id)
        rule.setContent { Thumb(tile, Modifier.size(120.dp)) }
        runCatching { rule.waitUntil(10_000) { thumbs.get(tile.id) != null } }.onFailure { fail("the grid tile of $name never loaded") }
        return thumbs.get(tile.id)!!
    }

    @Test fun grid_showsThePhotoTurned() = assertUpright("grid tile", gridBitmap(tile()))

    @Test fun viewer_showsThePhotoTurned() = assertUpright("viewer", viewerPhoto(context, tile()))

    @Test fun index_seesThePhotoTurned() {
        val t = tile()
        assertUpright("index 640 px decode", loadPhoto(resolver, t.uri, 640))
        assertUpright("index full decode", loadPhoto(resolver, t.uri))
    }

    // The tile is 1/3 of the screen minus the 1 dp gaps; a picture stretched more than 1.2x looks soft.
    // The phone's soft HEIC thumbs do not show on the API 37 emulator (bugs.md), so HEIC passes here.
    @Test fun grid_isSharp() {
        val bitmap = gridBitmap(tile())
        val tilePx = (context.resources.displayMetrics.let { it.widthPixels - 2 * it.density } / 3).toInt()
        assertTrue("grid tile of $name is ${bitmap.width}x${bitmap.height}, a $tilePx px tile needs at least ${tilePx / 1.2f} on the short side", min(bitmap.width, bitmap.height) >= tilePx / 1.2f)
    }
}
