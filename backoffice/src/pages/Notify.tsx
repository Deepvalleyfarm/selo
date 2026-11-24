import { useState } from "react";
import { can, useSession } from "../lib/session";
import { Page, Panel, useAction } from "../lib/ui";

export default function Notify() {
  const { admin } = useSession();
  const [f, setF] = useState({ title: "", body: "", role: "", city: "", uid: "" });
  const { busy, run, Msg } = useAction();
  const audience = f.uid ? { uid: f.uid } : { role: f.role || undefined, city: f.city || undefined };
  return <Page title="Notifications" sub="Push + in-app message to a role, a city, or one user">
    <Panel>
      <div style={{ display: "grid", gap: 10, maxWidth: 560 }}>
        <div className="field"><label htmlFor="t">Title</label><input id="t" maxLength={80} value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} placeholder="Heavy rain in Lusaka today" /></div>
        <div className="field"><label htmlFor="b">Message</label><textarea id="b" rows={3} maxLength={300} value={f.body} onChange={(e) => setF({ ...f, body: e.target.value })} placeholder="Riders: take care on Great East Road. Deliveries may take longer." /></div>
        <div className="toolbar">
          <div className="field"><label htmlFor="r">Role</label><select id="r" value={f.role} onChange={(e) => setF({ ...f, role: e.target.value })}><option value="">Everyone</option>{["BUYER", "SELLER", "AGENT", "RIDER"].map((r) => <option key={r}>{r}</option>)}</select></div>
          <div className="field"><label htmlFor="c">City</label><input id="c" value={f.city} onChange={(e) => setF({ ...f, city: e.target.value })} placeholder="Lusaka" /></div>
          <div className="field"><label htmlFor="u">Or one user ID</label><input id="u" value={f.uid} onChange={(e) => setF({ ...f, uid: e.target.value })} /></div>
        </div>
        <Msg />
        <button className="btn" disabled={busy || !f.title || !f.body || !can(admin, "operations", "moderator")}
          onClick={() => run("admin_sendNotification", { title: f.title, body: f.body, audience }, "Sent. Devices receive it within a few seconds.")}>Send notification</button>
      </div>
    </Panel>
  </Page>;
}
