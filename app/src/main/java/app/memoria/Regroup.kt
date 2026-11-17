package app.memoria

import androidx.room.withTransaction

/**
 * The old person whose faces overlap this new group the most, if it kept at least half of them.
 * Named or hidden people win, so a name survives two people joining. `taken` keeps one old person from landing on two groups when it split.
 */
fun carry(ids: List<Long>, old: List<PersonRow>, oldFaces: Map<Long, Set<Long>>, taken: MutableSet<Long>, pins: Map<Long, Long> = emptyMap()): PersonRow? {
    val set = ids.toSet()
    // [pins] (Unmerge): that old person goes only to the group holding its pinned face, however small its share
    old.firstOrNull { it.id !in taken && pins[it.id]?.let { f -> f in set } == true }?.let { taken += it.id; return it }
    return old.filter { it.id !in taken && it.id !in pins }
        .map { it to set.count { f -> f in oldFaces[it.id].orEmpty() } }
        .filter { (o, n) -> n * 2 >= (oldFaces[o.id]?.size ?: 1) }
        .maxWithOrNull(compareBy({ it.first.name != null || it.first.hidden }, { it.second }))?.first?.also { taken += it.id }
}

/**
 * Groups the faces and replaces the saved people. Saved people keep their faces (see [groupFaces]); [full] and a pinned person start over.
 * A new group keeps the id, name and hidden flag of the old person it matches (see [carry]), so an open Person screen and a name typed
 * mid-run stay on the same person. The caller bumps [peopleSaved].
 */
suspend fun regroup(db: Db, pins: Map<Long, Long> = emptyMap(), full: Boolean = false) {
    val start = System.nanoTime()
    val dao = db.index()
    // one transaction: a name or New person saved during the regroup waits for it instead of being lost
    val (saved, kept, groupMs) = db.withTransaction {
        val faces = dao.faceVecs()
        val old = dao.people()
        val oldFaces = faces.filter { it.personId != null }.groupBy { it.personId!! }.mapValues { e -> e.value.map { it.id }.toSet() }
        val merges = dao.merges()
        val answers = dao.answers()
        // a face with no person goes back into the linkage alone: Unmerge (pins) splits that person this way
        val toGroup = faces.map { if (full || it.personId in pins) FaceVec(it.id, it.photoId, it.box, it.vector, it.quality, it.kind, null, it.yes, it.rejected, it.nearSure, it.pet) else it }
        val t = System.nanoTime()
        val people = groupFaces(toGroup.filter { !it.pet }, answers, merges) + groupPets(faces.filter { it.pet }, answers, merges)
        val groupMs = (System.nanoTime() - t) / 1_000_000
        val taken = mutableSetOf<Long>()
        // a pinned face no group holds any more pins nothing: that person is carried by overlap as usual
        val live = pins.filterValues { f -> people.any { p -> p.faces.any { it.id == f } } }
        var next = (old.maxOfOrNull { it.id } ?: 0) + 1
        dao.deletePeople()
        dao.clearPersonIds()
        for (p in people) {
            val ids = p.faces.map { it.id }
            if (ids.isEmpty()) continue // never happens (a person needs faces), keeps IN () off the DB
            val match = carry(ids, old, oldFaces, taken, live)
            val pid = match?.id ?: next++
            // the cover follows the person only while its face is still with them; else the app picks one again (main face)
            dao.insertPerson(PersonRow(pid, p.main.id, match?.name, match?.hidden ?: false, match?.coverFaceId?.takeIf { it in ids }, match?.pos))
            dao.setPerson(ids, pid)
            val near = p.faces.filter { it.nearSure }.map { it.id }
            if (near.isNotEmpty()) dao.setNearSure(near)
        }
        Triple(people.size, taken.size, groupMs)
    }
    AppLog.i("saved $saved people ($kept kept their id) in ${(System.nanoTime() - start) / 1_000_000} ms, grouping $groupMs ms${if (full) ", full" else ""}")
}
