package app.memoria

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.Flow

/** camera can start a person, other (downloads, messengers) only joins people, video only joins too. Room saves the name, so the names are the DB values. */
enum class PhotoKind { camera, other, skip, error, video }

/**
 * townId: GeoNames id of the nearest town (see [Towns]), 0 = no GPS, null = GPS not read yet.
 * objects: the [ObjectDetector.VERSION] that boxed the photo (none needed for skip and error), null = not boxed yet.
 */
@Entity(tableName = "photo")
class PhotoRow(
    @PrimaryKey val id: Long, val path: String, val kind: PhotoKind,
    val lat: Double? = null, val lon: Double? = null, val townId: Int? = null, val town: String? = null,
    val objects: Int? = null,
)

@Entity(tableName = "face", indices = [Index("photoId")])
class FaceRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val photoId: Long,
    val box: FloatArray,
    val score: Float,
    val landmarks: FloatArray,
    val vector: FloatArray,
    val quality: Float,
    val thumb: ByteArray,
    val personId: Long? = null,
    val notFace: Boolean = false, // the user said "Not a face": left out of everything
    val nearSure: Boolean = false, // set at regroup: the person's sharp or Yes faces vouch for this face (see groupFaces)
    // a cat or dog box (`docs/objects.md`): DINOv2 vector, quality = box score, no landmarks; grouped by groupPets, never by groupFaces
    val pet: Boolean = false,
)

/**
 * People saved by the last index run, see savePeople. `name`, `hidden`, `coverFaceId` and `pos` are the user's; kept across re-index runs.
 * `mainFaceId` is the face the app matches and merges with; `coverFaceId` is only the picture shown for the person (Change cover), null = the main face.
 * `pos`: the named person's place after a drag on People, null = never moved.
 */
@Entity(tableName = "person")
class PersonRow(@PrimaryKey val id: Long, val mainFaceId: Long, val name: String? = null, val hidden: Boolean = false, val coverFaceId: Long? = null, val pos: Int? = null)

/**
 * Help recognize answer: face [faceId] is (yes) or is not (no) the person that [personFace] belongs to.
 * [personFace] is that person's main face (a starting face, or the face a New person began from), so it outlives the person ids that every regroup changes.
 */
@Entity(tableName = "answer", primaryKeys = ["faceId", "personFace"])
class AnswerRow(val faceId: Long, val personFace: Long, val yes: Boolean)

/** Merge with: the person whose main face is [face] is the same as the one whose main face is [personFace]. Main faces, like [AnswerRow]. */
@Entity(tableName = "merged", primaryKeys = ["face", "personFace"])
class MergeRow(val face: Long, val personFace: Long)

/** A YOLO26 person, cat or dog box (`docs/objects.md`), x1, y1, x2, y2 as fractions of the photo. Pets themselves are [FaceRow]s with pet set. */
@Entity(tableName = "object_box", indices = [Index("photoId")])
class ObjectBoxRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val photoId: Long, val cls: Int, val score: Float, val box: FloatArray)

/** The tagger's vector for a photo (`docs/tagger.md`), empty when the photo can't be read. [model] is [Tagger.VERSION]. */
@Entity(tableName = "photo_vector", indices = [Index("model")])
class PhotoVectorRow(@PrimaryKey val photoId: Long, val model: Int, val vector: FloatArray)

/** A photo's tags from its vector, as "id score;id score" ([photoTags]). [stamp] is [Tag.stamp]: a new `tags.json` scores every photo again. */
@Entity(tableName = "photo_tag")
class PhotoTagRow(@PrimaryKey val photoId: Long, val stamp: Int, val tags: String)

class Converters {
    @TypeConverter fun toBytes(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).apply { asFloatBuffer().put(v) }.array()
    @TypeConverter fun toFloats(b: ByteArray): FloatArray = FloatArray(b.size / 4).also { ByteBuffer.wrap(b).asFloatBuffer().get(it) }
}

@Dao
interface IndexDao {
    @Query("SELECT id FROM photo") suspend fun photoIds(): List<Long>
    @Query("DELETE FROM photo") suspend fun clearPhotos()
    @Query("DELETE FROM face WHERE photoId IN (:ids)") suspend fun deleteFaces(ids: List<Long>)
    @Query("DELETE FROM photo WHERE id IN (:ids)") suspend fun deletePhotos(ids: List<Long>)
    @Query("DELETE FROM face") suspend fun clearFaces()
    @Query("DELETE FROM answer") suspend fun clearAnswers()
    @Insert suspend fun insert(photo: PhotoRow, faces: List<FaceRow>)
    @Query("SELECT kind, COUNT(*) AS n FROM photo GROUP BY kind")
    fun kinds(): Flow<Map<@MapColumn("kind") PhotoKind, @MapColumn("n") Int>>
    @Query("SELECT COUNT(*) FROM face") fun faceCount(): Flow<Int>
    /** Faces the user did not mark "Not a face", for the People summary; the display reads no vectors. */
    @Query("SELECT COUNT(*) FROM face WHERE NOT notFace") suspend fun faceTotal(): Int
    @Query(
        "SELECT f.id, f.photoId, f.box, f.vector, f.quality, p.kind, f.personId, f.nearSure, f.pet, " +
            "EXISTS(SELECT 1 FROM answer a WHERE a.faceId = f.id AND a.yes) AS yes, " +
            "EXISTS(SELECT 1 FROM answer a WHERE a.faceId = f.id AND NOT a.yes) AS rejected FROM face f JOIN photo p ON p.id = f.photoId WHERE NOT f.notFace",
    )
    suspend fun faceVecs(): List<FaceVec>
    /** [faceVecs] of saved people only, for Help recognize and Merge with: no loose faces' vectors. */
    @Query(
        "SELECT f.id, f.photoId, f.box, f.vector, f.quality, p.kind, f.personId, f.nearSure, f.pet, " +
            "EXISTS(SELECT 1 FROM answer a WHERE a.faceId = f.id AND a.yes) AS yes, " +
            "EXISTS(SELECT 1 FROM answer a WHERE a.faceId = f.id AND NOT a.yes) AS rejected FROM face f JOIN photo p ON p.id = f.photoId WHERE NOT f.notFace AND f.personId IS NOT NULL",
    )
    suspend fun personFaceVecs(): List<FaceVec>
    /** The faces of one photo, with vectors: a handful of rows instead of the whole [faceVecs]. No pets: the person-box rule is about faces. */
    @Query(
        "SELECT f.id, f.photoId, f.box, f.vector, f.quality, p.kind, f.personId, f.nearSure, f.pet, " +
            "EXISTS(SELECT 1 FROM answer a WHERE a.faceId = f.id AND a.yes) AS yes, " +
            "EXISTS(SELECT 1 FROM answer a WHERE a.faceId = f.id AND NOT a.yes) AS rejected FROM face f JOIN photo p ON p.id = f.photoId WHERE NOT f.notFace AND NOT f.pet AND f.photoId = :photoId",
    )
    suspend fun photoFaces(photoId: Long): List<FaceVec>
    /** [photoFaces] with the photo's pet pictures, for the viewer's People & pets row. */
    @Query(
        "SELECT f.id, f.photoId, f.box, f.vector, f.quality, p.kind, f.personId, f.nearSure, f.pet, " +
            "EXISTS(SELECT 1 FROM answer a WHERE a.faceId = f.id AND a.yes) AS yes, " +
            "EXISTS(SELECT 1 FROM answer a WHERE a.faceId = f.id AND NOT a.yes) AS rejected FROM face f JOIN photo p ON p.id = f.photoId WHERE NOT f.notFace AND f.photoId = :photoId",
    )
    suspend fun viewerFaces(photoId: Long): List<FaceVec>
    /** The faces that belong to a saved person, without vectors: the viewer details panel never matches by distance. */
    @Query(
        "SELECT f.id, f.photoId, f.box, f.quality, p.kind, f.personId, f.nearSure, f.pet, " +
            "EXISTS(SELECT 1 FROM answer a WHERE a.faceId = f.id AND a.yes) AS yes, " +
            "EXISTS(SELECT 1 FROM answer a WHERE a.faceId = f.id AND NOT a.yes) AS rejected FROM face f JOIN photo p ON p.id = f.photoId WHERE NOT f.notFace AND f.personId IS NOT NULL",
    )
    suspend fun taggedFaces(): List<LightFace>
    @Query("SELECT * FROM person ORDER BY id") suspend fun people(): List<PersonRow>
    /** A removed name drops the place too, so a name given later goes after the moved people. */
    @Query("UPDATE person SET name = :name, pos = CASE WHEN :name IS NULL THEN NULL ELSE pos END WHERE id = :id") suspend fun setName(id: Long, name: String?)
    @Query("UPDATE person SET pos = :pos WHERE id = :id") suspend fun setPos(id: Long, pos: Int)
    @Query("DELETE FROM person") suspend fun deletePeople()
    @Query("UPDATE face SET personId = NULL, nearSure = 0") suspend fun clearPersonIds()
    @Query("UPDATE face SET personId = NULL WHERE id IN (:faces)") suspend fun unlinkFaces(faces: List<Long>)
    @Query("UPDATE face SET nearSure = 1 WHERE id IN (:faces)") suspend fun setNearSure(faces: List<Long>)
    @Query("UPDATE face SET nearSure = 0 WHERE id IN (:faces)") suspend fun clearNearSure(faces: List<Long>)
    @Insert suspend fun insertPerson(person: PersonRow)
    @Query("UPDATE face SET personId = :person WHERE id IN (:faces)") suspend fun setPerson(faces: List<Long>, person: Long)
    @Query("SELECT thumb FROM face WHERE id = :id") suspend fun thumb(id: Long): ByteArray?
    // TODO(0.16.0): delete with recutNarrowThumbs
    @Query("UPDATE face SET thumb = :thumb WHERE id = :id") suspend fun setThumb(id: Long, thumb: ByteArray)
    @Query("SELECT * FROM answer") suspend fun answers(): List<AnswerRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun answer(answer: AnswerRow)
    @Query("DELETE FROM answer WHERE faceId = :face AND yes") suspend fun dropYes(face: Long)
    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM person") suspend fun nextPersonId(): Long
    @Query("UPDATE face SET personId = NULL WHERE id = :face") suspend fun leavePerson(face: Long)
    @Query("UPDATE face SET notFace = 1, personId = NULL WHERE id = :face") suspend fun notFace(face: Long)
    @Query("SELECT * FROM merged ORDER BY rowid") suspend fun merges(): List<MergeRow> // oldest first: Unmerge lists the newest first
    @Query("DELETE FROM merged WHERE face IN (:faces)") suspend fun unmerge(faces: List<Long>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun merge(merge: MergeRow)
    @Query("DELETE FROM merged") suspend fun clearMerges()
    @Query("UPDATE face SET personId = :to WHERE personId = :from") suspend fun movePerson(from: Long, to: Long)
    @Query("DELETE FROM person WHERE id = :id") suspend fun deletePerson(id: Long)
    @Query("UPDATE person SET hidden = :hidden WHERE id = :id") suspend fun setHidden(id: Long, hidden: Boolean)
    @Query("UPDATE person SET coverFaceId = :face WHERE id = :id") suspend fun setCover(id: Long, face: Long?)
    /** Photos whose GPS was not read yet. Videos have their own query: their GPS is a location string in the file, not EXIF. */
    @Query("SELECT id FROM photo WHERE townId IS NULL AND kind <> 'video'") suspend fun unplaced(): List<Long>
    @Query("SELECT id FROM photo WHERE townId IS NULL AND kind = 'video'") suspend fun unplacedVideos(): List<Long>
    @Query("SELECT * FROM photo WHERE townId > 0") suspend fun placed(): List<PhotoRow>
    @Query("SELECT * FROM photo WHERE townId = :town") suspend fun photosIn(town: Int): List<PhotoRow>
    @Query("UPDATE photo SET lat = :lat, lon = :lon, townId = :townId, town = :town WHERE id = :id")
    suspend fun setPlace(id: Long, lat: Double?, lon: Double?, townId: Int, town: String?)
    @Query("SELECT photoId FROM photo_vector WHERE model = :model") suspend fun taggedIds(model: Int): List<Long>
    /** Vectors with no tags from this [stamp] yet; an empty one is a photo that couldn't be read. */
    @Query("SELECT * FROM photo_vector WHERE model = :model AND length(vector) > 0 AND photoId NOT IN (SELECT photoId FROM photo_tag WHERE stamp = :stamp)")
    suspend fun untaggedVectors(model: Int, stamp: Int): List<PhotoVectorRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTags(rows: List<PhotoTagRow>)
    @Query("SELECT * FROM photo_tag WHERE stamp = :stamp") suspend fun photoTags(stamp: Int): List<PhotoTagRow>
    @Query("SELECT t.* FROM photo_tag t JOIN photo p ON p.id = t.photoId WHERE p.townId = :town AND t.stamp = :stamp") suspend fun tagsInTown(town: Int, stamp: Int): List<PhotoTagRow>
    @Query("DELETE FROM photo_tag WHERE photoId IN (:ids)") suspend fun deleteTags(ids: List<Long>)
    /** Items with every index step done: the face pass, a vector from tagger [model] while [tagger] is on the phone, boxes from [objectsModel] while [objects] is. A video is done as soon as it is indexed: it is never tagged or boxed. */
    @Query(
        "SELECT COUNT(*) FROM photo WHERE (kind = 'video' OR NOT :tagger OR id IN (SELECT photoId FROM photo_vector WHERE model = :model)) " +
            "AND (NOT :objects OR objects = :objectsModel)",
    )
    fun doneCount(tagger: Boolean, model: Int, objects: Boolean, objectsModel: Int): Flow<Int>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertVector(row: PhotoVectorRow)
    @Query("DELETE FROM photo_vector WHERE photoId IN (:ids)") suspend fun deleteVectors(ids: List<Long>)
    /** The four reads below are for the debug section of Details: one photo's own rows. */
    @Query("SELECT * FROM photo WHERE id = :id") suspend fun photoRow(id: Long): PhotoRow?
    /** Bytes of the tagger vector: null = none yet, 0 = the photo couldn't be read. */
    @Query("SELECT length(vector) FROM photo_vector WHERE photoId = :id") suspend fun vectorBytes(id: Long): Int?
    @Query("SELECT tags FROM photo_tag WHERE photoId = :id AND stamp = :stamp") suspend fun tagsOf(id: Long, stamp: Int): String?
    @Query("SELECT * FROM object_box WHERE photoId = :id") suspend fun boxesOf(id: Long): List<ObjectBoxRow>
    @Query("SELECT id FROM photo WHERE objects = :model") suspend fun boxedIds(model: Int): List<Long>
    @Query("UPDATE photo SET objects = :model WHERE id = :id") suspend fun setBoxed(id: Long, model: Int)
    @Insert suspend fun insertBoxes(boxes: List<ObjectBoxRow>)
    @Query("DELETE FROM object_box WHERE photoId IN (:ids)") suspend fun deleteBoxes(ids: List<Long>)
    @Query("DELETE FROM object_box") suspend fun clearBoxes()
    @Query("DELETE FROM face WHERE id IN (:ids)") suspend fun deleteFaceIds(ids: List<Long>)
    @Insert suspend fun insertFaces(faces: List<FaceRow>)
    @Query("DELETE FROM face WHERE photoId = :photoId AND pet") suspend fun deletePets(photoId: Long)
    /** Photos with a cat or dog box 0.5+ and their surest box: the Pets category. */
    @Query("SELECT photoId, MAX(score) AS score FROM object_box WHERE cls IN (15, 16) AND score >= 0.5 GROUP BY photoId")
    suspend fun petPhotos(): Map<@MapColumn("photoId") Long, @MapColumn("score") Float>
}

@Database(entities = [PhotoRow::class, FaceRow::class, PersonRow::class, AnswerRow::class, MergeRow::class, PhotoVectorRow::class, ObjectBoxRow::class, PhotoTagRow::class], version = 16)
@TypeConverters(Converters::class)
abstract class Db : RoomDatabase() {
    abstract fun index(): IndexDao

    companion object {
        /** v5: person.name, so a named person keeps its name across re-index runs. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE person ADD COLUMN name TEXT")
        }
        /** v6: Help recognize answers. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE face ADD COLUMN notFace INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE answer (faceId INTEGER NOT NULL, personFace INTEGER NOT NULL, yes INTEGER NOT NULL, PRIMARY KEY(faceId, personFace))")
            }
        }
        /** v7: Merge with and Hide person. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE person ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE merged (face INTEGER NOT NULL, personFace INTEGER NOT NULL, PRIMARY KEY(face, personFace))")
            }
        }
        /** v8: Change cover, the user's pick for a person's picture (null = the main face). */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE person ADD COLUMN coverFaceId INTEGER")
        }
        /** v9: photo GPS and nearest town; old photos get them on the next index run. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (col in listOf("lat REAL", "lon REAL", "townId INTEGER", "town TEXT")) db.execSQL("ALTER TABLE photo ADD COLUMN $col")
            }
        }
        /** v10: face.nearSure, faces the person's sharp or Yes faces vouch for; set at the next regroup. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE face ADD COLUMN nearSure INTEGER NOT NULL DEFAULT 0")
        }
        /** v11: person.pos, the order of named people after a drag on People. */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE person ADD COLUMN pos INTEGER")
        }
        /** v12: the tagger's photo vectors, in a table of their own; the next run tags every photo. */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) =
                db.execSQL("CREATE TABLE IF NOT EXISTS `photo_vector` (`photoId` INTEGER NOT NULL, `model` INTEGER NOT NULL, `vector` BLOB NOT NULL, PRIMARY KEY(`photoId`))")
        }
        /** v13: YOLO26 boxes and pet vectors; the next run boxes every photo. */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photo ADD COLUMN objects INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS `object_box` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `photoId` INTEGER NOT NULL, `cls` INTEGER NOT NULL, `score` REAL NOT NULL, `box` BLOB NOT NULL, `vector` BLOB, `petId` INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_object_box_photoId` ON `object_box` (`photoId`)")
            }
        }
        /** v14: pets are faces (face.pet), so they get names, covers and merges like people; the boxes go and the next run makes them again. */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE face ADD COLUMN pet INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DROP TABLE object_box")
                db.execSQL("CREATE TABLE IF NOT EXISTS `object_box` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `photoId` INTEGER NOT NULL, `cls` INTEGER NOT NULL, `score` REAL NOT NULL, `box` BLOB NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_object_box_photoId` ON `object_box` (`photoId`)")
                db.execSQL("UPDATE photo SET objects = NULL")
            }
        }
        /** v15: each photo's tags, so categories don't score every vector on each open; the first open fills it. */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) =
                db.execSQL("CREATE TABLE IF NOT EXISTS `photo_tag` (`photoId` INTEGER NOT NULL, `stamp` INTEGER NOT NULL, `tags` TEXT NOT NULL, PRIMARY KEY(`photoId`))")
        }
        /** v16: an index on photo_vector.model, so counting tagged photos stops reading every vector. */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) =
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_vector_model` ON `photo_vector` (`model`)")
        }
        internal val MIGRATIONS = arrayOf(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
        private var db: Db? = null
        @Synchronized fun get(context: Context) =
            db ?: // a missing migration crashes instead of wiping names; MigrationTest checks each bump
            Room.databaseBuilder(context.applicationContext, Db::class.java, "memoria.db")
                .addMigrations(*MIGRATIONS)
                .fallbackToDestructiveMigrationFrom(true, 1, 2, 3).build().also { db = it }
    }
}
