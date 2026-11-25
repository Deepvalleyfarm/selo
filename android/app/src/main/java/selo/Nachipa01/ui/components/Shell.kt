package selo.Nachipa01.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.Advert
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.ui.theme.Selo

/** Home band: logo, greeting, bell. Mirrors the reference wallet-app header. */
@Composable
fun HomeBand(title: String, subtitle: String, badge: (@Composable () -> Unit)? = null, bottomPad: Int = 16, onBell: (() -> Unit)? = null) {
    val app = selo.Nachipa01.ui.LocalAppState.current
    val bell = onBell ?: { app.navRequest.value = "account?r=inbox" }
    Row(Modifier.fillMaxWidth().background(Selo.Red).statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = bottomPad.dp),
        verticalAlignment = Alignment.CenterVertically) {
        LogoTile(); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            Text(subtitle, color = Color.White.copy(alpha = .88f), fontSize = 12.sp)
        }
        if (badge != null) badge() else IconButton(bell) { Icon(Icons.Outlined.Notifications, "Notifications", tint = Color.White) }
    }
}

data class QuickAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

@Composable
fun WalletCard(tag: String, caption: String, balanceNgwee: Long, actions: List<QuickAction>, modifier: Modifier = Modifier) {
    var hidden by remember { mutableStateOf(true) }
    Box(modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(16.dp)).background(Color.White, RoundedCornerShape(16.dp))) {
        Text(tag, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Selo.Red, RoundedCornerShape(topStart = 16.dp, bottomEnd = 12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
        Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(caption, color = Selo.Mute, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                Text(if (hidden) "ZMW ••••••" else Fees.formatK(balanceNgwee).replace("K ", "ZMW "), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                IconButton({ hidden = !hidden }) { Icon(if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show balance", tint = Selo.Red) }
            }
            HorizontalDivider(color = Selo.Line)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                actions.forEachIndexed { i, a ->
                    if (i > 0) VerticalDivider(color = Color(0xFFF6C9D0))
                    Column(Modifier.weight(1f).clickable(onClick = a.onClick).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(a.icon, null, tint = Selo.Red, modifier = Modifier.size(22.dp))
                        Text(a.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun PillTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        tabs.forEachIndexed { i, t ->
            Text(t.uppercase(), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, letterSpacing = .3.sp,
                color = if (i == selected) Color.White else Color(0xFF9A9AA6),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (i == selected) Selo.Red else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 18.dp, vertical = 9.dp))
        }
    }
}

@Composable
fun CategoryTile(icon: ImageVector, label: String, badge: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(Modifier.size(62.dp).shadow(1.dp, RoundedCornerShape(15.dp)).background(Color.White, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Selo.Red, modifier = Modifier.size(26.dp))
            }
            if (badge != null) Text(badge, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).background(Selo.Coral, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
fun PromoBanner(title: String, sub: String, icon: ImageVector = Icons.Outlined.Shield) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF8F0019), Selo.Red, Color(0xFFE2334D)))).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).background(Color.White.copy(alpha = .16f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column { Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp); Text(sub, color = Color.White.copy(alpha = .9f), fontSize = 12.sp) }
    }
}

@Composable
fun StatCard(value: String, label: String, modifier: Modifier = Modifier, valueColor: Color = Selo.Red, bg: Color = Color.White) {
    Column(modifier.shadow(1.dp, RoundedCornerShape(14.dp)).background(bg, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = valueColor)
        Text(label, fontSize = 11.5.sp, color = Selo.Mute)
    }
}

data class NavItem(val route: String, val icon: ImageVector, val label: String)

/** Five-slot bottom bar with the role's main action raised in the centre. */
@Composable
fun SeloBottomBar(items: List<NavItem>, center: NavItem, current: String, centerBadge: Int = 0, onSelect: (String) -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .background(Color.White, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).navigationBarsPadding()
            .padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            val all = items.take(2) + center + items.drop(2)
            all.forEach { item ->
                val selected = current == item.route
                Column(Modifier.weight(1f).clickable { onSelect(item.route) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (item == center) Spacer(Modifier.height(30.dp))
                    else Icon(item.icon, item.label, tint = if (selected) Selo.Red else Color(0xFF8A8A96))
                    Text(item.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Selo.Red else Color(0xFF8A8A96))
                }
            }
        }
        Box(Modifier.align(Alignment.TopCenter).offset(y = (-26).dp).size(62.dp).shadow(8.dp, CircleShape)
            .background(Color.White, CircleShape).padding(5.dp).background(Selo.Red, CircleShape).clickable { onSelect(center.route) },
            contentAlignment = Alignment.Center) {
            Icon(center.icon, center.label, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        if (centerBadge > 0) Text("$centerBadge", color = Selo.Red, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.align(Alignment.TopCenter).offset(x = 22.dp, y = (-28).dp).background(Color.White, CircleShape)
                .border(1.5.dp, Selo.Red, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

private fun advertIcon(cat: String): ImageVector = when (cat) {
    "electronics" -> Icons.Outlined.PhoneAndroid
    "food" -> Icons.Outlined.LunchDining
    "solar" -> Icons.Outlined.WbSunny
    "fashion" -> Icons.Outlined.Checkroom
    else -> Icons.Outlined.Eco
}

/** Five paid adverts sliding every 3 s (welcome screen). Adverts are booked in the back office. */
@Composable
fun AdvertCarousel(ads: List<Advert>, onOpen: (Advert) -> Unit) {
    if (ads.isEmpty()) return
    val pager = rememberPagerState { ads.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ads.size) {
        while (true) { delay(3000); pager.animateScrollToPage((pager.currentPage + 1) % ads.size) }
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        HorizontalPager(pager, pageSpacing = 12.dp, modifier = Modifier.clip(RoundedCornerShape(18.dp))) { page ->
            val ad = ads[page]
            Box(Modifier.fillMaxWidth().height(196.dp).background(Brush.linearGradient(listOf(Color(ad.colorA), Color(ad.colorB))))
                .clickable { onOpen(ad) }.padding(16.dp)) {
                Text("SPONSORED · ${page + 1}/${ads.size}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.background(Color.Black.copy(alpha = .28f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                Box(Modifier.align(Alignment.TopEnd).padding(top = 22.dp).size(82.dp).background(Color.White.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(advertIcon(ad.category), null, tint = Color.White, modifier = Modifier.size(42.dp))
                }
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
                    Text(ad.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 21.sp, modifier = Modifier.fillMaxWidth(.7f))
                    Text(ad.seller, color = Color.White.copy(alpha = .9f), fontSize = 11.5.sp)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Fees.formatK(ad.priceNgwee).removeSuffix(".00"), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text(ad.cta, color = Selo.Ink, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                            modifier = Modifier.background(Color.White, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
            ads.indices.forEach { i ->
                val w by animateDpAsState(if (i == pager.currentPage) 20.dp else 7.dp, label = "dot")
                Box(Modifier.padding(horizontal = 3.dp).size(w, 7.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = .45f))
                    .clickable { scope.launch { pager.animateScrollToPage(i) } })
            }
        }
    }
}

@Composable
fun MapPlaceholder(height: Int = 150, accent: Color = Selo.Red) {
    // Lightweight route sketch; the full Maps SDK view is wired in RouteMap when a Maps key is configured.
    Box(Modifier.fillMaxWidth().height(height.dp).background(Color(0xFFE9EEF0))) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val road = Color.White
            drawLine(road, androidx.compose.ui.geometry.Offset(0f, h * .27f), androidx.compose.ui.geometry.Offset(w, h * .47f), 18f)
            drawLine(road, androidx.compose.ui.geometry.Offset(0f, h * .73f), androidx.compose.ui.geometry.Offset(w, h * .63f), 18f)
            drawLine(road, androidx.compose.ui.geometry.Offset(w * .25f, 0f), androidx.compose.ui.geometry.Offset(w * .34f, h), 18f)
            drawLine(road, androidx.compose.ui.geometry.Offset(w * .68f, 0f), androidx.compose.ui.geometry.Offset(w * .61f, h), 18f)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * .2f, h * .8f); cubicTo(w * .4f, h * .66f, w * .46f, h * .46f, w * .72f, h * .3f)
            }
            drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(6f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(16f, 12f))))
            drawCircle(Selo.Ink, 14f, androidx.compose.ui.geometry.Offset(w * .72f, h * .3f))
            drawCircle(Color.White, 20f, androidx.compose.ui.geometry.Offset(w * .46f, h * .52f))
            drawCircle(Selo.Green, 14f, androidx.compose.ui.geometry.Offset(w * .46f, h * .52f))
        }
    }
}
