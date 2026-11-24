import { useState } from "react";
import { useCollection } from "../lib/data";
import { when } from "../lib/format";
import { can, useSession } from "../lib/session";
import { Drawer, Page, Panel, StatePill, Table, useAction } from "../lib/ui";

export default function Disputes() {
  const disputes = useCollection("disputes").rows;
  const [open, setOpen] = useState<any>(null);
  return <Page title="Disputes" sub="Unpaid escrow legs are frozen until a dispute is resolved">
    <Panel><Table rows={disputes} onRow={setOpen} empty="No disputes." cols={[
      { h: "Dispute", r: (d) => <span className="mono">{d.id}</span> }, { h: "Order", r: (d) => <span className="mono">{d.orderId}</span> },
      { h: "Raised by", r: (d) => d.raisedBy }, { h: "Reason", r: (d) => d.reason }, { h: "Status", r: (d) => <StatePill s={d.status} /> },
      { h: "Age", r: (d) => when(d.createdAt) }]} /></Panel>
    {open && <Resolve d={open} onClose={() => setOpen(null)} />}
  </Page>;
}

function Resolve({ d, onClose }: { d: any; onClose: () => void }) {
  const { admin } = useSession();
  const { busy, run, Msg } = useAction();
  const [notes, setNotes] = useState("");
  const [share, setShare] = useState("");
  const go = (outcome: string, extra: Record<string, unknown> = {}) =>
    run("admin_resolveDispute", { disputeId: d.id, outcome, notes, ...extra }, `Resolved: ${outcome}. Both parties have been notified.`);
  return <Drawer title={`Dispute ${d.id}`} onClose={onClose}>
    <dl className="kv"><dt>Order</dt><dd className="mono">{d.orderId}</dd><dt>Raised by</dt><dd>{d.raisedBy}</dd><dt>Reason</dt><dd>{d.reason}</dd><dt>Status</dt><dd><StatePill s={d.status} /></dd></dl>
    <div className="field"><label htmlFor="notes">Resolution notes</label><textarea id="notes" rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} /></div>
    <Msg />
    {can(admin, "support", "finance") && d.status !== "RESOLVED" ? <>
      <button className="btn" disabled={busy || !notes} onClick={() => go("refund")}>Refund buyer in full</button>
      <button className="btn ok" disabled={busy || !notes} onClick={() => go("release")}>Release to seller and rider</button>
      <div className="toolbar"><input placeholder="Buyer share in ngwee" value={share} onChange={(e) => setShare(e.target.value)} />
        <button className="btn out" disabled={busy || !notes || !share} onClick={() => go("split", { buyerShareNgwee: Number(share) })}>Split</button></div>
    </> : null}
  </Drawer>;
}
