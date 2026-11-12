package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Test

/** The player's time reads like every player: m:ss, rounded to the nearest second (mockup "Video, playing"). */
class PlayerTimeTest {

    @Test
    fun clock_readsMinutesAndSeconds() {
        assertEquals("0:00", clock(0))
        assertEquals("0:05", clock(5_000))
        assertEquals("1:24", clock(84_000))
        assertEquals("3:10", clock(190_000))
        assertEquals("12:05", clock(725_000))
    }

    @Test
    fun clock_roundsToTheNearestSecond_andNeverGoesNegative() {
        assertEquals("0:01", clock(600))
        assertEquals("0:00", clock(499))
        assertEquals("0:00", clock(-2_000))
    }
}
