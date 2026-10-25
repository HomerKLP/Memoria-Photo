package app.memoria

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Release tag against the installed versionName: number by number, so 0.10.0 beats 0.9.1. */
class UpdatesNewerTest {

    @Test fun patchIsNewer() = assertTrue(Updates.newer("0.2.1", "0.2.0"))

    @Test fun sameIsNotNewer() = assertFalse(Updates.newer("0.2.0", "0.2.0"))

    @Test fun olderIsNotNewer() = assertFalse(Updates.newer("0.1.0", "0.2.0"))

    @Test fun twoDigitsBeatOne() = assertTrue(Updates.newer("0.10.0", "0.9.1"))

    @Test fun missingPartIsZero() = assertFalse(Updates.newer("0.2", "0.2.0"))

    @Test fun extraPartIsNewer() = assertTrue(Updates.newer("0.2.0.1", "0.2.0"))
}
