/**
 * Partner settlement ledger (openLedger/{pid}/entries). Separate record types, never mixed:
 *   SALE              + goods value of a partner product sold on SeloNachipa (owed to the partner)
 *   PLATFORM_FEE      − SeloNachipa's flat order fee on that sale
 *   COMMISSION        − marketplace commission (partner-specific %)
 *   DELIVERY_CHARGE   − deliveries the partner requested (owed by the partner)
 *   DELIVERY_REVERSAL + a cancelled delivery's charge returned
 *   API_USAGE / SUBSCRIPTION − monthly charges
 *   SETTLEMENT        ± money actually paid between SeloNachipa and the partner (recorded by finance)
 * Rider earnings stay in the existing rider ledger; platform revenue in platformRevenue. Entries are keyed by a
 * deterministic id so a retried trigger can't post twice.
 */
import { FieldValue } from "firebase-admin/firestore";
import type { Transaction } from "firebase-admin/firestore";
import { db } from "../config";
import { sha256 } from "./crypto";
import type { Env } from "./model";

export type EntryType = "SALE" | "PLATFORM_FEE" | "COMMISSION" | "DELIVERY_CHARGE" | "DELIVERY_REVERSAL" | "API_USAGE" | "SUBSCRIPTION" | "SETTLEMENT";
export interface Entry { type: EntryType; amountNgwee: number; refType: "order" | "partnerOrder" | "delivery" | "month" | "manual"; refId: string; env: Env; note?: string }
const SIGN: Record<EntryType, 1 | -1 | 0> = { SALE: 1, PLATFORM_FEE: -1, COMMISSION: -1, DELIVERY_CHARGE: -1, DELIVERY_REVERSAL: 1, API_USAGE: -1, SUBSCRIPTION: -1, SETTLEMENT: 0 };

export const entryId = (e: Pick<Entry, "type" | "refType" | "refId">) => `le_${sha256(`${e.type}|${e.refType}|${e.refId}`).slice(0, 24)}`;
/** Signed amount from the partner's point of view (+ = SeloNachipa owes the partner). SETTLEMENT carries its own sign. */
export const signed = (e: Pick<Entry, "type" | "amountNgwee">) => (SIGN[e.type] === 0 ? e.amountNgwee : SIGN[e.type] * Math.abs(e.amountNgwee));

export async function post(pid: string, e: Entry, tx?: Transaction) {
  const ref = db.doc(`openLedger/${pid}/entries/${entryId(e)}`);
  const doc = { ...e, pid, signedNgwee: signed(e), at: FieldValue.serverTimestamp() };
  if (tx) tx.create(ref, doc); else await ref.create(doc).catch(() => undefined); // already posted → no double entry
}

export function summarise(entries: Pick<Entry, "type" | "amountNgwee">[]) {
  const by: Record<string, number> = {};
  let balance = 0;
  for (const e of entries) { const s = signed(e); by[e.type] = (by[e.type] ?? 0) + s; balance += s; }
  return { byType: by, balanceNgwee: balance, direction: balance >= 0 ? "SeloNachipa owes partner" : "Partner owes SeloNachipa" };
}
