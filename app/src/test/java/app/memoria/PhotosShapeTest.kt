package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A grid preview of another shape than the photo is decoded again. */
class PhotosShapeTest {

    @Test fun sidewaysPhotoSwapsItsSize() {
        assertEquals(4608f / 3456f, shownRatio(3456, 4608, 90), 0.001f)
        assertEquals(3456f / 4608f, shownRatio(3456, 4608, 0), 0.001f)
        assertEquals(0f, shownRatio(0, 0, 0), 0f)
    }

    @Test fun previewOfAnotherShapeIsCaught() {
        // the camera's 320x240 preview, turned to 240x320, for a 4:3 photo shown wide
        assertFalse(sameShape(240, 320, shownRatio(3456, 4608, 90)))
        assertTrue(sameShape(300, 225, shownRatio(3456, 4608, 90)))
        assertTrue(sameShape(300, 168, 16f / 9f))
        assertTrue(sameShape(240, 320, 0f))
    }
}
