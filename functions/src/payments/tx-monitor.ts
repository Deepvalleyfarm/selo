/**
 * Super Admin Transaction Monitor (back office → Payments → Transactions). Reads lipila_webhook_events.
 * Every callable: Super Admin check (server-side, not just hidden in the UI) → 100 requests/minute per admin →
 * view audit entry (admin, action, filters) → query. Filtering is server-side; rawPayload only in the detail call.
 */
import { FieldValue, Timestamp, type Query } from "firebase-admin/firestore";
import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { db } from "../config";
import { buildTransactionsCsv } from "./tx-export";

const COLL = "lipila_webhook_events";
const LIST_FIELDS = ["webhookId", "referenceId", "identifier", "currency", "amount", "accountNumber", "status", "paymentType", "type", "message", "externalId", "referenceData", "processedAt", "source", "processed"];

async function superAdmin(req: CallableRequest, action: string, filters: Record<string, unknown>) {
  const t = req.auth?.token;
  if (!req.auth || t?.admin !== "super_admin") {
    await db.collection("adminAccessDenied").add({ uid: req.auth?.uid ?? null, email: t?.email ?? null, path: `tx-monitor/${action}`, reason: "not super_admin", at: FieldValue.serverTimestamp() }).catch(() => undefined);
    throw new HttpsError("permission-denied", "The transaction monitor is for Super Admins only.");
  }
  const ref = db.doc(`adminRate/${req.auth.uid}_${Math.floor(Date.now() / 60_000)}`);
  const n = await db.runTransaction(async (tx) => { const c = ((await tx.get(ref)).get("n") as number | undefined) ?? 0;
    tx.set(ref, { n: c + 1, expiresAt: Timestamp.fromMillis(Date.now() + 120_000) }); return c + 1; });
  if (n > 100) throw new HttpsError("resource-exhausted", "Too many requests — slow down (100 per minute).");
  await db.collection("adminViewLog").add({ adminUid: req.auth.uid, email: t?.email ?? null, action, filters, at: FieldValue.serverTimestamp() }).catch(() => undefined);
  return req.auth.uid;
}

export interface TxParams { q?: string; status?: string; paymentType?: string; type?: string; dateFrom?: string; dateTo?: string; pageSize?: number; cursor?: string; sortDir?: "asc" | "desc" }

function clean(p: any): TxParams {
  const s = (v: unknown, n = 80) => (typeof v === "string" && v.trim() ? v.trim().slice(0, n) : undefined);
  return { q: s(p?.q, 120), status: s(p?.status), paymentType: s(p?.paymentType), type: s(p?.type), dateFrom: s(p?.dateFrom, 10), dateTo: s(p?.dateTo, 10),
    pageSize: Math.min(100, Math.max(1, Number(p?.pageSize) || 50)), cursor: s(p?.cursor, 200), sortDir: p?.sortDir === "asc" ? "asc" : "desc" };
}
const startOf = (d?: string) => (d && /^\d{4}-\d{2}-\d{2}$/.test(d) ? Timestamp.fromDate(new Date(`${d}T00:00:00Z`)) : null);
const endOf = (d?: string) => (d && /^\d{4}-\d{2}-\d{2}$/.test(d) ? Timestamp.fromDate(new Date(`${d}T23:59:59.999Z`)) : null);

function baseQuery(p: TxParams): Query {
  let q: Query = db.collection(COLL);
  if (p.status) q = q.where("status", "==", p.status);
  if (p.paymentType) q = q.where("paymentType", "==", p.paymentType);
  if (p.type) q = q.where("type", "==", p.type);
  const from = startOf(p.dateFrom), to = endOf(p.dateTo);
  if (from) q = q.where("processedAt", ">=", from);
  if (to) q = q.where("processedAt", "<=", to);
  return q.orderBy("processedAt", p.sortDir ?? "desc");
}
const row = (d: FirebaseFirestore.DocumentSnapshot) => {
  const x = d.data() ?? {};
  return { id: d.id, ...Object.fromEntries(LIST_FIELDS.map((k) => [k, x[k] ?? null])), processedAt: (x.processedAt as Timestamp | undefined)?.toDate().toISOString() ?? null };
};
const matchesFilters = (r: Record<string, any>, p: TxParams) => (!p.status || r.status === p.status) && (!p.paymentType || r.paymentType === p.paymentType) && (!p.type || r.type === p.type)
  && (!p.dateFrom || (r.processedAt ?? "") >= `${p.dateFrom}T00:00:00`) && (!p.dateTo || (r.processedAt ?? "") <= `${p.dateTo}T23:59:59.999Z`);

/** Free-text search: exact matches on the id/number fields (indexed), then a bounded scan for message/narration text. */
async function search(p: TxParams) {
  const q = p.q!;
  const digits = q.replace(/\D/g, "");
  const msisdn = digits.length >= 9 ? (digits.startsWith("260") ? digits : `260${digits.replace(/^0/, "")}`) : null;
  const exact = await Promise.all([["referenceId", q], ["identifier", q], ["externalId", q], ["webhookId", q], ...(msisdn ? [["accountNumber", msisdn]] : [])]
    .map(([f, v]) => db.collection(COLL).where(f, "==", v).limit(100).get()));
  const seen = new Map<string, ReturnType<typeof row>>();
  exact.forEach((s) => s.docs.forEach((d) => seen.set(d.id, row(d))));
  if (!seen.size) {
    const needle = q.toLowerCase();
    const recent = await baseQuery({ ...p, q: undefined }).limit(1000).get();
    recent.docs.map(row).filter((r: any) => `${r.message ?? ""} ${r.referenceData ?? ""} ${r.identifier ?? ""} ${r.referenceId ?? ""}`.toLowerCase().includes(needle)).forEach((r) => seen.set(r.id, r));
  }
  const rows = [...seen.values()].filter((r) => matchesFilters(r, p)).sort((a: any, b: any) => (p.sortDir === "asc" ? 1 : -1) * String(a.processedAt).localeCompare(String(b.processedAt)));
  return { data: rows.slice(0, 200), total: rows.length, nextCursor: null as string | null };
}

export async function getTransactions(p: TxParams) {
  if (p.q) return search(p);
  let q = baseQuery(p);
  if (p.cursor) { const c = await db.doc(`${COLL}/${p.cursor}`).get(); if (c.exists) q = q.startAfter(c); }
  const [page, count] = await Promise.all([q.limit(p.pageSize ?? 50).get(), baseQuery(p).count().get()]);
  const data = page.docs.map(row);
  return { data, total: count.data().count, nextCursor: page.size === (p.pageSize ?? 50) ? page.docs[page.size - 1].id : null };
}

const statsCache = new Map<string, { at: number; v: unknown }>();
export async function getTransactionStats(dateFrom?: string, dateTo?: string) {
  const to = dateTo && /^\d{4}-\d{2}-\d{2}$/.test(dateTo) ? dateTo : new Date().toISOString().slice(0, 10);
  const from = dateFrom && /^\d{4}-\d{2}-\d{2}$/.test(dateFrom) ? dateFrom : to;
  const key = `${from}|${to}`;
  const hit = statsCache.get(key); if (hit && Date.now() - hit.at < 60_000) return hit.v;
  const days: string[] = [];
  for (let d = new Date(`${from}T00:00:00Z`); d <= new Date(`${to}T00:00:00Z`) && days.length < 366; d = new Date(d.getTime() + 86_400_000)) days.push(d.toISOString().slice(0, 10));
  const t: Record<string, number> = {};
  const snaps = await Promise.all(days.map((d) => db.collection(`lipila_tx_stats/${d}/shards`).get()));
  snaps.forEach((s) => s.docs.forEach((doc) => Object.entries(doc.data()).forEach(([k, v]) => { if (typeof v === "number") t[k] = (t[k] ?? 0) + v; })));
  const pick = (prefix: string) => Object.entries(t).filter(([k]) => k.startsWith(prefix)).map(([k, count]) => ({ key: k.slice(prefix.length).replace(/_/g, " "), count }));
  const byStatus = pick("status_").map(({ key: status, count }) => ({ status, count })).sort((a, b) => b.count - a.count);
  const statusCount = (re: RegExp) => byStatus.filter((s) => re.test(s.status)).reduce((s, x) => s + x.count, 0);
  const v = {
    totalCount: t.total ?? 0, successCount: statusCount(/^successful$/i), pendingCount: statusCount(/^pending$/i),
    failedCount: statusCount(/^(failed|wrong pin|wrong number|insufficient funds|transaction limit exceeded|cancelled|timeout|network error|system error)$/i),
    totalVolumeNgwee: t.volumeNgwee ?? 0, byStatus,
    byPaymentType: pick("pt_").map(({ key: paymentType, count }) => ({ paymentType, count, volumeNgwee: t[`ptvol_${paymentType.replace(/ /g, "_")}`] ?? 0 })).sort((a, b) => b.count - a.count),
    from, to,
  };
  statsCache.set(key, { at: Date.now(), v });
  return v;
}

export const admin_txSearch = onCall(async (req) => { const p = clean(req.data); await superAdmin(req, "search", { ...p }); return getTransactions(p); });

export const admin_txGet = onCall(async (req) => {
  const id = String(req.data?.id ?? "").slice(0, 200);
  await superAdmin(req, "view", { id });
  const d = await db.doc(`${COLL}/${id}`).get();
  if (!d.exists) throw new HttpsError("not-found", "Transaction not found.");
  const x = d.data()!;
  return { ...row(d), ipAddress: x.ipAddress ?? null, merchantId: x.merchantId ?? null, tenantId: x.tenantId ?? null, processingError: x.processingError ?? null,
    rawPayload: x.rawPayload ?? null };
});

export const admin_txStats = onCall(async (req) => {
  await superAdmin(req, "stats", { dateFrom: req.data?.dateFrom ?? null, dateTo: req.data?.dateTo ?? null });
  return getTransactionStats(req.data?.dateFrom, req.data?.dateTo);
});

export const admin_txExport = onCall({ timeoutSeconds: 120 }, async (req) => {
  const p = clean(req.data); await superAdmin(req, "export", { ...p });
  const all: any[] = [];
  if (p.q) all.push(...(await search(p)).data);
  else {
    let cursor: string | undefined;
    for (let i = 0; i < 50 && all.length < 10_000; i++) {
      const r = await getTransactions({ ...p, pageSize: 100, cursor });
      all.push(...r.data); if (!r.nextCursor) break; cursor = r.nextCursor;
    }
  }
  return { filename: `selonachipa-transactions-${new Date().toISOString().slice(0, 10)}.csv`, csv: buildTransactionsCsv(all), rows: all.length };
});
