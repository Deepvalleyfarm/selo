package selo.Nachipa01.ui.screens.discover

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import selo.Nachipa01.data.model.Listing
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.ui.theme.Selo
import kotlin.math.roundToInt

/** Callbacks a feed card raises; DiscoverScreen turns them into behaviour events and UI. */
class CardActions(
    val onLike: (Boolean) -> Unit, val onSaveLongPress: () -> Unit, val onShare: () -> Unit, val onEnquire: () -> Unit,
    val onDetails: () -> Unit, val onNotInterested: () -> Unit, val onFollow: () -> Unit, val onWatched: (watchMs: Long, totalMs: Long, replayed: Boolean) -> Unit,
)

/**
 * Full-screen listing card (PRD v6.1 §5.1.1). [active] is true only when the card is ≥50% visible and settled,
 * which drives autoplay/pause. Video plays muted until tapped.
 */
@Composable
fun FeedCard(l: Listing, active: Boolean, liked: Boolean, saved: Boolean, following: Boolean, actions: CardActions, modifier: Modifier = Modifier) {
    var muted by remember { mutableStateOf(true) }
    var photo by remember(l.id) { mutableIntStateOf(0) }
    var heart by remember { mutableStateOf(false) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val photos = l.photoUrls.ifEmpty { listOf("") }

    // photos auto-advance every 3 s while visible
    LaunchedEffect(active, l.id) { if (active && !l.isVideo && photos.size > 1) while (true) { delay(3_000); photo = (photo + 1) % photos.size } }
    LaunchedEffect(heart) { if (heart) { delay(700); heart = false } }

    Box(modifier.fillMaxSize().background(Color.Black)
        .offset { IntOffset(drag.value.roundToInt(), 0) }
        .alpha(1f - (kotlin.math.abs(drag.value) / 900f).coerceIn(0f, .6f))
        .pointerInput(l.id) {
            detectTapGestures(
                onDoubleTap = { heart = true; if (!liked) actions.onLike(true) },
                onLongPress = { actions.onSaveLongPress() },
                onTap = { if (l.isVideo) muted = !muted else photo = (photo + 1) % photos.size },
            )
        }
        .pointerInput(l.id) {
            detectHorizontalDragGestures(
                onDragEnd = { scope.launch { if (drag.value < -size.width * 0.3f) { drag.animateTo(-size.width.toFloat()); actions.onNotInterested() } else drag.animateTo(0f) } },
                onHorizontalDrag = { _, dx -> scope.launch { drag.snapTo((drag.value + dx).coerceAtMost(0f)) } },
            )
        }) {

        if (l.isVideo) VideoLayer(l, active, muted, actions.onWatched) else PhotoLayer(l, photos, photo)

        // readability scrims
        Box(Modifier.fillMaxWidth().height(160.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(.45f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().fillMaxHeight(.45f).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.75f)))))

        // top: category chip (right), photo dots / mute state (left)
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!l.isVideo && photos.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                photos.indices.forEach { i -> Box(Modifier.size(if (i == photo) 16.dp else 6.dp, 6.dp).background(Color.White.copy(if (i == photo) 1f else .5f), CircleShape)) }
            }
            if (l.isVideo) Icon(if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp, if (muted) "Tap to unmute" else "Tap to mute", tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.weight(1f))
            if (l.hasOffer) Text(if (l.discountPct > 0) "−${l.discountPct.toInt()}%" else "OFFER", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp,
                modifier = Modifier.padding(end = 6.dp).background(Selo.Coral, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
            Text(l.category, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                modifier = Modifier.background(Color.White.copy(.22f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp))
        }

        // right action bar
        Column(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 10.dp, bottom = 96.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionButton(if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, compact(l.likes + (if (liked) 1 else 0)), "Like", if (liked) Selo.Coral else Color.White) { actions.onLike(!liked) }
            ActionButton(Icons.Outlined.Share, compact(l.shares), "Share") { actions.onShare() }
            ActionButton(Icons.AutoMirrored.Outlined.Chat, "Enquire", "Enquire") { actions.onEnquire() }
            ActionButton(if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, "Save", "Save to wishlist", if (saved) Selo.Amber else Color.White) { actions.onSaveLongPress() }
        }

        // bottom-left: seller, title, price, details
        Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 16.dp, end = 84.dp, bottom = 92.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.sellerName, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                if (l.sellerVerified) Icon(Icons.Filled.Verified, "Verified seller", tint = Color(0xFF4FC3F7), modifier = Modifier.padding(start = 4.dp).size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (following) "Following" else "Follow", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.border(1.dp, Color.White, RoundedCornerShape(50)).clickable { actions.onFollow() }.padding(horizontal = 10.dp, vertical = 3.dp))
            }
            Text(l.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fees.formatK(l.offerPriceNgwee).removeSuffix(".00"), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                if (l.hasOffer) Text(Fees.formatK(l.priceNgwee).removeSuffix(".00"), color = Color.White.copy(.7f), fontSize = 13.sp,
                    textDecoration = TextDecoration.LineThrough, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            }
            Text("${l.district.ifBlank { l.province }} · View details", color = Color.White.copy(.9f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { actions.onDetails() }.padding(vertical = 4.dp))
        }

        AnimatedVisibility(heart, Modifier.align(Alignment.Center), enter = scaleIn() + fadeIn(), exit = fadeOut()) {
            Icon(Icons.Filled.Favorite, null, tint = Selo.Coral, modifier = Modifier.size(110.dp))
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, description: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(46.dp).background(Color.Black.copy(.25f), CircleShape), contentAlignment = Alignment.Center) { Icon(icon, description, tint = tint, modifier = Modifier.size(28.dp)) }
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

fun compact(n: Int) = when { n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 1_000 -> "%.1fK".format(n / 1e3); else -> "$n" }

@Composable
private fun PhotoLayer(l: Listing, photos: List<String>, index: Int) {
    val url = photos.getOrNull(index).orEmpty()
    val shift by animateFloatAsState(index.toFloat(), label = "photo")
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(l.colorA), Color(l.colorB))))) {
        if (url.startsWith("http") || url.startsWith("content:") || url.startsWith("file:"))
            AsyncImage(url, l.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Outlined.Image, null, tint = Color.White.copy(.35f), modifier = Modifier.size(96.dp).align(Alignment.Center).graphicsLayer { rotationZ = shift * 4 })
    }
}

/** ExoPlayer for real videos; demo listings without a URL get a timed placeholder so watch-time tracking still works. */
@OptIn(UnstableApi::class)
@Composable
private fun VideoLayer(l: Listing, active: Boolean, muted: Boolean, onWatched: (Long, Long, Boolean) -> Unit) {
    val ctx = LocalContext.current
    var progress by remember { mutableFloatStateOf(0f) }
    val url = l.videoUrl
    if (url == null) {
        var watched by remember(l.id) { mutableLongStateOf(0L) }
        val total = l.durationMs.takeIf { it > 0 } ?: 30_000L
        var loops by remember(l.id) { mutableIntStateOf(0) }
        LaunchedEffect(active) {
            if (active) while (true) { delay(100); watched += 100; if (watched % total == 0L) loops++; progress = (watched % total) / total.toFloat() }
        }
        DisposableEffect(active) { onDispose { if (watched > 0) onWatched(watched.coerceAtMost(total), total, loops > 0) } }
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(l.colorA), Color(l.colorB))))) {
            Icon(Icons.Outlined.PlayCircle, null, tint = Color.White.copy(if (active) .25f else .7f), modifier = Modifier.size(84.dp).align(Alignment.Center))
        }
    } else {
        val player = remember(url) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(Uri.parse(url))); repeatMode = Player.REPEAT_MODE_ONE; prepare() } }
        var startedAt by remember { mutableLongStateOf(0L) }
        var replays by remember { mutableIntStateOf(0) }
        LaunchedEffect(active) {
            player.playWhenReady = active
            if (active) { startedAt = System.currentTimeMillis(); while (true) { delay(150); val d = player.duration.takeIf { it > 0 } ?: 1
                progress = player.currentPosition / d.toFloat(); if (player.currentPosition < 200 && System.currentTimeMillis() - startedAt > 1_000) replays = replays.coerceAtLeast(1) } }
        }
        LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
        DisposableEffect(active) {
            onDispose { if (startedAt > 0) { onWatched(System.currentTimeMillis() - startedAt, player.duration.takeIf { it > 0 } ?: 1, replays > 0); startedAt = 0 } }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        AndroidView({ PlayerView(it).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; this.player = player } }, Modifier.fillMaxSize())
    }
    // thin progress line at the very top
    Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(.25f))) { Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color.White)) }
}
