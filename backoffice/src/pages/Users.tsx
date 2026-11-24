import { useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useCollection } from "../lib/data";
import { maskMsisdn, when } from "../lib/format";
import { can, useSession } from "../lib/session";
import { Drawer, Page, Panel, StatePill, Table, useAction, type Col } from "../lib/ui";

export default function Users() {
  const [params] = useSearchParams();
  const [q, setQ] = useState(params.get("q") ?? "");
  const [role, setRole] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const users = useCollection("users").rows;
  const rows = useMemo(() => users.filter((u) => (!role || u.role === role) &&
    (!q || [u.fullName, u.msisdn, u.storeName, u.id, u.area].some((v) => String(v ?? "").toLowerCase().includes(q.toLowerCase())))), [users, q, role]);
  const cols: Col<any>[] = [
    { h: "Name", r: (u) => <><b>{u.fullName}</b>{u.storeName && <div style={{ color: "var(--mute)" }}>{u.storeName}</div>}</> },
    { h: "Role", r: (u) => u.role }, { h: "Mobile", r: (u) => maskMsisdn(u.msisdn) }, { h: "Area", r: (u) => `${u.area ?? ""}${u.district ? `, ${u.district}` : ""}` },
    { h: "Status", r: (u) => <StatePill s={u.accountStatus} /> }, { h: "KYC", r: (u) => u.kycStatus ?? "—" }, { h: "Joined", r: (u) => when(u.createdAt) }];
  return <Page title="Users" sub={`${rows.length} of ${users.length} accounts`}>
    <div className="toolbar">
      <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Name, number, store, ID" aria-label="Search users" />
      <select value={role} onChange={(e) => setRole(e.target.value)} aria-label="Role"><option value="">All roles</option>{["BUYER", "SELLER", "AGENT", "RIDER"].map((r) => <option key={r}>{r}</option>)}</select>
    </div>
    <Panel><Table rows={rows} cols={cols} onRow={(u) => setOpen(u.id)} /></Panel>
    {open && <UserDrawer user={users.find((u) => u.id === open)!} onClose={() => setOpen(null)} />}
  </Page>;
}

function UserDrawer({ user, onClose }: { user: any; onClose: () => void }) {
  const { admin } = useSession();
  const { busy, run, Msg } = useAction();
  const [token, setToken] = useState<string | null>(null);
  const [note, setNote] = useState("");
  const suspended = user.accountStatus === "SUSPENDED";
  return <Drawer title={user.fullName} onClose={onClose}>
    <dl className="kv">
      <dt>Role</dt><dd>{user.role}</dd><dt>Status</dt><dd><StatePill s={user.accountStatus} /></dd>
      <dt>Mobile</dt><dd>{user.msisdn}</dd><dt>Store / hub</dt><dd>{user.storeName ?? user.hub ?? "—"}</dd>
      <dt>Area</dt><dd>{user.area ?? "—"}, {user.district ?? ""}</dd><dt>Payout wallet</dt><dd>{maskMsisdn(user.payoutWallet)}</dd>
      <dt>KYC</dt><dd>{user.kycStatus ?? "—"}</dd><dt>Referral code</dt><dd className="mono">{user.referralCode ?? "—"}</dd>
      <dt>Joined</dt><dd>{when(user.createdAt)}</dd><dt>User ID</dt><dd className="mono">{user.id}</dd>
    </dl>
    <Msg />
    {can(admin, "compliance", "support") && <button className={suspended ? "btn ok" : "btn"} disabled={busy}
      onClick={() => run("admin_suspendUser", { uid: user.id, suspend: !suspended }, suspended ? "Account reactivated." : "Account suspended and signed out on all devices.")}>
      {suspended ? "Reactivate account" : "Suspend account"}</button>}
    {can(admin, "support") && <Panel title="Manual PIN recovery">
      <p style={{ margin: "0 0 8px", color: "var(--mute)" }}>Use only after verifying identity (NRC on video call or in person). Issues a 30-minute single-use reset token.</p>
      <div className="field"><label htmlFor="note">Verification note</label><input id="note" value={note} onChange={(e) => setNote(e.target.value)} placeholder="NRC checked in person at Soweto office" /></div>
      <button className="btn out" style={{ marginTop: 8 }} disabled={busy || note.length < 8}
        onClick={async () => { const r = await run("admin_issueResetToken", { uid: user.id, note }, "Reset token issued."); if (r) setToken(r.resetToken); }}>Issue reset token</button>
      {token && <p className="mono" style={{ wordBreak: "break-all" }}>{token}</p>}
    </Panel>}
  </Drawer>;
}
