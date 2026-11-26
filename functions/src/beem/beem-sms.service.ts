/**
 * BEEM-OTP-001 · Module 2: transactional SMS via Beem (POST {smsBaseUrl}/send). Every message is logged in sms_logs;
 * bodies of sensitive events (delivery codes) are stored with digits masked.
 */
import { HTTP_ERRORS } from "./beem.codes";
import { beemConfig, cleanMsisdn, configProblem, type BeemConfig } from "./beem.config";
import { beemCode, beemRequest } from "./beem.http";
import type { BeemStore } from "./beem.store";
import { SENSITIVE_EVENTS, SMS_TEMPLATES, redactBody, type SmsTemplate } from "./sms-templates";

export interface SmsPayload { to: string; message: string; eventType: string; referenceId?: string; sensitive?: boolean }
export interface SmsResult { success: boolean; beemCode?: number; beemMessage?: string; error?: string }

/** Test hook: integration tests capture SMS instead of calling Beem. */
export const smsTestSink: { to: string; text: string; eventType: string }[] | null = process.env.SELO_SMS_SINK === "1" ? [] : null;

export class BeemSmsService {
  constructor(private store: BeemStore, private cfg: () => BeemConfig = beemConfig) {}

  async send(p: SmsPayload): Promise<SmsResult> {
    const cfg = this.cfg();
    const to = cleanMsisdn(p.to, cfg.countryCodes);
    const body = String(p.message ?? "").trim().slice(0, 459); // ≤ 3 SMS segments
    const log = (r: { success: boolean; code?: number | null; msg?: string | null; err?: string | null }) => this.store.logSms({
      msisdn: to ?? String(p.to ?? "").replace(/\D/g, "").slice(0, 20), event_type: p.eventType, reference_id: p.referenceId ?? null, application: cfg.application,
      message_body: p.sensitive || SENSITIVE_EVENTS.has(p.eventType) ? redactBody(body) : body,
      beem_code: r.code ?? null, beem_message: r.msg ?? null, success: r.success, error_detail: r.err ?? null }).catch(() => undefined);
    if (!to) { await log({ success: false, err: "invalid_number" }); return { success: false, error: "invalid_number" }; }
    if (!body) { await log({ success: false, err: "empty_message" }); return { success: false, error: "empty_message" }; }
    if (smsTestSink) { smsTestSink.push({ to, text: body, eventType: p.eventType }); await log({ success: true, code: 100, msg: "test sink" }); return { success: true, beemCode: 100 }; }
    const problem = configProblem(cfg);
    if (problem) { await log({ success: false, err: `config: ${problem}` }); return { success: false, error: "config" }; }

    const r = await beemRequest("POST", `${cfg.smsBaseUrl}/send`, cfg, {
      source_addr: cfg.senderId, schedule_time: "", encoding: 0, message: body, recipients: [{ recipient_id: 1, dest_addr: to }],
    });
    const { code, message } = beemCode(r.body);
    const ok = r.ok && (r.body?.successful === true || code === 100);
    const err = ok ? null : r.network ? HTTP_ERRORS.NETWORK.meaning : r.status === 401 || r.status === 403 ? HTTP_ERRORS.AUTH.meaning : `${HTTP_ERRORS.SMS_REJECTED.meaning} (HTTP ${r.status})`;
    await log({ success: ok, code, msg: message || null, err });
    return { success: ok, beemCode: code ?? undefined, beemMessage: message || undefined, error: err ?? undefined };
  }

  /** Sends a predefined template. */
  sendTemplate<T extends SmsTemplate>(template: T, to: string, args: Parameters<(typeof SMS_TEMPLATES)[T]>, referenceId?: string): Promise<SmsResult> {
    const fn = SMS_TEMPLATES[template] as (...a: unknown[]) => string;
    return this.send({ to, message: fn(...args), eventType: template, referenceId });
  }
}
