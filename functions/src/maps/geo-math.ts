/** Pure geometry helpers shared by the maps layer (no I/O, unit-tested). */
export interface LL { lat: number; lng: number }

export function haversineM(a: LL, b: LL): number {
  const R = 6_371_000, r = (d: number) => (d * Math.PI) / 180;
  const h = Math.sin(r(b.lat - a.lat) / 2) ** 2 + Math.cos(r(a.lat)) * Math.cos(r(b.lat)) * Math.sin(r(b.lng - a.lng) / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

export const isLL = (v: unknown): v is LL => {
  const o = v as LL | null;
  return !!o && Number.isFinite(Number(o.lat)) && Number.isFinite(Number(o.lng)) && Math.abs(Number(o.lat)) <= 90 && Math.abs(Number(o.lng)) <= 180;
};
export const toLL = (v: { lat: unknown; lng: unknown }): LL => ({ lat: Number(v.lat), lng: Number(v.lng) });

/** Cache cell: 4 decimals ≈ 11 m in Zambia. Two requests for the same 11 m square share one Google result. */
export const cell = (p: LL, decimals = 4) => `${p.lat.toFixed(decimals)},${p.lng.toFixed(decimals)}`;

/** Google encoded polyline → points. */
export function decodePolyline(s: string): LL[] {
  const out: LL[] = []; let i = 0, lat = 0, lng = 0;
  while (i < s.length) {
    for (const which of [0, 1]) {
      let b, shift = 0, result = 0;
      do { b = s.charCodeAt(i++) - 63; result |= (b & 0x1f) << shift; shift += 5; } while (b >= 0x20);
      const d = result & 1 ? ~(result >> 1) : result >> 1;
      if (which === 0) lat += d; else lng += d;
    }
    out.push({ lat: lat / 1e5, lng: lng / 1e5 });
  }
  return out;
}

export function encodePolyline(pts: LL[]): string {
  let out = "", pLat = 0, pLng = 0;
  const enc = (v: number) => { let x = v < 0 ? ~(v << 1) : v << 1; let s = ""; while (x >= 0x20) { s += String.fromCharCode((0x20 | (x & 0x1f)) + 63); x >>= 5; } return s + String.fromCharCode(x + 63); };
  for (const p of pts) { const la = Math.round(p.lat * 1e5), ln = Math.round(p.lng * 1e5); out += enc(la - pLat) + enc(ln - pLng); pLat = la; pLng = ln; }
  return out;
}

/** Shortest distance in metres from p to a polyline (equirectangular projection; fine at city scale). */
export function distanceToPathM(p: LL, path: LL[]): number {
  if (!path.length) return Infinity;
  if (path.length === 1) return haversineM(p, path[0]);
  const k = Math.cos((p.lat * Math.PI) / 180), M = 111_320;
  let best = Infinity;
  for (let i = 1; i < path.length; i++) {
    const a = path[i - 1], b = path[i];
    const ax = (a.lng - p.lng) * k * M, ay = (a.lat - p.lat) * M, bx = (b.lng - p.lng) * k * M, by = (b.lat - p.lat) * M;
    const dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
    const t = len === 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len));
    best = Math.min(best, Math.hypot(ax + t * dx, ay + t * dy));
  }
  return best;
}

const B32 = "0123456789bcdefghjkmnpqrstuvwxyz";
/** Standard geohash (used for "shops near me" range queries on the public stores collection). */
export function geohash(p: LL, precision = 9): string {
  let latR = [-90, 90], lngR = [-180, 180], hash = "", bit = 0, ch = 0, even = true;
  while (hash.length < precision) {
    const r = even ? lngR : latR, v = even ? p.lng : p.lat, mid = (r[0] + r[1]) / 2;
    if (v >= mid) { ch |= 1 << (4 - bit); r[0] = mid; } else r[1] = mid;
    even = !even;
    if (++bit === 5) { hash += B32[ch]; bit = 0; ch = 0; }
  }
  return hash;
}
