import { useState } from "react";
import { orderBy, limit } from "firebase/firestore";
import { useCollection } from "../lib/data";
import { download, toCsv, when } from "../lib/format";
import { Page, Panel, Table } from "../lib/ui";

export default function Audit() {
  const logs = useCollection("auditLogs", [orderBy("at", "desc"), limit(500)], "recent").rows;
  const events = useCollection("securityEvents", [orderBy("at", "desc"), limit(100)], "recent").rows;
  const [q, setQ] = useState("");
  const rows = logs.filter((l) => !q || JSON.stringify(l).toLowerCase().includes(q.toLowerCase()));
  return <Page title="Audit log" sub="Append-only record of every admin action and config change"
    right={<button className="btn out" onClick={() => download("audit-log.csv", toCsv(rows.map((r) => ({ when: String(r.at?.toDate?.() ?? r.at), action: r.action, target: r.target, by: r.actorEmail, role: r.actorRole, ip: r.ip, before: JSON.stringify(r.before), after: JSON.stringify(r.after) }))))}>Export CSV</button>}>
    <div className="toolbar"><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter by action, admin, target" aria-label="Filter" /></div>
    <Panel><Table rows={rows} cols={[
      { h: "When", r: (l) => when(l.at) }, { h: "Action", r: (l) => <span className="mono">{l.action}</span> }, { h: "Target", r: (l) => <span className="mono">{l.target}</span> },
      { h: "By", r: (l) => `${l.actorEmail ?? l.actorUid ?? "system"}${l.actorRole ? ` · ${l.actorRole}` : ""}` },
      { h: "Before → after", r: (l) => <span className="mono">{JSON.stringify(l.before)} → {JSON.stringify(l.after)}</span> }, { h: "IP", r: (l) => <span className="mono">{l.ip ?? "—"}</span> }]} /></Panel>
    <Panel title="Security events (lockouts, PIN resets, new-device logins)"><Table rows={events} empty="No events." cols={[
      { h: "When", r: (e) => when(e.at) }, { h: "Type", r: (e) => <span className="mono">{e.type}</span> }, { h: "User", r: (e) => e.uid ?? e.msisdn ?? "—" }]} /></Panel>
  </Page>;
}
