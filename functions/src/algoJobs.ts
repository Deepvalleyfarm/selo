/** Scheduled algorithm work (PRD v6.1 §3.4, §6.2, §6.3). Every algorithm change is a new immutable version. */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { db } from "./config";
import {
  DEFAULT_WEIGHTS, defaultCohortState, engagementRate, interestVector, kmeans, microTune, nearest, pickWinner, SIGNALS, viralityScore,
  weightVariants, type BehaviourEvent, type CohortState, type TuningChange, type Weights,
} from "./algorithm";
import { loadAlgo, lusakaDate, type AlgoCurrent } from "./discovery";

/** Writes algorithmState/v{n} (never overwritten) and moves the `current` pointer. Any version can be replayed. */
export async function saveAlgoVersion(next: Omit<AlgoCurrent, "version">, reason: string, changes: (TuningChange & { cohort?: number })[], by = "system") {
  return db.runTransaction(async (tx) => {
    const cur = (await tx.get(db.doc("algorithmState/current"))).data() as AlgoCurrent | undefined;
    const version = (cur?.version ?? 0) + 1;
    const doc = { ...next, version, reason, changes, createdBy: by, createdAt: FieldValue.serverTimestamp() };
    tx.create(db.doc(`algorithmState/v${String(version).padStart(6, "0")}`), doc);
    tx.set(db.doc("algorithmState/current"), { ...next, version, reason, updatedAt: FieldValue.serverTimestamp() });
    return version;
  });
}

const DAY = 86_400_000;

/** Hourly: buyer vectors + cohort for recently active buyers; seller engagement (7 d); listing virality (6 h). */
export const algo_hourlyDerive = onSchedule({ schedule: "every 60 minutes", timeoutSeconds: 540, memory: "1GiB" }, async () => {
  const now = Date.now();
  const algo = await loadAlgo();
  const recent = await db.collection("behaviourEvents").where("ts", ">=", now - 65 * 60_000).limit(20_000).get();
  const users = [...new Set(recent.docs.map((d) => d.get("userId") as string))].slice(0, 2000);
  const embCache = new Map<string, number[]>();
  for (const u of users) {
    const evs = (await db.collection("behaviourEvents").where("userId", "==", u).orderBy("ts", "desc").limit(100).get()).docs.map((d) => d.data() as BehaviourEvent);
    const ids = [...new Set(evs.map((e) => e.listingId).filter((x): x is string => !!x && !embCache.has(x)))];
    for (let i = 0; i < ids.length; i += 30) {
      const s = await db.collection("listings").where("__name__", "in", ids.slice(i, i + 30)).get();
      s.docs.forEach((d) => { const e = d.get("embedding"); if (Array.isArray(e)) embCache.set(d.id, e); });
    }
    const vector = interestVector(evs, embCache, now);
    const cohort = algo.centroids.length ? nearest(vector, algo.centroids) : 0;
    await db.doc(`buyerProfiles/${u}`).set({ vector, cohort, vectorUpdatedAt: now }, { merge: true });
  }

  // Seller engagement: rolling 7-day (likes + shares + enquiries) / views
  const week = await db.collection("listingDaily").where("date", ">=", lusakaDate(now - 6 * DAY)).limit(50_000).get();
  const per = new Map<string, { v: number; e: number }>();
  week.docs.forEach((d) => { const s = d.get("sellerUid"); if (!s) return; const p = per.get(s) ?? { v: 0, e: 0 };
    p.v += d.get("views") ?? 0; p.e += (d.get("likes") ?? 0) + (d.get("shares") ?? 0) + (d.get("enquiries") ?? 0); per.set(s, p); });
  for (const [seller, p] of per) {
    const rate = engagementRate(p.e, 0, 0, p.v);
    await db.doc(`sellerStats/${seller}`).set({ engagementRate7d: rate }, { merge: true });
    const ls = await db.collection("listings").where("sellerUid", "==", seller).where("status", "==", "live").get();
    const b = db.batch(); ls.docs.forEach((l) => b.update(l.ref, { sellerEngagementRate: rate })); await b.commit();
  }

  // Virality: share velocity in the last 6 h relative to the category baseline
  const shares = await db.collection("shares").where("ts", ">=", now - 6 * 3_600_000).limit(20_000).get();
  const byListing = new Map<string, number>();
  shares.docs.forEach((d) => byListing.set(d.get("listingId"), (byListing.get(d.get("listingId")) ?? 0) + 1));
  const prevViral = await db.collection("listings").where("viralityScore", ">", 0).limit(5000).get();
  const touched = new Set([...byListing.keys(), ...prevViral.docs.map((d) => d.id)]);
  const cats = new Map<string, { shares: number; n: number }>();
  const meta = new Map<string, string>();
  for (const id of touched) {
    const d = await db.doc(`listings/${id}`).get(); if (!d.exists) continue;
    const c = d.get("category") ?? "Other"; meta.set(id, c);
    const x = cats.get(c) ?? { shares: 0, n: 0 }; x.shares += byListing.get(id) ?? 0; x.n += 1; cats.set(c, x);
  }
  const b = db.batch();
  for (const id of touched) {
    const c = cats.get(meta.get(id) ?? "") ?? { shares: 0, n: 1 };
    if (meta.has(id)) b.update(db.doc(`listings/${id}`), { viralityScore: viralityScore(byListing.get(id) ?? 0, c.shares / Math.max(1, c.n)) });
  }
  await b.commit();
});

/** Every 6 hours: micro-tune each cohort from the last completed 6-hour block of cohort metrics. */
export const algo_microTune = onSchedule({ schedule: "every 6 hours", timeoutSeconds: 300 }, async () => {
  const now = Date.now();
  const prevBlock = (t: number) => `${lusakaDate(t)}_${Math.floor(new Date(t + 2 * 3_600_000).getUTCHours() / 6)}`;
  const block = prevBlock(now - 6 * 3_600_000);
  const algo = await loadAlgo();
  const metrics = await db.collection("cohortMetrics").where("block", "==", block).get();
  // baseline share rate by media type over the previous 7 days of blocks
  const base = await db.collection("cohortMetrics").where("block", ">=", `${lusakaDate(now - 7 * DAY)}_0`).limit(2000).get();
  const rate = (rows: FirebaseFirestore.DocumentSnapshot[], t: string) => {
    const sh = rows.reduce((s, r) => s + (r.get(`sharesType_${t}`) ?? 0), 0), v = rows.reduce((s, r) => s + (r.get(`viewsType_${t}`) ?? 0), 0);
    return v ? sh / v : 0;
  };
  const baseline = { video: rate(base.docs, "video"), photo: rate(base.docs, "photo") };
  const cohorts: Record<string, CohortState> = { ...algo.cohorts };
  const allChanges: (TuningChange & { cohort: number })[] = [];
  for (const m of metrics.docs) {
    const c = Number(m.get("cohort") ?? 0);
    const st = cohorts[String(c)] ?? defaultCohortState(c);
    const data = m.data();
    const skipRateByCategory: Record<string, number> = {};
    Object.keys(data).filter((k) => k.startsWith("views_")).forEach((k) => {
      const cat = k.slice(6), views = data[k] ?? 0;
      if (views >= 20) skipRateByCategory[cat] = (data[`skips_${cat}`] ?? 0) / views;
    });
    const sessions = data.sessions ?? 0;
    if (sessions < 5 && !Object.keys(skipRateByCategory).length) continue;
    const { state, changes } = microTune(st, {
      avgSessionMin: sessions ? (data.sessionMs ?? 0) / sessions / 60_000 : st.targetSessionMin,
      skipRateByCategory, shareRateByType: { video: rate([m], "video"), photo: rate([m], "photo") }, baselineShareRateByType: baseline,
    }, now);
    if (changes.length) { cohorts[String(c)] = state; allChanges.push(...changes.map((ch) => ({ ...ch, cohort: c }))); }
  }
  if (allChanges.length) await saveAlgoVersion({ cohorts, centroids: algo.centroids, variants: algo.variants }, `micro-tune ${block}`, allChanges);
});

/** Weekly: re-cluster 20 cohorts, settle the A/B test, start a new one, and flag weak categories for seller campaigns. */
export const algo_macroTune = onSchedule({ schedule: "every monday 03:00", timeZone: "Africa/Lusaka", timeoutSeconds: 540, memory: "1GiB" }, async () => {
  const now = Date.now();
  const algo = await loadAlgo();
  const profiles = await db.collection("buyerProfiles").where("vectorUpdatedAt", ">=", now - 7 * DAY).limit(50_000).get();
  const vectors = profiles.docs.map((d) => d.get("vector") as number[]).filter((v) => Array.isArray(v) && v.length === 50);
  const { centroids, assignment } = kmeans(vectors, 20);
  const changes: (TuningChange & { cohort?: number })[] = [{ rule: "recluster", detail: `${vectors.length} buyers into ${centroids.length} cohorts` }];
  let i = 0;
  for (let s = 0; s < profiles.docs.length; s += 400) {
    const b = db.batch();
    profiles.docs.slice(s, s + 400).forEach((d) => { const v = d.get("vector"); if (Array.isArray(v) && v.length === 50) b.update(d.ref, { cohort: assignment[i++] }); });
    await b.commit();
  }

  // A/B: compare variants with control on session minutes and enquiry conversion over the week
  const weekBlocks = await db.collection("cohortMetrics").where("block", ">=", `${lusakaDate(now - 7 * DAY)}_0`).limit(5000).get();
  const agg = (v: string) => {
    const g = (k: string) => weekBlocks.docs.reduce((s, d) => s + (d.get(`variant_${v}_${k}`) ?? 0), 0);
    const sessions = g("session_start") || 1;
    return { sessionMin: g("session_end") / 60 / sessions, conversion: g("enquiry_open") / Math.max(1, g("listing_view")), n: g("session_start") };
  };
  const control = agg("control");
  const results = [0, 1, 2, 3].map((k) => agg(`v${k}`));
  const winner = algo.variants.length ? pickWinner(control, results) : null;
  const cohorts: Record<string, CohortState> = {};
  for (let c = 0; c < Math.max(centroids.length, 1); c++) {
    const prev = algo.cohorts[String(c)] ?? defaultCohortState(c);
    cohorts[String(c)] = { ...prev, weights: winner !== null ? { ...algo.variants[winner] } : prev.weights };
  }
  if (winner !== null) changes.push({ rule: "ab_winner", detail: `variant v${winner} beat control (sessions ${results[winner].sessionMin.toFixed(1)} vs ${control.sessionMin.toFixed(1)} min)` });
  const baseWeights: Weights = winner !== null ? algo.variants[winner] : meanWeights(Object.values(cohorts).map((c) => c.weights));
  const variants = weightVariants(baseWeights, Math.floor(now / DAY));
  changes.push({ rule: "ab_new_variants", detail: "4 new weight variants at 2.5% traffic each" });

  // Underperforming categories: engagement across all cohorts below half the median
  const listings = await db.collection("listings").where("status", "==", "live").select("category", "stats").limit(5000).get();
  const byCat = new Map<string, { v: number; e: number }>();
  listings.docs.forEach((d) => { const c = d.get("category") ?? "Other", st = d.get("stats") ?? {}; const x = byCat.get(c) ?? { v: 0, e: 0 };
    x.v += st.views ?? 0; x.e += (st.likes ?? 0) + (st.shares ?? 0) + (st.enquiries ?? 0); byCat.set(c, x); });
  const rates = [...byCat].filter(([, x]) => x.v >= 100).map(([c, x]) => [c, x.e / x.v] as const);
  const median = [...rates].map((r) => r[1]).sort((a, b) => a - b)[Math.floor(rates.length / 2)] ?? 0;
  const weak = rates.filter(([, r]) => r < median / 2).map(([c]) => c);
  await db.doc(`adminMetrics/met_${lusakaDate(now)}_weak_categories`).set({ type: "weak_categories", date: lusakaDate(now), categories: weak, median, at: FieldValue.serverTimestamp() });
  if (weak.length) changes.push({ rule: "weak_categories", detail: `flagged for seller growth campaigns: ${weak.join(", ")}` });

  await saveAlgoVersion({ cohorts, centroids, variants }, "weekly macro-tune", changes);
});

function meanWeights(ws: Weights[]): Weights {
  if (!ws.length) return { ...DEFAULT_WEIGHTS };
  const out = { ...DEFAULT_WEIGHTS };
  SIGNALS.forEach((k) => { out[k] = Math.round((ws.reduce((s, w) => s + w[k], 0) / ws.length) * 10_000) / 10_000; });
  return out;
}

/** Daily: archived (soft-deleted) listings older than 30 days move to the cold `archive` collection — never purged. */
export const memory_archiveTombstones = onSchedule({ schedule: "30 2 * * *", timeZone: "Africa/Lusaka" }, async () => {
  const cutoff = Timestamp.fromMillis(Date.now() - 30 * DAY);
  const old = await db.collection("listings").where("status", "==", "archived").where("deletedAt", "<", cutoff).limit(400).get();
  for (const d of old.docs) {
    await db.runTransaction(async (tx) => {
      tx.set(db.doc(`archive/listings_${d.id}`), { ...d.data(), collection: "listings", originalId: d.id, archivedAt: FieldValue.serverTimestamp() });
      tx.delete(d.ref);
    });
  }
});

/** First run bootstraps algorithmState with the PRD default weights. */
export async function ensureAlgo() {
  const cur = await db.doc("algorithmState/current").get();
  if (!cur.exists) await saveAlgoVersion({ cohorts: { "0": defaultCohortState(0) }, centroids: [], variants: weightVariants(DEFAULT_WEIGHTS) }, "bootstrap defaults", []);
}
