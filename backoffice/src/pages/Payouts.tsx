import { useState } from "react";
import { orderBy, limit } from "firebase/firestore";
import { useCollection } from "../lib/data";
import { k, maskMsisdn, when } from "../lib/format";
import { can, useSession } from "../lib/session";
import { Kpi, Page, Panel, StatePill, Table, useAction } from "../lib/ui";

export default function Payouts() {
  const { admin } = useSession();
  const payouts = useCollection("payouts", [orderBy("createdAt", "desc"), limit(500)], "all").rows;
  const orders = useCollection("orders", [orderBy("createdAt", "desc"), limit(1000)], "recent").rows;
  const [f, setF] = useState("attention");
  const [paidRef, setPaidRef] = useState<Record<string, string>>({});
  const { busy, run, Msg } = useAction();
  const rows = payouts.filter((p) => f === "all" || (f === "attention" ? ["FAILED_RETRY", "ESCALATED"].includes(p.status) : p.status === f));
  const held = orders.filter((o) => ["ESCROW_LOCKED", "BROADCASTING", "RIDER_ASSIGNED", "PICKED_UP", "IN_TRANSIT"].includes(o.state));
  const heldNgwee = held.reduce((s, o) => s + (["PICKED_UP", "IN_TRANSIT"].includes(o.state) ? o.deliveryNgwee : o.totalNgwee), 0);
  const finance = can(admin, "finance");
  return <Page title="Escrow & payouts" sub="Every Lipila disbursement leg · failures retry 1, 5, 15, 60 min then hourly for 24 h before escalating">
    <div className="kpis">
      <Kpi label="Held in escrow" value={k(heldNgwee)} note={`${held.length} open orders`} tone="wn" />
      <Kpi label="Failing payouts" value={payouts.filter((p) => p.status === "FAILED_RETRY").length} note="auto-retrying" tone="dn" />
      <Kpi label="Escalated" value={payouts.filter((p) => p.status === "ESCALATED").length} note="need manual action" tone="dn" />
      <Kpi label="Paid out" value={k(payouts.filter((p) => p.status === "DISBURSED").reduce((s, p) => s + p.amountNgwee, 0))} note="loaded legs" tone="up" />
    </div>
    <div className="toolbar"><div className="seg">{[["attention", "Needs attention"], ["PENDING", "Pending"], ["DISBURSED", "Paid"], ["all", "All"]].map(([v, l]) =>
      <button key={v} className={f === v ? "on" : ""} onClick={() => setF(v)}>{l}</button>)}</div></div>
    <Msg />
    <Panel><Table rows={rows} empty="Nothing needs attention." cols={[
      { h: "Reference", r: (p) => <span className="mono">{p.ref ?? p.id}</span> }, { h: "Leg", r: (p) => p.leg },
      { h: "Amount", r: (p) => k(p.amountNgwee) }, { h: "Wallet", r: (p) => maskMsisdn(p.msisdn) },
      { h: "Status", r: (p) => <StatePill s={p.status} /> }, { h: "Attempts", r: (p) => p.attempts ?? 0 },
      { h: "Next try", r: (p) => p.status === "FAILED_RETRY" ? when(p.nextAttemptAt) : "—" }, { h: "Last error", r: (p) => p.lastError ?? "—" },
      { h: "Action", r: (p) => finance && p.status !== "DISBURSED" && <div style={{ display: "grid", gap: 6 }}>
        <button className="btn" disabled={busy} onClick={() => run("admin_retryPayout", { ref: p.ref ?? p.id }, `Retry sent for ${p.ref ?? p.id}.`)}>Retry now</button>
        <input placeholder="Lipila ref if paid manually" value={paidRef[p.id] ?? ""} onChange={(e) => setPaidRef({ ...paidRef, [p.id]: e.target.value })} />
        <button className="btn out" disabled={busy || !paidRef[p.id]} onClick={() => run("admin_retryPayout", { ref: p.ref ?? p.id, markPaid: true, lipilaRef: paidRef[p.id] }, "Marked as paid.")}>Mark paid</button>
      </div> }]} /></Panel>
    <p style={{ color: "var(--mute)" }}>Withdrawals are started only by the rider, seller or agent in the app. Admins can retry a failed leg but cannot start one.</p>
  </Page>;
}
