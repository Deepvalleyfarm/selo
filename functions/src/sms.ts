/**
 * Transactional SMS for SeloNaChipa, sent through the shared Beem module (BEEM-OTP-001, Module 2).
 * SMS_PROVIDER=beem (default) sends via Beem; SMS_PROVIDER=log only logs (emulator / staging without credit).
 * Prefer sendTemplate(): bodies come from server-side templates (beem/sms-templates.ts).
 */
import { logger } from "firebase-functions/v2";
import { SMS_PROVIDER } from "./config";
import { smsService } from "./beem";
import { SMS_TEMPLATES, redactBody, type SmsTemplate } from "./beem/sms-templates";

export interface SmsResult { ok: boolean; provider: string; messageId?: string; error?: string }
export const redact = redactBody;

/** Test hook: lets integration tests capture outgoing SMS without a gateway. */
export const smsTestSink: { to: string; text: string; eventType?: string }[] | null = process.env.SELO_SMS_SINK === "1" ? [] : null;

export interface SmsMeta { eventType?: string; referenceId?: string; sensitive?: boolean }

export async function sendSms(to: string, text: string, meta: SmsMeta = {}): Promise<SmsResult> {
  if (!/^\+?260(9[567]|7[567])\d{7}$/.test(to)) return { ok: false, provider: "none", error: "invalid_number" };
  if (smsTestSink) { smsTestSink.push({ to, text, eventType: meta.eventType }); return { ok: true, provider: "test" }; }
  let provider = "beem";
  try { provider = SMS_PROVIDER.value() || "beem"; } catch { /* params unavailable outside functions runtime */ }
  if (provider === "log") {
    logger.info("sms (log provider)", { to: to.slice(0, 7) + "•••", eventType: meta.eventType, text: redact(text) });
    return { ok: true, provider: "log" };
  }
  const r = await smsService.send({ to, message: text, eventType: meta.eventType ?? "NOTICE", referenceId: meta.referenceId, sensitive: meta.sensitive });
  return { ok: r.success, provider: "beem", error: r.error };
}

/** Sends one of the predefined templates (the only way app events should send SMS). */
export function sendTemplate<T extends SmsTemplate>(template: T, to: string | null | undefined, args: Parameters<(typeof SMS_TEMPLATES)[T]>, referenceId?: string) {
  if (!to) return Promise.resolve({ ok: false, provider: "none", error: "no_number" } as SmsResult);
  const fn = SMS_TEMPLATES[template] as (...a: unknown[]) => string;
  return sendSms(to, fn(...args), { eventType: template, referenceId });
}
