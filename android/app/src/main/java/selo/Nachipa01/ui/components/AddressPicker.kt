package selo.Nachipa01.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import selo.Nachipa01.data.model.AddressSuggestion
import selo.Nachipa01.data.model.Place
import selo.Nachipa01.domain.Cities
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.theme.Selo
import kotlin.coroutines.resume

private val isRobolectric = Build.FINGERPRINT == "robolectric"

/**
 * One address field for pickup and delivery everywhere (checkout, parcels, saved addresses):
 * live suggestions (shared store + maps service + neighbourhoods), "Use my location", saved addresses, and "Pick on map".
 * Every confirmed point is saved to the shared platform address store so other users can find it.
 */
@Composable
fun AddressField(
    label: String, value: Place?, onChange: (Place?) -> Unit, modifier: Modifier = Modifier,
    cityId: String? = null, problem: String? = null, showSaved: Boolean = true, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Place,
) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(value == null) }
    var results by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var mapOpen by remember { mutableStateOf(false) }
    var locError by remember { mutableStateOf<String?>(null) }
    val saved by app.backend.savedAddresses().collectAsState(emptyList())
    val cfg by app.config.collectAsState()

    fun confirm(p: Place) {
        editing = false; query = ""; results = emptyList()
        onChange(p)
        if (p.inServiceArea && p.addressId == null) scope.launch { runCatching { app.backend.savePlace(p) }.onSuccess { id -> onChange(p.copy(addressId = id)) } }
    }
    LaunchedEffect(query) {
        searchError = null; slow = false
        // Places API (New) cost rules: nothing below 3 characters, 500 ms debounce, cached results reused on-device.
        if (!selo.Nachipa01.data.maps.PlacesCache.shouldSearch(query, cfg.mapsAutocompleteMinChars)) { results = emptyList(); searching = false; return@LaunchedEffect }
        delay(cfg.mapsAutocompleteDebounceMs.toLong())
        searching = true
        val slowJob = launch { delay(2_500); slow = true }
        runCatching { app.backend.addressSuggestions(query, cityId) }
            .onSuccess { results = it }.onFailure { searchError = "Search isn't responding. Pick the spot on the map instead."; results = emptyList() }
        slowJob.cancel(); searching = false; slow = false
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) scope.launch { resolving = true; locError = null
            val at = currentLocation(ctx)
            if (at == null) locError = "We couldn't get your location. Turn on GPS or pick on the map."
            else runCatching { app.backend.reverseGeocode(at.first, at.second) }.onSuccess(::confirm).onFailure { locError = it.message }
            resolving = false }
        else locError = "Location permission denied. Search for the address or pick it on the map."
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (!editing && value != null) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White)
                .border(1.5.dp, if (problem != null || !value.inServiceArea) Selo.Red else Selo.Field, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Selo.Red)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(value.short, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(listOfNotNull(value.address.takeIf { it != value.label }, value.cityName).joinToString(" · "), color = Selo.Mute, fontSize = 12.sp, maxLines = 2)
                    if (value.approximate) Text("Approximate position — drag the pin on the map for accuracy", color = Selo.AmberDark, fontSize = 11.sp)
                }
                TextButton({ editing = true }) { Text("Change", color = Selo.Red) }
            }
        } else {
            OutlinedTextField(query, { query = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                placeholder = { Text("Search street, landmark or area") }, leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Selo.Red) },
                trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Selo.Red)
                    else if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Outlined.Close, "Clear") } },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Selo.Red, unfocusedContainerColor = Color.White, focusedContainerColor = Color.White))
            if (query.isNotBlank() && query.trim().length < cfg.mapsAutocompleteMinChars) Text("Keep typing — suggestions start after ${cfg.mapsAutocompleteMinChars} letters", color = Selo.Mute, fontSize = 12.sp)
            if (slow) Text("Still searching… slow connection", color = Selo.Mute, fontSize = 12.sp)
            searchError?.let { Text(it, color = Selo.Red, fontSize = 12.sp) }
            if (results.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, Selo.Line, RoundedCornerShape(12.dp))) {
                results.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(color = Selo.Line)
                    Row(Modifier.fillMaxWidth().clickable { scope.launch { resolving = true
                        runCatching { app.backend.placeDetails(s.id) }.onSuccess(::confirm).onFailure { searchError = it.message ?: "Couldn't load that address." }; resolving = false } }
                        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(when (s.source) { "store" -> Icons.Outlined.History; "gazetteer" -> Icons.Outlined.Map; else -> Icons.Outlined.Place }, null, tint = Selo.Mute, modifier = Modifier.size(18.dp))
                        Column(Modifier.padding(start = 10.dp)) { Text(s.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(s.secondary, color = Selo.Mute, fontSize = 12.sp, maxLines = 1) }
                    }
                }
            } else if (!searching && query.trim().length >= cfg.mapsAutocompleteMinChars && searchError == null)
                Text("No matches. Try a nearby landmark, or pick the spot on the map.", color = Selo.Mute, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({ askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                    { Text("Use my location") }, leadingIcon = { Icon(Icons.Outlined.MyLocation, null, Modifier.size(16.dp), tint = Selo.Red) })
                AssistChip({ mapOpen = true }, { Text("Pick on map") }, leadingIcon = { Icon(Icons.Outlined.PinDrop, null, Modifier.size(16.dp), tint = Selo.Red) })
                if (showSaved) saved.forEach { a -> AssistChip({ confirm(a.place) }, { Text(a.nickname) }, leadingIcon = { Icon(Icons.Outlined.Bookmark, null, Modifier.size(16.dp), tint = Selo.Red) }) }
                if (value != null) AssistChip({ editing = false }, { Text("Keep ${value.short.take(18)}") })
            }
            if (resolving) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Selo.Red)
            locError?.let { Text(it, color = Selo.Red, fontSize = 12.sp) }
        }
        val msg = problem ?: if (value != null && !value.inServiceArea) "That location is outside the cities we serve." else null
        msg?.let { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.ErrorOutline, null, tint = Selo.Red, modifier = Modifier.size(16.dp)); Text("  $it", color = Selo.Red, fontSize = 12.sp) } }
    }
    if (mapOpen) MapPicker(initial = value, cityId = cityId, onDismiss = { mapOpen = false }, onConfirm = { mapOpen = false; confirm(it) })
}

/** One-shot current location (GPS, then network). Null if unavailable or not permitted. */
@SuppressLint("MissingPermission")
suspend fun currentLocation(ctx: Context): Pair<Double, Double>? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    providers.forEach { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.takeIf { System.currentTimeMillis() - it.time < 120_000 }?.let { return it.latitude to it.longitude } }
    val p = providers.firstOrNull() ?: return null
    return withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { cont ->
            val sig = androidx.core.os.CancellationSignal()
            cont.invokeOnCancellation { sig.cancel() }
            LocationManagerCompat.getCurrentLocation(lm, p, sig, ContextCompat.getMainExecutor(ctx)) { loc -> if (cont.isActive) cont.resume(loc?.let { it.latitude to it.longitude }) }
        }
    }
}

/**
 * Full-screen map (OpenStreetMap). Starts on the user's current location when allowed, otherwise the previous point or
 * the city centre. Tap to place the pin or drag it; the address under the pin resolves live and "Confirm" saves it.
 */
@Composable
fun MapPicker(initial: Place?, cityId: String?, onDismiss: () -> Unit, onConfirm: (Place) -> Unit) {
    if (selo.Nachipa01.ui.maps.Maps.enabled) GoogleMapPicker(initial, cityId, onDismiss, onConfirm) else OsmMapPicker(initial, cityId, onDismiss, onConfirm)
}

/**
 * Google Maps pin picker (Yango-style): the pin stays in the middle and the map moves under it. The pin lifts while
 * the map moves; when it settles the address under it resolves. Reverse lookups are debounced, skipped for tiny moves
 * and capped per session (then the neighbourhood name is shown), so dragging around costs almost nothing.
 */
@Composable
private fun GoogleMapPicker(initial: Place?, cityId: String?, onDismiss: () -> Unit, onConfirm: (Place) -> Unit) {
    val app = LocalAppState.current
    val ctx = LocalContext.current
    val cfg by app.config.collectAsState()
    val city = Cities.byId(cityId ?: initial?.cityId) ?: Cities.byId("lusaka")!!
    var pin by remember { mutableStateOf(initial?.let { it.lat to it.lng } ?: (city.lat to city.lng)) }
    var cameraKey by remember { mutableStateOf("start") }
    var cameraTarget by remember { mutableStateOf(pin) }
    var moving by remember { mutableStateOf(false) }
    var resolved by remember { mutableStateOf(initial) }
    var resolving by remember { mutableStateOf(false) }
    var lookups by remember { mutableIntStateOf(0) }
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> permission = r.values.any { it }; denied = !permission }
    fun goTo(p: Pair<Double, Double>) { cameraTarget = p; cameraKey = "go${System.nanoTime()}" }
    LaunchedEffect(Unit) { if (!permission) runCatching { ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) } }
    LaunchedEffect(permission) { if (permission && initial == null) currentLocation(ctx)?.let(::goTo) }
    LaunchedEffect(pin, moving) {
        if (moving) return@LaunchedEffect
        val last = resolved
        if (last != null && selo.Nachipa01.domain.route.Geo.m(selo.Nachipa01.domain.route.LatLng(last.lat, last.lng), selo.Nachipa01.domain.route.LatLng(pin.first, pin.second)) < 12) return@LaunchedEffect
        delay(650)
        val c = Cities.cityOf(pin.first, pin.second)
        val local = Place("pin", Cities.nearestNeighbourhood(pin.first, pin.second)?.let { "Near $it" } ?: "Pinned location",
            listOfNotNull(Cities.nearestNeighbourhood(pin.first, pin.second), c?.name).joinToString(", ").ifBlank { "%.5f, %.5f".format(pin.first, pin.second) },
            pin.first, pin.second, c?.id, c?.name, Cities.nearestNeighbourhood(pin.first, pin.second), c != null)
        if (c == null || lookups >= cfg.mapsReversePerPickerSession) { resolved = local; return@LaunchedEffect }
        resolving = true
        resolved = runCatching { app.backend.reverseGeocode(pin.first, pin.second) }.getOrElse { local }.copy(lat = pin.first, lng = pin.second)
        lookups++; resolving = false
    }
    val lift by androidx.compose.animation.core.animateDpAsState(if (moving) 14.dp else 0.dp, label = "lift")
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFFEFF1EC))) {
            val callbacks = remember { selo.Nachipa01.ui.maps.MapCallbacks(
                onCameraMoveStarted = { moving = true },
                onCameraIdle = { t -> moving = false; pin = t.latitude to t.longitude }) }
            selo.Nachipa01.ui.maps.SeloMap(selo.Nachipa01.ui.maps.MapScene(camera = selo.Nachipa01.ui.maps.MapCamera.Center(
                com.google.android.gms.maps.model.LatLng(cameraTarget.first, cameraTarget.second), 17f, cameraKey)), Modifier.fillMaxSize(), callbacks) { StaticPinMap(Modifier.fillMaxSize()) }
            // fixed centre pin
            Box(Modifier.align(Alignment.Center).offset(y = (-22).dp - lift), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.LocationOn, null, tint = Selo.Red, modifier = Modifier.size(48.dp))
            }
            Box(Modifier.align(Alignment.Center).size(if (moving) 6.dp else 4.dp).background(Selo.Ink.copy(.5f), CircleShape))
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss, Modifier.background(Color.White, CircleShape)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Spacer(Modifier.width(10.dp))
                Text("Move the map to place the pin", fontWeight = FontWeight.Bold, modifier = Modifier.background(Color.White, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp))
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                IconButton({ if (permission) scope.launch { currentLocation(ctx)?.let(::goTo) } else runCatching { ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) } },
                    Modifier.align(Alignment.End).padding(12.dp).size(48.dp).background(Color.White, CircleShape)) { Icon(Icons.Outlined.MyLocation, "My location", tint = Selo.Red) }
                Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).navigationBarsPadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (denied) Text("Location permission is off, so the map starts at ${city.name} centre. Move the map to your spot.", color = Selo.AmberDark, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Place, null, tint = Selo.Red)
                        Column(Modifier.padding(start = 8.dp).weight(1f)) {
                            Text(if (moving) "Release to set the pin" else if (resolving) "Finding the address…" else resolved?.short ?: "", fontWeight = FontWeight.Bold)
                            if (!moving) Text(resolved?.let { listOfNotNull(it.address.takeIf { a -> a != it.label }, it.cityName).joinToString(" · ") } ?: "", color = Selo.Mute, fontSize = 12.sp)
                        }
                    }
                    val outside = !moving && resolved?.inServiceArea == false
                    if (outside) Text("That point is outside the cities we serve.", color = Selo.Red, fontSize = 12.sp)
                    PrimaryButton("Confirm location", whyDisabled = if (moving) "Let go of the map first." else if (resolving) "Still finding the address…" else if (resolved?.inServiceArea == false) "That point is outside the cities we serve. Move the pin." else "Move the map to place the pin.", enabled = !moving && !resolving && resolved != null && !outside) {
                        resolved?.let { onConfirm(it.copy(lat = pin.first, lng = pin.second)) } }
                }
            }
        }
    }
}

/** Fallback picker for builds without a Maps SDK key (OpenStreetMap tiles). */
@Composable
private fun OsmMapPicker(initial: Place?, cityId: String?, onDismiss: () -> Unit, onConfirm: (Place) -> Unit) {
    val app = LocalAppState.current
    val ctx = LocalContext.current
    val city = Cities.byId(cityId ?: initial?.cityId) ?: Cities.byId("lusaka")!!
    var pin by remember { mutableStateOf(initial?.let { it.lat to it.lng } ?: (city.lat to city.lng)) }
    var resolved by remember { mutableStateOf<Place?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var permission by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    val scope = rememberCoroutineScope()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> permission = r.values.any { it }; denied = !permission }

    LaunchedEffect(Unit) { if (!permission && !isRobolectric) runCatching { ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) } }
    LaunchedEffect(permission) {
        if (permission && initial == null) currentLocation(ctx)?.let { pin = it; mapView?.controller?.animateTo(GeoPoint(it.first, it.second)) }
    }
    LaunchedEffect(pin) {
        resolving = true; delay(450) // debounce while dragging
        resolved = runCatching { app.backend.reverseGeocode(pin.first, pin.second) }.getOrElse {
            val c = Cities.cityOf(pin.first, pin.second)
            Place("pin", "Pinned location", "%.5f, %.5f".format(pin.first, pin.second), pin.first, pin.second, c?.id, c?.name, Cities.nearestNeighbourhood(pin.first, pin.second), c != null)
        }
        resolving = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xFFEFF1EC))) {
            if (isRobolectric) StaticPinMap(Modifier.fillMaxSize())
            else AndroidView(factory = { c ->
                MapView(c).apply {
                    setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(16.5)
                    controller.setCenter(GeoPoint(pin.first, pin.second))
                    val marker = Marker(this).apply { position = GeoPoint(pin.first, pin.second); isDraggable = true; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); title = "Delivery point" }
                    marker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                        override fun onMarkerDrag(m: Marker) {}
                        override fun onMarkerDragStart(m: Marker) {}
                        override fun onMarkerDragEnd(m: Marker) { pin = m.position.latitude to m.position.longitude }
                    })
                    overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { marker.position = p; invalidate(); pin = p.latitude to p.longitude; return true }
                        override fun longPressHelper(p: GeoPoint) = false
                    }))
                    overlays.add(marker)
                    mapView = this
                }
            }, update = { mv -> (mv.overlays.lastOrNull() as? Marker)?.let { if (it.position.latitude != pin.first || it.position.longitude != pin.second) { it.position = GeoPoint(pin.first, pin.second); mv.invalidate() } } },
                modifier = Modifier.fillMaxSize())
            // top bar
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss, Modifier.background(Color.White, CircleShape)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Spacer(Modifier.width(10.dp))
                Text("Tap the map or drag the pin", fontWeight = FontWeight.Bold, modifier = Modifier.background(Color.White, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp))
                Spacer(Modifier.weight(1f))
                IconButton({ if (permission) scope.launch { currentLocation(ctx)?.let { pin = it; mapView?.controller?.animateTo(GeoPoint(it.first, it.second)) } }
                    else runCatching { ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) } },
                    Modifier.background(Color.White, CircleShape)) { Icon(Icons.Outlined.MyLocation, "My location", tint = Selo.Red) }
            }
            // bottom sheet
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (denied) Text("Location permission is off, so the map starts at ${city.name} centre. You can still tap to place your pin.", color = Selo.AmberDark, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Place, null, tint = Selo.Red)
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(if (resolving) "Finding the address…" else resolved?.short ?: "", fontWeight = FontWeight.Bold)
                        Text(resolved?.let { listOfNotNull(it.address.takeIf { a -> a != it.label }, it.cityName).joinToString(" · ") } ?: "", color = Selo.Mute, fontSize = 12.sp)
                    }
                }
                val outside = resolved?.inServiceArea == false
                if (outside) Text("That point is outside the cities we serve.", color = Selo.Red, fontSize = 12.sp)
                PrimaryButton("Confirm location", whyDisabled = if (resolving) "Still finding the address…" else if (resolved?.inServiceArea == false) "That point is outside the cities we serve." else "Tap the map to place the pin.", enabled = !resolving && resolved != null && !outside) { resolved?.let(onConfirm) }
            }
        }
    }
}

/** Test/preview stand-in for the tile map. */
@Composable
internal fun StaticPinMap(modifier: Modifier) = Canvas(modifier) {
    val step = 46.dp.toPx()
    var x = 0f; while (x < size.width) { drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 8f); x += step }
    var y = 0f; while (y < size.height) { drawLine(Color.White, Offset(0f, y), Offset(size.width, y), 8f); y += step }
    drawLine(Color(0xFFFFE08A), Offset(0f, size.height * .55f), Offset(size.width, size.height * .4f), 20f)
    val c = Offset(size.width / 2, size.height / 2)
    drawCircle(Selo.Red, 14.dp.toPx(), Offset(c.x, c.y - 22.dp.toPx()))
    drawPath(Path().apply { moveTo(c.x - 11.dp.toPx(), c.y - 16.dp.toPx()); lineTo(c.x + 11.dp.toPx(), c.y - 16.dp.toPx()); lineTo(c.x, c.y); close() }, Selo.Red)
    drawCircle(Color.White, 5.dp.toPx(), Offset(c.x, c.y - 22.dp.toPx()))
}
