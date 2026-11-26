/**
 * Wallet → personal mobile money withdrawal (full balance) via Lipila disbursements. Riders, sellers and agents.
 *
 * State machine (every transition is written to withdrawal_audit_log):
 *   REQUESTED → PIN_VERIFIED → LOCKED → SUBMITTED → PENDING → COMPLETED | FAILED | MANUAL_REVIEW
 *
 * - Full balance only: the server reads the available balance and withdraws exactly that.
 * - PIN checked server-side against the bcrypt hash BEFORE any lock or Lipila call; 5 wrong PINs lock withdrawals
 *   for 15 minutes. The PIN never goes to Lipila.
 * - Lock first: available → 0 and the amount moves to lockedNgwee in one transaction, together with
 *   activeWithdrawalId (one in-flight withdrawal per wallet, enforced here, not just in the UI).
 * - "Pending" from Lipila is not approval. Only a Successful callback (or a Successful status lookup) completes it.
 * - Failed → lock released, balance restored, Lipila's message shown verbatim.
 * - HTTP 400/401/403/429 on submit → request was never accepted → lock released, Lipila's error shown, retry allowed.
 * - No HTTP response at all, or no callback within the timeout → reconciliation (status lookup). Inconclusive after
 *   retries → MANUAL_REVIEW with funds still locked (never auto re-credit: the money may already have moved).
 * - Every attempt has a fresh referenceId; retries link to the previous attempt (retry_of).
 */
import { randomUUID } from "node:crypto";
import bcrypt from "bcryptjs";
import { FieldValue, Timestamp, type Transaction } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { logger } from "firebase-functions/v2";
import { db, loadConfig } from "../config";
import { isFailed, isSuccessful, lipila, lipilaConfig, toAccountNumber } from "../lipila";
import { notify } from "../util";

export type WStatus = "REQUESTED" | "PIN_VERIFIED" | "LOCKED" | "SUBMITTED" | "PENDING" | "COMPLETED" | "FAILED" | "MANUAL_REVIEW";
export const TERMINAL: WStatus[] = ["COMPLETED", "FAILED"];
const ALLOWED: Record<WStatus, WStatus[]> = {
  REQUESTED: ["PIN_VERIFIED", "FAILED"], PIN_VERIFIED: ["LOCKED", "FAILED"], LOCKED: ["SUBMITTED", "FAILED"],
  SUBMITTED: ["PENDING", "FAILED", "COMPLETED", "MANUAL_REVIEW"], PENDING: ["COMPLETED", "FAILED", "MANUAL_REVIEW"],
  MANUAL_REVIEW: ["COMPLETED", "FAILED"], COMPLETED: [], FAILED: [],
};
export const canMove = (from: WStatus, to: WStatus) => ALLOWED[from]?.includes(to) ?? false;

export const PIN_MAX_FAILS = 5, PIN_LOCK_MIN = 15;
export const walletOf = (role: string, uid: string) => role === "RIDER" ? { doc: `riderWallets/${uid}`, field: "availableNgwee" } : { doc: `sellerStats/${uid}`, field: "earningsNgwee" };

const reqRef = (id: string) => db.doc(`withdrawal_requests/${id}`);
function audit(tx: Transaction | null, id: string, uid: string, from: WStatus | null, to: WStatus, actor: "system" | "rider" | "seller" | "agent" | "admin" | "lipila",
  meta: Record<string, unknown> = {}) {
  const e = { withdrawal_request_id: id, user_id: uid, from_status: from, to_status: to, actor, metadata: meta, timestamp: FieldValue.serverTimestamp() };
  const ref = db.collection("withdrawal_audit_log").doc();
  return tx ? tx.set(ref, e) : ref.set(e);
}

/** Pure: what the withdraw button should say (also used to reject at the API layer). */
export function withdrawBlock(w: { availableNgwee: number; activeWithdrawalId?: string | null }, minNgwee: number): string | null {
  if (w.activeWithdrawalId) return "Withdrawal in progress";
  if (w.availableNgwee < minNgwee) return `Minimum withdrawal is ZMW ${(minNgwee / 100).toFixed(2)}`;
  return null;
}

export interface WithdrawResult { withdrawalId: string; status: WStatus; amountNgwee: number; message: string }

/** The whole request path (called by the wallet_withdraw callable). */
export async function requestWithdrawal(uid: string, role: string, pin: string, actor: "rider" | "seller" | "agent", retryOf?: string | null): Promise<WithdrawResult> {
  const cfg = await loadConfig();
  const minNgwee = Math.round(Number(cfg.minWithdrawal) * 100);
  const w = walletOf(role, uid);
  const [walletSnap, userSnap, secRef] = [await db.doc(w.doc).get(), await db.doc(`users/${uid}`).get(), db.doc(`users/${uid}/private/security`)];
  const available = (walletSnap.get(w.field) as number | undefined) ?? 0;
  const block = withdrawBlock({ availableNgwee: available, activeWithdrawalId: walletSnap.get("activeWithdrawalId") }, minNgwee);
  if (block) throw new HttpsError("failed-precondition", block);
  const account = userSnap.get("payoutWallet") as string | undefined;
  if (!account) throw new HttpsError("failed-precondition", "Add your mobile money number under Payout wallet first.");

  // REQUESTED
  const id = randomUUID();
  const referenceId = `SELO-WD-${randomUUID()}`;
  await reqRef(id).set({ user_id: uid, rider_id: role === "RIDER" ? uid : null, role, reference_id: referenceId, lipila_identifier: null, amount_ngwee: available,
    amount: available / 100, currency: "ZMW", account_number: toAccountNumber(account), status: "REQUESTED", lipila_response_raw: null, error_message: null,
    wallet_doc: w.doc, wallet_field: w.field, retry_of: retryOf ?? null, recon_attempts: 0, created_at: FieldValue.serverTimestamp(), updated_at: FieldValue.serverTimestamp(), resolved_at: null });
  await audit(null, id, uid, null, "REQUESTED", actor, { referenceId, retryOf: retryOf ?? null });

  // PIN (server-side, bcrypt). Wrong PIN → no lock, no Lipila call.
  const sec = (await secRef.get()).data() ?? {};
  const lockedUntil = (sec.walletPinLockedUntil as Timestamp | undefined)?.toMillis() ?? 0;
  if (lockedUntil > Date.now()) {
    await finishWithoutLock(id, uid, "PIN entry is locked. Try again later.", actor, { reason: "pin_locked" });
    throw new HttpsError("resource-exhausted", `Too many incorrect PINs. Try again in ${Math.ceil((lockedUntil - Date.now()) / 60_000)} minutes.`);
  }
  const good = !!sec.pinHash && (await bcrypt.compare(String(pin ?? ""), sec.pinHash));
  if (!good) {
    const fails = (sec.walletPinFails ?? 0) + 1;
    await secRef.set({ walletPinFails: fails >= PIN_MAX_FAILS ? 0 : fails,
      walletPinLockedUntil: fails >= PIN_MAX_FAILS ? Timestamp.fromMillis(Date.now() + PIN_LOCK_MIN * 60_000) : null }, { merge: true });
    await finishWithoutLock(id, uid, "Incorrect PIN", actor, { reason: "wrong_pin", failCount: fails });
    throw new HttpsError("permission-denied", "Incorrect PIN");
  }
  await secRef.set({ walletPinFails: 0, walletPinLockedUntil: null }, { merge: true });
  await reqRef(id).update({ status: "PIN_VERIFIED", updated_at: FieldValue.serverTimestamp() });
  await audit(null, id, uid, "REQUESTED", "PIN_VERIFIED", actor);

  // LOCK the full balance (one transaction; re-checks the in-flight guard and balance).
  const amount = await db.runTransaction(async (tx) => {
    const s = await tx.get(db.doc(w.doc));
    const bal = (s.get(w.field) as number | undefined) ?? 0;
    const b = withdrawBlock({ availableNgwee: bal, activeWithdrawalId: s.get("activeWithdrawalId") }, minNgwee);
    if (b) { tx.update(reqRef(id), { status: "FAILED", error_message: b, updated_at: FieldValue.serverTimestamp(), resolved_at: FieldValue.serverTimestamp() });
      audit(tx, id, uid, "PIN_VERIFIED", "FAILED", "system", { reason: b }); return -1; }
    tx.set(db.doc(w.doc), { [w.field]: 0, lockedNgwee: FieldValue.increment(bal), activeWithdrawalId: id }, { merge: true });
    tx.update(reqRef(id), { status: "LOCKED", amount_ngwee: bal, amount: bal / 100, balance_before: bal, updated_at: FieldValue.serverTimestamp() });
    audit(tx, id, uid, "PIN_VERIFIED", "LOCKED", "system", { amountNgwee: bal });
    return bal;
  });
  if (amount < 0) throw new HttpsError("failed-precondition", "Withdrawal in progress");

  // SUBMIT to Lipila
  await reqRef(id).update({ status: "SUBMITTED", submitted_at: FieldValue.serverTimestamp(), updated_at: FieldValue.serverTimestamp() });
  await audit(null, id, uid, "LOCKED", "SUBMITTED", "system", { referenceId });
  const r = await lipila.disburse(referenceId, amount, account, `Selonachipa ${role.toLowerCase()} withdrawal - ${uid}`);
  const raw = r.raw ?? null;

  if (r.ok && /pending/i.test(String(r.lipilaStatus ?? "Pending"))) {
    await reqRef(id).update({ status: "PENDING", lipila_identifier: r.identifier ?? null, lipila_response_raw: raw, pending_since: FieldValue.serverTimestamp(), updated_at: FieldValue.serverTimestamp() });
    await audit(null, id, uid, "SUBMITTED", "PENDING", "lipila", { identifier: r.identifier ?? null, response: raw });
    return { withdrawalId: id, status: "PENDING", amountNgwee: amount, message: "Withdrawal submitted. We'll notify you when your mobile money provider confirms it." };
  }
  if (r.ok && isSuccessful(r.lipilaStatus)) { await complete(id, raw, "lipila"); return { withdrawalId: id, status: "COMPLETED", amountNgwee: amount, message: "Withdrawal sent." }; }
  if (r.ok && isFailed(r.lipilaStatus)) { await fail(id, r.message ?? "Withdrawal failed", raw, "lipila"); throw new HttpsError("aborted", r.message ?? "Withdrawal failed"); }

  if (r.network || r.status >= 500 || r.status === 0 && !r.message?.startsWith("Payments are not configured")) {
    // We don't know whether Lipila accepted it: keep the lock and let reconciliation decide.
    await reqRef(id).update({ lipila_response_raw: raw, pending_since: FieldValue.serverTimestamp(), error_message: r.message ?? `HTTP ${r.status}`, updated_at: FieldValue.serverTimestamp() });
    await audit(null, id, uid, "SUBMITTED", "SUBMITTED", "system", { note: "no definitive response; awaiting reconciliation", httpStatus: r.status });
    return { withdrawalId: id, status: "SUBMITTED", amountNgwee: amount, message: "We're confirming your withdrawal, this may take a few minutes longer." };
  }
  // 4xx (incl. 429 after backoff) or not configured: the request was never accepted → release the lock.
  const msg = r.message ?? `Lipila rejected the request (HTTP ${r.status}).`;
  await fail(id, msg, raw, "lipila", { httpStatus: r.status });
  if ([400, 401, 403].includes(r.status) || r.status === 0) await alertOps(`Lipila disbursement rejected with HTTP ${r.status || "config"}`, { withdrawalId: id, message: msg });
  throw new HttpsError(r.status === 429 ? "resource-exhausted" : "failed-precondition", r.status === 429 ? "Mobile money is busy right now. Please try again in a minute." : msg);
}

async function finishWithoutLock(id: string, uid: string, msg: string, actor: "rider" | "seller" | "agent", meta: Record<string, unknown>) {
  await reqRef(id).update({ status: "FAILED", error_message: msg, updated_at: FieldValue.serverTimestamp(), resolved_at: FieldValue.serverTimestamp() });
  await audit(null, id, uid, "REQUESTED", "FAILED", actor, meta);
}

/** Successful: permanently debit (drop the lock). Idempotent. */
export async function complete(id: string, raw: unknown, actor: "lipila" | "system" | "admin", meta: Record<string, unknown> = {}): Promise<boolean> {
  const done = await db.runTransaction(async (tx) => {
    const w = (await tx.get(reqRef(id))).data();
    if (!w || !canMove(w.status, "COMPLETED")) return null;
    tx.set(db.doc(w.wallet_doc), { lockedNgwee: FieldValue.increment(-w.amount_ngwee), activeWithdrawalId: null }, { merge: true });
    tx.update(reqRef(id), { status: "COMPLETED", lipila_callback_raw: raw ?? null, error_message: null, updated_at: FieldValue.serverTimestamp(), resolved_at: FieldValue.serverTimestamp() });
    tx.set(db.collection(`ledgers/${w.user_id}/entries`).doc(), { type: "withdrawal", gross: w.amount_ngwee, fee: 0, net: w.amount_ngwee, lipilaRef: w.reference_id, withdrawalId: id, at: FieldValue.serverTimestamp() });
    audit(tx, id, w.user_id, w.status, "COMPLETED", actor, { ...meta, payload: raw ?? null });
    return w;
  });
  if (done) await notify(done.user_id, "Withdrawal sent", `ZMW ${(done.amount_ngwee / 100).toFixed(2)} has been sent to ${done.account_number}${done.lipila_identifier ? ` (ref ${done.lipila_identifier})` : ""}.`, "WITHDRAWAL_COMPLETED").catch(() => undefined);
  return !!done;
}

/** Failed: release the lock and restore the balance. Idempotent. */
export async function fail(id: string, message: string, raw: unknown, actor: "lipila" | "system" | "admin", meta: Record<string, unknown> = {}): Promise<boolean> {
  const done = await db.runTransaction(async (tx) => {
    const w = (await tx.get(reqRef(id))).data();
    if (!w || !canMove(w.status, "FAILED")) return null;
    const locked = ["LOCKED", "SUBMITTED", "PENDING", "MANUAL_REVIEW"].includes(w.status);
    if (locked) tx.set(db.doc(w.wallet_doc), { [w.wallet_field]: FieldValue.increment(w.amount_ngwee), lockedNgwee: FieldValue.increment(-w.amount_ngwee), activeWithdrawalId: null }, { merge: true });
    tx.update(reqRef(id), { status: "FAILED", error_message: message, lipila_callback_raw: raw ?? null, updated_at: FieldValue.serverTimestamp(), resolved_at: FieldValue.serverTimestamp() });
    audit(tx, id, w.user_id, w.status, "FAILED", actor, { ...meta, message, payload: raw ?? null, restored: locked });
    return locked ? w : null;
  });
  if (done) await notify(done.user_id, "Withdrawal failed", `${message} Your ZMW ${(done.amount_ngwee / 100).toFixed(2)} is back in your balance.`, "WITHDRAWAL_FAILED").catch(() => undefined);
  return !!done;
}

export async function toManualReview(id: string, reason: string) {
  const w = await db.runTransaction(async (tx) => {
    const x = (await tx.get(reqRef(id))).data();
    if (!x || !canMove(x.status, "MANUAL_REVIEW")) return null;
    tx.update(reqRef(id), { status: "MANUAL_REVIEW", error_message: reason, updated_at: FieldValue.serverTimestamp() });
    audit(tx, id, x.user_id, x.status, "MANUAL_REVIEW", "system", { reason });
    return x;
  });
  if (!w) return;
  await alertOps(`Withdrawal ${id} needs manual review: ${reason}`, { withdrawalId: id, amountNgwee: w.amount_ngwee, referenceId: w.reference_id });
  await notify(w.user_id, "Confirming your withdrawal", "We're confirming your withdrawal, this may take a few minutes longer.", "WITHDRAWAL_REVIEW").catch(() => undefined);
}

async function alertOps(message: string, data: Record<string, unknown>) {
  logger.error(message, data);
  await db.collection("opsAlerts").add({ type: "WITHDRAWAL", message, ...data, status: "OPEN", at: FieldValue.serverTimestamp() }).catch(() => undefined);
}

/** Applies a Lipila callback / status for a withdrawal reference. */
export async function applyLipilaStatus(referenceId: string, status: string, message: string | undefined, raw: unknown, actor: "lipila" | "system", lipilaRef?: string): Promise<"completed" | "failed" | "ignored" | "not_found"> {
  const q = await db.collection("withdrawal_requests").where("reference_id", "==", referenceId).limit(1).get();
  const d = q.docs[0] ?? (lipilaRef ? (await db.collection("withdrawal_requests").where("lipila_identifier", "==", lipilaRef).limit(1).get()).docs[0] : undefined);
  if (!d) return "not_found";
  if (isSuccessful(status)) return (await complete(d.id, raw, actor)) ? "completed" : "ignored";
  if (isFailed(status)) return (await fail(d.id, message || "The mobile money provider declined the withdrawal.", raw, actor)) ? "failed" : "ignored";
  return "ignored";
}

/**
 * Reconciliation: withdrawals still SUBMITTED/PENDING after the timeout are checked with Lipila. Successful/Failed
 * resolve them; anything else is retried up to 3 times, then MANUAL_REVIEW (funds stay locked).
 */
export async function reconcileWithdrawals(nowMs = Date.now()) {
  const cfg = await loadConfig();
  const timeoutMs = Number(cfg.withdrawalWebhookTimeoutSec ?? 300) * 1000;
  const due = await db.collection("withdrawal_requests").where("status", "in", ["SUBMITTED", "PENDING"]).where("pending_since", "<=", Timestamp.fromMillis(nowMs - timeoutMs)).limit(50).get();
  for (const d of due.docs) {
    const w = d.data();
    const s = await lipila.status("disbursement", w.reference_id, w.lipila_identifier).catch(() => null);
    if (s && isSuccessful(s.status)) { await complete(d.id, s.raw, "system", { via: "reconciliation" }); continue; }
    if (s && isFailed(s.status)) { await fail(d.id, s.message || "The mobile money provider declined the withdrawal.", s.raw, "system", { via: "reconciliation" }); continue; }
    const attempts = (w.recon_attempts ?? 0) + 1;
    await d.ref.update({ recon_attempts: attempts, last_recon_at: FieldValue.serverTimestamp(), last_recon_result: s?.status ?? "unreachable_or_not_configured" });
    await audit(null, d.id, w.user_id, w.status, w.status, "system", { reconciliation: attempts, result: s?.status ?? null });
    if (attempts >= 3 || (!s && !lipilaConfig().disbursementStatusPath)) await toManualReview(d.id, s ? `Lipila still reports "${s.status}" after ${attempts} checks.` : "No callback and the status lookup is unavailable.");
  }
  return due.size;
}
