package app.memoria

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.google.ai.edge.litert.Accelerator
import java.io.File
import kotlin.math.sqrt

/** <app files>/models/tagger.tflite: the index tags photos only while it is there. */
fun taggerFile(context: Context) = File(File(context.getExternalFilesDir(null), "models"), "tagger.tflite")

/** SigLIP2 B/16 vision side (`docs/tagger.md`): a photo -> its normalized 768 vector, for tags and search. Reads <app files>/models/tagger.tflite. */
class Tagger(
    context: Context,
    accelerators: List<Accelerator> = if (Build.HARDWARE == "ranchu") listOf(Accelerator.CPU) else listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU),
) : AutoCloseable {
    private val net = Net(liteEnv(context), taggerFile(context), accelerators)

    /** The whole photo squashed to 224x224, RGB, -1..1. Halved first, so a big photo is averaged, not sampled (the bench resizes bicubic with antialias). */
    fun input(image: Bitmap): FloatArray {
        var b = image
        while (b.width >= 448 || b.height >= 448) {
            b = Bitmap.createScaledBitmap(b, if (b.width >= 448) b.width / 2 else b.width, if (b.height >= 448) b.height / 2 else b.height, true)
        }
        b = Bitmap.createScaledBitmap(b, 224, 224, true)
        val pixels = IntArray(224 * 224)
        b.getPixels(pixels, 0, 224, 0, 0, 224, 224)
        val input = FloatArray(3 * 224 * 224)
        var i = 0
        for (shift in intArrayOf(16, 8, 0)) for (p in pixels) input[i++] = (p shr shift and 0xFF) / 127.5f - 1
        return input
    }

    fun vector(input: FloatArray): FloatArray {
        val v = net.run(input)[0]
        val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    override fun close() = net.close()

    companion object {
        /** Saved with each vector. Bump it with a new model file, and the next run tags every photo again. */
        const val VERSION = 1
    }
}
