/**
 * Google Maps Platform service layer (server side; the key never reaches the apps).
 *
 * Each function: 1) answers from cache when it can, 2) checks the kill switch and daily cap, 3) calls exactly one
 * approved endpoint with the smallest field mask, 4) caches the result, 5) meters the call. Every function returns
 * null instead of throwing when Google is off, capped, slow or failing, so callers always have a fallback path.
 */
import { MAPS_API_KEY } from "../config";
import { assertApproved, type MapsSku } from "./approved";
import { cacheGet, cacheSet, once } from "./cache";
import { cell, type LL } from "./geo-math";
import { allowed, flush, record } from "./usage";

const TIMEOUT_MS = 4_500;
type Cfg = Record<string, any>;

export function mapsKey(): string {
  try { return MAPS_API_KEY.value() || process.env.MAPS_API_KEY || ""; } catch { return process.env.MAPS_API_KEY || ""; }
}

/** The one place a request to Google is made. */
export async function googleFetch(method: "GET" | "POST", url: string, init: { body?: unknown; fieldMask?: string } = {}): Promise<{ ok: boolean; status: number; json: any }> {
  const sku = assertApproved(method, url);
  const key = mapsKey();
  if (!key) return { ok: false, status: 0, json: null };
  const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), TIMEOUT_MS);
  const started = Date.now();
  try {
    const isNew = !url.startsWith("https://maps.googleapis.com/");
    const r = await fetch(isNew ? url : `${url}&key=${encodeURIComponent(key)}`, {
      method, signal: ctl.signal,
      headers: isNew ? { "Content-Type": "application/json", "X-Goog-Api-Key": key, ...(init.fieldMask ? { "X-Goog-FieldMask": init.fieldMask } : {}) } : undefined,
      body: init.body ? JSON.stringify(init.body) : undefined,
    });
    const json: any = await r.json().catch(() => null);
    const ok = r.ok && (isNew || json?.status === "OK" || json?.status === "ZERO_RESULTS");
    record(sku, { ok, latencyMs: Date.now() - started });
    return { ok, status: r.status, json };
  } catch {
    record(sku, { ok: false, latencyMs: Date.now() - started });
    return { ok: false, status: 0, json: null };
  } finally { clearTimeout(t); void flush(); }
}

async function gate(sku: MapsSku, cfg: Cfg): Promise<boolean> {
  if (!mapsKey()) { record(sku, { fallback: true }); return false; }
  if (!(await allowed(sku, cfg))) { record(sku, { capped: true }); return false; }
  return true;
}

/* -------------------------------------------------------------------------------- Places API (New) */

export interface Prediction { placeId: string; primary: string; secondary: string }

/** Autocomplete predictions limited to Zambia, biased to the user's city. Only text + place id; no details. */
export async function placesAutocomplete(input: string, cfg: Cfg, opt: { bias?: LL | null; radiusM?: number; sessionToken?: string } = {}): Promise<Prediction[] | null> {
  const q = input.trim().replace(/\s+/g, " ").slice(0, 120);
  if (q.length < Number(cfg.mapsAutocompleteMinChars ?? 3)) return [];
  const key = `${q.toLowerCase()}|${opt.bias ? cell(opt.bias, 2) : "zm"}`; // ~1 km bias buckets
  const hit = await cacheGet<Prediction[]>("ac", key);
  if (hit) { record("places_autocomplete", { cacheHit: true }); return hit; }
  return once(`ac:${key}`, async () => {
    if (!(await gate("places_autocomplete", cfg))) return null;
    const r = await googleFetch("POST", "https://places.googleapis.com/v1/places:autocomplete", {
      fieldMask: "suggestions.placePrediction.placeId,suggestions.placePrediction.structuredFormat",
      body: { input: q, includedRegionCodes: ["zm"], regionCode: "zm", languageCode: "en", includeQueryPredictions: false,
        ...(opt.sessionToken ? { sessionToken: opt.sessionToken } : {}),
        ...(opt.bias ? { locationBias: { circle: { center: { latitude: opt.bias.lat, longitude: opt.bias.lng }, radius: Math.min(50_000, opt.radiusM ?? 25_000) } } } : {}) },
    });
    if (!r.ok) return null;
    const out: Prediction[] = (r.json?.suggestions ?? []).map((s: any) => s.placePrediction).filter(Boolean).slice(0, 6).map((p: any) => ({
      placeId: p.placeId, primary: p.structuredFormat?.mainText?.text ?? "", secondary: p.structuredFormat?.secondaryText?.text ?? "" }));
    await cacheSet("ac", key, out, Math.min(7, cfg.mapsCacheDays ?? 7));
    return out;
  });
}

/* -------------------------------------------------------------------------------- Geocoding API */

export interface Geocoded { placeId: string | null; address: string; latitude: number; longitude: number }

function firstResult(j: any): Geocoded | null {
  const r = j?.results?.[0]; const loc = r?.geometry?.location;
  if (!r || !Number.isFinite(loc?.lat)) return null;
  return { placeId: r.place_id ?? null, address: r.formatted_address ?? "", latitude: loc.lat, longitude: loc.lng };
}

/** place_id → formatted address + coordinates. Called once when a user saves a location; cached 30 days. */
export async function geocodePlaceId(placeId: string, cfg: Cfg): Promise<Geocoded | null> {
  const hit = await cacheGet<Geocoded>("gp", placeId);
  if (hit) { record("geocoding", { cacheHit: true }); return hit; }
  return once(`gp:${placeId}`, async () => {
    if (!(await gate("geocoding", cfg))) return null;
    const r = await googleFetch("GET", `https://maps.googleapis.com/maps/api/geocode/json?place_id=${encodeURIComponent(placeId)}&language=en`);
    const g = r.ok ? firstResult(r.json) : null;
    if (g) await cacheSet("gp", placeId, g, cfg.mapsCacheDays ?? 30);
    return g;
  });
}

/** Free-text address (legacy orders without coordinates). */
export async function geocodeAddress(text: string, cfg: Cfg): Promise<Geocoded | null> {
  const norm = text.trim().toLowerCase().replace(/\s+/g, " ").slice(0, 200);
  if (!norm) return null;
  const hit = await cacheGet<Geocoded | false>("ga", norm);
  if (hit !== undefined) { record("geocoding", { cacheHit: true }); return hit || null; }
  return once(`ga:${norm}`, async () => {
    if (!(await gate("geocoding", cfg))) return null;
    const r = await googleFetch("GET", `https://maps.googleapis.com/maps/api/geocode/json?address=${encodeURIComponent(text)}&components=country:ZM&region=zm`);
    if (!r.ok) return null;
    const g = firstResult(r.json);
    await cacheSet("ga", norm, g ?? false, cfg.mapsCacheDays ?? 30); // negative results cached too
    return g;
  });
}

/** Coordinates → address (map pin drop). Cached per ~11 m cell. */
export async function reverseGeocode(at: LL, cfg: Cfg): Promise<Geocoded | null> {
  const key = cell(at, 4);
  const hit = await cacheGet<Geocoded | false>("rv", key);
  if (hit !== undefined) { record("geocoding", { cacheHit: true }); return hit ? { ...hit, latitude: at.lat, longitude: at.lng } : null; }
  return once(`rv:${key}`, async () => {
    if (!(await gate("geocoding", cfg))) return null;
    const r = await googleFetch("GET", `https://maps.googleapis.com/maps/api/geocode/json?latlng=${at.lat},${at.lng}&result_type=street_address|premise|route|sublocality|neighborhood&language=en`);
    if (!r.ok) return null;
    const g = firstResult(r.json);
    await cacheSet("rv", key, g ?? false, cfg.mapsCacheDays ?? 30);
    return g ? { ...g, latitude: at.lat, longitude: at.lng } : null; // keep the user's exact pin
  });
}

/* -------------------------------------------------------------------------------- Routes API */

export type TravelMode = "TWO_WHEELER" | "DRIVE" | "BICYCLE";
const secs = (d?: string) => (d ? Math.round(parseFloat(d)) : 0);

/**
 * Road distance + duration for one origin→destination pair (Compute Route Matrix, 1 element, Essentials).
 * Used for delivery fees and ETAs before a rider exists. Cached per ~11 m cell pair.
 */
export async function routeMatrix(from: LL, to: LL, cfg: Cfg, mode: TravelMode = "TWO_WHEELER"): Promise<{ distanceM: number; durationS: number } | null> {
  const key = `${cell(from)}>${cell(to)}|${mode}`;
  const hit = await cacheGet<{ distanceM: number; durationS: number }>("rm", key);
  if (hit) { record("routes_matrix", { cacheHit: true }); return hit; }
  return once(`rm:${key}`, async () => {
    if (!(await gate("routes_matrix", cfg))) return null;
    const wp = (p: LL) => ({ waypoint: { location: { latLng: { latitude: p.lat, longitude: p.lng } } } });
    for (const m of mode === "TWO_WHEELER" ? (["TWO_WHEELER", "DRIVE"] as TravelMode[]) : [mode]) {
      const r = await googleFetch("POST", "https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix", {
        fieldMask: "originIndex,destinationIndex,distanceMeters,duration,condition",
        body: { origins: [wp(from)], destinations: [wp(to)], travelMode: m, ...(m === "BICYCLE" ? {} : { routingPreference: "TRAFFIC_UNAWARE" }) },
      });
      const el = Array.isArray(r.json) ? r.json[0] : null;
      if (r.ok && el?.condition === "ROUTE_EXISTS") {
        const v = { distanceM: el.distanceMeters ?? 0, durationS: secs(el.duration) };
        await cacheSet("rm", key, v, Math.min(7, cfg.mapsCacheDays ?? 7)); // roads change; keep a week
        return v;
      }
    }
    return null;
  });
}

export interface ComputedRoute { distanceM: number; durationS: number; polyline: string; legs: { distanceM: number; durationS: number; steps: any[] }[]; mode: TravelMode }

/** Full route with turn-by-turn steps (after acceptance only). TRAFFIC_UNAWARE unless the admin turns traffic on (Pro SKU). */
export async function computeRoute(points: LL[], cfg: Cfg, modes: TravelMode[]): Promise<ComputedRoute | null> {
  if (points.length < 2 || points.length > 12) return null;
  if (!(await gate("routes_compute", cfg))) return null;
  const wp = (p: LL) => ({ location: { latLng: { latitude: p.lat, longitude: p.lng } } });
  const [origin, ...rest] = points;
  for (const m of modes) {
    const r = await googleFetch("POST", "https://routes.googleapis.com/directions/v2:computeRoutes", {
      fieldMask: "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline,routes.legs.distanceMeters,routes.legs.duration," +
        "routes.legs.steps.distanceMeters,routes.legs.steps.staticDuration,routes.legs.steps.polyline.encodedPolyline,routes.legs.steps.navigationInstruction",
      body: { origin: wp(origin), destination: wp(rest[rest.length - 1]), intermediates: rest.slice(0, -1).map(wp), travelMode: m,
        ...(m === "BICYCLE" ? {} : { routingPreference: cfg.routesTrafficAware ? "TRAFFIC_AWARE" : "TRAFFIC_UNAWARE" }),
        languageCode: "en-GB", units: "METRIC", polylineQuality: "OVERVIEW" },
    });
    const route = r.ok ? r.json?.routes?.[0] : null;
    if (route) return {
      distanceM: route.distanceMeters ?? 0, durationS: secs(route.duration), polyline: route.polyline?.encodedPolyline ?? "", mode: m,
      legs: (route.legs ?? []).map((l: any) => ({ distanceM: l.distanceMeters ?? 0, durationS: secs(l.duration), steps: l.steps ?? [] })),
    };
  }
  return null;
}
