import { LIPILA_API_KEY, LIPILA_BASE_URL, LIPILA_CALLBACK_URL } from "./config";
import { network } from "./domain";

/**
 * Lipila mobile-money gateway client (Airtel Money, MTN MoMo, Zamtel Kwacha).
 * Endpoint paths and payload field names follow the integration notes in the PRD; confirm them
 * against the Lipila contract before go-live (PRD open question 7). Amounts are sent in kwacha.
 */
interface LipilaResult { ok: boolean; status: number; reference: string; message?: string; raw?: unknown }

async function post(path: string, body: Record<string, unknown>): Promise<LipilaResult> {
  const res = await fetch(`${LIPILA_BASE_URL.value()}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${LIPILA_API_KEY.value()}` },
    body: JSON.stringify(body),
  });
  const raw = await res.json().catch(() => ({}));
  return { ok: res.ok, status: res.status, reference: String(body.referenceId ?? ""), message: (raw as { message?: string }).message, raw };
}

export const lipila = {
  /** Push prompt on the payer's phone. ESCROW_LOCKED is only set by the webhook, never by this reply. */
  collect: (referenceId: string, amountNgwee: number, msisdn: string, narration: string) =>
    post("/api/v1/collections", { referenceId, amount: amountNgwee / 100, currency: "ZMW", accountNumber: msisdn,
      paymentType: network(msisdn), narration, callbackUrl: LIPILA_CALLBACK_URL.value() }),

  disburse: (referenceId: string, amountNgwee: number, msisdn: string, narration: string) =>
    post("/api/v1/disbursements", { referenceId, amount: amountNgwee / 100, currency: "ZMW", accountNumber: msisdn,
      paymentType: network(msisdn), narration, callbackUrl: LIPILA_CALLBACK_URL.value() }),

  /** Account-holder name lookup shown as "Wallet found: NAME. Is this you?" */
  async lookup(msisdn: string): Promise<string | null> {
    const r = await post("/api/v1/accounts/lookup", { accountNumber: msisdn, paymentType: network(msisdn) });
    const name = (r.raw as { accountName?: string; name?: string })?.accountName ?? (r.raw as { name?: string })?.name;
    return r.ok && name ? String(name).toUpperCase() : null;
  },
};

export { verifyLipilaSignature } from "./lipila-signature";
