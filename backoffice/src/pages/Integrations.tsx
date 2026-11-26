import { useMemo, useState } from "react";
import { limit, orderBy } from "firebase/firestore";
import { useCollection, useDoc } from "../lib/data";
import { download, k, toCsv, toDate, when } from "../lib/format";
import { can, useSession } from "../lib/session";
import { Drawer, Kpi, Page, Panel, Pill, Table, useAction } from "../lib/ui";

const days = (n: number) => Array.from({ length: n }, (_, i) => new Date(Date.now() - i * 86_400_000).toISOString().slice(0, 10));
const D7 = days(7);
/** Sums sharded counter docs (mapsUsage/{day}/shards, beem_stats/{day}/shards). Seven fixed hooks, one per day. */
function useShardTotals(root: string): Record<string, Record<string, number>> {
  const r0 = useCollection(`${root}/${D7[0]}/shards`).rows, r1 = useCollection(`${root}/${D7[1]}/shards`).rows, r2 = useCollection(`${root}/${D7[2]}/shards`).rows,
    r3 = useCollection(`${root}/${D7[3]}/shards`).rows, r4 = useCollection(`${root}/${D7[4]}/shards`).rows, r5 = useCollection(`${root}/${D7[5]}/shards`).rows,
    r6 = useCollection(`${root}/${D7[6]}/shards`).rows;
  return useMemo(() => Object.fromEntries([r0, r1, r2, r3, r4, r5, r6].map((rows, i) => {
    const t: Record<string, number> = {};
    rows.forEach((s) => Object.entries(s).forEach(([key, v]) => { if (typeof v === "number") t[key] = (t[key] ?? 0) + v; }));
    return [D7[i], t];
  })), [r0, r1, r2, r3, r4, r5, r6]);
}
const sum = (t: Record<string, Record<string, number>>, key: string) => Object.values(t).reduce((s, d) => s + (d[key] ?? 0), 0);
const n = (v: number) => v.toLocaleString("en-ZM");

/* =========================================================================== Google Maps usage */

const SKUS: { sku: string; label: string; price: number; cap: string; on: string }[] = [
  { sku: "places_autocomplete", label: "Places API (New) · Autocomplete", price: 2.83, cap: "mapsDailyCapAutocomplete", on: "mapsAutocompleteEnabled" },
  { sku: "geocoding", label: "Geocoding API", price: 5, cap: "mapsDailyCapGeocoding", on: "mapsGeocodingEnabled" },
  { sku: "routes_matrix", label: "Routes API · Route Matrix (fees, ETA)", price: 5, cap: "mapsDailyCapRouteMatrix", on: "mapsRoutesEnabled" },
  { sku: "routes_compute", label: "Routes API · Compute Routes (riders)", price: 5, cap: "mapsDailyCapRoutes", on: "mapsRoutesEnabled" },
];

export function MapsUsage() {
  const t = useShardTotals("mapsUsage");
  const cfg: Record<string, any> = useDoc("platformConfig/default") ?? {};
  const today = t[D7[0]] ?? {};
  const rows = SKUS.map((s) => {
    const calls = sum(t, `${s.sku}_calls`), hits = sum(t, `${s.sku}_cacheHits`);
    return { id: s.sku, ...s, calls, hits, errors: sum(t, `${s.sku}_errors`), fallbacks: sum(t, `${s.sku}_fallbacks`) + sum(t, `${s.sku}_capped`),
      todayCalls: today[`${s.sku}_calls`] ?? 0, cap: Number(cfg[s.cap] ?? 0), enabled: cfg[s.on] !== false,
      hitRate: calls + hits ? Math.round((hits / (calls + hits)) * 100) : 0, usd: (calls / 1000) * s.price,
      latency: calls ? Math.round(sum(t, `${s.sku}_latencyMsTotal`) / calls) : 0 };
  });
  const usd = rows.reduce((s, r) => s + r.usd, 0);
  const saved = rows.reduce((s, r) => s + (r.hits / 1000) * r.price, 0);
  return <Page title="Google Maps usage" sub="Only Places API (New), Geocoding, Routes and Maps SDK for Android are used · last 7 days · map loads on Android are free">
    <div className="kpis">
      <Kpi label="Billable calls (7 days)" value={n(rows.reduce((s, r) => s + r.calls, 0))} note={`${n(rows.reduce((s, r) => s + r.todayCalls, 0))} today`} />
      <Kpi label="Served from cache" value={`${rows.reduce((s, r) => s + r.hits, 0) ? Math.round(rows.reduce((s, r) => s + r.hits, 0) / Math.max(1, rows.reduce((s, r) => s + r.hits + r.calls, 0)) * 100) : 0}%`} note={`≈ US$${saved.toFixed(2)} not spent`} tone="up" />
      <Kpi label="List-price estimate" value={`US$${usd.toFixed(2)}`} note="before Google's monthly free usage" />
      <Kpi label="Fallbacks used" value={n(rows.reduce((s, r) => s + r.fallbacks, 0))} note="cap reached, switched off or no key" tone={rows.some((r) => r.fallbacks) ? "wn" : undefined} />
    </div>
    <Panel title="Per API">
      <Table rows={rows} cols={[{ h: "API", r: (r) => <>{r.label}{!r.enabled && <> <Pill tone="red">switched off</Pill></>}</> },
        { h: "Today / daily cap", r: (r) => <>{n(r.todayCalls)} / {r.cap ? n(r.cap) : "∞"}{r.cap && r.todayCalls >= r.cap * 0.8 ? <> <Pill tone={r.todayCalls >= r.cap ? "red" : "warn"}>{r.todayCalls >= r.cap ? "cap reached" : "80%"}</Pill></> : null}</> },
        { h: "Calls (7d)", r: (r) => n(r.calls) }, { h: "Cache hits", r: (r) => `${n(r.hits)} (${r.hitRate}%)` }, { h: "Errors", r: (r) => n(r.errors) },
        { h: "Avg latency", r: (r) => r.latency ? `${r.latency} ms` : "—" }, { h: "Est. cost", r: (r) => `US$${r.usd.toFixed(2)}` }]} />
      <p className="mute">Caps and switches are under Configuration → Maps &amp; delivery pricing. When a cap is reached the platform falls back to saved addresses, the neighbourhood list and straight-line distance (labelled approximate) — nothing breaks.</p>
    </Panel>
    <Panel title="Daily billable calls">
      <Table rows={D7.map((d) => ({ id: d, d, ...Object.fromEntries(SKUS.map((s) => [s.sku, (t[d] ?? {})[`${s.sku}_calls`] ?? 0])) }))} cols={[{ h: "Day", r: (r) => r.d },
        ...SKUS.map((s) => ({ h: s.label.split("·").pop()!.trim(), r: (r: any) => n(r[s.sku]) }))]} />
    </Panel>
  </Page>;
}

/* =========================================================================== Beem monitor (Super Admin) */

/** Every Beem code → explanation and action (mirrors functions/src/beem/beem.codes.ts). */
export const BEEM_CODES: Record<string, { meaning: string; cause: string; action: string }> = {
  "101": { meaning: "Failed to send SMS", cause: "Beem service degradation or insufficient SMS credits.", action: "Check your Beem account balance." },
  "102": { meaning: "Invalid phone number", cause: "Usually a typo by the user.", action: "None unless frequent." },
  "103": { meaning: "Phone number missing", cause: "A request reached Beem without a number.", action: "Report to engineering (validation bypass)." },
  "104": { meaning: "Application ID missing", cause: "BEEM_APP_ID is not set.", action: "Set BEEM_APP_ID and redeploy." },
  "106": { meaning: "Application not found", cause: "Beem does not recognise the App ID.", action: "Confirm the App ID in the Beem portal (OTP → Applications)." },
  "107": { meaning: "Application inactive", cause: "Application is inactive on Beem.", action: "Log into the Beem portal and reactivate Selonachipa." },
  "108": { meaning: "No channel found", cause: "No SMS channel attached to the Beem application.", action: "Attach a sender ID / channel in the Beem portal." },
  "113": { meaning: "pinId not found", cause: "Verification session expired.", action: "None unless frequent." },
  "114": { meaning: "Incorrect Pin", cause: "Wrong code typed.", action: "None." },
  "115": { meaning: "Pin TimeOut", cause: "Code expired before use — SMS may be arriving late.", action: "If frequent, check Beem delivery reports." },
  "116": { meaning: "Attempts Exceeded", cause: "Too many wrong codes for one PIN.", action: "Watch for brute-force patterns." },
  "117": { meaning: "Valid Pin", cause: "", action: "" },
  "118": { meaning: "Duplicate Pin", cause: "Code submitted again after success.", action: "None." },
};
const STATE_BADGE: Record<string, { color: string; label: string }> = {
  LIVE: { color: "#1D6A45", label: "● LIVE" }, DEGRADED: { color: "#F59E0B", label: "◐ DEGRADED" }, OFFLINE: { color: "#DC2626", label: "✕ OFFLINE" },
  CONFIG_ERROR: { color: "#DC2626", label: "✕ CONFIG ERROR" }, CHECKING: { color: "#9CA3AF", label: "○ CHECKING..." },
};
const ddmmyyyy = (v: unknown) => { const d = toDate(v); if (!d) return "—"; const p = (x: number) => String(x).padStart(2, "0");
  return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`; };
const mask = (m: string) => (m && m.length > 9 ? `${m.slice(0, 6)}${"*".repeat(Math.max(3, m.length - 9))}${m.slice(-3)}` : m || "—");

export function BeemMonitor() {
  const { admin } = useSession();
  if (admin?.role !== "super_admin") return <Page title="Beem integration"><Panel><p>The Beem monitor is available to Super Admins only.</p></Panel></Page>;
  return <BeemMonitorInner />;
}

function BeemMonitorInner() {
  const health = useDoc("beem_health/current");
  const stats = useShardTotals("beem_stats");
  const { busy, run, Msg } = useAction();
  const [checking, setChecking] = useState(false);
  const [tab, setTab] = useState<"otp" | "sms">("otp");
  const since = Date.now() - 86_400_000;
  const recentOtp = useCollection("otp_logs", [orderBy("created_at", "desc"), limit(400)], "recent").rows;
  const recentSms = useCollection("sms_logs", [orderBy("created_at", "desc"), limit(400)], "recent").rows;
  const failures24 = [...recentOtp, ...recentSms].filter((r) => !r.success && (toDate(r.created_at)?.getTime() ?? 0) > since);
  const byCode = failures24.reduce<Record<string, number>>((m, r) => { const key = r.beem_code != null ? String(r.beem_code) : (r.error_detail ?? "other").split(":")[0]; m[key] = (m[key] ?? 0) + 1; return m; }, {});
  const state = checking || !health ? "CHECKING" : String(health.state ?? "CHECKING");
  const badge = STATE_BADGE[state] ?? STATE_BADGE.CHECKING;
  const otpReq = sum(stats, "otp_requested") + sum(stats, "otp_request_failed"), verified = sum(stats, "otp_verified");
  const smsSent = sum(stats, "sms_sent"), failed = sum(stats, "sms_failed") + sum(stats, "otp_request_failed");
  const ok = (s?: { ok?: boolean; detail?: string }) => s ? <span style={{ color: s.ok ? "#1D6A45" : "#DC2626" }}>{s.ok ? "✓" : "✕"} {s.detail ?? (s.ok ? "Operational" : "Error")}</span> : <span className="mute">—</span>;
  return <Page title="Beem integration monitor" sub="OTP verification and transactional SMS (BEEM-OTP-001) · Super Admin only">
    <Panel>
      <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
        <b style={{ letterSpacing: ".04em" }}>BEEM AFRICA INTEGRATION STATUS</b>
        <span style={{ background: badge.color, color: "#fff", borderRadius: 999, padding: "3px 10px", fontWeight: 700, fontSize: 12 }}>{badge.label}</span>
        <span className="mute">Last checked: {health?.checkedAt ? when(new Date(health.checkedAt)) : "—"}{health?.mode === "probe" ? " · probe (no SMS sent)" : ""}</span>
        <button className="btn out" style={{ marginLeft: "auto" }} disabled={busy} onClick={async () => { setChecking(true); await run("admin_beemHealth", {}, "Health check complete."); setChecking(false); }}>Recheck</button>
      </div>
      <dl className="kv" style={{ marginTop: 10 }}>
        <dt>OTP Service</dt><dd>{ok(health?.otp)}</dd><dt>SMS Service</dt><dd>{ok(health?.sms)}</dd><dt>Credentials</dt><dd>{ok(health?.credentials)}</dd>
        {health?.balance != null && <><dt>SMS credits</dt><dd>{n(health.balance)}</dd></>}
        <dt>App</dt><dd>App Name: {health?.appName ?? "—"} &nbsp;|&nbsp; App ID: {health?.appId ?? "—"} &nbsp;|&nbsp; Sender ID: {health?.senderId ?? "—"}</dd>
      </dl>
      <Msg />
    </Panel>
    {failures24.length > 0 && <Panel title="⚠ Active errors (last 24 hours)">
      {Object.entries(byCode).sort((a, b) => b[1] - a[1]).map(([code, count]) => { const info = BEEM_CODES[code];
        return <div key={code} style={{ padding: "8px 0", borderBottom: "1px solid var(--line, #eee)" }}>
          <b>{count} {info ? `failure${count === 1 ? "" : "s"} with Code ${code} (${info.meaning})` : `failure${count === 1 ? "" : "s"}: ${code}`}</b>
          {info?.cause && <div className="mute">Possible cause: {info.cause}</div>}{info?.action && <div>→ {info.action}</div>}
        </div>; })}
    </Panel>}
    <div className="kpis">
      <Kpi label="OTP requests (7d)" value={n(otpReq)} />
      <Kpi label="Verified ✓" value={`${n(verified)} (${otpReq ? Math.round((verified / otpReq) * 100) : 0}%)`} tone="up" />
      <Kpi label="SMS sent (7d)" value={n(smsSent)} />
      <Kpi label="Failed ✗" value={`${n(failed)} (${otpReq + smsSent ? ((failed / (otpReq + smsSent)) * 100).toFixed(1) : "0.0"}%)`} tone={failed ? "dn" : undefined} />
    </div>
    <div className="seg" style={{ marginBottom: 10 }}><button className={tab === "otp" ? "on" : ""} onClick={() => setTab("otp")}>OTP logs</button><button className={tab === "sms" ? "on" : ""} onClick={() => setTab("sms")}>SMS logs</button></div>
    {tab === "otp" ? <LogTable kind="otp" /> : <LogTable kind="sms" />}
  </Page>;
}

function LogTable({ kind }: { kind: "otp" | "sms" }) {
  const [max, setMax] = useState(100);
  const rows = useCollection(kind === "otp" ? "otp_logs" : "sms_logs", [orderBy("created_at", "desc"), limit(max)], `${kind}${max}`).rows
    .slice().sort((a, b) => (toDate(b.created_at)?.getTime() ?? 0) - (toDate(a.created_at)?.getTime() ?? 0));
  const [from, setFrom] = useState(""); const [to, setTo] = useState(""); const [ev, setEv] = useState(""); const [st, setSt] = useState("all"); const [q, setQ] = useState("");
  const events = [...new Set(rows.map((r) => r.event_type))].sort();
  const shown = rows.filter((r) => { const t = toDate(r.created_at)?.getTime() ?? 0;
    return (!from || t >= new Date(from).getTime()) && (!to || t <= new Date(to).getTime() + 86_399_999) && (!ev || r.event_type === ev)
      && (st === "all" || (st === "success") === !!r.success) && (!q || String(r.msisdn).includes(q.replace(/\D/g, ""))); });
  const base = [{ h: "Time", r: (r: any) => ddmmyyyy(r.created_at) }, { h: "Number", r: (r: any) => <span className="mono">{mask(String(r.msisdn ?? ""))}</span> },
    { h: "Event", r: (r: any) => r.event_type }];
  const tail = [{ h: "Result", r: (r: any) => r.success ? <span style={{ color: "#1D6A45" }}>✓ Success</span> : <span style={{ color: "#DC2626" }}>✗ Failed</span> },
    { h: "Beem Code", r: (r: any) => r.beem_code ?? "—" }, { h: "Message", r: (r: any) => r.beem_message ?? "—" }, { h: "Error Detail", r: (r: any) => r.success ? "" : r.error_detail ?? "" }];
  const cols = kind === "otp" ? [...base, { h: "Context", r: (r: any) => r.app_context ?? "—" }, ...tail]
    : [...base, { h: "Application", r: (r: any) => r.application }, { h: "Reference ID", r: (r: any) => r.reference_id ? <a href={r.reference_id.startsWith("PCL-") ? "/parcels" : "/orders"} className="mono">{r.reference_id}</a> : "—" },
      { h: "Message Preview", r: (r: any) => <span className="mute">{String(r.message_body ?? "").slice(0, 60)}</span> }, ...tail];
  return <Panel right={<div className="toolbar">
    <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="From" /><input type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label="To" />
    <select value={ev} onChange={(e) => setEv(e.target.value)} aria-label="Event"><option value="">All events</option>{events.map((e) => <option key={e}>{e}</option>)}</select>
    <select value={st} onChange={(e) => setSt(e.target.value)} aria-label="Status"><option value="all">All</option><option value="success">Success</option><option value="failed">Failed</option></select>
    <input placeholder="Search number" value={q} onChange={(e) => setQ(e.target.value)} />
    <button className="btn out" onClick={() => download(`${kind}-logs.csv`, toCsv(shown.map((r) => ({ time: ddmmyyyy(r.created_at), number: mask(String(r.msisdn ?? "")), event: r.event_type,
      result: r.success ? "success" : "failed", beem_code: r.beem_code ?? "", message: r.beem_message ?? "", error: r.error_detail ?? "" }))))}>CSV</button></div>}>
    <Table rows={shown} cols={cols as any} empty="No log entries match." />
    {rows.length >= max && <button className="btn out" onClick={() => setMax(max + 100)}>Load more</button>}
  </Panel>;
}

/* =========================================================================== Withdrawals (Lipila) */

const W_TONE: Record<string, "ok" | "warn" | "red" | "info" | "grey"> = { COMPLETED: "ok", PENDING: "warn", SUBMITTED: "warn", LOCKED: "info", MANUAL_REVIEW: "red", FAILED: "grey", REQUESTED: "grey", PIN_VERIFIED: "grey" };

export function Withdrawals() {
  const rows = useCollection("withdrawal_requests", [orderBy("created_at", "desc"), limit(300)], "w").rows
    .slice().sort((a, b) => (toDate(b.created_at)?.getTime() ?? 0) - (toDate(a.created_at)?.getTime() ?? 0));
  const [filter, setFilter] = useState("active");
  const [open, setOpen] = useState<any>(null);
  const active = ["LOCKED", "SUBMITTED", "PENDING", "MANUAL_REVIEW"];
  const shown = rows.filter((r) => filter === "all" ? r.status !== "FAILED" || r.amount_ngwee > 0 : filter === "active" ? active.includes(r.status) : r.status === filter);
  const today = rows.filter((r) => (toDate(r.created_at)?.getTime() ?? 0) > Date.now() - 86_400_000);
  return <Page title="Withdrawals" sub="Full-balance wallet withdrawals via Lipila · only a Successful callback (or status check) completes one">
    <div className="kpis">
      <Kpi label="Waiting for Lipila" value={rows.filter((r) => ["SUBMITTED", "PENDING"].includes(r.status)).length} note={k(rows.filter((r) => ["SUBMITTED", "PENDING"].includes(r.status)).reduce((s, r) => s + r.amount_ngwee, 0)) + " locked"} tone="wn" />
      <Kpi label="Manual review" value={rows.filter((r) => r.status === "MANUAL_REVIEW").length} note="funds stay locked" tone={rows.some((r) => r.status === "MANUAL_REVIEW") ? "dn" : undefined} />
      <Kpi label="Paid today" value={k(today.filter((r) => r.status === "COMPLETED").reduce((s, r) => s + r.amount_ngwee, 0))} note={`${today.filter((r) => r.status === "COMPLETED").length} withdrawals`} tone="up" />
      <Kpi label="Failed today" value={today.filter((r) => r.status === "FAILED" && r.amount_ngwee > 0 && r.error_message !== "Incorrect PIN").length} note="balance restored" />
    </div>
    <Panel right={<div className="seg">{[["active", "Active"], ["MANUAL_REVIEW", "Manual review"], ["COMPLETED", "Completed"], ["FAILED", "Failed"], ["all", "All"]].map(([v, l]) =>
      <button key={v} className={filter === v ? "on" : ""} onClick={() => setFilter(v)}>{l}</button>)}</div>}>
      <Table rows={shown} onRow={setOpen} empty="Nothing here." cols={[{ h: "Requested", r: (r) => when(r.created_at) }, { h: "Who", r: (r) => <>{r.role}<div className="mute mono">{String(r.user_id).slice(0, 10)}</div></> },
        { h: "Amount", r: (r) => k(r.amount_ngwee) }, { h: "To", r: (r) => <span className="mono">{mask(r.account_number)}</span> },
        { h: "Status", r: (r) => <Pill tone={W_TONE[r.status] ?? "grey"}>{String(r.status).replace("_", " ").toLowerCase()}</Pill> },
        { h: "Lipila", r: (r) => <span className="mono mute">{r.lipila_identifier ?? r.reference_id?.slice(0, 16)}</span> }, { h: "Note", r: (r) => <span className="mute">{r.error_message ?? ""}</span> }]} />
    </Panel>
    {open && <WithdrawalDrawer w={rows.find((x) => x.id === open.id) ?? open} onClose={() => setOpen(null)} />}
  </Page>;
}

function WithdrawalDrawer({ w, onClose }: { w: any; onClose: () => void }) {
  const { admin } = useSession();
  const trail = useCollection("withdrawal_audit_log").rows.filter((a) => a.withdrawal_request_id === w.id).sort((a, b) => (toDate(a.timestamp)?.getTime() ?? 0) - (toDate(b.timestamp)?.getTime() ?? 0));
  const { busy, run, Msg } = useAction();
  const [note, setNote] = useState("");
  return <Drawer title={`Withdrawal ${String(w.id).slice(0, 8)}`} onClose={onClose}>
    <dl className="kv">
      <dt>Status</dt><dd><Pill tone={W_TONE[w.status] ?? "grey"}>{w.status}</Pill></dd><dt>Amount</dt><dd>{k(w.amount_ngwee)} (full balance)</dd>
      <dt>Account</dt><dd className="mono">{w.account_number}</dd><dt>Reference</dt><dd className="mono">{w.reference_id}</dd>
      <dt>Lipila identifier</dt><dd className="mono">{w.lipila_identifier ?? "—"}</dd>{w.retry_of && <><dt>Retry of</dt><dd className="mono">{w.retry_of}</dd></>}
      <dt>Message</dt><dd>{w.error_message ?? "—"}</dd><dt>Reconciliation</dt><dd>{w.recon_attempts ?? 0} checks{w.last_recon_result ? ` · last: ${w.last_recon_result}` : ""}</dd>
    </dl>
    <Panel title="Audit trail">
      <Table rows={trail} empty="No entries." cols={[{ h: "When", r: (a) => when(a.timestamp) }, { h: "From → to", r: (a) => `${a.from_status ?? "—"} → ${a.to_status}` }, { h: "Actor", r: (a) => a.actor },
        { h: "Detail", r: (a) => <span className="mute">{JSON.stringify(a.metadata ?? {}).slice(0, 120)}</span> }]} />
    </Panel>
    {w.lipila_response_raw && <Panel title="Lipila response"><pre style={{ whiteSpace: "pre-wrap", fontSize: 11 }}>{JSON.stringify(w.lipila_response_raw, null, 2)}</pre></Panel>}
    <Msg />
    {w.status === "MANUAL_REVIEW" && can(admin, "finance") && <div className="endpromo">
      <b>Resolve after checking the Lipila dashboard</b>
      <div className="field"><label htmlFor="wn">What you checked (kept in the audit log)</label><textarea id="wn" rows={2} value={note} onChange={(e) => setNote(e.target.value)} /></div>
      <div className="toolbar">
        <button className="btn ok" disabled={busy || note.trim().length < 10} onClick={() => run("admin_resolveWithdrawal", { withdrawalId: w.id, outcome: "completed", note }, "Marked as paid. Ledger debited.")}>Lipila shows Successful — complete</button>
        <button className="btn out" disabled={busy || note.trim().length < 10} onClick={() => run("admin_resolveWithdrawal", { withdrawalId: w.id, outcome: "failed", note }, "Marked failed. Balance restored.")}>Lipila shows Failed — restore balance</button>
      </div>
      <p className="mute">Never restore a balance unless Lipila confirms the disbursement failed — the money may already be with the user.</p>
    </div>}
  </Drawer>;
}
