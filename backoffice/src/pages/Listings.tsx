import { useState } from "react";
import { useCollection } from "../lib/data";
import { k, when } from "../lib/format";
import { can, useSession } from "../lib/session";
import { Page, Panel, StatePill, Table, useAction } from "../lib/ui";

export default function Listings() {
  const { admin } = useSession();
  const listings = useCollection("listings").rows;
  const flagged = useCollection("moderationQueue").rows;
  const [q, setQ] = useState("");
  const { busy, run, Msg } = useAction();
  const mod = can(admin, "moderator");
  const act = (id: string, status: string) => run("admin_moderateListing", { listingId: id, status }, `Listing ${status}. Feeds update within seconds.`);
  return <Page title="Listings moderation" sub="Media flagged by the scan or reported by users">
    <Msg />
    <Panel title={`Flagged by AI scan (${flagged.length})`}><Table rows={flagged} empty="Nothing flagged." cols={[
      { h: "Uploader", r: (m) => <span className="mono">{m.uid}</span> }, { h: "Reason", r: (m) => m.reason }, { h: "Files", r: (m) => (m.paths ?? []).length }, { h: "When", r: (m) => when(m.createdAt) }]} /></Panel>
    <div className="toolbar"><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search listings" aria-label="Search listings" /></div>
    <Panel><Table rows={listings.filter((l) => !q || `${l.title} ${l.sellerName}`.toLowerCase().includes(q.toLowerCase()))} cols={[
      { h: "Title", r: (l) => <b>{l.title}</b> }, { h: "Seller", r: (l) => l.sellerName }, { h: "Category", r: (l) => l.category },
      { h: "Price", r: (l) => k(l.priceNgwee) }, { h: "Views · saves", r: (l) => `${l.viewCount ?? 0} · ${l.saveCount ?? 0}` },
      { h: "Status", r: (l) => <StatePill s={l.status} /> },
      { h: "Action", r: (l) => mod && <div className="toolbar">
        {l.status !== "live" && <button className="link" disabled={busy} onClick={() => act(l.id, "live")}>Approve</button>}
        {l.status === "live" && <button className="link" disabled={busy} onClick={() => act(l.id, "paused")}>Pause</button>}
        {l.status !== "removed" && <button className="link" disabled={busy} onClick={() => act(l.id, "removed")}>Remove</button>}</div> }]} /></Panel>
  </Page>;
}
