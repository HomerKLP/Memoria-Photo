package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Test

/** Viewer fit: the photo's real on-screen size with ContentScale.Fit, so pan stops at the edges. */
class ViewerFitTest {

    private fun assertSize(w: Float, h: Float, size: androidx.compose.ui.geometry.Size) {
        assertEquals("width", w, size.width, 0.01f)
        assertEquals("height", h, size.height, 0.01f)
    }

    @Test fun tallPhotoFitsByHeight() = assertSize(500f, 1000f, fitSize(1000, 2000, 1000, 1000))

    @Test fun widePhotoFitsByWidth() = assertSize(1000f, 500f, fitSize(2000, 1000, 1000, 1000))

    @Test fun squarePhotoFillsTheShortestSide() = assertSize(800f, 800f, fitSize(400, 400, 1200, 800))

    @Test fun photoSmallerThanTheBoxIsScaledUpToFit() = assertSize(2000f, 1000f, fitSize(200, 100, 2000, 2000))

    @Test fun emptyBoxIsZero() = assertSize(0f, 0f, fitSize(1000, 2000, 0, 1000))

    @Test fun zeroSizeImageIsZero() = assertSize(0f, 0f, fitSize(0, 2000, 1000, 1000))

    // viewerSample on a 1080 px wide screen
    @Test fun twelveMegapixelPhotoIsHalvedOnce() = assertEquals(2, viewerSample(4080, 3060, 1080))

    @Test fun fiftyMegapixelPhotoIsHalvedTwice() = assertEquals(4, viewerSample(8160, 6120, 1080))

    @Test fun screenshotKeepsFullSize() = assertEquals(1, viewerSample(1080, 2376, 1080))

    @Test fun panoramaIsHalvedToFitTheGpuLimit() = assertEquals(2, viewerSample(8000, 1500, 1080))

    // photoMotion: a 2:1 photo on a 1000x2000 screen, its tile a 200 px square at (100, 300)
    private val fit = androidx.compose.ui.geometry.Size(1000f, 500f)
    private val screen = androidx.compose.ui.geometry.Size(1000f, 2000f)
    private val tile = androidx.compose.ui.geometry.Rect(100f, 300f, 300f, 500f)

    @Test fun inTheTilePhotoCoversItCropped() =
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 300f, 400f, 500f) to tile, photoMotion(fit, screen, tile, tile, 0f, 0f))

    @Test fun halfHiddenTileClipsToItsVisiblePart() {
        val visible = androidx.compose.ui.geometry.Rect(100f, 300f, 300f, 400f)
        assertEquals(visible, photoMotion(fit, screen, tile, visible, 0f, 0f).second)
    }

    @Test fun fullScreenPhotoIsCenteredAndNotClipped() =
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 750f, 1000f, 1250f) to null, photoMotion(fit, screen, tile, tile, 1f, 0f))

    @Test fun dragDownHalfTheScreenShrinksTo70Percent() =
        assertEquals(androidx.compose.ui.geometry.Rect(150f, 1825f, 850f, 2175f) to null, photoMotion(fit, screen, null, null, 1f, 1000f))

    // zoomToFocusPan: a 1000x500 photo on a 1000x2000 screen (it fits by width, so it cannot pan up/down)
    private val box = androidx.compose.ui.geometry.Size(1000f, 2000f)

    private fun assertPan(x: Float, y: Float, pan: androidx.compose.ui.geometry.Offset) {
        assertEquals("x", x, pan.x, 0.01f)
        assertEquals("y", y, pan.y, 0.01f)
    }

    @Test fun doubleTapAtCenterKeepsPanZero() =
        assertPan(0f, 0f, zoomToFocusPan(fit, box, 2.5f, androidx.compose.ui.geometry.Offset(500f, 1000f)))

    @Test fun doubleTapRightZoomsTowardTheTap() =
        assertPan(-450f, 0f, zoomToFocusPan(fit, box, 2.5f, androidx.compose.ui.geometry.Offset(800f, 1000f)))

    @Test fun doubleTapNearTheEdgeClampsToThePanRoom() =
        assertPan(-750f, 0f, zoomToFocusPan(fit, box, 2.5f, androidx.compose.ui.geometry.Offset(1000f, 1000f)))

    @Test fun doubleTapOutResetsPanToCenter() =
        assertPan(0f, 0f, zoomToFocusPan(fit, box, 1f, androidx.compose.ui.geometry.Offset(800f, 1000f)))

    @Test fun tappedPointStaysUnderTheFinger() {
        val focus = androidx.compose.ui.geometry.Offset(800f, 1000f)
        val z = 2.5f
        val pan = zoomToFocusPan(fit, box, z, focus)
        // where the content point that was under the tap lands: center + d*z + pan
        val d = focus - androidx.compose.ui.geometry.Offset(box.width / 2f, box.height / 2f)
        val landed = androidx.compose.ui.geometry.Offset(box.width / 2f, box.height / 2f) + d * z + pan
        assertEquals(focus.x, landed.x, 0.01f)
        assertEquals(focus.y, landed.y, 0.01f)
    }
}
