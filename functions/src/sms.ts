/**
 * SMS fallback (§9.3 step 3, §14). Providers: Africa's Talking (covers Airtel, MTN and Zamtel Zambia), a generic HTTP
 * gateway (e.g. a local aggregator), or "log" for the emulator. Message bodies are never logged: digits are masked.
 */
import { logger } from "firebase-functions/v2";
import { SMS_API_KEY, SMS_HTTP_URL, SMS_PROVIDER, SMS_SENDER_ID, SMS_USERNAME } from "./config";

export interface SmsResult { ok: boolean; provider: string; messageId?: string; error?: string }
export const redact = (text: string) => text.replace(/\d{4,}/g, (m) => "•".repeat(m.length));

/** Test hook: lets integration tests capture outgoing SMS without a gateway. */
export const smsTestSink: { to: string; text: string }[] | null = process.env.SELO_SMS_SINK === "1" ? [] : null;

export async function sendSms(to: string, text: string): Promise<SmsResult> {
  if (!/^\+260(9[567]|7[567])\d{7}$/.test(to)) return { ok: false, provider: "none", error: "invalid_number" };
  if (smsTestSink) { smsTestSink.push({ to, text }); return { ok: true, provider: "test" }; }
  let provider = "log";
  try { provider = SMS_PROVIDER.value(); } catch { /* params unavailable outside functions runtime */ }
  let key = "";
  try { key = SMS_API_KEY.value(); } catch { key = ""; }
  try {
    if (provider === "africastalking" && key) {
      const body = new URLSearchParams({ username: SMS_USERNAME.value(), to, message: text, from: SMS_SENDER_ID.value() });
      const r = await fetch("https://api.africastalking.com/version1/messaging", { method: "POST",
        headers: { apiKey: key, Accept: "application/json", "Content-Type": "application/x-www-form-urlencoded" }, body });
      const j = await r.json().catch(() => ({})) as any;
      const rec = j?.SMSMessageData?.Recipients?.[0];
      return { ok: r.ok && (rec?.statusCode === 101 || rec?.status === "Success"), provider, messageId: rec?.messageId, error: rec?.status };
    }
    if (provider === "http" && SMS_HTTP_URL.value()) {
      const r = await fetch(SMS_HTTP_URL.value(), { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${key}` },
        body: JSON.stringify({ to, text, sender: SMS_SENDER_ID.value() }) });
      return { ok: r.ok, provider, error: r.ok ? undefined : `http_${r.status}` };
    }
    logger.info("sms (log provider)", { to: to.slice(0, 7) + "•••", text: redact(text) });
    return { ok: true, provider: "log" };
  } catch (e) {
    logger.warn("sms failed", { to: to.slice(0, 7) + "•••", error: String(e) });
    return { ok: false, provider, error: "exception" };
  }
}
