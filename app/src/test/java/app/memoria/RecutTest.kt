package app.memoria

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.min

// TODO(0.16.0): delete with recutNarrowThumbs
/** [recutNarrowThumbs]: the one-time recut of face pictures saved with a 288 px long side. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real scaling and WebP
class RecutTest {
    private lateinit var db: Db
    private lateinit var dao: IndexDao
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("recutTest", Context.MODE_PRIVATE)
    private val loaded = mutableListOf<Long>()
    private val narrow = webp(224, 288)

    private fun webp(w: Int, h: Int) =
        ByteArrayOutputStream().also { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }.toByteArray()

    private fun face(id: Long, photo: Long, thumb: ByteArray, pet: Boolean = false) = FaceRow(
        id = id, photoId = photo, box = floatArrayOf(1119f, 418f, 2023f, 1822f), score = 0.9f, landmarks = FloatArray(0),
        vector = FloatArray(0), quality = 0.9f, thumb = thumb, personId = 7, pet = pet,
    )

    private fun recut() = runBlocking {
        recutNarrowThumbs(dao, prefs, { false }) { id -> loaded += id; Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888) }
    }

    private fun shortSide(id: Long) = runBlocking { dao.thumb(id)!!.let { BitmapFactory.decodeByteArray(it, 0, it.size) }.let { min(it.width, it.height) } }

    @Before fun open() = runBlocking {
        prefs.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, Db::class.java).build()
        dao = db.index()
        dao.insert(PhotoRow(1, "DCIM/Camera/a.jpg", PhotoKind.camera), listOf(face(1, 1, narrow), face(2, 1, narrow, pet = true)))
        dao.insert(PhotoRow(2, "DCIM/Camera/b.mp4", PhotoKind.video), listOf(face(3, 2, narrow)))
        dao.insert(PhotoRow(3, "DCIM/Camera/c.jpg", PhotoKind.camera), listOf(face(4, 3, webp(288, 360))))
    }

    @After fun close() = db.close()

    @Test fun recutsOnlyNarrowFacesFromPhotos() = runBlocking {
        recut()
        assertEquals(listOf(1L), loaded) // photo 3's picture is already 288 px wide; video faces are not cut from a photo
        assertEquals(288, shortSide(1))
        assertEquals(7L, dao.faceVecs().first { it.id == 1L }.personId)
        assertArrayEquals(narrow, dao.thumb(2)) // pets keep their pictures
        assertArrayEquals(narrow, dao.thumb(3))
    }

    @Test fun runsOnce() {
        recut()
        recut()
        assertEquals(listOf(1L), loaded)
    }

    @Test fun aStoppedRunResumesAfterTheLastPhoto() = runBlocking {
        dao.insert(PhotoRow(4, "DCIM/Camera/d.jpg", PhotoKind.camera), listOf(face(5, 4, narrow)))
        var calls = 0
        recutNarrowThumbs(dao, prefs, { calls++ > 0 }) { id -> loaded += id; Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888) }
        recut()
        assertEquals(listOf(1L, 4L), loaded)
    }
}
