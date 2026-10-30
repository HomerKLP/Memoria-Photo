package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectsTest {

    /** YOLO26's output with the given rows (column i: cx, cy, w, h, 80 scores), all other rows empty. */
    private fun output(vararg rows: Pair<FloatArray, Map<Int, Float>>): FloatArray {
        val out = FloatArray(84 * 8400)
        for ((i, row) in rows.withIndex()) {
            for (k in 0 until 4) out[k * 8400 + i] = row.first[k]
            for ((cls, score) in row.second) out[(4 + cls) * 8400 + i] = score
        }
        return out
    }

    @Test fun decodesCenterBoxesToCorners() {
        val box = yoloBoxes(output(floatArrayOf(100f, 200f, 40f, 80f) to mapOf(PERSON to 0.9f))).single()
        assertEquals(PERSON, box.cls)
        assertEquals(0.9f, box.score)
        assertEquals(listOf(80f, 160f, 120f, 240f), box.box.toList())
    }

    @Test fun keepsPersonCatDogFromScore005() {
        val boxes = yoloBoxes(output(
            floatArrayOf(100f, 100f, 50f, 50f) to mapOf(PERSON to 0.05f),
            floatArrayOf(300f, 100f, 50f, 50f) to mapOf(CAT to 0.6f),
            floatArrayOf(500f, 100f, 50f, 50f) to mapOf(DOG to 0.04f), // under 0.05
            floatArrayOf(100f, 300f, 50f, 50f) to mapOf(2 to 0.9f), // a car
        ))
        assertEquals(listOf(PERSON, CAT), boxes.map { it.cls })
    }

    @Test fun dropsDuplicatesPerClassAtIou07() {
        val boxes = yoloBoxes(output(
            floatArrayOf(100f, 100f, 100f, 100f) to mapOf(PERSON to 0.5f),
            floatArrayOf(102f, 100f, 100f, 100f) to mapOf(PERSON to 0.8f), // IoU 0.96 with the first: the higher score stays
            floatArrayOf(140f, 100f, 100f, 100f) to mapOf(PERSON to 0.3f), // IoU 0.45 with the second: both stay
            floatArrayOf(100f, 100f, 100f, 100f) to mapOf(CAT to 0.7f), // same place, another class: stays
        ))
        assertEquals(listOf(PERSON to 0.8f, PERSON to 0.3f, CAT to 0.7f), boxes.map { it.cls to it.score })
    }

    @Test fun faceCountsWhenHalfOfItIsInAPersonBox() {
        val person = floatArrayOf(0f, 0f, 100f, 100f)
        assertTrue(inPerson(floatArrayOf(10f, 10f, 30f, 30f), listOf(person)))   // all inside
        assertTrue(inPerson(floatArrayOf(90f, 10f, 110f, 30f), listOf(person)))  // half inside
        assertFalse(inPerson(floatArrayOf(95f, 10f, 115f, 30f), listOf(person))) // a quarter inside
        assertFalse(inPerson(floatArrayOf(10f, 10f, 30f, 30f), emptyList()))     // no person box
        assertTrue(inPerson(floatArrayOf(95f, 10f, 115f, 30f), listOf(person, floatArrayOf(100f, 0f, 200f, 100f)))) // any box will do
    }

    @Test fun averageLinkageGroupsCloseVectorsOnly() {
        val v = { x: Float, y: Float -> floatArrayOf(x, y).let { a -> val n = kotlin.math.sqrt(x * x + y * y); FloatArray(2) { a[it] / n } } }
        val groups = averageLinkage(listOf(v(1f, 0f), v(0f, 1f), v(1f, 0.1f), v(0.1f, 1f), v(-1f, 0f)), cut = 0.6f)
        assertEquals(listOf(listOf(0, 2), listOf(1, 3), listOf(4)), groups.map { it.sorted() }.sortedBy { it.first() })
    }
}
