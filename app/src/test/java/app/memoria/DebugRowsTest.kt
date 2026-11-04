package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Test

/** The five rows of Details' debug section. */
class DebugRowsTest {
    private val info = PhotoInfo("a.jpg", "DCIM/Camera", 0, 0, 0, null, null, null, "image/jpeg", 6)
    private fun face(id: Long, quality: Float, person: Long?, pet: Boolean = false) =
        FaceVec(id, 1, FloatArray(4), FloatArray(0), quality, PhotoKind.camera, person, pet = pet)

    @Test fun notIndexed_fileRowOnly() = assertEquals(
        listOf("image/jpeg · orientation 6" to "MediaStore id 48213") + List(4) { "Not indexed" to "–" },
        debugRows(48213, info, null, emptyList(), null, null, emptyMap(), emptyList()),
    )

    @Test fun indexed_everyRowFilled() = assertEquals(
        listOf(
            "image/jpeg · orientation 6" to "MediaStore id 48213",
            "2 faces · quality 0.92, 0.81" to "People #3, #7",
            "Tag vector: yes" to "Sea 0.40 · Beach 0.31 · Sky 0.22",
            "1 person · 1 cat" to "Box scores 0.88, 0.74",
            "Indexed · camera photo" to "Objects model v3",
        ),
        debugRows(
            48213, info, PhotoRow(1, "DCIM/Camera/a.jpg", PhotoKind.camera, objects = 3),
            listOf(face(1, 0.92f, 3), face(2, 0.81f, 7), face(3, 0.5f, 9, pet = true)),
            3072, "beach 0.31;sky 0.22;sea 0.40;dull 0.10", mapOf("beach" to "Beach", "sky" to "Sky", "sea" to "Sea"),
            listOf(ObjectBoxRow(photoId = 1, cls = CAT, score = 0.74f, box = FloatArray(4)), ObjectBoxRow(photoId = 1, cls = PERSON, score = 0.88f, box = FloatArray(4))),
        ),
    )

    @Test fun indexedButNothingFound_saysSo() = assertEquals(
        listOf(
            "– · orientation –" to "MediaStore id 48213",
            "No faces" to "No person yet",
            "Tag vector: empty" to "The photo could not be read",
            "No boxes" to "–",
            "Indexed · skip photo" to "Objects model v3",
        ),
        debugRows(48213, null, PhotoRow(1, "a", PhotoKind.skip, objects = 3), emptyList(), 0, null, emptyMap(), emptyList()),
    )

    @Test fun notBoxedNoVector() = debugRows(48213, info, PhotoRow(1, "a", PhotoKind.camera), emptyList(), null, null, emptyMap(), emptyList()).let {
        assertEquals("Tag vector: none yet" to "–", it[2])
        assertEquals("Not boxed yet" to "–", it[3])
        assertEquals("Indexed · camera photo" to "Objects not run", it[4])
    }
}
