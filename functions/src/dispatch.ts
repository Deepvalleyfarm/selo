/**
 * Smart rider dispatch. Instead of broadcasting a job to every eligible rider, each candidate gets a dispatch score
 * (0–100) and the job is offered in waves: the best few first, then more, then everyone eligible.
 *
 * Score components (weights sum to 100):
 *   30  distance from the rider to the seller / pickup
 *   10  distance from the rider to the buyer / drop-off
 *   15  fit with the rider's current route (drop-offs already on board): small detour = high score
 *   15  availability: free capacity and a recent GPS ping
 *   20  history: acceptance rate (10) and completion rate (10)
 *   10  estimated pickup time (road-distance estimate + time to finish what's already on board)
 */
import { FieldValue } from "firebase-admin/firestore";
import { db } from "./config";
import { haversineKm } from "./domain";

export interface LL { lat: number; lng: number }
export interface DispatchJob { pickup: LL | null; dropoff: LL | null }
export interface RiderCandidate {
  uid: string; geo: LL | null; pingAgeMin: number | null; activeCount: number; maxConcurrent: number;
  acceptanceRate: number; completionRate: number; completedDeliveries: number; routeStops: LL[];
}
export interface DispatchScore {
  uid: string; score: number; etaPickupMin: number | null;
  parts: { toSeller: number; toBuyer: number; route: number; availability: number; history: number; eta: number };
  toSellerKm: number | null; detourKm: number | null;
}

/** City riding: straight-line × 1.3 for roads, 22 km/h average on a motorbike in traffic, 4 min per stop on board. */
const ROAD = 1.3, SPEED_KMH = 22, MIN_PER_ACTIVE_STOP = 4;
const clamp01 = (x: number) => Math.max(0, Math.min(1, x));
const r1 = (x: number) => Math.round(x * 10) / 10;

export function estimatePickupMin(c: RiderCandidate, pickup: LL | null): number | null {
  if (!c.geo || !pickup) return null;
  return Math.round((haversineKm(c.geo, pickup) * ROAD / SPEED_KMH) * 60 + c.activeCount * MIN_PER_ACTIVE_STOP);
}

/** Extra km to fit this job into the rider's current route (0 when they carry nothing). */
export function detourKm(c: RiderCandidate, job: DispatchJob): number | null {
  if (!c.routeStops.length) return 0;
  if (!job.pickup) return null;
  const toPickup = Math.min(...c.routeStops.map((s) => haversineKm(s, job.pickup!)));
  const toDrop = job.dropoff ? Math.min(...c.routeStops.map((s) => haversineKm(s, job.dropoff!))) : toPickup;
  return toPickup + toDrop / 2;
}

export function dispatchScore(c: RiderCandidate, job: DispatchJob): DispatchScore {
  const toSellerKm = c.geo && job.pickup ? haversineKm(c.geo, job.pickup) : null;
  const toBuyerKm = c.geo && job.dropoff ? haversineKm(c.geo, job.dropoff) : null;
  const detour = detourKm(c, job);
  const eta = estimatePickupMin(c, job.pickup);
  const fresh = c.pingAgeMin == null ? 0.4 : c.pingAgeMin <= 2 ? 1 : c.pingAgeMin <= 10 ? 0.7 : 0.3;
  const parts = {
    toSeller: 30 * (toSellerKm == null ? 0.3 : clamp01(1 - toSellerKm / 10)),
    toBuyer: 10 * (toBuyerKm == null ? 0.3 : clamp01(1 - toBuyerKm / 15)),
    route: 15 * (detour == null ? 0.5 : clamp01(1 - detour / 5)),
    availability: 15 * clamp01(1 - c.activeCount / Math.max(1, c.maxConcurrent)) * fresh,
    history: 10 * clamp01(c.acceptanceRate / 100) + 10 * clamp01(c.completionRate / 100) * (c.completedDeliveries < 5 ? 0.8 : 1),
    eta: 10 * (eta == null ? 0.3 : clamp01(1 - eta / 30)),
  };
  const score = Object.values(parts).reduce((a, b) => a + b, 0);
  return { uid: c.uid, score: r1(score), etaPickupMin: eta, toSellerKm: toSellerKm == null ? null : r1(toSellerKm), detourKm: detour == null ? null : r1(detour),
    parts: Object.fromEntries(Object.entries(parts).map(([k, v]) => [k, r1(v)])) as DispatchScore["parts"] };
}

/** Best first; ties go to the rider who'd reach the pickup sooner. */
export function rankCandidates(cs: RiderCandidate[], job: DispatchJob): DispatchScore[] {
  return cs.map((c) => dispatchScore(c, job)).sort((a, b) => b.score - a.score || (a.etaPickupMin ?? 999) - (b.etaPickupMin ?? 999));
}

/** Wave sizes: round 0 → top 3, round 1 → top 8, round ≥ 2 → every eligible rider. */
export function waveSize(round: number, sizes = [3, 8]): number { return round < sizes.length ? sizes[round] : Infinity; }
export function pickWave(ranked: DispatchScore[], round: number, sizes?: number[]): string[] {
  return ranked.slice(0, waveSize(round, sizes)).map((s) => s.uid);
}

/** Loads what the score needs for each eligible rider: position, capacity, history and the drop-offs on board. */
export async function loadCandidates(uids: string[], maxConcurrent: number): Promise<RiderCandidate[]> {
  if (!uids.length) return [];
  const [presence, wallets] = await Promise.all([db.getAll(...uids.map((u) => db.doc(`riderPresence/${u}`))), db.getAll(...uids.map((u) => db.doc(`riderWallets/${u}`)))]);
  const now = Date.now();
  return Promise.all(uids.map(async (uid, i) => {
    const p = presence[i]?.data() ?? {}, w = wallets[i]?.data() ?? {};
    const active = (p.activeCount ?? w.activeCount ?? 0) as number;
    let stops: LL[] = [];
    if (active > 0) {
      const [o, pc] = await Promise.all([
        db.collection("orders").where("riderUid", "==", uid).where("state", "in", ["RIDER_ASSIGNED", "PICKED_UP", "IN_TRANSIT"]).limit(4).get(),
        db.collection("parcels").where("riderUid", "==", uid).where("status", "in", ["RIDER_ASSIGNED", "IN_TRANSIT"]).limit(4).get()]);
      stops = [...o.docs.map((d) => d.get("dropoffGeo")), ...pc.docs.map((d) => d.get("dropoff"))]
        .filter((s) => s && Number.isFinite(Number(s.lat))).map((s) => ({ lat: Number(s.lat), lng: Number(s.lng) }));
    }
    const done = Number(w.completedDeliveries ?? 0), failed = Number(w.failedDeliveries ?? 0), dropped = Number(w.jobsCancelled ?? 0);
    return { uid, geo: p.geo && Number.isFinite(Number(p.geo.lat)) ? { lat: Number(p.geo.lat), lng: Number(p.geo.lng) } : null,
      pingAgeMin: p.lastPingAt?.toMillis ? (now - p.lastPingAt.toMillis()) / 60_000 : null, activeCount: active, maxConcurrent,
      acceptanceRate: Number(w.acceptanceRate ?? 100), completionRate: done + failed + dropped > 0 ? (done * 100) / (done + failed + dropped) : 100,
      completedDeliveries: done, routeStops: stops };
  }));
}

/**
 * Ranks the eligible riders, offers the job to the next wave and records why (top 10 scores) for the back office.
 * Riders from earlier waves keep the offer. Returns the riders newly notified.
 */
export async function offerInWaves(docPath: string, eligible: string[], job: DispatchJob, maxConcurrent: number, round: number, already: string[] = []) {
  const ranked = rankCandidates(await loadCandidates(eligible, maxConcurrent), job);
  const wave = pickWave(ranked, round);
  const fresh = wave.filter((u) => !already.includes(u));
  // broadcastAt marks the first offer (refund timers run from it); waveAt marks this wave.
  await db.doc(docPath).update({
    ...(wave.length ? { notifiedRiders: FieldValue.arrayUnion(...wave) } : {}), dispatchRound: round + 1, dispatchMode: "smart",
    dispatchScores: ranked.slice(0, 10).map((s) => ({ uid: s.uid, score: s.score, etaPickupMin: s.etaPickupMin, toSellerKm: s.toSellerKm, detourKm: s.detourKm })),
    waveAt: FieldValue.serverTimestamp(), ...(round === 0 ? { broadcastAt: FieldValue.serverTimestamp() } : {}),
  });
  return { fresh, ranked, exhausted: wave.length >= ranked.length };
}
