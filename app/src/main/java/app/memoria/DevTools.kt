package app.memoria

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first

/** Counts for the Numbers block on Dev tools. */
class DevNumbers(
    val camera: Int, val other: Int, val skip: Int,
    val sure: Int, val unsure: Int,
    val named: Int, val unnamed: Int, val hidden: Int,
    val withTown: Int, val withoutTown: Int,
)

/** Reads the counts from the DB. Faces are the ones in people: sure and unsure as the app counts them. */
suspend fun devNumbers(dao: IndexDao): DevNumbers {
    val kinds = dao.kinds().first()
    val faces = dao.taggedFaces().map { it.toVec().sure }
    val people = dao.people()
    val shown = people.filter { !it.hidden }
    val named = shown.count { !it.name.isNullOrBlank() }
    val withTown = dao.placed().size
    return DevNumbers(
        camera = kinds[PhotoKind.camera] ?: 0, other = kinds[PhotoKind.other] ?: 0, skip = kinds[PhotoKind.skip] ?: 0,
        sure = faces.count { it }, unsure = faces.count { !it },
        named = named, unnamed = shown.size - named, hidden = people.size - shown.size,
        withTown = withTown, withoutTown = dao.photoIds().size - withTown,
    )
}

/** Always four lines, so the block keeps its height while [n] loads (null). */
fun devNumbersText(n: DevNumbers?) =
    if (n == null) "Photos  …\nFaces   …\nPeople  …\nPlaces  …"
    else "Photos  camera ${n.camera} · other ${n.other} · skip ${n.skip}\n" +
        "Faces   sure ${n.sure} · unsure ${n.unsure}\n" +
        "People  named ${n.named} · unnamed ${n.unnamed} · hidden ${n.hidden}\n" +
        "Places  with town ${n.withTown} · without ${n.withoutTown}"

/** Dev tools page from the mockup: rows like Settings, then the numbers and the log. A busy run greys the Run rows. */
@Composable
fun DevToolsContent(
    busy: Boolean,
    numbers: DevNumbers?,
    log: String,
    preview: Pair<Bitmap, List<Face>>? = null,
    onBack: () -> Unit = {},
    onRunCpu: () -> Unit = {},
    onRunGpu: () -> Unit = {},
    onRunNpu: () -> Unit = {},
    onIndexTest: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        TopBar("Dev tools", "Debug builds only", onBack = onBack)
        SectionHead("Models")
        SettingRow(R.drawable.ic_play, "Run on CPU", "Time the models on the test photos", onRunCpu.takeIf { !busy })
        SettingRow(R.drawable.ic_play, "Run on GPU", "Same photos, on the GPU", onRunGpu.takeIf { !busy })
        SettingRow(R.drawable.ic_bolt, "Run on NPU", "Same photos, on the NPU", onRunNpu.takeIf { !busy })
        SectionHead("Index")
        SettingRow(R.drawable.ic_folder, "Index test folder", "Pictures/MemoriaTest only", onIndexTest)
        SectionHead("Numbers")
        Mono(devNumbersText(numbers), minLines = 4)
        SectionHead("Log")
        preview?.let { (bitmap, faces) ->
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth())
                Canvas(Modifier.matchParentSize()) {
                    val s = size.width / bitmap.width
                    for (f in faces) {
                        drawRect(Color.Green, Offset(f.box[0] * s, f.box[1] * s), Size((f.box[2] - f.box[0]) * s, (f.box[3] - f.box[1]) * s), style = Stroke(3f))
                    }
                }
            }
        }
        Mono(log)
    }
}

/** Mockup `.log`: small muted monospace text, 16 dp from the edge. */
@Composable
private fun Mono(text: String, minLines: Int = 1) =
    Text(
        text, Modifier.padding(horizontal = 16.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = minLines,
    )
