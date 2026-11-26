/**
 * Rider routing on the Routes API (computeRoutes) — only for jobs the rider has accepted.
 *
 * Cost rules:
 *  - Stops must be the rider's own active pickups/drop-offs (no routing arbitrary points).
 *  - The last route is kept in riderRoutes/{uid}. Re-opening the app, or asking again while still on the route,
 *    returns the cached route for free. Finishing a stop never needs a new route: the app drops the finished leg.
 *  - A new route is computed only when the set of stops changes (a new job) or the rider has really left the route,
 *    and then no more than once per `rerouteMinIntervalSec` and `rerouteMaxPerHour` times an hour.
 *  - TRAFFIC_UNAWARE keeps calls on the Essentials SKU unless the admin switches traffic on.
 * Each order also gets its navigation summary { pickupLat, pickupLng, dropoffLat, dropoffLng, distanceKm,
 * estimatedMinutes } and the polyline of the leg into its drop-off, so the buyer's live map needs no extra calls.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { MAPS_API_KEY, db, loadConfig } from "./config";
import { computeRoute, geocodeAddress, type ComputedRoute, type TravelMode } from "./maps/client";
import { decodePolyline, distanceToPathM, encodePolyline, haversineM, isLL, toLL, type LL } from "./maps/geo-math";
import { requireUser } from "./util";

const ACTIVE_ORDER = ["RIDER_ASSIGNED", "PICKED_UP", "IN_TRANSIT", "AWAITING_DELIVERY_CODE"];
const ACTIVE_PARCEL = ["RIDER_ASSIGNED", "IN_TRANSIT", "AWAITING_DELIVERY_CODE"];

/** Pickup and drop-off coordinates for the rider's own active orders; geocodes (once, cached) any that are missing. */
export const route_locate = onCall({ secrets: [MAPS_API_KEY] }, async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const ids = (Array.isArray(req.data?.orderIds) ? req.data.orderIds : []).filter((x: unknown) => typeof x === "string").slice(0, 8) as string[];
  const cfg = await loadConfig();
  const out: Record<string, { pickup: LL | null; dropoff: LL | null }> = {};
  for (const id of ids) {
    const parcel = id.startsWith("PCL-");
    const ref = db.doc(`${parcel ? "parcels" : "orders"}/${id}`);
    const o = (await ref.get()).data();
    if (!o || o.riderUid !== uid) continue;
    if (parcel) { out[id] = { pickup: isLL(o.pickup) ? toLL(o.pickup) : null, dropoff: isLL(o.dropoff) ? toLL(o.dropoff) : null }; continue; }
    let pickup = isLL(o.pickupGeo) ? toLL(o.pickupGeo) : null, dropoff = isLL(o.dropoffGeo) ? toLL(o.dropoffGeo) : null;
    if (!pickup && o.pickup) { const g = await geocodeAddress(`${o.pickup}, ${o.zoneName ?? ""}, Zambia`, cfg); if (g) { pickup = { lat: g.latitude, lng: g.longitude }; await ref.update({ pickupGeo: pickup }); } }
    if (!dropoff && o.dropoff) { const g = await geocodeAddress(`${o.dropoff}, Zambia`, cfg); if (g) { dropoff = { lat: g.latitude, lng: g.longitude }; await ref.update({ dropoffGeo: dropoff }); } }
    out[id] = { pickup, dropoff };
  }
  return { stops: out };
});

const MANEUVER: Record<string, string> = {
  TURN_LEFT: "turn-left", TURN_RIGHT: "turn-right", TURN_SLIGHT_LEFT: "slight-left", TURN_SLIGHT_RIGHT: "slight-right",
  TURN_SHARP_LEFT: "sharp-left", TURN_SHARP_RIGHT: "sharp-right", UTURN_LEFT: "uturn", UTURN_RIGHT: "uturn", STRAIGHT: "straight",
  ROUNDABOUT_LEFT: "roundabout", ROUNDABOUT_RIGHT: "roundabout", MERGE: "merge", FORK_LEFT: "fork-left", FORK_RIGHT: "fork-right",
  DEPART: "depart", NAME_CHANGE: "straight", RAMP_LEFT: "slight-left", RAMP_RIGHT: "slight-right",
};
const secs = (d?: string) => (d ? Math.round(parseFloat(d)) : 0);

export interface TrafficInfo { level: "clear" | "moderate" | "heavy" | "unknown"; delayS: number; segments: { polyline: string; speed: string }[] }
export interface RouteResponse { distanceM: number; durationS: number; steps: any[]; polyline: string; source: string; cached?: boolean; throttled?: boolean; routeKey: string;
  traffic?: TrafficInfo; alternatives?: number; computedAt?: number }

/** Splits the route polyline into coloured stretches (NORMAL / SLOW / TRAFFIC_JAM) and grades the whole route (pure). */
export function trafficInfo(polyline: string, intervals: { from: number; to: number; speed: string }[], durationS: number, staticS: number): TrafficInfo {
  if (!polyline || !intervals.length) return { level: "unknown", delayS: Math.max(0, durationS - staticS), segments: [] };
  const pts = decodePolyline(polyline);
  const segments = intervals.filter((i) => i.to > i.from).map((i) => ({ polyline: encodePolyline(pts.slice(i.from, i.to + 1)), speed: i.speed }));
  const len = (sp: string) => intervals.filter((i) => i.speed === sp).reduce((s, i) => s + (i.to - i.from), 0);
  const total = Math.max(1, intervals.reduce((s, i) => s + (i.to - i.from), 0));
  const jam = len("TRAFFIC_JAM") / total, slow = len("SLOW") / total;
  const delayS = Math.max(0, durationS - staticS);
  const level = jam > 0.15 || delayS > 600 ? "heavy" : jam > 0 || slow > 0.25 || delayS > 180 ? "moderate" : "clear";
  return { level, delayS, segments };
}

export function shapeRoute(r: ComputedRoute, routeKey: string): RouteResponse {
  const steps = r.legs.flatMap((leg, legIndex) => [
    ...leg.steps.map((s: any) => ({
      instruction: s.navigationInstruction?.instructions ?? "Continue", maneuver: MANEUVER[s.navigationInstruction?.maneuver] ?? "straight",
      distanceM: s.distanceMeters ?? 0, durationS: secs(s.staticDuration), polyline: s.polyline?.encodedPolyline ?? "", legIndex,
    })),
    { instruction: "Arrive at your stop", maneuver: "arrive", distanceM: 0, durationS: 0, polyline: "", legIndex },
  ]);
  return { distanceM: r.distanceM, durationS: r.durationS, steps, polyline: r.polyline, source: `google_${r.mode.toLowerCase()}`, routeKey,
    traffic: trafficInfo(r.polyline, r.speedIntervals ?? [], r.durationS, r.staticDurationS ?? r.durationS), alternatives: r.alternatives ?? 0, computedAt: Date.now() };
}

/**
 * Decides whether a cached route can be reused (pure; unit-tested).
 * reuse  – same stops and the rider is still on (or at the start of) the route
 * wait   – rider left the route but rerouted too recently / too often this hour: keep the old route
 * fetch  – call the Routes API
 */
export function routeDecision(c: { cachedKey?: string; requestedKey: string; origin: LL; cachedPath: LL[]; cachedOrigin?: LL | null;
  lastAtMs?: number; callsLastHour: number; nowMs: number; cfg: { rerouteMinIntervalSec: number; rerouteMaxPerHour: number; offRouteMeters: number } }): "reuse" | "wait" | "fetch" {
  if (c.cachedKey && c.cachedKey === c.requestedKey) {
    const onRoute = distanceToPathM(c.origin, c.cachedPath) <= c.cfg.offRouteMeters ||
      (c.cachedOrigin ? haversineM(c.origin, c.cachedOrigin) <= c.cfg.offRouteMeters : false);
    if (onRoute) return "reuse";
    if (c.lastAtMs && c.nowMs - c.lastAtMs < c.cfg.rerouteMinIntervalSec * 1000) return "wait";
  }
  if (c.callsLastHour >= c.cfg.rerouteMaxPerHour) return c.cachedKey ? "wait" : "fetch";
  return "fetch";
}

/** Route for the rider's accepted jobs. Body: { origin, stops: [{orderId, type, lat, lng}], vehicle }. */
export const route_directions = onCall({ secrets: [MAPS_API_KEY] }, async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const origin = req.data?.origin;
  const stops = (Array.isArray(req.data?.stops) ? req.data.stops : []).slice(0, 10) as any[];
  if (!isLL(origin) || !stops.length || !stops.every((s) => isLL(s))) throw new HttpsError("invalid-argument", "Origin and stop coordinates are required.");
  const cfg = await loadConfig();

  // Only the rider's own accepted jobs can be routed.
  const [orders, parcels] = await Promise.all([
    db.collection("orders").where("riderUid", "==", uid).where("state", "in", ACTIVE_ORDER).get(),
    db.collection("parcels").where("riderUid", "==", uid).where("status", "in", ACTIVE_PARCEL).get(),
  ]);
  const allowed: { id: string; at: LL; type: string }[] = [];
  orders.docs.forEach((d) => { const o = d.data(); if (isLL(o.pickupGeo)) allowed.push({ id: d.id, at: toLL(o.pickupGeo), type: "PICKUP" }); if (isLL(o.dropoffGeo)) allowed.push({ id: d.id, at: toLL(o.dropoffGeo), type: "DROPOFF" }); });
  parcels.docs.forEach((d) => { const p = d.data(); if (isLL(p.pickup)) allowed.push({ id: d.id, at: toLL(p.pickup), type: "PICKUP" }); if (isLL(p.dropoff)) allowed.push({ id: d.id, at: toLL(p.dropoff), type: "DROPOFF" }); });
  if (!allowed.length) throw new HttpsError("failed-precondition", "Routes are available once you accept a job.");
  const matched = stops.map((s) => allowed.filter((a) => haversineM(a.at, toLL(s)) <= 75).sort((a, b) => haversineM(a.at, toLL(s)) - haversineM(b.at, toLL(s)))[0]);
  if (matched.some((m) => !m)) throw new HttpsError("permission-denied", "One of those stops isn't on your active jobs.");
  const routeKey = matched.map((m) => `${m!.id}:${m!.type}`).join(">");

  const ref = db.doc(`riderRoutes/${uid}`);
  const cur = (await ref.get()).data();
  const hourAgo = Date.now() - 3_600_000;
  const history = ((cur?.history ?? []) as number[]).filter((t) => t > hourAgo);
  const decision = routeDecision({ cachedKey: cur?.routeKey, requestedKey: routeKey, origin: toLL(origin), cachedPath: cur?.response?.polyline ? decodePolyline(cur.response.polyline) : [],
    cachedOrigin: cur?.origin ?? null, lastAtMs: (cur?.computedAt as Timestamp | undefined)?.toMillis(), callsLastHour: history.length, nowMs: Date.now(),
    cfg: { rerouteMinIntervalSec: Number(cfg.rerouteMinIntervalSec), rerouteMaxPerHour: Number(cfg.rerouteMaxPerHour), offRouteMeters: Number(cfg.offRouteMeters) } });
  if (decision !== "fetch" && cur?.response) return { ...cur.response, cached: true, throttled: decision === "wait" } as RouteResponse;

  const vehicle = String(req.data?.vehicle ?? "Motorbike").toLowerCase();
  const modes: TravelMode[] = vehicle.includes("bicycle") ? ["BICYCLE", "DRIVE"] : ["TWO_WHEELER", "DRIVE"];
  const r = await computeRoute([toLL(origin), ...matched.map((m) => m!.at)], cfg, modes);
  if (!r) {
    if (cur?.response && cur.routeKey === routeKey) return { ...cur.response, cached: true, throttled: true };
    throw new HttpsError("unavailable", "Road directions are unavailable right now.");
  }
  const res = shapeRoute(r, routeKey);
  await ref.set({ routeKey, origin: toLL(origin), response: res, computedAt: Timestamp.now(), history: [...history, Date.now()] });
  await writeOrderNavigation(matched.map((m) => m!), r, allowed);
  return res;
});

/** Stores the spec'd navigation summary on each order/parcel plus the polyline of the leg into its drop-off. */
async function writeOrderNavigation(stops: { id: string; type: string; at: LL }[], r: ComputedRoute, all: { id: string; type: string; at: LL }[]) {
  const b = db.batch();
  const byId = new Map<string, { pickup?: LL; dropoff?: LL; legIndex?: number }>();
  stops.forEach((s, i) => { const e = byId.get(s.id) ?? {}; if (s.type === "PICKUP") e.pickup = s.at; else { e.dropoff = s.at; e.legIndex = i; } byId.set(s.id, e); });
  for (const [id, e] of byId) {
    if (!e.dropoff) continue;
    e.pickup ??= all.find((a) => a.id === id && a.type === "PICKUP")?.at;
    const legs = r.legs.slice(0, (e.legIndex ?? 0) + 1);
    const leg = r.legs[e.legIndex ?? 0];
    const legPath = leg ? leg.steps.flatMap((s: any) => decodePolyline(s.polyline?.encodedPolyline ?? "")) : [];
    const navigation = { pickupLat: e.pickup?.lat ?? null, pickupLng: e.pickup?.lng ?? null, dropoffLat: e.dropoff.lat, dropoffLng: e.dropoff.lng,
      distanceKm: Math.round(legs.reduce((s, l) => s + l.distanceM, 0) / 100) / 10, estimatedMinutes: Math.max(1, Math.ceil(legs.reduce((s, l) => s + l.durationS, 0) / 60)),
      updatedAt: FieldValue.serverTimestamp() };
    b.set(db.doc(`${id.startsWith("PCL-") ? "parcels" : "orders"}/${id}`), { navigation, trackingPolyline: encodePolyline(legPath.filter((_, i) => i % 2 === 0 || i === legPath.length - 1)) }, { merge: true });
  }
  await b.commit().catch(() => undefined);
}
