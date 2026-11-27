import { useEffect, useState, useSyncExternalStore } from "react";
import { collection, doc, onSnapshot, query, setDoc, serverTimestamp, type QueryConstraint } from "firebase/firestore";
import { httpsCallable } from "firebase/functions";
import { auth, db, fns, isDemo } from "./firebase";
import { demo, demoAudit, demoChanged, demoPatch, demoSubscribe } from "./demo";

export type Row = Record<string, any> & { id: string };

/**
 * Live Firestore listener. Every table in the back office uses this, so anything the Android app
 * writes (orders, listings, KYC submissions, rider presence) appears here within seconds.
 */
export function useCollection(path: string, constraints: QueryConstraint[] = [], key = ""): { rows: Row[]; loading: boolean; error?: string } {
  const demoRows = useSyncExternalStore(demoSubscribe, () => demo[path] ?? EMPTY);
  const [state, set] = useState<{ rows: Row[]; loading: boolean; error?: string }>({ rows: [], loading: !isDemo });
  useEffect(() => {
    if (isDemo || !db) return;
    return onSnapshot(query(collection(db, path), ...constraints),
      (s) => set({ rows: s.docs.map((d) => ({ id: d.id, ...d.data() })), loading: false }),
      (e) => set({ rows: [], loading: false, error: e.message }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, key]);
  return isDemo ? { rows: demoRows, loading: false } : state;
}
const EMPTY: Row[] = [];

export function useDoc(path: string): Row | null {
  const [coll, id] = [path.split("/").slice(0, -1).join("/"), path.split("/").pop()!];
  const demoRow = useSyncExternalStore(demoSubscribe, () => demo[coll]?.find((r) => r.id === id) ?? null);
  const [row, setRow] = useState<Row | null>(null);
  useEffect(() => {
    if (isDemo || !db) return;
    return onSnapshot(doc(db, path), (s) => setRow(s.exists() ? { id: s.id, ...s.data() } : null));
  }, [path]);
  return isDemo ? demoRow : row;
}

/** Demo-mode stand-ins for the admin callables, so every button works without a backend. */
const demoFns: Record<string, (d: any) => unknown> = {
  admin_endPromotion: (d) => { const p = demo.promotions.find((x) => x.id === d.promoId); if (!p) throw new Error("Promotion not found.");
    demoPatch("promotions", d.promoId, { status: "ENDED", endReason: d.reason }); demoAudit("promotion.end", `promotions/${d.promoId}`, { status: "ACTIVE" }, { status: "ENDED", reason: d.reason });
    return { ok: true }; },
  admin_updateConfig: (d) => { const cur = demo.platformConfig[0]; demoAudit("config.update", "platformConfig/default",
    Object.fromEntries(Object.keys(d.changes).map((k) => [k, cur[k]])), d.changes);
    demoPatch("platformConfig", "default", { ...d.changes, version: (cur.version as number) + 1, updatedBy: "you (demo)", updatedAt: new Date() }); },
  admin_setKyc: (d) => { demoPatch("users", d.uid, { accountStatus: d.approve ? "ACTIVE" : "PENDING_KYC", kycStatus: d.approve ? "VERIFIED" : "REJECTED" });
    demo.kycQueue = demo.kycQueue.filter((r) => r.id !== d.uid); demoAudit(d.approve ? "kyc.approve" : "kyc.reject", `users/${d.uid}`, null, { reason: d.reason ?? null }); demoChanged(); },
  admin_suspendUser: (d) => { demoPatch("users", d.uid, { accountStatus: d.suspend === false ? "ACTIVE" : "SUSPENDED" }); demoAudit("user.suspend", `users/${d.uid}`, null, { suspend: d.suspend }); },
  admin_retryPayout: (d) => { demoPatch("payouts", d.ref, { status: "DISBURSED", attempts: 0 }); demoAudit(d.markPaid ? "payout.mark_paid" : "payout.retry", `payouts/${d.ref}`, null, null); },
  admin_resolveDispute: (d) => { demoPatch("disputes", d.disputeId, { status: "RESOLVED", resolution: d.outcome }); demoAudit(`dispute.${d.outcome}`, `disputes/${d.disputeId}`, null, null); },
  admin_decideClaim: (d) => { demoPatch("socialFundClaims", d.claimId, { status: d.approve ? "APPROVED" : "REJECTED" }); demoAudit("claim", `socialFundClaims/${d.claimId}`, null, { approve: d.approve }); },
  admin_moderateListing: (d) => { demoPatch("listings", d.listingId, { status: d.status }); demoAudit("listing.moderate", `listings/${d.listingId}`, null, { status: d.status }); },
  admin_issueResetToken: (d) => ({ resetToken: `${d.uid}.demo-token` }),
  admin_markPremiumsRemitted: (d) => { const rows = demo.riderCoverPremiums.filter((r) => d.ids.includes(r.id));
    const total = rows.reduce((s, r) => s + (r.amountNgwee as number), 0);
    if (rows.some((r) => r.remitted)) throw new Error("Some selected premiums are already marked as paid. Refresh and try again.");
    if (Math.round(d.amountNgwee) !== total) throw new Error(`The amount paid doesn't match the selected premiums (K ${(total / 100).toFixed(2)}).`);
    const id = `rem${Date.now()}`; rows.forEach((r) => demoPatch("riderCoverPremiums", r.id, { remitted: true, remittanceId: id }));
    demo.coverRemittances = [{ id, insurer: rows[0].insurer, reference: d.reference, amountNgwee: total, count: rows.length, by: "you (demo)", note: d.note || null, at: new Date() }, ...demo.coverRemittances];
    demoAudit("riderCover.remit", `coverRemittances/${id}`, null, { amountNgwee: total, reference: d.reference }); demoChanged(); return { ok: true }; },
  admin_setRiderCover: (d) => { demoPatch("riderCover", "active", { ...d, updatedAt: new Date() }); demoAudit(d.enabled ? "riderCover.enable" : "riderCover.disable", "riderCover/active", null, d); },
  admin_sendNotification: () => { demoAudit("notification.send", "users", null, null); },
  admin_updateGuardrails: (d) => { const cur = demo.platformConfig.find((r) => r.id === "guardrails")!;
    if (d.changes.enabled === false) throw new Error("Turning guardrails off needs a CEO-level override.");
    if (Number(d.changes.sessionCapMinutes ?? 0) > 145) throw new Error("A cap above 145 minutes needs a CEO-level override.");
    demoAudit("guardrails.update", "platformConfig/guardrails", cur, d.changes); demoPatch("platformConfig", "guardrails", { ...d.changes, updatedBy: "you (demo)", updatedAt: new Date() }); },
  admin_rollbackAlgorithm: (d) => { const old = demo.algorithmState.find((r) => r.id === d.versionId)!; const cur = demo.algorithmState.find((r) => r.id === "current")!;
    const version = (cur.version as number) + 1; demo.algorithmState = [{ ...cur, ...old, id: "current", version, reason: `rollback to ${d.versionId}` },
      { id: `v${String(version).padStart(6, "0")}`, version, reason: `rollback to ${d.versionId}`, createdBy: "you (demo)", createdAt: new Date(), changes: [{ rule: "rollback", detail: `replayed ${d.versionId}` }] },
      ...demo.algorithmState.filter((r) => r.id !== "current")]; demoAudit("algorithm.rollback", "algorithmState/current", null, { version }); demoChanged(); return { version }; },
  admin_exportLeads: () => ({ rows: demo.leads, shareable: demo.leads.filter((r) => r.consent) }),
  admin_overrideDelivery: (d) => { const coll = d.kind === "parcel" ? "parcels" : "orders"; const p = demo[coll].find((x) => x.id === d.orderId);
    if (!p) throw new Error("Not found."); if (p.escrowStatus === "RELEASED") throw new Error("Escrow was already released.");
    const f: any = p.fees ?? {}; const statusKey = d.kind === "parcel" ? "status" : "state";
    demoPatch(coll, d.orderId, { [statusKey]: "DELIVERED", escrowStatus: "RELEASED", deliveryOverride: true, codeSummary: { ...(p.codeSummary ?? {}), state: "overridden" },
      verification: { override: true, by: "you (demo)", reason: d.reason } });
    demo.escrow_ledger = [...[["delivery_fee", "rider", f.riderEarningsNgwee], ["delivery_fee", "platform", f.platformFeeNgwee], ["delivery_fee", "rider_social_fund", f.socialFundNgwee], ["sending_fee", "platform", f.sendingNgwee]]
      .filter(([, , a]) => a).map(([leg, party, a], i) => ({ id: `lo${Date.now()}${i}`, kind: d.kind, orderId: d.orderId, type: "RELEASED", leg, party, amountNgwee: a, override: true, by: "you (demo)", at: new Date() })), ...demo.escrow_ledger];
    demo.admin_audit_log = [{ id: `al${Date.now()}`, action: "delivery.override", kind: d.kind, orderId: d.orderId, actorEmail: "you (demo)", reason: d.reason, evidence: d.evidence, riderEarningsNgwee: f.riderEarningsNgwee, at: new Date() }, ...demo.admin_audit_log];
    demoChanged(); return { ok: true }; },
  admin_refundParcel: (d) => { const p: any = demo.parcels.find((x) => x.id === d.parcelId)!; const amt = d.amountNgwee ?? p.fees.totalNgwee;
    if (amt > p.fees.totalNgwee) throw new Error("Refund is more than the sender paid.");
    demoPatch("parcels", d.parcelId, { escrowStatus: "REFUNDED", status: p.status === "DELIVERY_FAILED" || p.status === "DISPUTED" ? p.status : "CANCELLED" });
    demo.escrow_ledger = [{ id: `lr${Date.now()}`, kind: "parcel", orderId: d.parcelId, type: "REFUNDED", leg: "delivery_fee", party: "payer", amountNgwee: amt, override: true, by: "you (demo)", at: new Date() }, ...demo.escrow_ledger];
    demo.admin_audit_log = [{ id: `al${Date.now()}`, action: "parcel.refund", kind: "parcel", orderId: d.parcelId, actorEmail: "you (demo)", reason: d.reason, amountNgwee: amt, at: new Date() }, ...demo.admin_audit_log];
    demoChanged(); return { refundedNgwee: amt }; },
  admin_editReceiver: (d) => { if (!/^(\+?260|0)?(95|96|97|76|77)\d{7}$/.test(String(d.msisdn).replace(/\s/g, ""))) throw new Error("Enter a valid Zambian mobile number.");
    const p: any = demo.parcels.find((x) => x.id === d.parcelId)!; demoPatch("parcels", d.parcelId, { receiver: { ...p.receiver, msisdn: d.msisdn } });
    demo.admin_audit_log = [{ id: `al${Date.now()}`, action: "parcel.edit_receiver", kind: "parcel", orderId: d.parcelId, actorEmail: "you (demo)", reason: d.reason, at: new Date() }, ...demo.admin_audit_log]; demoChanged(); },
  admin_replyTicket: (d) => { const path = `supportTickets/${d.ticketId}/messages`;
    demo[path] = [...(demo[path] ?? []), { id: `m${Date.now()}`, from: "support", agent: "you (demo)", text: d.text, at: new Date() }];
    demoPatch("supportTickets", d.ticketId, { status: d.close ? "RESOLVED" : "AWAITING_USER", lastMessage: d.text.slice(0, 140), lastFrom: "support", updatedAt: new Date() }); },
  admin_beemHealth: () => { demoPatch("beem_health", "current", { checkedAt: Date.now(), state: "LIVE" }); return demo.beem_health[0]; },
  admin_resolveWithdrawal: (d) => { const w = demo.withdrawal_requests.find((x) => x.id === d.withdrawalId);
    if (!w || w.status !== "MANUAL_REVIEW") throw new Error("Only withdrawals in manual review can be resolved.");
    if (String(d.note ?? "").trim().length < 10) throw new Error("Explain what you checked on the Lipila dashboard (min. 10 characters).");
    demoPatch("withdrawal_requests", d.withdrawalId, { status: d.outcome === "completed" ? "COMPLETED" : "FAILED", error_message: d.outcome === "failed" ? "Your mobile money provider did not complete this withdrawal." : null });
    demo.withdrawal_audit_log = [...demo.withdrawal_audit_log, { id: `wa${Date.now()}`, withdrawal_request_id: d.withdrawalId, from_status: "MANUAL_REVIEW", to_status: d.outcome === "completed" ? "COMPLETED" : "FAILED",
      actor: "admin", metadata: { by: "you (demo)", note: d.note }, timestamp: new Date() }]; demoChanged(); return { ok: true }; },
  admin_txSearch: (d) => { const q = String(d.q ?? "").toLowerCase();
    const all = (demo.lipila_txs as any[]).filter((t) => (!d.status || t.status === d.status) && (!d.paymentType || t.paymentType === d.paymentType) && (!d.type || t.type === d.type)
      && (!d.dateFrom || t.processedAt >= `${d.dateFrom}T00:00:00`) && (!d.dateTo || t.processedAt <= `${d.dateTo}T23:59:59.999Z`)
      && (!q || [t.referenceId, t.identifier, t.accountNumber, t.externalId, t.message].some((v) => String(v ?? "").toLowerCase().includes(q.replace(/^0/, "260")) || String(v ?? "").toLowerCase().includes(q))));
    const start = d.cursor ? all.findIndex((t) => t.id === d.cursor) + 1 : 0; const page = all.slice(start, start + (d.pageSize ?? 50));
    return { data: page.map(({ rawPayload, ...r }) => r), total: all.length, nextCursor: start + page.length < all.length ? page[page.length - 1].id : null }; },
  admin_txGet: (d) => { const t = (demo.lipila_txs as any[]).find((x) => x.id === d.id); if (!t) throw new Error("Transaction not found."); const { rawPayload: _r, id: _i, processed: _p, source: _s, merchantId: _m, webhookId: _w, ...payload } = t; return { ...t, rawPayload: payload }; },
  admin_txStats: (d) => { const rows = (demo.lipila_txs as any[]).filter((t) => (!d.dateFrom || t.processedAt >= `${d.dateFrom}T00:00:00`) && (!d.dateTo || t.processedAt <= `${d.dateTo}T23:59:59.999Z`));
    const by = (k: string) => Object.entries(rows.reduce<Record<string, number>>((m, t: any) => { m[t[k]] = (m[t[k]] ?? 0) + 1; return m; }, {}));
    const failRe = /^(Failed|Wrong PIN|Wrong Number|Insufficient Funds|Transaction Limit Exceeded|Cancelled|Timeout|Network Error|System Error)$/;
    return { totalCount: rows.length, successCount: rows.filter((t) => t.status === "Successful").length, pendingCount: rows.filter((t) => t.status === "Pending").length,
      failedCount: rows.filter((t) => failRe.test(t.status)).length, totalVolumeNgwee: Math.round(rows.filter((t) => t.status === "Successful" && t.type === "Collection").reduce((s, t) => s + t.amount, 0) * 100),
      byStatus: by("status").map(([status, count]) => ({ status, count })).sort((a, b) => b.count - a.count),
      byPaymentType: by("paymentType").map(([paymentType, count]) => ({ paymentType, count, volumeNgwee: 0 })).sort((a, b) => b.count - a.count) }; },
  admin_txExport: (d) => { const rows = (demoFns.admin_txSearch({ ...d, pageSize: 10_000 }) as { data: any[] }).data;
    const cell = (v: unknown) => { const s = v == null ? "" : typeof v === "number" ? v.toFixed(2) : String(v); return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; };
    const cols = [["Date/Time", "processedAt"], ["Reference ID", "referenceId"], ["Internal ID", "identifier"], ["External ID (MNO)", "externalId"], ["Mobile Number", "accountNumber"], ["Amount", "amount"],
      ["Currency", "currency"], ["Status", "status"], ["Payment Type", "paymentType"], ["Transaction Type", "type"], ["Message", "message"], ["Narration", "referenceData"]];
    return { filename: `selonachipa-transactions-${new Date().toISOString().slice(0, 10)}.csv`, csv: "\uFEFF" + [cols.map((c) => c[0]).join(","), ...rows.map((r) => cols.map(([, k]) => cell(r[k])).join(","))].join("\r\n"), rows: rows.length }; },
  admin_setRole: (d) => { demoPatch("admins", d.email, { email: d.email, role: d.role }); demoAudit("admin.set_role", "admins", null, d); },
};

export async function callAdmin<T = any>(name: string, data: Record<string, unknown>): Promise<T> {
  if (isDemo) { await new Promise((r) => setTimeout(r, 350)); return (demoFns[name]?.(data) ?? { ok: true }) as T; } // demo stand-ins may throw like the real API
  const res = await httpsCallable(fns!, name)(data);
  return res.data as T;
}

/** Direct writes are only used for content/zones; rules restrict them to the right admin roles. */
export async function writeDoc(path: string, data: Record<string, unknown>) {
  const [coll, id] = [path.split("/").slice(0, -1).join("/"), path.split("/").pop()!];
  if (isDemo) { demoPatch(coll, id, data); demoAudit("content.write", path, null, data); return; }
  await setDoc(doc(db!, path), { ...data, updatedBy: auth?.currentUser?.uid ?? null, updatedAt: serverTimestamp() }, { merge: true });
}
