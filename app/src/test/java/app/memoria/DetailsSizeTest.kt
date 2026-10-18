package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Test

/** Details sheet file size: B under a kilobyte, whole KB under a megabyte, one decimal above. */
class DetailsSizeTest {

    @Test fun bytes() = assertEquals("512 B", sizeLabel(512))

    @Test fun justUnderAKilobyteStaysBytes() = assertEquals("999 B", sizeLabel(999))

    @Test fun kilobytesAreWhole() = assertEquals("1 KB", sizeLabel(1_000))

    @Test fun kilobytesRound() = assertEquals("12 KB", sizeLabel(12_400))

    @Test fun justUnderAMegabyteIsKilobytes() = assertEquals("1000 KB", sizeLabel(999_999))

    @Test fun megabytesHaveOneDecimal() = assertEquals("1.0 MB", sizeLabel(1_000_000))

    @Test fun megabytesRoundToOneDecimal() = assertEquals("3.5 MB", sizeLabel(3_500_000))

    @Test fun zero() = assertEquals("0 B", sizeLabel(0))
}
