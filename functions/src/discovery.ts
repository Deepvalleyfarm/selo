/**
 * Pillar 1 + 3 runtime: behaviour-event ingestion, feed ranking, rails, search, sharing and the seller dashboard.
 * All state lives in the named Firestore database; events are append-only and every read model is derived from them.
 */
import { FieldValue, Timestamp, type DocumentSnapshot } from "firebase-admin/firestore";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { onDocumentCreated, onDocumentWritten } from "firebase-functions/v2/firestore";
import { DB_ID, db } from "./config";
import {
  abBucket, cosine, DEFAULT_GUARDRAILS, DEFAULT_WEIGHTS, defaultCohortState, embedListing, eventProblem, guardrailState, queryTokens,
  rank, retrieve, searchTokens, type BehaviourEvent, type CandidateListing, type CohortState, type Guardrails, type Weights,
} from "./algorithm";
import { normaliseMsisdn } from "./domain";
import { notify, requireUser, str } from "./util";

/* ----------------------------------------------------------------- shared helpers */

export const lusakaDate = (ms = Date.now()) => new Date(ms + 2 * 3_600_000).toISOString().slice(0, 10);
const lusakaHour = (ms: number) => new Date(ms + 2 * 3_600_000).getUTCHours();
export const isoWeek = (ms = Date.now()) => { const d = new Date(ms + 2 * 3_600_000); const day = (d.getUTCDay() + 6) % 7; d.setUTCDate(d.getUTCDate() - day + 3);
  const w1 = new Date(Date.UTC(d.getUTCFullYear(), 0, 4)); return `${d.getUTCFullYear()}-W${String(1 + Math.round(((+d - +w1) / 86_400_000 - 3 + ((w1.getUTCDay() + 6) % 7)) / 7)).padStart(2, "0")}`; };
const sixHourBlock = (ms: number) => `${lusakaDate(ms)}_${Math.floor(lusakaHour(ms) / 6)}`;
const ms = (v: unknown) => (v instanceof Timestamp ? v.toMillis() : typeof v === "number" ? v : 0);

export interface AlgoCurrent { version: number; cohorts: Record<string, CohortState>; centroids: number[][]; variants: Weights[]; updatedAt?: unknown }
export async function loadAlgo(): Promise<AlgoCurrent> {
  const d = (await db.doc("algorithmState/current").get()).data() as AlgoCurrent | undefined;
  return d ?? { version: 0, cohorts: {}, centroids: [], variants: [] };
}
export const cohortState = (a: AlgoCurrent, cohort: number): CohortState => a.cohorts[String(cohort)] ?? defaultCohortState(cohort);
export async function loadGuardrails(): Promise<Guardrails> {
  return { ...DEFAULT_GUARDRAILS, ...((await db.doc("platformConfig/guardrails").get()).data() as Partial<Guardrails> | undefined) };
}

export function toCandidate(d: DocumentSnapshot): CandidateListing {
  const x = d.data() ?? {};
  const st = x.stats ?? {};
  return {
    id: d.id, sellerUid: x.sellerUid, category: x.category ?? "Other", mediaType: x.mediaType === "video" ? "video" : "photo", status: x.status,
    publishedAt: ms(x.publishedAt), province: x.province, district: x.district, priceNgwee: x.priceNgwee,
    embedding: Array.isArray(x.embedding) && x.embedding.length === 50 ? x.embedding : embedListing(x),
    stats: { views: st.views ?? 0, likes: st.likes ?? 0, shares: st.shares ?? 0, enquiries: st.enquiries ?? 0, saves: st.saves ?? 0,
      notInterested: st.notInterested ?? 0, watchMs: st.watchMs ?? 0, watchTotalMs: st.watchTotalMs ?? 0 },
    sellerEngagementRate: x.sellerEngagementRate ?? 0, viralityScore: x.viralityScore ?? 0, discountPct: x.discountPct ?? 0,
  };
}

/** Fields a buyer device may see (no embeddings, no search index). */
export function publicListing(d: DocumentSnapshot, extra: Record<string, unknown> = {}) {
  const x = d.data() ?? {};
  return {
    id: d.id, sellerUid: x.sellerUid, sellerName: x.sellerName ?? "", sellerVerified: !!x.sellerVerified, title: x.title ?? "",
    description: x.description ?? "", priceNgwee: x.priceNgwee ?? 0, discountPct: x.discountPct ?? 0, discountNgwee: x.discountNgwee ?? 0,
    category: x.category ?? "", condition: x.condition ?? "", availability: x.availability ?? "In Stock", province: x.province ?? "",
    district: x.district ?? "", location: x.location ?? "", tags: x.tags ?? [], mediaType: x.mediaType ?? "photo", videoUrl: x.videoUrl ?? null,
    photoUrls: x.photoUrls ?? [], thumbUrl: x.thumbUrl ?? null, stats: { likes: x.stats?.likes ?? 0, shares: x.stats?.shares ?? 0, views: x.stats?.views ?? 0 },
    publishedAt: ms(x.publishedAt), ...extra,
  };
}

/** Live listings, cached per instance for 60 s (feed, rails and search all read the same pool). */
let poolCache: { at: number; docs: DocumentSnapshot[] } | null = null;
async function livePool(): Promise<DocumentSnapshot[]> {
  if (poolCache && Date.now() - poolCache.at < 60_000) return poolCache.docs;
  const s = await db.collection("listings").where("status", "==", "live").orderBy("publishedAt", "desc").limit(1500).get();
  poolCache = { at: Date.now(), docs: s.docs };
  return s.docs;
}

/* ---------------------------------------------------------- event ingestion (trigger) */

/**
 * Every behaviour event (written append-only by the app) updates the read models:
 * listing counters, daily listing stats, cohort metrics for tuning, seller audience, admin KPIs, shares, searches.
 */
export const onBehaviourEvent = onDocumentCreated({ document: "behaviourEvents/{id}", database: DB_ID }, async (e) => {
  const ev = e.data?.data() as BehaviourEvent | undefined;
  if (!ev || eventProblem(ev)) return;
  const ts = ev.ts || Date.now(), date = lusakaDate(ts);
  const prof = (await db.doc(`buyerProfiles/${ev.userId}`).get()).data() ?? {};
  const cohort = prof.cohort ?? 0;
  const batch = db.batch();
  const inc = (n = 1) => FieldValue.increment(n);
  const statKey: Partial<Record<BehaviourEvent["type"], string>> = { listing_view: "views", like: "likes", share: "shares", enquiry_open: "enquiries",
    save: "saves", not_interested: "notInterested", details_open: "taps", skip: "skips" };
  const k = statKey[ev.type];
  const sign = ev.undone ? -1 : 1;

  if (ev.listingId) {
    const lRef = db.doc(`listings/${ev.listingId}`);
    const upd: Record<string, unknown> = {};
    if (k) upd[`stats.${k}`] = inc(sign);
    if (ev.type === "video_watch") { upd["stats.watchMs"] = inc(Math.min(ev.watchMs ?? 0, ev.totalMs ?? 0)); upd["stats.watchTotalMs"] = inc(ev.totalMs ?? 0); }
    if (Object.keys(upd).length) batch.set(lRef, flatten(upd), { merge: true });
    if (k) batch.set(db.doc(`listingDaily/${ev.listingId}_${date}`), { listingId: ev.listingId, sellerUid: ev.sellerUid ?? null, date, [k]: inc(sign) }, { merge: true });
    if (ev.sellerUid && ["listing_view", "like", "share", "enquiry_open", "save"].includes(ev.type)) {
      batch.set(db.doc(`sellerAudience/${ev.sellerUid}_${date}`), { sellerUid: ev.sellerUid, date,
        province: { [prof.province ?? "Unknown"]: inc() }, ageBand: { [prof.ageBand ?? "Not given"]: inc() }, hour: { [String(lusakaHour(ts))]: inc() } }, { merge: true });
    }
  }
  // cohort metrics feed the 6-hourly micro-tuning
  const cm: Record<string, unknown> = { cohort, block: sixHourBlock(ts) };
  if (ev.type === "session_end") { cm.sessionMs = inc(ev.durationMs ?? 0); cm.sessions = inc(); }
  if (ev.type === "listing_view") { cm[`views_${slug(ev.category)}`] = inc(); cm[`viewsType_${ev.mediaType ?? "photo"}`] = inc(); }
  if (ev.type === "skip") cm[`skips_${slug(ev.category)}`] = inc();
  if (ev.type === "share") cm[`sharesType_${ev.mediaType ?? "photo"}`] = inc();
  if (ev.variant) cm[`variant_${ev.variant}_${ev.type}`] = inc(ev.type === "session_end" ? Math.round((ev.durationMs ?? 0) / 1000) : 1);
  batch.set(db.doc(`cohortMetrics/${cohort}_${sixHourBlock(ts)}`), cm, { merge: true });

  // admin KPIs (daily + hourly), split by media type to compare video vs photo listings
  const kpi: Record<string, unknown> = { date, type: "daily", [`events_${ev.type}`]: inc() };
  if (ev.mediaType) kpi[`${ev.mediaType}_${ev.type}`] = inc();
  if (ev.type === "session_end") { kpi.sessionMs = inc(ev.durationMs ?? 0); kpi.sessions = inc(); }
  batch.set(db.doc(`adminMetrics/met_${date}_daily`), kpi, { merge: true });
  batch.set(db.doc(`adminMetrics/met_${date}_h${String(lusakaHour(ts)).padStart(2, "0")}`), { date, hour: lusakaHour(ts), type: "hourly", [`events_${ev.type}`]: inc() }, { merge: true });

  // buyer profile: guardrail counters, last search, likes list
  const p: Record<string, unknown> = { lastEventAt: ts };
  if (ev.type === "session_end") p[`dailyMinutes.${date}`] = inc((ev.durationMs ?? 0) / 60_000);
  if (ev.type === "enquiry_open") p[`weeklyEnquiries.${isoWeek(ts)}`] = inc();
  if (ev.type === "not_interested" && ev.listingId) p[`blockedListings.${ev.listingId}`] = ts;
  if (ev.type === "search") {
    p.lastSearch = { query: ev.query ?? "", ts, clicked: ev.firstClickListingId ?? null };
    p.recentSearches = FieldValue.arrayUnion((ev.query ?? "").slice(0, 60));
    const prev = prof.lastSearch as { query: string; ts: number } | undefined;
    const a = queryTokens(prev?.query ?? "")[0], b = queryTokens(ev.query ?? "")[0];
    if (prev && a && b && a !== b && ts - prev.ts < 15 * 60_000) {
      batch.set(db.doc(`searchStats/${a}`), { related: { [b]: inc() } }, { merge: true });
      batch.set(db.doc(`searchStats/${b}`), { related: { [a]: inc() } }, { merge: true });
    }
  }
  batch.set(db.doc(`buyerProfiles/${ev.userId}`), flatten(p), { merge: true });

  if (ev.type === "share" && ev.listingId) {
    batch.set(db.collection("shares").doc(`shr_${e.params.id}`), { listingId: ev.listingId, sellerUid: ev.sellerUid ?? null, userId: ev.userId,
      channel: ev.channel, ts, createdAt: FieldValue.serverTimestamp() });
  }
  if (ev.type === "like" && ev.listingId) {
    const ref = db.doc(`likes/${ev.userId}/items/${ev.listingId}`);
    if (ev.undone) batch.delete(ref); else batch.set(ref, { listingId: ev.listingId, ts });
  }
  await batch.commit();
});

const slug = (s?: string) => (s ?? "other").toLowerCase().replace(/[^a-z0-9]+/g, "-");
/** Turns {"a.b": x} into nested objects so set(..., {merge}) works with FieldValue sentinels. */
function flatten(o: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, any> = {};
  for (const [k, v] of Object.entries(o)) {
    const parts = k.split(".");
    let cur = out;
    parts.slice(0, -1).forEach((p) => { cur[p] = cur[p] ?? {}; cur = cur[p]; });
    cur[parts.at(-1)!] = v;
  }
  return out;
}

/* ---------------------------------------------------------------------- feed */

export const feed_next = onCall(async (req) => {
  const { uid } = requireUser(req);
  const count = Math.min(20, Math.max(1, Number(req.data?.count) || 8));
  const sessionId = typeof req.data?.sessionId === "string" && /^[\w-]{8,64}$/.test(req.data.sessionId) ? req.data.sessionId : `ses_${Date.now()}_${uid.slice(0, 6)}`;
  const now = Date.now();
  const [profSnap, algo, guard, pool] = await Promise.all([db.doc(`buyerProfiles/${uid}`).get(), loadAlgo(), loadGuardrails(), livePool()]);
  const prof = profSnap.data() ?? {};
  const cohort = Number(prof.cohort ?? 0);
  const st = cohortState(algo, cohort);
  const bucket = abBucket(uid);
  const weights: Weights = bucket !== null && algo.variants[bucket] ? algo.variants[bucket] : st.weights ?? DEFAULT_WEIGHTS;
  const variant = bucket !== null && algo.variants[bucket] ? `v${bucket}` : "control";
  const minutesToday = Number(prof.dailyMinutes?.[lusakaDate(now)] ?? 0);
  const weeks = [0, 1, 2, 3].map((w) => Number(prof.weeklyEnquiries?.[isoWeek(now - w * 7 * 86_400_000)] ?? 0));
  const g = guardrailState(guard, minutesToday, weeks);

  const shownAt: Record<string, number> = Object.fromEntries(Object.entries(prof.shownAt ?? {}).filter(([, t]) => now - Number(t) < 72 * 3_600_000)) as Record<string, number>;
  const blockedListings = new Set(Object.keys(prof.blockedListings ?? {}));
  const blockedSellers = new Set<string>(prof.blockedSellers ?? []);
  const candidatesAll = pool.map(toCandidate).filter((c) => c.sellerUid !== uid);
  const buyerVec: number[] = Array.isArray(prof.vector) && prof.vector.length === 50 ? prof.vector : new Array(50).fill(0);

  let picked: { listing: CandidateListing; score: number; source: string }[];
  if (g.optimise) {
    const { candidates, sources } = retrieve({ all: candidatesAll, buyerVec, centroid: algo.centroids[cohort], province: prof.province, district: prof.district,
      shownAt, blockedListings, blockedSellers, now, mix: st.mix, categoryShare: st.categoryShare, typeBoost: st.typeBoost });
    picked = rank(candidates, buyerVec, weights, count).map((r) => ({ ...r, source: sources[r.listing.id] }));
  } else {
    // Past the daily cap: stop optimising — plain newest-first, no personalisation (PRD §6.4).
    picked = candidatesAll.filter((c) => !shownAt[c.id] && !blockedListings.has(c.id)).sort((a, b) => b.publishedAt - a.publishedAt)
      .slice(0, count).map((l) => ({ listing: l, score: 0, source: "unranked_cap" }));
  }
  if (!picked.length) { // everything seen in 72 h: allow repeats rather than an empty feed
    picked = candidatesAll.filter((c) => !blockedListings.has(c.id)).sort((a, b) => b.viralityScore - a.viralityScore).slice(0, count)
      .map((l) => ({ listing: l, score: 0, source: "repeat" }));
  }
  const byId = new Map(pool.map((d) => [d.id, d]));
  const items = picked.map((p) => publicListing(byId.get(p.listing.id)!, { rankSource: p.source, rankScore: Math.round(p.score * 1000) / 1000 }));

  for (const p of picked) shownAt[p.listing.id] = now;
  await Promise.all([
    db.doc(`buyerProfiles/${uid}`).set({ shownAt, abVariant: variant, cohort }, { merge: true }),
    db.doc(`feedSessions/${sessionId}`).set({ buyerId: uid, cohort, variant, algoVersion: algo.version, guardrailOptimise: g.optimise,
      items: FieldValue.arrayUnion(...picked.map((p) => ({ id: p.listing.id, source: p.source, score: Math.round(p.score * 1000) / 1000, at: now }))),
      startedAt: FieldValue.serverTimestamp() }, { merge: true }),
  ]);
  return { sessionId, items, variant, cohort, algoVersion: algo.version,
    guard: { minutesToday: Math.round(minutesToday), warn: g.warn, capReached: !g.optimise, budgetCheck: g.budgetCheck,
      warnAtMinutes: guard.warnAtMinutes, capMinutes: guard.sessionCapMinutes } };
});

/* ------------------------------------------------------------------- home rails */

export const home_rails = onCall(async (req) => {
  const { uid } = requireUser(req);
  const [profSnap, pool] = await Promise.all([db.doc(`buyerProfiles/${uid}`).get(), livePool()]);
  const prof = profSnap.data() ?? {};
  const cands = pool.map(toCandidate).filter((c) => c.sellerUid !== uid);
  const byId = new Map(pool.map((d) => [d.id, d]));
  const vec: number[] = Array.isArray(prof.vector) && prof.vector.length === 50 ? prof.vector : [];
  const pub = (ls: CandidateListing[]) => ls.slice(0, 12).map((l) => publicListing(byId.get(l.id)!));
  const rails: { id: string; title: string; items: unknown[] }[] = [];

  const lastLike = await db.collection(`likes/${uid}/items`).orderBy("ts", "desc").limit(1).get();
  const liked = lastLike.docs[0] && byId.get(lastLike.docs[0].id);
  if (liked) {
    const ref = toCandidate(liked);
    rails.push({ id: "because_liked", title: `Because you liked ${liked.get("title")}`,
      items: pub(cands.filter((c) => c.id !== ref.id).sort((a, b) => cosine(ref.embedding, b.embedding) - cosine(ref.embedding, a.embedding))) });
  }
  const eng = (c: CandidateListing) => (c.stats.likes + c.stats.shares * 2 + c.stats.enquiries * 2) / (c.stats.views + 10) + c.viralityScore * 0.2;
  const local = cands.filter((c) => (prof.district && c.district === prof.district) || (prof.province && c.province === prof.province));
  if (local.length) rails.push({ id: "popular_area", title: `Popular in ${prof.district || prof.province}`, items: pub(local.sort((a, b) => eng(b) - eng(a))) });

  const ls = prof.lastSearch as { query: string; ts: number; clicked: string | null } | undefined;
  if (ls?.query && !ls.clicked && Date.now() - ls.ts < 3 * 86_400_000) {
    const toks = queryTokens(ls.query);
    const hits = pool.filter((d) => toks.every((t) => (d.get("searchTokens") ?? []).includes(t)))
      .map(toCandidate).filter((c) => !prof.province || c.province === prof.province);
    if (hits.length) rails.push({ id: "searched_nearby", title: `You searched for "${ls.query}" — here are sellers nearby`, items: pub(hits) });
  }
  const deals = cands.filter((c) => (c.discountPct ?? 0) > 0 || (byId.get(c.id)?.get("discountNgwee") ?? 0) > 0);
  if (deals.length) rails.push({ id: "deals", title: "Deals for you", items: pub(vec.length ? deals.sort((a, b) => cosine(vec, b.embedding) - cosine(vec, a.embedding)) : deals) });
  return { rails };
});

/* ----------------------------------------------------------------------- search */

export const search_listings = onCall(async (req) => {
  requireUser(req);
  const q = String(req.data?.q ?? "").slice(0, 80);
  const f = (req.data?.filters ?? {}) as { minNgwee?: number; maxNgwee?: number; province?: string; district?: string; condition?: string;
    mediaType?: string; availability?: string; category?: string };
  const toks = queryTokens(q);
  let docs: DocumentSnapshot[];
  if (toks.length) {
    docs = (await db.collection("listings").where("status", "==", "live").where("searchTokens", "array-contains", toks[0]).limit(300).get()).docs
      .filter((d) => toks.every((t) => (d.get("searchTokens") ?? []).includes(t)));
  } else docs = await livePool();
  const ok = (d: DocumentSnapshot) => {
    const x = d.data() ?? {}, price = x.priceNgwee ?? 0;
    return (f.minNgwee == null || price >= f.minNgwee) && (f.maxNgwee == null || price <= f.maxNgwee) && (!f.province || x.province === f.province)
      && (!f.district || x.district === f.district) && (!f.condition || x.condition === f.condition) && (!f.mediaType || x.mediaType === f.mediaType)
      && (!f.availability || x.availability === f.availability) && (!f.category || x.category === f.category);
  };
  const rel = (d: DocumentSnapshot) => toks.filter((t) => String(d.get("title") ?? "").toLowerCase().includes(t)).length * 2 + (d.get("stats.likes") ?? 0) / 100;
  const results = docs.filter(ok).sort((a, b) => rel(b) - rel(a)).slice(0, 60).map((d) => publicListing(d));
  let alsoSearched: string[] = [];
  if (toks[0]) {
    const related = ((await db.doc(`searchStats/${toks[0]}`).get()).get("related") ?? {}) as Record<string, number>;
    alsoSearched = Object.entries(related).sort((a, b) => b[1] - a[1]).slice(0, 6).map(([t]) => t);
  }
  return { results, alsoSearched };
});

/* ---------------------------------------------------------------------- sharing */

export const share_toUser = onCall(async (req) => {
  const { uid } = requireUser(req);
  const listingId = str(req.data?.listingId, "listing");
  const msisdn = normaliseMsisdn(req.data?.phone);
  if (!msisdn) throw new HttpsError("invalid-argument", "Enter your friend's mobile number.");
  const [idx, l, me] = await Promise.all([db.doc(`msisdnIndex/${msisdn}`).get(), db.doc(`listings/${listingId}`).get(), db.doc(`users/${uid}`).get()]);
  if (!idx.exists) throw new HttpsError("not-found", "That number isn't on SeloNaChipa yet. Share on WhatsApp instead.");
  if (!l.exists) throw new HttpsError("not-found", "Listing not found.");
  const to = idx.get("uid") as string;
  await db.collection(`inbox/${to}/items`).add({ type: "listing_share", listingId, fromUid: uid, fromName: me.get("fullName") ?? "",
    title: l.get("title"), priceNgwee: l.get("priceNgwee"), thumbUrl: l.get("thumbUrl") ?? null, readAt: null, createdAt: FieldValue.serverTimestamp() });
  await notify(to, `${me.get("firstName") || "A friend"} shared a listing`, `${l.get("title")} · K${((l.get("priceNgwee") ?? 0) / 100).toFixed(2)}`, "LISTING_SHARE", `selonachipa://listing/${listingId}`);
  return { ok: true };
});

/** Web preview for selonachipa://listing/{id} links shared on WhatsApp (served at /l/{id} via Hosting). */
export const listing_preview = onRequest(async (req, res) => {
  const id = (req.path.split("/").filter(Boolean).pop() ?? "").replace(/[^\w-]/g, "");
  const d = id ? await db.doc(`listings/${id}`).get() : null;
  const esc = (s: unknown) => String(s ?? "").replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
  const play = "https://play.google.com/store/apps/details?id=selo.Nachipa01";
  if (!d?.exists || !["live", "sold"].includes(d.get("status"))) { res.status(404).send(`<!doctype html><meta charset="utf-8"><title>SeloNaChipa</title><p>This listing is no longer available. <a href="${play}">Get the SeloNaChipa app</a></p>`); return; }
  const x = d.data()!;
  const price = `K ${((x.priceNgwee ?? 0) / 100).toLocaleString("en-ZM", { minimumFractionDigits: 2 })}`;
  const deep = `selonachipa://listing/${id}`;
  res.set("Cache-Control", "public, max-age=300").send(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(x.title)} · ${price} | SeloNaChipa</title>
<meta property="og:title" content="${esc(x.title)} · ${price}"><meta property="og:description" content="${esc(String(x.description ?? "").slice(0, 160))}">
${x.thumbUrl ? `<meta property="og:image" content="${esc(x.thumbUrl)}">` : ""}<meta property="og:type" content="product">
<style>body{margin:0;font-family:system-ui,sans-serif;background:#f5f5f5;color:#1a1a2e}main{max-width:460px;margin:0 auto;padding:16px}
img{width:100%;border-radius:16px;aspect-ratio:4/5;object-fit:cover;background:#ffe1e6}h1{font-size:20px;margin:14px 0 4px}.p{color:#CC0023;font-size:24px;font-weight:800}
a.b{display:block;text-align:center;padding:14px;border-radius:99px;font-weight:800;text-decoration:none;margin-top:12px}.r{background:#CC0023;color:#fff}.o{border:1.5px solid #CC0023;color:#CC0023}</style></head>
<body><main>${x.thumbUrl ? `<img src="${esc(x.thumbUrl)}" alt="">` : ""}<h1>${esc(x.title)}</h1><div class="p">${price}</div>
<p>${esc(x.sellerName)} · ${esc(x.district || x.province || "")}</p><p>${esc(x.description)}</p>
<a class="b r" href="${deep}">Open in SeloNaChipa</a><a class="b o" href="${play}">Download the SeloNaChipa app</a></main>
<script>setTimeout(function(){location.href=${JSON.stringify(deep)}},300)</script></body></html>`);
});

/* ------------------------------------------------------------- listing lifecycle */

const BANNED = /\b(gun|firearm|ammunition|cocaine|heroin|mandrax|ivory|rhino horn|pangolin|counterfeit|fake (?:id|nrc|passport)|stolen)\b/i;

/** Keeps the embedding and search index current, auto-approves clean listings and notifies followers when one goes live. */
export const onListingWrite = onDocumentWritten({ document: "listings/{id}", database: DB_ID }, async (e) => {
  const after = e.data?.after?.data(), before = e.data?.before?.data();
  if (!after) return;
  const ref = e.data!.after.ref;
  const textKey = [after.title, after.description, (after.tags ?? []).join(","), after.category, after.sellerName, after.condition].join("|");
  const upd: Record<string, unknown> = {};
  if (!before || [before.title, before.description, (before.tags ?? []).join(","), before.category, before.sellerName, before.condition].join("|") !== textKey || !after.embedding) {
    upd.embedding = embedListing(after);
    upd.searchTokens = searchTokens(after.title, after.description, (after.tags ?? []).join(" "), after.sellerName, after.category);
  }
  if (after.status === "pending_review") {
    const text = `${after.title} ${after.description} ${(after.tags ?? []).join(" ")}`;
    if (after.aiSafe === false || BANNED.test(text)) {
      upd.status = "flagged";
      await db.collection("moderationQueue").add({ listingId: ref.id, uid: after.sellerUid, reason: after.aiSafe === false ? "AI flagged media" : "Policy keyword in text",
        createdAt: FieldValue.serverTimestamp() });
    } else { upd.status = "live"; upd.publishedAt = FieldValue.serverTimestamp(); }
  }
  if (Object.keys(upd).length) await ref.update(upd);

  const nowLive = (upd.status ?? after.status) === "live", wasLive = before?.status === "live";
  if (nowLive && !wasLive) {
    await db.doc(`sellerStats/${after.sellerUid}`).set({ liveListings: FieldValue.increment(1) }, { merge: true });
    const followers = await db.collection("follows").where("sellerUid", "==", after.sellerUid).limit(2000).get();
    await Promise.all(followers.docs.map((f) => notify(f.get("followerUid"), `New from ${after.sellerName}`, `${after.title} · K${((after.priceNgwee ?? 0) / 100).toFixed(2)}`,
      "FOLLOWED_SELLER_LISTING", `selonachipa://listing/${ref.id}`)));
  } else if (wasLive && !nowLive) {
    await db.doc(`sellerStats/${after.sellerUid}`).set({ liveListings: FieldValue.increment(-1) }, { merge: true });
  }
});

/* -------------------------------------------------------------- seller dashboard */

export const seller_dashboard = onCall(async (req) => {
  const { uid } = requireUser(req, "SELLER", "AGENT");
  const sellerUid = typeof req.data?.sellerUid === "string" ? req.data.sellerUid : uid;
  const now = Date.now();
  const dates = Array.from({ length: 14 }, (_, i) => lusakaDate(now - i * 86_400_000));
  const [listings, daily, audience, sales] = await Promise.all([
    db.collection("listings").where("sellerUid", "==", sellerUid).where("status", "in", ["live", "paused", "sold", "pending_review", "flagged", "draft"]).limit(500).get(),
    db.collection("listingDaily").where("sellerUid", "==", sellerUid).where("date", ">=", dates.at(-1)).get(),
    db.collection("sellerAudience").where("sellerUid", "==", sellerUid).where("date", ">=", dates[6]).get(),
    db.collection("orders").where("sellerUid", "==", sellerUid).where("state", "==", "DELIVERED").limit(1000).get(),
  ]);
  const sum = (rows: FirebaseFirestore.QueryDocumentSnapshot[], k: string) => rows.reduce((s, r) => s + (r.get(k) ?? 0), 0);
  const thisWeek = daily.docs.filter((d) => dates.slice(0, 7).includes(d.get("date"))), lastWeek = daily.docs.filter((d) => dates.slice(7).includes(d.get("date")));
  const merge = (field: string) => audience.docs.reduce((acc, d) => { Object.entries(d.get(field) ?? {}).forEach(([k, v]) => { acc[k] = (acc[k] ?? 0) + Number(v); }); return acc; }, {} as Record<string, number>);
  const cards = listings.docs.map((d) => ({ id: d.id, title: d.get("title"), status: d.get("status"), thumbUrl: d.get("thumbUrl") ?? null, mediaType: d.get("mediaType") ?? "photo",
    priceNgwee: d.get("priceNgwee") ?? 0, views: d.get("stats.views") ?? 0, likes: d.get("stats.likes") ?? 0, shares: d.get("stats.shares") ?? 0,
    enquiries: d.get("stats.enquiries") ?? 0, taps: d.get("stats.taps") ?? 0, postedAt: ms(d.get("publishedAt") ?? d.get("createdAt")) }));
  const top = (k: "views" | "enquiries" | "likes") => [...cards].sort((a, b) => b[k] - a[k])[0] ?? null;
  return {
    listings: cards,
    week: { views: sum(thisWeek, "views"), lastWeekViews: sum(lastWeek, "views"), likes: sum(thisWeek, "likes"), shares: sum(thisWeek, "shares"), enquiries: sum(thisWeek, "enquiries") },
    totals: { likes: cards.reduce((s, c) => s + c.likes, 0), shares: cards.reduce((s, c) => s + c.shares, 0), enquiries: cards.reduce((s, c) => s + c.enquiries, 0) },
    funnel: { views: cards.reduce((s, c) => s + c.views, 0), taps: cards.reduce((s, c) => s + c.taps, 0), enquiries: cards.reduce((s, c) => s + c.enquiries, 0), sales: sales.size },
    top: { views: top("views"), enquiries: top("enquiries"), likes: top("likes") },
    audience: { ageBand: merge("ageBand"), province: merge("province"), peakHours: merge("hour") },
  };
});

export const seller_bulkListings = onCall(async (req) => {
  const { uid } = requireUser(req, "SELLER", "AGENT");
  const action = String(req.data?.action);
  const map: Record<string, { from: string[]; to: string }> = {
    pause: { from: ["live"], to: "paused" }, resume: { from: ["paused"], to: "live" }, sold: { from: ["live", "paused"], to: "sold" },
    archive: { from: ["live", "paused", "sold", "draft", "flagged"], to: "archived" },
  };
  const m = map[action];
  if (!m) throw new HttpsError("invalid-argument", "Unknown action.");
  const ids: string[] | null = Array.isArray(req.data?.ids) ? req.data.ids.slice(0, 500) : null;
  const snap = await db.collection("listings").where("sellerUid", "==", uid).where("status", "in", m.from).limit(500).get();
  const docs = snap.docs.filter((d) => !ids || ids.includes(d.id));
  const batch = db.batch();
  // Soft delete only: archived listings keep every field and are moved to cold archive after 30 days, never purged.
  docs.forEach((d) => batch.update(d.ref, { status: m.to, statusChangedAt: FieldValue.serverTimestamp(), ...(m.to === "archived" ? { deletedAt: FieldValue.serverTimestamp() } : {}) }));
  await batch.commit();
  return { updated: docs.length };
});

/** Keeps the buyer profile (read by the feed and the event trigger) in step with the user's region, age band and consent. */
export const onUserProfileWrite = onDocumentWritten({ document: "users/{uid}", database: DB_ID }, async (e) => {
  const a = e.data?.after?.data(); if (!a) return;
  const b = e.data?.before?.data() ?? {};
  if (["province", "district", "ageBand", "leadConsent"].every((k) => a[k] === b[k])) return;
  await db.doc(`buyerProfiles/${e.params.uid}`).set({ province: a.province ?? null, district: a.district ?? null, ageBand: a.ageBand ?? null,
    leadConsent: a.leadConsent === true }, { merge: true });
});
