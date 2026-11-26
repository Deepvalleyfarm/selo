/**
 * Shared address system on the approved Google Maps APIs (see maps/approved.ts):
 *  - Autocomplete: platform address store → Places API (New) predictions (Zambia only) → neighbourhood gazetteer.
 *  - Selecting a prediction: Geocoding API by place_id, once; the result is cached and the saved location keeps it forever.
 *  - Pin drop: nearby saved address first (free), then Geocoding API reverse lookup cached per ~11 m.
 *  - Distance/ETA/fee: Routes API computeRouteMatrix (cached), straight line × 1.3 labelled "approximate" as fallback.
 * Nothing here throws because Google is down, capped or switched off: the store and gazetteer still answer.
 */
import { createHash } from "node:crypto";
import { FieldValue } from "firebase-admin/firestore";
import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { MAPS_API_KEY, db, loadConfig } from "./config";
import { peekOtpToken } from "./account";
import { normaliseMsisdn } from "./domain";
import { geocodeAddress, geocodePlaceId, placesAutocomplete, reverseGeocode, routeMatrix } from "./maps/client";
import { geohash, haversineM, isLL, toLL } from "./maps/geo-math";
import { userWithinLimit } from "./maps/usage";
import { CITIES, cityById, cityOf, haversineKm, neighbourhoodFallback, type LL } from "./parcel-rules";

export const ROAD_FACTOR = 1.3;

export const normLabel = (s: string) => s.toLowerCase().normalize("NFKD").replace(/[^a-z0-9 ]+/g, " ").replace(/\s+/g, " ").trim();
export const searchTokens = (s: string) => [...new Set(normLabel(s).split(" ").filter((w) => w.length >= 2))].slice(0, 20)
  .flatMap((w) => [w, ...Array.from({ length: Math.max(0, Math.min(w.length, 8) - 2) }, (_, i) => w.slice(0, i + 2))]).filter((v, i, a) => a.indexOf(v) === i);

export interface Suggestion { id: string; label: string; secondary: string; source: "store" | "google" | "gazetteer"; lat?: number; lng?: number; distanceM?: number | null }
export interface Place { id: string; label: string; address: string; lat: number; lng: number; cityId: string | null; cityName: string | null; neighbourhood: string | null;
  inServiceArea: boolean; approximate: boolean; placeId?: string | null; landmark?: string | null }

function placeFor(label: string, address: string, at: LL, id: string, approximate: boolean, neighbourhood?: string | null, placeId?: string | null): Place {
  const city = cityOf(at);
  return { id, label, address, lat: at.lat, lng: at.lng, cityId: city?.id ?? null, cityName: city?.name ?? null,
    neighbourhood: neighbourhood ?? nearestNeighbourhood(at)?.name ?? null, inServiceArea: !!city, approximate, placeId: placeId ?? null };
}

/** Closest named neighbourhood in the gazetteer (uses stand-in positions when no surveyed centroid exists). */
export function nearestNeighbourhood(p: LL) {
  const city = cityOf(p); if (!city) return null;
  return city.neighbourhoods.map((name) => ({ name, d: haversineKm(p, neighbourhoodFallback(city, name)) })).sort((a, b) => a.d - b.d)[0] ?? null;
}

function gazetteer(q: string, cityId?: string | null): Suggestion[] {
  const n = normLabel(q); if (n.length < 2) return [];
  const out: Suggestion[] = [];
  for (const c of CITIES) {
    if (cityId && c.id !== cityId) continue;
    for (const nb of c.neighbourhoods) if (normLabel(nb).includes(n) || normLabel(`${nb} ${c.name}`).startsWith(n))
      out.push({ id: `nb:${c.id}:${nb}`, label: nb, secondary: `${c.name} · neighbourhood`, source: "gazetteer" });
    if (normLabel(c.name).startsWith(n)) out.push({ id: `nb:${c.id}:`, label: c.name, secondary: `${c.province} Province · city centre`, source: "gazetteer" });
  }
  return out.slice(0, 8);
}

/** Who is asking: a signed-in user, or someone mid-registration holding a verified (unconsumed) OTP token. */
export async function caller(req: CallableRequest): Promise<string> {
  if (req.auth?.uid) return req.auth.uid;
  const m = normaliseMsisdn(req.data?.preauth?.msisdn);
  if (m && (await peekOtpToken(m, req.data?.preauth?.otpToken))) return `pre_${m}`;
  throw new HttpsError("unauthenticated", "Please log in again.");
}

export async function autocomplete(input: string, who: string, cityId?: string | null, near?: LL | null, sessionToken?: string):
  Promise<{ suggestions: Suggestion[]; degraded: boolean }> {
  const cfg = await loadConfig();
  const q = input.trim().slice(0, 120);
  if (q.length < Number(cfg.mapsAutocompleteMinChars ?? 3)) return { suggestions: [], degraded: false };
  const first = normLabel(q).split(" ")[0]?.slice(0, 8) ?? "";
  const storeQ = db.collection("addresses").where("searchTokens", "array-contains", first);
  const store = await (cityId ? storeQ.where("cityId", "==", cityId) : storeQ).orderBy("useCount", "desc").limit(12).get().then((s) => s.docs).catch(() => []);
  // Every typed word must appear (not just the first), so "Arcades stall 14" doesn't return any "Arcades …".
  const words = normLabel(q).split(" ").filter((w) => w.length >= 2);
  const fromStore: Suggestion[] = store.filter((d) => { const hay = normLabel(`${d.get("label")} ${d.get("address")}`); return words.every((w) => hay.includes(w)); })
    .map((d) => ({ id: `addr:${d.id}`, label: d.get("label"), secondary: d.get("address"), source: "store", lat: d.get("lat"), lng: d.get("lng") }));
  // Enough strong matches from our own store? Skip Google entirely (free).
  let google: Suggestion[] = []; let degraded = false;
  // Enough strong matches nearby? Skip Google (free). Far-away matches don't count when we know where the user is.
  const nearbyStore = near ? fromStore.filter((s) => s.lat != null && haversineKm(near, { lat: s.lat!, lng: s.lng! }) <= 15) : fromStore;
  if (nearbyStore.length < 3) {
    const city = cityId ? cityById(cityId) : null;
    const bias = near ?? (city ? { lat: city.lat, lng: city.lng } : null);
    const limitOk = await userWithinLimit(who, "places_autocomplete", Number(cfg.mapsUserAutocompletePerHour ?? 0));
    const preds = limitOk ? await placesAutocomplete(q, cfg, { bias, radiusM: near ? 12_000 : (city?.radiusKm ?? 25) * 1000, sessionToken, origin: near ?? null }) : null;
    degraded = preds === null;
    google = (preds ?? []).map((p) => ({ id: `g:${p.placeId}`, label: p.primary, secondary: p.secondary, source: "google" as const, distanceM: p.distanceM ?? null }));
  }
  // Straight-line distance from the user for every suggestion we know the position of.
  const withDist = (s: Suggestion): Suggestion => {
    if (s.distanceM != null || !near) return s;
    if (s.lat != null && s.lng != null) return { ...s, distanceM: Math.round(haversineKm(near, { lat: s.lat, lng: s.lng }) * 1000) };
    if (s.id.startsWith("nb:")) { const [, cid, nb] = s.id.split(":"); const c = cityById(cid);
      if (c) { const p = nb ? neighbourhoodFallback(c, nb) : { lat: c.lat, lng: c.lng }; return { ...s, distanceM: Math.round(haversineKm(near, p) * 1000) }; } }
    return s;
  };
  // Our own stored places nearest-first (Google already ranks its own by relevance around the user).
  const storeRanked = fromStore.map(withDist).sort((a, b) => (a.distanceM ?? Infinity) - (b.distanceM ?? Infinity));
  const merged = [...storeRanked, ...google.map(withDist), ...gazetteer(q, cityId).map(withDist)];
  const seen = new Set<string>();
  return { suggestions: merged.filter((s) => { const k = normLabel(s.label + s.secondary); if (seen.has(k)) return false; seen.add(k); return true; }).slice(0, 10), degraded };
}

/** Resolves a suggestion to a point. Google predictions are geocoded once by place_id (Geocoding API, cached). */
export async function details(id: string, who = "system"): Promise<Place> {
  const cfg = await loadConfig();
  if (id.startsWith("addr:")) {
    const d = (await db.doc(`addresses/${id.slice(5)}`).get()).data();
    if (!d) throw new HttpsError("not-found", "That address is no longer available.");
    return placeFor(d.label, d.address, { lat: d.lat, lng: d.lng }, id, !!d.approximate, d.neighbourhood, d.placeId ?? null);
  }
  if (id.startsWith("nb:")) {
    const [, cityId, nb] = id.split(":"); const city = cityById(cityId);
    if (!city) throw new HttpsError("not-found", "Unknown city.");
    const label = nb || city.name;
    const g = nb ? await geocodeAddress(`${nb}, ${city.name}, Zambia`, cfg) : null;
    const gl = g ? { lat: g.latitude, lng: g.longitude } : null;
    const at = gl && cityOf(gl)?.id === city.id ? gl : nb ? neighbourhoodFallback(city, nb) : { lat: city.lat, lng: city.lng };
    return placeFor(label, `${label}${nb ? ", " + city.name : ""}`, at, id, !gl, nb || null);
  }
  if (id.startsWith("g:")) {
    const placeId = id.slice(2);
    // Same place saved before? Use the stored coordinates — never geocode the same place twice.
    const known = await db.collection("addresses").where("placeId", "==", placeId).limit(1).get().catch(() => null);
    const k = known?.docs[0];
    if (k) return placeFor(k.get("label"), k.get("address"), { lat: k.get("lat"), lng: k.get("lng") }, `addr:${k.id}`, false, k.get("neighbourhood"), placeId);
    if (!(await userWithinLimit(who, "geocoding", Number(cfg.mapsUserGeocodePerHour ?? 0))))
      throw new HttpsError("resource-exhausted", "Too many address lookups. Pick the spot on the map instead.");
    const g = await geocodePlaceId(placeId, cfg);
    if (!g) throw new HttpsError("unavailable", "We couldn't load that address. Try again or pick it on the map.");
    const short = g.address.split(",")[0];
    return placeFor(short, g.address, { lat: g.latitude, lng: g.longitude }, id, false, null, placeId);
  }
  throw new HttpsError("invalid-argument", "Unknown address.");
}

/** Pin drop: a saved address within 25 m wins (free); otherwise one cached reverse geocode; otherwise the gazetteer. */
export async function reverse(at: LL): Promise<Place> {
  const pinId = `pin:${at.lat.toFixed(6)},${at.lng.toFixed(6)}`;
  const near = await db.collection("addresses").where("cell", "==", `${at.lat.toFixed(3)},${at.lng.toFixed(3)}`).limit(30).get().catch(() => null);
  const best = near?.docs.map((d) => ({ d, m: haversineM(at, { lat: d.get("lat"), lng: d.get("lng") }) })).sort((a, b) => a.m - b.m)[0];
  if (best && best.m <= 25) return { ...placeFor(best.d.get("label"), best.d.get("address"), at, pinId, false, best.d.get("neighbourhood"), best.d.get("placeId") ?? null),
    landmark: best.m <= 10 ? best.d.get("label") : (best.d.get("landmark") ?? null) };
  const g = await reverseGeocode(at, await loadConfig());
  if (g) return { ...placeFor(g.landmark ?? g.address.split(",")[0], g.address, at, pinId, false, null, g.placeId), landmark: g.landmark ?? null };
  const nb = nearestNeighbourhood(at); const city = cityOf(at);
  const label = nb ? `Pinned location near ${nb.name}` : "Pinned location";
  return placeFor(label, city ? `${nb?.name ? nb.name + ", " : ""}${city.name}` : `${at.lat.toFixed(5)}, ${at.lng.toFixed(5)}`, at, pinId, false, nb?.name ?? null);
}

/** Road distance/duration (Routes API matrix, cached); falls back to straight line × 1.3 and says so. */
export async function routeDistance(from: LL, to: LL): Promise<{ distanceKm: number; durationMin: number; approximate: boolean }> {
  const r = await routeMatrix(from, to, await loadConfig());
  if (r) return { distanceKm: Math.round(r.distanceM / 100) / 10, durationMin: Math.max(1, Math.ceil(r.durationS / 60)), approximate: false };
  const km = Math.round(haversineKm(from, to) * ROAD_FACTOR * 10) / 10;
  return { distanceKm: km, durationMin: Math.max(1, Math.ceil((km / 25) * 60)), approximate: true };
}

/** Platform address store with de-duplication: same name within 30 m, the same Google place, or any entry within 8 m. */
export async function saveAddress(uid: string, p: { label: string; address: string; lat: number; lng: number; source?: string; approximate?: boolean; placeId?: string | null }) {
  const at = toLL(p);
  const city = cityOf(at);
  if (!city) throw new HttpsError("failed-precondition", "That location is outside our service area.");
  const label = p.label.trim().slice(0, 80) || "Pinned location";
  const cell = `${at.lat.toFixed(3)},${at.lng.toFixed(3)}`; // ~110 m buckets for the dedupe lookup
  const near = await db.collection("addresses").where("cell", "==", cell).limit(50).get();
  const dup = near.docs.find((d) => {
    const m = haversineKm(at, { lat: d.get("lat"), lng: d.get("lng") }) * 1000;
    return m <= 8 || (m <= 30 && normLabel(d.get("label")) === normLabel(label)) || (!!p.placeId && d.get("placeId") === p.placeId && m <= 150);
  });
  if (dup) {
    await dup.ref.update({ useCount: FieldValue.increment(1), lastUsedAt: FieldValue.serverTimestamp(), usedBy: FieldValue.arrayUnion(uid),
      ...(p.placeId && !dup.get("placeId") ? { placeId: p.placeId } : {}) });
    return { id: dup.id, deduplicated: true };
  }
  const id = createHash("sha1").update(`${cell}|${normLabel(label)}`).digest("hex").slice(0, 20);
  const nb = nearestNeighbourhood(at);
  await db.doc(`addresses/${id}`).set({ label, address: p.address.trim().slice(0, 200), lat: at.lat, lng: at.lng, cell, cityId: city.id, cityName: city.name,
    neighbourhood: nb?.name ?? null, searchTokens: searchTokens(`${label} ${p.address} ${nb?.name ?? ""}`), source: p.source ?? "user", approximate: !!p.approximate,
    placeId: p.placeId ?? null, createdBy: uid, createdAt: FieldValue.serverTimestamp(), useCount: 1, usedBy: [uid], lastUsedAt: FieldValue.serverTimestamp() }, { merge: true });
  return { id, deduplicated: false };
}

/**
 * The permanent location on a user's record: users/{uid}.location = { address, latitude, longitude } (+ placeId,
 * source). Sellers also get the public stores/{uid} doc (store markers, "shops near me"). Saving the same place again
 * is a no-op, so coordinates are never looked up twice.
 */
export async function saveUserLocation(uid: string, role: string, p: { address: string; label?: string; latitude: number; longitude: number; placeId?: string | null; source?: string }) {
  const at = { lat: p.latitude, lng: p.longitude };
  if (!isLL(at)) throw new HttpsError("invalid-argument", "Location required.");
  const city = cityOf(at);
  if (!city) throw new HttpsError("failed-precondition", "That location is outside the cities we serve.");
  const ref = db.doc(`users/${uid}`);
  const cur = (await ref.get()).data() ?? {};
  const same = cur.location && ((p.placeId && cur.location.placeId === p.placeId) ||
    haversineM({ lat: cur.location.latitude, lng: cur.location.longitude }, at) < 3);
  const location = { address: p.address.slice(0, 200), latitude: at.lat, longitude: at.lng, placeId: p.placeId ?? null, source: p.source ?? "user",
    cityId: city.id, neighbourhood: nearestNeighbourhood(at)?.name ?? null, updatedAt: FieldValue.serverTimestamp() };
  if (!same) await ref.set({ location, geo: at, landmark: cur.landmark ?? p.label ?? null }, { merge: true });
  if (role === "SELLER") await db.doc(`stores/${uid}`).set({ name: cur.storeName ?? cur.fullName ?? "", address: location.address, latitude: at.lat, longitude: at.lng,
    geohash: geohash(at), geohash5: geohash(at, 5), cityId: city.id, sellerUid: uid, active: cur.accountStatus === "ACTIVE", updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  await saveAddress(uid, { label: p.label ?? p.address.split(",")[0], address: p.address, lat: at.lat, lng: at.lng, source: p.source, placeId: p.placeId });
  return { ok: true, unchanged: !!same, cityId: city.id };
}

/* ------------------------------------------------------------------ callables */

const opt = { secrets: [MAPS_API_KEY] };
export const geo_autocomplete = onCall(opt, async (req) => {
  const who = await caller(req);
  return autocomplete(String(req.data?.input ?? ""), who, typeof req.data?.cityId === "string" ? req.data.cityId : null,
    isLL(req.data?.near) ? toLL(req.data.near) : null, typeof req.data?.sessionToken === "string" ? req.data.sessionToken.slice(0, 64) : undefined);
});
export const geo_details = onCall(opt, async (req) => { const who = await caller(req); return details(String(req.data?.id ?? ""), who); });
export const geo_reverse = onCall(opt, async (req) => {
  await caller(req);
  if (!isLL(req.data)) throw new HttpsError("invalid-argument", "Location required.");
  return reverse(toLL(req.data));
});
export const geo_distance = onCall(opt, async (req) => {
  await caller(req);
  if (!isLL(req.data?.from) || !isLL(req.data?.to)) throw new HttpsError("invalid-argument", "Both points are required.");
  const from = toLL(req.data.from), to = toLL(req.data.to);
  const r = await routeDistance(from, to);
  return { ...r, sameCity: cityOf(from)?.id === cityOf(to)?.id && !!cityOf(from), fromCity: cityOf(from)?.name ?? null, toCity: cityOf(to)?.name ?? null };
});
export const address_save = onCall(async (req) => {
  const uid = req.auth?.uid; if (!uid) throw new HttpsError("unauthenticated", "Please log in again.");
  const d = (req.data ?? {}) as Record<string, any>;
  if (!isLL(d as unknown)) throw new HttpsError("invalid-argument", "Location required.");
  return saveAddress(uid, { label: String(d.label ?? ""), address: String(d.address ?? ""), lat: Number(d.lat), lng: Number(d.lng),
    source: typeof d.source === "string" ? d.source.slice(0, 20) : "user", approximate: d.approximate === true, placeId: typeof d.placeId === "string" ? d.placeId : null });
});
/** Buyer delivery address, seller store location, agent / rider base location. */
export const location_save = onCall(async (req) => {
  const uid = req.auth?.uid; if (!uid) throw new HttpsError("unauthenticated", "Please log in again.");
  const d = (req.data ?? {}) as Record<string, any>;
  return saveUserLocation(uid, String(req.auth?.token.role ?? ""), { address: String(d.address ?? "").trim() || "Pinned location", label: typeof d.label === "string" ? d.label : undefined,
    latitude: Number(d.latitude ?? d.lat), longitude: Number(d.longitude ?? d.lng), placeId: typeof d.placeId === "string" ? d.placeId : null, source: typeof d.source === "string" ? d.source.slice(0, 20) : "user" });
});
