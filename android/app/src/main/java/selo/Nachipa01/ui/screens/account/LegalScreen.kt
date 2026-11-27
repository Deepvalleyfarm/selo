package selo.Nachipa01.ui.screens.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import selo.Nachipa01.ui.components.SeloHeader
import selo.Nachipa01.ui.theme.Selo

/** The four agreements bundled with the app (assets/legal, Markdown converted from the company's Word documents). */
enum class LegalDoc(val key: String, val title: String, val short: String) {
    TERMS("terms", "Terms of Use", "How everyone may use SeloNaChipa"),
    PRIVACY("privacy", "Privacy Policy", "What data we collect and your rights"),
    SELLER("seller", "Seller Terms", "Extra terms for sellers"),
    RIDER_AGENT("rider_agent", "Rider and Agent Terms", "Extra terms for riders and agents");
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: TERMS }
}

private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

/**
 * Blocks screenshots, screen recording and the Recents thumbnail while an agreement is open (FLAG_SECURE), and
 * restores the normal window when the user leaves.
 */
@Composable
fun SecureScreen() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val w = ctx.activity()?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private sealed interface Block
private data class Heading(val level: Int, val text: String) : Block
private data class Para(val text: String, val bullet: Boolean = false) : Block

/** Small Markdown subset used by the agreements: # / ## headings, paragraphs, "- " bullets, **bold**, _italic_, [links](…). */
private fun parse(md: String): List<Block> = md.replace("\\[", "[").replace("\\]", "]").replace("\\'", "'").replace("\\\"", "\"")
    .split(Regex("\n\\s*\n")).flatMap { chunk ->
        val lines = chunk.lines().filter { it.isNotBlank() }
        when {
            lines.isEmpty() -> emptyList()
            lines[0].startsWith("## ") -> listOf(Heading(2, lines[0].removePrefix("## ").trim())) + lines.drop(1).map { Para(it.trim()) }
            lines[0].startsWith("# ") -> listOf(Heading(1, lines[0].removePrefix("# ").trim())) + lines.drop(1).map { Para(it.trim()) }
            lines.all { it.trimStart().startsWith("- ") } -> lines.map { Para(it.trimStart().removePrefix("- ").trim(), bullet = true) }
            else -> listOf(Para(lines.joinToString(" ") { it.trim() }))
        }
    }

/** Inline formatting, with links as tappable annotations (in-app for the other agreements, browser for web links). */
private fun inline(s: String, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    val re = Regex("""\*\*(.+?)\*\*|_(.+?)_|\[(.+?)]\((\S+?)\)|<(\S+@\S+)>""")
    var i = 0
    re.findAll(s).forEach { m ->
        append(s.substring(i, m.range.first))
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            m.groups[2] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Selo.Mute)) { append(m.groupValues[2]) }
            m.groups[3] != null -> { val url = m.groupValues[4]
                withLink(LinkAnnotation.Clickable(url, TextLinkStyles(SpanStyle(color = Selo.Red, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline))) { onLink(url) }) {
                    append(m.groupValues[3]) } }
            else -> append(m.groupValues[5])
        }
        i = m.range.last + 1
    }
    append(s.substring(i))
}

/**
 * Reader for the Terms of Use and the agreements it links to. Text can't be selected or copied (no selection
 * container, no copy action) and the window is protected from screenshots and screen recording.
 */
@Composable
fun LegalScreen(initial: String?, onBack: () -> Unit) {
    SecureScreen()
    val ctx = LocalContext.current
    val stack = remember { mutableStateListOf(LegalDoc.of(initial)) }
    val doc = stack.last()
    val blocks = remember(doc) { runCatching { parse(ctx.assets.open("legal/${doc.key}.md").bufferedReader().readText()) }.getOrDefault(listOf(Para("This document couldn't be opened."))) }
    val list = rememberLazyListState()
    LaunchedEffect(doc) { list.scrollToItem(0) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack() }
    androidx.activity.compose.BackHandler(onBack = ::back)
    fun open(url: String) {
        if (url.startsWith("selo-legal://")) { val d = LegalDoc.of(url.removePrefix("selo-legal://")); if (d != doc) stack.add(d) }
        else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    Column(Modifier.fillMaxSize().background(Selo.Surface)) {
        SeloHeader(doc.title, "SeloNaChipa · Deep Valley Farms Limited", onBack = ::back)
        // Related agreements, one tap away (the Terms of Use links to all of them).
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LegalDoc.entries.forEach { d ->
                Text(d.title.replace(" and ", " & ").replace("Terms of Use", "Terms"), fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                    color = if (d == doc) Color.White else Selo.Red, maxLines = 1,
                    modifier = Modifier.weight(1f).background(if (d == doc) Selo.Red else Selo.RedTint, RoundedCornerShape(50))
                        .clickable { if (d != doc) { stack.clear(); stack.add(d) } }.padding(vertical = 7.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(blocks) { b ->
                when (b) {
                    is Heading -> if (b.level == 1) Text(b.text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Selo.Ink, lineHeight = 28.sp)
                        else Text(b.text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Selo.Red, modifier = Modifier.padding(top = 10.dp))
                    is Para -> if (b.bullet) Row(Modifier.padding(start = 6.dp)) {
                        Text("•", color = Selo.Red, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
                        Text(inline(b.text, ::open), fontSize = 14.5.sp, lineHeight = 21.sp, color = Selo.Ink) }
                        else Text(inline(b.text, ::open), fontSize = 14.5.sp, lineHeight = 21.sp, color = Selo.Ink)
                }
            }
            item {
                Row(Modifier.padding(top = 16.dp).fillMaxWidth().background(Selo.RedTint, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, null, tint = Selo.Red, modifier = Modifier.size(18.dp))
                    Text("  This agreement is protected: copying and screenshots are disabled.", fontSize = 12.sp, color = Selo.Mute)
                }
            }
        }
    }
}

/** The agreements as a full-screen dialog (used during sign-up so the form isn't lost). The dialog window is secure too. */
@Composable
fun LegalDialog(doc: LegalDoc, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false,
        securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        Box(Modifier.fillMaxSize().background(Selo.Surface)) { LegalScreen(doc.key, onDismiss) }
    }
}

/** "Read: Terms of Use · Privacy Policy · …" — tappable links to the agreements a form asks the user to accept. */
@Composable
fun LegalLinks(docs: List<LegalDoc>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf<LegalDoc?>(null) }
    Column(modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Read before you accept", fontSize = 12.sp, color = Selo.Mute, fontWeight = FontWeight.Bold)
        docs.forEach { d -> Row(Modifier.fillMaxWidth().clickable { open = d }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Description, null, tint = Selo.Red, modifier = Modifier.size(18.dp))
            Text("  ${d.title}", color = Selo.Red, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, null, tint = Selo.Mute, modifier = Modifier.size(18.dp)) } }
    }
    open?.let { LegalDialog(it) { open = null } }
}
