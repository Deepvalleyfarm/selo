import { useState } from "react";
import { useCollection } from "../lib/data";
import { download, k, toCsv, toDate, when } from "../lib/format";
import { Drawer, Kpi, Page, Panel, Pill, Table, useAction } from "../lib/ui";

type Op = "airtel" | "mtn" | "zamtel";
const OPS: Record<Op, { label: string; color: string }> = {
  airtel: { label: "Airtel Money", color: "#ED1C24" }, mtn: { label: "MTN Money", color: "#FFCB05" }, zamtel: { label: "Zamtel Money", color: "#2BB24C" },
};
export const opOf = (v?: string): Op | null => {
  if (!v) return null;
  if (/airtel/i.test(v)) return "airtel"; if (/mtn/i.test(v)) return "mtn"; if (/zamtel/i.test(v)) return "zamtel";
  const p = v.replace(/\D/g, "").replace(/^260/, "").replace(/^0/, "").slice(0, 2);
  return ["97", "77"].includes(p) ? "airtel" : ["96", "76"].includes(p) ? "mtn" : ["95", "75"].includes(p) ? "zamtel" : null;
};
export const OpMark = ({ op, size = 22 }: { op: Op | null; size?: number }) => op
  ? <img src={`/operators/${op}_mark.png`} alt={OPS[op].label} title={OPS[op].label} width={size} height={size} className="opmark" />
  : <span className="opmark" style={{ width: size, height: size }} />;

const statusTone = (s: string) => (({ ACTIVE: "ok", PENDING_PAYMENT: "warn", PAYMENT_FAILED: "red", ENDED: "grey", EXPIRED: "grey" } as const)[s as "ACTIVE"] ?? "grey");
const DAY = 86_400_000;

/** v6.2 Boosts & Feed Stories: platform revenue from paid reach, per operator, with per-promotion results. */
export default function Promotions() {
  const promos = useCollection("promotions").rows.sort((a, b) => (toDate(b.createdAt)?.getTime() ?? 0) - (toDate(a.createdAt)?.getTime() ?? 0));
  const revenue = useCollection("platformRevenue").rows;
  const [filter, setFilter] = useState("all");
  const [open, setOpen] = useState<any>(null);
  const [reason, setReason] = useState("");
  const [refund, setRefund] = useState(true);
  const { busy, run, Msg } = useAction();

  const since = (days: number) => revenue.filter((r) => (toDate(r.at)?.getTime() ?? 0) >= Date.now() - days * DAY);
  const sum = (rows: any[], src?: string) => rows.filter((r) => !src || r.source === src).reduce((s, r) => s + (r.amountNgwee ?? 0), 0);
  const last30 = since(30);
  const byOp = (Object.keys(OPS) as Op[]).map((op) => ({ op, n: sum(last30.filter((r) => opOf(r.operator) === op)) }));
  const maxOp = Math.max(1, ...byOp.map((x) => x.n));
  const active = promos.filter((p) => p.status === "ACTIVE");
  const shown = promos.filter((p) => filter === "all" || p.status === filter || p.type === filter);
  const ctr = (p: any) => (p.stats?.impressions ? `${((p.stats.clicks / p.stats.impressions) * 100).toFixed(1)}%` : "—");
  const days = Array.from({ length: 14 }, (_, i) => { const d = new Date(Date.now() - (13 - i) * DAY); return d.toISOString().slice(0, 10); });
  const perDay = days.map((d) => ({ d, boost: sum(revenue.filter((r) => toDate(r.at)?.toISOString().slice(0, 10) === d), "boost"),
    story: sum(revenue.filter((r) => toDate(r.at)?.toISOString().slice(0, 10) === d), "feed_story"), fees: sum(revenue.filter((r) => toDate(r.at)?.toISOString().slice(0, 10) === d), "order_fees") }));
  const maxDay = Math.max(1, ...perDay.map((x) => x.boost + x.story + x.fees));

  return <Page title="Boosts & revenue" sub="Paid reach and transaction fees · confirmed mobile money payments only"
    right={<button className="btn out" onClick={() => download("platform-revenue.csv", toCsv(revenue.map((r) => ({ date: toDate(r.at)?.toISOString() ?? "", source: r.source,
      amount_zmw: ((r.amountNgwee ?? 0) / 100).toFixed(2), operator: r.operator ?? "", promotion: r.promoId ?? "", order: r.orderId ?? "", lipila_ref: r.lipilaRef ?? "" }))))}>Export revenue (CSV)</button>}>
    <div className="kpis">
      <Kpi label="Promotion revenue · 30 days" value={k(sum(last30, "boost") + sum(last30, "feed_story"))} note={`Today ${k(sum(since(1), "boost") + sum(since(1), "feed_story"))}`} tone="up" />
      <Kpi label="Boosts · 30 days" value={k(sum(last30, "boost"))} note={`${active.filter((p) => p.type === "boost").length} live now`} />
      <Kpi label="Feed Stories · 30 days" value={k(sum(last30, "feed_story"))} note={`${active.filter((p) => p.type === "story").length} live now`} />
      <Kpi label="Platform & transaction fees · 30 days" value={k(sum(last30, "order_fees"))} note="from buyer checkouts" />
    </div>
    <div className="grid2">
      <Panel title="Revenue per day · last 14 days">
        <div className="stack">{perDay.map((x) => <div className="sb" key={x.d} title={`${x.d}: Boost ${k(x.boost)} · Story ${k(x.story)} · Fees ${k(x.fees)}`}>
          <div className="col" style={{ height: `${((x.boost + x.story + x.fees) / maxDay) * 100}%` }}>
            <i style={{ flex: x.fees, background: "var(--rev-fees)" }} /><i style={{ flex: x.story, background: "var(--rev-story)" }} /><i style={{ flex: x.boost, background: "var(--brand)" }} />
          </div><span>{x.d.slice(8)}</span></div>)}</div>
        <div className="legend"><span><i style={{ background: "var(--brand)" }} />Boost</span><span><i style={{ background: "var(--rev-story)" }} />Feed Story</span><span><i style={{ background: "var(--rev-fees)" }} />Transaction fees</span></div>
      </Panel>
      <Panel title="Collected by operator · 30 days">
        <div className="oprows">{byOp.map(({ op, n }) => <div className="oprow" key={op}>
          <img src={`/operators/${op}.png`} alt={OPS[op].label} className="oplogo" />
          <div className="t"><i style={{ width: `${(n / maxOp) * 100}%`, background: OPS[op].color }} /></div><b>{k(n)}</b></div>)}</div>
      </Panel>
    </div>
    <Msg />
    <Panel title={`Promotions (${shown.length})`} right={<div className="seg">{[["all", "All"], ["ACTIVE", "Live"], ["PAYMENT_FAILED", "Payment failed"], ["boost", "Boosts"], ["story", "Stories"], ["ENDED", "Ended"]].map(([v, l]) =>
      <button key={v} className={filter === v ? "on" : ""} onClick={() => setFilter(v)}>{l}</button>)}</div>}>
      <Table rows={shown} onRow={setOpen} empty="No promotions yet." cols={[
        { h: "Listing", r: (p) => <><b>{p.listingTitle}</b><div className="mute">{p.sellerName}</div></> },
        { h: "Type", r: (p) => <Pill tone={p.type === "story" ? "fund" : "info"}>{p.type === "story" ? "Feed Story" : `Boost · ${String(p.reach).toLowerCase()}`}</Pill> },
        { h: "Paid with", r: (p) => { const op = opOf(p.walletMsisdn); return <span className="opcell"><OpMark op={op} />{op ? OPS[op].label : "—"}</span>; } },
        { h: "Price", r: (p) => k(p.priceNgwee) }, { h: "Status", r: (p) => <Pill tone={statusTone(p.status)}>{String(p.status).replace(/_/g, " ").toLowerCase()}</Pill> },
        { h: "Reached", r: (p) => (p.stats?.impressions ?? 0).toLocaleString() }, { h: "CTR", r: ctr }, { h: "Enquiries", r: (p) => p.stats?.enquiries ?? 0 },
        { h: "Ends", r: (p) => p.endsAt ? when(p.endsAt) : "—" }]} />
    </Panel>
    {open && <Drawer title={open.listingTitle} onClose={() => setOpen(null)}>
      <dl className="kv">
        <dt>Seller</dt><dd>{open.sellerName}</dd>
        <dt>Type</dt><dd>{open.type === "story" ? "Feed Story · 24 h" : `Boost · ${String(open.reach).toLowerCase()} · ${open.days} day(s)`}</dd>
        <dt>Price</dt><dd>{k(open.priceNgwee)}</dd>
        <dt>Paid with</dt><dd className="opcell"><OpMark op={opOf(open.walletMsisdn)} size={26} />{open.walletMsisdn}</dd>
        <dt>Status</dt><dd><Pill tone={statusTone(open.status)}>{String(open.status).replace(/_/g, " ").toLowerCase()}</Pill>{open.failureReason && <div className="mute">{open.failureReason}</div>}</dd>
        <dt>Areas</dt><dd>{(open.targeting?.districts?.length ? open.targeting.districts : open.targeting?.provinces?.length ? open.targeting.provinces : ["All Zambia"]).join(", ")}</dd>
        <dt>Ages</dt><dd>{open.targeting?.ageBands?.join(", ") || "All ages"}</dd>
        <dt>Interests</dt><dd>{open.targeting?.interests?.join(", ") || "Any"}{open.targeting?.searchIntent ? " · people searching" : ""}{open.targeting?.followers ? " · followers" : ""}</dd>
        <dt>Estimated reach</dt><dd>{(open.estimatedReach ?? 0).toLocaleString()} buyers</dd>
        <dt>Results</dt><dd>{open.stats?.impressions ?? 0} reached · {open.stats?.clicks ?? 0} opened · {open.stats?.likes ?? 0} likes · {open.stats?.enquiries ?? 0} enquiries</dd>
        <dt>Lipila ref</dt><dd className="mono">{open.paidRef ?? open.collectionRef ?? "—"}</dd>
      </dl>
      {open.status === "ACTIVE" && <div className="endpromo">
        <div className="field"><label htmlFor="why">Reason (sent to the seller)</label><input id="why" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. Breaks the content policy" /></div>
        <label className="chk"><input type="checkbox" checked={refund} onChange={(e) => setRefund(e.target.checked)} /> Refund the unused days to the seller's wallet</label>
        <button className="btn" disabled={busy || reason.trim().length < 4} onClick={async () => {
          const r = await run("admin_endPromotion", { promoId: open.id, reason, refund }, "Promotion ended. The seller has been notified.");
          if (r) setOpen(null);
        }}>End promotion</button>
      </div>}
    </Drawer>}
  </Page>;
}
