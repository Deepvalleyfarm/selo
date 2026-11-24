package selo.Nachipa01.ui.screens.seller

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo
import java.io.File
import java.util.UUID

/** D1: choose source — record video, take photos, gallery, WhatsApp share, or a text prompt for Gemini. */
@Composable
fun AddListingScreen(onClose: () -> Unit, onCamera: () -> Unit, onProcess: () -> Unit) {
    val app = LocalAppState.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) { app.capturedMedia.addAll(uris); onProcess() }
    }
    var prompt by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Add a listing", "Record or upload — our AI does the rest", onBack = onClose)
        FormColumn {
            if (app.capturedMedia.isNotEmpty()) NoteBox("${app.capturedMedia.size} file(s) received from WhatsApp. Tap Continue to process.", Tone.OK)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceTile(Icons.Outlined.Videocam, "Record video", "15–30 s, best results", true, Modifier.weight(1f), onCamera)
                SourceTile(Icons.Outlined.PhotoCamera, "Take photos", "Up to 10 stills", false, Modifier.weight(1f), onCamera)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SourceTile(Icons.Outlined.PhotoLibrary, "From gallery", "Video or photos", false, Modifier.weight(1f)) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                SourceTile(Icons.Outlined.Chat, "From WhatsApp", "Share media to Selo", false, Modifier.weight(1f)) {
                    if (app.capturedMedia.isNotEmpty()) onProcess() }
            }
            Text("or describe it", color = Selo.Mute, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).border(1.4.dp, Selo.Purple, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = Selo.Purple, modifier = Modifier.size(14.dp))
                    Text(" AI LISTING GENERATOR", color = Selo.Purple, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                }
                OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), placeholder = { Text("Fresh tomatoes, 5 kg bag, picked this morning in Chongwe, fast sale", fontStyle = FontStyle.Italic) },
                    minLines = 2, shape = RoundedCornerShape(10.dp))
                Button({ app.listingPrompt.value = prompt; onProcess() }, enabled = prompt.length > 8 || app.capturedMedia.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Selo.Purple), shape = RoundedCornerShape(50), modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(14.dp)); Text(" Generate listing")
                }
            }
            SeloCard { Text("Tips for a great Selo advert", fontWeight = FontWeight.Bold); Sub("Good daylight · product in the centre · show size and quantity · say the price out loud in the video") }
        }
    }
}

@Composable
private fun SourceTile(icon: ImageVector, title: String, sub: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.background(if (filled) Selo.Red else Color.White, RoundedCornerShape(14.dp)).border(1.4.dp, Selo.Red, RoundedCornerShape(14.dp))
        .clickable(onClick = onClick).padding(14.dp)) {
        Icon(icon, null, tint = if (filled) Color.White else Selo.Red, modifier = Modifier.size(26.dp))
        Text(title, color = if (filled) Color.White else Selo.Red, fontWeight = FontWeight.ExtraBold)
        Text(sub, color = if (filled) Color(0xFFFFD6DC) else Selo.Mute, fontSize = 11.sp)
    }
}

/** D2: in-app CameraX camera with VIDEO (30 s cap) and PHOTO (10 stills) modes. */
@SuppressLint("MissingPermission")
@Composable
fun CameraScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val app = LocalAppState.current
    var granted by remember { mutableStateOf(listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it[Manifest.permission.CAMERA] == true }
    LaunchedEffect(Unit) { if (!granted) perm.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }

    var videoMode by remember { mutableStateOf(true) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val controller = remember { LifecycleCameraController(ctx).apply { cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA } }
    LaunchedEffect(videoMode, granted) {
        if (granted) {
            controller.setEnabledUseCases(if (videoMode) CameraController.VIDEO_CAPTURE else CameraController.IMAGE_CAPTURE)
            controller.bindToLifecycle(owner)
        }
    }
    LaunchedEffect(recording) { seconds = 0; while (recording != null) { delay(1000); seconds++; if (seconds >= 30) { recording?.stop(); recording = null } } }

    fun shoot() {
        if (videoMode) {
            recording?.let { it.stop(); recording = null; return }
            val f = File(ctx.cacheDir, "listing-${UUID.randomUUID()}.mp4")
            recording = controller.startRecording(FileOutputOptions.Builder(f).build(), AudioConfig.create(true), ContextCompat.getMainExecutor(ctx)) { e ->
                if (e is VideoRecordEvent.Finalize) {
                    if (!e.hasError()) { app.capturedMedia.add(Uri.fromFile(f)); onDone() } else error = "Recording failed. Try again."
                }
            }
        } else {
            if (app.capturedMedia.size >= 10) return
            val f = File(ctx.cacheDir, "listing-${UUID.randomUUID()}.jpg")
            controller.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(ctx),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(o: ImageCapture.OutputFileResults) { app.capturedMedia.add(Uri.fromFile(f)) }
                    override fun onError(e: ImageCaptureException) { error = "Photo failed. Try again." }
                })
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) AndroidView({ PreviewView(it).apply { this.controller = controller } }, Modifier.fillMaxSize())
        else Text("Camera permission is needed to record your advert.", color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        // framing guide
        Box(Modifier.align(Alignment.Center).fillMaxWidth(.78f).fillMaxHeight(.5f).border(2.dp, Color.White.copy(alpha = .55f), RoundedCornerShape(16.dp)))
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.Outlined.Close, "Close", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                if (videoMode) Text("● 00:${"%02d".format(seconds)} / 00:30", color = Color.White, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.background(Color.Black.copy(alpha = .45f), RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                Spacer(Modifier.weight(1f)); Spacer(Modifier.width(48.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                listOf("✓ Good light", "Product centred", "Hold steady").forEach { Text(it, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Selo.Green.copy(alpha = .85f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp)) }
            }
            error?.let { Text(it, color = Color.White, modifier = Modifier.background(Selo.Red, RoundedCornerShape(8.dp)).padding(6.dp)) }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                listOf("PHOTO" to false, "VIDEO" to true).forEach { (l, v) ->
                    Text(l, color = if (videoMode == v) Color.White else Color.White.copy(alpha = .6f), fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                        modifier = Modifier.clickable(enabled = recording == null) { videoMode = v })
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (app.capturedMedia.isNotEmpty()) Text("${app.capturedMedia.size}/10", color = Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Brush.linearGradient(listOf(Color(0xFFC0392B), Color(0xFFE67E22))), RoundedCornerShape(8.dp)).padding(10.dp))
                }
                Box(Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).clickable(enabled = granted) { shoot() }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (recording != null) 28.dp else 56.dp).background(if (videoMode) Color(0xFFFF3B4F) else Color.White,
                        if (recording != null) RoundedCornerShape(6.dp) else CircleShape))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (!videoMode && app.capturedMedia.isNotEmpty()) TextButton(onDone) { Text("Done", color = Color.White, fontWeight = FontWeight.ExtraBold) }
                    else IconButton({ controller.cameraSelector = if (controller.cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA }) {
                        Icon(Icons.Outlined.Cameraswitch, "Flip camera", tint = Color.White) }
                }
            }
        }
    }
}

private val aiSteps = listOf(
    "Quality & content check" to "Clear, safe to publish", "Background clean-up" to "Photos enhanced",
    "Object detection" to "Product, quantity and size", "Writing title, description, price & tags" to "Gemini · comparing prices nearby",
    "Selo Creative Engine" to "Cutting your 15 s advert",
)

/** D3: upload + five-step AI checklist. The seller never sees a blank wait. */
@Composable
fun AiProcessingScreen(onReady: () -> Unit, onFail: () -> Unit) {
    val app = LocalAppState.current
    var upload by remember { mutableFloatStateOf(0f) }
    var step by remember { mutableIntStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching {
            val paths = if (app.capturedMedia.isEmpty()) emptyList() else app.backend.uploadMedia(app.capturedMedia.toList()) { upload = it }
            upload = 1f
            app.aiDraft.value = app.backend.runAiPipeline(paths, app.listingPrompt.value) { step = it }
        }.onSuccess { onReady() }.onFailure { error = it.message ?: "Processing failed." }
    }
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Selo.Purple, modifier = Modifier.size(14.dp))
            Text(" SELO AI AT WORK", color = Selo.Purple, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
        }
        H("Building your listing", 20)
        Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.linearGradient(listOf(Color(0xFFB0281A), Color(0xFFE5671F), Color(0xFFF2B33D))), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PlayCircle, null, tint = Color.White, modifier = Modifier.size(44.dp)) }
        Row { Text("Uploading ${app.capturedMedia.size} file(s)", color = Selo.Mute, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("${(upload * 100).toInt()}%", fontWeight = FontWeight.Bold) }
        LinearProgressIndicator({ upload }, Modifier.fillMaxWidth(), color = Selo.Red, trackColor = Selo.Line)
        aiSteps.forEachIndexed { i, (t, s) ->
            ChecklistRow(when { i < step -> StepState.DONE; i == step -> StepState.RUNNING; else -> StepState.WAITING }, t, s)
        }
        Text("Usually done in under 30 seconds", color = Selo.Mute, fontStyle = FontStyle.Italic, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        if (error != null) { ErrorText(error); OutlineButton("Go back", onClick = onFail) }
    }
}

/** D4: review the AI draft; every field editable; publish runs the media scan server-side. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewListingScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val app = LocalAppState.current
    val draft = app.aiDraft.value ?: return onBack()
    var title by remember { mutableStateOf(draft.title) }
    var desc by remember { mutableStateOf(draft.description) }
    var price by remember { mutableStateOf("%.2f".format(draft.priceNgwee / 100.0)) }
    var stock by remember { mutableStateOf(draft.stock.toString()) }
    val tags = remember { mutableStateListOf(*draft.tags.toTypedArray()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun save(asDraft: Boolean) {
        busy = true; error = null
        scope.launch {
            runCatching { app.backend.publishListing(draft.copy(title = title, description = desc, priceNgwee = Fees.kwachaToNgwee(price.toDoubleOrNull() ?: 0.0),
                stock = stock.toIntOrNull() ?: 1, tags = tags.toList()), asDraft) }.onSuccess { onDone() }.onFailure { error = it.message }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        SeloHeader("Your Selo listing is ready", "AI did the work — review and publish", onBack = onBack)
        FormColumn {
            Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.linearGradient(listOf(Color(0xFFB0281A), Color(0xFFE5671F), Color(0xFFF2B33D))), RoundedCornerShape(14.dp))) {
                StatusChip("Selo advert", Tone.BRAND); Icon(Icons.Outlined.PlayCircle, null, tint = Color.White, modifier = Modifier.size(42.dp).align(Alignment.Center))
            }
            SeloField("Title", title, { title = it }, trailing = Icons.Outlined.Edit)
            SeloField("Description", desc, { desc = it }, singleLine = false)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeloField("Price (ZMW)", price, { price = it }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                SeloField("Stock", stock, { stock = it }, Modifier.weight(1f), keyboard = KeyboardType.Number)
            }
            if (draft.priceHighNgwee > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Selo.Purple, modifier = Modifier.size(13.dp))
                Text(" AI suggests ${Fees.formatK(draft.priceLowNgwee)}–${Fees.formatK(draft.priceHighNgwee)} based on ${draft.comparables} listings near you",
                    color = Selo.Purple, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SeloChip(draft.category, true) {}
                tags.toList().forEach { t -> SeloChip("#$t ×", false) { tags.remove(t) } }
            }
            ErrorText(error)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("Save draft", Modifier.weight(1f)) { save(true) }
                PrimaryButton("Publish now", Modifier.weight(1.4f), loading = busy, enabled = title.isNotBlank()) { save(false) }
            }
        }
    }
}
