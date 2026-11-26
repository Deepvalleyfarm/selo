package selo.Nachipa01.ui.screens.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import selo.Nachipa01.data.model.ShopProfile
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.screens.discover.ListingTile
import selo.Nachipa01.ui.theme.Selo

/** A shop's page for buyers: its own banner, logo, registered business name, location and live products. */
@Composable
fun ShopScreen(sellerUid: String, onBack: () -> Unit) {
    val app = LocalAppState.current
    var shop by remember { mutableStateOf<ShopProfile?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sellerUid) { runCatching { app.backend.shopProfile(sellerUid) }.onSuccess { shop = it }.onFailure { err = it.message ?: "Couldn't open this shop."; app.fail(err) } }
    val s = shop
    Column(Modifier.fillMaxSize().background(Selo.Surface)) {
        if (s == null) {
            Row(Modifier.fillMaxWidth().background(Selo.Red).statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }; Text("Shop", color = Color.White, fontWeight = FontWeight.Bold) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (err != null) Text(err!!, color = Selo.Red) else CircularProgressIndicator(color = Selo.Red) }
            return
        }
        LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(span = { GridItemSpan(2) }) {
                Box(Modifier.fillMaxWidth().height(230.dp)) {
                    // the seller's own banner (or a brand gradient until they upload one)
                    Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.linearGradient(listOf(Color(0xFF7A0016), Color(0xFFE2334D))))) {
                        s.bannerUrl?.let { AsyncImage(it, "${s.name} banner", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        IconButton(onBack, Modifier.statusBarsPadding().padding(6.dp).background(Color.White.copy(.9f), CircleShape)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    }
                    Box(Modifier.padding(start = 16.dp).align(Alignment.BottomStart).size(92.dp).clip(CircleShape).background(Color.White).border(3.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center) {
                        if (s.photoUrl != null) AsyncImage(s.photoUrl, s.name, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                        else Icon(Icons.Outlined.Storefront, null, tint = Selo.Red, modifier = Modifier.size(40.dp))
                    }
                }
            }
            item(span = { GridItemSpan(2) }) {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (s.verified) Icon(Icons.Filled.Verified, "Verified business", tint = Color(0xFF1D9BF0), modifier = Modifier.padding(start = 6.dp).size(20.dp))
                    }
                    if (s.address.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Place, null, tint = Selo.Mute, modifier = Modifier.size(16.dp)); Text(" ${s.address}", color = Selo.Mute, fontSize = 13.sp) }
                    Text("${s.listings.size} product${if (s.listings.size == 1) "" else "s"} available", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    if (s.listings.isEmpty()) Text("This shop has no products listed right now.", color = Selo.Mute)
                }
            }
            items(s.listings, key = { it.id }) { l ->
                ListingTile(l, Modifier.padding(horizontal = 6.dp)) { app.info("${l.title} · ${selo.Nachipa01.domain.Fees.formatK(l.offerPriceNgwee)}") }
            }
        }
    }
}
