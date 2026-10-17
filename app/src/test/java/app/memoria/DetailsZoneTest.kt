package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

/** Viewer details timezone label. Catches the "GMT-" bug for zones west of GMT. */
class DetailsZoneTest {

    @Test fun wholeHourOffsets() {
        assertEquals("GMT+5", zoneLabel(ZoneOffset.ofHours(5)))
        assertEquals("GMT-5", zoneLabel(ZoneOffset.ofHours(-5)))
        assertEquals("GMT+0", zoneLabel(ZoneOffset.UTC))
        assertEquals("GMT-8", zoneLabel(ZoneOffset.ofHours(-8)))
    }

    @Test fun halfHourOffsets() {
        assertEquals("GMT+5:30", zoneLabel(ZoneOffset.ofHoursMinutes(5, 30)))
        assertEquals("GMT-8:30", zoneLabel(ZoneOffset.ofHoursMinutes(-8, -30)))
        assertEquals("GMT+5:45", zoneLabel(ZoneOffset.ofHoursMinutes(5, 45)))
    }
}
