import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { callAdmin } from "../lib/data";
import { k } from "../lib/format";
import { useSession } from "../lib/session";
import { Drawer, Kpi, Page, Panel } from "../lib/ui";

/* Super Admin Transaction Monitor — every Lipila callback, statuses shown verbatim. Server-side filtering via
   admin_tx* callables (Super Admin check, rate limit and view audit on every call). */

const KNOWN_STATUSES = ["Successful", "Failed", "Pending", "Wrong PIN", "Wrong Number", "Insufficient Funds", "Transaction Limit Exceeded", "Timeout", "Cancelled", "Network Error", "System Error", "Unknown"];
const PAYMENT_TYPES = ["AirtelMoney", "MTNMoney", "ZamtelKwacha", "Card", "Bank"];
const BADGE: Record<string, [string, string]> = {
  "Successful": ["#DCFCE7", "#166534"], "Failed": ["#FEE2E2", "#991B1B"], "Pending": ["#FEF9C3", "#854D0E"], "Wrong PIN": ["#FFEDD5", "#9A3412"],
  "Wrong Number": ["#FFEDD5", "#9A3412"], "Insufficient Funds": ["#FEE2E2", "#991B1B"], "Timeout": ["#F3F4F6", "#4B5563"], "Cancelled": ["#F3F4F6", "#4B5563"],
};
export const StatusBadge = ({ s }: { s?: string | null }) => { const [bg, fg] = BADGE[s ?? ""] ?? ["#F3E8FF", "#6B21A8"];
  return <span style={{ background: bg, color: fg, borderRadius: 999, padding: "2px 9px", fontSize: 12, fontWeight: 600, whiteSpace: "nowrap" }}>{s || "—"}</span>; };

const iso = (d: Date) => d.toISOString().slice(0, 10);
const preset = (days: number) => ({ dateFrom: iso(new Date(Date.now() - (days - 1) * 86_400_000)), dateTo: iso(new Date()) });
const fmt = (v: string | null, tz?: string) => v ? new Date(v).toLocaleString("en-GB", { timeZone: tz, dateStyle: "short", timeStyle: "medium" }) : "—";
const zmw = (n: unknown) => `ZMW ${Number(n ?? 0).toLocaleString("en-ZM", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

type Tx = Record<string, any> & { id: string };
interface Filters { q: string; status: string; paymentType: string; type: string; dateFrom: string; dateTo: string }

export default function Transactions() {
  const { admin } = useSession();
  const { id } = useParams();
  if (admin?.role !== "super_admin") return <Page title="Payment Transaction Monitor"><Panel><p>This monitor is available to Super Admins only.</p></Panel></Page>;
  return <Monitor deepLink={id} />;
}

function Monitor({ deepLink }: { deepLink?: string }) {
  const [f, setF] = useState<Filters>({ q: "", status: "", paymentType: "", type: "", ...preset(7) });
  const [qInput, setQInput] = useState("");
  const [rows, setRows] = useState<Tx[]>([]);
  const [total, setTotal] = useState(0);
  const [cursors, setCursors] = useState<(string | null)[]>([null]);
  const [page, setPage] = useState(0);
  const [next, setNext] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [stats, setStats] = useState<any>(null);
  const [auto, setAuto] = useState(true);
  const [refreshedAt, setRefreshedAt] = useState<Date | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(deepLink ?? null);
  const [sort, setSort] = useState<{ by: "processedAt" | "amount" | "status"; dir: "asc" | "desc" }>({ by: "processedAt", dir: "desc" });
  const newest = useRef<string | null>(null);

  // debounce the search box (400 ms)
  useEffect(() => { const t = setTimeout(() => setF((x) => ({ ...x, q: qInput.trim() })), 400); return () => clearTimeout(t); }, [qInput]);

  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    setErr(null);
    try {
      const params = { ...f, cursor: cursors[page], pageSize: 50, sortDir: sort.by === "processedAt" ? sort.dir : "desc" };
      const [r, s] = await Promise.all([callAdmin<{ data: Tx[]; total: number; nextCursor: string | null }>("admin_txSearch", params),
        callAdmin("admin_txStats", { dateFrom: f.dateFrom, dateTo: f.dateTo })]);
      if (quiet && page === 0 && newest.current && r.data[0] && r.data[0].id !== newest.current) {
        const fresh = r.data.findIndex((x) => x.id === newest.current);
        setToast(`${fresh < 0 ? "New" : fresh} new transaction${fresh === 1 ? "" : "s"}`); setTimeout(() => setToast(null), 4000);
      }
      if (page === 0) newest.current = r.data[0]?.id ?? null;
      setRows(r.data); setTotal(r.total); setNext(r.nextCursor); setStats(s); setRefreshedAt(new Date());
    } catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }, [f, cursors, page, sort.dir, sort.by]);

  useEffect(() => { void load(); }, [load]);
  useEffect(() => { if (!auto) return; const t = setInterval(() => void load(true), 30_000); return () => clearInterval(t); }, [auto, load]);
  const setFilter = (patch: Partial<Filters>) => { setF((x) => ({ ...x, ...patch })); setPage(0); setCursors([null]); };

  const shown = useMemo(() => sort.by === "processedAt" ? rows : [...rows].sort((a, b) => (sort.dir === "asc" ? 1 : -1) *
    (sort.by === "amount" ? Number(a.amount) - Number(b.amount) : String(a.status).localeCompare(String(b.status)))), [rows, sort]);
  const statuses = [...new Set([...KNOWN_STATUSES, ...((stats?.byStatus ?? []) as { status: string }[]).map((s) => s.status)])];
  const pct = (n: number) => (stats?.totalCount ? `${Math.round((n / stats.totalCount) * 100)}%` : "0%");
  const th = (label: string, by: "processedAt" | "amount" | "status") => <span style={{ cursor: "pointer" }} onClick={() => setSort((s) => ({ by, dir: s.by === by && s.dir === "desc" ? "asc" : "desc" }))}>
    {label}{sort.by === by ? (sort.dir === "desc" ? " ↓" : " ↑") : ""}</span>;

  const exportCsv = async () => {
    const r = await callAdmin<{ csv: string; filename: string }>("admin_txExport", { ...f });
    const url = URL.createObjectURL(new Blob([r.csv], { type: "text/csv;charset=utf-8" }));
    const a = document.createElement("a"); a.href = url; a.download = r.filename; a.click(); URL.revokeObjectURL(url);
  };

  return <Page title="Payment Transaction Monitor" sub={`Every Lipila callback, statuses verbatim · last refresh ${refreshedAt ? refreshedAt.toLocaleTimeString("en-GB") : "—"}`}
    right={<div className="toolbar">
      <label className="mute" style={{ display: "flex", gap: 6, alignItems: "center" }}><input type="checkbox" checked={auto} onChange={(e) => setAuto(e.target.checked)} /> Auto-refresh 30 s</label>
      <button className="btn out" onClick={() => void load()}>Refresh</button>
      <button className="btn" style={{ background: "#1B6B2E" }} onClick={() => void exportCsv()}>Export CSV</button></div>}>
    {toast && <div className="banner ok">{toast}</div>}
    <div className="kpis">
      <Kpi label="Total transactions" value={(stats?.totalCount ?? 0).toLocaleString()} note={`${f.dateFrom} → ${f.dateTo}`} />
      <Kpi label="Successful" value={(stats?.successCount ?? 0).toLocaleString()} note={pct(stats?.successCount ?? 0)} tone="up" />
      <Kpi label="Failed" value={(stats?.failedCount ?? 0).toLocaleString()} note={pct(stats?.failedCount ?? 0)} tone={stats?.failedCount ? "dn" : undefined} />
      <Kpi label="Volume collected" value={k(stats?.totalVolumeNgwee ?? 0)} note="successful collections" />
    </div>
    {stats?.byPaymentType?.length > 0 && <Panel title="By payment type">
      <div style={{ display: "grid", gap: 6 }}>{stats.byPaymentType.map((p: any) => <div key={p.paymentType} style={{ display: "grid", gridTemplateColumns: "130px 1fr 90px", alignItems: "center", gap: 8 }}>
        <span>{p.paymentType || "—"}</span>
        <div style={{ background: "#EEF2EE", borderRadius: 4, height: 10 }}><div style={{ width: `${Math.round((p.count / Math.max(1, stats.byPaymentType[0].count)) * 100)}%`, background: "#1B6B2E", height: 10, borderRadius: 4 }} /></div>
        <span className="mute">{p.count.toLocaleString()}</span></div>)}</div>
    </Panel>}
    <Panel>
      <div className="toolbar" style={{ flexWrap: "wrap", marginBottom: 10 }}>
        <input style={{ minWidth: 260 }} placeholder="Search by TX ID, mobile, reference..." value={qInput} onChange={(e) => setQInput(e.target.value)} />
        <select value={f.status} onChange={(e) => setFilter({ status: e.target.value })} aria-label="Status"><option value="">All statuses</option>{statuses.map((s) => <option key={s}>{s}</option>)}</select>
        <select value={f.paymentType} onChange={(e) => setFilter({ paymentType: e.target.value })} aria-label="Payment type"><option value="">All payment types</option>{PAYMENT_TYPES.map((s) => <option key={s}>{s}</option>)}</select>
        <select value={f.type} onChange={(e) => setFilter({ type: e.target.value })} aria-label="Type"><option value="">All</option><option>Collection</option><option>Disbursement</option></select>
        <div className="seg">{[["Today", 1], ["7 Days", 7], ["30 Days", 30], ["90 Days", 90]].map(([l, d]) => { const p = preset(d as number);
          return <button key={l} className={f.dateFrom === p.dateFrom && f.dateTo === p.dateTo ? "on" : ""} onClick={() => setFilter(p)}>{l}</button>; })}</div>
        <input type="date" value={f.dateFrom} onChange={(e) => setFilter({ dateFrom: e.target.value })} aria-label="From" />
        <input type="date" value={f.dateTo} onChange={(e) => setFilter({ dateTo: e.target.value })} aria-label="To" />
        <button className="btn out" onClick={() => { setQInput(""); setFilter({ q: "", status: "", paymentType: "", type: "", ...preset(7) }); }}>Clear all filters</button>
      </div>
      <div className="mute" style={{ marginBottom: 6 }}>Showing {shown.length} of {total.toLocaleString()} transactions</div>
      {err && <div className="banner err">{err}</div>}
      <div className="tablewrap" style={{ overflowX: "auto" }}>
        <table style={{ minWidth: 1100 }}>
          <thead><tr><th>#</th><th>{th("Date & Time", "processedAt")}</th><th>Ref ID</th><th>Internal ID</th><th>Mobile Number</th><th>{th("Amount", "amount")}</th>
            <th>Payment Type</th><th>{th("Status", "status")}</th><th>Type</th><th>Message</th><th>Actions</th></tr></thead>
          <tbody>
            {loading && !rows.length ? Array.from({ length: 6 }, (_, i) => <tr key={i}><td colSpan={11}><div style={{ height: 14, background: "#EEF0EE", borderRadius: 4 }} /></td></tr>)
              : shown.map((r, i) => <tr key={r.id} className="click" onClick={() => setOpen(r.id)} style={{ background: i % 2 ? "#FAFBFA" : undefined }}>
                <td className="mute">{page * 50 + i + 1}</td><td>{fmt(r.processedAt, "Africa/Lusaka")}</td><td className="mono">{String(r.referenceId ?? "").slice(0, 13)}</td>
                <td className="mono">{r.identifier ?? "—"}</td><td className="mono">{r.accountNumber}</td><td>{zmw(r.amount)}</td><td>{r.paymentType || "—"}</td>
                <td><StatusBadge s={r.status} /></td><td>{r.type}</td><td className="mute" style={{ maxWidth: 220, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{r.message}</td>
                <td><button className="btn out" onClick={(e) => { e.stopPropagation(); setOpen(r.id); }}>View</button></td></tr>)}
          </tbody>
        </table>
      </div>
      {!loading && !rows.length && <div style={{ textAlign: "center", padding: 30 }}><div style={{ fontSize: 40 }}>🧾</div><p className="mute">No transactions match these filters.</p></div>}
      <div className="toolbar" style={{ marginTop: 10 }}>
        <button className="btn out" disabled={page === 0} onClick={() => setPage(page - 1)}>‹ Prev</button>
        <span className="mute">Page {page + 1}</span>
        <button className="btn out" disabled={!next} onClick={() => { setCursors((c) => { const x = [...c]; x[page + 1] = next; return x; }); setPage(page + 1); }}>Next ›</button>
      </div>
    </Panel>
    {open && <TxDetail id={open} onClose={() => setOpen(null)} />}
  </Page>;
}

function TxDetail({ id, onClose }: { id: string; onClose: () => void }) {
  const [t, setT] = useState<any>(null);
  const [err, setErr] = useState<string | null>(null);
  const [raw, setRaw] = useState(false);
  useEffect(() => { callAdmin("admin_txGet", { id }).then(setT).catch((e) => setErr((e as Error).message)); }, [id]);
  const copy = (v?: string) => v && navigator.clipboard?.writeText(v);
  return <Drawer title="Transaction" onClose={onClose}>
    {err && <div className="banner err">{err}</div>}
    {!t ? <p className="mute">Loading…</p> : <>
      <dl className="kv">
        <dt>Status</dt><dd><StatusBadge s={t.status} /></dd>
        <dt>Reference ID</dt><dd className="mono">{t.referenceId} <button className="btn out" onClick={() => copy(t.referenceId)}>Copy</button></dd>
        <dt>Internal ID</dt><dd className="mono">{t.identifier || "—"} {t.identifier && <button className="btn out" onClick={() => copy(t.identifier)}>Copy</button>}</dd>
        <dt>External ID (MNO)</dt><dd className="mono">{t.externalId ? <span>{t.externalId} <button className="btn out" onClick={() => copy(t.externalId)}>Copy</button></span> : "—"}</dd>
        <dt>Amount</dt><dd>{zmw(t.amount)} {t.currency}</dd><dt>Mobile number</dt><dd className="mono">{t.accountNumber}</dd>
        <dt>Payment type</dt><dd>{t.paymentType || "—"}</dd><dt>Type</dt><dd>{t.type}</dd><dt>Message</dt><dd>{t.message || "—"}</dd>
        <dt>Narration</dt><dd>{t.referenceData || "—"}</dd><dt>Device IP</dt><dd className="mono">{t.ipAddress || "—"}</dd>
        <dt>Received (UTC)</dt><dd>{fmt(t.processedAt, "UTC")}</dd><dt>Received (Lusaka)</dt><dd>{fmt(t.processedAt, "Africa/Lusaka")}</dd>
        <dt>Webhook ID</dt><dd className="mono">{t.webhookId ?? `— (${t.source})`}</dd><dt>Merchant</dt><dd>{t.merchantId ?? "—"}</dd>
        <dt>Processed</dt><dd>{t.processed ? "Yes" : "No"}{t.processingError ? ` · ${t.processingError}` : ""}</dd>
      </dl>
      <button className="btn out" onClick={() => setRaw(!raw)}>{raw ? "Hide" : "Show"} raw payload</button>
      {raw && <pre style={{ background: "#0F1A12", color: "#B9F5C8", padding: 12, borderRadius: 8, fontSize: 11, whiteSpace: "pre-wrap", marginTop: 8 }}>{JSON.stringify(t.rawPayload, null, 2)}</pre>}
      <p className="mute" style={{ marginTop: 8 }}>Deep link: {location.origin}/transactions/{id}</p>
    </>}
  </Drawer>;
}
