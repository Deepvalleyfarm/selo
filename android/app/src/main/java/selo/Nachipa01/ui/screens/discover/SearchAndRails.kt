package selo.Nachipa01.ui.screens.discover

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import selo.Nachipa01.data.events.BehaviourEvent
import selo.Nachipa01.data.model.Listing
import selo.Nachipa01.data.model.Rail
import selo.Nachipa01.data.model.SearchFilters
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.domain.Taxonomy
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo

/** Grid tile used by search results, rails and the Liked/Saved tabs. */
@Composable
fun ListingTile(l: Listing, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.8f).background(Brush.linearGradient(listOf(Color(l.colorA), Color(l.colorB))))) {
            val url = l.thumbUrl ?: l.photoUrls.firstOrNull { it.startsWith("http") }
            if (url != null) AsyncImage(url, l.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (l.isVideo) Icon(Icons.Outlined.PlayCircle, "Video", tint = Color.White, modifier = Modifier.padding(6.dp).size(20.dp).align(Alignment.TopEnd))
            if (l.hasOffer) Text(if (l.discountPct > 0) "−${l.discountPct.toInt()}%" else "OFFER", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(6.dp).background(Selo.Coral, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Column(Modifier.padding(8.dp)) {
            Text(l.title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fees.formatK(l.offerPriceNgwee).removeSuffix(".00"), color = Selo.Red, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                if (l.hasOffer) Text(" ${Fees.formatK(l.priceNgwee).removeSuffix(".00")}", color = Selo.Mute, fontSize = 10.sp, textDecoration = TextDecoration.LineThrough)
            }
            Text("${l.district.ifBlank { l.province }} · ♥ ${compact(l.likes)}", color = Selo.Mute, fontSize = 10.5.sp, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            selo.Nachipa01.ui.components.ProductActions(l, Modifier.fillMaxWidth(), compact = true)
        }
    }
}

@Composable
fun RailRow(rail: Rail, onOpen: (Listing) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(rail.title, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp), maxLines = 2)
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rail.items, key = { rail.id + it.id }) { l -> ListingTile(l, Modifier.width(136.dp)) { onOpen(l) } }
        }
    }
}

/** Home-tab recommendation rails (PRD v6.1 §5.5). */
@Composable
fun HomeRails(onOpen: (Listing) -> Unit) {
    val app = LocalAppState.current
    var rails by remember { mutableStateOf<List<Rail>>(emptyList()) }
    LaunchedEffect(Unit) { rails = runCatching { app.backend.homeRails() }.getOrDefault(emptyList()) }
    rails.filter { it.items.isNotEmpty() }.forEach { r ->
        RailRow(r) { l -> app.track(BehaviourEvent("listing_view", l.id, l.sellerUid, l.category, l.mediaType, source = "rail")); onOpen(l) }
    }
}

private const val PREFS = "selo_search"
private fun Context.loadRecent(): List<String> = getSharedPreferences(PREFS, 0).getString("recent", "")!!.split('\u0001').filter { it.isNotBlank() }
private fun Context.saveRecent(list: List<String>) = getSharedPreferences(PREFS, 0).edit().putString("recent", list.joinToString("\u0001")).apply()

/**
 * Search & browse (PRD v6.1 §5.2): full-text across title, description, tags and seller; results as a grid;
 * filters; recent searches stored on the device and synced to the buyer profile through search events.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpen: (Listing) -> Unit) {
    val app = LocalAppState.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(SearchFilters()) }
    var results by remember { mutableStateOf<List<Listing>?>(null) }
    var also by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var lastQuery by remember { mutableStateOf<String?>(null) }
    var clicked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (app.recentSearches.isEmpty()) app.recentSearches.addAll(ctx.loadRecent()) }

    fun logPrevious() { lastQuery?.let { if (!clicked) app.track(BehaviourEvent("search", query = it, resultsCount = results?.size ?: 0)) } }
    fun run(query: String = q, f: SearchFilters = filters) {
        if (query.isBlank() && f.active == 0) { results = null; return }
        logPrevious(); busy = true; clicked = false
        scope.launch {
            runCatching { app.backend.search(query, f) }.onSuccess { r -> results = r.results; also = r.alsoSearched; lastQuery = query
                if (query.isNotBlank()) { app.recentSearches.remove(query); app.recentSearches.add(0, query); while (app.recentSearches.size > 10) app.recentSearches.removeAt(10); ctx.saveRecent(app.recentSearches) } }
            busy = false
        }
    }
    fun open(l: Listing) {
        if (!clicked) { app.track(BehaviourEvent("search", query = lastQuery ?: q, resultsCount = results?.size ?: 0, firstClickListingId = l.id)); clicked = true }
        app.track(BehaviourEvent("listing_view", l.id, l.sellerUid, l.category, l.mediaType, source = "search")); onOpen(l)
    }
    DisposableEffect(Unit) { onDispose { logPrevious() } }

    Column(Modifier.fillMaxSize().background(Selo.Surface)) {
        Column(Modifier.fillMaxWidth().background(Selo.Red).statusBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search products, sellers, tags") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(50),
                    trailingIcon = { if (q.isNotEmpty()) IconButton({ q = ""; results = null }) { Icon(Icons.Outlined.Close, "Clear") } },
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { run() }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White, focusedBorderColor = Color.White))
                BadgedBox({ if (filters.active > 0) Badge { Text("${filters.active}") } }, Modifier.padding(start = 8.dp)) {
                    IconButton({ showFilters = true }) { Icon(Icons.Outlined.Tune, "Filters", tint = Color.White) }
                }
            }
            if (also.isNotEmpty() && results != null) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("People also searched for ", color = Color.White.copy(.9f), fontSize = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    also.take(4).forEach { a -> Text(a, color = Selo.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Color.White, RoundedCornerShape(50)).clickable { q = a; run(a) }.padding(horizontal = 9.dp, vertical = 3.dp)) }
                }
            }
        }
        val res = results
        if (res == null) Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (app.recentSearches.isNotEmpty()) {
                Row { SectionLabel("Recent searches"); Spacer(Modifier.weight(1f)); Text("Clear all", color = Selo.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { app.recentSearches.clear(); ctx.saveRecent(emptyList()) }) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    app.recentSearches.forEach { r -> Text(r, fontSize = 13.sp, modifier = Modifier.background(Color.White, RoundedCornerShape(8.dp))
                        .clickable { q = r; run(r) }.padding(horizontal = 10.dp, vertical = 6.dp)) }
                }
            }
            SectionLabel("Browse categories")
            Taxonomy.CATEGORIES.filter { it != "Other" }.chunked(4).forEach { row ->
                Row { row.forEach { c -> CategoryTile(categoryIcon(c), c, modifier = Modifier.weight(1f)) { filters = filters.copy(category = c); run("", filters.copy(category = c)) } }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) } }
            }
        } else if (busy) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Selo.Red) }
        else LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(span = { GridItemSpan(2) }) { Text("${res.size} result${if (res.size == 1) "" else "s"}${if (lastQuery.isNullOrBlank()) "" else " for \"$lastQuery\""}${filters.category?.let { " in $it" } ?: ""}",
                color = Selo.Mute, fontSize = 12.sp) }
            if (res.isEmpty()) item(span = { GridItemSpan(2) }) { NoteBox("No results. Try a different word or remove a filter.") }
            items(res, key = { it.id }) { l -> ListingTile(l) { open(l) } }
        }
    }

    if (showFilters) ModalBottomSheet({ showFilters = false }, containerColor = Color.White) {
        var f by remember { mutableStateOf(filters) }
        var range by remember { mutableStateOf((f.minNgwee ?: 0L).toFloat() / 100f..(f.maxNgwee ?: 500_000L).toFloat() / 100f) }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            H("Filters", 17)
            Text("Price: K ${range.start.toInt()} – K ${range.endInclusive.toInt()}${if (range.endInclusive >= 5000f) "+" else ""}", fontWeight = FontWeight.SemiBold)
            RangeSlider(range, { range = it }, valueRange = 0f..5000f, colors = SliderDefaults.colors(thumbColor = Selo.Red, activeTrackColor = Selo.Red))
            Text("Province", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Taxonomy.PROVINCES.forEach { p -> SeloChip(p, f.province == p) { f = f.copy(province = if (f.province == p) null else p) } }
            }
            SeloField("District (optional)", f.district ?: "", { f = f.copy(district = it.ifBlank { null }) })
            Text("Condition", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Taxonomy.CONDITIONS.forEach { c -> SeloChip(c, f.condition == c) { f = f.copy(condition = if (f.condition == c) null else c) } }
            }
            Text("Listing type", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("video" to "Video", "photo" to "Photo").forEach { (v, n) -> SeloChip(n, f.mediaType == v) { f = f.copy(mediaType = if (f.mediaType == v) null else v) } } }
            Text("Availability", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Taxonomy.AVAILABILITY.forEach { a -> SeloChip(a, f.availability == a) { f = f.copy(availability = if (f.availability == a) null else a) } } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlineButton("Clear", Modifier.weight(1f)) { filters = SearchFilters(); showFilters = false; run(q, SearchFilters()) }
                PrimaryButton("Show results", Modifier.weight(1.4f)) {
                    filters = f.copy(minNgwee = (range.start * 100).toLong().takeIf { it > 0 }, maxNgwee = (range.endInclusive * 100).toLong().takeIf { range.endInclusive < 5000f })
                    showFilters = false; run(q, filters)
                }
            }
        }
    }
}

fun categoryIcon(c: String) = when (c) {
    "Fresh produce" -> Icons.Outlined.Eco; "Groceries" -> Icons.Outlined.ShoppingBasket; "Fast food" -> Icons.Outlined.LunchDining
    "Electronics" -> Icons.Outlined.Tv; "Phones & accessories" -> Icons.Outlined.PhoneAndroid; "Fashion" -> Icons.Outlined.Checkroom
    "Beauty" -> Icons.Outlined.Spa; "Home" -> Icons.Outlined.Home; "Furniture" -> Icons.Outlined.Chair; "Hardware" -> Icons.Outlined.Handyman
    "Building materials" -> Icons.Outlined.Foundation; "Agriculture" -> Icons.Outlined.Agriculture; "Baby" -> Icons.Outlined.ChildCare
    "Vehicles & parts" -> Icons.Outlined.DirectionsCar; "Services" -> Icons.Outlined.MiscellaneousServices; else -> Icons.Outlined.GridView
}

/** Liked and Saved tabs plus named wishlists on the buyer profile (PRD v6.1 §5.3). */
@Composable
fun LikedSavedTabs(onOpen: (Listing) -> Unit) {
    val app = LocalAppState.current
    val liked by app.backend.likedListings().collectAsState(emptyList())
    val lists by app.backend.wishlists().collectAsState(emptyList())
    var tab by remember { mutableIntStateOf(0) }
    var all by remember { mutableStateOf<Map<String, Listing>>(emptyMap()) }
    LaunchedEffect(lists) { all = lists.flatMap { it.itemIds }.distinct().mapNotNull { id -> runCatching { app.backend.listing(id) }.getOrNull() }.associateBy { it.id } }
    PillTabs(listOf("Liked", "Saved"), tab) { tab = it }
    if (tab == 0) {
        if (liked.isEmpty()) NoteBox("Double-tap a listing in the feed to like it.")
        liked.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { ListingTile(it, Modifier.weight(1f)) { onOpen(it) } }; repeat(3 - row.size) { Spacer(Modifier.weight(1f)) } } }
    } else {
        if (lists.all { it.itemIds.isEmpty() }) NoteBox("Long-press a listing in the feed to save it to a wishlist.")
        lists.filter { it.itemIds.isNotEmpty() }.forEach { w ->
            Text("${w.name} · ${w.itemIds.size}", fontWeight = FontWeight.ExtraBold)
            w.itemIds.mapNotNull { all[it] }.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { ListingTile(it, Modifier.weight(1f)) { onOpen(it) } }; repeat(3 - row.size) { Spacer(Modifier.weight(1f)) } } }
        }
    }
}
