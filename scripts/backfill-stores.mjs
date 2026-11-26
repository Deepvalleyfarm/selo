// One-off: create users.location and public stores/{sellerUid} for accounts registered before v6.5.
// Uses coordinates already saved on the account (geo) — no Google calls.
//   cd functions && GOOGLE_APPLICATION_CREDENTIALS=sa.json node ../scripts/backfill-stores.mjs
import { initializeApp } from "firebase-admin/app";
import { getFirestore, FieldValue } from "firebase-admin/firestore";

initializeApp();
const db = getFirestore("ai-studio-611c950c-febe-47c1-afff-35dcb0788339");
const B32 = "0123456789bcdefghjkmnpqrstuvwxyz";
function geohash(lat, lng, precision = 9) {
  let la = [-90, 90], ln = [-180, 180], h = "", bit = 0, ch = 0, even = true;
  while (h.length < precision) { const r = even ? ln : la, v = even ? lng : lat, mid = (r[0] + r[1]) / 2;
    if (v >= mid) { ch |= 1 << (4 - bit); r[0] = mid; } else r[1] = mid; even = !even; if (++bit === 5) { h += B32[ch]; bit = 0; ch = 0; } }
  return h;
}
let users = 0, stores = 0;
for (const role of ["SELLER", "BUYER", "AGENT", "RIDER"]) {
  const snap = await db.collection("users").where("role", "==", role).get();
  for (const d of snap.docs) {
    const u = d.data(); const g = u.geo;
    if (!g || !Number.isFinite(g.lat)) continue;
    const address = u.location?.address ?? u.landmark ?? u.area ?? "";
    if (!u.location) { await d.ref.set({ location: { address, latitude: g.lat, longitude: g.lng, placeId: null, source: "backfill", updatedAt: FieldValue.serverTimestamp() } }, { merge: true }); users++; }
    if (role === "SELLER") { await db.doc(`stores/${d.id}`).set({ name: u.storeName ?? u.fullName ?? "", address, latitude: g.lat, longitude: g.lng,
      geohash: geohash(g.lat, g.lng), geohash5: geohash(g.lat, g.lng, 5), sellerUid: d.id, active: u.accountStatus === "ACTIVE", updatedAt: FieldValue.serverTimestamp() }, { merge: true }); stores++; }
  }
}
console.log(`locations backfilled: ${users}, stores written: ${stores}`);
