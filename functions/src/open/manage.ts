/**
 * Open Network management.
 *  - admin_open* (Super Admin only, every change audited): register/approve/suspend platforms, services, scopes,
 *    environments, rate limits, partner pricing, credentials (create / rotate / revoke), webhooks, event replay,
 *    settlement records, manual rider dispatch.
 *  - devportal_* (signed-in developers, own platforms only): register a platform, see status and usage, obtain
 *    sandbox credentials (production once approved), configure webhooks, send a test event, request production access.
 * Secrets are returned once and never stored in clear: client secrets as scrypt hashes, webhook secrets AES-GCM encrypted.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { db } from "../config";
import { audit, notify, requireAdmin } from "../util";
import { encrypt, hashSecret, newClientId, newClientSecret, newWebhookSecret } from "./crypto";
import { ALL_SCOPES, type Env, EVENTS, OPEN_API_ENC_KEY, OPEN_SECRETS, type Partner, type PartnerPricing, secretValue } from "./model";
import { post } from "./settlement";
import { replay } from "./webhooks";

const opts = { secrets: OPEN_SECRETS };
const superAdmin = (req: any) => requireAdmin(req, "super_admin");
const clean = (s: unknown, n: number) => String(s ?? "").trim().slice(0, n);
const num = (v: unknown) => (v === null || v === undefined || v === "" ? null : Math.max(0, Number(v)));
const slugify = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 40);

function pricingFrom(p: any = {}): PartnerPricing {
  return { deliveryBaseNgwee: num(p.deliveryBaseNgwee), deliveryPerKmNgwee: num(p.deliveryPerKmNgwee), expressSurchargePct: num(p.expressSurchargePct),
    wholesaleDiscountPct: num(p.wholesaleDiscountPct), marketplaceCommissionPct: num(p.marketplaceCommissionPct), subscriptionMonthlyNgwee: num(p.subscriptionMonthlyNgwee),
    apiCallNgwee: num(p.apiCallNgwee), settlementCycle: p.settlementCycle === "weekly" ? "weekly" : "monthly" };
}

async function createClient(partnerId: string, env: Env, by: string) {
  const clientId = newClientId(env), secret = newClientSecret(), { salt, hash } = hashSecret(secret);
  await db.doc(`openClients/${clientId}`).create({ partnerId, env, status: "ACTIVE", salt, hash, createdBy: by, createdAt: FieldValue.serverTimestamp() });
  return { clientId, clientSecret: secret, environment: env, note: "Store the secret in your server's secret store now — it is shown only once and must never be put in browser or mobile code." };
}

/* =================================================================== Super Admin */
export const admin_openPartnerSave = onCall(opts, async (req) => {
  const a = superAdmin(req); const d = req.data ?? {};
  const name = clean(d.name, 80); if (name.length < 2) throw new HttpsError("invalid-argument", "Enter the platform name.");
  const id = clean(d.id, 60) || `ptn_${slugify(name)}_${Date.now().toString(36)}`;
  const ref = db.doc(`openPartners/${id}`); const before = (await ref.get()).data() ?? null;
  const next = { name, slug: slugify(name), services: { commerce: d.services?.commerce === true, logistics: d.services?.logistics === true },
    scopes: (Array.isArray(d.scopes) ? d.scopes : []).filter((s: string) => ALL_SCOPES.includes(s)),
    environments: { sandbox: d.environments?.sandbox !== false, production: d.environments?.production === true },
    rateLimitPerMin: Math.min(6000, Math.max(10, Math.round(Number(d.rateLimitPerMin) || 120))), pricing: pricingFrom(d.pricing),
    contact: { name: clean(d.contact?.name, 80), email: clean(d.contact?.email, 120), phone: clean(d.contact?.phone, 20) }, website: clean(d.website, 200),
    notes: clean(d.notes, 1000), updatedAt: FieldValue.serverTimestamp(), updatedBy: a.email };
  await ref.set({ ...next, ...(before ? {} : { status: "PENDING", createdAt: FieldValue.serverTimestamp(), createdBy: a.email, ownerUids: [], webhook: null }) }, { merge: true });
  await audit(a, before ? "open.partner.update" : "open.partner.create", `openPartners/${id}`, before, next);
  return { id };
});

export const admin_openPartnerStatus = onCall(opts, async (req) => {
  const a = superAdmin(req); const id = clean(req.data?.id, 80); const status = String(req.data?.status ?? "");
  if (!["ACTIVE", "SUSPENDED", "REJECTED", "PENDING"].includes(status)) throw new HttpsError("invalid-argument", "Unknown status.");
  const ref = db.doc(`openPartners/${id}`); const p = (await ref.get()).data(); if (!p) throw new HttpsError("not-found", "Platform not found.");
  await ref.update({ status, statusReason: clean(req.data?.reason, 300) || null, ...(status === "ACTIVE" && !p.approvedAt ? { approvedAt: FieldValue.serverTimestamp(), approvedBy: a.email } : {}),
    ...(req.data?.production === true ? { "environments.production": true, productionRequested: false } : {}) });
  await audit(a, `open.partner.${status.toLowerCase()}`, `openPartners/${id}`, { status: p.status }, { status, reason: req.data?.reason ?? null });
  await Promise.all((p.ownerUids ?? []).map((u: string) => notify(u, `${p.name}: ${status.toLowerCase()}`, status === "ACTIVE" ? "Your integration is approved. Get sandbox credentials in the developer portal." :
    `Your integration is ${status.toLowerCase()}.`, "OPEN_PARTNER").catch(() => undefined)));
  return { ok: true };
});

export const admin_openCredentials = onCall(opts, async (req) => {
  const a = superAdmin(req); const pid = clean(req.data?.partnerId, 80); const action = String(req.data?.action ?? "");
  const p = (await db.doc(`openPartners/${pid}`).get()).data(); if (!p) throw new HttpsError("not-found", "Platform not found.");
  if (action === "create") {
    const env: Env = req.data?.env === "production" ? "production" : "sandbox";
    const r = await createClient(pid, env, a.email); await audit(a, "open.credentials.create", `openClients/${r.clientId}`, null, { partnerId: pid, env }); return r;
  }
  const cid = clean(req.data?.clientId, 80); const cref = db.doc(`openClients/${cid}`); const c = (await cref.get()).data();
  if (!c || c.partnerId !== pid) throw new HttpsError("not-found", "Client not found.");
  if (action === "revoke") { await cref.update({ status: "REVOKED", revokedAt: FieldValue.serverTimestamp(), revokedBy: a.email }); await audit(a, "open.credentials.revoke", `openClients/${cid}`, null, null); return { ok: true }; }
  if (action === "rotate") { // new secret, same client id; the old secret stops working immediately
    const secret = newClientSecret(), { salt, hash } = hashSecret(secret);
    await cref.update({ salt, hash, status: "ACTIVE", rotatedAt: FieldValue.serverTimestamp(), rotatedBy: a.email });
    await audit(a, "open.credentials.rotate", `openClients/${cid}`, null, null);
    return { clientId: cid, clientSecret: secret, environment: c.env, note: "The previous secret no longer works. Store this one now — it is shown only once." };
  }
  throw new HttpsError("invalid-argument", "action must be create, rotate or revoke.");
});

async function saveWebhook(pid: string, url: unknown, events: unknown, rotate: boolean) {
  const u = url ? String(url) : null;
  if (u && !/^https:\/\/[^\s]+$/.test(u)) throw new HttpsError("invalid-argument", "The webhook URL must start with https://");
  const ev = (Array.isArray(events) ? events : []).filter((e: string) => (EVENTS as readonly string[]).includes(e));
  const p = (await db.doc(`openPartners/${pid}`).get()).data() as Partner;
  const fresh = !p.webhook?.secretEnc || rotate; const secret = fresh ? newWebhookSecret() : null;
  await db.doc(`openPartners/${pid}`).update({ webhook: { url: u, events: ev, secretEnc: fresh ? encrypt(secret!, secretValue(OPEN_API_ENC_KEY, "OPEN_API_ENC_KEY")) : p.webhook!.secretEnc } });
  return { url: u, events: ev, ...(secret ? { signingSecret: secret, note: "Shown once — store it to verify webhook signatures." } : {}) };
}
export const admin_openWebhook = onCall(opts, async (req) => {
  const a = superAdmin(req); const pid = clean(req.data?.partnerId, 80);
  if (!(await db.doc(`openPartners/${pid}`).get()).exists) throw new HttpsError("not-found", "Platform not found.");
  const r = await saveWebhook(pid, req.data?.url, req.data?.events, req.data?.rotateSecret === true);
  await audit(a, "open.webhook.update", `openPartners/${pid}`, null, { url: r.url, events: r.events }); return r;
});
export const admin_openReplay = onCall(opts, async (req) => { const a = superAdmin(req); const r = await replay(clean(req.data?.eventId, 80));
  await audit(a, "open.webhook.replay", `openEvents/${req.data?.eventId}`, null, { result: r }); return { status: r }; });

export const admin_openSettlement = onCall(opts, async (req) => {
  const a = superAdmin(req); const pid = clean(req.data?.partnerId, 80); const amount = Math.round(Number(req.data?.amountNgwee));
  const reference = clean(req.data?.reference, 80);
  if (!amount || reference.length < 4) throw new HttpsError("invalid-argument", "Enter the amount (negative = paid to the partner) and the payment reference.");
  await post(pid, { type: "SETTLEMENT", amountNgwee: amount, refType: "manual", refId: reference, env: "production", note: clean(req.data?.note, 200) });
  await audit(a, "open.settlement", `openLedger/${pid}`, null, { amount, reference }); return { ok: true };
});

/** Manual dispatch: offer a partner delivery to a specific rider (the rider still accepts in the app, so no conflicting assignments). */
export const admin_openAssignRider = onCall(opts, async (req) => {
  const a = requireAdmin(req, "operations"); const id = clean(req.data?.deliveryId, 40); const rider = clean(req.data?.riderUid, 128);
  const ref = db.doc(`parcels/${id}`); const p = (await ref.get()).data();
  if (!p || p.status !== "AWAITING_RIDER") throw new HttpsError("failed-precondition", "Only deliveries waiting for a rider can be assigned.");
  const r = (await db.doc(`riderPresence/${rider}`).get()).data();
  if (!r?.isOnline) throw new HttpsError("failed-precondition", "That rider is offline.");
  await ref.update({ notifiedRiders: FieldValue.arrayUnion(rider), manualOfferBy: a.email, manualOfferAt: FieldValue.serverTimestamp() });
  await notify(rider, "Job offered to you", `Operations picked you for a delivery${p.source?.platform ? ` from ${p.source.platform}` : ""}. Open Jobs to accept.`, "NEW_ORDER_AVAILABLE", `selo://rider/jobs/parcel/${id}`);
  await audit(a, "open.delivery.manual_offer", `parcels/${id}`, null, { rider }); return { ok: true };
});

/* =================================================================== Developer portal */
const devUser = (req: any) => { if (!req.auth?.uid) throw new HttpsError("unauthenticated", "Sign in to the developer portal."); return { uid: req.auth.uid as string, email: (req.auth.token?.email as string) ?? "" }; };
async function ownPartner(uid: string, pid: string) {
  const p = (await db.doc(`openPartners/${pid}`).get()).data();
  if (!p || !(p.ownerUids ?? []).includes(uid)) throw new HttpsError("not-found", "Platform not found.");
  return p;
}

export const devportal_register = onCall(opts, async (req) => {
  const u = devUser(req); const d = req.data ?? {};
  const name = clean(d.platformName, 80); if (name.length < 2) throw new HttpsError("invalid-argument", "Enter your platform's name.");
  const mine = await db.collection("openPartners").where("ownerUids", "array-contains", u.uid).count().get();
  if (mine.data().count >= 5) throw new HttpsError("resource-exhausted", "You can register up to 5 platforms.");
  const id = `ptn_${slugify(name)}_${Date.now().toString(36)}`;
  const services = { commerce: d.services?.commerce === true, logistics: d.services?.logistics === true };
  await db.doc(`openPartners/${id}`).create({ name, slug: slugify(name), status: "PENDING", services, environments: { sandbox: true, production: false },
    // requested scopes for the services asked for; an admin confirms them on approval
    scopes: ALL_SCOPES.filter((s) => s.startsWith("webhooks") || s.startsWith("settlements") || (services.commerce && /^(products|inventory|catalog|orders)/.test(s)) || (services.logistics && /^(delivery|coverage)/.test(s))),
    rateLimitPerMin: 120, pricing: {}, webhook: null, ownerUids: [u.uid], contact: { name: clean(d.contactName, 80), email: u.email, phone: clean(d.phone, 20) },
    website: clean(d.website, 200), useCase: clean(d.useCase, 1000), createdAt: FieldValue.serverTimestamp(), createdBy: `portal:${u.email}` });
  await db.collection("opsAlerts").add({ type: "OPEN_PARTNER_APPLICATION", partnerId: id, name, createdAt: FieldValue.serverTimestamp() });
  return { id, status: "PENDING" };
});

export const devportal_mine = onCall(opts, async (req) => {
  const u = devUser(req);
  const q = await db.collection("openPartners").where("ownerUids", "array-contains", u.uid).get();
  const since = new Date(Date.now() - 30 * 86_400_000).toISOString().slice(0, 10);
  return { platforms: await Promise.all(q.docs.map(async (d) => {
    const x = d.data();
    const [clients, usage] = await Promise.all([db.collection("openClients").where("partnerId", "==", d.id).get(),
      db.collection("openUsage").where("pid", "==", d.id).where("day", ">=", since).get()]);
    return { id: d.id, name: x.name, status: x.status, services: x.services, scopes: x.scopes, environments: x.environments, productionRequested: !!x.productionRequested,
      rateLimitPerMin: x.rateLimitPerMin, webhook: { url: x.webhook?.url ?? null, events: x.webhook?.events ?? [] },
      clients: clients.docs.map((c) => ({ clientId: c.id, env: c.get("env"), status: c.get("status"), lastTokenAt: (c.get("lastTokenAt") as Timestamp | undefined)?.toMillis() ?? null })),
      usage: usage.docs.map((s) => ({ day: s.get("day"), env: s.get("env"), calls: s.get("calls") ?? 0, errors: s.get("errors") ?? 0 })).sort((a, b) => a.day.localeCompare(b.day)) };
  })) };
});

export const devportal_credentials = onCall(opts, async (req) => {
  const u = devUser(req); const pid = clean(req.data?.partnerId, 80); const p = await ownPartner(u.uid, pid);
  const env: Env = req.data?.env === "production" ? "production" : "sandbox";
  if (p.status !== "ACTIVE") throw new HttpsError("failed-precondition", "Your platform isn't approved yet. We'll notify you when it is.");
  if (!p.environments?.[env]) throw new HttpsError("failed-precondition", env === "production" ? "Request production access first." : "Sandbox is disabled for this platform.");
  const existing = await db.collection("openClients").where("partnerId", "==", pid).where("env", "==", env).where("status", "==", "ACTIVE").count().get();
  if (existing.data().count >= 3) throw new HttpsError("resource-exhausted", "You already have 3 active credentials for this environment. Ask us to revoke one.");
  return createClient(pid, env, `portal:${u.email}`);
});

export const devportal_requestProduction = onCall(opts, async (req) => {
  const u = devUser(req); const pid = clean(req.data?.partnerId, 80); const p = await ownPartner(u.uid, pid);
  if (p.status !== "ACTIVE") throw new HttpsError("failed-precondition", "Get your sandbox integration approved first.");
  await db.doc(`openPartners/${pid}`).update({ productionRequested: true, productionRequestNote: clean(req.data?.note, 1000), productionRequestedAt: FieldValue.serverTimestamp() });
  await db.collection("opsAlerts").add({ type: "OPEN_PRODUCTION_REQUEST", partnerId: pid, name: p.name, createdAt: FieldValue.serverTimestamp() });
  return { ok: true };
});

export const devportal_webhook = onCall(opts, async (req) => {
  const u = devUser(req); const pid = clean(req.data?.partnerId, 80); const p = await ownPartner(u.uid, pid);
  if (!(p.scopes ?? []).includes("webhooks:manage")) throw new HttpsError("permission-denied", "Webhooks aren't enabled for this platform.");
  return saveWebhook(pid, req.data?.url, req.data?.events, req.data?.rotateSecret === true);
});
export const devportal_testWebhook = onCall(opts, async (req) => {
  const u = devUser(req); const pid = clean(req.data?.partnerId, 80); await ownPartner(u.uid, pid);
  const { emit } = await import("./webhooks");
  return { id: await emit(pid, "sandbox", "delivery.created", `portal-test-${Date.now()}`, { test: true, message: "Test webhook from the SeloNachipa developer portal" }) };
});

/* =================================================================== monthly partner billing */
/** 1st of the month: subscription and API-usage charges for last month go into each partner's ledger (production only). */
export const scheduled_openBilling = onSchedule({ schedule: "20 6 1 * *", timeZone: "Africa/Lusaka" }, async () => {
  const now = new Date(); const y = now.getUTCMonth() === 0 ? now.getUTCFullYear() - 1 : now.getUTCFullYear(); const m = now.getUTCMonth() === 0 ? 12 : now.getUTCMonth();
  const month = `${y}-${String(m).padStart(2, "0")}`;
  const partners = await db.collection("openPartners").where("status", "==", "ACTIVE").get();
  for (const p of partners.docs) {
    const pr = (p.get("pricing") ?? {}) as PartnerPricing;
    if (pr.subscriptionMonthlyNgwee) await post(p.id, { type: "SUBSCRIPTION", amountNgwee: pr.subscriptionMonthlyNgwee, refType: "month", refId: month, env: "production" });
    if (pr.apiCallNgwee) {
      const u = await db.collection("openUsage").where("pid", "==", p.id).where("env", "==", "production").where("day", ">=", `${month}-01`).where("day", "<=", `${month}-31`).get();
      const calls = u.docs.reduce((s, d) => s + (d.get("calls") ?? 0), 0);
      if (calls) await post(p.id, { type: "API_USAGE", amountNgwee: calls * pr.apiCallNgwee, refType: "month", refId: month, env: "production", note: `${calls} API calls` });
    }
  }
});
