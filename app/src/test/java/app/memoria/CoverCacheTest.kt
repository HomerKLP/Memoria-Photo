package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cover change writes through the in-memory screen cache (the Library tab is off composition while a
 * person is open, so it would otherwise show the old cover on its first frame back).
 */
class CoverCacheTest {

    private fun face(id: Long) = FaceVec(id, photoId = id, box = floatArrayOf(0f, 0f, 50f, 50f), vector = FloatArray(512).also { it[0] = 1f }, quality = 0.9f, kind = PhotoKind.camera, personId = 1)

    private fun person(id: Long, faceIds: List<Long>) = Person(faceIds.map { face(it) }, face(faceIds.first()), id, "P$id")

    @Test fun libraryData_withCover_changesOnlyThatPerson() {
        val dana = person(1, listOf(10, 11))
        val other = person(2, listOf(20, 21))
        val data = LibraryData(listOf(dana, other), emptyList(), emptyList(), 0, 0, 0)

        val updated = data.withCover(1, 11)

        assertEquals(11L, updated.faces[0].cover.id)
        assertEquals(20L, updated.faces[1].cover.id) // untouched
        assertSame(other, updated.faces[1])
        assertEquals(data.faces.size, updated.faces.size)
    }

    @Test fun peopleData_withCover_changesOnlyThatPerson() {
        val data = PeopleData("2 people", listOf(person(1, listOf(10, 11)), person(2, listOf(20))), emptyList())

        val updated = data.withCover(1, 11)

        assertEquals(11L, updated.people[0].cover.id)
        assertEquals(20L, updated.people[1].cover.id)
    }

    @Test fun withCover_isNoOpForAMissingPerson() {
        val data = PeopleData("1 person", listOf(person(1, listOf(10))), emptyList())
        assertEquals(data.people, data.withCover(99, 10).people)
    }

    @Test fun updateInCache_replacesTheTypedValue() {
        val map = mutableMapOf<String, Any?>("k" to 41)
        updateInCache(map, "k", Int::class.javaObjectType) { it + 1 }
        assertEquals(42, map["k"])
    }

    @Test fun updateInCache_doesNothingForAMissingKey() {
        val map = mutableMapOf<String, Any?>()
        updateInCache(map, "k", Int::class.javaObjectType) { it + 1 }
        assertNull(map["k"])
    }

    @Test fun updateInCache_doesNothingForAnotherType() {
        val map = mutableMapOf<String, Any?>("k" to "text")
        updateInCache(map, "k", Int::class.javaObjectType) { it + 1 }
        assertEquals("text", map["k"])
        assertTrue(map["k"] is String)
    }
}
