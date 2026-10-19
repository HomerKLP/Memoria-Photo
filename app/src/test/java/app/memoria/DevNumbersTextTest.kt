package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Test

class DevNumbersTextTest {

    @Test fun loading_isFourLines() {
        assertEquals(4, devNumbersText(null).lines().size)
    }

    @Test fun loaded_isFourLines_withTheCounts() {
        val lines = devNumbersText(DevNumbers(1402, 180, 124, 2870, 685, 9, 33, 2, 1480, 226)).lines()

        assertEquals(4, lines.size)
        assertEquals("Photos  camera 1402 · other 180 · skip 124", lines[0])
        assertEquals("Faces   sure 2870 · unsure 685", lines[1])
        assertEquals("People  named 9 · unnamed 33 · hidden 2", lines[2])
        assertEquals("Places  with town 1480 · without 226", lines[3])
    }
}
