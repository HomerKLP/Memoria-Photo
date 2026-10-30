package app.memoria

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.google.ai.edge.litert.Accelerator
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// COCO classes the app keeps
const val PERSON = 0
const val CAT = 15
const val DOG = 16

/** A YOLO26 box: COCO [cls], [score], and x1, y1, x2, y2 in the image's pixels. */
class ObjectBox(val cls: Int, val score: Float, val box: FloatArray)

/** <app files>/models/objects.tflite and pets.tflite: the index boxes photos only while both are there. */
fun objectsFiles(context: Context) = File(context.getExternalFilesDir(null), "models").let { listOf(File(it, "objects.tflite"), File(it, "pets.tflite")) }

/** The strict rule (`docs/objects.md`): a face counts only when at least half of it is inside one of the [people] boxes. Both in the same units. */
internal fun inPerson(face: FloatArray, people: List<FloatArray>): Boolean = people.any { b ->
    val w = min(face[2], b[2]) - max(face[0], b[0])
    val h = min(face[3], b[3]) - max(face[1], b[1])
    max(w, 0f) * max(h, 0f) >= 0.5f * (face[2] - face[0]) * (face[3] - face[1])
}

/** YOLO26-M (`docs/objects.md`): person, cat and dog boxes. Reads <app files>/models/objects.tflite. */
class ObjectDetector(
    context: Context,
    accelerators: List<Accelerator> = if (Build.HARDWARE == "ranchu") listOf(Accelerator.CPU) else listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU),
) : AutoCloseable {
    private val net = Net(liteEnv(context), objectsFiles(context)[0], accelerators)

    fun detect(lb: Letterbox): List<ObjectBox> = yoloBoxes(net.run(lb.input)[0]).map { b ->
        ObjectBox(b.cls, b.score, FloatArray(4) { k -> (b.box[k] - if (k % 2 == 0) lb.padW else lb.padH) / lb.scale })
    }

    override fun close() = net.close()

    companion object {
        /** Saved on each photo with its boxes. Bump it with a new objects or pets file, and the next run boxes every photo again. */
        const val VERSION = 3 // 2: pet vectors from the squashed box and Noodle's layer; 3: pets from boxes 0.7+, no duplicate boxes
    }
}

/**
 * YOLO26's raw output, 84 x 8400 (a column per row: cx, cy, w, h, 80 scores), to person, cat and dog boxes in letterbox pixels.
 * Score 0.05+, duplicates dropped per class at IoU 0.7, like bench/objects.py Yolo26.
 */
internal fun yoloBoxes(out: FloatArray): List<ObjectBox> {
    val n = out.size / 84
    val found = mutableListOf<ObjectBox>()
    for (cls in intArrayOf(PERSON, CAT, DOG)) {
        val kept = mutableListOf<ObjectBox>()
        for (i in (0 until n).filter { out[(4 + cls) * n + it] >= 0.05f }.sortedByDescending { out[(4 + cls) * n + it] }) {
            val cx = out[i]; val cy = out[n + i]; val w = out[2 * n + i]; val h = out[3 * n + i]
            val box = floatArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            if (kept.none { overlap(it.box, box) > 0.7f }) kept += ObjectBox(cls, out[(4 + cls) * n + i], box)
        }
        found += kept
    }
    return found
}

// OpenCV's NMS IoU: no +1 pixel, unlike the face detector's
private fun overlap(a: FloatArray, b: FloatArray): Float {
    val inter = max(0f, min(a[2], b[2]) - max(a[0], b[0])) * max(0f, min(a[3], b[3]) - max(a[1], b[1]))
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)
}

/**
 * DINOv2-S plus Noodle Gallery's pet layer (`docs/objects.md`): a cat or dog box -> its normalized 512 vector, to tell pets apart.
 * Reads <app files>/models/pets.tflite. No NPU: DINOv2's vectors there are only ~0.93 like the CPU's (GPU 0.996), and pets are few.
 */
class PetVectors(
    context: Context,
    accelerators: List<Accelerator> = if (Build.HARDWARE == "ranchu") listOf(Accelerator.CPU) else listOf(Accelerator.GPU, Accelerator.CPU),
) : AutoCloseable {
    private val net = Net(liteEnv(context), objectsFiles(context)[1], accelerators)

    /** [box] cut from [image] and squashed to 224x224 whole (a middle square loses a lying cat's head and tail), RGB, ImageNet mean and std. */
    fun vector(image: Bitmap, box: FloatArray): FloatArray {
        val x1 = box[0].toInt().coerceIn(0, image.width - 1)
        val y1 = box[1].toInt().coerceIn(0, image.height - 1)
        var b = Bitmap.createBitmap(image, x1, y1, (box[2].toInt().coerceAtMost(image.width) - x1).coerceAtLeast(1), (box[3].toInt().coerceAtMost(image.height) - y1).coerceAtLeast(1))
        // each side halved down to under 448 first: averaged, not sampled, as in Tagger.input
        while (max(b.width, b.height) >= 448) b = Bitmap.createScaledBitmap(b, if (b.width >= 448) b.width / 2 else b.width, if (b.height >= 448) b.height / 2 else b.height, true)
        b = Bitmap.createScaledBitmap(b, 224, 224, true)
        val pixels = IntArray(224 * 224)
        b.getPixels(pixels, 0, 224, 0, 0, 224, 224)
        val input = FloatArray(3 * 224 * 224)
        var i = 0
        for ((c, shift) in intArrayOf(16, 8, 0).withIndex()) for (p in pixels) input[i++] = ((p shr shift and 0xFF) / 255f - MEAN[c]) / STD[c]
        val v = net.run(input)[0]
        val norm = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(v.size) { v[it] / norm }
    }

    override fun close() = net.close()
}

private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
