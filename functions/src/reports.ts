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
export type ReportRole = "SELLER" | "RIDER" | "AGENT";
/** A titled table of role-specific detail (e.g. a rider's earnings by job type, an agent's commission by seller). */
export interface Breakdown { title: string; rows: { label: string; value: string; sub?: string }[] }
export interface Report { uid: string; role: ReportRole; month: string; monthLabel: string; metrics: Metric[]; highlights: string[]; tips: string[];
  highlightsTitle: string; tipsTitle: string; breakdowns: Breakdown[]; top?: { title: string; units: number; revenueNgwee: number }[] }
const K = (n: number) => `K ${(n / 100).toLocaleString("en-ZM", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const DAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];

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
  const breakdowns: Breakdown[] = [{ title: "Where your sales came from", rows: [
    { label: "Sales from your deals (Marketing Centre)", value: `${a.promoShare}%` }, { label: "Average order", value: K(a.aov) }, { label: "Items per order", value: a.orders ? (a.units / a.orders).toFixed(1) : "0" } ] }];
  return { metrics, highlights, tips, top, breakdowns };
}

interface RiderEntry { type: string; kind?: string; net?: number; bonus?: number; socialLevy?: number; km?: number; insurancePremium?: number; gross?: number;
  platformFee?: number; dropArea?: string; pickupArea?: string; at?: { toDate(): Date } | Date }
const when = (e: RiderEntry) => (e.at instanceof Date ? e.at : e.at?.toDate?.()) ?? null;
/** Rider report: only the rider's own delivery jobs — marketplace orders and parcels they carried. */
export function riderMetrics(cur: RiderEntry[], prev: RiderEntry[], w: { acceptanceRate?: number; completedDeliveries?: number; failedDeliveries?: number }) {
  const take = (e: RiderEntry) => (e.net ?? 0) + (e.bonus ?? 0) - (e.insurancePremium ?? 0);
  const calc = (es: RiderEntry[]) => { const d = es.filter((e) => e.type === "delivery_credit");
    return { d, deliveries: d.length, earnings: d.reduce((s, e) => s + take(e), 0), gross: d.reduce((s, e) => s + (e.gross ?? 0), 0),
      fund: d.reduce((s, e) => s + (e.socialLevy ?? 0), 0), km: Math.round(d.reduce((s, e) => s + (e.km ?? 0), 0)),
      bonus: d.reduce((s, e) => s + (e.bonus ?? 0), 0), fees: d.reduce((s, e) => s + (e.platformFee ?? 0), 0), cover: d.reduce((s, e) => s + (e.insurancePremium ?? 0), 0) }; };
  const a = calc(cur), b = calc(prev);
  const done = w.completedDeliveries ?? 0, failed = w.failedDeliveries ?? 0;
  const metrics: Metric[] = [
    { key: "earnings", label: "Net earnings from deliveries", value: a.earnings, previous: b.earnings, unit: "ngwee" },
    { key: "deliveries", label: "Deliveries completed", value: a.deliveries, previous: b.deliveries, unit: "count" },
    { key: "km", label: "Distance ridden", value: a.km, previous: b.km, unit: "km" },
    { key: "perDelivery", label: "Average per delivery", value: a.deliveries ? Math.round(a.earnings / a.deliveries) : 0, previous: b.deliveries ? Math.round(b.earnings / b.deliveries) : 0, unit: "ngwee" },
    { key: "fund", label: "Saved in your Social Fund", value: a.fund, previous: b.fund, unit: "ngwee" },
    { key: "acceptance", label: "Acceptance rate", value: Math.round(w.acceptanceRate ?? 100), previous: Math.round(w.acceptanceRate ?? 100), unit: "pct" },
    { key: "completion", label: "Completion rate (all time)", value: pct(done, done + failed) || 100, previous: pct(done, done + failed) || 100, unit: "pct" },
  ];
  // by job type
  const byKind = (k: string) => a.d.filter((e) => (e.kind ?? "order") === k);
  const orders = byKind("order"), parcels = byKind("parcel");
  const sum = (es: RiderEntry[]) => es.reduce((s, e) => s + take(e), 0);
  // busiest day and areas
  const dayCount = new Map<number, { n: number; v: number }>();
  a.d.forEach((e) => { const t = when(e); if (!t) return; const day = new Date(t.getTime() + 2 * 3_600_000).getUTCDay(); const x = dayCount.get(day) ?? { n: 0, v: 0 }; x.n++; x.v += take(e); dayCount.set(day, x); });
  const bestDay = [...dayCount].sort((x, y) => y[1].v - x[1].v)[0];
  const areas = new Map<string, number>(); a.d.forEach((e) => { const ar = (e.dropArea || e.pickupArea || "").trim(); if (ar) areas.set(ar, (areas.get(ar) ?? 0) + 1); });
  const topAreas = [...areas].sort((x, y) => y[1] - x[1]).slice(0, 5);
  const breakdowns: Breakdown[] = [
    { title: "Earnings by job type", rows: [
      { label: "Marketplace order deliveries", value: K(sum(orders)), sub: `${orders.length} jobs` },
      { label: "Parcel deliveries", value: K(sum(parcels)), sub: `${parcels.length} jobs` } ] },
    { title: "Where your money went", rows: [
      { label: "Delivery fees earned (gross)", value: K(a.gross) }, { label: "Platform fee", value: `− ${K(a.fees)}` },
      { label: "Social Fund (yours, saved)", value: `− ${K(a.fund)}` }, { label: "Hero bonus", value: `+ ${K(a.bonus)}` },
      ...(a.cover ? [{ label: "Accident cover premiums", value: `− ${K(a.cover)}` }] : []), { label: "Net earnings", value: K(a.earnings) } ] },
    ...(topAreas.length ? [{ title: "Your busiest areas", rows: topAreas.map(([ar, n]) => ({ label: ar, value: `${n} drop-offs` })) }] : []),
  ];
  const highlights: string[] = [], tips: string[] = [];
  if (b.deliveries > 0) highlights.push(a.deliveries >= b.deliveries ? `${a.deliveries - b.deliveries} more deliveries than last month.` : `${b.deliveries - a.deliveries} fewer deliveries than last month.`);
  if (a.earnings > 0 && b.earnings > 0) highlights.push(a.earnings >= b.earnings ? `Earnings up ${pct(a.earnings - b.earnings, b.earnings)}%.` : `Earnings down ${pct(b.earnings - a.earnings, b.earnings)}%.`);
  if (bestDay) highlights.push(`Your best day was ${DAYS[bestDay[0]]}s (${K(bestDay[1].v)} from ${bestDay[1].n} jobs).`);
  if (parcels.length && orders.length) highlights.push(`${pct(parcels.length, a.deliveries)}% of your jobs were parcels.`);
  if (a.fund > 0) highlights.push(`${K(a.fund)} went into your Social Fund.`);
  if (a.bonus > 0) highlights.push(`Hero bonus paid: ${K(a.bonus)}.`);
  if ((w.acceptanceRate ?? 100) < 80) tips.push("Your acceptance rate is under 80%. Smart dispatch offers jobs first to riders who accept and complete reliably.");
  if (a.deliveries < 20) tips.push("Stay online at lunch (12:00–14:00) and evening rush (17:00–19:00) near markets to get more top-ranked offers.");
  if (bestDay) tips.push(`Plan to be online on ${DAYS[bestDay[0]]}s — that's when you earned most.`);
  if (topAreas[0]) tips.push(`Most of your drop-offs were in ${topAreas[0][0]}. Add it (or the whole city) in Zone & availability to get more jobs there.`);
  if (!parcels.length) tips.push("You did no parcel jobs. Parcels pay per km and often batch with orders on the same route.");
  tips.push("Take a photo at every drop-off (Safety Centre → Delivery evidence): it protects your earnings in disputes.");
  if (!highlights.length) highlights.push("No completed deliveries this month yet.");
  return { metrics, highlights, tips, breakdowns };
}

interface AgentOrder { state: string; goodsNgwee: number; sellerUid: string; sellerName?: string }
/** Agent report: commissions from sales they made for their sellers, sellers onboarded and listings created. */
export function agentMetrics(cur: AgentOrder[], prev: AgentOrder[], rateBySeller: Map<string, number>, cutPct: number, onboarded: { cur: number; prev: number },
  listings: { cur: number; prev: number }) {
  const net = (o: AgentOrder) => { const g = Math.round((o.goodsNgwee * (rateBySeller.get(o.sellerUid) ?? 10)) / 100); return g - Math.round((g * cutPct) / 100); };
  const calc = (os: AgentOrder[]) => { const p = os.filter((o) => PAID.has(o.state));
    return { p, sales: p.reduce((s, o) => s + o.goodsNgwee, 0), commission: p.reduce((s, o) => s + net(o), 0), sellers: new Set(p.map((o) => o.sellerUid)).size }; };
  const a = calc(cur), b = calc(prev);
  const metrics: Metric[] = [
    { key: "commission", label: "Net commission earned", value: a.commission, previous: b.commission, unit: "ngwee" },
    { key: "sales", label: "Sales you made for sellers", value: a.sales, previous: b.sales, unit: "ngwee" },
    { key: "orders", label: "Paid orders", value: a.p.length, previous: b.p.length, unit: "count" },
    { key: "activeSellers", label: "Sellers with sales", value: a.sellers, previous: b.sellers, unit: "count" },
    { key: "onboarded", label: "New sellers onboarded", value: onboarded.cur, previous: onboarded.prev, unit: "count" },
    { key: "listings", label: "Listings you created", value: listings.cur, previous: listings.prev, unit: "count" },
  ];
  const bySeller = new Map<string, { uid: string; name: string; sales: number; commission: number; n: number }>();
  a.p.forEach((o) => { const x = bySeller.get(o.sellerUid) ?? { uid: o.sellerUid, name: o.sellerName || "Seller", sales: 0, commission: 0, n: 0 }; x.sales += o.goodsNgwee; x.commission += net(o); x.n++; bySeller.set(o.sellerUid, x); });
  const ranked = [...bySeller.values()].sort((x, y) => y.commission - x.commission);
  const breakdowns: Breakdown[] = ranked.length ? [{ title: "Commission by seller", rows: ranked.slice(0, 8).map((x) =>
    ({ label: x.name, value: K(x.commission), sub: `${x.n} orders · ${K(x.sales)} sales · ${rateBySeller.get(x.uid) ?? 10}% rate` })) }] : [];
  const highlights: string[] = [], tips: string[] = [];
  if (b.commission > 0) highlights.push(a.commission >= b.commission ? `Commission up ${pct(a.commission - b.commission, b.commission)}% on last month.` : `Commission down ${pct(b.commission - a.commission, b.commission)}% on last month.`);
  if (ranked[0]) highlights.push(`Top seller for you: ${ranked[0].name} (${K(ranked[0].commission)} commission).`);
  if (onboarded.cur) highlights.push(`You brought ${onboarded.cur} new seller${onboarded.cur === 1 ? "" : "s"} onto SeloNaChipa.`);
  if (!highlights.length) highlights.push("No commissioned sales this month yet.");
  if (onboarded.cur === 0) tips.push("Onboard at least two new sellers at your hub this month — each one adds commission from their first sale.");
  if (listings.cur < 10) tips.push("Sellers with 10+ video listings sell more. Help your sellers post short videos with the Listing Studio.");
  if (ranked.length > 0 && ranked.length < 3) tips.push("Most of your commission comes from one or two sellers. Spread your effort to reduce risk.");
  tips.push("Share your referral link on WhatsApp Status every week — new sellers signed up through it are linked to you.");
  return { metrics, highlights, tips, breakdowns };
}

const TITLES: Record<ReportRole, { highlightsTitle: string; tipsTitle: string }> = {
  SELLER: { highlightsTitle: "Your shop's highlights", tipsTitle: "Grow your sales next month" },
  RIDER: { highlightsTitle: "Your riding highlights", tipsTitle: "Earn more next month" },
  AGENT: { highlightsTitle: "Your agency highlights", tipsTitle: "Grow your commissions next month" },
};

export async function buildReport(uid: string, role: ReportRole, month: string): Promise<Report> {
  const [s, e] = monthRange(month), [ps, pe] = monthRange(prevMonth(month));
  const range = (a: Date, b: Date) => [Timestamp.fromDate(a), Timestamp.fromDate(b)] as const;
  const base = { uid, role, month, monthLabel: monthLabel(month), ...TITLES[role] };
  if (role === "SELLER") {
    const get = (a: Date, b: Date) => db.collection("orders").where("sellerUid", "==", uid).where("createdAt", ">=", range(a, b)[0]).where("createdAt", "<", range(a, b)[1])
      .limit(2000).get().then((q) => q.docs.map((d) => d.data() as SellerOrder));
    const [cur, prev, cfg] = await Promise.all([get(s, e), get(ps, pe), loadConfig()]);
    return { ...base, ...sellerMetrics(cur, prev, Math.round(Number(cfg.flatPlatformFeeKwacha ?? 0) * 100)) };
  }
  if (role === "AGENT") {
    const orders = (a: Date, b: Date) => db.collection("orders").where("agentUid", "==", uid).where("createdAt", ">=", range(a, b)[0]).where("createdAt", "<", range(a, b)[1])
      .limit(2000).get().then((q) => q.docs.map((d) => d.data() as AgentOrder));
    const count = (coll: string, field: string, a: Date, b: Date) => db.collection(coll).where("agentUid", "==", uid).where(field, ">=", range(a, b)[0]).where(field, "<", range(a, b)[1])
      .count().get().then((c) => c.data().count).catch(() => 0);
    const [cur, prev, links, cfg, onC, onP, lsC, lsP] = await Promise.all([orders(s, e), orders(ps, pe), db.collection("agentLinks").where("agentUid", "==", uid).limit(500).get(),
      loadConfig(), count("agentLinks", "createdAt", s, e), count("agentLinks", "createdAt", ps, pe), count("listings", "createdAt", s, e), count("listings", "createdAt", ps, pe)]);
    const rates = new Map(links.docs.map((l) => [l.get("sellerUid") as string, Number(l.get("commissionPct") ?? 10)]));
    return { ...base, ...agentMetrics(cur, prev, rates, Number(cfg.agentPlatformCutPct ?? 0), { cur: onC, prev: onP }, { cur: lsC, prev: lsP }) };
  }
  const get = (a: Date, b: Date) => db.collection(`ledgers/${uid}/entries`).where("at", ">=", range(a, b)[0]).where("at", "<", range(a, b)[1]).limit(3000).get()
    .then((q) => q.docs.map((d) => d.data() as RiderEntry));
  const [cur, prev, w] = await Promise.all([get(s, e), get(ps, pe), db.doc(`riderWallets/${uid}`).get()]);
  return { ...base, ...riderMetrics(cur, prev, w.data() ?? {}) };
}

export const performance_report = onCall(async (req) => {
  const { uid, role } = requireUser(req, "SELLER", "RIDER", "AGENT");
  const now = monthOf(new Date());
  const month = /^\d{4}-\d{2}$/.test(String(req.data?.month ?? "")) ? String(req.data.month) : now;
  if (month > now) throw new HttpsError("invalid-argument", "That month hasn't started yet.");
  if (month < now) { const saved = await db.doc(`performanceReports/${uid}_${month}`).get(); if (saved.exists) return saved.data(); }
  return buildReport(uid, role as ReportRole, month);
});

/** 1st of the month, 06:10 Lusaka: save last month's report for every active seller and rider and let them know. */
export const scheduled_monthlyReports = onSchedule({ schedule: "10 6 1 * *", timeZone: "Africa/Lusaka", timeoutSeconds: 540 }, async () => {
  const month = prevMonth(monthOf(new Date()));
  for (const role of ["SELLER", "RIDER", "AGENT"] as const) {
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
