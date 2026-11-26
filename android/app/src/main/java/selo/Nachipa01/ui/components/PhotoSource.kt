package selo.Nachipa01.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.theme.Selo
import java.io.File

/**
 * "Take a photo" (opens the live camera) or "Choose from gallery" (saved photos of an ID, etc.).
 * The picked image is copied into the app's private files so sign-up drafts survive the app being closed.
 * Every outcome is reported: saved, cancelled, camera permission refused, or the image couldn't be read.
 */
@Composable
fun rememberPhotoSource(title: String = "Add a photo", onPhoto: (Uri) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val app = LocalAppState.current
    var open by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<File?>(null) }
    val current by rememberUpdatedState(onPhoto)

    fun deliver(src: Uri?) {
        val saved = src?.let { copyToPrivate(ctx, it) }
        if (saved != null) { current(saved); app.ok("Photo added") } else app.fail("We couldn't read that photo. Try another one.")
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending
        if (ok && f != null && f.length() > 0) { current(Uri.fromFile(f)); app.ok("Photo added") } else app.info("No photo taken.")
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) deliver(uri) else app.info("No photo selected.") }
    fun launchCamera() {
        val dir = File(ctx.filesDir, "captures").apply { mkdirs() }
        val f = File(dir, "photo_${System.currentTimeMillis()}.jpg"); pending = f
        runCatching { camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)) }.onFailure { app.fail("Couldn't open the camera. Choose a photo from your gallery instead.") }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else app.fail("Camera permission is off. Allow it in Settings, or choose a photo from your gallery.")
    }

    if (open) AlertDialog(onDismissRequest = { open = false }, containerColor = Color.White, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SourceRow(Icons.Outlined.PhotoCamera, "Take a photo", "Opens your camera") {
                open = false
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                else permission.launch(Manifest.permission.CAMERA)
            }
            SourceRow(Icons.Outlined.PhotoLibrary, "Choose from gallery", "Use a photo already on your phone") {
                open = false
                runCatching { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.onFailure { app.fail("Couldn't open your gallery.") }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton({ open = false }) { Text("Cancel", color = Selo.Red) } })
    return { open = true }
}

@Composable
private fun SourceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Selo.Red, modifier = Modifier.size(26.dp))
        Column(Modifier.padding(start = 14.dp)) { Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp); Text(sub, color = Selo.Mute, fontSize = 12.sp) }
    }
}

private fun copyToPrivate(ctx: Context, src: Uri): Uri? = runCatching {
    val dir = File(ctx.filesDir, "captures").apply { mkdirs() }
    val f = File(dir, "pick_${System.currentTimeMillis()}.jpg")
    ctx.contentResolver.openInputStream(src)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
    if (f.length() == 0L) null else Uri.fromFile(f)
}.getOrNull()
