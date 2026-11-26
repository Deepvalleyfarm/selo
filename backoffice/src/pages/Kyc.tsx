import { useEffect, useState } from "react";
import { where } from "firebase/firestore";
import { getDownloadURL, listAll, ref } from "firebase/storage";
import { useCollection } from "../lib/data";
import { storage } from "../lib/firebase";
import { when } from "../lib/format";
import { can, useSession } from "../lib/session";
import { Drawer, Page, Panel, StatePill, Table, useAction } from "../lib/ui";

export default function Kyc() {
  const queue = useCollection("kycQueue", [where("status", "==", "PENDING")], "p").rows.filter((r) => r.status === "PENDING");
  const users = useCollection("users").rows;
  const reverify = users.filter((u) => u.kycStatus === "REVERIFY");
  const [open, setOpen] = useState<string | null>(null);
  const u = (id: string) => users.find((x) => x.id === id);
  return <Page title="KYC queue" sub="Sellers, agents and riders waiting for verification · target 24–48 h">
    <Panel title={`Pending (${queue.length})`}>
      <Table rows={queue} onRow={(r) => setOpen(r.uid)} empty="Queue is clear." cols={[
        { h: "Applicant", r: (r) => <b>{u(r.uid)?.fullName ?? r.uid}</b> }, { h: "Role", r: (r) => r.role + (u(r.uid)?.accountType === "CORPORATE" ? " · corporate" : "") },
        { h: "Store / vehicle", r: (r) => u(r.uid)?.storeName ?? u(r.uid)?.vehicle ?? "—" }, { h: "City", r: (r) => u(r.uid)?.district ?? "—" },
        { h: "Documents", r: (r) => `${(r.documents ?? []).length} files` }, { h: "Waiting", r: (r) => when(r.submittedAt) }]} />
    </Panel>
    {reverify.length > 0 && <Panel title="Re-verification (vehicle or details changed)">
      <Table rows={reverify} onRow={(r) => setOpen(r.id)} cols={[{ h: "Rider", r: (r) => r.fullName }, { h: "City", r: (r) => r.district }, { h: "Status", r: () => <StatePill s="PENDING" /> }]} />
    </Panel>}
    {open && <Review uid={open} user={u(open)} onClose={() => setOpen(null)} />}
  </Page>;
}

function Review({ uid, user, onClose }: { uid: string; user: any; onClose: () => void }) {
  const { admin } = useSession();
  const { busy, run, Msg } = useAction();
  const [reason, setReason] = useState("");
  const [docs, setDocs] = useState<{ name: string; url: string }[]>([]);
  useEffect(() => {
    if (!storage) { setDocs(["NRC front", "NRC back", "Portrait", "Licence front", "Licence back"].map((n) => ({ name: n, url: "" }))); return; }
    listAll(ref(storage, `kyc/${uid}`)).then(async (l) => setDocs(await Promise.all(l.items.map(async (i) => ({ name: i.name.split("-")[0], url: await getDownloadURL(i) }))))).catch(() => setDocs([]));
  }, [uid]);
  return <Drawer title={`Review · ${user?.fullName ?? uid}`} onClose={onClose}>
    <dl className="kv"><dt>Role</dt><dd>{user?.role}</dd><dt>Mobile</dt><dd>{user?.msisdn}</dd><dt>Store / hub</dt><dd>{user?.storeName ?? user?.hub ?? "—"}</dd><dt>Account type</dt><dd>{user?.accountType === "CORPORATE" ? "Corporate" : "Individual"}</dd>{user?.accountType === "CORPORATE" && <><dt>Registered address</dt><dd>{user?.registeredAddress ?? "—"}</dd><dt>Registration no.</dt><dd>{user?.corporate?.regNumber ?? "—"}</dd><dt>TPIN</dt><dd>{user?.corporate?.tpin ?? "—"}</dd></>}
      <dt>Vehicle</dt><dd>{user?.vehicleDetails ? `${user.vehicle} · ${user.vehicleDetails.make} ${user.vehicleDetails.model} · ${user.vehicleDetails.plate}` : user?.vehicle ?? "—"}</dd>
      <dt>Payout wallet</dt><dd>{user?.payoutWallet ?? "—"}</dd></dl>
    <div className="docs">{docs.map((d) => d.url
      ? <a key={d.name + d.url} className="doc" href={d.url} target="_blank" rel="noreferrer" style={{ backgroundImage: `url(${d.url})`, backgroundSize: "cover", color: "#fff", textShadow: "0 1px 3px #000" }}>{d.name}</a>
      : <div key={d.name} className="doc">{d.name}</div>)}</div>
    <p style={{ color: "var(--mute)", margin: 0 }}>{user?.accountType === "CORPORATE" ? "Check the organisation name, registration number and TPIN match the certificate of incorporation (and PACRA / ZRA records)." : "Check the face matches the NRC portrait, the names match across documents, and the licence is valid."}</p>
    <Msg />
    {can(admin, "compliance") ? <>
      <button className="btn ok" disabled={busy} onClick={async () => { if (await run("admin_setKyc", { uid, approve: true }, "Approved. The user's app unlocks within seconds.")) setTimeout(onClose, 900); }}>Approve</button>
      <div className="field"><label htmlFor="reason">Reason for rejection (sent to the user)</label>
        <textarea id="reason" rows={2} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Licence photo is blurred. Please retake it in daylight." /></div>
      <button className="btn out" disabled={busy || reason.length < 6} onClick={async () => { if (await run("admin_setKyc", { uid, approve: false, reason }, "Rejected with reason.")) setTimeout(onClose, 900); }}>Reject with reason</button>
    </> : <p className="err">Only Compliance can approve or reject.</p>}
  </Drawer>;
}
