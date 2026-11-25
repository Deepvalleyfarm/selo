package selo.Nachipa01.ui.screens.seller

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import selo.Nachipa01.data.model.Listing
import selo.Nachipa01.data.repo.PublishRequest
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.domain.Taxonomy
import selo.Nachipa01.ui.ListingForm
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.UploadedMedia
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.screens.auth.saveJpeg
import selo.Nachipa01.ui.screens.discover.CardActions
import selo.Nachipa01.ui.screens.discover.FeedCard
import selo.Nachipa01.ui.theme.Selo
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/* ============================================================ 1. choose source */

/** '+' on the seller dashboard → Record a Video or Use Photos (PRD v6.1 §4.1). */
@Composable
fun AddListingScreen(onClose: () -> Unit, onCamera: () -> Unit, onProcess: () -> Unit, onPhotos: () -> Unit = {}) {
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Add a listing", "Show it — our AI writes the listing", onBack = onClose)
        FormColumn {
            if (app.capturedMedia.isNotEmpty()) NoteBox("${app.capturedMedia.size} file(s) received from WhatsApp.", Tone.OK)
            ChoiceCard(Icons.Outlined.Videocam, "Record a Video", "${cfg.videoMinSec}–${cfg.videoMaxSec} seconds, portrait. Listings with video get about 2.5× more buyer interactions.", true) {
                app.resetListingFlow(); app.listingMediaType.value = "video"; onCamera()
            }
            ChoiceCard(Icons.Outlined.PhotoLibrary, "Use Photos", "Up to ${cfg.maxPhotos} photos from your camera or gallery. Drag to reorder.", false) {
                val shared = app.capturedMedia.toList(); app.resetListingFlow(); app.capturedMedia.addAll(shared); app.listingMediaType.value = "photo"; onPhotos()
            }
            SeloCard {
                Text("What happens next", fontWeight = FontWeight.ExtraBold)
                Sub("1. Your phone picks out key frames and suggests a category instantly.\n2. Selo AI drafts the title, description, price in ZMW and category — usually within 8 seconds.\n3. You check every field, preview it as buyers will see it, then post.")
            }
            SeloCard { Text("Tips", fontWeight = FontWeight.Bold); Sub("Good daylight · product in the centre · show size and quantity · say the price out loud") }
        }
    }
}

@Composable
private fun ChoiceCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, filled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (filled) Selo.Red else Color.White).border(1.5.dp, Selo.Red, RoundedCornerShape(16.dp))
        .clickable(onClick = onClick).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).background(if (filled) Color.White.copy(.18f) else Selo.RedTint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (filled) Color.White else Selo.Red, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (filled) Color.White else Selo.Red, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            Text(sub, color = if (filled) Color(0xFFFFD6DC) else Selo.Mute, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
    }
}

/* ============================================================ 2a. video recorder */

/** Portrait recorder: 15–60 s window, countdown of time left, live audio waveform. */
@SuppressLint("MissingPermission")
@Composable
fun CameraScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    var granted by remember { mutableStateOf(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it[Manifest.permission.CAMERA] == true }
    LaunchedEffect(Unit) { if (!granted) perm.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    val wave = remember { mutableStateListOf<Float>() }
    val controller = remember { LifecycleCameraController(ctx).apply { cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA } }
    LaunchedEffect(granted) { if (granted) { controller.setEnabledUseCases(CameraController.VIDEO_CAPTURE); controller.bindToLifecycle(owner) } }
    val maxMs = cfg.videoMaxSec * 1000L; val minMs = cfg.videoMinSec * 1000L
    LaunchedEffect(recording) { elapsedMs = 0; while (recording != null) { delay(100); elapsedMs += 100; if (elapsedMs >= maxMs) { recording?.stop(); recording = null } } }

    fun toggle() {
        recording?.let { if (elapsedMs >= minMs) { it.stop(); recording = null }; return }
        wave.clear(); error = null
        val f = File(ctx.cacheDir, "listing-${UUID.randomUUID()}.mp4")
        recording = controller.startRecording(FileOutputOptions.Builder(f).build(), AudioConfig.create(true), ContextCompat.getMainExecutor(ctx)) { e ->
            when (e) {
                is VideoRecordEvent.Status -> { val amp = e.recordingStats.audioStats.audioAmplitude.toFloat(); wave.add(amp.coerceIn(0f, 1f)); if (wave.size > 60) wave.removeAt(0) }
                is VideoRecordEvent.Finalize -> if (!e.hasError() || e.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) {
                    app.capturedMedia.clear(); app.capturedMedia.add(Uri.fromFile(f)); app.listingMediaType.value = "video"
                    app.videoDurationMs.value = e.recordingStats.recordedDurationNanos / 1_000_000; onDone()
                } else error = "Recording failed. Try again."
            }
        }
    }
    val left = ((maxMs - elapsedMs) / 1000).coerceAtLeast(0)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) AndroidView({ PreviewView(it).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FILL_CENTER } }, Modifier.fillMaxSize())
        else Text("Camera and microphone permission are needed to record your advert.", color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        Box(Modifier.align(Alignment.Center).fillMaxWidth(.78f).fillMaxHeight(.5f).border(2.dp, Color.White.copy(.5f), RoundedCornerShape(16.dp)))
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.Outlined.Close, "Close", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                Text(if (recording != null) "● ${left}s left" else "Up to ${cfg.videoMaxSec}s", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(if (recording != null) Selo.Red else Color.Black.copy(.45f), RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                Spacer(Modifier.weight(1f)); Spacer(Modifier.width(48.dp))
            }
            // live waveform while recording
            Canvas(Modifier.padding(top = 10.dp).fillMaxWidth(.8f).height(36.dp)) {
                val n = 60; val w = size.width / n
                wave.forEachIndexed { i, a -> val h = (4f + a * size.height * 3).coerceAtMost(size.height)
                    drawRoundRect(Color.White, Offset(i * w, (size.height - h) / 2), androidx.compose.ui.geometry.Size(w * .6f, h), androidx.compose.ui.geometry.CornerRadius(3f)) }
            }
            if (recording != null && elapsedMs < minMs) Text("Keep going — ${((minMs - elapsedMs) / 1000) + 1}s until you can stop", color = Color.White, fontSize = 12.sp)
            error?.let { Text(it, color = Color.White, modifier = Modifier.background(Selo.Red, RoundedCornerShape(8.dp)).padding(6.dp)) }
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LinearProgressIndicator({ elapsedMs / maxMs.toFloat() }, Modifier.fillMaxWidth(.8f).height(4.dp), color = Selo.Red, trackColor = Color.White.copy(.3f))
            Spacer(Modifier.height(18.dp))
            Box(Modifier.size(78.dp).border(4.dp, Color.White, CircleShape).clickable(enabled = granted) { toggle() }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(if (recording != null) 28.dp else 58.dp).background(Color(0xFFFF3B4F), if (recording != null) RoundedCornerShape(6.dp) else CircleShape)
                    .graphicsLayer { alpha = if (recording != null && elapsedMs < minMs) .5f else 1f })
            }
            Text(if (recording == null) "Tap to record" else "Tap to stop", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/* ============================================================ 2b. photos */

/** Up to 4 photos from the camera or the gallery; long-press and drag to reorder (first photo is the cover). */
@Composable
fun PhotoPickScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val app = LocalAppState.current
    val cfg by app.config.collectAsState()
    val max = cfg.maxPhotos
    val photos = app.capturedMedia
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(max)) { uris -> uris.take(max - photos.size).forEach(photos::add) }
    val camera = selo.Nachipa01.ui.screens.auth.rememberPhotoCapture { if (photos.size < max) photos.add(it) }
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Use photos", "${photos.size} of $max · first photo is the cover", onBack = onBack)
        FormColumn {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("Take photo", Modifier.weight(1f)) { if (photos.size < max) camera() }
                OutlineButton("From gallery", Modifier.weight(1f)) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }
            ReorderableThumbs(photos)
            if (photos.size > 1) Sub("Long-press a photo and drag it left or right to change the order.")
            PrimaryButton("Continue", enabled = photos.isNotEmpty()) { app.listingMediaType.value = "photo"; onDone() }
        }
    }
}

@Composable
fun ReorderableThumbs(items: MutableList<Uri>) {
    var dragging by remember { mutableIntStateOf(-1) }
    var dx by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val slot = maxWidth / 4
        val slotPx = with(androidx.compose.ui.platform.LocalDensity.current) { slot.toPx() }
        Row {
            items.forEachIndexed { i, uri ->
                Box(Modifier.width(slot).aspectRatio(.8f).padding(4.dp)
                    .graphicsLayer { if (i == dragging) { translationX = dx; scaleX = 1.06f; scaleY = 1.06f } }
                    .clip(RoundedCornerShape(12.dp)).background(Selo.RedTint)
                    .pointerInput(items.size) {
                        detectDragGesturesAfterLongPress(onDragStart = { dragging = i; dx = 0f },
                            onDragEnd = {
                                val to = (dragging + (dx / slotPx).roundToInt()).coerceIn(0, items.lastIndex)
                                if (dragging in items.indices && to != dragging) items.add(to, items.removeAt(dragging))
                                dragging = -1; dx = 0f
                            }, onDragCancel = { dragging = -1; dx = 0f }) { c, d -> c.consume(); dx += d.x }
                    }) {
                    AsyncImage(uri, "Photo ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Text("${i + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(4.dp).background(Selo.Red, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp))
                    IconButton({ items.removeAt(i) }, Modifier.align(Alignment.TopEnd).size(28.dp)) { Icon(Icons.Outlined.Cancel, "Remove", tint = Color.White) }
                }
            }
            repeat(4 - items.size) { Box(Modifier.width(slot).aspectRatio(.8f).padding(4.dp).border(1.5.dp, Selo.Field, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AddAPhoto, null, tint = Selo.Field) } }
        }
    }
}

/* ============================================================ 3. on-device + cloud AI */

/** Frame plan from PRD §4.1.3: 1 fps for the first 10 s, then 1 every 5 s (capped at 16 frames). */
fun framePlanMs(durationMs: Long): List<Long> {
    val first = (0L until minOf(durationMs, 10_000L) step 1_000L).toList()
    val rest = (10_000L until durationMs step 5_000L).toList()
    return (first + rest).take(16)
}

private suspend fun extractFrames(ctx: Context, video: Uri, durationMs: Long): List<Uri> = withContext(Dispatchers.IO) {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, video)
        val dur = durationMs.takeIf { it > 0 } ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 15_000L
        framePlanMs(dur).mapNotNull { t -> r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { ctx.saveJpeg(scale(it)) } }
    } catch (_: Exception) { emptyList() } finally { r.release() }
}

private fun scale(b: Bitmap): Bitmap { val m = maxOf(b.width, b.height); if (m <= 720) return b; val k = 720f / m
    return Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) }

private suspend fun onDeviceLabels(ctx: Context, image: Uri): List<Pair<String, Float>> = runCatching {
    val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build())
    labeler.process(InputImage.fromFilePath(ctx, image)).await().map { it.text to it.confidence }
}.getOrDefault(emptyList())

private val steps = listOf(
    "Picking key frames" to "On your phone · 1 per second, then 1 every 5 s", "Instant category" to "On-device image recognition",
    "Uploading" to "Video and frames", "Writing title, description, price & category" to "Selo AI · Zambian market prices", "Ready for your review" to "",
)

/** AI pipeline (§4.1.3). If the cloud step fails or exceeds the target time, the seller goes to an empty manual form with a notice. */
@Composable
fun AiProcessingScreen(onReady: () -> Unit, onFail: () -> Unit) {
    val app = LocalAppState.current
    val ctx = LocalContext.current
    val cfg by app.config.collectAsState()
    var step by remember { mutableIntStateOf(0) }
    var upload by remember { mutableFloatStateOf(0f) }
    var category by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val media = app.capturedMedia.toList()
        val video = app.listingMediaType.value == "video"
        runCatching {
            step = 0
            val frames = if (video && media.isNotEmpty()) extractFrames(ctx, media[0], app.videoDurationMs.value) else media
            step = 1
            val labels = frames.firstOrNull()?.let { onDeviceLabels(ctx, it) } ?: emptyList()
            category = Taxonomy.fromLabels(labels); app.onDeviceCategory.value = category
            step = 2
            val extra = if (video) frames else emptyList()
            val paths = app.backend.uploadMedia(media + extra) { upload = it }
            val mediaPaths = paths.take(media.size); val framePaths = if (video) paths.drop(media.size) else mediaPaths
            app.uploaded.value = UploadedMedia(if (video) mediaPaths.firstOrNull() else null, if (video) emptyList() else mediaPaths,
                if (video) framePaths.firstOrNull() else mediaPaths.firstOrNull(), framePaths,
                photoByUri = if (video) emptyMap() else media.map { it.toString() }.zip(mediaPaths).toMap())
            step = 3
            val draft = runCatching { withTimeout(cfg.aiTimeoutSec * 1000L) {
                app.backend.runAiPipeline(framePaths, app.listingPrompt.value, { }, labels, if (video) "video" else "photo") } }
            draft.onSuccess { d ->
                app.aiDraft.value = d; app.aiFallback.value = null
                app.listingForm.value = ListingForm(d.title, d.description, "%.2f".format(d.priceNgwee / 100.0), d.category.ifBlank { category ?: "" }, d.condition,
                    tags = d.tags, priceHint = if (d.priceHighNgwee > 0) "AI suggests ${Fees.formatK(d.priceLowNgwee)}–${Fees.formatK(d.priceHighNgwee)} from ${d.comparables} listings in ${d.priceScope.ifBlank { "Zambia" }}" else null,
                    aiUsed = true, province = "Lusaka", district = app.session.value?.area ?: "")
            }.onFailure { e ->
                app.aiFallback.value = if (e is kotlinx.coroutines.TimeoutCancellationException) "Auto-fill took longer than ${cfg.aiTimeoutSec} seconds, so please fill in the details yourself."
                    else "Auto-fill isn't available right now (${e.message ?: "error"}). Please fill in the details yourself."
                app.listingForm.value = ListingForm(category = category ?: "", aiUsed = false, province = "Lusaka", district = app.session.value?.area ?: "")
            }
            step = 4
        }.onSuccess { delay(400); onReady() }.onFailure { error = it.message ?: "Upload failed. Check your connection and try again." }
    }
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Selo.Purple, modifier = Modifier.size(14.dp)); Text(" SELO AI AT WORK", color = Selo.Purple, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
        }
        H("Building your listing", 20)
        if (category != null) Row(verticalAlignment = Alignment.CenterVertically) { Text("Looks like: ", color = Selo.Mute); StatusChip(category!!, Tone.FUND) }
        Row { Text("Uploading ${app.capturedMedia.size} file(s)", color = Selo.Mute, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("${(upload * 100).toInt()}%", fontWeight = FontWeight.Bold) }
        LinearProgressIndicator({ upload }, Modifier.fillMaxWidth(), color = Selo.Red, trackColor = Selo.Line)
        steps.forEachIndexed { i, (t, s) -> ChecklistRow(when { i < step -> StepState.DONE; i == step -> StepState.RUNNING; else -> StepState.WAITING }, t, s.ifBlank { null }) }
        Text("Usually done in under ${cfg.aiTimeoutSec} seconds", color = Selo.Mute, fontStyle = FontStyle.Italic, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        if (error != null) { ErrorText(error); OutlineButton("Go back", onClick = onFail) }
    }
}

/* ============================================================ 4. review every field */

/** AI pre-fill review (§4.1.1): every field is shown and must be confirmed before Preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewListingScreen(onBack: () -> Unit, onDone: () -> Unit, onPreview: () -> Unit = {}) {
    val app = LocalAppState.current
    // Single source of truth so "Looks right" and edits update the same form.
    val f = app.listingForm.value ?: ListingForm()
    fun edit(field: String, change: ListingForm.() -> ListingForm) { val cur = app.listingForm.value ?: ListingForm(); app.listingForm.value = cur.change().copy(reviewed = cur.reviewed + field) }
    val pending = ListingForm.FIELDS.count { it !in f.reviewed }
    Column(Modifier.fillMaxSize()) {
        SeloHeader(if (f.aiUsed) "Your Selo listing is ready" else "Add your listing details", if (f.aiUsed) "AI pre-filled it — check every field" else "Auto-fill wasn't available",
            onBack = onBack)
        FormColumn {
            app.aiFallback.value?.let { NoteBox(it, Tone.WARN, Icons.Outlined.Info) }
            if (app.listingMediaType.value == "photo" && app.capturedMedia.size > 1) {
                Text("Photo order (first is the cover)", fontWeight = FontWeight.Bold); ReorderableThumbs(app.capturedMedia)
            }
            ReviewField("title", "Title", f) { SeloField("Title", f.title, { v -> edit("title") { copy(title = v.take(80)) } }) }
            ReviewField("description", "Description", f) { SeloField("Description (3–5 sentences)", f.description, { v -> edit("description") { copy(description = v.take(1200)) } }, singleLine = false) }
            ReviewField("price", "Price", f) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeloField("Price (ZMW)", f.price, { v -> edit("price") { copy(price = v.filter { it.isDigit() || it == '.' }) } }, Modifier.weight(1.3f), keyboard = KeyboardType.Decimal)
                    SeloField("Offer % (optional)", f.discountPct, { v -> edit("price") { copy(discountPct = v.filter(Char::isDigit).take(2)) } }, Modifier.weight(1f), keyboard = KeyboardType.Number)
                }
                f.priceHint?.let { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.AutoAwesome, null, tint = Selo.Purple, modifier = Modifier.size(13.dp))
                    Text(" $it", color = Selo.Purple, fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
            }
            ReviewField("category", "Category", f) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Taxonomy.CATEGORIES.forEach { c -> SeloChip(c, f.category == c) { edit("category") { copy(category = c) } } }
                }
            }
            ReviewField("condition", "Condition / quality", f) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Taxonomy.CONDITIONS.forEach { c -> SeloChip(c, f.condition == c) { edit("condition") { copy(condition = c) } } } }
            }
            ReviewField("availability", "Availability", f) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Taxonomy.AVAILABILITY.forEach { a -> SeloChip(a, f.availability == a) { edit("availability") { copy(availability = a) } } } }
            }
            ReviewField("location", "Location", f) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeloField("Province", f.province, { v -> edit("location") { copy(province = v) } }, Modifier.weight(1f))
                    SeloField("District", f.district, { v -> edit("location") { copy(district = v) } }, Modifier.weight(1f))
                }
                SeloField("Area / landmark", f.location, { v -> edit("location") { copy(location = v) } }, trailing = Icons.Outlined.Place)
            }
            ReviewField("tags", "Tags", f) {
                var t by remember { mutableStateOf("") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    f.tags.forEach { tag -> SeloChip("#$tag ×", false) { edit("tags") { copy(tags = tags - tag) } } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SeloField("Add a tag", t, { t = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(30) }, Modifier.weight(1f))
                    TextButton({ if (t.isNotBlank()) { edit("tags") { copy(tags = (tags + t).distinct().take(8)) }; t = "" } }) { Text("Add", color = Selo.Red) }
                }
            }
            if (pending > 0) Text("$pending field${if (pending == 1) "" else "s"} still to check", color = Selo.AmberDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            PrimaryButton("Preview listing", enabled = f.allReviewed && f.title.isNotBlank() && (f.price.toDoubleOrNull() ?: 0.0) > 0 && f.category.isNotBlank()) { onPreview() }
        }
    }
}

/** A field card with an explicit "Looks right" confirmation; editing also counts as reviewed. */
@Composable
private fun ReviewField(key: String, label: String, f: ListingForm, content: @Composable ColumnScope.() -> Unit) {
    val app = LocalAppState.current
    val done = key in f.reviewed
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).border(1.5.dp, if (done) Selo.Green else Selo.Amber, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            if (done) Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.CheckCircle, null, tint = Selo.Green, modifier = Modifier.size(16.dp)); Text(" Checked", color = Selo.GreenDark, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            else Text("Looks right ✓", color = Selo.Red, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50))
                .clickable { app.listingForm.value?.let { app.listingForm.value = it.copy(reviewed = it.reviewed + key) } }.border(1.dp, Selo.Red, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        content()
    }
}

/* ============================================================ 5. preview + post */

/** Shows the listing exactly as the buyer feed will, then posts it (status pending_review → auto-approved). */
@Composable
fun PreviewListingScreen(onBack: () -> Unit, onPosted: () -> Unit) {
    val app = LocalAppState.current
    val f = app.listingForm.value ?: return onBack()
    val u = app.session.value
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val video = app.listingMediaType.value == "video"
    val price = Fees.kwachaToNgwee(f.price.toDoubleOrNull() ?: 0.0)
    val preview = Listing("preview", u?.uid ?: "", u?.storeName?.ifBlank { u.fullName } ?: "", f.title, f.description, price, f.category, f.district,
        videoUrl = if (video) app.capturedMedia.firstOrNull()?.toString() else null, mediaType = if (video) "video" else "photo",
        photoUrls = if (video) emptyList() else app.capturedMedia.map { it.toString() }, sellerVerified = u?.status == selo.Nachipa01.data.model.AccountStatus.ACTIVE,
        condition = f.condition, availability = f.availability, province = f.province, district = f.district, tags = f.tags, discountPct = f.discountPct.toDoubleOrNull() ?: 0.0)
    fun post(asDraft: Boolean) {
        busy = true; error = null
        scope.launch {
            val up = app.uploaded.value
            runCatching { app.backend.publishListingV2(PublishRequest(if (video) "video" else "photo", up?.videoPath,
                // photos in the seller's final order (they may have been reordered on the review screen)
                if (video) emptyList() else app.capturedMedia.mapNotNull { up?.photoByUri?.get(it.toString()) },
                if (video) up?.thumbPath else app.capturedMedia.firstOrNull()?.let { up?.photoByUri?.get(it.toString()) }, app.videoDurationMs.value, f.title, f.description, price, f.category, f.condition, f.availability, f.province, f.district, f.location, f.tags,
                f.discountPct.toDoubleOrNull() ?: 0.0, f.aiUsed, asDraft)) }.onSuccess { app.resetListingFlow(); app.listingForm.value = null; onPosted() }.onFailure { error = it.message }
            busy = false
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        FeedCard(preview, active = true, liked = false, saved = false, following = false, actions = CardActions({}, {}, {}, {}, {}, {}, {}, { _, _, _ -> }))
        Row(Modifier.statusBarsPadding().padding(top = 40.dp, start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "Back to edit", tint = Color.White) }
            Text("PREVIEW · how buyers will see it", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.background(Selo.Purple, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorText(error)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("Save draft", Modifier.weight(1f)) { post(true) }
                PrimaryButton("Post listing", Modifier.weight(1.4f), loading = busy) { post(false) }
            }
            Sub("Posted listings are checked automatically and go live straight away unless something needs a closer look.")
        }
    }
}
