/**
 * Shared address system: autocomplete, place details, reverse geocoding, route distance and the platform address store.
 * Google Maps Platform is called server-side (MAPS_API_KEY never reaches the apps). Without a key, or when Google is
 * slow or down, the store and the built-in neighbourhood gazetteer still answer, and distances fall back to straight
 * line × road factor, labelled approximate.
 */
import { createHash } from "node:crypto";
import { FieldValue } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { MAPS_API_KEY, db } from "./config";
import { CITIES, cityById, cityOf, haversineKm, neighbourhoodFallback, type LL } from "./parcel-rules";
import { requireUser, str } from "./util";

export const ROAD_FACTOR = 1.3;
const TIMEOUT_MS = 4_000;
const isLL = (v: any): v is LL => v && Number.isFinite(Number(v.lat)) && Number.isFinite(Number(v.lng)) && Math.abs(v.lat) <= 90 && Math.abs(v.lng) <= 180;
const toLL = (v: any): LL => ({ lat: Number(v.lat), lng: Number(v.lng) });
function key(): string { try { return MAPS_API_KEY.value() || process.env.MAPS_API_KEY || ""; } catch { return process.env.MAPS_API_KEY || ""; } }

async function getJson(url: string, init?: RequestInit): Promise<any | null> {
  const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), TIMEOUT_MS);
  try { const r = await fetch(url, { ...init, signal: ctl.signal }); return r.ok ? await r.json() : null; } catch { return null; } finally { clearTimeout(t); }
}

export const normLabel = (s: string) => s.toLowerCase().normalize("NFKD").replace(/[^a-z0-9 ]+/g, " ").replace(/\s+/g, " ").trim();
export const searchTokens = (s: string) => [...new Set(normLabel(s).split(" ").filter((w) => w.length >= 2))].slice(0, 20)
  .flatMap((w) => [w, ...Array.from({ length: Math.max(0, Math.min(w.length, 8) - 2) }, (_, i) => w.slice(0, i + 2))]).filter((v, i, a) => a.indexOf(v) === i);

export interface Suggestion { id: string; label: string; secondary: string; source: "store" | "google" | "gazetteer"; lat?: number; lng?: number }
export interface Place { id: string; label: string; address: string; lat: number; lng: number; cityId: string | null; cityName: string | null; neighbourhood: string | null;
  inServiceArea: boolean; approximate: boolean }

function placeFor(label: string, address: string, at: LL, id: string, approximate: boolean, neighbourhood?: string | null): Place {
  const city = cityOf(at);
  return { id, label, address, lat: at.lat, lng: at.lng, cityId: city?.id ?? null, cityName: city?.name ?? null,
    neighbourhood: neighbourhood ?? nearestNeighbourhood(at)?.name ?? null, inServiceArea: !!city, approximate };
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

export async function autocomplete(input: string, cityId?: string | null, near?: LL | null): Promise<{ suggestions: Suggestion[]; degraded: boolean }> {
  const q = input.trim().slice(0, 120);
  if (q.length < 2) return { suggestions: [], degraded: false };
  const tok = searchTokens(q)[0];
  const storeQ = db.collection("addresses").where("searchTokens", "array-contains", normLabel(q).split(" ")[0]?.slice(0, 8) ?? tok);
  const [store, google] = await Promise.all([
    (cityId ? storeQ.where("cityId", "==", cityId) : storeQ).orderBy("useCount", "desc").limit(6).get().then((s) => s.docs).catch(() => []),
    (async () => {
      const k = key(); if (!k) return null;
      const city = cityId ? cityById(cityId) : null; const bias = near ?? (city ? { lat: city.lat, lng: city.lng } : null);
      const url = `https://maps.googleapis.com/maps/api/place/autocomplete/json?input=${encodeURIComponent(q)}&components=country:zm&key=${k}` +
        (bias ? `&location=${bias.lat},${bias.lng}&radius=${(city?.radiusKm ?? 20) * 1000}` : "");
      const j = await getJson(url);
      return j?.status === "OK" || j?.status === "ZERO_RESULTS" ? (j.predictions ?? []) : null;
    })(),
  ]);
  const fromStore: Suggestion[] = store.filter((d) => normLabel(`${d.get("label")} ${d.get("address")}`).includes(normLabel(q).split(" ")[0] ?? ""))
    .map((d) => ({ id: `addr:${d.id}`, label: d.get("label"), secondary: d.get("address"), source: "store", lat: d.get("lat"), lng: d.get("lng") }));
  const fromGoogle: Suggestion[] = (google ?? []).slice(0, 6).map((p: any) => ({ id: `g:${p.place_id}`, label: p.structured_formatting?.main_text ?? p.description,
    secondary: p.structured_formatting?.secondary_text ?? "", source: "google" }));
  const merged = [...fromStore, ...fromGoogle, ...gazetteer(q, cityId)];
  const seen = new Set<string>();
  return { suggestions: merged.filter((s) => { const k = normLabel(s.label + s.secondary); if (seen.has(k)) return false; seen.add(k); return true; }).slice(0, 10),
    degraded: !!key() && google === null };
}

export async function details(id: string): Promise<Place> {
  if (id.startsWith("addr:")) {
    const d = (await db.doc(`addresses/${id.slice(5)}`).get()).data();
    if (!d) throw new HttpsError("not-found", "That address is no longer available.");
    return placeFor(d.label, d.address, { lat: d.lat, lng: d.lng }, id, !!d.approximate, d.neighbourhood);
  }
  if (id.startsWith("nb:")) {
    const [, cityId, nb] = id.split(":"); const city = cityById(cityId);
    if (!city) throw new HttpsError("not-found", "Unknown city.");
    const label = nb || city.name;
    const g = await geocodeText(`${nb ? nb + ", " : ""}${city.name}, Zambia`);
    const at = g && cityOf(g)?.id === city.id ? g : nb ? neighbourhoodFallback(city, nb) : { lat: city.lat, lng: city.lng };
    return placeFor(label, `${label}${nb ? ", " + city.name : ""}`, at, id, !g, nb || null);
  }
  if (id.startsWith("g:")) {
    const k = key(); if (!k) throw new HttpsError("unavailable", "Address lookup is unavailable. Pick the spot on the map instead.");
    const j = await getJson(`https://maps.googleapis.com/maps/api/place/details/json?place_id=${encodeURIComponent(id.slice(2))}&fields=name,formatted_address,geometry&key=${k}`);
    const r = j?.result;
    if (!r?.geometry?.location) throw new HttpsError("unavailable", "We couldn't load that address. Try again or pick it on the map.");
    return placeFor(r.name ?? r.formatted_address, r.formatted_address ?? r.name, r.geometry.location, id, false);
  }
  throw new HttpsError("invalid-argument", "Unknown address.");
}

async function geocodeText(text: string): Promise<LL | null> {
  const k = key(); if (!k) return null;
  const j = await getJson(`https://maps.googleapis.com/maps/api/geocode/json?address=${encodeURIComponent(text)}&region=zm&components=country:ZM&key=${k}`);
  const loc = j?.results?.[0]?.geometry?.location; return isLL(loc) ? toLL(loc) : null;
}

export async function reverse(at: LL): Promise<Place> {
  const k = key();
  if (k) {
    const j = await getJson(`https://maps.googleapis.com/maps/api/geocode/json?latlng=${at.lat},${at.lng}&result_type=street_address|route|premise|sublocality|neighborhood&key=${k}`);
    const r = j?.results?.[0];
    if (r) return placeFor(r.formatted_address.split(",")[0], r.formatted_address, at, `pin:${at.lat.toFixed(6)},${at.lng.toFixed(6)}`, false);
  }
  const nb = nearestNeighbourhood(at); const city = cityOf(at);
  const label = nb ? `Pinned location near ${nb.name}` : "Pinned location";
  return placeFor(label, city ? `${nb?.name ? nb.name + ", " : ""}${city.name}` : `${at.lat.toFixed(5)}, ${at.lng.toFixed(5)}`, at,
    `pin:${at.lat.toFixed(6)},${at.lng.toFixed(6)}`, false, nb?.name ?? null);
}

/** Road distance/duration; falls back to straight line × 1.3 and says so (approximate). */
export async function routeDistance(from: LL, to: LL): Promise<{ distanceKm: number; durationMin: number; approximate: boolean }> {
  const k = key();
  if (k) {
    const j = await getJson(`https://maps.googleapis.com/maps/api/distancematrix/json?origins=${from.lat},${from.lng}&destinations=${to.lat},${to.lng}&mode=driving&region=zm&key=${k}`);
    const el = j?.rows?.[0]?.elements?.[0];
    if (el?.status === "OK") return { distanceKm: Math.round(el.distance.value / 100) / 10, durationMin: Math.ceil(el.duration.value / 60), approximate: false };
  }
  const km = Math.round(haversineKm(from, to) * ROAD_FACTOR * 10) / 10;
  return { distanceKm: km, durationMin: Math.ceil((km / 25) * 60), approximate: true };
}

/** Platform address store with de-duplication: same name within 30 m, or any entry within 8 m, is the same place. */
export async function saveAddress(uid: string, p: { label: string; address: string; lat: number; lng: number; source?: string; approximate?: boolean }) {
  const at = toLL(p);
  const city = cityOf(at);
  if (!city) throw new HttpsError("failed-precondition", "That location is outside our service area.");
  const label = p.label.trim().slice(0, 80) || "Pinned location";
  const cell = `${at.lat.toFixed(3)},${at.lng.toFixed(3)}`; // ~110 m buckets for the dedupe lookup
  const near = await db.collection("addresses").where("cell", "==", cell).limit(50).get();
  const dup = near.docs.find((d) => {
    const m = haversineKm(at, { lat: d.get("lat"), lng: d.get("lng") }) * 1000;
    return m <= 8 || (m <= 30 && normLabel(d.get("label")) === normLabel(label));
  });
  if (dup) {
    await dup.ref.update({ useCount: FieldValue.increment(1), lastUsedAt: FieldValue.serverTimestamp(), usedBy: FieldValue.arrayUnion(uid) });
    return { id: dup.id, deduplicated: true };
  }
  const id = createHash("sha1").update(`${cell}|${normLabel(label)}`).digest("hex").slice(0, 20);
  const nb = nearestNeighbourhood(at);
  await db.doc(`addresses/${id}`).set({ label, address: p.address.trim().slice(0, 200), lat: at.lat, lng: at.lng, cell, cityId: city.id, cityName: city.name,
    neighbourhood: nb?.name ?? null, searchTokens: searchTokens(`${label} ${p.address} ${nb?.name ?? ""}`), source: p.source ?? "user", approximate: !!p.approximate,
    createdBy: uid, createdAt: FieldValue.serverTimestamp(), useCount: 1, usedBy: [uid], lastUsedAt: FieldValue.serverTimestamp() }, { merge: true });
  return { id, deduplicated: false };
}

/* ------------------------------------------------------------------ callables */

const opt = { secrets: [MAPS_API_KEY] };
export const geo_autocomplete = onCall(opt, async (req) => {
  requireUser(req);
  return autocomplete(String(req.data?.input ?? ""), typeof req.data?.cityId === "string" ? req.data.cityId : null, isLL(req.data?.near) ? toLL(req.data.near) : null);
});
export const geo_details = onCall(opt, async (req) => { requireUser(req); return details(str(req.data?.id, "address")); });
export const geo_reverse = onCall(opt, async (req) => {
  requireUser(req);
  if (!isLL(req.data)) throw new HttpsError("invalid-argument", "Location required.");
  return reverse(toLL(req.data));
});
export const geo_distance = onCall(opt, async (req) => {
  requireUser(req);
  if (!isLL(req.data?.from) || !isLL(req.data?.to)) throw new HttpsError("invalid-argument", "Both points are required.");
  const from = toLL(req.data.from), to = toLL(req.data.to);
  const r = await routeDistance(from, to);
  return { ...r, sameCity: cityOf(from)?.id === cityOf(to)?.id && !!cityOf(from), fromCity: cityOf(from)?.name ?? null, toCity: cityOf(to)?.name ?? null };
});
export const address_save = onCall(async (req) => {
  const { uid } = requireUser(req);
  const d = (req.data ?? {}) as Record<string, any>;
  if (!isLL(d as unknown)) throw new HttpsError("invalid-argument", "Location required.");
  return saveAddress(uid, { label: String(d.label ?? ""), address: String(d.address ?? ""), lat: Number(d.lat), lng: Number(d.lng),
    source: typeof d.source === "string" ? d.source.slice(0, 20) : "user", approximate: d.approximate === true });
});
