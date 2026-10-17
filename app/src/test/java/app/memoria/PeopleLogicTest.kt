package app.memoria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleLogicTest {

    /** Same face vector every time, so cosine distance is 0 and everything would merge. */
    private fun face(id: Long, photoId: Long, quality: Float = 0.9f, kind: String = "camera") =
        FaceVec(id, photoId, floatArrayOf(0f, 0f, 50f, 50f), FloatArray(512).also { it[0] = 1f }, quality, kind, null)

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
        assertTrue(groupFaces((1L..6L).map { face(it, it, kind = "other") }).isEmpty())
    }

    @Test fun smallFacesAreLeftOut() {
        // box under 40 px on one side: dropped before grouping
        val small = (1L..6L).map { FaceVec(it, it, floatArrayOf(0f, 0f, 20f, 20f), FloatArray(512).also { v -> v[0] = 1f }, 0.9f, "camera", null) }
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
            val faces = vectors.mapIndexed { i, v -> FaceVec(i.toLong(), i.toLong(), floatArrayOf(0f, 0f, 50f, 50f), v, 0.9f, "camera", null) }
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
}

// Small copies of a face with one flag flipped, so the tests read simply.
private fun FaceVec.withYes() = FaceVec(id, photoId, box, vector, quality, kind, personId, yes = true, rejected = rejected)
private fun FaceVec.withRejected() = FaceVec(id, photoId, box, vector, quality, kind, personId, yes = yes, rejected = true)
