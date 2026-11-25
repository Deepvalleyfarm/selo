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
