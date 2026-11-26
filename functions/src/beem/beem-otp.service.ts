/**
 * BEEM-OTP-001 · Module 1: mobile number verification with Beem OTP.
 *
 * request(): validate + clean the number → rate limits (3 per number per 10 min, 10 per IP per 10 min) →
 *            POST {otpBaseUrl}/request {appId, msisdn} → store pinId against the number → { pinId, expiresInSeconds }.
 * verify():  the pinId must be the one issued to that number (prevents PIN hijacking) → POST /verify {pinId, pin}
 *            → 117 marks the number verified. The PIN itself is never stored, logged or returned.
 */
import { BEEM_OTP_CODES, HTTP_ERRORS } from "./beem.codes";
import { beemConfig, cleanMsisdn, configProblem, type BeemConfig } from "./beem.config";
import { beemCode, beemRequest } from "./beem.http";
import type { BeemStore } from "./beem.store";

export const OTP_EXPIRES_IN_SECONDS = 60;
export const MAX_PER_NUMBER = 3, MAX_PER_IP = 10, WINDOW_MS = 10 * 60_000;

export interface Meta { ip?: string | null; userAgent?: string | null; uid?: string | null }
export type OtpError = { success: false; reason: string; message: string; beemCode?: number | null; retryAfterSeconds?: number };
export type RequestResult = { success: true; pinId: string; expiresInSeconds: number; msisdn: string } | OtpError;
export type VerifyResult = { success: true; msisdn: string; appContext: string } | OtpError;

function httpFailure(r: { status: number; network: boolean }): { key: keyof typeof HTTP_ERRORS; detail: string } | null {
  if (r.network) return { key: "NETWORK", detail: "network error or timeout (after retry)" };
  if (r.status === 401 || r.status === 403) return { key: "AUTH", detail: `HTTP ${r.status}` };
  if (r.status >= 500) return { key: "SERVER", detail: `HTTP ${r.status}` };
  return null;
}

export class BeemOtpService {
  constructor(private store: BeemStore, private cfg: () => BeemConfig = beemConfig) {}

  async request(rawMsisdn: unknown, appContext: string, meta: Meta = {}): Promise<RequestResult> {
    const cfg = this.cfg();
    const ctx = String(appContext || "signup").slice(0, 50);
    const msisdn = cleanMsisdn(rawMsisdn, cfg.countryCodes);
    const base = { msisdn: msisdn ?? String(rawMsisdn ?? "").replace(/\D/g, "").slice(0, 20), pin_id: null, event_type: "OTP_REQUESTED" as const,
      app_context: ctx, ip_address: meta.ip ?? null, user_agent: meta.userAgent?.slice(0, 300) ?? null };
    if (!msisdn) {
      await this.store.logOtp({ ...base, beem_code: null, beem_message: null, success: false, error_detail: "invalid_format (rejected before Beem)" });
      return { success: false, reason: "invalid_number", message: BEEM_OTP_CODES[102].user };
    }
    const problem = configProblem(cfg);
    if (problem) {
      await this.store.logOtp({ ...base, beem_code: null, beem_message: null, success: false, error_detail: `config: ${problem}` });
      return { success: false, reason: "config", message: HTTP_ERRORS.CONFIG.user };
    }
    const perNumber = await this.store.hit(`n:${msisdn}`, WINDOW_MS);
    const perIp = meta.ip ? await this.store.hit(`ip:${meta.ip}`, WINDOW_MS) : 0;
    if (perNumber > MAX_PER_NUMBER || perIp > MAX_PER_IP) {
      await this.store.logOtp({ ...base, beem_code: null, beem_message: null, success: false, error_detail: perNumber > MAX_PER_NUMBER ? "rate_limited:number" : "rate_limited:ip" });
      return { success: false, reason: "rate_limited", message: "Too many codes requested. Please wait 10 minutes and try again.", retryAfterSeconds: 600 };
    }

    const r = await beemRequest("POST", `${cfg.otpBaseUrl}/request`, cfg, { appId: cfg.appId, msisdn });
    const fail = httpFailure(r);
    const { code, message } = beemCode(r.body);
    const pinId: string | undefined = r.body?.data?.pinId ?? r.body?.pinId;
    if (fail || !pinId) {
      const info = fail ? HTTP_ERRORS[fail.key] : (code != null && BEEM_OTP_CODES[code]) || HTTP_ERRORS.SERVER;
      await this.store.logOtp({ ...base, beem_code: code, beem_message: message || info.meaning, success: false, error_detail: fail?.detail ?? `${info.meaning}${r.status ? ` (HTTP ${r.status})` : ""}` });
      return { success: false, reason: fail ? fail.key.toLowerCase() : `beem_${code ?? "unknown"}`, message: info.user || HTTP_ERRORS.SERVER.user, beemCode: code };
    }
    await this.store.saveSession({ msisdn, pinId, appContext: ctx, requestedAt: Date.now(), attempts: 0, verified: false, uid: meta.uid ?? null });
    await this.store.logOtp({ ...base, pin_id: pinId, beem_code: code ?? 100, beem_message: message || "SMS sent successfully", success: true, error_detail: null });
    return { success: true, pinId, expiresInSeconds: OTP_EXPIRES_IN_SECONDS, msisdn };
  }

  async verify(pinId: unknown, pin: unknown, rawMsisdn: unknown, meta: Meta = {}): Promise<VerifyResult> {
    const cfg = this.cfg();
    const msisdn = cleanMsisdn(rawMsisdn, cfg.countryCodes);
    const id = String(pinId ?? "").trim().slice(0, 100);
    const code6 = String(pin ?? "").replace(/\D/g, "");
    const base = { msisdn: msisdn ?? "", pin_id: id || null, app_context: "signup", ip_address: meta.ip ?? null, user_agent: meta.userAgent?.slice(0, 300) ?? null };
    if (!msisdn || !id || code6.length < 4 || code6.length > 8) return { success: false, reason: "invalid_input", message: "Enter the 6-digit code." };
    const session = await this.store.getSession(msisdn);
    if (!session || session.pinId !== id) {
      await this.store.logOtp({ ...base, event_type: "OTP_FAILED", beem_code: null, beem_message: null, success: false, error_detail: "pinId does not belong to this number" });
      return { success: false, reason: "session_mismatch", message: BEEM_OTP_CODES[113].user };
    }
    base.app_context = session.appContext;
    if (session.verified) return { success: false, reason: "beem_118", message: BEEM_OTP_CODES[118].user, beemCode: 118 };

    const r = await beemRequest("POST", `${cfg.otpBaseUrl}/verify`, cfg, { pinId: id, pin: code6 });
    const fail = httpFailure(r);
    const { code, message } = beemCode(r.body);
    if (!fail && code === 117) {
      await this.store.saveSession({ ...session, verified: true, attempts: session.attempts + 1 });
      await this.store.logOtp({ ...base, event_type: "OTP_VERIFIED", beem_code: 117, beem_message: message || "Valid Pin", success: true, error_detail: null });
      return { success: true, msisdn, appContext: session.appContext };
    }
    await this.store.saveSession({ ...session, attempts: session.attempts + 1 });
    const info = fail ? HTTP_ERRORS[fail.key] : (code != null && BEEM_OTP_CODES[code]) || HTTP_ERRORS.SERVER;
    await this.store.logOtp({ ...base, event_type: "OTP_FAILED", beem_code: code, beem_message: message || info.meaning, success: false, error_detail: fail?.detail ?? info.meaning });
    return { success: false, reason: fail ? fail.key.toLowerCase() : `beem_${code ?? "unknown"}`, message: info.user || HTTP_ERRORS.SERVER.user, beemCode: code };
  }
}
