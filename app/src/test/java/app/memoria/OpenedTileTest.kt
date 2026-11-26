package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** A file opened from another app: is it a library file, and its own date when it is not. */
class OpenedTileTest {

    private val zone = ZoneId.of("Asia/Tashkent") // +05:00, no summer time

    @Test fun exifDate_withOffset_usesIt() {
        assertEquals(Instant.parse("2025-09-01T00:32:22Z").toEpochMilli(), exifDate("2025:09:01 03:32:22", "+03:00", zone))
    }

    @Test fun exifDate_withoutOffset_isThePhonesZone() {
        assertEquals(Instant.parse("2025-09-01T00:32:22Z").toEpochMilli(), exifDate("2025:09:01 05:32:22", null, zone))
    }

    @Test fun exifDate_noneOrBroken_isZero() {
        assertEquals(0L, exifDate(null, null, zone))
        assertEquals(0L, exifDate("    :  :     :  :  ", null, zone))
        // a broken offset falls back to the zone, it does not drop the date
        assertEquals(Instant.parse("2025-09-01T00:32:22Z").toEpochMilli(), exifDate("2025:09:01 05:32:22", "bad", zone))
    }

    @Test fun videoDate_isUtc() {
        assertEquals(Instant.parse("2025-09-01T00:32:22Z").toEpochMilli(), videoDate("20250901T003222.000Z"))
    }

    @Test fun videoDate_noneOr1904_isZero() {
        assertEquals(0L, videoDate(null))
        assertEquals(0L, videoDate("19040101T000000.000Z"))
        assertEquals(0L, videoDate("garbage"))
    }

    @Test fun libraryPath_takesMediaStoreItems_notPickerOnes() {
        assertTrue(libraryPath.matches("/external/images/media/123"))
        assertTrue(libraryPath.matches("/external_primary/video/media/9"))
        assertTrue(libraryPath.matches("/external/file/77"))
        // the photo picker's number is its own, not a MediaStore id
        assertFalse(libraryPath.matches("/picker/0/com.android.providers.media.photopicker/media/1000"))
        assertFalse(libraryPath.matches("/external/images/media"))
    }
}
