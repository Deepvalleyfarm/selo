package selo.Nachipa01.ui.screens.rider

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import selo.Nachipa01.data.model.RiderDelivery
import selo.Nachipa01.data.model.RiderProfile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Statement periods offered on the Earnings tab. */
enum class StatementPeriod(val label: String) { THIS_MONTH("This month"), LAST_MONTH("Last month"), LAST_90("Last 90 days"), ALL("All deliveries") }

fun statementRange(p: StatementPeriod, now: Long = System.currentTimeMillis()): Pair<Long, Long> {
    val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    return when (p) {
        StatementPeriod.THIS_MONTH -> c.timeInMillis to now
        StatementPeriod.LAST_MONTH -> { val end = c.timeInMillis; c.add(Calendar.MONTH, -1); c.timeInMillis to end }
        StatementPeriod.LAST_90 -> now - 90L * 86_400_000 to now
        StatementPeriod.ALL -> 0L to now
    }
}

private fun k(n: Long) = "K " + "%,.2f".format(Locale.UK, n / 100.0)
private val RED = Color.rgb(200, 16, 46); private val INK = Color.rgb(17, 24, 39); private val MUTE = Color.rgb(107, 114, 128)
private val LINE = Color.rgb(229, 231, 235); private val ZEBRA = Color.rgb(249, 250, 251); private val GREEN = Color.rgb(22, 101, 52)

/**
 * Rider statement as an A4 PDF: branded header, rider details and period, summary totals, then one row per delivery
 * (date, job, route, km, gross, platform fee, Social Fund, bonus, net) with the column headers repeated on every page,
 * page numbers and a totals row.
 */
fun riderStatementPdf(ctx: Context, profile: RiderProfile?, riderName: String, all: List<RiderDelivery>, period: StatementPeriod): File {
    val doc = PdfDocument()
    var page: PdfDocument.Page? = null
    drawStatement(profile, riderName, all, period, start = { n -> page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create()); page!!.canvas },
        finish = { doc.finishPage(page) })
    val dir = File(ctx.cacheDir, "receipts").apply { mkdirs() }
    val file = File(dir, "SeloNaChipa-statement-${period.name.lowercase()}-${SimpleDateFormat("yyyyMMdd", Locale.UK).format(Date())}.pdf")
    file.outputStream().use { doc.writeTo(it) }; doc.close()
    return file
}

/** Draws the statement page by page on any canvas (PDF in the app, bitmaps in tests). A4 points: 595 × 842. */
fun drawStatement(profile: RiderProfile?, riderName: String, all: List<RiderDelivery>, period: StatementPeriod, start: (Int) -> android.graphics.Canvas, finish: () -> Unit) {
    val (from, to) = statementRange(period)
    val rows = all.filter { it.at in from..to }.sortedBy { it.at }
    val W = 595; val H = 842; val M = 36f
    val day = SimpleDateFormat("d MMM yyyy", Locale.UK); val dt = SimpleDateFormat("d MMM, HH:mm", Locale.UK)
    fun paint(size: Float, color: Int = INK, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL); textAlign = align }
    fun fit(s: String, p: Paint, w: Float): String { if (p.measureText(s) <= w) return s; var t = s; while (t.isNotEmpty() && p.measureText("$t…") > w) t = t.dropLast(1); return "$t…" }

    // columns: x positions (left edge) and widths; money columns right-aligned
    data class Col(val title: String, val x: Float, val w: Float, val right: Boolean = false)
    val cols = listOf(Col("Date", M, 62f), Col("Job", M + 62, 62f), Col("Route", M + 124, 118f), Col("km", M + 242, 30f, true),
        Col("Gross", M + 272, 58f, true), Col("Fee", M + 330, 48f, true), Col("Fund", M + 378, 48f, true), Col("Bonus", M + 426, 42f, true), Col("Net", M + 468, 55f, true))
    val sum = { f: (RiderDelivery) -> Long -> rows.sumOf(f) }
    val tGross = sum { it.grossNgwee }; val tFee = sum { it.platformNgwee }; val tFund = sum { it.fundNgwee }; val tBonus = sum { it.bonusNgwee }; val tNet = sum { it.netNgwee }
    val tKm = rows.sumOf { it.km }

    var pageNo = 0; var canvas: android.graphics.Canvas? = null; var y = 0f
    val rowH = 20f
    fun footer() { val c = canvas!!
        c.drawLine(M, H - 40f, W - M, H - 40f, Paint().apply { color = LINE })
        c.drawText("SeloNaChipa · Escrow-protected deliveries · support +260 978 070 734", M, H - 26f, paint(8f, MUTE))
        c.drawText("Page $pageNo", W - M, H - 26f, paint(8f, MUTE, align = Paint.Align.RIGHT)) }
    fun tableHeader() { val c = canvas!!
        c.drawRect(M, y, W - M, y + 20f, Paint().apply { color = INK })
        cols.forEach { col -> c.drawText(col.title, if (col.right) col.x + col.w - 4 else col.x + 4, y + 13.5f, paint(8.5f, Color.WHITE, true, if (col.right) Paint.Align.RIGHT else Paint.Align.LEFT)) }
        y += 20f }
    fun newPage(first: Boolean) {
        if (pageNo > 0) { footer(); finish() }
        pageNo++; canvas = start(pageNo); val c = canvas!!
        if (first) {
            c.drawRect(0f, 0f, W.toFloat(), 86f, Paint().apply { color = RED })
            c.drawText("SeloNaChipa", M, 36f, paint(20f, Color.WHITE, true))
            c.drawText("Rider earnings statement", M, 58f, paint(12f, Color.WHITE))
            c.drawText("${period.label} · ${day.format(Date(if (from == 0L) rows.firstOrNull()?.at ?: to else from))} – ${day.format(Date(to))}", W - M, 36f, paint(10f, Color.WHITE, true, Paint.Align.RIGHT))
            c.drawText("Generated ${dt.format(Date())}", W - M, 58f, paint(9f, Color.WHITE, align = Paint.Align.RIGHT))
            y = 112f
            c.drawText(riderName, M, y, paint(13f, INK, true))
            c.drawText(listOfNotNull(profile?.msisdn, profile?.vehicle?.let { v -> listOfNotNull(v, profile.plate.takeIf { it.isNotBlank() }).joinToString(" · ") }, profile?.city).joinToString("  ·  "), M, y + 16f, paint(9.5f, MUTE))
            y += 34f
            // summary boxes
            val boxes = listOf("Deliveries" to "${rows.size}", "Distance" to "%.1f km".format(Locale.UK, tKm), "Gross fees" to k(tGross), "Net earnings" to k(tNet))
            val bw = (W - 2 * M - 3 * 8f) / 4
            boxes.forEachIndexed { i, (l, v) -> val x = M + i * (bw + 8f)
                c.drawRoundRect(RectF(x, y, x + bw, y + 50f), 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (i == 3) Color.rgb(220, 252, 231) else ZEBRA })
                c.drawText(l, x + 10f, y + 18f, paint(8.5f, MUTE)); c.drawText(v, x + 10f, y + 38f, paint(13f, if (i == 3) GREEN else INK, true)) }
            y += 66f
            c.drawText("Platform fee ${k(tFee)}  ·  Social Fund saved ${k(tFund)}  ·  Hero bonus ${k(tBonus)}", M, y, paint(9.5f, INK))
            y += 18f
        } else {
            c.drawText("SeloNaChipa · Rider statement · $riderName (continued)", M, 40f, paint(10f, MUTE, true)); y = 56f
        }
        tableHeader()
    }
    newPage(true)
    if (rows.isEmpty()) canvas!!.drawText("No completed deliveries in this period.", M + 4, y + 18f, paint(10f, MUTE))
    rows.forEachIndexed { i, r ->
        if (y + rowH > H - 70f) newPage(false)
        val c = canvas!!
        if (i % 2 == 1) c.drawRect(M, y, W - M, y + rowH, Paint().apply { color = ZEBRA })
        val cells = listOf(dt.format(Date(r.at)), r.orderId, "${r.pickupArea} → ${r.dropArea}", "%.1f".format(Locale.UK, r.km),
            "%,.2f".format(Locale.UK, r.grossNgwee / 100.0), "%,.2f".format(Locale.UK, r.platformNgwee / 100.0), "%,.2f".format(Locale.UK, r.fundNgwee / 100.0),
            "%,.2f".format(Locale.UK, r.bonusNgwee / 100.0), "%,.2f".format(Locale.UK, r.netNgwee / 100.0))
        cells.forEachIndexed { j, t -> val col = cols[j]; val p = paint(8.5f, if (j == 8) GREEN else INK, j == 8, if (col.right) Paint.Align.RIGHT else Paint.Align.LEFT)
            c.drawText(fit(t, p, col.w - 6), if (col.right) col.x + col.w - 4 else col.x + 4, y + 13.5f, p) }
        y += rowH
    }
    // totals row
    if (y + 28f > H - 70f) newPage(false)
    canvas!!.drawLine(M, y, W - M, y, Paint().apply { color = INK; strokeWidth = 1.2f })
    listOf(2 to "Totals (K)", 3 to "%.1f".format(Locale.UK, tKm), 4 to "%,.2f".format(Locale.UK, tGross / 100.0), 5 to "%,.2f".format(Locale.UK, tFee / 100.0),
        6 to "%,.2f".format(Locale.UK, tFund / 100.0), 7 to "%,.2f".format(Locale.UK, tBonus / 100.0), 8 to "%,.2f".format(Locale.UK, tNet / 100.0)).forEach { (j, t) ->
        val col = cols[j]; canvas!!.drawText(t, if (col.right) col.x + col.w - 4 else col.x + 4, y + 16f, paint(9f, if (j == 8) GREEN else INK, true, if (col.right) Paint.Align.RIGHT else Paint.Align.LEFT)) }
    y += 34f
    if (y + 30f < H - 60f) {
        canvas!!.drawText("Net = gross − platform fee − Social Fund + Hero bonus (− accident cover premium, if you joined). Social Fund contributions stay yours.", M, y, paint(8f, MUTE))
    }
    footer(); finish()
}

fun shareStatement(ctx: Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "SeloNaChipa rider statement").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share statement"))
}
