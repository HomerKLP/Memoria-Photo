package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure logic of the Photos select mode. The select state and layout are checked in the androidTest suites. */
class PhotoSelectTest {

    @Test fun formatBytes_bytes() = assertEquals("512 B", formatBytes(512))

    @Test fun formatBytes_kilobytes() = assertEquals("1.0 KB", formatBytes(1000))

    @Test fun formatBytes_megabytes() = assertEquals("18.4 MB", formatBytes(18_400_000))

    @Test fun formatBytes_gigabytes() = assertEquals("2.5 GB", formatBytes(2_500_000_000))

    @Test fun formatBytes_zero() = assertEquals("0 B", formatBytes(0))

    @Test fun bytesOf_gigabytes() = assertEquals("1.2 of 4.8 GB", bytesOf(1_200_000_000, 4_800_000_000))

    @Test fun bytesOf_underAGigabyte_readsMegabytes() = assertEquals("0.3 of 67.1 MB", bytesOf(300_000, 67_108_864))
}
