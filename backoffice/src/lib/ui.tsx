import { useState, type ReactNode } from "react";
import { Icon } from "./icons";
import { callAdmin } from "./data";

export const Pill = ({ tone, children }: { tone: "ok" | "warn" | "red" | "info" | "fund" | "grey"; children: ReactNode }) =>
  <span className={`pill p-${tone}`}>{children}</span>;

export const stateTone: Record<string, "ok" | "warn" | "red" | "info" | "fund" | "grey"> = {
  INITIATED: "grey", ESCROW_LOCKED: "info", BROADCASTING: "warn", RIDER_ASSIGNED: "info", PICKED_UP: "fund", IN_TRANSIT: "fund",
  DELIVERED: "ok", REFUNDED: "grey", CANCELLED: "grey", PAYOUT_PENDING_RETRY: "red",
  ACTIVE: "ok", PENDING_KYC: "warn", PENDING_VERIFICATION: "warn", SUSPENDED: "red", PENDING: "warn", APPROVED: "ok", REJECTED: "red",
  OPEN: "warn", RESOLVED: "ok", FAILED_RETRY: "red", DISBURSED: "ok", ESCALATED: "red", HELD: "info", live: "ok", draft: "grey", paused: "warn", removed: "red",
};
export const StatePill = ({ s }: { s?: string }) => <Pill tone={stateTone[s ?? ""] ?? "grey"}>{(s ?? "—").replace(/_/g, " ")}</Pill>;

export function Page({ title, sub, right, children }: { title: string; sub?: string; right?: ReactNode; children: ReactNode }) {
  return <div className="content">
    <div className="ptitle"><div><h1>{title}</h1>{sub && <small>{sub}</small>}</div>{right}</div>
    {children}
  </div>;
}

export function Panel({ title, right, children }: { title?: string; right?: ReactNode; children: ReactNode }) {
  return <section className="panel">{title && <h2>{title}{right}</h2>}{children}</section>;
}

export interface Col<T> { h: string; r: (row: T) => ReactNode; w?: number }
export function Table<T extends { id: string }>({ rows, cols, onRow, empty = "Nothing here yet." }: { rows: T[]; cols: Col<T>[]; onRow?: (r: T) => void; empty?: string }) {
  if (!rows.length) return <div className="empty">{empty}</div>;
  return <div className="tablewrap"><table>
    <thead><tr>{cols.map((c) => <th key={c.h} style={c.w ? { width: c.w } : undefined}>{c.h}</th>)}</tr></thead>
    <tbody>{rows.map((r) => <tr key={r.id} className={onRow ? "click" : ""} onClick={() => onRow?.(r)}>{cols.map((c) => <td key={c.h}>{c.r(r)}</td>)}</tr>)}</tbody>
  </table></div>;
}

export function Drawer({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return <div className="drawer-bg" onClick={onClose} role="dialog" aria-modal="true">
    <aside className="drawer" onClick={(e) => e.stopPropagation()}>
      <h3>{title}<button className="link" onClick={onClose} aria-label="Close"><Icon n="close" /></button></h3>
      {children}
    </aside>
  </div>;
}

export const Kpi = ({ label, value, note, tone }: { label: string; value: ReactNode; note?: string; tone?: "up" | "dn" | "wn" }) =>
  <div className="k"><small>{label}</small><b>{value}</b>{note && <em className={tone}>{note}</em>}</div>;

/** Runs an admin callable with busy/error state and a confirmation prompt shown inline. */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const run = async (name: string, data: Record<string, unknown>, okText: string) => {
    setBusy(true); setMsg(null);
    try { const r = await callAdmin(name, data); setMsg({ ok: true, text: okText }); return r; }
    catch (e) { setMsg({ ok: false, text: (e as Error).message }); return null; }
    finally { setBusy(false); }
  };
  const Msg = () => msg ? <div className={`banner ${msg.ok ? "ok" : "err"}`}>{msg.text}</div> : null;
  return { busy, run, Msg };
}
