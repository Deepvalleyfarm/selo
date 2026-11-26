/**
 * Payment background jobs and admin tools.
 *  - payments_reconcile (every minute): withdrawals with no callback after the timeout are checked with Lipila;
 *    collections stuck "pending" for 5+ minutes are checked too (results go through the same idempotent event path).
 *  - admin_resolveWithdrawal: finance resolves a MANUAL_REVIEW withdrawal after checking the Lipila dashboard.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { LIPILA_API_KEY, db } from "../config";
import { isFailed, isSuccessful, lipila } from "../lipila";
import { audit as adminAudit, requireAdmin, str } from "../util";
import { complete, fail, reconcileWithdrawals } from "./withdrawals";

export async function reconcileCollections(nowMs = Date.now()) {
  const cutoff = Timestamp.fromMillis(nowMs - 5 * 60_000);
  const stuck = await db.collection("orders").where("state", "==", "INITIATED").where("paymentStatus", "==", "PENDING").where("createdAt", "<=", cutoff).limit(25).get().catch(() => null);
  for (const d of stuck?.docs ?? []) {
    const ref = d.get("lipilaCollectionRef") as string | undefined; if (!ref) continue;
    const s = await lipila.status("collection", ref).catch(() => null);
    if (s && (isSuccessful(s.status) || isFailed(s.status)))
      await db.doc(`lipila_webhook_events/recon_${ref}_${s.status.replace(/\W+/g, "_")}`).create({ webhookId: null, referenceId: ref, identifier: ref, status: s.status,
        message: s.message ?? null, type: "Collection", currency: "ZMW", amount: (d.get("totalNgwee") ?? 0) / 100, accountNumber: String(d.get("walletMsisdn") ?? "").replace(/\D/g, ""),
        paymentType: "", rawPayload: s.raw ?? null, source: "reconciliation", processedAt: FieldValue.serverTimestamp(), processed: false }).catch(() => undefined);
  }
}

export const payments_reconcile = onSchedule({ schedule: "every 1 minutes", secrets: [LIPILA_API_KEY], timeoutSeconds: 120 }, async () => {
  await reconcileWithdrawals();
  await reconcileCollections();
});

export const admin_resolveWithdrawal = onCall(async (req) => {
  const a = requireAdmin(req, "finance");
  const id = str(req.data?.withdrawalId, "withdrawal");
  const outcome = String(req.data?.outcome);
  const note = str(req.data?.note, "note", 500);
  if (note.length < 10) throw new HttpsError("invalid-argument", "Explain what you checked on the Lipila dashboard (min. 10 characters).");
  const w = (await db.doc(`withdrawal_requests/${id}`).get()).data();
  if (!w) throw new HttpsError("not-found", "Withdrawal not found.");
  if (w.status !== "MANUAL_REVIEW") throw new HttpsError("failed-precondition", `Only withdrawals in manual review can be resolved (this one is ${w.status}).`);
  const meta = { by: a.email, note };
  const ok = outcome === "completed" ? await complete(id, { admin: a.email, note }, "admin", meta)
    : outcome === "failed" ? await fail(id, "Your mobile money provider did not complete this withdrawal.", { admin: a.email, note }, "admin", meta)
    : (() => { throw new HttpsError("invalid-argument", "Outcome must be completed or failed."); })();
  await adminAudit(a, `withdrawal.${outcome}`, `withdrawal_requests/${id}`, { status: "MANUAL_REVIEW" }, { status: outcome.toUpperCase(), note });
  return { ok };
});
