package selo.Nachipa01.ui.maps

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Build
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.clustering.ClusterManager
import com.google.maps.android.clustering.view.DefaultClusterRenderer
import selo.Nachipa01.BuildConfig
import java.util.WeakHashMap
import com.google.android.gms.maps.model.LatLng as GLatLng

/**
 * Google Maps SDK for Android, used for display only (no Places/Directions calls from the phone).
 *
 * Cost and performance rules:
 *  - Lazy: nothing Maps-related is created until the first map is actually on screen.
 *  - Reused: one MapView per Activity (MapViewPool) moves between screens instead of a new map per screen.
 *  - Diffed: markers and polylines are updated in place (positions animate), never cleared and re-added per GPS fix.
 * Builds without MAPS_ANDROID_API_KEY (and Robolectric tests) use the fallback composable instead.
 */
object Maps {
    val enabled: Boolean get() = BuildConfig.MAPS_ENABLED && Build.FINGERPRINT != "robolectric"
    private var initialized = false
    fun ensureInitialized(ctx: Context) {
        if (initialized) return
        initialized = true
        runCatching { MapsInitializer.initialize(ctx.applicationContext, MapsInitializer.Renderer.LATEST) { } }
    }
}

/* ------------------------------------------------------------------ scene model */

enum class PinKind { PICKUP, DROPOFF, RIDER, STORE, BUYER, DONE }
data class MapPin(val id: String, val at: GLatLng, val kind: PinKind, val label: String? = null, val title: String? = null, val rotation: Float = 0f)
data class MapLine(val id: String, val points: List<GLatLng>, val color: Int, val widthDp: Float = 6f, val dashed: Boolean = false)
sealed interface MapCamera {
    /** Fit all points once per [key] (recomputed only when the key changes). */
    data class Fit(val points: List<GLatLng>, val key: String, val paddingDp: Int = 56) : MapCamera
    /** Navigation camera: follows the rider, rotated to the heading, tilted like an in-car view. */
    data class Follow(val target: GLatLng, val bearing: Float, val zoom: Float = 17.2f, val tilt: Float = 50f) : MapCamera
    /** Starts at a point; afterwards the user moves the map. */
    data class Center(val target: GLatLng, val zoom: Float = 16.5f, val key: String = "") : MapCamera
}
data class MapScene(val pins: List<MapPin> = emptyList(), val lines: List<MapLine> = emptyList(), val camera: MapCamera? = null,
                    val clusterStores: Boolean = false, val myLocation: Boolean = false)

class MapCallbacks(
    val onCameraIdle: ((GLatLng) -> Unit)? = null,
    val onCameraMoveStarted: ((byUser: Boolean) -> Unit)? = null,
    val onPinClick: ((MapPin) -> Unit)? = null,
)

/* ------------------------------------------------------------------ pool */

private class Claim(val container: FrameLayout, var scene: MapScene, var callbacks: MapCallbacks)

private class PooledMap(val view: MapView) {
    var map: GoogleMap? = null
    val claims = ArrayDeque<Claim>()
    val renderer = SceneRenderer(view.context)
    fun top() = claims.lastOrNull()
}

private object MapViewPool {
    private val pool = WeakHashMap<Activity, PooledMap>()

    fun obtain(activity: Activity): PooledMap = pool.getOrPut(activity) {
        Maps.ensureInitialized(activity)
        val mv = MapView(activity)
        mv.onCreate(null)
        val pm = PooledMap(mv)
        (activity as? LifecycleOwner)?.lifecycle?.let { lc ->
            if (lc.currentState.isAtLeast(Lifecycle.State.STARTED)) mv.onStart()
            if (lc.currentState.isAtLeast(Lifecycle.State.RESUMED)) mv.onResume()
            lc.addObserver(LifecycleEventObserver { _, e ->
                when (e) {
                    Lifecycle.Event.ON_START -> mv.onStart(); Lifecycle.Event.ON_RESUME -> mv.onResume()
                    Lifecycle.Event.ON_PAUSE -> mv.onPause(); Lifecycle.Event.ON_STOP -> mv.onStop()
                    Lifecycle.Event.ON_DESTROY -> { mv.onDestroy(); pool.remove(activity) }
                    else -> Unit
                }
            })
        }
        mv.getMapAsync { gm ->
            pm.map = gm
            gm.uiSettings.apply { isMapToolbarEnabled = false; isCompassEnabled = false; isMyLocationButtonEnabled = false; isIndoorLevelPickerEnabled = false }
            runCatching { gm.setMapStyle(MapStyleOptions(CLEAN_STYLE)) }
            gm.isBuildingsEnabled = false; gm.isIndoorEnabled = false; gm.isTrafficEnabled = false
            pm.top()?.let { pm.renderer.attach(gm, it.scene, it.callbacks, reset = true) }
        }
        pm
    }

    fun claim(pm: PooledMap, c: Claim) {
        pm.claims.addLast(c); moveTo(pm, c)
    }
    fun release(pm: PooledMap, c: Claim) {
        val wasTop = pm.top() === c
        pm.claims.remove(c)
        if (wasTop) pm.top()?.let { moveTo(pm, it) } ?: (pm.view.parent as? ViewGroup)?.removeView(pm.view)
    }
    private fun moveTo(pm: PooledMap, c: Claim) {
        (pm.view.parent as? ViewGroup)?.takeIf { it !== c.container }?.removeView(pm.view)
        if (pm.view.parent == null) c.container.addView(pm.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pm.map?.let { pm.renderer.attach(it, c.scene, c.callbacks, reset = true) }
    }
    fun update(pm: PooledMap, c: Claim) { if (pm.top() === c) pm.map?.let { pm.renderer.attach(it, c.scene, c.callbacks, reset = false) } }
}

private tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

/**
 * A Google map showing [scene]. [fallback] renders instead when Maps isn't available (no key, tests).
 */
@Composable
fun SeloMap(scene: MapScene, modifier: Modifier = Modifier, callbacks: MapCallbacks = remember { MapCallbacks() }, fallback: @Composable () -> Unit) {
    if (!Maps.enabled) { fallback(); return }
    val activity = LocalContext.current.activity() ?: run { fallback(); return }
    val pm = remember(activity) { MapViewPool.obtain(activity) }
    val claim = remember { Claim(FrameLayout(activity), scene, callbacks) }
    DisposableEffect(pm) { MapViewPool.claim(pm, claim); onDispose { MapViewPool.release(pm, claim) } }
    SideEffect { claim.scene = scene; claim.callbacks = callbacks; MapViewPool.update(pm, claim) }
    AndroidView(factory = { claim.container }, modifier = modifier)
}

/* ------------------------------------------------------------------ renderer (diffing) */

private class StoreItem(val pin: MapPin) : ClusterItem {
    override fun getPosition() = pin.at
    override fun getTitle() = pin.title
    override fun getSnippet(): String? = null
    override fun getZIndex(): Float = 0f
}

private class SceneRenderer(private val ctx: Context) {
    private var map: GoogleMap? = null
    private val markers = HashMap<String, Marker>()
    private val pins = HashMap<String, MapPin>()
    private val lines = HashMap<String, Polyline>()
    private var lastCameraKey: String? = null
    private var cluster: ClusterManager<StoreItem>? = null
    private var clusterIds: Set<String> = emptySet()
    private var callbacks = MapCallbacks()
    private var userMoving = false

    fun attach(gm: GoogleMap, scene: MapScene, cb: MapCallbacks, reset: Boolean) {
        if (reset || map !== gm) {
            gm.clear(); markers.clear(); pins.clear(); lines.clear(); lastCameraKey = null; cluster = null; clusterIds = emptySet()
            map = gm; callbacks = cb
            gm.setOnCameraMoveStartedListener { r -> userMoving = r == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE; callbacks.onCameraMoveStarted?.invoke(userMoving) }
            gm.setOnCameraIdleListener { cluster?.onCameraIdle(); callbacks.onCameraIdle?.invoke(gm.cameraPosition.target) }
            gm.setOnMarkerClickListener { m -> if (cluster?.onMarkerClick(m) == true) true else { pins[m.tag as? String]?.let { p -> callbacks.onPinClick?.invoke(p) }; callbacks.onPinClick != null } }
        }
        callbacks = cb
        renderPins(gm, scene)
        renderLines(gm, scene.lines)
        moveCamera(gm, scene.camera)
    }

    private fun renderPins(gm: GoogleMap, scene: MapScene) {
        val (stores, others) = scene.pins.partition { scene.clusterStores && it.kind == PinKind.STORE }
        if (stores.isNotEmpty()) {
            val cm = cluster ?: ClusterManager<StoreItem>(ctx, gm).also { c ->
                c.renderer = object : DefaultClusterRenderer<StoreItem>(ctx, gm, c) {
                    override fun onBeforeClusterItemRendered(item: StoreItem, o: MarkerOptions) { o.icon(Icons.get(ctx, PinKind.STORE, null)).anchor(.5f, 1f).title(item.pin.title) }
                }
                c.setOnClusterItemClickListener { i -> callbacks.onPinClick?.invoke(i.pin); true }
                c.setOnClusterClickListener { cl -> gm.animateCamera(CameraUpdateFactory.newLatLngZoom(cl.position, gm.cameraPosition.zoom + 2)); true }
                cluster = c
            }
            val ids = stores.map { it.id }.toSet()
            if (ids != clusterIds) { cm.clearItems(); cm.addItems(stores.map(::StoreItem)); cm.cluster(); clusterIds = ids }
        }
        val keep = others.map { it.id }.toSet()
        markers.keys.filter { it !in keep }.forEach { markers.remove(it)?.remove(); pins.remove(it) }
        for (p in others) {
            val m = markers[p.id]
            val old = pins[p.id]
            if (m == null) {
                markers[p.id] = gm.addMarker(MarkerOptions().position(p.at).icon(Icons.get(ctx, p.kind, p.label)).title(p.title)
                    .anchor(.5f, if (p.kind == PinKind.RIDER || p.kind == PinKind.BUYER) .5f else 1f).flat(p.kind == PinKind.RIDER).rotation(p.rotation)
                    .zIndex(if (p.kind == PinKind.RIDER) 10f else 1f))!!.also { it.tag = p.id }
            } else {
                if (old?.kind != p.kind || old.label != p.label) m.setIcon(Icons.get(ctx, p.kind, p.label))
                if (old?.at != p.at) animate(m, p.at)
                if (old?.rotation != p.rotation) m.rotation = p.rotation
            }
            pins[p.id] = p
        }
    }

    /** Smooth glide between GPS fixes (Yango-style moving rider). */
    private fun animate(m: Marker, to: GLatLng) {
        val from = m.position
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900; interpolator = LinearInterpolator()
            addUpdateListener { a -> val f = a.animatedFraction; m.position = GLatLng(from.latitude + (to.latitude - from.latitude) * f, from.longitude + (to.longitude - from.longitude) * f) }
            start()
        }
    }

    private fun renderLines(gm: GoogleMap, ls: List<MapLine>) {
        val keep = ls.map { it.id }.toSet()
        lines.keys.filter { it !in keep }.forEach { lines.remove(it)?.remove() }
        val d = ctx.resources.displayMetrics.density
        for (l in ls) {
            val pl = lines[l.id]
            if (pl == null) lines[l.id] = gm.addPolyline(PolylineOptions().addAll(l.points).color(l.color).width(l.widthDp * d).jointType(JointType.ROUND)
                .startCap(RoundCap()).endCap(RoundCap()).apply { if (l.dashed) pattern(listOf(Dash(18f * d), Gap(10f * d))) })
            else if (pl.points != l.points) pl.points = l.points
        }
    }

    private fun moveCamera(gm: GoogleMap, cam: MapCamera?) {
        when (cam) {
            null -> Unit
            is MapCamera.Fit -> if (cam.key != lastCameraKey && cam.points.isNotEmpty()) {
                lastCameraKey = cam.key
                if (cam.points.size == 1) gm.moveCamera(CameraUpdateFactory.newLatLngZoom(cam.points[0], 15.5f))
                else {
                    val b = LatLngBounds.builder().apply { cam.points.forEach { include(it) } }.build()
                    val pad = (cam.paddingDp * ctx.resources.displayMetrics.density).toInt()
                    runCatching { gm.moveCamera(CameraUpdateFactory.newLatLngBounds(b, pad)) }
                        .onFailure { gm.moveCamera(CameraUpdateFactory.newLatLngZoom(b.center, 13f)) } // before layout
                }
            }
            is MapCamera.Follow -> if (!userMoving) gm.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(cam.target).zoom(cam.zoom).bearing(cam.bearing).tilt(cam.tilt).build()), 900, null)
            is MapCamera.Center -> if (cam.key != lastCameraKey) { lastCameraKey = cam.key; gm.moveCamera(CameraUpdateFactory.newLatLngZoom(cam.target, cam.zoom)) }
        }
    }
}

/** Marker bitmaps drawn once and cached (no image assets, crisp at any density). */
private object Icons {
    private val cache = HashMap<String, BitmapDescriptor>()
    fun get(ctx: Context, kind: PinKind, label: String?): BitmapDescriptor = cache.getOrPut("$kind|$label") {
        val d = ctx.resources.displayMetrics.density
        val bmp = when (kind) {
            PinKind.RIDER -> circle(d, 30, 0xFF1D6A45.toInt(), arrow = true)
            PinKind.BUYER -> circle(d, 22, 0xFF2563EB.toInt(), null)
            else -> pin(d, when (kind) { PinKind.PICKUP -> 0xFFC8102E.toInt(); PinKind.DROPOFF -> 0xFF1F2937.toInt(); PinKind.STORE -> 0xFFC8102E.toInt(); else -> 0xFF9CA3AF.toInt() },
                label ?: when (kind) { PinKind.PICKUP -> "P"; PinKind.DROPOFF -> "D"; PinKind.STORE -> "S"; else -> "✓" })
        }
        BitmapDescriptorFactory.fromBitmap(bmp)
    }
    private fun pin(d: Float, color: Int, text: String): Bitmap {
        val w = (34 * d).toInt(); val h = (46 * d).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x33000000; c.drawOval(w * .3f, h - 6 * d, w * .7f, h.toFloat(), p)
        p.color = color
        val r = w / 2f - 2 * d
        c.drawPath(Path().apply { moveTo(w / 2f - r * .55f, r * 1.6f); lineTo(w / 2f, h - 4 * d); lineTo(w / 2f + r * .55f, r * 1.6f); close() }, p)
        c.drawCircle(w / 2f, r + 2 * d, r, p)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(w / 2f, r + 2 * d, r * .62f, p)
        p.color = color; p.textSize = 13 * d; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
        c.drawText(text.take(2), w / 2f, r + 2 * d + 4.5f * d, p)
        return b
    }
    private fun circle(d: Float, sizeDp: Int, color: Int, arrow: Boolean?): Bitmap {
        val s = (sizeDp * d).toInt(); val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        p.color = color; c.drawCircle(s / 2f, s / 2f, s / 2f - 3 * d, p)
        if (arrow == true) { p.color = 0xFFFFFFFF.toInt()
            c.drawPath(Path().apply { moveTo(s / 2f, s * .22f); lineTo(s * .72f, s * .74f); lineTo(s / 2f, s * .62f); lineTo(s * .28f, s * .74f); close() }, p) }
        return b
    }
}

/** Quiet map style: fewer POIs and transit labels so pins and the route stand out. */
private const val CLEAN_STYLE = """[
 {"featureType":"poi.business","stylers":[{"visibility":"off"}]},
 {"featureType":"poi","elementType":"labels.icon","stylers":[{"saturation":-80},{"lightness":20}]},
 {"featureType":"transit","stylers":[{"visibility":"off"}]},
 {"featureType":"road","elementType":"labels.icon","stylers":[{"visibility":"off"}]}
]"""
