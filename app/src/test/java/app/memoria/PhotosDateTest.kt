package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Timeline date: EXIF date, else the date in the file name, else the older of added and modified. */
class PhotosDateTest {

    private val utc = ZoneId.of("UTC")

    @Test fun dateFromNameWithTime() {
        val got = fallbackDate("IMG_20250901_003222.jpg", added = 999L, modified = 888L, zone = utc)
        assertEquals(Instant.parse("2025-09-01T00:32:22Z").toEpochMilli(), got)
    }

    @Test fun dateFromNameWithoutTime() {
        // WA0005 style: date only, time defaults to midnight
        val got = fallbackDate("IMG-20250308-WA0005.jpg", added = 999L, modified = 888L, zone = utc)
        assertEquals(Instant.parse("2025-03-08T00:00:00Z").toEpochMilli(), got)
    }

    @Test fun noDateInNameUsesOlderOfAddedAndModified() {
        // added and modified are in seconds; the result is in milliseconds
        assertEquals(500_000L, fallbackDate("photo.jpg", added = 1000L, modified = 500L, zone = utc))
    }

    @Test fun impossibleDateInNameFallsBackToFileTime() {
        // month 13 does not parse, so the file time wins
        assertEquals(1_000_000L, fallbackDate("IMG_20251301_000000.jpg", added = 2000L, modified = 1000L, zone = utc))
    }

    @Test fun tooShortNumberIsNotADate() {
        assertEquals(7_000L, fallbackDate("1234567.jpg", added = 8L, modified = 7L, zone = utc))
    }
}
