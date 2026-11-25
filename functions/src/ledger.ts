/**
 * Append-only escrow ledger (§9.4). Every movement of buyer/sender money is a separate entry:
 * HELD on payment, RELEASED per leg on a verified delivery code (or admin override), REFUNDED on cancellation.
 * Clients can never write here (security rules); entries are never updated or deleted.
 */
import { FieldValue, type Transaction } from "firebase-admin/firestore";
import { db } from "./config";

export type OrderKind = "parcel" | "order";
export type LedgerType = "HELD" | "RELEASED" | "REFUNDED";
export type Leg = "delivery_fee" | "sending_fee" | "insurance" | "goods" | "service_fee" | "processing_fee"
  | "rider_earnings" | "platform_fee" | "social_fund" | "seller_proceeds" | "hero_bonus" | "refund";

export interface LedgerEntry {
  kind: OrderKind; orderId: string; type: LedgerType; leg: Leg; amountNgwee: number;
  party: "escrow" | "rider" | "platform" | "rider_social_fund" | "seller" | "payer";
  partyUid?: string | null; balanceBefore?: number | null; balanceAfter?: number | null;
  override?: boolean; by?: string; ref?: string | null; note?: string | null;
}

export function writeLedger(tx: Transaction | FirebaseFirestore.WriteBatch, e: LedgerEntry) {
  if (!Number.isFinite(e.amountNgwee) || e.amountNgwee < 0) throw new Error(`bad ledger amount ${e.amountNgwee}`);
  const ref = db.collection("escrow_ledger").doc();
  (tx as Transaction).set(ref, { override: false, partyUid: null, balanceBefore: null, balanceAfter: null, by: "system", ref: null, note: null,
    ...e, at: FieldValue.serverTimestamp() });
  return ref.id;
}

/** Guard document that makes each escrow release happen exactly once (§9.4 idempotency). */
export const releaseGuard = (kind: OrderKind, id: string) => db.doc(`escrow_releases/${kind}_${id}`);
export const coll = (kind: OrderKind) => (kind === "parcel" ? "parcels" : "orders");
export const statusField = (kind: OrderKind) => (kind === "parcel" ? "status" : "state");
