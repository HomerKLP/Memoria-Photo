package app.memoria

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swiping to the next video and back lost the picture (sound kept playing) because the page that left cleared the new page's surface. */
class VideoSurfaceTest {

    @Test
    fun onlyTheOwningPageMayClearTheSurface() {
        val surface = VideoSurface()
        surface.owner = 2L // the page in view attached its view last
        assertFalse("a page that just left must not clear the new page's surface", ownsTexture(surface, 1L))
        assertTrue("the page in view may clear its own surface", ownsTexture(surface, 2L))
    }

    @Test
    fun plainPage_clearsIt() {
        assertTrue(ownsTexture(null, 1L))
    }
}
