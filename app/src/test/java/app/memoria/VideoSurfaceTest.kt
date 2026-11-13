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

    /** Seen on the phone 2026-11-13: the first photo opened instantly, no fly-in, because every page waited for the player's ready flag. */
    @Test
    fun aPhotoPageIsCurrentWithoutThePlayer() {
        assertTrue(pageIsCurrent(page = 1, currentPage = 1, atRest = true, isVideo = false, ready = false))
        assertTrue(pageIsCurrent(page = 2, currentPage = 2, atRest = true, isVideo = false, ready = true))
    }

    @Test
    fun aVideoPageWaitsForItsOwnFile() {
        assertFalse(pageIsCurrent(page = 1, currentPage = 1, atRest = true, isVideo = true, ready = false))
        assertTrue(pageIsCurrent(page = 1, currentPage = 1, atRest = true, isVideo = true, ready = true))
        // a neighbour page or a moving pager never owns the motion
        assertFalse(pageIsCurrent(page = 0, currentPage = 1, atRest = true, isVideo = true, ready = true))
        assertFalse(pageIsCurrent(page = 1, currentPage = 1, atRest = false, isVideo = false, ready = true))
    }
}
