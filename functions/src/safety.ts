/**
 * Rider Safety Centre.
 *  - SOS: one tap creates an alert, texts the rider's emergency contact with a live-location link and alerts operations.
 *  - Incident location sharing: the same live link without the SMS, to share with anyone (WhatsApp, police, family).
 *  - Verified rider ID: a public verification page (/v/{code}) buyers, sellers or police can open from the QR code.
 *  - Delivery evidence: geotagged, time-stamped photos at pickup / drop-off, attached to the order for disputes.
 *  - Optional insurance cover: opt in or out; the premium comes off each completed delivery (deliveryConfirmation.ts).
 *  - Safety education: short lessons; completing them is recorded on the rider profile.
 */
import { randomBytes } from "node:crypto";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { SMS_API_KEY, TRACKING_BASE_URL, db, loadConfig } from "./config";
import { sendSms } from "./sms";
import { requireAdmin, requireUser } from "./util";

const base = () => { try { return TRACKING_BASE_URL.value().replace(/\/t\/?$/, ""); } catch { return "https://mabala-f2d65.web.app"; } };
const esc = (s: unknown) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
const isLL = (v: any) => v && Number.isFinite(Number(v.lat)) && Number.isFinite(Number(v.lng)) && Math.abs(Number(v.lat)) <= 90 && Math.abs(Number(v.lng)) <= 180;
export const mapsLink = (lat: number, lng: number) => `https://maps.google.com/?q=${lat.toFixed(6)},${lng.toFixed(6)}`;

export const LESSONS = ["helmet", "night", "robbery", "weather", "road", "cash", "first-aid"] as const;
/** Default rider cover (admin can change in config): K 2 per delivery for up to K 10,000 accident / medical cover. */
export const DEFAULT_COVER = { premiumNgwee: 200, coverNgwee: 1_000_000 };

export function sosSms(name: string, msisdn: string, lat: number, lng: number, link: string, kind: "SOS" | "SAFE") {
  return kind === "SAFE" ? `SeloNaChipa: ${name} has marked themselves SAFE. Thank you.`
    : `SeloNaChipa SAFETY ALERT: ${name} (${msisdn}) pressed SOS. Location: ${mapsLink(lat, lng)} Live: ${link} Call them or 999 / 991 now.`;
}

export const rider_sos = onCall({ secrets: [SMS_API_KEY] }, async (req) => {
  const { uid } = requireUser(req, "RIDER");
  if (!isLL(req.data)) throw new HttpsError("invalid-argument", "We need your location to send help. Turn location on and try again.");
  const kind = req.data?.kind === "SHARE" ? "SHARE" : "SOS";
  const at = { lat: Number(req.data.lat), lng: Number(req.data.lng) };
  const recent = await db.collection("sosAlerts").where("riderUid", "==", uid).where("status", "==", "OPEN").limit(1).get();
  const u = (await db.doc(`users/${uid}`).get()).data() ?? {};
  // An open alert is reused (pressing SOS twice never sends two SMS within a minute); its position is refreshed.
  let ref = recent.docs[0]?.ref, token = recent.docs[0]?.get("token") as string | undefined;
  if (ref && kind === "SOS" && recent.docs[0].get("kind") === "SHARE") await ref.update({ kind: "SOS" });
  if (!ref) {
    token = randomBytes(12).toString("base64url");
    ref = db.collection("sosAlerts").doc();
    await ref.set({ riderUid: uid, riderName: u.fullName ?? "", riderMsisdn: u.msisdn ?? "", plate: u.vehicleDetails?.plate ?? null, kind, token, status: "OPEN",
      lat: at.lat, lng: at.lng, accuracy: Number(req.data?.accuracy) || null, note: String(req.data?.note ?? "").slice(0, 300), contactNotified: false,
      orderId: typeof req.data?.orderId === "string" ? req.data.orderId.slice(0, 40) : null, createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() });
  } else await ref.update({ lat: at.lat, lng: at.lng, updatedAt: FieldValue.serverTimestamp() });
  const link = `${base()}/s/${token}`;
  let contactNotified = false;
  const snap = (await ref.get()).data()!;
  if (kind === "SOS" && !snap.contactNotified) {
    const phone = u.emergencyContact?.phone;
    if (phone) contactNotified = (await sendSms(phone, sosSms(u.fullName ?? "A SeloNaChipa rider", u.msisdn ?? "", at.lat, at.lng, link, "SOS"), { eventType: "RIDER_SOS", referenceId: ref.id })).ok;
    await ref.update({ contactNotified });
    // Operations see every SOS on the back office immediately.
    await db.collection("opsAlerts").add({ type: "RIDER_SOS", riderUid: uid, alertId: ref.id, lat: at.lat, lng: at.lng, link, createdAt: FieldValue.serverTimestamp() });
  }
  return { alertId: ref.id, shareUrl: link, mapsUrl: mapsLink(at.lat, at.lng), contactNotified: contactNotified || snap.contactNotified === true,
    hasContact: !!u.emergencyContact?.phone, contactName: u.emergencyContact?.name ?? null };
});

export const rider_sosUpdate = onCall({ secrets: [SMS_API_KEY] }, async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const ref = db.doc(`sosAlerts/${String(req.data?.alertId ?? "")}`);
  const a = (await ref.get()).data();
  if (!a || a.riderUid !== uid) throw new HttpsError("not-found", "Alert not found.");
  if (isLL(req.data)) { await ref.update({ lat: Number(req.data.lat), lng: Number(req.data.lng), updatedAt: FieldValue.serverTimestamp() }); return { ok: true }; }
  const status = req.data?.status === "SAFE" ? "SAFE" : "CLOSED";
  await ref.update({ status, closedAt: FieldValue.serverTimestamp() });
  const u = (await db.doc(`users/${uid}`).get()).data() ?? {};
  if (a.kind === "SOS" && a.contactNotified && u.emergencyContact?.phone)
    await sendSms(u.emergencyContact.phone, sosSms(u.fullName ?? "The rider", "", 0, 0, "", "SAFE"), { eventType: "RIDER_SOS_SAFE", referenceId: ref.id }).catch(() => undefined);
  return { ok: true };
});

/** Public live-location page for an SOS / shared incident (auto-refreshes every 20 s while open). */
export const sos_view = onRequest(async (req, res) => {
  const token = String(req.path.split("/").pop() ?? "").slice(0, 40);
  const q = token ? await db.collection("sosAlerts").where("token", "==", token).limit(1).get() : null;
  const a = q?.docs[0]?.data();
  if (!a) { res.status(404).send("This link has expired."); return; }
  const live = a.status === "OPEN" ? (await db.doc(`riderLocations/${a.riderUid}`).get()).data() : null;
  const lat = Number(live?.lat ?? a.lat), lng = Number(live?.lng ?? a.lng);
  const when = ((live?.at ?? a.updatedAt) as Timestamp | undefined)?.toDate().toLocaleString("en-GB", { timeZone: "Africa/Lusaka", timeStyle: "short", dateStyle: "medium" }) ?? "";
  res.set("Cache-Control", "no-store").send(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
${a.status === "OPEN" ? '<meta http-equiv="refresh" content="20">' : ""}<meta name="robots" content="noindex"><title>${a.kind === "SOS" ? "SOS" : "Shared location"} · SeloNaChipa</title>
<style>body{font-family:system-ui,sans-serif;margin:0;padding:16px;background:#f4f4f6;color:#111}.c{max-width:480px;margin:auto;background:#fff;border-radius:16px;padding:20px}
.b{display:inline-block;border-radius:99px;padding:4px 12px;font-weight:800;color:#fff;background:${a.status === "OPEN" ? (a.kind === "SOS" ? "#C8102E" : "#0F766E") : "#166534"}}
a.btn{display:block;text-align:center;background:#C8102E;color:#fff;padding:14px;border-radius:12px;font-weight:800;text-decoration:none;margin-top:12px}.m{color:#6b7280;font-size:13px}</style></head>
<body><div class="c"><span class="b">${a.status === "OPEN" ? (a.kind === "SOS" ? "SOS · NEEDS HELP" : "LIVE LOCATION") : a.status === "SAFE" ? "SAFE" : "CLOSED"}</span>
<h2>${esc(a.riderName)}</h2><p>Verified SeloNaChipa rider${a.plate ? ` · plate <b>${esc(a.plate)}</b>` : ""}</p>
<p class="m">Last position ${esc(when)}${a.note ? ` · “${esc(a.note)}”` : ""}</p>
<a class="btn" href="${mapsLink(lat, lng)}">Open location in Maps</a>
${a.riderMsisdn && a.status === "OPEN" ? `<a class="btn" style="background:#111" href="tel:${esc(a.riderMsisdn)}">Call ${esc(a.riderName.split(" ")[0])}</a>` : ""}
<p class="m">Emergency: Police 999 · Ambulance 991 · SeloNaChipa support +260 978 070 734</p></div></body></html>`);
});

const riderCode = () => `SNR-${randomBytes(3).toString("hex").toUpperCase()}`;

/** Verified rider ID card (lazily issues the rider's public code). */
export const rider_idCard = onCall(async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const ref = db.doc(`users/${uid}`);
  const u = (await ref.get()).data() ?? {};
  let code = u.riderCode as string | undefined;
  if (!code) { code = riderCode(); await db.doc(`riderCodes/${code}`).create({ uid }); await ref.update({ riderCode: code }); }
  return { code, verifyUrl: `${base()}/v/${code}`, name: u.fullName ?? "", photoUrl: u.photoUrl ?? null, plate: u.vehicleDetails?.plate ?? "",
    vehicle: [u.vehicle, u.vehicleDetails?.make, u.vehicleDetails?.model].filter(Boolean).join(" "), verified: u.accountStatus === "ACTIVE" && u.kycStatus === "VERIFIED",
    status: u.accountStatus ?? "", memberSince: u.memberSince ?? "" };
});

/** Public verification page behind the rider's QR code. Shows only what's needed to recognise the rider. */
export const rider_verify = onRequest(async (req, res) => {
  const code = String(req.path.split("/").pop() ?? "").toUpperCase().slice(0, 16);
  const idx = /^SNR-[0-9A-F]{6}$/.test(code) ? (await db.doc(`riderCodes/${code}`).get()).data() : null;
  const u = idx ? (await db.doc(`users/${idx.uid}`).get()).data() : null;
  const ok = !!u && u.role === "RIDER" && u.accountStatus === "ACTIVE" && u.kycStatus === "VERIFIED";
  res.status(u ? 200 : 404).set("Cache-Control", "no-store").send(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>Rider verification · SeloNaChipa</title><style>body{font-family:system-ui,sans-serif;margin:0;padding:16px;background:#f4f4f6}
.c{max-width:420px;margin:auto;background:#fff;border-radius:16px;padding:22px;text-align:center}.ok{color:#166534}.bad{color:#C8102E}img{width:110px;height:110px;border-radius:50%;object-fit:cover}</style></head>
<body><div class="c">${u ? `${u.photoUrl ? `<img src="${esc(u.photoUrl)}" alt="">` : ""}<h2>${esc(u.fullName)}</h2>
<h3 class="${ok ? "ok" : "bad"}">${ok ? "✔ Verified SeloNaChipa rider" : "✖ NOT an active verified rider"}</h3>
<p>Plate <b>${esc(u.vehicleDetails?.plate ?? "—")}</b> · ${esc([u.vehicleDetails?.make, u.vehicleDetails?.model].filter(Boolean).join(" "))}</p><p>ID ${esc(code)} · since ${esc(u.memberSince ?? "")}</p>`
    : `<h3 class="bad">✖ Unknown rider ID</h3>`}<p style="color:#6b7280;font-size:13px">If something feels wrong, don't hand over goods. Call SeloNaChipa on +260 978 070 734.</p></div></body></html>`);
});

/** Geotagged, time-stamped delivery evidence (photo already uploaded to Storage by the rider app). */
export const delivery_addEvidence = onCall(async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const kind = req.data?.kind === "parcel" ? "parcels" : "orders";
  const id = String(req.data?.id ?? "").slice(0, 40);
  const path = String(req.data?.path ?? "");
  if (!path.startsWith(`evidence/${uid}/`)) throw new HttpsError("invalid-argument", "Upload the photo first.");
  const ref = db.doc(`${kind}/${id}`);
  const d = (await ref.get()).data();
  if (!d || d.riderUid !== uid) throw new HttpsError("permission-denied", "This isn't your delivery.");
  const stage = ["pickup", "dropoff", "incident"].includes(req.data?.stage) ? req.data.stage : "dropoff";
  const item = { path, stage, lat: isLL(req.data) ? Number(req.data.lat) : null, lng: isLL(req.data) ? Number(req.data.lng) : null, at: Timestamp.now(), by: uid };
  await ref.update({ evidence: FieldValue.arrayUnion(item), ...(stage === "dropoff" && !d.proofOfDelivery ? { proofOfDelivery: path } : {}) });
  await db.doc(`users/${uid}`).update({ "safety.evidenceCount": FieldValue.increment(1) }).catch(() => undefined);
  return { ok: true, count: (d.evidence?.length ?? 0) + 1 };
});

export const rider_safety = onCall(async (req) => {
  const { uid } = requireUser(req, "RIDER");
  const cfg = await loadConfig() as unknown as Record<string, unknown>;
  const cover = { premiumNgwee: Number(cfg.riderCoverPremiumNgwee ?? DEFAULT_COVER.premiumNgwee), coverNgwee: Number(cfg.riderCoverNgwee ?? DEFAULT_COVER.coverNgwee) };
  const d = req.data ?? {};
  const uRef = db.doc(`users/${uid}`), wRef = db.doc(`riderWallets/${uid}`);
  if (typeof d.insurance === "boolean") {
    await wRef.set({ insurance: { on: d.insurance, premiumNgwee: cover.premiumNgwee, coverNgwee: cover.coverNgwee, since: d.insurance ? FieldValue.serverTimestamp() : null } }, { merge: true });
    await uRef.update({ "safety.insurance": d.insurance });
  }
  if (typeof d.lessonDone === "string" && (LESSONS as readonly string[]).includes(d.lessonDone)) await uRef.update({ "safety.lessons": FieldValue.arrayUnion(d.lessonDone) });
  const [u, w] = await Promise.all([uRef.get(), wRef.get()]);
  const open = await db.collection("sosAlerts").where("riderUid", "==", uid).where("status", "==", "OPEN").limit(1).get();
  return { emergencyName: u.get("emergencyContact.name") ?? null, emergencyPhone: u.get("emergencyContact.phone") ?? null,
    insurance: w.get("insurance.on") === true, premiumNgwee: cover.premiumNgwee, coverNgwee: cover.coverNgwee, lessons: u.get("safety.lessons") ?? [],
    evidenceCount: u.get("safety.evidenceCount") ?? 0, openAlertId: open.docs[0]?.id ?? null, openAlertUrl: open.docs[0] ? `${base()}/s/${open.docs[0].get("token")}` : null };
});


/** Operations close an alert once the rider is reached. */
export const admin_closeSos = onCall(async (req) => {
  const a = requireAdmin(req, "operations", "support");
  const ref = db.doc(`sosAlerts/${String(req.data?.alertId ?? "")}`);
  if (!(await ref.get()).exists) throw new HttpsError("not-found", "Alert not found.");
  await ref.update({ status: "CLOSED", closedAt: FieldValue.serverTimestamp(), closedBy: a.email ?? a.uid });
  return { ok: true };
});
