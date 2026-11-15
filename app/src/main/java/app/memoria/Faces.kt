package app.memoria

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Box is x1, y1, x2, y2. Landmarks are 5 points: eyes, nose, mouth corners. */
class Face(val box: FloatArray, val score: Float, val landmarks: FloatArray) {
    var vector: FloatArray? = null
    var quality: Float? = null
    var thumb: ByteArray? = null
}

/**
 * Decodes with EXIF rotation, longest side capped at [side] (4096 like the bench). [size] gets the photo's own width and height, after [turn].
 * [turn] is the photo's own tag from [plainSource], which hands over a DNG or HEIC as stored, so the picture is turned here.
 */
fun loadPhoto(source: ImageDecoder.Source, side: Int = 4096, turn: Int = 1, size: (Int, Int) -> Unit = { _, _ -> }): Bitmap {
    var w = 0
    var h = 0
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        w = info.size.width
        h = info.size.height
        val s = side.toFloat() / max(w, h)
        if (s < 1) decoder.setTargetSize((w * s).toInt(), (h * s).toInt())
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // getPixels needs a CPU bitmap
    }
    if (turn in 5..8) size(h, w) else size(w, h)
    return bitmap.turnedBy(turn)
}

/** [loadPhoto] of a MediaStore photo, the way the index reads it: a DNG or HEIC is turned by its own tag. */
fun loadPhoto(resolver: ContentResolver, uri: Uri, side: Int = 4096, size: (Int, Int) -> Unit = { _, _ -> }): Bitmap {
    val (source, turn) = plainSource(resolver, uri)
    return loadPhoto(source, side, turn, size)
}

/**
 * The source to decode [uri] with, and the tag to turn the result by. A HEIC's own turn and mirror are cleared
 * out of a copy of its bytes, so the decoder returns the stored pixels and [blankHeicTurn]'s tag turns them once.
 */
fun plainSource(resolver: ContentResolver, uri: Uri, type: String? = photoType(resolver, uri)): Pair<ImageDecoder.Source, Int> {
    if (type == "image/heic" || type == "image/heif") {
        val bytes = runCatching { resolver.openInputStream(uri)?.readBytes() }.getOrNull()
            ?: return ImageDecoder.createSource(resolver, uri) to 1 // no bytes read, so nothing was cleared: let the decoder keep its own turn
        return ImageDecoder.createSource(ByteBuffer.wrap(bytes)) to blankHeicTurn(bytes)
    }
    return ImageDecoder.createSource(resolver, uri) to photoTurn(resolver, uri, type)
}

/** The photo's own turn tag (1-8): a DNG's TIFF tag or a HEIC's boxes, and 1 for any other file, which Android turns itself. */
fun photoTurn(resolver: ContentResolver, uri: Uri, type: String? = photoType(resolver, uri)): Int = when (type) {
    "image/x-adobe-dng" ->
        runCatching { resolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } }.getOrDefault(1)
    "image/heic", "image/heif" ->
        runCatching { resolver.openInputStream(uri)!!.use { blankHeicTurn(it.readNBytes(65536)) } }.getOrDefault(1)
    else -> 1
}

private fun photoType(resolver: ContentResolver, uri: Uri): String? =
    runCatching { resolver.getType(uri) }.getOrNull() ?: fileType(uri)

/**
 * Clears the turn and mirror notes of the HEIC file in [bytes] and returns them as an EXIF-style tag (1-8), plus the mirror axis it used.
 * Phones' decoders drop the mirror or turn twice (bugs.md "Mirrored HEIC"), so the pixels are decoded as stored and turned once here.
 */
fun blankHeic(bytes: ByteArray): Pair<Int, Int> {
    val end = min(bytes.size, 65536)
    val free = byteArrayOf(0x66, 0x72, 0x65, 0x65) // "free"
    // ipco child index -> the box's angle or axis, and the first of each for a file with no pitm entry
    val irot = HashMap<Int, Int>()
    val imir = HashMap<Int, Int>()
    var firstIrot = -1
    var firstImir = -1
    for (ipco in boxesOf(bytes, end, "ipco")) {
        var at = ipco + 8
        val stop = boxStop(bytes, ipco, end)
        var index = 1
        while (at + 8 <= stop) {
            val size = u32(bytes, at, false)
            if (size < 8) break
            val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
            if ((type == "irot" || type == "imir") && at + 9 <= stop) {
                val payload = bytes[at + 8].toInt() and 0xff
                if (type == "irot") {
                    irot[index] = payload and 3
                    if (firstIrot < 0) firstIrot = payload and 3
                } else {
                    imir[index] = payload and 1
                    if (firstImir < 0) firstImir = payload and 1
                }
                free.copyInto(bytes, at + 4) // same size, so the property indexes stay
            }
            at += size
            index++
        }
    }
    var primary = -1
    for (pitm in boxesOf(bytes, end, "pitm")) {
        if (pitm + 12 > boxStop(bytes, pitm, end)) continue
        primary = if (u8(bytes, pitm + 8) == 0) u16(bytes, pitm + 12, false) else u32(bytes, pitm + 12, false)
        break
    }
    var primaryAngle = -1
    var primaryAxis = -1
    for (ipma in boxesOf(bytes, end, "ipma")) {
        val stop = boxStop(bytes, ipma, end)
        val version = u8(bytes, ipma + 8)
        val wide = (u8(bytes, ipma + 11) and 1) != 0 // flags' low bit: 2-byte associations
        var at = ipma + 12
        if (at + 4 > stop) continue
        val entries = u32(bytes, at, false)
        at += 4
        for (e in 0 until entries) {
            if (at >= stop) break
            val id = if (version == 0) u16(bytes, at, false) else u32(bytes, at, false)
            at += if (version == 0) 2 else 4
            if (at >= stop) break
            val count = u8(bytes, at)
            at++
            for (a in 0 until count) {
                if (at + (if (wide) 2 else 1) > stop) break
                val v = if (wide) u16(bytes, at, false) else u8(bytes, at)
                if (v < 0) break
                val index = v and if (wide) 0x7fff else 0x7f
                if (id == primary) {
                    irot[index]?.let { primaryAngle = it }
                    imir[index]?.let { primaryAxis = it }
                }
                // A left-over index of a cleared property is skipped by readers only when it is not essential
                if (index in irot || index in imir) bytes[at] = (bytes[at].toInt() and 0x7f).toByte()
                at += if (wide) 2 else 1
            }
        }
    }
    val angle = if (primaryAngle >= 0) primaryAngle else firstIrot
    val axis = if (primaryAxis >= 0) primaryAxis else firstImir
    val exif = blankExifTurn(bytes)
    if (angle < 0 && axis < 0) return (if (exif in 1..8) exif else 1) to -1
    return (HEIC_TURNS[(if (angle < 0) 0 else angle * 90) to axis] ?: 1) to axis
}

fun blankHeicTurn(bytes: ByteArray): Int = blankHeic(bytes).first

/** The mirror axis of a HEIC's own boxes (0 or 1), and -1 when it has none or the file can't be read. */
fun heicMirrorAxis(resolver: ContentResolver, uri: Uri): Int =
    runCatching { resolver.openInputStream(uri)!!.use { blankHeic(it.readNBytes(65536)).second } }.getOrDefault(-1)

/** True when this phone's HEIC decoder shows the probe file (EXIF 5) without its mirror. */
fun decoderDropsMirror(context: Context): Boolean = try {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.assets, "mirror_probe.heic")) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSampleSize(4)
    }
    val p = bitmap.getPixel(bitmap.width / 4, bitmap.height / 4) // upright: the red top-left corner
    !(Color.red(p) > 150 && Color.green(p) < 120 && Color.blue(p) < 120)
} catch (e: Exception) {
    false // can't tell, so leave the user's data alone
}

/**
 * Index in [new] -> the id of the old face found at the same place. An old box is also tried mirrored by [axis],
 * as an old decode on a phone that dropped a HEIC's mirror found it. Boxes are in pixels of a [width] x [height] picture.
 */
fun matchFaces(old: List<Pair<Long, FloatArray>>, new: List<FloatArray>, axis: Int, width: Int, height: Int): Map<Int, Long> {
    // every pair at 0.5 IoU or better, highest first, then each old face goes to one new face only
    val pairs = new.withIndex().flatMap { (n, box) ->
        old.withIndex().mapNotNull { (o, face) ->
            val score = max(iou(box, face.second), iou(box, mirrored(face.second, axis, width, height)))
            if (score >= 0.5f) Triple(n, o, score) else null
        }
    }.sortedByDescending { it.third }
    val out = HashMap<Int, Long>()
    val taken = HashSet<Int>()
    for ((n, o, _) in pairs) {
        if (n in out || o in taken) continue
        out[n] = old[o].first
        taken += o
    }
    return out
}

/** [b] turned into the same picture as its mirror: axis 0 top-bottom, axis 1 left-right, -1 as it is. */
private fun mirrored(b: FloatArray, axis: Int, width: Int, height: Int): FloatArray = when (axis) {
    0 -> floatArrayOf(b[0], height - b[3], b[2], height - b[1])
    1 -> floatArrayOf(width - b[2], b[1], width - b[0], b[3])
    else -> b
}

/** The start offsets of the boxes of [type] in the first [end] bytes of [bytes]. */
private fun boxesOf(bytes: ByteArray, end: Int, type: String): List<Int> {
    val t = type.toByteArray(Charsets.US_ASCII)
    val out = ArrayList<Int>()
    for (typeAt in 4 until end - 3) {
        if (bytes[typeAt] != t[0] || bytes[typeAt + 1] != t[1] || bytes[typeAt + 2] != t[2] || bytes[typeAt + 3] != t[3]) continue
        val start = typeAt - 4
        val size = u32(bytes, start, false)
        if (size >= 8 && size <= bytes.size - start) out += start
    }
    return out
}

/** Where the box at [start] ends, at most [end]. */
private fun boxStop(bytes: ByteArray, start: Int, end: Int): Int {
    val size = u32(bytes, start, false)
    return if (size < 8) start else min(start + size, end)
}

/** The tag of each (rotation counter-clockwise, mirror axis or -1) pair a HEIC file can hold. */
private val HEIC_TURNS = mapOf(
    (0 to -1) to 1, (0 to 0) to 4, (0 to 1) to 2,
    (90 to -1) to 8, (90 to 0) to 5, (90 to 1) to 7,
    (180 to -1) to 3, (180 to 0) to 2, (180 to 1) to 4,
    (270 to -1) to 6, (270 to 0) to 7, (270 to 1) to 5,
)

/** Clears IFD0's orientation tag (0x0112) in [bytes] and returns its old value, 1 when there is none. */
private fun blankExifTurn(bytes: ByteArray): Int {
    var s = 0
    while (s <= bytes.size - 8) {
        if (bytes[s] == 'E'.code.toByte() && bytes[s + 1] == 'x'.code.toByte() && bytes[s + 2] == 'i'.code.toByte() &&
            bytes[s + 3] == 'f'.code.toByte() && bytes[s + 4] == 0.toByte() && bytes[s + 5] == 0.toByte()
        ) {
            val t = s + 6
            val little = bytes[t] == 'I'.code.toByte() && bytes[t + 1] == 'I'.code.toByte()
            val big = bytes[t] == 'M'.code.toByte() && bytes[t + 1] == 'M'.code.toByte()
            if ((little || big) && u16(bytes, t + 2, little) == 42) {
                val ifd = t + u32(bytes, t + 4, little)
                val n = u16(bytes, ifd, little)
                for (k in 0 until n) {
                    val e = ifd + 2 + 12 * k
                    if (e + 12 > bytes.size) break
                    if (u16(bytes, e, little) != 0x0112) continue
                    val value = u16(bytes, e + 8, little)
                    bytes[e + 8] = if (little) 1 else 0
                    bytes[e + 9] = if (little) 0 else 1
                    return value
                }
            }
        }
        s++
    }
    return 1
}

/** A byte at [at], and -1 when it is outside [b]. */
private fun u8(b: ByteArray, at: Int): Int = if (at < 0 || at >= b.size) -1 else b[at].toInt() and 0xff

/** A 16-bit value at [at], and -1 when it is outside [b]. */
private fun u16(b: ByteArray, at: Int, little: Boolean): Int {
    if (at < 0 || at + 2 > b.size) return -1
    return if (little) (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
    else ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)
}

/** A 32-bit value at [at], and -1 when it is outside [b]. */
private fun u32(b: ByteArray, at: Int, little: Boolean): Int {
    if (at < 0 || at + 4 > b.size) return -1
    return if (little) u16(b, at, true) or (u16(b, at + 2, true) shl 16)
    else (u16(b, at, false) shl 16) or u16(b, at + 2, false)
}

/** The type of a `file://` URI, which the resolver has no header for (Dev tools' media check). */
private fun fileType(uri: Uri): String? = when (uri.scheme) {
    "file" -> when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
        "dng" -> "image/x-adobe-dng"
        "heic", "heif" -> "image/heic"
        else -> null
    }
    else -> null
}

/** The picture turned by an EXIF-style [tag] (1-8). Anything else leaves it as it is. */
fun Bitmap.turnedBy(tag: Int): Bitmap {
    val m = Matrix()
    when (tag) {
        2 -> m.postScale(-1f, 1f)
        3 -> m.postRotate(180f)
        4 -> m.postScale(1f, -1f)
        5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
        6 -> m.postRotate(90f)
        7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        8 -> m.postRotate(270f)
        else -> return this
    }
    val hardware = config == Bitmap.Config.HARDWARE
    val flat = if (hardware) copy(Bitmap.Config.ARGB_8888, false) else this
    val turned = Bitmap.createBitmap(flat, 0, 0, flat.width, flat.height, m, true)
    return if (hardware) turned.copy(Bitmap.Config.HARDWARE, false) else turned
}

/** [image] letterboxed to 640x640, gray 114 padding, RGB, 0..1: the input of YOLOv5m-face and YOLO26. A model's x is (x - padW) / scale in the image. */
class Letterbox(image: Bitmap) {
    val scale = min(640f / image.height, 640f / image.width)
    val padW = (640 - (image.width * scale).toInt()) / 2
    val padH = (640 - (image.height * scale).toInt()) / 2
    val input = FloatArray(3 * 640 * 640)

    init {
        val boxed = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        Canvas(boxed).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(Bitmap.createScaledBitmap(image, (image.width * scale).toInt(), (image.height * scale).toInt(), true), padW.toFloat(), padH.toFloat(), null)
        }
        val pixels = IntArray(640 * 640)
        boxed.getPixels(pixels, 0, 640, 0, 0, 640, 640)
        var i = 0
        for (shift in intArrayOf(16, 8, 0)) for (p in pixels) input[i++] = (p shr shift and 0xFF) / 255f
    }
}

private var env: Environment? = null

/** One LiteRT environment for the app. Its compile cache keeps the NPU builds in the app's cache dir, so only the first load builds them. */
@Synchronized
fun liteEnv(context: Context): Environment = env ?: context.applicationContext.let {
    Environment.create(it, BuiltinNpuAcceleratorProvider(it), emptyMap(), true)
}.also { env = it }

/** One model with its input and output buffers, on the first of [accelerators] the phone has and the model runs on. */
class Net(env: Environment, file: File, accelerators: List<Accelerator>) : AutoCloseable {
    // Only Qualcomm's NPU plugin is bundled; on other chips an NPU load quietly runs on the CPU (bugs.md).
    private val model = open(env, file, accelerators.filter { it in env.getAvailableAccelerators() && (it != Accelerator.NPU || Build.SOC_MANUFACTURER == "QTI") })
    private val inputs = model.createInputBuffers()
    private val outputs = model.createOutputBuffers()

    fun run(input: FloatArray): List<FloatArray> {
        inputs[0].writeFloat(input)
        model.run(inputs, outputs)
        return outputs.map { it.readFloat() }
    }

    override fun close() {
        (inputs + outputs).forEach { it.close() }
        model.close()
    }
}

private fun open(env: Environment, file: File, accelerators: List<Accelerator>): CompiledModel {
    val on = accelerators.first()
    val options = CompiledModel.Options(on).apply {
        if (on == Accelerator.NPU) qualcommOptions = CompiledModel.QualcommOptions(htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.BURST)
        if (on == Accelerator.CPU) cpuOptions = CompiledModel.CpuOptions(numThreads = 4) // LiteRT's default is ~3x slower than ONNX Runtime was
    }
    return try {
        CompiledModel.create(file.path, options, env).also { AppLog.i("${file.name} on $on") }
    } catch (e: Exception) {
        if (accelerators.size == 1) throw e
        AppLog.w("${file.name} failed on $on", e)
        open(env, file, accelerators.drop(1))
    }
}

/**
 * YOLOv5m-face + AdaFace IR101 + EDifFIQA-T quality, same steps as uniface 4.0 so results match bench/bench.py.
 * Reads the .tflite files in <app files>/models. The emulator runs on the CPU: its software GPU gives every face after some point the same result (bugs.md).
 */
class FacePipeline(
    context: Context,
    accelerators: List<Accelerator> = if (Build.HARDWARE == "ranchu") listOf(Accelerator.CPU) else listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU),
) : AutoCloseable {
    private val models = File(context.getExternalFilesDir(null), "models")
    private val detector = Net(liteEnv(context), File(models, "detector_raw.tflite"), accelerators)
    private val recognizer = Net(liteEnv(context), File(models, "recognizer.tflite"), accelerators)
    private val quality = Net(liteEnv(context), File(models, "quality.tflite"), accelerators)

    fun detect(lb: Letterbox): List<Face> {
        // 3 head grids (80, 40, 20 cells a side), each cell 3 anchors x 16: x, y, w, h, score, 10 landmark coords, class.
        // Decoded here like bench/tflite.py decode: in the model it is 5-D, which the GPU can't run and the NPU builds in ~40 s.
        val rows = mutableListOf<FloatArray>()
        for ((g, out) in detector.run(lb.input).sortedByDescending { it.size }.withIndex()) {
            val side = 80 shr g
            val stride = 8 shl g
            for (a in 0 until 3) for (y in 0 until side) for (x in 0 until side) {
                val o = (y * side + x) * 48 + a * 16
                val score = sigmoid(out[o + 4])
                if (score < 0.6f) continue
                val aw = ANCHORS[g][2 * a]
                val ah = ANCHORS[g][2 * a + 1]
                rows += floatArrayOf(
                    (sigmoid(out[o]) * 2 - 0.5f + x) * stride,
                    (sigmoid(out[o + 1]) * 2 - 0.5f + y) * stride,
                    (sigmoid(out[o + 2]) * 2).pow(2) * aw,
                    (sigmoid(out[o + 3]) * 2).pow(2) * ah,
                    score,
                ) + FloatArray(10) { i -> if (i % 2 == 0) out[o + 5 + i] * aw + x * stride else out[o + 5 + i] * ah + y * stride }
            }
        }
        val kept = mutableListOf<FloatArray>()
        for (r in rows.sortedByDescending { it[4] }) {
            val box = floatArrayOf(r[0] - r[2] / 2, r[1] - r[3] / 2, r[0] + r[2] / 2, r[1] + r[3] / 2)
            if (kept.size < 750 && kept.none { iou(it, box) > 0.5f }) kept += box + r.copyOfRange(4, 15)
        }
        return kept.map { k ->
            val back = { v: Float, pad: Int -> (v - pad) / lb.scale }
            Face(
                box = floatArrayOf(back(k[0], lb.padW), back(k[1], lb.padH), back(k[2], lb.padW), back(k[3], lb.padH)),
                score = k[4],
                landmarks = FloatArray(10) { i -> back(k[5 + i], if (i % 2 == 0) lb.padW else lb.padH) },
            )
        }
    }

    /** Sets face.vector and face.quality from one aligned crop. */
    fun embed(image: Bitmap, face: Face) {
        val face112 = align(image, face.landmarks)
        // AdaFace wants BGR, (v - 127.5) / 127.5
        val input = FloatBuffer.allocate(3 * 112 * 112)
        for (shift in intArrayOf(0, 8, 16)) for (p in face112) input.put(((p shr shift and 0xFF) - 127.5f) / 127.5f)
        val v = recognizer.run(input.array())[0]
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        face.vector = FloatArray(v.size) { v[it] / norm }
        // EDifFIQA wants RGB, same scale
        val rgb = FloatBuffer.allocate(3 * 112 * 112)
        for (shift in intArrayOf(16, 8, 0)) for (p in face112) rgb.put(((p shr shift and 0xFF) - 127.5f) / 127.5f)
        face.quality = quality.run(rgb.array())[0][0]
    }

    override fun close() {
        detector.close()
        recognizer.close()
        quality.close()
    }
}

// YOLOv5-face anchors, w and h for each of 3 per head (stride 8, 16, 32)
private val ANCHORS = arrayOf(
    floatArrayOf(4f, 5f, 8f, 10f, 13f, 16f),
    floatArrayOf(23f, 29f, 43f, 55f, 73f, 105f),
    floatArrayOf(146f, 217f, 231f, 300f, 335f, 433f),
)

private fun sigmoid(x: Float) = 1 / (1 + exp(-x))

// Standard ArcFace 112x112 positions of the 5 landmarks
private val REFERENCE = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)

/** Like cv2.warpAffine with a least-squares similarity transform: bilinear, black outside the photo. */
fun align(image: Bitmap, lm: FloatArray): IntArray {
    var sx = 0f; var sy = 0f; var dx = 0f; var dy = 0f
    for (i in 0 until 5) {
        sx += lm[2 * i] / 5; sy += lm[2 * i + 1] / 5
        dx += REFERENCE[2 * i] / 5; dy += REFERENCE[2 * i + 1] / 5
    }
    var dot = 0f; var cross = 0f; var len = 0f
    for (i in 0 until 5) {
        val x = lm[2 * i] - sx; val y = lm[2 * i + 1] - sy
        val u = REFERENCE[2 * i] - dx; val v = REFERENCE[2 * i + 1] - dy
        dot += x * u + y * v; cross += x * v - y * u; len += x * x + y * y
    }
    // photo -> face: u = a*x - b*y + tx, v = b*x + a*y + ty
    val a = dot / len; val b = cross / len
    val tx = dx - (a * sx - b * sy); val ty = dy - (b * sx + a * sy)
    val d = a * a + b * b

    // Read only the photo area the face comes from
    val corners = listOf(0f to 0f, 111f to 0f, 0f to 111f, 111f to 111f).map { (u, v) ->
        (a * (u - tx) + b * (v - ty)) / d to (-b * (u - tx) + a * (v - ty)) / d
    }
    val left = (corners.minOf { it.first }.toInt() - 2).coerceIn(0, image.width - 1)
    val top = (corners.minOf { it.second }.toInt() - 2).coerceIn(0, image.height - 1)
    val right = (corners.maxOf { it.first }.toInt() + 3).coerceIn(left + 1, image.width)
    val bottom = (corners.maxOf { it.second }.toInt() + 3).coerceIn(top + 1, image.height)
    val rw = right - left
    val region = IntArray(rw * (bottom - top))
    image.getPixels(region, 0, rw, left, top, rw, bottom - top)
    val px = { x: Int, y: Int -> if (x in left until right && y in top until bottom) region[(y - top) * rw + x - left] else 0 }

    return IntArray(112 * 112) { i ->
        val u = (i % 112) - tx; val v = (i / 112) - ty
        val x = (a * u + b * v) / d; val y = (-b * u + a * v) / d
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val p00 = px(x0, y0); val p10 = px(x0 + 1, y0); val p01 = px(x0, y0 + 1); val p11 = px(x0 + 1, y0 + 1)
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val c = { p: Int -> (p shr shift and 0xFF).toFloat() }
            val top2 = c(p00) * (1 - fx) + c(p10) * fx
            val bottom2 = c(p01) * (1 - fx) + c(p11) * fx
            out = out or ((top2 * (1 - fy) + bottom2 * fy).roundToInt().coerceIn(0, 255) shl shift)
        }
        out
    }
}

// Same as uniface non_max_suppression: areas count the +1 pixel
private fun iou(a: FloatArray, b: FloatArray): Float {
    val w = max(0f, min(a[2], b[2]) - max(a[0], b[0]) + 1)
    val h = max(0f, min(a[3], b[3]) - max(a[1], b[1]) + 1)
    val inter = w * h
    return inter / ((a[2] - a[0] + 1) * (a[3] - a[1] + 1) + (b[2] - b[0] + 1) * (b[3] - b[1] + 1) - inter)
}
