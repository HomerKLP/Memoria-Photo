package app.memoria

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The month slider: the tick position and the month under the finger must use the same math. */
class MonthSliderTest {

    @Test fun firstAndLastMonthSitInsideThePadding() {
        assertEquals(10f, trackY(months = 2, index = 0, height = 100.dp, pad = 10.dp).value, 0.01f)
        assertEquals(90f, trackY(months = 2, index = 1, height = 100.dp, pad = 10.dp).value, 0.01f)
    }

    @Test fun oneMonthSitsInTheMiddle() {
        assertEquals(50f, trackY(months = 1, index = 0, height = 100.dp, pad = 10.dp).value, 0.01f)
    }

    @Test fun pressIsRoundedToTheNearestMonth() {
        // track runs 10..90 px, 3 months
        assertEquals(0, monthAt(10f, height = 100, pad = 10f, months = 3))
        assertEquals(0, monthAt(0f, height = 100, pad = 10f, months = 3))   // clamped above the rail
        assertEquals(1, monthAt(50f, height = 100, pad = 10f, months = 3))
        assertEquals(2, monthAt(90f, height = 100, pad = 10f, months = 3))
        assertEquals(2, monthAt(120f, height = 100, pad = 10f, months = 3)) // clamped below the rail
    }

    @Test fun oneMonthAlwaysGivesZero() {
        assertEquals(0, monthAt(0f, height = 100, pad = 10f, months = 1))
        assertEquals(0, monthAt(100f, height = 100, pad = 10f, months = 0))
    }
}
