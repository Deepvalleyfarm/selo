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
object LastFix { var at: Pair<Double, Double>? = null; var time = 0L; var accuracyM: Float? = null
    fun fresh(maxAgeMs: Long = 120_000) = at?.takeIf { System.currentTimeMillis() - time < maxAgeMs }
    fun set(p: Pair<Double, Double>, acc: Float?) { at = p; accuracyM = acc; time = System.currentTimeMillis() } }

private fun hasPermission(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
/** "Precise" location (Android 12+ lets people allow only an approximate ~1–3 km position). */
fun hasPreciseLocation(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** A position fix with its accuracy radius in metres (68 % confidence, as reported by Android). */
data class Fix(val lat: Double, val lng: Double, val accuracyM: Float?) { val at get() = lat to lng }

/** Accuracy we aim for before stopping the GPS: a house/shop entrance, not just the street. */
const val GOOD_ACCURACY_M = 25f

/**
 * Current position, as accurate as the phone can manage in a few seconds:
 * 1. a reused fix from the last 30 s if it's already good,
 * 2. live high-accuracy fused updates, keeping the best fix and stopping as soon as one is within [GOOD_ACCURACY_M]
 *    (or after [maxWaitMs]) — the first GPS reading is often 50–200 m off, so we don't stop at the first one,
 * 3. the fused last-known fix (≤ 2 min old), then the platform GPS / network providers.
 * Null if not permitted or nothing answers.
 */
@SuppressLint("MissingPermission")
suspend fun currentFix(ctx: Context, maxWaitMs: Long = 12_000): Fix? {
    if (!hasPermission(ctx)) return null
    LastFix.fresh(30_000)?.let { p -> if ((LastFix.accuracyM ?: 999f) <= GOOD_ACCURACY_M) return Fix(p.first, p.second, LastFix.accuracyM) }
    var best: android.location.Location? = null
    fun keep(l: android.location.Location?) { if (l != null && (best == null || (l.hasAccuracy() && l.accuracy < (best!!.takeIf { it.hasAccuracy() }?.accuracy ?: Float.MAX_VALUE)))) best = l }
    if (Build.FINGERPRINT != "robolectric") runCatching {
        val fused = LocationServices.getFusedLocationProviderClient(ctx)
        val priority = if (hasPreciseLocation(ctx)) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        withTimeoutOrNull(maxWaitMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                val cb = object : com.google.android.gms.location.LocationCallback() {
                    override fun onLocationResult(r: com.google.android.gms.location.LocationResult) {
                        r.locations.forEach(::keep)
                        if ((best?.accuracy ?: Float.MAX_VALUE) <= GOOD_ACCURACY_M && cont.isActive) cont.resume(Unit)
                    }
                }
                fused.requestLocationUpdates(LocationRequest.Builder(priority, 1_000).setMinUpdateIntervalMillis(500).setWaitForAccurateLocation(true)
                    .setMaxUpdateAgeMillis(10_000).build(), ContextCompat.getMainExecutor(ctx), cb)
                cont.invokeOnCancellation { fused.removeLocationUpdates(cb) }
                // resume path also has to stop updates
                cont.context[kotlinx.coroutines.Job]?.invokeOnCompletion { fused.removeLocationUpdates(cb) }
            }
        }
        if (best == null) keep(fused.lastLocation.await()?.takeIf { System.currentTimeMillis() - it.time < 120_000 })
    }
    if (best == null) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        providers.forEach { p -> keep(runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.takeIf { System.currentTimeMillis() - it.time < 120_000 }) }
        if (best == null) providers.firstOrNull()?.let { p ->
            keep(withTimeoutOrNull(12_000) {
                suspendCancellableCoroutine { cont ->
                    val sig = androidx.core.os.CancellationSignal()
                    cont.invokeOnCancellation { sig.cancel() }
                    LocationManagerCompat.getCurrentLocation(lm, p, sig, ContextCompat.getMainExecutor(ctx)) { loc -> if (cont.isActive) cont.resume(loc) }
                }
            })
        }
    }
    return best?.let { l -> Fix(l.latitude, l.longitude, l.takeIf { it.hasAccuracy() }?.accuracy).also { LastFix.set(it.at, it.accuracyM) } }
}

/** Position only (callers that don't care about accuracy). */
suspend fun currentLocation(ctx: Context, maxWaitMs: Long = 12_000): Pair<Double, Double>? = currentFix(ctx, maxWaitMs)?.at

/**
 * "Use my location" with every step explained: asks for permission, offers to switch location on (system dialog),
 * waits for a fix, and reports exactly what went wrong if it can't.
 */
class Locator internal constructor(private val start: (onFix: (Fix?, String?) -> Unit) -> Unit) {
    fun locate(onFix: (Fix?, String?) -> Unit) = start(onFix)
}

@Composable
fun rememberLocator(): Locator {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<((Fix?, String?) -> Unit)?>(null) }
    fun fetch() = scope.launch {
        val cb = pending ?: return@launch
        val fix = currentFix(ctx)
        cb(fix, if (fix == null) "We couldn't get a GPS fix. Move near a window or outside, or search / pick the spot on the map." else null)
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
        if (r.values.any { it }) { if (r[Manifest.permission.ACCESS_FINE_LOCATION] != true) android.widget.Toast.makeText(ctx,
            "Precise location is off, so your position may be off by a kilometre or more. Check the pin.", android.widget.Toast.LENGTH_LONG).show()
            checkSettingsThenFetch() }
        else { pending?.invoke(null, "Location permission was denied. Allow it in Settings, or search / pick the spot on the map."); pending = null }
    }
    return remember { Locator { cb ->
        pending = cb
        // Ask again when only "approximate" was allowed: Android offers the upgrade to precise.
        if (hasPreciseLocation(ctx)) checkSettingsThenFetch()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    } }
}
