package app.memoria

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search builds its rows from the Library snapshot: it keeps no copy of its own, so a rename, a new person or
 * a new photo count is what Search shows the next time, and an old snapshot is never mixed in.
 */
class SearchDataTest {

    private fun face(id: Long, personId: Long) =
        FaceVec(id, photoId = id, box = floatArrayOf(0f, 0f, 50f, 50f), vector = FloatArray(512).also { it[0] = 1f }, quality = 0.9f, kind = PhotoKind.camera, personId = personId)

    private fun person(id: Long, name: String?, faceIds: List<Long>) =
        Person(faceIds.map { face(it, id) }, face(faceIds.first(), id), id, name)

    private fun snapshot(people: List<Person>) = LibraryData(people, emptyList(), emptyList(), 0, 0, 0)

    @Test fun aRenameShowsTheSnapshotsName() {
        val before = searchData(snapshot(listOf(person(1, "Dana", listOf(10, 11)))))
        assertEquals(listOf("Dana"), before.hits.map { it.name })

        val after = searchData(snapshot(listOf(person(1, "Dana K", listOf(10, 11)))))
        assertEquals(listOf("Dana K"), after.hits.map { it.name })
        assertTrue(before.hits.none { it.name == "Dana K" })
    }

    @Test fun aNewPersonShowsUpWithTheNextSnapshot() {
        val one = searchData(snapshot(listOf(person(1, "Dana", listOf(10)))))
        val two = searchData(snapshot(listOf(person(1, "Dana", listOf(10)), person(2, "Andy", listOf(20)))))

        assertEquals(listOf("Dana"), one.people.map { it.name })
        assertEquals(setOf("Dana", "Andy"), two.people.map { it.name }.toSet())
    }

    @Test fun aPersonsNewPhotosChangeTheCount() {
        val one = searchData(snapshot(listOf(person(1, "Dana", listOf(10)))))
        val three = searchData(snapshot(listOf(person(1, "Dana", listOf(10, 11, 12)))))

        assertEquals("1 photo", one.hits.single().note)
        assertEquals("3 photos", three.hits.single().note)
    }

    /** A person with no name was never in Search, and a hidden one is dropped by [libraryData] before this. */
    @Test fun anUnnamedPersonIsNotAHit() {
        val data = searchData(snapshot(listOf(person(1, null, listOf(10)))))
        assertTrue(data.people.isEmpty())
        assertTrue(data.hits.isEmpty())
    }
}

/** The year and month hits of the Photos tab: newest first, a year before its own months. */
class SearchDateHitsTest {

    private val utc = ZoneId.of("UTC")
    private fun at(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).atStartOfDay(utc).toInstant().toEpochMilli()

    @Test fun yearsAndMonthsNewestFirst() {
        val hits = dateHits(listOf(at(2024, 12, 1), at(2025, 3, 15), at(2025, 3, 20), at(2025, 1, 5)), utc)

        assertEquals(
            listOf(
                HitKind.year to "m2025-03", HitKind.month to "m2025-03", HitKind.month to "m2025-01",
                HitKind.year to "m2024-12", HitKind.month to "m2024-12",
            ),
            hits.map { it.kind to it.key },
        )
        assertEquals("3 items", hits[0].note) // the whole year
        assertEquals("2 items", hits[1].note)
        assertEquals("1 item", hits[3].note)
    }

    /** A month key is the Photos tab's header key ("m2025-03") and a year carries its newest month's, so both open the tab there. */
    @Test fun aMonthKeyIsTheHeaderKey() {
        val hits = dateHits(listOf(at(2025, 1, 5), at(2025, 3, 15)), utc)
        assertEquals("m2025-03", hits[0].key)
        assertEquals("m2025-03", hits[1].key)
        assertEquals("m2025-01", hits[2].key)
    }
}
