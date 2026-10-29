package app.memoria

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val FULL_TEXTS = "https://github.com/HomerKLP/Memoria-Photo/blob/master/THIRD_PARTY.md"

/** Licenses from Settings > About. Opens the full texts in the browser, the app has no internet permission. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    LicensesContent(onBack) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FULL_TEXTS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Licenses rows per the mockup. Same list as `THIRD_PARTY.md`. */
@Composable
fun LicensesContent(onBack: () -> Unit = {}, onFullTexts: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        TopBar("Licenses", "Open source, GPL-3.0", onBack = onBack)
        Note(R.drawable.ic_info, "What Memoria is built from", Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
        SectionHead("App")
        LicenseRow("Memoria Photo", "GPL-3.0")
        SectionHead("Data")
        LicenseRow("GeoNames", "Town names, towns of 1,000+ only · CC BY 4.0")
        SectionHead("Fonts")
        LicenseRow("Bricolage Grotesque", "Titles and headings · SIL OFL 1.1")
        LicenseRow("Roboto", "All other text · SIL OFL 1.1")
        SectionHead("Libraries")
        LicenseRow("LiteRT", "Runs the models · Apache 2.0")
        LicenseRow("Qualcomm QNN libraries", "Runs the models on the NPU · Qualcomm's own license")
        LicenseRow("AndroidX, Compose, Material", "App code and UI · Apache 2.0")
        LicenseRow("Kotlin", "Language and runtime · Apache 2.0")
        LicenseRow("Reorderable", "Drag to reorder people · Apache 2.0")
        SectionHead("Models")
        LicenseRow("YOLOv5m-face", "Finds faces · GPL-3.0")
        LicenseRow("AdaFace IR101", "Face vector · MIT")
        LicenseRow("eDifFIQA-T", "Face quality · MIT")
        LicenseRow("SigLIP 2 B/16", "Photo tags · Apache 2.0")
        LicenseRow("YOLO26-M", "Person, cat and dog boxes · AGPL-3.0")
        LicenseRow("DINOv2-S", "Tells pets apart · Apache 2.0")
        SectionHead("More")
        LicenseRow("Full license texts", "Opens THIRD_PARTY.md on GitHub", onFullTexts) {
            Icon(painterResource(R.drawable.ic_chevron), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Mockup `.set` with no icon. Plain text unless [onClick] is given. */
@Composable
private fun LicenseRow(title: String, info: String, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Text(info, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}
