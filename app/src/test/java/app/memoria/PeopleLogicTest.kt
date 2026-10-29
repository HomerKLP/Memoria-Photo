package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleLogicTest {

    /** Same face vector every time, so cosine distance is 0 and everything would merge. */
    private fun face(id: Long, photoId: Long, quality: Float = 0.9f, kind: PhotoKind = PhotoKind.camera) =
        FaceVec(id, photoId, floatArrayOf(0f, 0f, 50f, 50f), FloatArray(512).also { it[0] = 1f }, quality, kind, null)

    private fun at(id: Long, quality: Float, vararg x: Float) =
        FaceVec(id, id, floatArrayOf(0f, 0f, 50f, 50f), FloatArray(512).also { v -> x.forEachIndexed { i, f -> v[i] = f } }, quality, PhotoKind.camera, null)

    // Six starting faces on axis 0. Y (axis 1) is far from them. C is 0.4 from Y. D is 0.4 from C but 0.64 from Y.
    private val seeds = (1L..6L).map { face(it, it) }
    private val y = at(7, 0.35f, 0f, 1f)
    private val c = at(8, 0.35f, 0f, 0.6f, 0.8f)
    private val d = at(9, 0.35f, 0f, 0.36f, 0.48f, 0.8f)
    private fun joined(faces: List<FaceVec>, answers: List<AnswerRow>) = groupFaces(seeds + faces, answers).single().faces.map { it.id }.toSet()

    @Test fun aFaceNearAYesFaceJoinsItsPerson() {
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L), joined(listOf(y, c, d), emptyList()))
        // C joins through Y; D does not, because C only joined and is no reference
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), joined(listOf(y, c, d), listOf(AnswerRow(7, 1, true))))
    }

    @Test fun aYesFaceUnderTheQualityFloorIsNoReference() {
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), joined(listOf(at(7, 0.2f, 0f, 1f), c), listOf(AnswerRow(7, 1, true))))
    }

    @Test fun aNoAnswerStopsTheJoinThroughAYesFace() {
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), joined(listOf(y, c), listOf(AnswerRow(7, 1, true), AnswerRow(8, 1, false))))
    }

    private fun faceOf(faces: List<FaceVec>, id: Long, answers: List<AnswerRow> = emptyList()) =
        groupFaces(seeds + faces, answers).single().faces.single { it.id == id }

    @Test fun aBlurryFaceCloseToASharpOneIsSure() {
        // 0.4 from the starting faces: joins, and the quality line alone would leave it unsure
        assertTrue(faceOf(listOf(at(7, 0.3f, 0.6f, 0.8f)), 7).sure)
    }

    @Test fun aBlurryFaceFartherThanHalfStaysUnsure() {
        // 0.6 from the starting faces: joins by the average, but nothing vouches for it
        assertFalse(faceOf(listOf(at(7, 0.3f, 0.4f, 0.9165f)), 7).sure)
    }

    @Test fun aBlurryFaceStaysUnsureWhenAnotherPersonIsAlmostAsClose() {
        // X is 0.40 from the first person and 0.45 from the second: a gap under 0.1
        val second = (11L..16L).map { at(it, 0.9f, -0.338f, 0.941f) }
        val people = groupFaces(seeds + second + at(7, 0.3f, 0.6f, 0.8f))
        assertFalse(people.flatMap { it.faces }.single { it.id == 7L }.sure)
    }

    @Test fun aFaceTheUserRejectedIsNeverSureByNeighbours() {
        assertFalse(faceOf(listOf(at(7, 0.3f, 0.6f, 0.8f).withRejected()), 7).sure)
    }

    @Test fun aYesFaceMakesItsLookAlikesSure() {
        val answers = listOf(AnswerRow(7, 1, true))
        val lookAlike = at(8, 0.3f, 0f, 0.96f, 0.28f)  // 0.04 from Y: through Y
        val unvouched = at(9, 0.3f, 0.4f, 0f, 0.9165f) // 0.6 from the starting faces and 1.0 from Y: joins by the average only
        val faces = listOf(y, lookAlike, unvouched)
        assertTrue(faceOf(faces, 8, answers).sure)
        assertFalse(faceOf(faces, 9, answers).sure)
    }

    @Test fun twinsAreTheUnsureFacesWithin03() {
        val a = at(1, 0.3f, 1f)
        val twin = at(2, 0.3f, 0.96f, 0.28f)
        val far = at(3, 0.3f, 0.6f, 0.8f)
        val sharp = at(4, 0.9f, 0.99f, 0.14f)
        assertEquals(listOf(2L), Person(listOf(a, twin, far, sharp), a).twinsOf(a).map { it.id })
    }

    @Test fun sureNeedsQualityOrAYes() {
        assertTrue(face(1, 1, quality = 0.4f).sure)
        assertFalse(face(1, 1, quality = 0.39f).sure)
        assertTrue(face(1, 1, quality = 0.1f).withYes().sure)   // a yes overrides a low quality
        assertFalse(face(1, 1, quality = 0.9f).withRejected().sure)
    }

    @Test fun aPersonNeedsSixPhotos() {
        assertEquals(1, groupFaces((1L..6L).map { face(it, it) }).size)
        assertEquals(6, groupFaces((1L..6L).map { face(it, it) })[0].photos)
    }

    @Test fun mainFaceIsTheSharpestStartingFace() {
        val faces = (1L..6L).map { face(it, it, quality = 0.5f + it / 100f) }
        assertEquals(6L, groupFaces(faces).single().main.id)
    }

    @Test fun fivePhotosAreNotEnough() {
        assertTrue(groupFaces((1L..5L).map { face(it, it) }).isEmpty())
    }

    @Test fun onePhotoCountsOnce() {
        // 7 faces but only 5 different photos: still not a person
        val faces = (1L..7L).map { face(it, photoId = 1 + (it - 1) % 5) }
        assertTrue(groupFaces(faces).isEmpty())
    }

    @Test fun lowQualityDoesNotStartAPerson() {
        assertTrue(groupFaces((1L..6L).map { face(it, it, quality = 0.2f) }).isEmpty())
    }

    @Test fun nonCameraDoesNotStartAPerson() {
        assertTrue(groupFaces((1L..6L).map { face(it, it, kind = PhotoKind.other) }).isEmpty())
    }

    @Test fun smallFacesAreLeftOut() {
        // box under 40 px on one side: dropped before grouping
        val small = (1L..6L).map { FaceVec(it, it, floatArrayOf(0f, 0f, 20f, 20f), FloatArray(512).also { v -> v[0] = 1f }, 0.9f, PhotoKind.camera, null) }
        assertTrue(groupFaces(small).isEmpty())
    }

    @Test fun sameGroupsAsClosestPairFirst() {
        for (seed in 1L..5L) {
            // 12 people with loose to tight faces (distance 0.3-0.75, the cut is 0.68), plus 60 strangers
            val rnd = java.util.Random(seed)
            fun unit(v: FloatArray) = v.also { val l = kotlin.math.sqrt(v.sumOf { it * it.toDouble() }).toFloat(); for (k in v.indices) v[k] /= l }
            fun noise() = FloatArray(512) { rnd.nextGaussian().toFloat() / kotlin.math.sqrt(512f) }
            val vectors = (1..12).flatMap { _ ->
                val center = unit(noise()); val spread = 0.6f + rnd.nextFloat()
                List(4 + rnd.nextInt(20)) { unit(noise().also { v -> for (k in v.indices) v[k] = center[k] + spread * v[k] }) }
            } + List(60) { unit(noise()) }
            val faces = vectors.mapIndexed { i, v -> FaceVec(i.toLong(), i.toLong(), floatArrayOf(0f, 0f, 50f, 50f), v, 0.9f, PhotoKind.camera, null) }
            assertEquals(closestPairFirst(faces, 0.68f), groupFaces(faces).map { p -> p.faces.map { it.id }.toSet() }.toSet())
        }
    }

    /** The old O(n³) grouping: merge the closest pair until none is within the cut. Each face is its own photo here. */
    private fun closestPairFirst(seeds: List<FaceVec>, cut: Float): Set<Set<Long>> {
        val n = seeds.size
        val d = Array(n) { i -> FloatArray(n) { j -> var s = 0f; for (k in 0 until 512) s += seeds[i].vector[k] * seeds[j].vector[k]; 1 - s } }
        val members = Array(n) { mutableListOf(seeds[it].id) }
        val alive = BooleanArray(n) { true }
        while (true) {
            var best = cut; var a = -1; var b = -1
            for (i in 0 until n) if (alive[i]) for (j in i + 1 until n) if (alive[j] && d[i][j] <= best) { best = d[i][j]; a = i; b = j }
            if (a < 0) break
            val na = members[a].size; val nb = members[b].size
            for (k in 0 until n) { d[a][k] = (na * d[a][k] + nb * d[b][k]) / (na + nb); d[k][a] = d[a][k] }
            members[a].addAll(members[b])
            alive[b] = false
        }
        return (0 until n).filter { alive[it] && members[it].size > 5 }.map { members[it].toSet() }.toSet()
    }

    private fun pet(id: Long, photoId: Long, x: Float, y: Float) =
        FaceVec(id, photoId, floatArrayOf(0f, 0f, 50f, 50f), floatArrayOf(x, y), 0.8f, PhotoKind.camera, null, pet = true)

    @Test fun petsGroupFromSixPhotos_andMergesJoinThem() {
        val cat = (1L..6L).map { pet(it, it, 1f, 0f) }
        val dog = (11L..16L).map { pet(it, it, 0f, 1f) }
        val seenOnce = (21L..25L).map { pet(it, it, -1f, 0f) } // 5 photos: no pet yet
        val pets = groupPets(cat + dog + seenOnce)
        assertEquals(listOf((1L..6L).toSet(), (11L..16L).toSet()), pets.map { p -> p.faces.map { it.id }.toSet() }.sortedBy { it.min() })
        assertTrue(pets.all { it.pet })
        assertEquals(12, groupPets(cat + dog, listOf(MergeRow(face = 11, personFace = 1))).single().faces.size)
    }

    @Test fun aPersonAndAPetNeverMerge() {
        val person = face(1, 1).let { Person(listOf(it), it) }
        val cat = pet(2, 2, 1f, 0f).let { Person(listOf(it), it) }
        assertFalse(canMerge(person, cat))
        assertTrue(canMerge(cat, cat))
        assertEquals("1 person · 1 pet", peopleAndPets(listOf(person, cat)))
        assertEquals("2 people", peopleAndPets(listOf(person, person)))
    }
}

// Small copies of a face with one flag flipped, so the tests read simply.
private fun FaceVec.withYes() = FaceVec(id, photoId, box, vector, quality, kind, personId, yes = true, rejected = rejected)
private fun FaceVec.withRejected() = FaceVec(id, photoId, box, vector, quality, kind, personId, yes = yes, rejected = true)
