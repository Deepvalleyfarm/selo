import { useState } from "react";
import { Link } from "react-router-dom";
import { limit, orderBy, where } from "firebase/firestore";
import { useCollection } from "../lib/data";
import { k, k0, when } from "../lib/format";
import { Kpi, Page, Panel, StatePill, Table } from "../lib/ui";

const STATES = ["ESCROW_LOCKED", "BROADCASTING", "RIDER_ASSIGNED", "IN_TRANSIT", "PICKED_UP", "DELIVERED", "PAYOUT_PENDING_RETRY", "REFUNDED"];

export default function Overview() {
  const [range, setRange] = useState(1);
  const since = new Date(Date.now() - range * 86_400_000);
  const orders = useCollection("orders", [where("createdAt", ">=", since), orderBy("createdAt", "desc"), limit(2000)], `o${range}`).rows;
  const payouts = useCollection("payouts", [where("status", "in", ["FAILED_RETRY", "ESCALATED"])], "f").rows.filter((p) => p.status !== "DISBURSED");
  const presence = useCollection("riderPresence", [where("isOnline", "==", true)], "on").rows.filter((r) => r.isOnline);
  const kyc = useCollection("kycQueue", [where("status", "==", "PENDING")], "p").rows.filter((r) => r.status === "PENDING");
  const users = useCollection("users").rows;
  const gmv = orders.filter((o) => !["INITIATED", "CANCELLED", "REFUNDED"].includes(o.state)).reduce((s, o) => s + (o.totalNgwee ?? 0), 0);
  const held = orders.filter((o) => ["ESCROW_LOCKED", "BROADCASTING", "RIDER_ASSIGNED", "PICKED_UP", "IN_TRANSIT"].includes(o.state))
    .reduce((s, o) => s + (o.state === "PICKED_UP" || o.state === "IN_TRANSIT" ? o.deliveryNgwee : o.totalNgwee), 0);
  const byState = STATES.map((s) => [s, orders.filter((o) => o.state === s).length] as const);
  const max = Math.max(1, ...byState.map(([, n]) => n));
  const name = (uid: string) => users.find((u) => u.id === uid);

  return <Page title="Overview" sub={`${new Date().toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long", year: "numeric" })} · all zones`}
    right={<div className="seg">{[[1, "Today"], [7, "7 days"], [30, "30 days"]].map(([d, l]) =>
      <button key={d} className={range === d ? "on" : ""} onClick={() => setRange(d as number)}>{l}</button>)}</div>}>
    <div className="kpis">
      <Kpi label={`GMV · ${range === 1 ? "today" : `${range} days`}`} value={k0(gmv)} note={`${orders.length} orders`} tone="up" />
      <Kpi label="Held in escrow" value={k0(held)} note="open orders" tone="wn" />
      <Kpi label="Riders online" value={presence.length} note="live presence" tone="up" />
      <Kpi label="Payouts to retry" value={payouts.length} note={payouts.some((p) => p.status === "ESCALATED") ? "escalations need you" : "auto-retrying"} tone="dn" />
      <Kpi label="KYC queue" value={kyc.length} note={`${kyc.filter((x) => x.role === "RIDER").length} riders · ${kyc.filter((x) => x.role !== "RIDER").length} sellers/agents`} tone="wn" />
    </div>
    <div className="cols">
      <Panel title="Payout retry queue" right={<Link className="link" to="/payouts">Open monitor</Link>}>
        <Table rows={payouts} empty="No failed payouts. Every leg has been paid." cols={[
          { h: "Reference", r: (p) => <span className="mono">{p.ref}</span> }, { h: "Leg", r: (p) => p.leg },
          { h: "Amount", r: (p) => k(p.amountNgwee) }, { h: "Error", r: (p) => p.lastError ?? "—" },
          { h: "Attempts", r: (p) => `${p.attempts ?? 0} · next ${when(p.nextAttemptAt)}` }, { h: "Status", r: (p) => <StatePill s={p.status} /> }]} />
      </Panel>
      <Panel title="Orders by state · live">
        <div className="hbars">{byState.map(([s, n]) => <div className="hb" key={s}><span>{s.replace(/_/g, " ")}</span>
          <span className="t"><i style={{ width: `${(n / max) * 100}%`, background: s === "DELIVERED" ? "#10B981" : s === "PAYOUT_PENDING_RETRY" ? "#CC0023" : s === "BROADCASTING" ? "#F59E0B" : undefined }} /></span><b>{n}</b></div>)}</div>
      </Panel>
    </div>
    <Panel title="KYC queue" right={<Link className="link" to="/kyc">Review all {kyc.length}</Link>}>
      <Table rows={kyc.slice(0, 6)} empty="No applications waiting." cols={[
        { h: "Applicant", r: (r) => name(r.uid)?.fullName ?? r.uid }, { h: "Role", r: (r) => r.role },
        { h: "City", r: (r) => name(r.uid)?.district ?? "—" }, { h: "Documents", r: (r) => (r.documents ?? []).join(" · ") },
        { h: "Submitted", r: (r) => when(r.submittedAt) }, { h: "Status", r: (r) => <StatePill s={r.status} /> }]} />
    </Panel>
  </Page>;
}
