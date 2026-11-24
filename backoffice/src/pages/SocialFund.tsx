import { useCollection } from "../lib/data";
import { k, when } from "../lib/format";
import { can, useSession } from "../lib/session";
import { Kpi, Page, Panel, StatePill, Table, useAction } from "../lib/ui";

export default function SocialFund() {
  const { admin } = useSession();
  const wallets = useCollection("riderWallets").rows;
  const claims = useCollection("socialFundClaims").rows;
  const { busy, run, Msg } = useAction();
  const balance = wallets.reduce((s, w) => s + (w.socialFundNgwee ?? 0), 0);
  return <Page title="Rider Social Fund" sub="rider_social_fund · a separate named account, never merged with platform revenue">
    <div className="kpis">
      <Kpi label="Locked balance (all riders)" value={k(balance)} note={`${wallets.length} riders`} />
      <Kpi label="Claims pending" value={claims.filter((c) => c.status === "PENDING").length} note="review within 48 h" tone="wn" />
      <Kpi label="Approved claims" value={k(claims.filter((c) => c.status === "APPROVED").reduce((s, c) => s + (c.approvedNgwee ?? c.amountNgwee), 0))} tone="up" note="paid via Lipila" />
    </div>
    <Msg />
    <Panel title="Claims">
      <Table rows={claims} empty="No claims." cols={[
        { h: "Rider", r: (c) => c.riderName ?? c.riderUid }, { h: "Category", r: (c) => c.category }, { h: "Amount", r: (c) => k(c.amountNgwee) },
        { h: "Details", r: (c) => c.description }, { h: "Filed", r: (c) => when(c.createdAt) }, { h: "Status", r: (c) => <StatePill s={c.status} /> },
        { h: "Decision", r: (c) => can(admin, "finance") && c.status === "PENDING" && <div className="toolbar">
          <button className="btn ok" disabled={busy} onClick={() => run("admin_decideClaim", { claimId: c.id, approve: true }, "Claim approved and payout started.")}>Approve</button>
          <button className="btn out" disabled={busy} onClick={() => run("admin_decideClaim", { claimId: c.id, approve: false, reason: "Not eligible" }, "Claim rejected.")}>Reject</button></div> }]} />
    </Panel>
  </Page>;
}
