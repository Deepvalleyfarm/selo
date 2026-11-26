/**
 * Metering, caps and kill switches for Google Maps calls.
 *
 * Every outgoing call and every cache hit is counted per SKU per UTC day in sharded counters
 * (mapsUsage/{yyyy-mm-dd}/shards/{0..N}) so a million users never hot-spot one document. Each function instance batches
 * its counts in memory and flushes at most every 10 s. Cap checks read the day's total at most once a minute per
 * instance, so caps are approximate (they may overshoot by roughly one minute of traffic) but cost nothing per call.
 */
import { FieldValue } from "firebase-admin/firestore";
import { logger } from "firebase-functions/v2";
import { db } from "../config";
import { CAP_KEY, ENABLED_KEY, type MapsSku } from "./approved";

const SHARDS = 10;
export const day = (t = Date.now()) => new Date(t).toISOString().slice(0, 10);

type Counts = Record<string, number>;
let pending: Counts = {}; let pendingDay = day(); let lastFlush = Date.now();
const totals = new Map<string, { at: number; v: Counts }>();
const alerted = new Set<string>();

function bump(k: string, n = 1) { pending[k] = (pending[k] ?? 0) + n; }

export function record(sku: MapsSku, e: { cacheHit?: boolean; ok?: boolean; latencyMs?: number; fallback?: boolean; capped?: boolean }) {
  if (pendingDay !== day()) { void flush(true); }
  if (e.cacheHit) bump(`${sku}.cacheHits`);
  else if (e.capped) bump(`${sku}.capped`);
  else if (e.fallback) bump(`${sku}.fallbacks`);
  else { bump(`${sku}.calls`); if (e.ok === false) bump(`${sku}.errors`); if (e.latencyMs != null) bump(`${sku}.latencyMsTotal`, Math.round(e.latencyMs)); }
  if (!e.cacheHit) logger.info("maps_call", { sku, ...e });
}

/** Writes batched counters. Called after each request (cheap no-op unless 10 s passed or forced). */
export async function flush(force = false) {
  if (!force && Date.now() - lastFlush < 10_000) return;
  const batch = pending, d = pendingDay;
  pending = {}; pendingDay = day(); lastFlush = Date.now();
  if (!Object.keys(batch).length) return;
  const upd: Record<string, unknown> = { updatedAt: FieldValue.serverTimestamp() };
  for (const [k, v] of Object.entries(batch)) upd[k.replace(".", "_")] = FieldValue.increment(v);
  await db.doc(`mapsUsage/${d}/shards/${Math.floor(Math.random() * SHARDS)}`).set(upd, { merge: true })
    .catch((e) => logger.warn("maps usage flush failed", String(e)));
  await db.doc(`mapsUsage/${d}`).set({ day: d, touchedAt: FieldValue.serverTimestamp() }, { merge: true }).catch(() => undefined);
}

/** Today's totals across shards (cached for 60 s per instance). */
export async function todayTotals(d = day()): Promise<Counts> {
  const c = totals.get(d);
  if (c && Date.now() - c.at < 60_000) return c.v;
  const snap = await db.collection(`mapsUsage/${d}/shards`).get().catch(() => null);
  const v: Counts = {};
  snap?.docs.forEach((s) => Object.entries(s.data()).forEach(([k, n]) => { if (typeof n === "number") v[k] = (v[k] ?? 0) + n; }));
  // include what this instance hasn't flushed yet
  Object.entries(pending).forEach(([k, n]) => { const kk = k.replace(".", "_"); v[kk] = (v[kk] ?? 0) + n; });
  totals.set(d, { at: Date.now(), v });
  return v;
}

/** False when the SKU is switched off or today's cap is reached; raises one ops alert per SKU per day at 80%. */
export async function allowed(sku: MapsSku, cfg: Record<string, unknown>): Promise<boolean> {
  if (cfg[ENABLED_KEY[sku]] === false) return false;
  const cap = Number(cfg[CAP_KEY[sku]] ?? 0);
  if (!cap) return true;
  const used = (await todayTotals())[`${sku}_calls`] ?? 0;
  const key = `${day()}:${sku}`;
  if (used >= cap * 0.8 && !alerted.has(key)) {
    alerted.add(key);
    await db.collection("opsAlerts").add({ type: "MAPS_CAP", sku, used, cap, level: used >= cap ? "cap_reached" : "80_percent",
      message: `Google Maps ${sku} has used ${used} of its ${cap} daily calls.`, at: FieldValue.serverTimestamp(), status: "OPEN" }).catch(() => undefined);
  }
  return used < cap;
}

/** Per-user hourly limit on cache-missing calls (protects spend from one abusive account). */
export async function userWithinLimit(who: string, sku: MapsSku, perHour: number): Promise<boolean> {
  if (!perHour) return true;
  const ref = db.doc(`mapsRate/${who.replace(/\//g, "_")}_${sku}_${new Date().toISOString().slice(0, 13)}`);
  const n = await db.runTransaction(async (tx) => {
    const cur = ((await tx.get(ref)).get("n") as number | undefined) ?? 0;
    if (cur >= perHour) return cur + 1;
    tx.set(ref, { n: cur + 1, expiresAt: new Date(Date.now() + 2 * 3_600_000) }, { merge: true });
    return cur + 1;
  }).catch(() => 0);
  return n <= perHour;
}
