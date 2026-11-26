package selo.Nachipa01.ui.screens.account

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import selo.Nachipa01.BuildConfig
import selo.Nachipa01.data.model.Receipt
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Public verification page for a receipt (same host as parcel tracking). */
fun receiptLink(r: Receipt) = BuildConfig.TRACKING_BASE_URL.replace("/t/", "/r/") + r.token
private fun paidOn(r: Receipt) = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK).format(Date(r.paidAt))

/** My receipts (refId = null) or the receipt for one order / parcel / promotion. */
@Composable
fun ReceiptsScreen(refId: String?, onBack: () -> Unit) {
    val app = LocalAppState.current
    var list by remember { mutableStateOf<List<Receipt>?>(null) }
    var open by remember { mutableStateOf<Receipt?>(null) }
    LaunchedEffect(refId) {
        runCatching { app.backend.receipts(refId) }.onSuccess { list = it; if (refId != null) open = it.firstOrNull() }
            .onFailure { list = emptyList(); app.fail(it.message ?: "Couldn't load receipts.") }
    }
    val r = open
    Column(Modifier.fillMaxSize().background(Selo.Surface)) {
        SeloHeader(if (r != null) "Receipt ${r.number}" else "My receipts", if (r != null) "Paid ${paidOn(r)}" else "Every confirmed payment",
            onBack = { if (r != null && refId == null) open = null else onBack() })
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Selo.Red) }
            r != null -> ReceiptDetail(r)
            list!!.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(if (refId != null) "The receipt appears here once your payment is confirmed." else "No receipts yet. You get one for every confirmed payment.", color = Selo.Mute) }
            else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list!!.forEach { x ->
                    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).clickable { open = x }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Receipt, null, tint = Selo.Red)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(x.number, fontWeight = FontWeight.Bold); Text("${x.onBehalfOf} · ${paidOn(x)}", color = Selo.Mute, fontSize = 12.sp) }
                        Text(Fees.formatK(x.totalNgwee), fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiptDetail(r: Receipt) {
    val ctx = LocalContext.current
    val app = LocalAppState.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SeloCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SeloNaChipa", color = Selo.Red, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
                StatusChip("PAID", Tone.OK)
            }
            Text(r.issuerNote, color = Selo.Mute, fontSize = 12.sp)
            HorizontalDivider(color = Selo.Line, modifier = Modifier.padding(vertical = 6.dp))
            KvRow("Received from", r.payerName); KvRow("Date", paidOn(r)); KvRow("Paid with", "${r.method} ${r.wallet}"); KvRow("Reference", r.refId)
            HorizontalDivider(color = Selo.Line, modifier = Modifier.padding(vertical = 6.dp))
            r.lines.forEach { l -> Column { KvRow(l.label, Fees.formatK(l.amountNgwee)); l.onBehalfOf?.let { Text("on behalf of $it", color = Selo.Mute, fontSize = 11.sp) } } }
            HorizontalDivider(color = Selo.Line, modifier = Modifier.padding(vertical = 6.dp))
            KvRow("Total paid", Fees.formatK(r.totalNgwee), strong = true)
            Text("Payment ref ${r.paymentReference}", color = Selo.Mute, fontSize = 11.sp)
        }
        PrimaryButton("Share on WhatsApp", color = Color(0xFF25D366)) { shareReceipt(ctx, r, whatsapp = true) { app.fail(it) } }
        OutlineButton("Send by email") { shareReceipt(ctx, r, email = true) { app.fail(it) } }
        TextButton({ shareReceipt(ctx, r) { app.fail(it) } }, Modifier.align(Alignment.CenterHorizontally)) {
            Icon(Icons.Outlined.Share, null, tint = Selo.Red); Text("  Other apps", color = Selo.Red) }
        Text("Anyone with the link can check this receipt is genuine: ${receiptLink(r)}", color = Selo.Mute, fontSize = 11.sp)
    }
}

/** Renders the receipt to an A5 PDF (cache/receipts) for sharing. */
fun receiptPdf(ctx: Context, r: Receipt): File {
    val doc = PdfDocument(); val page = doc.startPage(PdfDocument.PageInfo.Builder(420, 595, 1).create()); val c = page.canvas
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var y = 44f
    fun text(s: String, x: Float, size: Float, bold: Boolean = false, color: Int = 0xFF111827.toInt(), right: Boolean = false) {
        p.textSize = size; p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; p.color = color; p.textAlign = if (right) Paint.Align.RIGHT else Paint.Align.LEFT
        c.drawText(s, x, y, p)
    }
    text("SeloNaChipa", 28f, 20f, true, 0xFFC8102E.toInt()); text("PAID", 392f, 12f, true, 0xFF166534.toInt(), right = true); y += 26
    text("Receipt ${r.number}", 28f, 15f, true); y += 18
    r.issuerNote.chunked(68).forEach { text(it, 28f, 9.5f, color = 0xFF6B7280.toInt()); y += 13 }
    y += 6
    listOf("Received from" to r.payerName, "Date" to paidOn(r), "Paid with" to "${r.method} ${r.wallet}", "Reference" to r.refId).forEach { (k, v) ->
        text(k, 28f, 10.5f, color = 0xFF6B7280.toInt()); text(v, 392f, 10.5f, right = true); y += 17 }
    p.color = 0xFFE5E7EB.toInt(); c.drawLine(28f, y - 6, 392f, y - 6, p); y += 8
    r.lines.forEach { l -> text(l.label.take(52), 28f, 10.5f); text(Fees.formatK(l.amountNgwee), 392f, 10.5f, right = true); y += 13
        l.onBehalfOf?.let { text("on behalf of $it", 28f, 8.5f, color = 0xFF6B7280.toInt()); y += 13 }; y += 3 }
    p.color = 0xFFE5E7EB.toInt(); c.drawLine(28f, y - 6, 392f, y - 6, p); y += 10
    text("Total paid", 28f, 13f, true); text(Fees.formatK(r.totalNgwee), 392f, 13f, true, right = true); y += 22
    text("Payment ref ${r.paymentReference}", 28f, 8.5f, color = 0xFF6B7280.toInt()); y += 12
    text("Verify: ${receiptLink(r)}", 28f, 8.5f, color = 0xFF6B7280.toInt()); y += 12
    text("Funds are held in Selo Escrow and released only on delivery. WhatsApp +260 978 070 734.", 28f, 8.5f, color = 0xFF6B7280.toInt())
    doc.finishPage(page)
    val dir = File(ctx.cacheDir, "receipts").apply { mkdirs() }
    val f = File(dir, "${r.number}.pdf"); f.outputStream().use { doc.writeTo(it) }; doc.close()
    return f
}

fun shareReceipt(ctx: Context, r: Receipt, whatsapp: Boolean = false, email: Boolean = false, onError: (String) -> Unit) {
    val uri: Uri = runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", receiptPdf(ctx, r)) }.getOrElse { onError("Couldn't create the receipt PDF."); return }
    val text = "SeloNaChipa receipt ${r.number}: ${Fees.formatK(r.totalNgwee)} paid ${paidOn(r)}. ${r.issuerNote}. Verify: ${receiptLink(r)}"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "SeloNaChipa receipt ${r.number}"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (whatsapp) setPackage("com.whatsapp")
        if (email) selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
    }
    runCatching { ctx.startActivity(if (whatsapp) send else Intent.createChooser(send, if (email) "Send receipt by email" else "Share receipt").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { if (whatsapp) { onError("WhatsApp isn't installed — choose another app."); shareReceipt(ctx, r, onError = onError) } else onError("No app found to share the receipt.") }
}
