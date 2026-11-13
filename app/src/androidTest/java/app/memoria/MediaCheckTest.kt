package app.memoria

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Dev tools media check itself (mockup #devtools): a made-up photo and a video from `assets/` go
 * through the same three decodes and the same player the phone sweep uses, and the report lands next to
 * them. The full table runs on the phone (Dev tools > Check media files); this proves the plumbing on
 * every CI run without the table's whole 55 files.
 */
class MediaCheckTest {

    @Test fun check_writesTheReport_forAPhotoAndAVideo() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val lines = instrumentation.context.assets.open("media.tsv").bufferedReader().readLines().filter { it.isNotBlank() }
            val keep = lines.drop(1).filter { it.substringBefore('\t') in setOf("fixtures/jpg_o6.jpg", "videos/video_land.mp4") }
            assertEquals(2, keep.size)

            val dir = File(context.cacheDir, "media_check_test").apply { deleteRecursively(); mkdirs() }
            File(dir, "fixtures").mkdirs()
            File(dir, "videos").mkdirs()
            for (line in keep) {
                val name = line.substringBefore('\t')
                instrumentation.context.assets.open(name).use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
            }
            File(dir, "media.tsv").writeText((listOf(lines.first()) + keep).joinToString("\n"))

            val report = checkMedia(context, dir)

            assertEquals("${report.files.map { "${it.file} ${it.result} ${it.reason}" }}", 0, report.failed)
            assertEquals(2, report.passed)
            assertTrue("the report is on disk", File(context.cacheDir, "media_check.json").isFile)
            assertTrue("the phone's decoders are in the report", report.decoders.isNotEmpty())
            dir.deleteRecursively()
        }
    }
}
