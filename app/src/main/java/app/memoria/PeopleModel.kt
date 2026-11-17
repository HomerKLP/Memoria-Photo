package app.memoria

/**
 * A saved face with the kind of its photo. [yes]: the user said it is its person. [rejected]: the user said no to some person.
 * [nearSure]: its person's sharp or Yes faces vouch for it (set by [groupFaces], saved at regroup). [pet]: a cat or dog picture, see [groupPets].
 */
class FaceVec(
    val id: Long, val photoId: Long, val box: FloatArray, val vector: FloatArray, val quality: Float, val kind: PhotoKind, val personId: Long?,
    val yes: Boolean = false, val rejected: Boolean = false, val nearSure: Boolean = false, val pet: Boolean = false,
) {
    /** Unsure faces wait in Help recognize. A face moved off a person by a no landed by distance, so it is asked again. */
    val sure get() = yes || (!rejected && (nearSure || quality >= 0.4f))

    /** Cosine distance: 0 is the same face. */
    fun distanceTo(o: FaceVec) = 1 - dot(vector, o.vector)

    fun withNearSure(v: Boolean) = FaceVec(id, photoId, box, vector, quality, kind, personId, yes, rejected, v, pet)
}

/**
 * A face that belongs to a person, without its 512-float vector (see `IndexDao.taggedFaces`).
 * The viewer details panel shows names, covers and counts; it never matches by distance, so the vector is not read.
 */
class LightFace(
    val id: Long, val photoId: Long, val box: FloatArray, val quality: Float, val kind: PhotoKind, val personId: Long,
    val yes: Boolean = false, val rejected: Boolean = false, val nearSure: Boolean = false, val pet: Boolean = false,
) {
    fun toVec() = FaceVec(id, photoId, box, FloatArray(0), quality, kind, personId, yes, rejected, nearSure, pet)
}

class Person(val faces: List<FaceVec>, val main: FaceVec, val id: Long = 0, val name: String? = null, val hidden: Boolean = false, val coverFaceId: Long? = null, val pos: Int? = null) {
    /**
     * The person's faces from photos: the faces row, the Unsure card, Help recognize, the cover and the face count.
     * A video face only ever joins a person, it never shows (ROADMAP Phase 13).
     */
    val shownFaces = faces.filter { it.kind != PhotoKind.video }

    /** The unsure faces of this person that look like [face] (0.3 or closer): one Yes on [face] settles them too. Not under quality 0.3: the regroup doesn't trust that Yes. */
    fun twinsOf(face: FaceVec) = if (face.quality < 0.3f) emptyList() else shownFaces.filter { !it.sure && it.id != face.id && it.distanceTo(face) <= 0.3f }

    /** Distinct photos the person appears in; a video counts as a video, never a photo. Counted once: the faces never change while this snapshot lives. */
    val photos = shownFaces.map { it.photoId }.distinct().size

    /** Distinct videos the person appears in. */
    val videos = faces.filter { it.kind == PhotoKind.video }.map { it.photoId }.distinct().size

    /** The face shown for this person (People, Library, the top bar, the sheets): the user's pick, or the main face. Never a video face. */
    val cover get() = shownFaces.firstOrNull { it.id == coverFaceId } ?: main

    /** A cat or dog: its faces are pet pictures (docs/objects.md). */
    val pet get() = main.pet
}

/** The same person with a new cover, for updating a cached list in place (see [updateCached]). */
fun Person.withCover(cover: Long?) = Person(faces, main, id, name, hidden, cover, pos)

/**
 * [p] with its [faces], for the screens that list saved people. Null when the person has no face left
 * (a stopped index run, a manual edit). The main face is [PersonRow.mainFaceId], or the first one.
 */
fun personOf(p: PersonRow, faces: List<FaceVec>): Person? {
    if (faces.isEmpty()) return null
    return Person(faces, faces.firstOrNull { it.id == p.mainFaceId } ?: faces.first(), p.id, p.name, p.hidden, p.coverFaceId, p.pos)
}

/** "18 people · 3 pets", no pets part without pets (docs/design.html, People & pets). */
fun peopleAndPets(all: List<Person>): String {
    val pets = all.count { it.pet }
    val people = all.size - pets
    return "$people ${if (people == 1) "person" else "people"}" + if (pets == 0) "" else " · $pets pet${if (pets == 1) "" else "s"}"
}

/** "224 photos · 6 videos": a person's title line, the videos part only when there are some (docs/design.html, Person & Pet). */
fun photosAndVideos(photos: Int, videos: Int) = count(photos, "photo") + if (videos == 0) "" else " · ${count(videos, "video")}"

/** "96 faces match Dana": the count moved off the title line, which the Filter icon needs (mockup "Filter"). */
fun facesMatch(n: Int, who: String) = "${count(n, "face")} ${if (n == 1) "matches" else "match"} $who"

/** Everything the People screen shows, loaded in one pass. */
data class PeopleData(val summary: String, val people: List<Person>, val unsure: List<FaceVec>)

/** The same data with one person's cover changed, for updating a cached list in place (see [updateCached]). */
fun PeopleData.withCover(personId: Long, faceId: Long?) =
    copy(people = people.map { if (it.id == personId) it.withCover(faceId) else it })
