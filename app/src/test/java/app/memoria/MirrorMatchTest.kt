package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `matchFaces` gives a new face the id of the old face at the same place, also mirrored as the old decode saw it. */
class MirrorMatchTest {

    private val w = 1000
    private val h = 800

    @Test fun aTopBottomMirror_matchesItsOwnBox() {
        val old = listOf(7L to floatArrayOf(100f, 600f, 300f, 750f))
        val new = listOf(floatArrayOf(100f, 50f, 300f, 200f)) // 800 - 750 and 800 - 600
        assertEquals(mapOf(0 to 7L), matchFaces(old, new, 0, w, h))
    }

    @Test fun aLeftRightMirror_matchesItsOwnBox() {
        val old = listOf(7L to floatArrayOf(700f, 100f, 900f, 300f))
        val new = listOf(floatArrayOf(100f, 100f, 300f, 300f)) // 1000 - 900 and 1000 - 700
        assertEquals(mapOf(0 to 7L), matchFaces(old, new, 1, w, h))
    }

    @Test fun anUprightOldBox_matchesToo() {
        val old = listOf(7L to floatArrayOf(100f, 100f, 300f, 300f))
        assertEquals(mapOf(0 to 7L), matchFaces(old, listOf(floatArrayOf(100f, 100f, 300f, 300f)), 1, w, h))
    }

    @Test fun twoFaces_eachMatchTheirOwn() {
        val old = listOf(7L to floatArrayOf(100f, 600f, 300f, 750f), 8L to floatArrayOf(600f, 600f, 800f, 750f))
        val new = listOf(floatArrayOf(600f, 50f, 800f, 200f), floatArrayOf(100f, 50f, 300f, 200f))
        assertEquals(mapOf(0 to 8L, 1 to 7L), matchFaces(old, new, 0, w, h))
    }

    @Test fun aFarBox_staysUnmatched() {
        val old = listOf(7L to floatArrayOf(600f, 600f, 800f, 750f))
        val new = listOf(floatArrayOf(100f, 50f, 300f, 200f))
        assertTrue(matchFaces(old, new, 0, w, h).isEmpty())
    }

    @Test fun oneOldFace_neverMatchesTwo() {
        val old = listOf(7L to floatArrayOf(100f, 100f, 300f, 300f))
        val new = listOf(floatArrayOf(100f, 100f, 300f, 300f), floatArrayOf(105f, 105f, 305f, 305f))
        val match = matchFaces(old, new, -1, w, h)
        assertEquals(1, match.size)
        assertEquals(setOf(7L), match.values.toSet())
    }
}
