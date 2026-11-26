package selo.Nachipa01.ui.screens.seller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import selo.Nachipa01.data.model.AdPack
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo
import java.io.File
import java.net.URL

/** Where each piece of copy goes, and the app it opens. */
private enum class Channel(val label: String, val pkg: String?, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    WHATSAPP("WhatsApp Status", "com.whatsapp", Icons.Outlined.Chat), FACEBOOK("Facebook", "com.facebook.katana", Icons.Outlined.Public),
    TIKTOK("TikTok", "com.zhiliaoapp.musically", Icons.Outlined.MusicNote),
}

/**
 * AI Ad Generator: one product photo in; a promotional image (9:16 story and 1:1 square), a product description and
 * ready-to-post copy for WhatsApp Status, Facebook and TikTok out. Each can be copied or shared straight to the app.
 */
@Composable
fun AdGeneratorScreen(onBack: () -> Unit) {
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var photo by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var priceK by remember { mutableStateOf("") }
    var offer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var ad by remember { mutableStateOf<AdPack?>(null) }
    val pick = rememberPhotoSource("Product photo") { photo = it; ad = null }
    LaunchedEffect(busy) {
        val steps = listOf("Uploading your photo…", "Reading the product…", "Writing your ad…", "Designing the image…")
        var i = 0; while (busy) { stage = steps[i % steps.size]; i++; kotlinx.coroutines.delay(2_200) }
    }

    Column(Modifier.fillMaxSize().background(Selo.Surface)) {
        SeloHeader("AI Ad Generator", "Photo in · ad out for WhatsApp, Facebook and TikTok", onBack = { if (ad != null) ad = null else onBack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val a = ad
            if (a == null) {
                Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)).background(if (photo != null) Color.Black else Selo.RedTint)
                    .border(1.5.dp, if (photo != null) Selo.Green else Selo.Red.copy(.4f), RoundedCornerShape(16.dp)).clickable { pick() }, contentAlignment = Alignment.Center) {
                    photo?.let { coil3.compose.AsyncImage(it, "Product photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        ?: Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.AddAPhoto, null, tint = Selo.Red, modifier = Modifier.size(36.dp))
                            Text("Add a product photo *", fontWeight = FontWeight.ExtraBold, color = Selo.Red)
                            Text("Camera or gallery · plain background works best", fontSize = 12.sp, color = Selo.Mute) }
                }
                SeloField("Product name (optional)", name, { name = it.take(60) }, placeholder = "AI names it from the photo if empty")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SeloField("Price (K)", priceK, { priceK = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                    SeloField("Offer (optional)", offer, { offer = it.take(40) }, Modifier.weight(1.4f), placeholder = "e.g. Free delivery today")
                }
                NoteBox("Your photo stays exactly as it is — Selo adds the headline, price and branding around it. The AI writes only what it can see and what you typed.", Tone.INFO)
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), color = Selo.Red, strokeWidth = 2.dp); Text("  $stage", color = Selo.Mute) }
            } else AdResult(a, priceK.toDoubleOrNull()?.let { Fees.kwachaToNgwee(it) } ?: 0)
        }
        if (ad == null) Column(Modifier.background(Color.White).padding(14.dp)) {
            PrimaryButton("Generate my ad", enabled = photo != null && !busy, loading = busy, whyDisabled = "Add a product photo first.") {
                busy = true
                scope.launch {
                    runCatching { app.backend.generateAd(photo!!, name.trim(), priceK.toDoubleOrNull()?.let { Fees.kwachaToNgwee(it) } ?: 0, offer.trim()) }
                        .onSuccess { ad = it; app.ok("Your ad is ready") }.onFailure { app.fail(it.message ?: "Couldn't make the ad. Try again.") }
                    busy = false
                }
            }
        }
    }
}

@Composable
private fun AdResult(a: AdPack, priceNgwee: Long) {
    val ctx = LocalContext.current
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    var square by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SeloChip("Status / TikTok 9:16", !square) { square = false }; SeloChip("Facebook 1:1", square) { square = true } }
    val url = if (square) a.squareUrl else a.storyUrl
    Box(Modifier.fillMaxWidth().aspectRatio(if (square) 1f else 9f / 16f).clip(RoundedCornerShape(16.dp)).background(Selo.Ink), contentAlignment = Alignment.Center) {
        coil3.compose.AsyncImage(url, "Promotional image", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // Demo / preview: the server composes the real image; show the headline over the photo if it is a raw photo.
        if (url?.startsWith("http") != true) Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(a.headline, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.background(Color.Black.copy(.6f)).padding(8.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                if (priceNgwee > 0) Text(Fees.formatK(priceNgwee), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.background(Selo.Red).padding(8.dp))
                Spacer(Modifier.height(8.dp))
                Text("Order on SeloNaChipa · Pay with Mobile Money", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().background(Selo.Red).padding(8.dp))
            }
        }
    }
    OutlineButton("Save / share image") { scope.launch { shareImage(ctx, url, null, null)?.let { app.fail(it) } } }
    CopyCard("Product description", Icons.Outlined.Description, a.description, null)
    CopyCard(Channel.WHATSAPP.label, Channel.WHATSAPP.icon, a.whatsapp, Channel.WHATSAPP, a.storyUrl)
    CopyCard(Channel.FACEBOOK.label, Channel.FACEBOOK.icon, a.facebook + "\n\n" + a.hashtags.joinToString(" "), Channel.FACEBOOK, a.squareUrl)
    CopyCard(Channel.TIKTOK.label, Channel.TIKTOK.icon, a.tiktok + " " + a.hashtags.joinToString(" "), Channel.TIKTOK, a.storyUrl)
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun CopyCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, ch: Channel?, image: String? = null) {
    val ctx = LocalContext.current
    val app = LocalAppState.current
    val scope = rememberCoroutineScope()
    SeloCard {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Selo.Red); Text("  $title", fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text("${text.length} chars", color = Selo.Mute, fontSize = 11.sp) }
        Text(text, fontSize = 14.sp, lineHeight = 19.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, text)); app.ok("Copied") }) {
                Icon(Icons.Outlined.ContentCopy, null, tint = Selo.Red, modifier = Modifier.size(16.dp)); Text(" Copy", color = Selo.Red, fontWeight = FontWeight.Bold) }
            if (ch != null) TextButton({ scope.launch { shareImage(ctx, image, text, ch.pkg)?.let { app.fail(it) } } }) {
                Icon(Icons.Outlined.Share, null, tint = Selo.Red, modifier = Modifier.size(16.dp)); Text(" Post to ${ch.label.substringBefore(' ')}", color = Selo.Red, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Downloads the composed image to the cache and opens the share sheet (or the given app). Returns an error message. */
private suspend fun shareImage(ctx: Context, url: String?, text: String?, pkg: String?): String? {
    val file = runCatching {
        withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "receipts").apply { mkdirs() }
            File(dir, "selo-ad-${System.currentTimeMillis()}.jpg").also { f ->
                when {
                    url == null -> error("no image")
                    url.startsWith("http") -> URL(url).openStream().use { i -> f.outputStream().use { i.copyTo(it) } }
                    else -> ctx.contentResolver.openInputStream(Uri.parse(url))!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                }
            }
        }
    }.getOrNull()
    val send = Intent(Intent.ACTION_SEND).apply {
        type = if (file != null) "image/jpeg" else "text/plain"
        file?.let { putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
    }
    if (pkg != null) runCatching { ctx.startActivity(Intent(send).setPackage(pkg)); return null }
    return runCatching { ctx.startActivity(Intent.createChooser(send, "Share your ad")); null }.getOrElse { if (file == null && text == null) "Couldn't load the image. Check your connection." else "No app found to share with." }
}
