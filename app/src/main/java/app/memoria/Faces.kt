package app.memoria

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import java.nio.FloatBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Box is x1, y1, x2, y2. Landmarks are 5 points: eyes, nose, mouth corners. */
class Face(val box: FloatArray, val score: Float, val landmarks: FloatArray) {
    var vector: FloatArray? = null
    var quality: Float? = null
    var thumb: ByteArray? = null
}

/** Decodes with EXIF rotation, longest side capped at [side] (4096 like the bench). */
fun loadPhoto(source: ImageDecoder.Source, side: Int = 4096): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
    val s = side.toFloat() / max(info.size.width, info.size.height)
    if (s < 1) decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // getPixels needs a CPU bitmap
}

/** YOLOv5m-face + AdaFace IR101 + EDifFIQA-T quality, same steps as uniface 4.0 so results match bench/bench.py. */
class FacePipeline(
    private val env: OrtEnvironment,
    detectorModel: String,
    recognizerModel: String,
    qualityModel: String,
    options: OrtSession.SessionOptions,
) : AutoCloseable {
    private val detector = env.createSession(detectorModel, options)
    private val recognizer = env.createSession(recognizerModel, options)
    private val quality = env.createSession(qualityModel, options)

    fun detect(image: Bitmap): List<Face> {
        // Letterbox to 640x640, gray 114 padding, RGB, 0..1
        val scale = min(640f / image.height, 640f / image.width)
        val w = (image.width * scale).toInt()
        val h = (image.height * scale).toInt()
        val padW = (640 - w) / 2
        val padH = (640 - h) / 2
        val boxed = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        Canvas(boxed).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(Bitmap.createScaledBitmap(image, w, h, true), padW.toFloat(), padH.toFloat(), null)
        }
        val pixels = IntArray(640 * 640)
        boxed.getPixels(pixels, 0, 640, 0, 0, 640, 640)
        val input = FloatBuffer.allocate(3 * 640 * 640)
        for (shift in intArrayOf(16, 8, 0)) for (p in pixels) input.put((p shr shift and 0xFF) / 255f)
        input.rewind()

        // Rows: cx, cy, w, h, score, 10 landmark coords, class
        @Suppress("UNCHECKED_CAST")
        val rows = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, 640, 640)).use { tensor ->
            detector.run(mapOf(detector.inputNames.first() to tensor)).use { (it[0].value as Array<Array<FloatArray>>)[0] }
        }
        val kept = mutableListOf<FloatArray>()
        for (r in rows.filter { it[4] >= 0.6f }.sortedByDescending { it[4] }) {
            val box = floatArrayOf(r[0] - r[2] / 2, r[1] - r[3] / 2, r[0] + r[2] / 2, r[1] + r[3] / 2)
            if (kept.size < 750 && kept.none { iou(it, box) > 0.5f }) kept += box + r.copyOfRange(4, 15)
        }
        return kept.map { k ->
            val back = { v: Float, pad: Int -> (v - pad) / scale }
            Face(
                box = floatArrayOf(back(k[0], padW), back(k[1], padH), back(k[2], padW), back(k[3], padH)),
                score = k[4],
                landmarks = FloatArray(10) { i -> back(k[5 + i], if (i % 2 == 0) padW else padH) },
            )
        }
    }

    /** Sets face.vector and face.quality from one aligned crop. */
    fun embed(image: Bitmap, face: Face) {
        val face112 = align(image, face.landmarks)
        // AdaFace wants BGR, (v - 127.5) / 127.5
        val input = FloatBuffer.allocate(3 * 112 * 112)
        for (shift in intArrayOf(0, 8, 16)) for (p in face112) input.put(((p shr shift and 0xFF) - 127.5f) / 127.5f)
        input.rewind()
        @Suppress("UNCHECKED_CAST")
        val v = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, 112, 112)).use { tensor ->
            recognizer.run(mapOf(recognizer.inputNames.first() to tensor)).use { (it[0].value as Array<FloatArray>)[0] }
        }
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        face.vector = FloatArray(v.size) { v[it] / norm }
        // EDifFIQA wants RGB, same scale
        val rgb = FloatBuffer.allocate(3 * 112 * 112)
        for (shift in intArrayOf(16, 8, 0)) for (p in face112) rgb.put(((p shr shift and 0xFF) - 127.5f) / 127.5f)
        rgb.rewind()
        @Suppress("UNCHECKED_CAST")
        face.quality = OnnxTensor.createTensor(env, rgb, longArrayOf(1, 3, 112, 112)).use { tensor ->
            quality.run(mapOf(quality.inputNames.first() to tensor)).use { (it[0].value as Array<FloatArray>)[0][0] }
        }
    }

    override fun close() {
        detector.close()
        recognizer.close()
        quality.close()
    }
}

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
