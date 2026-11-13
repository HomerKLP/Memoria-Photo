package app.memoria

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** The video's shape decides how it fits the screen; the wrong number is what stretched a portrait clip in the viewer (2026-11-12). */
class VideoAspectTest {

    @Test
    fun aspect_isWidthOverHeight() {
        assertEquals(320f / 180f, videoAspectOf(320, 180), 0.001f)
        assertEquals(180f / 320f, videoAspectOf(180, 320), 0.001f)
    }

    @Test
    fun aspect_appliesThePixelRatio() {
        // an anamorphic file: 720x576 pixels shown as 16:9
        assertEquals(16f / 9f, videoAspectOf(720, 576, 1.422f), 0.002f)
    }

    @Test
    fun aspect_unknownSize_isZero() {
        assertEquals(0f, videoAspectOf(0, 0), 0f)
        assertEquals(0f, videoAspectOf(320, 0), 0f)
        assertEquals(0f, videoAspectOf(-1, 180), 0f)
    }

    /** The box the playing video sits in, and the poster's own fit, are the same rect: the page never resizes when the player reports. */
    @Test
    fun fitOf_isTheBoxTheVideoIsDrawnIn() {
        val tall = fitOf(9f / 16f, IntSize(360, 480))
        assertEquals(270f, tall.width, 0.01f)
        assertEquals(480f, tall.height, 0.01f)
        val wide = fitOf(16f / 9f, IntSize(360, 480))
        assertEquals(360f, wide.width, 0.01f)
        assertEquals(202.5f, wide.height, 0.01f)
    }

    @Test
    fun fitOf_nothingKnown_isZero() {
        assertEquals(0f, fitOf(0f, IntSize(360, 480)).width, 0f)
        assertEquals(0f, fitOf(16f / 9f, IntSize.Zero).width, 0f)
    }
}
