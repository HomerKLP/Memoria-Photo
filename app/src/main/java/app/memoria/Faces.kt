package app.memoria

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File
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

/** Decodes with EXIF rotation, longest side capped at [side] (4096 like the bench). */
fun loadPhoto(source: ImageDecoder.Source, side: Int = 4096): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
    val s = side.toFloat() / max(info.size.width, info.size.height)
    if (s < 1) decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // getPixels needs a CPU bitmap
}

private var env: Environment? = null

/** One LiteRT environment for the app. Its compile cache keeps the NPU builds in the app's cache dir, so only the first load builds them. */
@Synchronized
private fun liteEnv(context: Context): Environment = env ?: context.applicationContext.let {
    Environment.create(it, BuiltinNpuAcceleratorProvider(it), emptyMap(), true)
}.also { env = it }

/** One model with its input and output buffers, on the first of [accelerators] the phone has and the model runs on. */
private class Net(env: Environment, file: File, accelerators: List<Accelerator>) : AutoCloseable {
    private val model = open(env, file, accelerators.filter { it in env.getAvailableAccelerators() })
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
        CompiledModel.create(file.path, options, env).also { Log.i("Memoria", "${file.name} on $on") }
    } catch (e: Exception) {
        if (accelerators.size == 1) throw e
        Log.w("Memoria", "${file.name} failed on $on", e)
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

        // 3 head grids (80, 40, 20 cells a side), each cell 3 anchors x 16: x, y, w, h, score, 10 landmark coords, class.
        // Decoded here like bench/tflite.py decode: in the model it is 5-D, which the GPU can't run and the NPU builds in ~40 s.
        val rows = mutableListOf<FloatArray>()
        for ((g, out) in detector.run(input.array()).sortedByDescending { it.size }.withIndex()) {
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
