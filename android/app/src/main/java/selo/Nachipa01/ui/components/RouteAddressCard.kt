package selo.Nachipa01.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import selo.Nachipa01.data.maps.PlacesCache
import selo.Nachipa01.data.maps.RecentPlaces
import selo.Nachipa01.data.model.AddressSuggestion
import selo.Nachipa01.data.model.Place
import selo.Nachipa01.domain.route.Geo
import selo.Nachipa01.domain.route.LatLng
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.theme.Selo

private val Ink = Color(0xFF1F1F1F); private val Grey = Color(0xFF8C8C8C); private val Divider = Color(0xFFE6E6E6)

/** Grey category glyph for a suggestion, guessed from its name (gym, mall, police, school, estate, market…). */
fun placeIcon(label: String, secondary: String = "", source: String = ""): ImageVector {
    val t = "$label $secondary".lowercase()
    fun has(vararg w: String) = w.any { it in t }
    return when {
        source == "store" -> Icons.Outlined.History
        has("fitness", "gym") -> Icons.Outlined.FitnessCenter
        has("mall", "shoprite", "pick n pay", "spar", "shop", "plaza", "arcades") -> Icons.Outlined.LocalMall
        has("market") -> Icons.Outlined.Storefront
        has("police") -> Icons.Outlined.LocalPolice
        has("hospital", "clinic", "health", "pharmacy") -> Icons.Outlined.LocalHospital
        has("school", "university", "college", "academy") -> Icons.Outlined.School
        has("church", "mosque", "cathedral") -> Icons.Outlined.Church
        has("bank", "atm") -> Icons.Outlined.AccountBalance
        has("hotel", "lodge", "guest house") -> Icons.Outlined.Hotel
        has("restaurant", "café", "cafe", "grill", "kfc", "pizza", "chicken") -> Icons.Outlined.Restaurant
        has("filling", "puma", "total", "engen", "fuel") -> Icons.Outlined.LocalGasStation
        has("bus", "station", "terminus", "stop") -> Icons.Outlined.DirectionsBus
        has("estate", "residential", "flats", "apartments", "house") -> Icons.Outlined.Home
        has("road", "street", "avenue", "drive", "rd") -> Icons.Outlined.AddRoad
        source == "gazetteer" -> Icons.Outlined.Map
        else -> Icons.Outlined.Place
    }
}

private fun distText(m: Int) = if (m < 1000) "$m m" else "%.1f km".format(m / 1000.0)

/**
 * Pickup → delivery card in the style of ride-hailing apps: one rounded card with the From row (red ring) and the To
 * row (search), each tappable to edit; the active row becomes a search box with a clear button and "Map"; suggestions
 * list underneath with category icons, the area and the distance from you. When nothing is typed it offers the
 * phone's current location, saved and recently used places.
 *
 * [fromEditable] = false shows a fixed pickup (e.g. the seller's shop at checkout).
 */
@Composable
fun RouteAddressCard(
    from: Place?, to: Place?, onFrom: (Place) -> Unit, onTo: (Place) -> Unit, modifier: Modifier = Modifier,
    fromEditable: Boolean = true, fixedFromTitle: String? = null, fixedFromSub: String? = null,
    fromHint: String = "Pickup point", toHint: String = "Where to deliver?", toCityId: String? = null, problem: String? = null,
) {
    val app = LocalAppState.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    // which row is being edited: "from", "to" or null. Start on the first empty one.
    var active by remember { mutableStateOf(if (fromEditable && from == null) "from" else if (to == null) "to" else null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var here by remember { mutableStateOf(LastFix.fresh()) }
    var mapFor by remember { mutableStateOf<String?>(null) }
    var mapStart by remember { mutableStateOf<Place?>(null) }
    var mapNote by remember { mutableStateOf<String?>(null) }
    val saved by app.backend.savedAddresses().collectAsState(emptyList())
    val cfg by app.config.collectAsState()
    val recent = remember(active) { RecentPlaces.all() }
    val cityId = if (active == "to") toCityId else null
    fun distM(lat: Double, lng: Double): Int? = here?.let { h -> Geo.m(LatLng(h.first, h.second), LatLng(lat, lng)).toInt() }

    fun confirm(which: String, p: Place) {
        if (which == "from") onFrom(p) else onTo(p)
        RecentPlaces.remember(p); PlacesCache.endSession()
        query = ""; results = emptyList(); error = null; focus.clearFocus()
        // next empty row, otherwise close
        active = if (which == "from" && to == null) "to" else null
        if (p.inServiceArea && p.addressId == null) scope.launch { runCatching { app.backend.savePlace(p) }.onSuccess { id ->
            val withId = p.copy(addressId = id); if (which == "from") onFrom(withId) else onTo(withId); RecentPlaces.remember(withId) } }
    }
    // The pickup can arrive from GPS after the card opens: move straight on to "To".
    LaunchedEffect(from) { if (active == "from" && query.isEmpty() && from != null) active = if (to == null) "to" else null }
    // A default delivery address (e.g. the saved Home) can also arrive after opening: show it as chosen.
    LaunchedEffect(to) { if (active == "to" && query.isEmpty() && to != null) active = null }
    LaunchedEffect(active) {
        query = ""; results = emptyList()
        if (active != null && here == null && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            here = withTimeoutOrNull(8_000) { currentLocation(ctx, 6_000) }
    }
    LaunchedEffect(query, active) {
        error = null
        if (active == null || !PlacesCache.shouldSearch(query, cfg.mapsAutocompleteMinChars)) { results = emptyList(); searching = false; return@LaunchedEffect }
        delay(cfg.mapsAutocompleteDebounceMs.toLong())
        searching = true
        runCatching { app.backend.addressSuggestions(query, cityId, here?.first, here?.second) }
            .onSuccess { results = it }.onFailure { error = "Search isn't responding. Tap Map to pick the spot."; results = emptyList() }
        searching = false
    }
    val locator = rememberLocator()
    fun useMyLocation(which: String) {
        resolving = true
        locator.locate { fix, problem ->
            if (fix == null) { resolving = false; app.fail(problem); return@locate }
            here = fix.at
            scope.launch {
                runCatching { app.backend.reverseGeocode(fix.lat, fix.lng).copy(lat = fix.lat, lng = fix.lng) }.onSuccess { p ->
                    val acc = fix.accuracyM?.toInt()
                    if (acc != null && acc > 60) { mapStart = p.copy(approximate = true); mapNote = "Your phone places you within about $acc m. Move the map so the pin sits on your door."; mapFor = which }
                    else { confirm(which, p); app.ok("Location found${acc?.let { " · ±$it m" } ?: ""}") }
                }.onFailure { app.fail(it.message ?: "Couldn't find your address.") }
                resolving = false
            }
        }
    }
    fun pick(s: AddressSuggestion, which: String) { scope.launch { resolving = true
        runCatching { app.backend.placeDetails(s.id) }.onSuccess { confirm(which, it) }.onFailure { error = it.message ?: "Couldn't load that address." }; resolving = false } }

    Column(modifier) {
        // ---------------------------------------------------------------- the card
        Column(Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black.copy(.08f), spotColor = Color.Black.copy(.12f))
            .background(Color.White, RoundedCornerShape(18.dp))) {
            RouteRow(
                lead = { Ring() }, place = from, hint = fromHint, editing = active == "from", query = query, onQuery = { query = it.take(120) },
                fixedTitle = fixedFromTitle, fixedSub = fixedFromSub, enabled = fromEditable,
                onTap = { if (fromEditable) active = "from" }, onClear = { query = "" }, onMap = { mapFor = "from" }, searching = searching && active == "from",
            )
            Box(Modifier.padding(start = 64.dp, end = 20.dp).fillMaxWidth().height(1.dp).background(Divider))
            RouteRow(
                lead = { Icon(if (active == "to") Icons.Outlined.Search else Icons.Outlined.Flag, null, tint = Ink, modifier = Modifier.size(24.dp)) },
                place = to, hint = toHint, editing = active == "to", query = query, onQuery = { query = it.take(120) },
                onTap = { active = "to" }, onClear = { query = "" }, onMap = { mapFor = "to" }, searching = searching && active == "to",
            )
        }
        if (resolving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(2.dp), color = Selo.Red)
        // distance between the two points once both are set
        if (active == null && from != null && to != null) {
            val km = Geo.m(LatLng(from.lat, from.lng), LatLng(to.lat, to.lng)) / 1000.0
            Row(Modifier.padding(start = 18.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Route, null, tint = Grey, modifier = Modifier.size(16.dp))
                Text("  ≈ %.1f km straight line · the fee uses the road distance".format(km), color = Grey, fontSize = 12.sp)
            }
            to.landmark?.let { Text("Drop-off landmark: $it", color = Selo.Teal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 18.dp, top = 2.dp)) }
        }
        problem?.let { Row(Modifier.padding(start = 18.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = Selo.Red, modifier = Modifier.size(16.dp)); Text("  $it", color = Selo.Red, fontSize = 12.sp) } }

        // ---------------------------------------------------------------- suggestions
        val which = active ?: return@Column
        Spacer(Modifier.height(6.dp))
        error?.let { Text(it, color = Selo.Red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        if (query.isNotBlank() && query.trim().length < cfg.mapsAutocompleteMinChars)
            Text("Keep typing…", color = Grey, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        val savedPlaces = saved.map { it.nickname to it.place }
        val local = if (query.isBlank()) emptyList() else RecentPlaces.matches(savedPlaces.map { it.second } + recent, query, 4).distinctBy { "%.4f,%.4f".format(it.lat, it.lng) }
        if (query.isBlank()) {
            SuggestionRow(Icons.Outlined.MyLocation, "Use my current location", if (resolving) "Finding you…" else "Exact spot from your phone's GPS", null, accent = true) { useMyLocation(which) }
            savedPlaces.forEach { (nick, p) -> SuggestionRow(when (nick.lowercase()) { "home" -> Icons.Outlined.Home; "work" -> Icons.Outlined.Work; "shop" -> Icons.Outlined.Storefront; else -> Icons.Outlined.Bookmark },
                nick, p.short, distM(p.lat, p.lng)) { confirm(which, p) } }
            recent.filterNot { r -> savedPlaces.any { it.second.lat == r.lat && it.second.lng == r.lng } }.take(5)
                .forEach { p -> SuggestionRow(Icons.Outlined.History, p.short, listOfNotNull(p.neighbourhood, p.cityName).joinToString(", ").ifBlank { p.address }, distM(p.lat, p.lng)) { confirm(which, p) } }
        } else {
            local.forEach { p -> SuggestionRow(Icons.Outlined.History, p.short, listOfNotNull(p.neighbourhood, p.cityName).joinToString(", ").ifBlank { p.address }, distM(p.lat, p.lng)) { confirm(which, p) } }
            results.filterNot { r -> local.any { it.short.equals(r.label, true) } }.forEach { s ->
                SuggestionRow(placeIcon(s.label, s.secondary, s.source), s.label, s.secondary, s.distanceM) { pick(s, which) } }
            if (!searching && results.isEmpty() && local.isEmpty() && query.trim().length >= cfg.mapsAutocompleteMinChars && error == null)
                SuggestionRow(Icons.Outlined.PinDrop, "Can't find it? Pick the spot on the map", "Move the map until the pin is on the door", null, accent = true) { mapFor = which }
        }
    }
    mapFor?.let { w -> MapPicker(initial = mapStart ?: (if (w == "from") from else to), cityId = if (w == "to") toCityId else null, note = mapNote,
        onDismiss = { mapFor = null; mapStart = null; mapNote = null }, onConfirm = { p -> mapFor = null; mapStart = null; mapNote = null; confirm(w, p.copy(approximate = false)) }) }
}

/** Red ring marking the pickup, like the start of a route. */
@Composable private fun Ring() = Canvas(Modifier.size(22.dp)) {
    drawCircle(Selo.Red, radius = size.minDimension / 2 - 3.dp.toPx(), style = Stroke(width = 5.dp.toPx()))
}

@Composable
private fun RouteRow(
    lead: @Composable () -> Unit, place: Place?, hint: String, editing: Boolean, query: String, onQuery: (String) -> Unit,
    onTap: () -> Unit, onClear: () -> Unit, onMap: () -> Unit, searching: Boolean,
    fixedTitle: String? = null, fixedSub: String? = null, enabled: Boolean = true,
) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(editing) { if (editing) runCatching { fr.requestFocus() } }
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(enabled = enabled && !editing, onClick = onTap).padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) { lead() }
        Spacer(Modifier.width(12.dp))
        if (editing) {
            BasicTextField(query, onQuery, Modifier.weight(1f).focusRequester(fr), singleLine = true, cursorBrush = SolidColor(Selo.Red),
                textStyle = TextStyle(fontSize = 19.sp, color = Ink),
                decorationBox = { inner -> Box { if (query.isEmpty()) Text(place?.short ?: hint, fontSize = 19.sp, color = Grey, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() } })
            if (searching) CircularProgressIndicator(Modifier.size(18.dp), color = Selo.Red, strokeWidth = 2.dp)
            else if (query.isNotEmpty()) IconButton(onClear, Modifier.size(40.dp)) { Icon(Icons.Filled.Cancel, "Clear", tint = Color(0xFF9E9E9E), modifier = Modifier.size(22.dp)) }
            Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(34.dp).background(Divider))
            Text("Map", fontSize = 17.sp, color = Ink, modifier = Modifier.clickable(onClick = onMap).padding(horizontal = 8.dp, vertical = 10.dp))
        } else {
            Column(Modifier.weight(1f)) {
                val title = fixedTitle ?: place?.short
                Text(title ?: hint, fontSize = 19.sp, color = if (title != null) Ink else Grey, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = fixedSub ?: place?.let { p -> listOfNotNull(p.address.takeIf { it != p.label && it.isNotBlank() }, p.landmark?.let { "near $it" }).joinToString(" · ").ifBlank { p.cityName } }
                sub?.let { Text(it, fontSize = 13.sp, color = Grey, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (enabled) Icon(Icons.Outlined.Edit, "Change", tint = Color(0xFFBDBDBD), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SuggestionRow(icon: ImageVector, title: String, sub: String, distanceM: Int?, accent: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) { Icon(icon, null, tint = if (accent) Selo.Red else Color(0xFF6E6E6E), modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Column(Modifier.padding(vertical = 13.dp)) {
                Text(title, fontSize = 18.sp, color = if (accent) Selo.Red else Ink, fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub.isNotBlank()) Text(sub, fontSize = 14.sp, color = Grey, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
        }
        distanceM?.let { Text(distText(it), fontSize = 13.sp, color = Grey, modifier = Modifier.padding(start = 8.dp)) }
    }
}
