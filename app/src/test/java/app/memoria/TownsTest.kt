package app.memoria

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Nearest town for a photo's GPS. */
class TownsTest {
    private val towns = Towns.parse(listOf("1\tWest\t0.0\t179.9", "2\tEast\t0.0\t-179.0", "3\tNorth\t60.0\t10.0", "4\tNorthFar\t60.0\t10.3", ""))

    @Test fun picksNearest() {
        assertEquals("North", towns.name[towns.nearest(60.0, 10.1)])
        assertEquals("NorthFar", towns.name[towns.nearest(60.0, 10.2)])
    }

    @Test fun wrapsAroundTheDateLine() {
        assertEquals("East", towns.name[towns.nearest(0.0, -179.5)])
        assertEquals("West", towns.name[towns.nearest(0.0, -179.9)]) // 0.2° away across the line, 0.9° to East
    }

    @Test fun realFileFindsVersailles() {
        val real = Towns.parse(File("src/main/assets/towns.tsv").readLines())
        assertEquals("Versailles", real.name[real.nearest(48.8049, 2.1204)]) // the palace
    }
}
