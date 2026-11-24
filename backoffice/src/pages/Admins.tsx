import { useState } from "react";
import { useCollection } from "../lib/data";
import { useSession } from "../lib/session";
import { Page, Panel, Table, useAction } from "../lib/ui";

const ROLES = ["super_admin", "operations", "finance", "compliance", "support", "moderator", "analyst"];
const WHAT: Record<string, string> = {
  super_admin: "Everything, including admin users and IP allow-list", operations: "Zones, riders, dispatch, incidents, notifications",
  finance: "Escrow, payout retries, refunds, Social Fund claims, config", compliance: "KYC approvals, suspensions, documents",
  support: "User lookup, manual PIN recovery, disputes", moderator: "Listings, adverts, categories, SeloWizard prompts", analyst: "Read-only dashboards and exports",
};

export default function Admins() {
  const { admin } = useSession();
  const admins = useCollection("admins").rows;
  const [f, setF] = useState({ email: "", role: "support" });
  const { busy, run, Msg } = useAction();
  const sa = admin?.role === "super_admin";
  return <Page title="Admin users" sub="Roles are Firebase custom claims · enforce MFA and the IP allow-list in Firebase Authentication settings">
    <Panel><Table rows={admins} cols={[{ h: "Email", r: (a) => a.email }, { h: "Role", r: (a) => a.role ?? "removed" }, { h: "Can", r: (a) => WHAT[a.role] ?? "—" },
      { h: "", r: (a) => sa && a.role && <button className="link" disabled={busy} onClick={() => run("admin_setRole", { email: a.email, role: null }, `Removed ${a.email}.`)}>Remove</button> }]} /></Panel>
    {sa && <Panel title="Invite or change an admin">
      <div className="toolbar">
        <div className="field"><label htmlFor="e">Email</label><input id="e" type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} size={28} /></div>
        <div className="field"><label htmlFor="r">Role</label><select id="r" value={f.role} onChange={(e) => setF({ ...f, role: e.target.value })}>{ROLES.map((r) => <option key={r}>{r}</option>)}</select></div>
        <button className="btn" style={{ alignSelf: "end" }} disabled={busy || !f.email.includes("@")} onClick={() => run("admin_setRole", f, `${f.email} is now ${f.role}. They must sign in again.`)}>Save role</button>
      </div>
      <p style={{ color: "var(--mute)" }}>{WHAT[f.role]}</p>
      <Msg />
    </Panel>}
  </Page>;
}
