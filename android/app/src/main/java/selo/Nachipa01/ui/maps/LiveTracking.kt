package selo.Nachipa01.ui.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import selo.Nachipa01.domain.route.LatLng
import selo.Nachipa01.domain.route.LiveEta
import selo.Nachipa01.domain.route.NavigationEngine
import selo.Nachipa01.domain.route.StopType
import selo.Nachipa01.ui.screens.rider.MapStop
import selo.Nachipa01.ui.screens.rider.RouteMap
import selo.Nachipa01.ui.theme.Selo

/**
 * Buyer / receiver live map: pickup, drop-off, the rider gliding between 30-second pings, the road path into the
 * drop-off and a live ETA. Everything comes from the order document (written by the rider's route) — the phone makes
 * no Google web-service calls; the map itself is Maps SDK for Android.
 */
@Composable
fun LiveTrackingMap(pickup: LatLng?, drop: LatLng?, rider: LatLng?, polyline: String?, etaMinutes: Int?, height: Dp = 190.dp) {
    val path = remember(polyline) { polyline?.let { NavigationEngine.decodePolyline(it) } ?: emptyList() }
    val stops = listOfNotNull(pickup?.let { MapStop(it, StopType.PICKUP, 0, done = rider != null && path.isNotEmpty()) }, drop?.let { MapStop(it, StopType.DROPOFF, 0) })
    val eta = LiveEta.minutes(path, rider, etaMinutes)
    Box(Modifier.fillMaxWidth().height(height)) {
        RouteMap(path, stops, rider, Modifier.fillMaxSize(), etaMin = if (rider != null) eta else null)
        if (rider != null && eta != null) Text("Arriving in ~$eta min", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(Selo.Ink.copy(.85f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}
