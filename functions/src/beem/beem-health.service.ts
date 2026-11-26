/**
 * BEEM-OTP-001 · background health check (every 5 minutes) and on-demand recheck from the admin monitor.
 *
 * Default "probe" mode sends NO SMS: it asks Beem's OTP API to send to a deliberately invalid number (Beem answers
 * 102 Invalid phone number after checking credentials and the application, so 401 / 106 / 107 still surface) and
 * reads the SMS account balance (validates SMS credentials and warns on low credit). "send" mode requests a real OTP
 * to BEEM_HEALTH_TEST_MSISDN as the spec describes — that costs one SMS per check (288 a day), so use it sparingly.
 */
import { beemConfig, configProblem, type BeemConfig } from "./beem.config";
import { beemCode, beemRequest } from "./beem.http";

export type HealthState = "LIVE" | "DEGRADED" | "OFFLINE" | "CONFIG_ERROR";
export interface ServiceHealth { ok: boolean; state: HealthState; detail: string; code?: number | null; httpStatus?: number; latencyMs?: number }
export interface BeemHealth { state: HealthState; otp: ServiceHealth; sms: ServiceHealth; credentials: { ok: boolean; detail: string };
  balance: number | null; appName: string; appId: number; senderId: string; mode: "probe" | "send"; checkedAt: number }

const PROBE_MSISDN = "260000000000"; // never a real subscriber

function classify(r: { network: boolean; status: number; latencyMs: number }, code: number | null, okCodes: number[], msg: string): ServiceHealth {
  if (r.network) return { ok: false, state: "OFFLINE", detail: "Cannot reach Beem API", latencyMs: r.latencyMs };
  if (r.status === 401 || r.status === 403) return { ok: false, state: "CONFIG_ERROR", detail: "Invalid API credentials", httpStatus: r.status, code };
  if (code === 104 || code === 106 || code === 107 || code === 108) return { ok: false, state: "CONFIG_ERROR", detail: code === 107 ? "Application inactive" : code === 108 ? "No channel found" : "Application not found", code, httpStatus: r.status };
  if (r.status >= 500) return { ok: false, state: "DEGRADED", detail: `Beem returned HTTP ${r.status}`, httpStatus: r.status, code };
  if (code != null && okCodes.includes(code)) return { ok: true, state: "LIVE", detail: msg || "Operational", code, httpStatus: r.status, latencyMs: r.latencyMs };
  if (code === 101) return { ok: false, state: "DEGRADED", detail: "Failed to send SMS (credits or Beem degradation)", code, httpStatus: r.status };
  return r.status >= 200 && r.status < 300 ? { ok: true, state: "LIVE", detail: msg || "Operational", code, httpStatus: r.status, latencyMs: r.latencyMs }
    : { ok: false, state: "DEGRADED", detail: msg || `HTTP ${r.status}`, code, httpStatus: r.status };
}

export async function checkBeemHealth(cfgFn: () => BeemConfig = beemConfig): Promise<BeemHealth> {
  const cfg = cfgFn();
  const head = { appName: cfg.appName, appId: cfg.appId, senderId: cfg.senderId, mode: cfg.healthMode, checkedAt: Date.now() };
  const problem = configProblem(cfg);
  if (problem) {
    const s: ServiceHealth = { ok: false, state: "CONFIG_ERROR", detail: problem };
    return { ...head, state: "CONFIG_ERROR", otp: s, sms: s, credentials: { ok: false, detail: problem }, balance: null };
  }
  const sendMode = cfg.healthMode === "send" && !!cfg.healthMsisdn;
  const [otpR, balR] = await Promise.all([
    beemRequest("POST", `${cfg.otpBaseUrl}/request`, cfg, { appId: cfg.appId, msisdn: sendMode ? cfg.healthMsisdn : PROBE_MSISDN }),
    beemRequest("GET", cfg.balanceUrl, cfg),
  ]);
  const oc = beemCode(otpR.body);
  const otp = classify(otpR, oc.code, sendMode ? [100] : [100, 102], oc.code === 102 && !sendMode ? "Operational (probe: no SMS sent)" : oc.message);
  const credits = Number(balR.body?.data?.credit_balance ?? balR.body?.credit_balance ?? balR.body?.data?.balance);
  const sms: ServiceHealth = balR.network ? { ok: false, state: "OFFLINE", detail: "Cannot reach Beem SMS API", latencyMs: balR.latencyMs }
    : balR.status === 401 || balR.status === 403 ? { ok: false, state: "CONFIG_ERROR", detail: "Invalid API credentials", httpStatus: balR.status }
    : balR.ok && Number.isFinite(credits) && credits <= 0 ? { ok: false, state: "DEGRADED", detail: "SMS credit balance is 0", httpStatus: balR.status }
    : balR.ok ? { ok: true, state: "LIVE", detail: Number.isFinite(credits) ? `Operational · ${credits} credits` : "Operational", httpStatus: balR.status, latencyMs: balR.latencyMs }
    : { ok: false, state: "DEGRADED", detail: `Balance check returned HTTP ${balR.status}`, httpStatus: balR.status };
  const credentialsBad = otp.detail === "Invalid API credentials" || sms.detail === "Invalid API credentials";
  const states = [otp.state, sms.state];
  const state: HealthState = states.includes("CONFIG_ERROR") ? "CONFIG_ERROR" : states.every((s) => s === "OFFLINE") ? "OFFLINE"
    : states.every((s) => s === "LIVE") ? "LIVE" : "DEGRADED";
  return { ...head, state, otp, sms, credentials: { ok: !credentialsBad, detail: credentialsBad ? "Invalid API credentials" : "Valid" },
    balance: Number.isFinite(credits) ? credits : null };
}
