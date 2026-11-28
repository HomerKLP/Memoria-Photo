package app.memoria

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.min

/** The biggest face circle is 96 dp, 288 px at 3x; the round crop fills it by the picture's short side. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real scaling and WebP
class FaceThumbTest {

    private fun shortSide(box: FloatArray): Int {
        val bytes = faceThumb(Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888), box)
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        return min(b.width, b.height)
    }

    @Test fun aTallFaceFillsThe96dpCircle() {
        val s = shortSide(floatArrayOf(1119f, 418f, 2023f, 1822f))
        assertTrue("short side $s px", s >= 288)
    }

    @Test fun aWideFaceFillsThe96dpCircle() {
        val s = shortSide(floatArrayOf(1000f, 1000f, 2400f, 1900f))
        assertTrue("short side $s px", s >= 288)
    }
}
