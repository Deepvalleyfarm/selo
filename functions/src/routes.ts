/**
 * Rider multi-stop routing (PRD 6.5 + route planning).
 * The app orders the stops itself (every pickup before its drop-off); these callables geocode stops that have no
 * coordinates and fetch real road directions with turn-by-turn steps from the Google Maps Routes API.
 * The Maps key stays server-side in Secret Manager (MAPS_API_KEY); the app never sees it.
 */
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions/v2";
import { MAPS_API_KEY, db } from "./config";
import { requireUser } from "./util";

type LL = { lat: number; lng: number };
const isLL = (v: any): v is LL => v && Number.isFinite(v.lat) && Number.isFinite(v.lng) && Math.abs(v.lat) <= 90 && Math.abs(v.lng) <= 180;

/** Geocodes a Zambian address. Results are cached in geocodeCache so the same stall/landmark is looked up once. */
export async function geocode(address: string, key: string): Promise<LL | null> {
  const norm = address.trim().toLowerCase().replace(/\s+/g, " ").slice(0, 200);
  if (!norm) return null;
  const cacheId = Buffer.from(norm).toString("base64url").slice(0, 300);
  const cached = await db.doc(`geocodeCache/${cacheId}`).get();
  if (cached.exists) return cached.get("at") ?? null;
  const url = `https://maps.googleapis.com/maps/api/geocode/json?address=${encodeURIComponent(address)}&region=zm&components=country:ZM&key=${key}`;
  const r = await fetch(url).then((x) => x.json()).catch(() => null) as { results?: { geometry: { location: LL } }[] } | null;
  const at = r?.results?.[0]?.geometry?.location ?? null;
  await db.doc(`geocodeCache/${cacheId}`).set({ address: norm, at, cachedAt: Date.now() });
  return at;
}

/** Pickup and drop-off coordinates for the rider's own active orders; geocodes and stores any that are missing. */
export const route_locate = onCall({ secrets: [MAPS_API_KEY] }, async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const ids = (Array.isArray(req.data?.orderIds) ? req.data.orderIds : []).filter((x: unknown) => typeof x === "string").slice(0, 8) as string[];
  const out: Record<string, { pickup: LL | null; dropoff: LL | null }> = {};
  for (const id of ids) {
    const ref = db.doc(`orders/${id}`);
    const o = (await ref.get()).data();
    if (!o || o.riderUid !== uid) continue;
    let pickup = isLL(o.pickupGeo) ? o.pickupGeo : null, dropoff = isLL(o.dropoffGeo) ? o.dropoffGeo : null;
    const key = MAPS_API_KEY.value();
    if (!pickup && o.pickup && key) { pickup = await geocode(`${o.pickup}, ${o.zoneName ?? ""}, Zambia`, key); if (pickup) await ref.update({ pickupGeo: pickup }); }
    if (!dropoff && o.dropoff && key) { dropoff = await geocode(`${o.dropoff}, Zambia`, key); if (dropoff) await ref.update({ dropoffGeo: dropoff }); }
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

/** Calls Routes API computeRoutes once; TWO_WHEELER where Google supports it, otherwise DRIVE. */
async function computeRoutes(key: string, waypoints: any[], mode: "TWO_WHEELER" | "DRIVE" | "BICYCLE") {
  const [origin, ...rest] = waypoints;
  const body = {
    origin, destination: rest[rest.length - 1], intermediates: rest.slice(0, -1), travelMode: mode,
    ...(mode === "BICYCLE" ? {} : { routingPreference: "TRAFFIC_AWARE" }), languageCode: "en-GB", units: "METRIC", polylineQuality: "HIGH_QUALITY",
  };
  const res = await fetch("https://routes.googleapis.com/directions/v2:computeRoutes", {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Goog-Api-Key": key,
      "X-Goog-FieldMask": "routes.distanceMeters,routes.duration,routes.legs.steps.distanceMeters,routes.legs.steps.staticDuration,routes.legs.steps.polyline.encodedPolyline,routes.legs.steps.navigationInstruction" },
    body: JSON.stringify(body),
  });
  const json = await res.json().catch(() => ({})) as any;
  return { ok: res.ok && Array.isArray(json.routes) && json.routes.length > 0, json, status: res.status };
}

export const route_directions = onCall({ secrets: [MAPS_API_KEY] }, async (req) => {
  requireUser(req, "RIDER");
  const origin = req.data?.origin;
  const stops = (Array.isArray(req.data?.stops) ? req.data.stops : []).slice(0, 10);
  if (!isLL(origin) || !stops.length) throw new HttpsError("invalid-argument", "Origin and at least one stop are required.");
  const key = MAPS_API_KEY.value();
  if (!key) throw new HttpsError("failed-precondition", "Turn-by-turn directions aren't configured yet.");
  const wp = (s: any) => (isLL(s) ? { location: { latLng: { latitude: s.lat, longitude: s.lng } } } : { address: `${String(s?.address ?? "").slice(0, 200)}, Zambia` });
  const waypoints = [wp(origin), ...stops.map(wp)];
  const vehicle = String(req.data?.vehicle ?? "Motorbike").toLowerCase();
  const modes: ("TWO_WHEELER" | "DRIVE" | "BICYCLE")[] = vehicle.includes("bicycle") ? ["BICYCLE", "DRIVE"] : ["TWO_WHEELER", "DRIVE"];
  let r: Awaited<ReturnType<typeof computeRoutes>> | null = null, used = modes[0];
  for (const m of modes) { r = await computeRoutes(key, waypoints, m); used = m; if (r.ok) break; }
  if (!r?.ok) { logger.warn("routes api failed", r?.status, JSON.stringify(r?.json).slice(0, 500)); throw new HttpsError("unavailable", "Directions are unavailable right now."); }
  const route = r.json.routes[0];
  const steps = (route.legs ?? []).flatMap((leg: any, legIndex: number) => [
    ...(leg.steps ?? []).map((s: any) => ({
      instruction: s.navigationInstruction?.instructions ?? "Continue", maneuver: MANEUVER[s.navigationInstruction?.maneuver] ?? "straight",
      distanceM: s.distanceMeters ?? 0, durationS: secs(s.staticDuration), polyline: s.polyline?.encodedPolyline ?? "", legIndex,
    })),
    { instruction: "Arrive at your stop", maneuver: "arrive", distanceM: 0, durationS: 0, polyline: "", legIndex },
  ]);
  return { distanceM: route.distanceMeters ?? 0, durationS: secs(route.duration), steps, source: `google_${used.toLowerCase()}` };
});
