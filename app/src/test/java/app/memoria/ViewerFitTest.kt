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
}
