package selo.Nachipa01.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Remembers the last fix for the session so search distances and parcel "From" don't wait for GPS again. */
object LastFix { var at: Pair<Double, Double>? = null; var time = 0L
    fun fresh(maxAgeMs: Long = 120_000) = at?.takeIf { System.currentTimeMillis() - time < maxAgeMs } }

private fun hasPermission(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * Current position: Google Play fused location (high accuracy, fresh fix, 15 s), then its last known fix, then the
 * platform GPS/network providers. Null if not permitted or nothing answers.
 */
@SuppressLint("MissingPermission")
suspend fun currentLocation(ctx: Context): Pair<Double, Double>? {
    if (!hasPermission(ctx)) return null
    LastFix.fresh(30_000)?.let { return it }
    if (Build.FINGERPRINT != "robolectric") runCatching {
        val fused = LocationServices.getFusedLocationProviderClient(ctx)
        val cts = CancellationTokenSource()
        val fix = withTimeoutOrNull(15_000) {
            fused.getCurrentLocation(CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY).setMaxUpdateAgeMillis(20_000)
                .setDurationMillis(15_000).build(), cts.token).await()
        } ?: run { cts.cancel(); fused.lastLocation.await() }
        fix?.let { return (it.latitude to it.longitude).also { p -> LastFix.at = p; LastFix.time = System.currentTimeMillis() } }
    }
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    providers.forEach { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.takeIf { System.currentTimeMillis() - it.time < 120_000 }?.let { return it.latitude to it.longitude } }
    val p = providers.firstOrNull() ?: return null
    return withTimeoutOrNull(12_000) {
        suspendCancellableCoroutine { cont ->
            val sig = androidx.core.os.CancellationSignal()
            cont.invokeOnCancellation { sig.cancel() }
            LocationManagerCompat.getCurrentLocation(lm, p, sig, ContextCompat.getMainExecutor(ctx)) { loc -> if (cont.isActive) cont.resume(loc?.let { it.latitude to it.longitude }) }
        }
    }?.also { LastFix.at = it; LastFix.time = System.currentTimeMillis() }
}

/**
 * "Use my location" with every step explained: asks for permission, offers to switch location on (system dialog),
 * waits for a fix, and reports exactly what went wrong if it can't.
 */
class Locator internal constructor(private val start: (onFix: (Pair<Double, Double>?, String?) -> Unit) -> Unit) {
    fun locate(onFix: (Pair<Double, Double>?, String?) -> Unit) = start(onFix)
}

@Composable
fun rememberLocator(): Locator {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<((Pair<Double, Double>?, String?) -> Unit)?>(null) }
    fun fetch() = scope.launch {
        val cb = pending ?: return@launch
        val at = currentLocation(ctx)
        cb(at, if (at == null) "We couldn't get a GPS fix. Move near a window or outside, or search / pick the spot on the map." else null)
        pending = null
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) fetch()
        else { pending?.invoke(null, "Location is switched off. Turn it on, or search / pick the spot on the map."); pending = null }
    }
    fun checkSettingsThenFetch() {
        if (Build.FINGERPRINT == "robolectric") { fetch(); return }
        val req = LocationSettingsRequest.Builder().addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000).build()).setAlwaysShow(true).build()
        LocationServices.getSettingsClient(ctx).checkLocationSettings(req)
            .addOnSuccessListener { fetch() }
            .addOnFailureListener { e -> if (e is ResolvableApiException) runCatching { settings.launch(IntentSenderRequest.Builder(e.resolution).build()) }.onFailure { fetch() } else fetch() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) checkSettingsThenFetch()
        else { pending?.invoke(null, "Location permission was denied. Allow it in Settings, or search / pick the spot on the map."); pending = null }
    }
    return remember { Locator { cb ->
        pending = cb
        if (hasPermission(ctx)) checkSettingsThenFetch()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    } }
}
