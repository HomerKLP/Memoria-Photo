package app.memoria

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.BatteryManager
import android.provider.MediaStore.Images.Media
import android.util.Log
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
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/** camera can start a person, other (downloads, messengers) only joins people. Room saves the name, so the names are the DB values. */
enum class PhotoKind { camera, other, skip, error }

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
@Entity(tableName = "photo_vector")
class PhotoVectorRow(@PrimaryKey val photoId: Long, val model: Int, val vector: FloatArray)

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
    /** The faces of one photo, with vectors: a handful of rows instead of the whole [faceVecs]. No pets: the viewer and the person-box rule are about faces. */
    @Query(
        "SELECT f.id, f.photoId, f.box, f.vector, f.quality, p.kind, f.personId, f.nearSure, f.pet, " +
            "EXISTS(SELECT 1 FROM answer a WHERE a.faceId = f.id AND a.yes) AS yes, " +
            "EXISTS(SELECT 1 FROM answer a WHERE a.faceId = f.id AND NOT a.yes) AS rejected FROM face f JOIN photo p ON p.id = f.photoId WHERE NOT f.notFace AND NOT f.pet AND f.photoId = :photoId",
    )
    suspend fun photoFaces(photoId: Long): List<FaceVec>
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
    @Query("UPDATE face SET nearSure = 1 WHERE id IN (:faces)") suspend fun setNearSure(faces: List<Long>)
    @Insert suspend fun insertPerson(person: PersonRow)
    @Query("UPDATE face SET personId = :person WHERE id IN (:faces)") suspend fun setPerson(faces: List<Long>, person: Long)
    @Query("SELECT thumb FROM face WHERE id = :id") suspend fun thumb(id: Long): ByteArray?
    @Query("UPDATE face SET thumb = :thumb WHERE id = :id") suspend fun setThumb(id: Long, thumb: ByteArray)
    @Query("SELECT * FROM answer") suspend fun answers(): List<AnswerRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun answer(answer: AnswerRow)
    @Query("DELETE FROM answer WHERE faceId = :face AND yes") suspend fun dropYes(face: Long)
    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM person") suspend fun nextPersonId(): Long
    @Query("UPDATE face SET personId = NULL WHERE id = :face") suspend fun leavePerson(face: Long)
    @Query("UPDATE face SET notFace = 1, personId = NULL WHERE id = :face") suspend fun notFace(face: Long)
    @Query("SELECT * FROM merged") suspend fun merges(): List<MergeRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun merge(merge: MergeRow)
    @Query("DELETE FROM merged") suspend fun clearMerges()
    @Query("UPDATE face SET personId = :to WHERE personId = :from") suspend fun movePerson(from: Long, to: Long)
    @Query("DELETE FROM person WHERE id = :id") suspend fun deletePerson(id: Long)
    @Query("UPDATE person SET hidden = :hidden WHERE id = :id") suspend fun setHidden(id: Long, hidden: Boolean)
    @Query("UPDATE person SET coverFaceId = :face WHERE id = :id") suspend fun setCover(id: Long, face: Long?)
    @Query("SELECT id FROM photo WHERE townId IS NULL") suspend fun unplaced(): List<Long>
    @Query("SELECT * FROM photo WHERE townId > 0") suspend fun placed(): List<PhotoRow>
    @Query("UPDATE photo SET lat = :lat, lon = :lon, townId = :townId, town = :town WHERE id = :id")
    suspend fun setPlace(id: Long, lat: Double?, lon: Double?, townId: Int, town: String?)
    @Query("SELECT photoId FROM photo_vector WHERE model = :model") suspend fun taggedIds(model: Int): List<Long>
    /** Vectors for categories; an empty one is a photo that couldn't be read. */
    @Query("SELECT * FROM photo_vector WHERE model = :model AND length(vector) > 0") suspend fun vectors(model: Int): List<PhotoVectorRow>
    /** Photos with every index step done: the face pass, a vector from tagger [model] while [tagger] is on the phone, boxes from [objectsModel] while [objects] is. */
    @Query(
        "SELECT COUNT(*) FROM photo WHERE (NOT :tagger OR id IN (SELECT photoId FROM photo_vector WHERE model = :model)) " +
            "AND (NOT :objects OR objects = :objectsModel)",
    )
    fun doneCount(tagger: Boolean, model: Int, objects: Boolean, objectsModel: Int): Flow<Int>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertVector(row: PhotoVectorRow)
    @Query("DELETE FROM photo_vector WHERE photoId IN (:ids)") suspend fun deleteVectors(ids: List<Long>)
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

@Database(entities = [PhotoRow::class, FaceRow::class, PersonRow::class, AnswerRow::class, MergeRow::class, PhotoVectorRow::class, ObjectBoxRow::class], version = 14)
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
        internal val MIGRATIONS = arrayOf(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
        private var db: Db? = null
        @Synchronized fun get(context: Context) =
            db ?: // a missing migration crashes instead of wiping names; MigrationTest checks each bump
            Room.databaseBuilder(context.applicationContext, Db::class.java, "memoria.db")
                .addMigrations(*MIGRATIONS)
                .fallbackToDestructiveMigrationFrom(true, 1, 2, 3).build().also { db = it }
    }
}

class MediaPhoto(val id: Long, val path: String, val mime: String, val width: Int, val height: Int)

// ponytail: test folder only, drop the filter to index the whole library
fun testPhotos(context: Context): List<MediaPhoto> {
    val photos = mutableListOf<MediaPhoto>()
    val cols = arrayOf(Media._ID, Media.RELATIVE_PATH, Media.DISPLAY_NAME, Media.MIME_TYPE, Media.WIDTH, Media.HEIGHT)
    context.contentResolver.query(Media.EXTERNAL_CONTENT_URI, cols, "${Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/MemoriaTest/%"), null)?.use { c ->
        while (c.moveToNext()) photos += MediaPhoto(c.getLong(0), c.getString(1) + c.getString(2), c.getString(3), c.getInt(4), c.getInt(5))
    }
    return photos
}

/**
 * Every image MediaStore knows about, like Immich's import paths: include all, minus the albums
 * the user ignores (Album, More > Ignore faces). A run forgets the faces of photos that drop out.
 * Videos stay out for now. kindOf sorts camera / other / skip.
 */
fun libraryPhotos(context: Context): List<MediaPhoto> {
    val skipped = IndexProgress.skipped(context)
    val photos = mutableListOf<MediaPhoto>()
    val cols = arrayOf(Media._ID, Media.RELATIVE_PATH, Media.DISPLAY_NAME, Media.MIME_TYPE, Media.WIDTH, Media.HEIGHT, Media.BUCKET_ID)
    context.contentResolver.query(Media.EXTERNAL_CONTENT_URI, cols, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val path = c.getString(1) + c.getString(2)
            if (c.getLong(6) !in skipped) photos += MediaPhoto(c.getLong(0), path, c.getString(3), c.getInt(4), c.getInt(5))
        }
    }
    return photos
}

/**
 * How many photos [libraryPhotos] would return, without building the list: a row-count read only.
 * Library, People and the test screen only need the number for their progress totals.
 */
fun libraryPhotoCount(context: Context): Int {
    val skipped = IndexProgress.skipped(context)
    val selection = if (skipped.isEmpty()) null else "${Media.BUCKET_ID} NOT IN (${skipped.joinToString(",")})"
    return runCatching {
        context.contentResolver.query(Media.EXTERNAL_CONTENT_URI, arrayOf(Media._ID), selection, null, null)?.use { it.count } ?: 0
    }.getOrDefault(0)
}

/** Total and start time of the current index run, so the UI can show a progress bar and time left. */
object IndexProgress {
    private fun prefs(context: Context) = context.getSharedPreferences("index", Context.MODE_PRIVATE)
    fun start(context: Context, total: Int, base: Int) {
        prefs(context).edit().putInt("total", total).putInt("base", base).putLong("started", System.currentTimeMillis()).putBoolean("paused", false).apply()
    }
    fun total(context: Context) = prefs(context).getInt("total", 0)
    fun base(context: Context) = prefs(context).getInt("base", 0)
    fun startedAt(context: Context) = prefs(context).getLong("started", 0L)
    fun paused(context: Context) = prefs(context).getBoolean("paused", false)
    fun setPaused(context: Context, paused: Boolean) = prefs(context).edit().putBoolean("paused", paused).apply()
    fun testOnly(context: Context) = prefs(context).getBoolean("testOnly", false)
    fun setTestOnly(context: Context, testOnly: Boolean) = prefs(context).edit().putBoolean("testOnly", testOnly).apply()
    fun chargingOnly(context: Context) = prefs(context).getBoolean("chargingOnly", false)
    fun setChargingOnly(context: Context, on: Boolean) = prefs(context).edit().putBoolean("chargingOnly", on).apply()
    /** Albums (MediaStore bucket ids) left out of face search. */
    fun skipped(context: Context): Set<Long> = prefs(context).getStringSet("skipped", null).orEmpty().map { it.toLong() }.toSet()
    fun setSkipped(context: Context, bucket: Long, on: Boolean) {
        val ids = skipped(context).map { "$it" }.toSet()
        prefs(context).edit().putStringSet("skipped", if (on) ids + "$bucket" else ids - "$bucket").apply()
    }
    /** Done: clears the run's total, base and start time, keeps the paused and scope flags. */
    fun finish(context: Context) = prefs(context).edit().remove("total").remove("base").remove("started").apply()
}

/** A queued run that only waits for the charger, so the progress row says so instead of hiding. */
fun waitsForCharger(context: Context) =
    IndexProgress.chargingOnly(context) && !context.getSystemService(BatteryManager::class.java).isCharging

/** Starts, pauses and resumes the index work. Pausing cancels the worker; already-saved photos are skipped on resume. */
object IndexControl {
    private const val WORK = "index"
    fun start(context: Context, testOnly: Boolean) {
        IndexProgress.setTestOnly(context, testOnly)
        IndexProgress.setPaused(context, false)
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<IndexWorker>().setInputData(workDataOf("testOnly" to testOnly))
                // unplugging stops the worker, WorkManager starts it again on the next charge
                .setConstraints(Constraints.Builder().setRequiresCharging(IndexProgress.chargingOnly(context)).build()).build(),
        )
    }
    fun pause(context: Context) {
        IndexProgress.setPaused(context, true)
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
    fun resume(context: Context) = start(context, IndexProgress.testOnly(context))
}

/** Goes up after each savePeople, so Library and People reload while a run is going. */
val peopleSaved = MutableStateFlow(0)

/**
 * The old person whose faces overlap this new group the most, if it kept at least half of them.
 * Named or hidden people win, so a name survives two people joining. `taken` keeps one old person from landing on two groups when it split.
 */
fun carry(ids: List<Long>, old: List<PersonRow>, oldFaces: Map<Long, Set<Long>>, taken: MutableSet<Long>): PersonRow? {
    val set = ids.toSet()
    return old.filter { it.id !in taken }
        .map { it to set.count { f -> f in oldFaces[it.id].orEmpty() } }
        .filter { (o, n) -> n * 2 >= (oldFaces[o.id]?.size ?: 1) }
        .maxWithOrNull(compareBy({ it.first.name != null || it.first.hidden }, { it.second }))?.first?.also { taken += it.id }
}

/** Rough time left: elapsed per photo times the photos left. Null until at least one photo is done. */
fun etaMillis(started: Long, done: Int, total: Int): Long? {
    if (started <= 0L || done <= 0 || total <= done) return null
    return (System.currentTimeMillis() - started) / done * (total - done)
}

fun formatEta(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(1)
    return when {
        s < 60 -> "${s}s left"
        s < 3600 -> "${s / 60}m left"
        else -> "${s / 3600}h ${(s % 3600) / 60}m left"
    }
}

/** Folders, mime types and tiny images that never enter people grouping. Pure part of [kindOf]. */
internal fun skipsIndex(photo: MediaPhoto): Boolean =
    "screenshot" in photo.path.lowercase() || photo.mime == "image/gif" || min(photo.width, photo.height) in 1 until 200

/** Pure part of [kindOf]: [hasCameraMake] is whether the file carries a camera EXIF make. */
internal fun kindOf(photo: MediaPhoto, hasCameraMake: Boolean): PhotoKind = when {
    skipsIndex(photo) -> PhotoKind.skip
    hasCameraMake -> PhotoKind.camera
    else -> PhotoKind.other
}

fun kindOf(context: Context, photo: MediaPhoto, uri: Uri): PhotoKind =
    kindOf(photo, context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it).getAttribute(ExifInterface.TAG_MAKE) } != null)

/** Finds faces in new photos and saves them. Photos already in the DB are skipped, so it can stop and resume. */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dao = Db.get(applicationContext).index()
        val testOnly = inputData.getBoolean("testOnly", false)
        val all = if (testOnly) testPhotos(applicationContext) else libraryPhotos(applicationContext)
        val total = all.size
        val done = dao.photoIds().toHashSet()
        val tagged = dao.taggedIds(Tagger.VERSION).toHashSet()
        val boxed = dao.boxedIds(ObjectDetector.VERSION).toHashSet()
        // photos deleted from the phone: forget them, their faces, vectors and boxes, so counts and people match the phone
        if (!testOnly) (done + tagged - all.map { it.id }.toSet()).chunked(500).forEach { dao.deleteFaces(it); dao.deletePhotos(it); dao.deleteVectors(it); dao.deleteBoxes(it) }
        recutThumbs()
        if (isStopped) return Result.success()
        val todo = all.filter { it.id !in done }
        val tagging = taggerFile(applicationContext).exists()
        val boxing = objectsFiles(applicationContext).all { it.exists() }
        // photos indexed before the tagger or the object models, or by older ones
        val catchUp = all.filter { it.id in done && ((tagging && it.id !in tagged) || (boxing && it.id !in boxed)) }
        if (todo.isEmpty() && catchUp.isEmpty()) {
            IndexProgress.finish(applicationContext)
            placePhotos()
            return savePeople() // nothing new, but the grouping code may have changed
        }
        val base = total - todo.size - catchUp.size // photos with every step done, so the bar starts there
        IndexProgress.start(applicationContext, total, base)
        // Foreground, or the system stops the job midway. Not allowed when started from the background (rerun after a stop), then it runs as a plain job.
        val notifications = applicationContext.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("index", "Indexing", NotificationManager.IMPORTANCE_LOW))
        val foreground = try {
            setForeground(ForegroundInfo(1, notice(base, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            true
        } catch (e: IllegalStateException) {
            Log.w("Memoria", "index runs as a plain job", e)
            false
        }
        val start = System.nanoTime()
        var count = 0
        var tagCount = 0
        var catchUpCount = 0
        var nextSave = start + 30_000_000_000L
        // no tagger file, or it won't load: faces only
        val tagger = if (!taggerFile(applicationContext).exists()) null else try {
            Tagger(applicationContext)
        } catch (e: Exception) {
            Log.w("Memoria", "tagger not loaded", e)
            null
        }
        // no object models, or they won't load: no gate and no pets, and the photos stay unboxed for a later run
        val objects = if (!boxing) null else {
            var detector: ObjectDetector? = null
            try {
                detector = ObjectDetector(applicationContext)
                detector to PetVectors(applicationContext)
            } catch (e: Exception) {
                Log.w("Memoria", "object models not loaded", e)
                detector?.close()
                null
            }
        }
        try {
            FacePipeline(applicationContext).use { pipeline ->
                Log.i("Memoria", "models loaded in ${(System.nanoTime() - start) / 1_000_000} ms")
                for (photo in todo) {
                    if (isStopped) break
                    val uri = ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, photo.id)
                    val tag = tagger?.takeIf { photo.id !in tagged } // a Rescan keeps the vectors
                    var vector: FloatArray? = null
                    var boxes: List<ObjectBoxRow>? = null // null: not boxed, the object models are not here
                    var pets = emptyList<FaceRow>()
                    val (kind, faces) = try {
                        val kind = kindOf(applicationContext, photo, uri)
                        kind to if (kind == PhotoKind.skip) {
                            if (tag != null) vector = tagAlone(tag, photo.id)
                            if (objects != null) boxes = emptyList()
                            emptyList()
                        } else {
                            // Detect on a small decode, full decode only for photos with faces (~half). The tagger reads the same small decode.
                            val source = ImageDecoder.createSource(applicationContext.contentResolver, uri)
                            var w = 0
                            var h = 0
                            val small = loadPhoto(source, 640) { pw, ph -> w = pw; h = ph }
                            if (tag != null) vector = try {
                                tag.vector(tag.input(small))
                            } catch (e: Exception) {
                                Log.w("Memoria", "tag ${photo.id}", e)
                                FloatArray(0)
                            }
                            val lb = Letterbox(small)
                            val found = if (objects == null) pipeline.detect(lb) else {
                                val all = objects.first.detect(lb)
                                boxes = all.map { boxRow(photo.id, it, small) }
                                pets = all.filter { it.cls != PERSON && it.score >= 0.5f }.map { petFace(photo.id, it, small, objects.second, w, h) }
                                // no person box, no face detection; then a face counts only inside a person box (docs/objects.md)
                                val people = all.filter { it.cls == PERSON }.map { it.box }
                                if (people.isEmpty()) emptyList() else pipeline.detect(lb).filter { inPerson(it.box, people) }
                            }
                            if (found.isEmpty()) found else {
                                val image = loadPhoto(source)
                                val kx = image.width.toFloat() / small.width
                                val ky = image.height.toFloat() / small.height
                                found.onEach { f ->
                                    for (a in listOf(f.box, f.landmarks)) for (i in a.indices) a[i] *= if (i % 2 == 0) kx else ky
                                    pipeline.embed(image, f)
                                    f.thumb = faceThumb(image, f.box)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("Memoria", "index ${photo.id}", e)
                        if (tag != null && vector == null) vector = FloatArray(0) // unreadable: not tried again
                        if (objects != null) boxes = emptyList()
                        PhotoKind.error to emptyList()
                    }
                    Db.get(applicationContext).withTransaction {
                        dao.insert(PhotoRow(photo.id, photo.path, kind, objects = boxes?.let { ObjectDetector.VERSION }), faces.map {
                            FaceRow(photoId = photo.id, box = it.box, score = it.score, landmarks = it.landmarks, vector = it.vector!!, quality = it.quality!!, thumb = it.thumb!!)
                        } + pets)
                        boxes?.let { dao.insertBoxes(it) }
                    }
                    vector?.let { dao.insertVector(PhotoVectorRow(photo.id, Tagger.VERSION, it)); tagCount++ }
                    count++
                    if (foreground && count % 10 == 0) notifications.notify(1, notice(base + count, total))
                    // people show up while indexing; saves stay 10x their own time apart, so they never take more than ~10% of the run
                    if (System.nanoTime() > nextSave) {
                        val t = System.nanoTime()
                        placePhotos()
                        savePeople()
                        nextSave = System.nanoTime() + maxOf(30_000_000_000L, 10 * (System.nanoTime() - t))
                    }
                }
            }
            // the user's faces stay whatever the boxes say: answered, merged, or a cover
            val kept = if (objects == null) emptySet() else
                (dao.answers().flatMap { listOf(it.faceId, it.personFace) } + dao.merges().flatMap { listOf(it.face, it.personFace) } + dao.people().mapNotNull { it.coverFaceId }).toSet()
            for ((i, photo) in catchUp.withIndex()) {
                if (isStopped) break
                if (foreground && i % 10 == 0) notifications.notify(1, notice(base + count + i, total, if (boxing) "Scanning photos" else "Tagging photos"))
                val tag = tagger?.takeIf { photo.id !in tagged }
                val box = objects?.takeIf { photo.id !in boxed }
                if (tag == null && box == null) continue
                catchUp(photo.id, tag, box, kept)
                if (tag != null) tagCount++
                catchUpCount++
            }
        } finally {
            tagger?.close()
            objects?.first?.close()
            objects?.second?.close()
            IndexProgress.finish(applicationContext)
            val stop = if (isStopped) ", stopped, reason $stopReason" else ""
            Log.i("Memoria", "indexed $count photos, tagged $tagCount, caught up $catchUpCount in %.0f s$stop".format((System.nanoTime() - start) / 1e9))
        }
        if (isStopped) return Result.success()
        placePhotos()
        return savePeople()
    }

    /**
     * A photo indexed before the tagger or the object models: one 640 decode for both. Its old faces outside every person box are
     * dropped, except [kept] ones, and its pets are added. An unreadable photo gets an empty vector and no boxes, so it isn't tried again.
     */
    private suspend fun catchUp(id: Long, tag: Tagger?, objects: Pair<ObjectDetector, PetVectors>?, kept: Set<Long>) {
        val db = Db.get(applicationContext)
        val dao = db.index()
        var w = 0
        var h = 0
        val small = try {
            loadPhoto(ImageDecoder.createSource(applicationContext.contentResolver, ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, id)), 640) { pw, ph -> w = pw; h = ph }
        } catch (e: Exception) {
            Log.w("Memoria", "catch up $id", e)
            null
        }
        if (tag != null) dao.insertVector(PhotoVectorRow(id, Tagger.VERSION, small?.let {
            try {
                tag.vector(tag.input(it))
            } catch (e: Exception) {
                Log.w("Memoria", "tag $id", e)
                null
            }
        } ?: FloatArray(0)))
        if (objects == null) return
        var boxes = emptyList<ObjectBoxRow>()
        var pets = emptyList<FaceRow>()
        var drop = emptyList<Long>()
        if (small != null) try {
            val found = objects.first.detect(Letterbox(small))
            boxes = found.map { boxRow(id, it, small) }
            pets = found.filter { it.cls != PERSON && it.score >= 0.5f }.map { petFace(id, it, small, objects.second, w, h) }
            val people = found.filter { it.cls == PERSON }.map { toFull(it.box, small, w, h) }
            drop = dao.photoFaces(id).filter { it.id !in kept && !inPerson(it.box, people) }.map { it.id }
        } catch (e: Exception) {
            Log.w("Memoria", "box $id", e)
        }
        db.withTransaction {
            if (drop.isNotEmpty()) dao.deleteFaceIds(drop)
            // ponytail: a new model version boxes again and replaces the pets, so a pet's cover or merge on this photo is lost; keep them then
            dao.deletePets(id)
            dao.insertFaces(pets)
            dao.insertBoxes(boxes)
            dao.setBoxed(id, ObjectDetector.VERSION)
        }
    }

    /** [b] as a DB row, the box as fractions of [small]. */
    private fun boxRow(photoId: Long, b: ObjectBox, small: Bitmap) =
        ObjectBoxRow(photoId = photoId, cls = b.cls, score = b.score, box = FloatArray(4) { i -> b.box[i] / if (i % 2 == 0) small.width else small.height })

    /** A cat or dog box with score 0.5+ as a pet face: the DINOv2 vector, a picture cut from [small], the box in face pixels. */
    private fun petFace(photoId: Long, b: ObjectBox, small: Bitmap, pets: PetVectors, w: Int, h: Int) = FaceRow(
        photoId = photoId, box = toFull(b.box, small, w, h), score = b.score, landmarks = FloatArray(0),
        vector = pets.vector(small, b.box), quality = b.score, thumb = faceThumb(small, b.box), pet = true,
    )

    /** A box in [small]'s pixels to the pixels of loadPhoto's 4096 decode of a [w] x [h] photo, where faces are saved. */
    private fun toFull(b: FloatArray, small: Bitmap, w: Int, h: Int): FloatArray {
        val k = min(1f, 4096f / max(w, h))
        return FloatArray(4) { i -> b[i] * k * if (i % 2 == 0) w.toFloat() / small.width else h.toFloat() / small.height }
    }

    private val towns by lazy { Towns.load(applicationContext) }

    /** A 640 decode for the tagger alone. An unreadable photo gets an empty vector, so it isn't tried again. */
    private fun tagAlone(tagger: Tagger, id: Long): FloatArray = try {
        tagger.vector(tagger.input(loadPhoto(ImageDecoder.createSource(applicationContext.contentResolver, ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, id)), 640)))
    } catch (e: Exception) {
        Log.w("Memoria", "tag $id", e)
        FloatArray(0)
    }

    /** Cuts the face pictures saved at the old 160 px cap again, once per install. Resumes by photo id after a stop. */
    private suspend fun recutThumbs() {
        val prefs = applicationContext.getSharedPreferences("index", Context.MODE_PRIVATE)
        val after = prefs.getLong("recutTo", 0)
        if (after == Long.MAX_VALUE) return
        val start = System.nanoTime()
        val dao = Db.get(applicationContext).index()
        val photos = dao.faceVecs().filter { it.photoId > after }.groupBy { it.photoId }.toSortedMap()
        for ((id, faces) in photos) {
            if (isStopped) return
            try {
                val image = loadPhoto(ImageDecoder.createSource(applicationContext.contentResolver, ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, id)))
                faces.forEach { dao.setThumb(it.id, faceThumb(image, it.box)) }
            } catch (e: Exception) {
                Log.w("Memoria", "recut $id", e)
            }
            prefs.edit().putLong("recutTo", id).apply()
        }
        prefs.edit().putLong("recutTo", Long.MAX_VALUE).apply()
        Log.i("Memoria", "recut ${photos.size} photos in ${(System.nanoTime() - start) / 1_000_000_000} s")
    }

    /** Reads GPS and finds the nearest town for photos saved without one: new photos, and photos indexed before DB v9. */
    private suspend fun placePhotos() {
        val start = System.nanoTime()
        val dao = Db.get(applicationContext).index()
        val ids = dao.unplaced()
        for (id in ids) {
            if (isStopped) break
            val gps = photoGps(applicationContext, ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, id))
            val t = gps?.let { towns.nearest(it.first, it.second) }
            dao.setPlace(id, gps?.first, gps?.second, t?.let { towns.id[it] } ?: 0, t?.let { towns.name[it] })
        }
        Log.i("Memoria", "placed ${ids.size} photos in ${(System.nanoTime() - start) / 1_000_000} ms")
    }

    /**
     * Groups all faces again and replaces the saved people. A new group keeps the id, name and hidden flag of the old person it
     * matches (see [carry]), so an open Person screen and a name typed mid-run stay on the same person.
     */
    private suspend fun savePeople(): Result {
        val start = System.nanoTime()
        val db = Db.get(applicationContext)
        val dao = db.index()
        // one transaction: a name or New person saved during the regroup waits for it instead of being lost
        val (saved, kept) = db.withTransaction {
            val faces = dao.faceVecs()
            val old = dao.people()
            val oldFaces = faces.filter { it.personId != null }.groupBy { it.personId!! }.mapValues { e -> e.value.map { it.id }.toSet() }
            val merges = dao.merges()
            val people = groupFaces(faces.filter { !it.pet }, dao.answers(), merges) + groupPets(faces.filter { it.pet }, merges)
            val taken = mutableSetOf<Long>()
            var next = (old.maxOfOrNull { it.id } ?: 0) + 1
            dao.deletePeople()
            dao.clearPersonIds()
            for (p in people) {
                val ids = p.faces.map { it.id }
                if (ids.isEmpty()) continue // never happens (a person needs faces), keeps IN () off the DB
                val match = carry(ids, old, oldFaces, taken)
                val pid = match?.id ?: next++
                // the cover follows the person only while its face is still with them; else the app picks one again (main face)
                dao.insertPerson(PersonRow(pid, p.main.id, match?.name, match?.hidden ?: false, match?.coverFaceId?.takeIf { it in ids }, match?.pos))
                dao.setPerson(ids, pid)
                val near = p.faces.filter { it.nearSure }.map { it.id }
                if (near.isNotEmpty()) dao.setNearSure(near)
            }
            people.size to taken.size
        }
        peopleSaved.value++
        Log.i("Memoria", "saved $saved people ($kept kept their id) in ${(System.nanoTime() - start) / 1_000_000} ms")
        return Result.success()
    }

    private fun notice(done: Int, total: Int, title: String = "Finding faces"): Notification {
        val base = IndexProgress.base(applicationContext)
        val eta = etaMillis(IndexProgress.startedAt(applicationContext), (done - base).coerceAtLeast(0), total - base)
        return Notification.Builder(applicationContext, "index")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (eta != null) "$done / $total photos · ${formatEta(eta)}" else "$done / $total photos")
            .setProgress(total, done, false)
            .setOngoing(true)
            // a tap opens Settings, where the run shows; the open app gets it in onNewIntent
            .setContentIntent(PendingIntent.getActivity(
                applicationContext, 0,
                Intent(applicationContext, MainActivity::class.java).putExtra("openSettings", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ))
            .build()
    }
}
