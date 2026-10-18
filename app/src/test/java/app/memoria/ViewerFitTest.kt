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
}
