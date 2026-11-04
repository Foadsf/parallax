package io.github.foadsf.parallax

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

private val Bg = Color(0xFF0B0D10)
private val Panel = Color(0xFF15181D)
private val Line = Color(0x22FFFFFF)
private val Ink = Color(0xFFE8EAED)
private val Muted = Color(0xFF8A9099)
private val Accent = Color(0xFF5EEAD4)
private val Warn = Color(0xFFFFC857)
private val Stop = Color(0xFFFF6B6B)

private sealed interface Screen {
    data object Capture : Screen
    data object Scans : Screen
    data class Processing(val scan: Scan) : Screen
    data class Viewer(val scan: Scan) : Screen
    data object Device : Screen
}

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf<Screen>(Screen.Capture)
    private var hasCamera by mutableStateOf(false)
    private var modes by mutableStateOf<List<CaptureMode>>(emptyList())
    private var modeIndex by mutableStateOf(0)
    private var preview by mutableStateOf<ImageBitmap?>(null)
    private var live by mutableStateOf(LiveState())
    private var torch by mutableStateOf(TorchMode.AUTO)
    private var controller: CaptureController? = null

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasCamera = ok
        if (ok) openCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BuildConfigCompat.versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        modes = runCatching { CameraProbe.modes(CameraProbe.lenses(getSystemService(CameraManager::class.java))) }
            .getOrDefault(emptyList())
        hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCamera) permission.launch(Manifest.permission.CAMERA)
        // Reopened while a reconstruction runs in the background: show its progress.
        (ReconstructionService.state.value as? Job.Running)?.let { screen = Screen.Processing(it.scan) }
        setContent {
            val job by ReconstructionService.state.collectAsState()
            LaunchedEffect(job, screen) {
                val j = job
                if (screen !is Screen.Processing) return@LaunchedEffect
                when (j) {
                    is Job.Done -> { ReconstructionService.acknowledge(); go(Screen.Viewer(j.scan)) }
                    is Job.Failed -> {
                        ReconstructionService.acknowledge()
                        Toast.makeText(this@MainActivity, j.message, Toast.LENGTH_LONG).show()
                        go(Screen.Scans)
                    }
                    else -> {}
                }
            }
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel, primary = Accent)) {
                Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
                    when (val s = screen) {
                        Screen.Capture -> CaptureScreen()
                        Screen.Scans -> ScansScreen()
                        is Screen.Processing -> ProcessingScreen(s.scan, job)
                        is Screen.Viewer -> ViewerScreen(s.scan)
                        Screen.Device -> DeviceScreen()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (screen == Screen.Capture) openCamera()
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    private fun openCamera() {
        if (!hasCamera || controller != null || modes.isEmpty()) return
        controller = CaptureController(this,
            onPreview = { preview = it.asImageBitmap() },
            onState = { live = it },
            onError = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() },
        ).also {
            it.torchMode = torch
            it.open(modes[modeIndex.coerceIn(0, modes.lastIndex)])
        }
    }

    private fun closeCamera() {
        controller?.close()
        controller = null
        preview = null
    }

    private fun go(s: Screen) {
        if (s == Screen.Capture) { screen = s; openCamera() } else { closeCamera(); screen = s }
    }

    /** Reconstructs in a foreground service, so leaving the app or locking the phone is fine. */
    private fun process(scan: Scan) {
        val running = ReconstructionService.state.value as? Job.Running
        if (running != null && running.scan.dir != scan.dir) {
            Toast.makeText(this, "Already reconstructing ${running.scan.title}", Toast.LENGTH_LONG).show()
            go(Screen.Processing(running.scan))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)  // progress notification; optional
        }
        ReconstructionService.start(this, scan)
        go(Screen.Processing(scan))
    }

    // ---- Capture ----------------------------------------------------------------------------

    @Composable
    private fun CaptureScreen() {
        if (!hasCamera) {
            Centered("Parallax needs the camera to scan.", "Allow camera") { permission.launch(Manifest.permission.CAMERA) }
            return
        }
        if (modes.isEmpty()) {
            Centered("No rear camera found.", null) {}
            return
        }
        val view = LocalView.current
        LaunchedEffect(live.keyframes) {
            if (live.keyframes > 0) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        Column(Modifier.fillMaxSize()) {
            // Top bar: lens modes and torch.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEachIndexed { i, m ->
                        Chip(m.label + if (m is CaptureMode.Rig) "  ·  metric" else "", selected = i == modeIndex, enabled = !live.scanning) {
                            if (i != modeIndex) { modeIndex = i; closeCamera(); openCamera() }
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                RoundButton(onClick = {
                    torch = when (torch) { TorchMode.AUTO -> TorchMode.ON; TorchMode.ON -> TorchMode.OFF; TorchMode.OFF -> TorchMode.AUTO }
                    controller?.torchMode = torch
                }) {
                    GlyphIcon(when (torch) { TorchMode.AUTO -> Glyph.TorchAuto; TorchMode.ON -> Glyph.Torch; TorchMode.OFF -> Glyph.TorchOff },
                        if (live.torchOn) Warn else Ink)
                }
            }
            // Viewfinder with tracked features.
            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                val img = preview
                Box(Modifier.fillMaxWidth().aspectRatio(img?.let { it.width.toFloat() / it.height } ?: 0.75f)
                    .clip(RoundedCornerShape(20.dp)).background(Panel)) {
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    val pts = live.points
                    Canvas(Modifier.fillMaxSize()) {
                        // Each point with its trail back to where it was at the last keyframe: long
                        // trails mean enough parallax for the next keyframe.
                        val r = 2.2.dp.toPx()
                        val w = 1.2.dp.toPx()
                        val c = if (live.scanning) Accent else Color.White.copy(alpha = 0.55f)
                        val trail = c.copy(alpha = 0.45f)
                        for (k in 0 until pts.size / 4) {
                            val at = Offset(pts[4 * k] * size.width, pts[4 * k + 1] * size.height)
                            if (live.scanning) drawLine(trail, Offset(pts[4 * k + 2] * size.width, pts[4 * k + 3] * size.height), at, w, StrokeCap.Round)
                            drawCircle(c, r, at)
                        }
                    }
                    if (live.scanning) {
                        CoverageDial(live.coverage, live.heading,
                            Modifier.align(Alignment.TopStart).padding(10.dp).size(76.dp))
                        Text("${live.keyframes}", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                                .background(Color(0x99000000), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            Text(live.hint.ifEmpty { if (live.scanning) " " else "Frame the object, then tap to scan" },
                color = if (live.hint.startsWith("Too dark") || live.hint.startsWith("Slow down") || live.hint.startsWith("Hold steady") ||
                    live.hint.startsWith("Glare")) Warn else Muted,
                fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
            // Bottom: scans, shutter, spacer.
            Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                RoundButton(onClick = { if (!live.scanning) go(Screen.Scans) }) { GlyphIcon(Glyph.Stack, Ink) }
                Shutter(live.scanning, live.progress) {
                    val c = controller ?: return@Shutter
                    if (!live.scanning) c.startScan()
                    else c.stopScan { scan ->
                        if (scan == null) Toast.makeText(this@MainActivity, "Too few keyframes; move further around the object", Toast.LENGTH_LONG).show()
                        else process(scan)
                    }
                }
                Spacer(Modifier.size(52.dp))
            }
        }
    }

    /**
     * Sides of the object already captured: outer ring = level views, inner ring = views from
     * above, 24 sectors each, starting at the top with the first keyframe. The white tick marks
     * where the phone is looking now.
     */
    @Composable
    private fun CoverageDial(covered: BooleanArray, heading: Int, modifier: Modifier) {
        Canvas(modifier.clip(CircleShape).background(Color(0x99000000))) {
            val sweep = 360f / Coverage.SECTORS
            val outerW = size.minDimension * 0.13f
            val innerW = size.minDimension * 0.11f
            val outerInset = outerW / 2 + size.minDimension * 0.06f
            val innerInset = outerInset + outerW / 2 + innerW / 2 + size.minDimension * 0.04f
            for (band in 0..1) {
                val inset = if (band == 0) outerInset else innerInset
                val w = if (band == 0) outerW else innerW
                for (k in 0 until Coverage.SECTORS) {
                    val on = covered.getOrElse(band * Coverage.SECTORS + k) { false }
                    drawArc(if (on) Accent else Color.White.copy(alpha = 0.14f), -90f + k * sweep + 1.2f, sweep - 2.4f, false,
                        topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset),
                        style = Stroke(w))
                }
            }
            if (heading >= 0) {
                val inset = if (heading < Coverage.SECTORS) outerInset else innerInset
                val k = heading % Coverage.SECTORS
                drawArc(Color.White, -90f + k * sweep, sweep, false,
                    topLeft = Offset(inset, inset), size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset),
                    style = Stroke(size.minDimension * 0.05f, cap = StrokeCap.Round))
            }
        }
    }

    @Composable
    private fun Shutter(scanning: Boolean, progress: Float, onClick: () -> Unit) {
        val p by animateFloatAsState(if (scanning) progress else 0f, label = "progress")
        Box(Modifier.size(84.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 4.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.25f), size.minDimension / 2 - w / 2, style = Stroke(w))
                if (scanning) drawArc(Accent, -90f, 360f * p, false, style = Stroke(w, cap = StrokeCap.Round),
                    topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w))
            }
            Box(Modifier.size(if (scanning) 30.dp else 64.dp).clip(if (scanning) RoundedCornerShape(8.dp) else CircleShape)
                .background(if (scanning) Stop else Accent))
        }
    }

    // ---- Processing -------------------------------------------------------------------------

    @Composable
    private fun ProcessingScreen(scan: Scan, job: Job) {
        val running = job as? Job.Running
        val fraction = running?.fraction ?: 0f
        val stage = running?.stage ?: "Starting"
        val cancelling = running?.cancelling == true
        BackHandler { go(Screen.Scans) }
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            val p by animateFloatAsState(fraction, label = "fraction")
            Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = 6.dp.toPx()
                    val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
                    drawArc(Line, 0f, 360f, false, topLeft = Offset(w / 2, w / 2), size = sz, style = Stroke(w))
                    drawArc(Accent, -90f, 360f * p, false, topLeft = Offset(w / 2, w / 2), size = sz, style = Stroke(w, cap = StrokeCap.Round))
                }
                Text("${(p * 100).toInt()}%", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Light)
            }
            Spacer(Modifier.height(28.dp))
            Text(stage, color = Ink, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text("${scan.title} · ${scan.imageCount()} images", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text("Runs in the background; you can leave the app", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(40.dp))
            TextButton(onClick = { ReconstructionService.cancel() }) { Text(if (cancelling) "Cancelling…" else "Cancel", color = Muted) }
        }
    }

    // ---- Viewer -----------------------------------------------------------------------------

    @Composable
    private fun ViewerScreen(scan: Scan) {
        BackHandler { go(Screen.Scans) }
        val report = remember(scan) { scan.reportJson() }
        var view by remember { mutableStateOf<PointCloudView?>(null) }
        var dense by remember { mutableStateOf(scan.dense.length() > 300) }
        var loading by remember { mutableStateOf(true) }
        var pointSize by remember { mutableStateOf(1) }   // index into sizes
        var frustaOn by remember { mutableStateOf(true) }
        val sizes = floatArrayOf(0.5f, 1f, 2f)
        LaunchedEffect(view, dense) {
            val v = view ?: return@LaunchedEffect
            loading = true
            val (cloud, frusta) = withContext(Dispatchers.IO) {
                val c = Cloud.readPly(if (dense) scan.dense else scan.sparse)
                c to c?.let { Frusta.fromColmap(File(scan.dir, "sparse/0"), it.radius * 0.07f) }
            }
            v.show(cloud, frusta, metric = (report?.optDouble("metresPerUnit", 0.0) ?: 0.0) > 0)
            loading = false
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView({ ctx -> PointCloudView(ctx).also { view = it } }, Modifier.fillMaxSize())
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Bg, Bg.copy(alpha = 0.95f), Bg.copy(alpha = 0.6f), Color.Transparent)))
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(onClick = { go(Screen.Scans) }) { GlyphIcon(Glyph.Back, Ink) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(scan.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(summary(report, dense), color = Muted, fontSize = 12.sp)
                }
            }
            // View controls: point size, cameras, recentre.
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundButton(onClick = {
                    pointSize = (pointSize + 1) % sizes.size
                    view?.apply { pointScale = sizes[pointSize]; requestRender() }
                }) { GlyphIcon(Glyph.Points, Ink) }
                RoundButton(onClick = {
                    frustaOn = !frustaOn
                    view?.apply { showFrusta = frustaOn; requestRender() }
                }) { GlyphIcon(Glyph.Frustum, if (frustaOn) Accent else Muted) }
                RoundButton(onClick = { view?.resetView() }) { GlyphIcon(Glyph.Recenter, Ink) }
            }
            if (loading) Text("Loading…", color = Muted, modifier = Modifier.align(Alignment.Center))
            Row(Modifier.align(Alignment.BottomCenter).padding(20.dp).clip(RoundedCornerShape(28.dp)).background(Panel)
                .border(1.dp, Line, RoundedCornerShape(28.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Chip("Dense", dense, enabled = scan.dense.length() > 300) { dense = true }
                Spacer(Modifier.width(6.dp))
                Chip("Sparse", !dense, enabled = true) { dense = false }
                Spacer(Modifier.width(14.dp))
                RoundButton(onClick = {
                    // Copying tens of megabytes: off the main thread.
                    lifecycleScope.launch {
                        val name = withContext(Dispatchers.IO) { runCatching { Exporter.saveToDownloads(this@MainActivity, scan) }.getOrNull() }
                        Toast.makeText(this@MainActivity, name?.let { "Saved to Downloads: $it" } ?: "Could not save", Toast.LENGTH_LONG).show()
                    }
                }) { GlyphIcon(Glyph.Download, Ink) }
                Spacer(Modifier.width(6.dp))
                RoundButton(onClick = { lifecycleScope.launch(Dispatchers.IO) { runCatching { Exporter.share(this@MainActivity, scan) } } }) {
                    GlyphIcon(Glyph.Share, Ink)
                }
            }
        }
    }

    private fun scaleLabel(r: JSONObject): String = when {
        r.optDouble("metresPerUnit", 0.0) <= 0 -> "unscaled"
        r.optString("scaleSource") == "marker" -> "metric (marker)"
        else -> "metric (lens pair)"
    }

    private fun summary(r: JSONObject?, dense: Boolean): String {
        if (r == null) return "Not reconstructed"
        val pts = if (dense) r.optInt("densePoints") else r.optInt("sparsePoints")
        val scale = scaleLabel(r)
        return "%,d points · %d/%d cameras · %.2f px · %s".format(pts, r.optInt("imagesRegistered"), r.optInt("imagesTotal"),
            r.optDouble("meanReprojError", 0.0), scale)
    }

    private fun shortSummary(r: JSONObject): String {
        val pts = r.optInt("densePoints").takeIf { it > 0 } ?: r.optInt("sparsePoints")
        val scale = if (r.optDouble("metresPerUnit", 0.0) > 0) "metric" else "unscaled"
        return "%,d pts · %d/%d cams · %s".format(pts, r.optInt("imagesRegistered"), r.optInt("imagesTotal"), scale)
    }

    // ---- Scans ------------------------------------------------------------------------------

    @Composable
    private fun ScansScreen() {
        BackHandler { go(Screen.Capture) }
        var scans by remember { mutableStateOf(ScanStore.list(this)) }
        var confirm by remember { mutableStateOf<Scan?>(null) }
        var renaming by remember { mutableStateOf<Scan?>(null) }
        var markerDialog by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(onClick = { go(Screen.Capture) }) { GlyphIcon(Glyph.Back, Ink) }
                Spacer(Modifier.width(12.dp))
                Text("Scans", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                RoundButton(onClick = { markerDialog = true }) { GlyphIcon(Glyph.Ruler, if (Settings.markerSizeMm(this@MainActivity) > 0) Accent else Ink) }
                Spacer(Modifier.width(8.dp))
                RoundButton(onClick = { go(Screen.Device) }) { GlyphIcon(Glyph.Info, Ink) }
            }
            if (scans.isEmpty()) Text("No scans yet.", color = Muted, modifier = Modifier.padding(24.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(scans, key = { it.name }) { s -> ScanRow(s, onDelete = { confirm = s }, onRename = { renaming = s }) }
            }
        }
        confirm?.let { s ->
            AlertDialog(onDismissRequest = { confirm = null },
                title = { Text("Delete scan?") },
                text = { Text("${s.title} and its ${s.imageCount()} images will be removed from this phone.") },
                confirmButton = { TextButton({ s.dir.deleteRecursively(); scans = ScanStore.list(this); confirm = null }) { Text("Delete", color = Stop) } },
                dismissButton = { TextButton({ confirm = null }) { Text("Keep") } })
        }
        renaming?.let { s ->
            var text by remember(s) { mutableStateOf(s.title.takeIf { it != s.name } ?: "") }
            AlertDialog(onDismissRequest = { renaming = null },
                title = { Text("Rename scan") },
                text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(s.name) }) },
                confirmButton = { TextButton({
                    runCatching { s.rename(text) }
                    scans = ScanStore.list(this); renaming = null
                }) { Text("Save") } },
                dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } })
        }
        if (markerDialog) {
            var text by remember { mutableStateOf(Settings.markerSizeMm(this).takeIf { it > 0 }?.toString() ?: "") }
            AlertDialog(onDismissRequest = { markerDialog = false },
                title = { Text("Scale marker") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Print an ArUco marker (4×4 dictionary, see the project page), put it next to the object and enter " +
                            "its black square's edge, measured after printing. Reconstructions then use it for true scale, on any phone. " +
                            "Leave empty to rely on the lens pair, if there is one.", fontSize = 13.sp)
                        OutlinedTextField(text, { t -> text = t.filter { it.isDigit() }.take(4) }, singleLine = true,
                            label = { Text("Edge (mm)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                },
                confirmButton = { TextButton({
                    Settings.setMarkerSizeMm(this, text.toIntOrNull()?.coerceIn(0, 2000) ?: 0)
                    markerDialog = false
                }) { Text("Save") } },
                dismissButton = { TextButton({ markerDialog = false }) { Text("Cancel") } })
        }
    }

    @Composable
    private fun ScanRow(s: Scan, onDelete: () -> Unit, onRename: () -> Unit) {
        val thumb = remember(s) {
            s.imagesDir.listFiles()?.filter { it.name.startsWith("p_") }?.minByOrNull { it.name }?.let { f ->
                runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 16 })?.asImageBitmap() }.getOrNull()
            }
        }
        val report = remember(s) { s.reportJson() }
        val title = remember(s) { s.title }
        val bytes by produceState(-1L, s) { value = withContext(Dispatchers.IO) { s.sizeBytes() } }
        var menu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel)
            .clickable { if (report?.optBoolean("ok") == true) go(Screen.Viewer(s)) else process(s) }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Bg)) {
                thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (report?.optBoolean("ok") == true) shortSummary(report) else "${s.imageCount()} images · tap to reconstruct",
                    color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (bytes >= 0) Text(megabytes(bytes) + if (title != s.name) " · ${s.name}" else "", color = Muted, fontSize = 11.sp)
            }
            Box {
                RoundButton(onClick = { menu = true }) { GlyphIcon(Glyph.More, Muted, 20.dp) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Rename") }, { menu = false; onRename() })
                    DropdownMenuItem({ Text(if (report?.optBoolean("ok") == true) "Reconstruct again" else "Reconstruct") },
                        { menu = false; process(s) })
                    DropdownMenuItem({ Text("Delete", color = Stop) }, { menu = false; onDelete() },
                        enabled = (ReconstructionService.state.value as? Job.Running)?.scan?.dir != s.dir)
                }
            }
        }
    }

    // ---- This phone -------------------------------------------------------------------------

    @Composable
    private fun DeviceScreen() {
        BackHandler { go(Screen.Scans) }
        var report by remember { mutableStateOf<DeviceReport?>(null) }
        LaunchedEffect(Unit) { report = withContext(Dispatchers.IO) { DeviceProbe.report(this@MainActivity) } }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(onClick = { go(Screen.Scans) }) { GlyphIcon(Glyph.Back, Ink) }
                Spacer(Modifier.width(12.dp))
                Text("This phone", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                RoundButton(onClick = {
                    val r = report ?: return@RoundButton
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Parallax device report", r.asText()))
                    Toast.makeText(this@MainActivity, "Report copied; paste it into a GitHub issue", Toast.LENGTH_LONG).show()
                }) { GlyphIcon(Glyph.Copy, Ink) }
            }
            val r = report
            if (r == null) {
                Text("Checking the hardware…", color = Muted, modifier = Modifier.padding(24.dp))
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(r.sections, key = { it.first }) { (title, rows) ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(title, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        for ((k, v) in rows) Column {
                            Text(k, color = Muted, fontSize = 12.sp)
                            Text(v, color = Ink, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }

    // ---- Small components --------------------------------------------------------------------

    @Composable
    private fun Chip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
        Text(text, color = when { !enabled -> Muted.copy(alpha = 0.5f); selected -> Bg; else -> Ink }, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Accent else Panel)
                .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(50))
                .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
    }

    @Composable
    private fun RoundButton(onClick: () -> Unit, content: @Composable () -> Unit) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Panel).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            content()
        }
    }

    @Composable
    private fun Centered(message: String, action: String?, onAction: () -> Unit) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text(message, color = Ink, fontSize = 16.sp, textAlign = TextAlign.Center)
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onAction) { Text(action, color = Accent) }
            }
        }
    }

    private fun megabytes(b: Long) = String.format(Locale.ROOT, "%.1f MB", b / 1e6)

    @Suppress("unused")
    private fun Bitmap.aspect() = width.toFloat() / height
}
