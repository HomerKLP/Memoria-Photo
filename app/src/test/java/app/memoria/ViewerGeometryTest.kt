package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The details panel's height on a portrait and a sideways screen. Sideways the photo keeps the whole
 * screen and the panel is a sheet over it, so the upright square's formula cannot apply (2026-11-18).
 */
class ViewerGeometryTest {

    private val overlap = 84f // 28 dp at 3x

    /** Upright, unchanged: the panel covers what the photo square leaves above it. */
    @Test
    fun upright_keepsItsOldHeight() {
        assertEquals(2400f - 1080f + overlap, viewerUp(1080, 2400, overlap), 0.01f)
    }

    /** Sideways, the panel is 44% of the height, as in the mockup (160 of 360). */
    @Test
    fun sideways_isASheetOfTheHeight() {
        assertEquals(1080f * landscapeSheetHeight, viewerUp(2400, 1080, overlap), 0.01f)
    }

    /** A screen with no room upright gives the overlap, never a negative height. */
    @Test
    fun squareUpright_isOnlyTheOverlap() {
        assertEquals(overlap, viewerUp(1200, 1200, overlap), 0.01f)
    }

    /** Before the first layout the size is zero: no crash and no negative height. */
    @Test
    fun nothingMeasured_isSafe() {
        assertEquals(overlap, viewerUp(0, 0, overlap), 0.01f)
    }
}
