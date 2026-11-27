package selo.Nachipa01.ui.screens.account

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import selo.Nachipa01.data.model.PerformanceReport
import selo.Nachipa01.data.model.ReportMetric
import selo.Nachipa01.domain.Fees
import selo.Nachipa01.ui.LocalAppState
import selo.Nachipa01.ui.components.*
import selo.Nachipa01.ui.theme.Selo
import java.time.YearMonth

fun formatMetric(m: ReportMetric, v: Long = m.value) = when (m.unit) {
    "ngwee" -> Fees.formatK(v); "pct" -> "$v%"; "km" -> "$v km"; "min" -> "$v min"; else -> "%,d".format(v)
}
/** Lower is better for these (shown green when they fall). */
private val LOWER_IS_BETTER = setOf("cancelRate")

/** Monthly performance report for sellers and riders: month picker, key numbers vs last month, highlights and tips. */
@Composable
fun PerformanceReportScreen(initialMonth: String?, onBack: () -> Unit) {
    val app = LocalAppState.current
    val ctx = LocalContext.current
    val thisMonth = remember { YearMonth.now() }
    var month by remember { mutableStateOf(initialMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: thisMonth.minusMonths(1)) }
    var r by remember { mutableStateOf<PerformanceReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(month) {
        loading = true
        runCatching { app.backend.performanceReport(month.toString()) }.onSuccess { r = it }.onFailure { app.fail(it.message ?: "Couldn't load the report.") }
        loading = false
    }
    Column(Modifier.fillMaxSize().background(Selo.Surface)) {
        SeloHeader("Monthly report", r?.monthLabel ?: "", onBack = onBack, trailing = {
            r?.let { rep -> IconButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, reportText(rep)), "Share report")) }) {
                Icon(Icons.Outlined.Share, "Share report", tint = Color.White) } } })
        Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ month = month.minusMonths(1) }) { Icon(Icons.Outlined.ChevronLeft, "Previous month", tint = Selo.Red) }
            Text(month.month.name.lowercase().replaceFirstChar { it.uppercase() } + " ${month.year}" + if (month == thisMonth) " (so far)" else "",
                Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton({ if (month < thisMonth) month = month.plusMonths(1) }, enabled = month < thisMonth) { Icon(Icons.Outlined.ChevronRight, "Next month", tint = if (month < thisMonth) Selo.Red else Selo.Line) }
        }
        val rep = r
        if (loading && rep == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Selo.Red) }
        else if (rep != null) Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Selo.Red)
            rep.metrics.firstOrNull()?.let { lead ->
                Column(Modifier.fillMaxWidth().background(Selo.Red, RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Text(lead.label, color = Color.White.copy(.85f), fontSize = 13.sp)
                    Text(formatMetric(lead), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    lead.changePct?.let { c -> Text("${if (c >= 0) "▲" else "▼"} ${kotlin.math.abs(c)}% vs ${formatMetric(lead, lead.previous)} last month", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                }
            }
            rep.metrics.drop(1).chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { MetricTile(it, Modifier.weight(1f)) }; if (row.size == 1) Spacer(Modifier.weight(1f)) }
            }
            // Role-specific detail: a rider's earnings by job type, an agent's commission by seller, a seller's sales sources.
            rep.breakdowns.forEach { b -> SectionLabel(b.title); SeloCard { b.rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(color = Selo.Line)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(row.label, fontWeight = FontWeight.SemiBold); row.sub?.let { Text(it, color = Selo.Mute, fontSize = 12.sp) } }
                    Text(row.value, fontWeight = FontWeight.ExtraBold)
                } } } }
            if (rep.highlights.isNotEmpty()) { SectionLabel(rep.highlightsTitle); SeloCard { rep.highlights.forEach { h -> Row { Icon(Icons.Outlined.Star, null, tint = Selo.Amber, modifier = Modifier.size(18.dp)); Text("  $h") } } } }
            if (rep.top.isNotEmpty()) { SectionLabel("Top products"); SeloCard { rep.top.forEachIndexed { i, t -> KvRow("${i + 1}. ${t.title} · ${t.units} sold", Fees.formatK(t.revenueNgwee)) } } }
            SectionLabel(rep.tipsTitle)
            SeloCard { rep.tips.forEach { t -> Row { Icon(Icons.Outlined.Lightbulb, null, tint = Selo.Red, modifier = Modifier.size(18.dp)); Text("  $t") } } }
            Sub(when (rep.role) { "RIDER" -> "Covers the deliveries you completed (orders and parcels). Ready on the 1st of each month."
                "AGENT" -> "Covers sales you made for your sellers and your commission after the platform share. Ready on the 1st of each month."
                else -> "Reports are ready on the 1st of each month. Amounts are in Kwacha and exclude refunded orders." })
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun MetricTile(m: ReportMetric, modifier: Modifier) {
    val c = m.changePct
    val good = c != null && ((c >= 0) != (m.key in LOWER_IS_BETTER))
    Column(modifier.background(Color.White, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text(m.label, color = Selo.Mute, fontSize = 12.sp, maxLines = 2, lineHeight = 15.sp)
        Text(formatMetric(m), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
        if (c != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (c >= 0) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown, null, tint = if (good) Selo.Green else Selo.Red, modifier = Modifier.size(16.dp))
            Text(" ${if (c >= 0) "+" else ""}$c%", color = if (good) Selo.GreenDark else Selo.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        } else if (m.unit == "pct" && m.value != m.previous) Text("was ${m.previous}%", color = Selo.Mute, fontSize = 12.sp)
    }
}

private fun reportText(r: PerformanceReport) = buildString {
    appendLine("SeloNaChipa · ${r.monthLabel} report")
    r.metrics.forEach { appendLine("${it.label}: ${formatMetric(it)}") }
    r.highlights.forEach { appendLine("• $it") }
}
