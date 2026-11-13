package app.memoria

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `media.tsv` and the test files stay in step, so a new file never goes untested. */
class MediaTableTest {

    private val assets = File("src/androidTest/assets")
    private val lines = File(assets, "media.tsv").readLines().filter { it.isNotBlank() }
    private val rows = lines.drop(1).map { lines.first().split('\t').zip(it.split('\t')).toMap() }

    @Test fun header_isTheOneTheTestsRead() =
        assertEquals("file expect shape orientation codec sound about source".split(' '), lines.first().split('\t'))

    @Test fun everyFile_hasOneRow_andEveryRowAFile() {
        val files = listOf("fixtures", "videos").flatMap { dir -> File(assets, dir).walk().filter { it.isFile && !it.name.startsWith(".") }.map { it.relativeTo(assets).path } }.sorted()
        assertEquals(files, rows.map { it["file"]!! }.sorted())
    }

    @Test fun everyRow_hasValuesTheTestsKnow() {
        for (row in rows) {
            val video = row["file"]!!.startsWith("videos/")
            val expects = if (video) setOf("plays", "plays or card", "card") else setOf("upright", "opens")
            assertTrue("${row["file"]}: ${row.size} columns", row.size == 8)
            assertTrue("${row["file"]}: expect ${row["expect"]}", row["expect"] in expects)
            val needsShape = row["expect"] != "card" && row["expect"] != "upright"
            assertTrue("${row["file"]}: shape ${row["shape"]}", if (needsShape) row["shape"]!!.matches(Regex("\\d+:\\d+")) else row["shape"] == "-")
            if (video && row["expect"] != "card") {
                assertTrue("${row["file"]}: codec ${row["codec"]}", row["codec"] in setOf("H.264", "HEVC", "VP9", "AV1", "H.263"))
                assertTrue("${row["file"]}: sound ${row["sound"]}", row["sound"] in setOf("yes", "no"))
            }
            if (!video) assertTrue("${row["file"]}: orientation ${row["orientation"]}", row["orientation"]!!.matches(Regex("[0-9]|-")))
        }
    }
}
