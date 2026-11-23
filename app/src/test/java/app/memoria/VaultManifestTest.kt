package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Test

/** The vault manifest's text: a name with a tab, a newline, a percent or a plus must not break its line. */
class VaultManifestTest {
    private val photo = VaultItem(
        7, "IMG\t2024 01\n100% + Холод (1).jpg", "image/jpeg", 1_700_000_000_000, 1_700_000_100_000,
        12_345_678, 4032, 3024, null, "DCIM/Camera/",
    )
    private val video = VaultItem(8, "VID_1.mp4", "video/mp4", 1, 2, 3, 1920, 1080, 42_000, "Pictures/Memoria/")

    @Test
    fun everyFieldComesBack() = assertEquals(listOf(photo, video), decodeVault(encodeVault(listOf(photo, video))))

    @Test
    fun anEmptyManifestStaysEmpty() = assertEquals(emptyList<VaultItem>(), decodeVault(encodeVault(emptyList())))

    @Test
    fun aNameWithATabKeepsItsLine() = assertEquals(1, encodeVault(listOf(photo)).lines().size)
}
