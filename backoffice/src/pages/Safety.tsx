import { orderBy, where } from "firebase/firestore";
import { useCollection } from "../lib/data";
import { when } from "../lib/format";
import { Page, Panel, StatePill, Table, useAction } from "../lib/ui";

/** Rider Safety Centre: live SOS alerts and shared incident locations. Operations call the rider and close the alert. */
export default function Safety() {
  const open = useCollection("sosAlerts", [where("status", "==", "OPEN")], "open").rows.filter((r) => r.status === "OPEN");
  const recent = useCollection("sosAlerts", [orderBy("createdAt", "desc")], "recent").rows.slice(0, 50);
  const { busy, run, Msg } = useAction();
  const maps = (r: any) => `https://maps.google.com/?q=${Number(r.lat).toFixed(6)},${Number(r.lng).toFixed(6)}`;
  return <Page title="Rider safety" sub="SOS alerts open here immediately · call the rider first, then the emergency contact">
    <Msg />
    <Panel title={`Open alerts (${open.length})`}>
      <Table rows={open} empty="No open alerts." cols={[
        { h: "Type", r: (r) => <StatePill s={r.kind === "SOS" ? "SOS" : "SHARED"} /> },
        { h: "Rider", r: (r) => <b>{r.riderName}</b> }, { h: "Phone", r: (r) => <a href={`tel:${r.riderMsisdn}`}>{r.riderMsisdn}</a> },
        { h: "Plate", r: (r) => r.plate ?? "—" }, { h: "Contact told", r: (r) => (r.contactNotified ? "Yes (SMS)" : "No") },
        { h: "Since", r: (r) => when(r.createdAt) }, { h: "Location", r: (r) => <a href={maps(r)} target="_blank" rel="noreferrer">Open map</a> },
        { h: "", r: (r) => <button className="btn out" disabled={busy} onClick={() => run("admin_closeSos", { alertId: r.id }, "Alert closed.")}>Close</button> },
      ]} />
    </Panel>
    <Panel title="Recent">
      <Table rows={recent} cols={[{ h: "Type", r: (r) => r.kind }, { h: "Rider", r: (r) => r.riderName }, { h: "Status", r: (r) => <StatePill s={r.status} /> },
        { h: "Created", r: (r) => when(r.createdAt) }, { h: "Note", r: (r) => r.note || "—" }]} />
    </Panel>
  </Page>;
}
