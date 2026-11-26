/**
 * Payment receipts. Every confirmed payment (order, parcel, Boost / Feed Story, registration fee) gets a receipt
 * issued to the payer by SeloNaChipa on behalf of the seller, agent or rider (or SeloNaChipa itself for platform
 * fees). Receipts are idempotent per payment reference, readable by the payer and the party they're issued on behalf
 * of, and verifiable on a public page (/r/{token}) that the app shares on WhatsApp or by email.
 */
import { createHash, randomBytes } from "node:crypto";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { onRequest } from "firebase-functions/v2/https";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { db } from "../config";
import { operatorLabel } from "../domain";

export type ReceiptKind = "order" | "parcel" | "boost" | "story" | "registration";
export interface ReceiptParty { type: "seller" | "agent" | "rider" | "selonachipa"; name: string; uid?: string | null; via?: string | null }
export interface ReceiptLine { label: string; amountNgwee: number; onBehalfOf?: string }
export interface ReceiptInput { reference: string; kind: ReceiptKind; refId: string; payerUid: string; payerName: string; payerMsisdn: string;
  onBehalfOf: ReceiptParty; lines: ReceiptLine[]; totalNgwee: number; walletMsisdn: string }

/** SNC-R-YYMMDD-XXXXXX, stable for a given payment reference (so a repeated callback never makes a second receipt). */
export function receiptNumber(reference: string, at = new Date()): string {
  const d = at.toISOString().slice(2, 10).replace(/-/g, "");
  return `SNC-R-${d}-${createHash("sha256").update(reference).digest("hex").slice(0, 6).toUpperCase()}`;
}
export const maskWallet = (m: string) => { const d = String(m ?? "").replace(/\D/g, ""); return d.length >= 9 ? `0${d.slice(-9, -6)} ••• ${d.slice(-3)}` : "—"; };

export async function issueReceipt(r: ReceiptInput): Promise<string> {
  const id = `rcpt_${createHash("sha256").update(r.reference).digest("hex").slice(0, 24)}`;
  const ref = db.doc(`receipts/${id}`);
  await db.runTransaction(async (tx) => {
    if ((await tx.get(ref)).exists) return;
    tx.create(ref, { number: receiptNumber(r.reference), kind: r.kind, refId: r.refId, paymentReference: r.reference,
      issuer: "SeloNaChipa", issuerNote: `Issued by SeloNaChipa on behalf of ${r.onBehalfOf.name}${r.onBehalfOf.via ? ` (via ${r.onBehalfOf.via})` : ""}`,
      payerUid: r.payerUid, payerName: r.payerName, payerMsisdn: r.payerMsisdn, onBehalfOf: r.onBehalfOf, partyUids: [r.payerUid, r.onBehalfOf.uid].filter(Boolean),
      lines: r.lines, totalNgwee: r.totalNgwee, currency: "ZMW", method: operatorLabel(r.walletMsisdn), wallet: maskWallet(r.walletMsisdn),
      token: randomBytes(12).toString("base64url"), status: "PAID", paidAt: FieldValue.serverTimestamp(), createdAt: FieldValue.serverTimestamp() });
  });
  return id;
}

/** When the rider is assigned later (parcels), the receipt names them. */
export async function setReceiptParty(refId: string, party: ReceiptParty) {
  const q = await db.collection("receipts").where("refId", "==", refId).limit(3).get();
  await Promise.all(q.docs.map((d) => d.ref.update({ onBehalfOf: party, partyUids: FieldValue.arrayUnion(...[party.uid].filter(Boolean)),
    issuerNote: `Issued by SeloNaChipa on behalf of ${party.name}${party.via ? ` (via ${party.via})` : ""}` })));
}

const k = (n: number) => `K ${(n / 100).toLocaleString("en-ZM", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const esc = (s: unknown) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

/** Public, read-only receipt page used for sharing and verification. */
export function receiptHtml(r: Record<string, any>): string {
  const paid = (r.paidAt as Timestamp | undefined)?.toDate().toLocaleString("en-GB", { timeZone: "Africa/Lusaka", dateStyle: "medium", timeStyle: "short" }) ?? "";
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Receipt ${esc(r.number)} · SeloNaChipa</title><meta name="robots" content="noindex">
<style>body{font-family:system-ui,sans-serif;background:#f4f4f6;margin:0;padding:16px;color:#111827}.c{max-width:460px;margin:auto;background:#fff;border-radius:16px;padding:22px;box-shadow:0 2px 12px #0001}
h1{font-size:18px;margin:0}.m{color:#6b7280;font-size:13px}.r{display:flex;justify-content:space-between;padding:7px 0;border-bottom:1px solid #eee;font-size:14px}
.t{font-weight:800;font-size:17px;border:0}.b{display:inline-block;background:#DCFCE7;color:#166534;border-radius:99px;padding:3px 10px;font-weight:700;font-size:12px}
.brand{color:#C8102E;font-weight:900}</style></head><body><div class="c">
<div style="display:flex;justify-content:space-between;align-items:center"><span class="brand">SeloNaChipa</span><span class="b">PAID</span></div>
<h1 style="margin-top:12px">Receipt ${esc(r.number)}</h1><p class="m">${esc(r.issuerNote)}</p>
<div class="r"><span>Received from</span><b>${esc(r.payerName)}</b></div>
<div class="r"><span>Date</span><span>${esc(paid)}</span></div>
<div class="r"><span>Paid with</span><span>${esc(r.method)} ${esc(r.wallet)}</span></div>
<div class="r"><span>Reference</span><span>${esc(r.refId)}</span></div>
${(r.lines ?? []).map((l: any) => `<div class="r"><span>${esc(l.label)}${l.onBehalfOf ? `<br><small class="m">on behalf of ${esc(l.onBehalfOf)}</small>` : ""}</span><span>${k(l.amountNgwee)}</span></div>`).join("")}
<div class="r t"><span>Total paid</span><span>${k(r.totalNgwee)}</span></div>
<p class="m">Payment ref ${esc(r.paymentReference)} · Funds are held in Selo Escrow and released only on delivery.</p>
<p class="m">Questions? WhatsApp +260 978 070 734.</p></div></body></html>`;
}

export const receipt_view = onRequest(async (req, res) => {
  const token = String(req.path.split("/").pop() ?? "").slice(0, 40);
  const q = token ? await db.collection("receipts").where("token", "==", token).limit(1).get() : null;
  const r = q?.docs[0]?.data();
  if (!r) { res.status(404).send("Receipt not found."); return; }
  res.set("Cache-Control", "private, max-age=300").send(receiptHtml(r));
});

/** The payer's (or the on-behalf party's) receipts. */
export const receipt_get = onCall(async (req) => {
  const uid = req.auth?.uid; if (!uid) throw new HttpsError("unauthenticated", "Please log in again.");
  const byRef = typeof req.data?.refId === "string" ? req.data.refId : null;
  const q = byRef ? await db.collection("receipts").where("refId", "==", byRef).where("partyUids", "array-contains", uid).limit(1).get()
    : await db.collection("receipts").where("partyUids", "array-contains", uid).orderBy("createdAt", "desc").limit(50).get();
  return { receipts: q.docs.map((d) => { const x = d.data(); return { id: d.id, ...x, paidAt: (x.paidAt as Timestamp | undefined)?.toMillis() ?? null,
    createdAt: (x.createdAt as Timestamp | undefined)?.toMillis() ?? null } }) };
});
