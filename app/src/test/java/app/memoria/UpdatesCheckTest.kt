package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Updates.check` read and rules without a phone: GitHub's JSON, the stored version, and a stale row. */
class UpdatesCheckTest {
    private fun json(tag: String, vararg assets: String) =
        """{"tag_name":"$tag","html_url":"https://github.com/HomerKLP/Memoria-Photo/releases/tag/$tag","assets":[${assets.joinToString(",")}]}"""

    private fun asset(name: String, size: Long = 102_760_448) =
        """{"name":"$name","browser_download_url":"https://github.com/HomerKLP/Memoria-Photo/releases/download/x/$name","size":$size}"""

    private val apk = asset("memoria-0.13.0.apk")

    @Test fun releaseDropsTheVFromTheTag() {
        assertEquals("0.13.0", Updates.release(json("v0.13.0", apk)).version)
    }

    @Test fun releaseTakesTheApkAsset() {
        val r = Updates.release(json("v0.13.0", asset("notes.txt", 12), apk))
        assertEquals("https://github.com/HomerKLP/Memoria-Photo/releases/download/x/memoria-0.13.0.apk", r.url)
        assertEquals(102_760_448L, r.size)
        assertEquals("https://github.com/HomerKLP/Memoria-Photo/releases/tag/v0.13.0", r.page)
    }

    @Test fun releaseWithNoApkFails() {
        assertTrue(runCatching { Updates.release(json("v0.13.0", asset("notes.txt", 12))) }.isFailure)
    }

    @Test fun releaseWithBrokenJsonFails() {
        assertTrue(runCatching { Updates.release("{") }.isFailure)
    }

    /** The check keeps whatever GitHub calls latest, so the version found at the check before is not kept. */
    @Test fun latestReleaseReplacesTheStoredVersion() {
        val f = Updates.found(Updates.release(json("v0.13.0", apk)), "0.12.0", stored = "0.12.1", stopped = false, notified = "0.12.1")
        assertEquals("0.13.0", f.version)
    }

    @Test fun newerVersionIsAnnouncedOnce() {
        val r = Updates.release(json("v0.13.0", apk))
        assertTrue(Updates.found(r, "0.12.0", null, false, null).announce)
        assertTrue(Updates.found(r, "0.12.0", "0.12.1", false, "0.12.1").announce)
        assertFalse(Updates.found(r, "0.12.0", "0.13.0", false, "0.13.0").announce)
    }

    @Test fun sameOrOlderIsNotAnnounced() {
        val r = Updates.release(json("v0.13.0", apk))
        assertFalse(Updates.found(r, "0.13.0", null, false, null).announce)
        assertFalse(Updates.found(r, "0.14.0", null, false, null).announce)
    }

    @Test fun aStoppedDownloadStaysOnlyOnItsVersion() {
        val r = Updates.release(json("v0.13.0", apk))
        assertTrue(Updates.found(r, "0.12.0", "0.13.0", stopped = true, notified = null).stopped)
        assertFalse(Updates.found(r, "0.12.0", "0.12.1", stopped = true, notified = null).stopped)
    }

    @Test fun aCheckOlderThanAnHourIsStale() {
        val now = 1_000_000_000L
        assertTrue(Updates.stale(0, now)) // never checked
        assertFalse(Updates.stale(now - 10 * 60_000, now))
        assertTrue(Updates.stale(now - 60 * 60_000, now))
        assertTrue(Updates.stale(now - 2 * 60 * 60_000, now))
    }
}
