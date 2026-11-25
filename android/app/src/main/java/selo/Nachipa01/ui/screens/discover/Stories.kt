package selo.Nachipa01.ui.screens.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import selo.Nachipa01.data.events.BehaviourEvent
import selo.Nachipa01.data.model.Listing
import selo.Nachipa01.data.model.Story
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.theme.Selo

private const val STORY_MS = 5_000L
private val ring = Brush.sweepGradient(listOf(Selo.Red, Selo.Purple, Color(0xFFF59E0B), Selo.Red))

/** Feed Stories (PRD v6.2): paid 24-hour stories at the top of buyer Home. Followed sellers come first. */
@Composable
fun StoriesRow(onOpen: (Listing) -> Unit) {
    val app = LocalAppState.current
    var stories by remember { mutableStateOf<List<Story>>(emptyList()) }
    var openAt by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) { stories = runCatching { app.backend.stories() }.getOrDefault(emptyList()) }
    if (stories.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        stories.forEachIndexed { i, s ->
            Column(Modifier.width(68.dp).clickable { openAt = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).background(ring, CircleShape).padding(3.dp).background(Color.White, CircleShape).padding(2.dp).clip(CircleShape)
                    .background(Selo.RedTint), contentAlignment = Alignment.Center) {
                    val img = s.listing.thumbUrl ?: s.listing.photoUrls.firstOrNull()
                    if (img != null) AsyncImage(img, s.listing.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Outlined.Inventory2, null, tint = Selo.Red)
                }
                Text(s.sellerName, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (s.followed) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
    openAt?.let { start -> StoryViewer(stories, start, onClose = { openAt = null }, onOpen = { openAt = null; onOpen(it) }) }
}

@Composable
fun StoryViewer(stories: List<Story>, start: Int, onClose: () -> Unit, onOpen: (Listing) -> Unit) {
    val app = LocalAppState.current
    var index by remember { mutableIntStateOf(start) }
    var progress by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    val s = stories[index]
    LaunchedEffect(index) {
        app.track(BehaviourEvent("listing_view", s.listing.id, s.listing.sellerUid, s.listing.category, s.listing.mediaType, source = "story", promoId = s.promoId))
        progress = 0f
        while (progress < 1f) { delay(50); if (!paused) progress += 50f / STORY_MS }
        if (index < stories.lastIndex) index++ else onClose()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(index) {
            detectTapGestures(
                onPress = { paused = true; tryAwaitRelease(); paused = false },
                onTap = { o -> if (o.x < size.width / 3) { if (index > 0) index-- } else if (index < stories.lastIndex) index++ else onClose() },
            )
        }) {
            val img = s.listing.thumbUrl ?: s.listing.photoUrls.firstOrNull()
            if (img != null) AsyncImage(img, s.listing.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Selo.Red, Selo.RedDeep))))
            Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(.55f), Color.Transparent))))
            Box(Modifier.fillMaxWidth().fillMaxHeight(.45f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.8f)))))
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    stories.indices.forEach { i ->
                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(.35f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(when { i < index -> 1f; i == index -> progress.coerceIn(0f, 1f); else -> 0f }).background(Color.White))
                        }
                    }
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).background(Selo.Red, CircleShape).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        Text(s.sellerName.take(1), color = Color.White, fontWeight = FontWeight.ExtraBold)
                    }
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(s.sellerName, color = Color.White, fontWeight = FontWeight.ExtraBold)
                        Text(if (s.followed) "Following · Sponsored" else "Sponsored", color = Color.White.copy(.8f), fontSize = 11.sp)
                    }
                    IconButton(onClose) { Icon(Icons.Outlined.Close, "Close", tint = Color.White) }
                }
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().navigationBarsPadding().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(s.listing.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
                Text(Fees.formatK(s.listing.offerPriceNgwee), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text(listOf(s.listing.district, s.listing.province).filter { it.isNotBlank() }.joinToString(", ").ifBlank { s.listing.zone },
                    color = Color.White.copy(.85f), fontSize = 13.sp)
                Button({
                    app.track(BehaviourEvent("details_open", s.listing.id, s.listing.sellerUid, s.listing.category, s.listing.mediaType, source = "story", promoId = s.promoId))
                    onOpen(s.listing)
                }, Modifier.fillMaxWidth().padding(top = 6.dp).height(50.dp), shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {
                    Text("View listing", color = Selo.Red, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                }
            }
        }
    }
}
