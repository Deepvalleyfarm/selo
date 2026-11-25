import { useState } from "react";
import { useCollection } from "../lib/data";
import { download, toCsv, when } from "../lib/format";
import { Page, Panel, Pill, Table, useAction } from "../lib/ui";

/**
 * Buyer engagement per seller. Admins see every engaged buyer (operations, support, fraud). Sellers only ever receive
 * buyers who switched on "Let sellers contact me" — required by Zambia's Data Protection Act 2021.
 */
export default function Leads() {
  const sellers = useCollection("users").rows.filter((u) => u.role === "SELLER");
  const [seller, setSeller] = useState("");
  const [data, setData] = useState<{ rows: any[]; shareable: any[] } | null>(null);
  const { busy, run, Msg } = useAction();
  const load = async (uid: string) => { setSeller(uid); setData(null); const r = await run("admin_exportLeads", { sellerUid: uid }, "Loaded. This view is recorded in the audit log."); if (r) setData(r); };
  const name = sellers.find((s) => s.id === seller)?.storeName ?? "";
  return <Page title="Buyer engagement & leads" sub="Who liked, saved or asked about a seller's listings in the last 30 days">
    <div className="banner">Seller exports include <b>only buyers who opted in</b> to being contacted by sellers. Other buyers are visible here for operations only and must not be shared.</div>
    <div className="toolbar"><select value={seller} onChange={(e) => load(e.target.value)} aria-label="Seller"><option value="">Choose a seller…</option>
      {sellers.map((s) => <option key={s.id} value={s.id}>{s.storeName ?? s.fullName}</option>)}</select></div>
    <Msg />
    {data && <Panel title={`${data.rows.length} engaged buyers · ${data.shareable.length} consented`} right={<button className="btn out" disabled={busy || !data.shareable.length}
      onClick={() => download(`leads-${name.toLowerCase().replace(/\W+/g, "-")}.csv`, toCsv(data.shareable.map((r) => ({ name: r.name, phone: r.msisdn, area: r.area, district: r.district,
        likes: r.likes, saves: r.saves, enquiries: r.enquiries, listings: r.listings }))))}>Export consented leads (CSV)</button>}>
      <Table rows={data.rows.map((r) => ({ ...r, id: r.uid }))} cols={[
        { h: "Buyer", r: (r) => <b>{r.name}</b> }, { h: "Phone", r: (r) => r.msisdn }, { h: "Area", r: (r) => `${r.area}, ${r.district}` },
        { h: "Likes", r: (r) => r.likes }, { h: "Saves", r: (r) => r.saves }, { h: "Enquiries", r: (r) => r.enquiries }, { h: "Listings", r: (r) => r.listings },
        { h: "Last active", r: (r) => when(r.lastActive) }, { h: "Consent", r: (r) => r.consent ? <Pill tone="ok">Shareable</Pill> : <Pill tone="grey">Admin only</Pill> }]} />
    </Panel>}
  </Page>;
}
