package selo.Nachipa01.ui.screens.buyer

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storefront
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
import com.google.android.gms.maps.model.LatLng
import selo.Nachipa01.data.model.StoreLocation
import selo.Nachipa01.domain.Cities
import selo.Nachipa01.domain.route.Geo
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.maps.*
import selo.Nachipa01.ui.theme.Selo
import selo.Nachipa01.domain.route.LatLng as DLatLng

/**
 * "Shops near you": store markers from the public stores collection (geohash cells around the buyer), clustered when
 * zoomed out. One Firestore query per screen visit; no Google web-service calls.
 */
@Composable
fun ShopsMapScreen(onBack: () -> Unit) {
    val app = LocalAppState.current
    val ctx = LocalContext.current
    val lusaka = Cities.byId("lusaka")!!
    var here by remember { mutableStateOf(lusaka.lat to lusaka.lng) }
    var stores by remember { mutableStateOf<List<StoreLocation>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<StoreLocation?>(null) }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            currentLocation(ctx)?.let { here = it }
        runCatching { app.backend.nearbyStores(here.first, here.second) }.onSuccess { stores = it }.onFailure { err = it.message; stores = emptyList() }
    }
    val me = DLatLng(here.first, here.second)
    val sorted = stores.orEmpty().sortedBy { Geo.m(me, DLatLng(it.lat, it.lng)) }
    Column(Modifier.fillMaxSize().background(Selo.Surface)) {
        SeloHeader("Shops near you", stores?.let { "${it.size} shops within about 7 km" } ?: "Finding shops…", onBack = onBack)
        val scene = MapScene(pins = sorted.map { MapPin(it.sellerUid, LatLng(it.lat, it.lng), PinKind.STORE, title = it.name) } +
            MapPin("me", LatLng(here.first, here.second), PinKind.BUYER), clusterStores = true,
            camera = MapCamera.Center(LatLng(here.first, here.second), 13.5f, "${here.first},${here.second}"))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            SeloMap(scene, Modifier.fillMaxSize(), remember { MapCallbacks(onPinClick = { p -> picked = stores?.firstOrNull { it.sellerUid == p.id } }) }) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sorted, key = { it.sellerUid }) { s -> StoreRow(s, me) { picked = s } }
                }
            }
            if (stores == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Selo.Red)
            err?.let { Text(it, color = Selo.Red, modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp)) }
            if (stores?.isEmpty() == true && err == null) Text("No shops on SeloNaChipa near you yet.", modifier = Modifier.align(Alignment.Center).background(Color.White, RoundedCornerShape(8.dp)).padding(12.dp))
        }
        picked?.let { s -> Column(Modifier.fillMaxWidth().background(Color.White).navigationBarsPadding().padding(16.dp)) { StoreRow(s, me) {} } }
    }
}

@Composable
private fun StoreRow(s: StoreLocation, me: DLatLng, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Storefront, null, tint = Selo.Red)
        Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(s.name, fontWeight = FontWeight.Bold); Text(s.address, color = Selo.Mute, fontSize = 12.sp, maxLines = 1) }
        Text("%.1f km".format(Geo.m(me, DLatLng(s.lat, s.lng)) / 1000), color = Selo.Mute, fontSize = 12.sp)
    }
}
