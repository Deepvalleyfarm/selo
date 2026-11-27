/**
 * Partner settlement ledger (openLedger/{pid}/entries). Product sales are never recorded here — they belong to the
 * originating platform's own checkout and books. Separate record types:
 *   DELIVERY_CHARGE   − the full SeloNachipa delivery fee for each delivery the platform requested (it collected it
 *                       from its buyer and owes it to SeloNachipa, which splits it to rider / platform / Social Fund)
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

export type EntryType = "DELIVERY_CHARGE" | "DELIVERY_REVERSAL" | "API_USAGE" | "SUBSCRIPTION" | "SETTLEMENT";
export interface Entry { type: EntryType; amountNgwee: number; refType: "delivery" | "month" | "manual"; refId: string; env: Env; note?: string }
const SIGN: Record<EntryType, 1 | -1 | 0> = { DELIVERY_CHARGE: -1, DELIVERY_REVERSAL: 1, API_USAGE: -1, SUBSCRIPTION: -1, SETTLEMENT: 0 };

export const entryId = (e: Pick<Entry, "type" | "refType" | "refId">) => `le_${sha256(`${e.type}|${e.refType}|${e.refId}`).slice(0, 24)}`;
/** Signed amount from the partner's point of view (negative = the partner owes SeloNachipa). SETTLEMENT carries its own sign (+ = partner paid SeloNachipa). */
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
  return { byType: by, balanceNgwee: balance, direction: balance < 0 ? "Partner owes SeloNachipa" : balance > 0 ? "SeloNachipa owes partner" : "Settled" };
}
