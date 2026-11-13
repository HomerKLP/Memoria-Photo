package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Test

/** Dev tools' media block: always three lines, so nothing moves (mockup #devtools). */
class MediaCheckTextTest {

    private fun results(vararg results: String) = results.mapIndexed { i, r -> MediaCheckResult("f$i", r) }.toList()

    @Test fun notRunYet_isThreeLines() {
        val lines = mediaCheckText(null).lines()
        assertEquals(3, lines.size)
        assertEquals("Not run yet", line(lines, "Last"))
        assertEquals("—", line(lines, "Failed"))
        assertEquals("—", line(lines, "Report"))
    }

    @Test fun checking_countsTheFiles() =
        assertEquals("Checking 23 of 55…", line(mediaCheckText(MediaCheckState(23, 55)).lines(), "Last"))

    @Test fun result_readsTheCountsAndTheTime() {
        val report = MediaCheckReport(results("pass", "pass", "fail", "skipped"), emptyList(), 100_000)
        val text = mediaCheckText(MediaCheckState(4, 4, report))
        assertEquals(3, text.lines().size)
        assertEquals("2 of 4 fine · 1 failed · 1 skipped · 1:40", line(text.lines(), "Last"))
        assertEquals("f2", line(text.lines(), "Failed"))
        assertEquals(mediaCheckPath, line(text.lines(), "Report"))
    }

    @Test fun result_listsTwoFailedThenTheRest() {
        val report = MediaCheckReport(results("fail", "fail", "fail", "pass"), emptyList(), 61_000)
        assertEquals("f0, f1, +1", line(mediaCheckText(MediaCheckState(4, 4, report)).lines(), "Failed"))
    }

    @Test fun error_keepsThreeLines() {
        val lines = mediaCheckText(MediaCheckState(error = "No media.tsv, run make push-media")).lines()
        assertEquals(3, lines.size)
        assertEquals("No media.tsv, run make push-media", line(lines, "Last"))
    }

    private fun line(lines: List<String>, label: String) = lines.first { it.startsWith(label) }.removePrefix(label).trim()
}
