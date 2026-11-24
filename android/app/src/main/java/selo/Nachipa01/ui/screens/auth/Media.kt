package selo.Nachipa01.ui.screens.auth

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/** Saves a captured bitmap as a compressed JPEG (<1 MB target) in cache and returns its Uri. */
fun Context.saveJpeg(bmp: Bitmap): Uri {
    val f = File(cacheDir, "capture-${UUID.randomUUID()}.jpg")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    return Uri.fromFile(f)
}

/** Returns a launcher that opens the camera and hands back a saved photo Uri. */
@Composable
fun rememberPhotoCapture(onCaptured: (Uri) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) onCaptured(ctx.saveJpeg(bmp))
    }
    return { launcher.launch(null) }
}

data class DetectedAddress(val province: String, val district: String, val area: String, val lat: Double, val lng: Double)

/** Fills province, district and area from the device location (PRD: auto-detect address). */
@SuppressLint("MissingPermission")
suspend fun Context.detectAddress(): DetectedAddress? = withContext(Dispatchers.IO) {
    val lm = getSystemService(LocationManager::class.java) ?: return@withContext null
    val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return@withContext null
    @Suppress("DEPRECATION")
    val a = runCatching { Geocoder(this@detectAddress, Locale.ENGLISH).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() }.getOrNull()
    DetectedAddress(
        province = a?.adminArea?.removeSuffix(" Province") ?: "Lusaka",
        district = a?.subAdminArea ?: a?.locality ?: "Lusaka",
        area = a?.subLocality ?: a?.thoroughfare ?: a?.locality ?: "",
        lat = loc.latitude, lng = loc.longitude,
    )
}
