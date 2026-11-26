/**
 * Monthly performance reports for sellers and riders. The numbers are worked out from orders (sellers) and the
 * rider's delivery ledger (riders), compared with the month before, with plain-language highlights and tips.
 * On the 1st of each month the previous month's report is saved for every active seller and rider and they get a
 * notification; the app can also open the current month to date at any time.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { db, loadConfig } from "./config";
import { notify, requireUser } from "./util";

export interface Metric { key: string; label: string; value: number; previous: number; unit: "ngwee" | "count" | "pct" | "km" | "min" }
export interface Report { uid: string; role: "SELLER" | "RIDER"; month: string; monthLabel: string; metrics: Metric[]; highlights: string[]; tips: string[];
  top?: { title: string; units: number; revenueNgwee: number }[] }

/** "2026-11" → [start, end) in Lusaka time (UTC+2, no DST). */
export function monthRange(month: string): [Date, Date] {
  const [y, m] = month.split("-").map(Number);
  return [new Date(Date.UTC(y, m - 1, 1, -2)), new Date(Date.UTC(y, m, 1, -2))];
}
export const prevMonth = (month: string) => { const [y, m] = month.split("-").map(Number); return m === 1 ? `${y - 1}-12` : `${y}-${String(m - 1).padStart(2, "0")}`; };
export const monthOf = (d: Date) => { const l = new Date(d.getTime() + 2 * 3_600_000); return `${l.getUTCFullYear()}-${String(l.getUTCMonth() + 1).padStart(2, "0")}`; };
export const monthLabel = (month: string) => monthRange(month)[0].toLocaleString("en-GB", { month: "long", year: "numeric", timeZone: "Africa/Lusaka" });

const PAID = new Set(["ESCROW_LOCKED", "BROADCASTING", "RIDER_ASSIGNED", "PICKED_UP", "IN_TRANSIT", "AWAITING_DELIVERY_CODE", "DELIVERED", "COMPLETED"]);
const pct = (a: number, b: number) => (b > 0 ? Math.round((a * 100) / b) : 0);

interface SellerOrder { state: string; goodsNgwee: number; sellerNetNgwee?: number; items?: { title: string; qty: number; lineNgwee?: number; unitNgwee: number; promo?: string | null }[]; feeFlatNgwee?: number }
export function sellerMetrics(cur: SellerOrder[], prev: SellerOrder[], flatFeeNgwee: number) {
  const calc = (os: SellerOrder[]) => {
    const paid = os.filter((o) => PAID.has(o.state)), refunded = os.filter((o) => o.state === "REFUNDED" || o.state === "CANCELLED");
    const sales = paid.reduce((s, o) => s + (o.goodsNgwee ?? 0), 0);
    const units = paid.reduce((s, o) => s + (o.items ?? []).reduce((a, i) => a + i.qty, 0), 0);
    const promoSales = paid.reduce((s, o) => s + (o.items ?? []).filter((i) => i.promo).reduce((a, i) => a + (i.lineNgwee ?? i.qty * i.unitNgwee), 0), 0);
    return { orders: paid.length, sales, net: paid.reduce((t, o) => t + (o.goodsNgwee ?? 0) - Math.min(flatFeeNgwee, o.goodsNgwee ?? 0), 0), units, aov: paid.length ? Math.round(sales / paid.length) : 0,
      cancelRate: pct(refunded.length, paid.length + refunded.length), promoShare: pct(promoSales, sales) };
  };
  const a = calc(cur), b = calc(prev);
  const metrics: Metric[] = [
    { key: "sales", label: "Sales", value: a.sales, previous: b.sales, unit: "ngwee" },
    { key: "net", label: "Your earnings (after platform fee)", value: a.net, previous: b.net, unit: "ngwee" },
    { key: "orders", label: "Paid orders", value: a.orders, previous: b.orders, unit: "count" },
    { key: "units", label: "Items sold", value: a.units, previous: b.units, unit: "count" },
    { key: "aov", label: "Average order", value: a.aov, previous: b.aov, unit: "ngwee" },
    { key: "cancelRate", label: "Cancelled or refunded", value: a.cancelRate, previous: b.cancelRate, unit: "pct" },
    { key: "promoShare", label: "Sales from your deals", value: a.promoShare, previous: b.promoShare, unit: "pct" },
  ];
  const byTitle = new Map<string, { units: number; revenueNgwee: number }>();
  cur.filter((o) => PAID.has(o.state)).forEach((o) => (o.items ?? []).forEach((i) => { const t = byTitle.get(i.title) ?? { units: 0, revenueNgwee: 0 };
    t.units += i.qty; t.revenueNgwee += i.lineNgwee ?? i.qty * i.unitNgwee; byTitle.set(i.title, t); }));
  const top = [...byTitle].map(([title, v]) => ({ title, ...v })).sort((x, y) => y.revenueNgwee - x.revenueNgwee).slice(0, 5);
  const highlights: string[] = [], tips: string[] = [];
  if (b.sales > 0) highlights.push(a.sales >= b.sales ? `Sales up ${pct(a.sales - b.sales, b.sales)}% on last month.` : `Sales down ${pct(b.sales - a.sales, b.sales)}% on last month.`);
  if (top[0]) highlights.push(`Best seller: ${top[0].title} (${top[0].units} sold).`);
  if (a.orders === 0) tips.push("No paid orders this month. Post a new video listing and share it on WhatsApp Status with the AI Ad Generator.");
  if (a.promoShare < 10 && a.orders > 0) tips.push("Try a Marketing Centre deal — a weekend limited-time offer or Buy 1 Get 1 Free on a slow product.");
  if (a.cancelRate > 10) tips.push("Over 10% of orders were cancelled or refunded. Keep stock levels up to date and pack orders promptly.");
  if (a.aov > 0 && a.aov < 10_000) tips.push("Small average orders — a product bundle can raise what each buyer spends.");
  if (!tips.length) tips.push("Keep it up: reply to enquiries fast and keep your best seller in stock.");
  return { metrics, highlights, tips, top };
}

interface RiderEntry { type: string; net?: number; bonus?: number; socialLevy?: number; km?: number; insurancePremium?: number }
export function riderMetrics(cur: RiderEntry[], prev: RiderEntry[], w: { acceptanceRate?: number; completedDeliveries?: number; failedDeliveries?: number }) {
  const calc = (es: RiderEntry[]) => { const d = es.filter((e) => e.type === "delivery_credit");
    return { deliveries: d.length, earnings: d.reduce((s, e) => s + (e.net ?? 0) + (e.bonus ?? 0) - (e.insurancePremium ?? 0), 0),
      fund: d.reduce((s, e) => s + (e.socialLevy ?? 0), 0), km: Math.round(d.reduce((s, e) => s + (e.km ?? 0), 0)) }; };
  const a = calc(cur), b = calc(prev);
  const done = w.completedDeliveries ?? 0, failed = w.failedDeliveries ?? 0;
  const metrics: Metric[] = [
    { key: "earnings", label: "Net earnings", value: a.earnings, previous: b.earnings, unit: "ngwee" },
    { key: "deliveries", label: "Deliveries completed", value: a.deliveries, previous: b.deliveries, unit: "count" },
    { key: "km", label: "Distance ridden", value: a.km, previous: b.km, unit: "km" },
    { key: "perDelivery", label: "Average per delivery", value: a.deliveries ? Math.round(a.earnings / a.deliveries) : 0, previous: b.deliveries ? Math.round(b.earnings / b.deliveries) : 0, unit: "ngwee" },
    { key: "fund", label: "Saved in your Social Fund", value: a.fund, previous: b.fund, unit: "ngwee" },
    { key: "acceptance", label: "Acceptance rate", value: Math.round(w.acceptanceRate ?? 100), previous: Math.round(w.acceptanceRate ?? 100), unit: "pct" },
    { key: "completion", label: "Completion rate (all time)", value: pct(done, done + failed) || 100, previous: pct(done, done + failed) || 100, unit: "pct" },
  ];
  const highlights: string[] = [], tips: string[] = [];
  if (b.deliveries > 0) highlights.push(a.deliveries >= b.deliveries ? `${a.deliveries - b.deliveries} more deliveries than last month.` : `${b.deliveries - a.deliveries} fewer deliveries than last month.`);
  if (a.fund > 0) highlights.push(`K ${(a.fund / 100).toFixed(2)} went into your Social Fund.`);
  if ((w.acceptanceRate ?? 100) < 80) tips.push("Your acceptance rate is under 80%. Smart dispatch offers jobs first to riders who accept and complete reliably.");
  if (a.deliveries < 20) tips.push("Stay online at busy times (12:00–14:00 and 17:00–19:00) near markets to get more top-ranked offers.");
  tips.push("Ride safe: keep your emergency contact up to date in the Safety Centre.");
  return { metrics, highlights, tips };
}

export async function buildReport(uid: string, role: "SELLER" | "RIDER", month: string): Promise<Report> {
  const [s, e] = monthRange(month), [ps, pe] = monthRange(prevMonth(month));
  if (role === "SELLER") {
    const get = (a: Date, b: Date) => db.collection("orders").where("sellerUid", "==", uid).where("createdAt", ">=", Timestamp.fromDate(a)).where("createdAt", "<", Timestamp.fromDate(b))
      .limit(2000).get().then((q) => q.docs.map((d) => d.data() as SellerOrder));
    const [cur, prev, cfg] = await Promise.all([get(s, e), get(ps, pe), loadConfig()]);
    const r = sellerMetrics(cur, prev, Math.round(Number(cfg.flatPlatformFeeKwacha ?? 0) * 100));
    return { uid, role, month, monthLabel: monthLabel(month), ...r };
  }
  const get = (a: Date, b: Date) => db.collection(`ledgers/${uid}/entries`).where("at", ">=", Timestamp.fromDate(a)).where("at", "<", Timestamp.fromDate(b)).limit(3000).get()
    .then((q) => q.docs.map((d) => d.data() as RiderEntry));
  const [cur, prev, w] = await Promise.all([get(s, e), get(ps, pe), db.doc(`riderWallets/${uid}`).get()]);
  return { uid, role, month, monthLabel: monthLabel(month), ...riderMetrics(cur, prev, w.data() ?? {}) };
}

export const performance_report = onCall(async (req) => {
  const { uid, role } = requireUser(req, "SELLER", "RIDER");
  const now = monthOf(new Date());
  const month = /^\d{4}-\d{2}$/.test(String(req.data?.month ?? "")) ? String(req.data.month) : now;
  if (month > now) throw new HttpsError("invalid-argument", "That month hasn't started yet.");
  if (month < now) { const saved = await db.doc(`performanceReports/${uid}_${month}`).get(); if (saved.exists) return saved.data(); }
  return buildReport(uid, role as "SELLER" | "RIDER", month);
});

/** 1st of the month, 06:10 Lusaka: save last month's report for every active seller and rider and let them know. */
export const scheduled_monthlyReports = onSchedule({ schedule: "10 6 1 * *", timeZone: "Africa/Lusaka", timeoutSeconds: 540 }, async () => {
  const month = prevMonth(monthOf(new Date()));
  for (const role of ["SELLER", "RIDER"] as const) {
    const users = await db.collection("users").where("role", "==", role).where("accountStatus", "==", "ACTIVE").limit(5000).get();
    for (const u of users.docs) {
      const r = await buildReport(u.id, role, month).catch(() => null);
      if (!r) continue;
      await db.doc(`performanceReports/${u.id}_${month}`).set({ ...r, createdAt: FieldValue.serverTimestamp() });
      const lead = r.metrics[0];
      await notify(u.id, `Your ${r.monthLabel} report`, `${lead.label}: K ${(lead.value / 100).toFixed(2)}. Tap to see what worked and tips for this month.`, "MONTHLY_REPORT",
        `selo://report/${month}`).catch(() => undefined);
    }
  }
});
