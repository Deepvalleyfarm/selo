import { OpMark, opOf } from "./Promotions";
import { useState } from "react";
import { collection, onSnapshot, orderBy, query, limit } from "firebase/firestore";
import { useEffect } from "react";
import { useCollection } from "../lib/data";
import { db } from "../lib/firebase";
import { k, when } from "../lib/format";
import { Drawer, Page, Panel, Pill, StatePill, Table } from "../lib/ui";

const FILTERS = ["All", "BROADCASTING", "RIDER_ASSIGNED", "IN_TRANSIT", "DELIVERED", "PAYOUT_PENDING_RETRY", "REFUNDED"];

export default function Orders() {
  const orders = useCollection("orders", [orderBy("createdAt", "desc"), limit(500)], "recent").rows;
  const [f, setF] = useState("All");
  const [kind, setKind] = useState("all");
  const [open, setOpen] = useState<any>(null);
  const rows = orders.filter((o) => (f === "All" || o.state === f) && (kind === "all" || (kind === "parcels") === !!o.isParcel));
  return <Page title="Orders & parcels" sub="Live from the app · newest first">
    <div className="toolbar">
      <div className="seg">{FILTERS.map((s) => <button key={s} className={f === s ? "on" : ""} onClick={() => setF(s)}>{s.replace(/_/g, " ").toLowerCase()}</button>)}</div>
      <select value={kind} onChange={(e) => setKind(e.target.value)} aria-label="Type"><option value="all">Orders and parcels</option><option value="orders">Orders</option><option value="parcels">Parcels</option></select>
    </div>
    <Panel><Table rows={rows} onRow={setOpen} cols={[
      { h: "ID", r: (o) => <span className="mono">{o.id}</span> },
      { h: "Buyer", r: (o) => o.buyerName }, { h: "Seller", r: (o) => o.sellerName }, { h: "Rider", r: (o) => o.riderName ?? "—" },
      { h: "Zone", r: (o) => o.zoneName }, { h: "Total", r: (o) => k(o.totalNgwee) },
      { h: "Payment", r: (o) => <span className="opcell" title={o.paymentFailure ?? ""}><OpMark op={opOf(o.walletMsisdn)} />
        {o.paymentStatus === "FAILED" ? <Pill tone="red">failed</Pill> : o.state === "INITIATED" ? <Pill tone="warn">awaiting</Pill> : <Pill tone="ok">paid</Pill>}</span> },
      { h: "State", r: (o) => <><StatePill s={o.state} /> {o.isInsured && <Pill tone="ok">INSURED</Pill>}</> }, { h: "Created", r: (o) => when(o.createdAt) }]} /></Panel>
    {open && <OrderDrawer o={open} onClose={() => setOpen(null)} />}
  </Page>;
}

function OrderDrawer({ o, onClose }: { o: any; onClose: () => void }) {
  const [ledger, setLedger] = useState<any[]>([]);
  useEffect(() => {
    if (!db) { setLedger([{ id: "1", type: "credit", leg: "collection", amountNgwee: o.totalNgwee }, { id: "2", type: "credit", leg: "platform_fee", amountNgwee: 500 }]); return; }
    return onSnapshot(query(collection(db, `escrowLedger/${o.id}/entries`), orderBy("at")), (s) => setLedger(s.docs.map((d) => ({ id: d.id, ...d.data() }))), () => setLedger([]));
  }, [o.id]);
  return <Drawer title={`${o.isParcel ? "Parcel" : "Order"} ${o.id}`} onClose={onClose}>
    <dl className="kv">
      <dt>State</dt><dd><StatePill s={o.state} /></dd><dt>Buyer</dt><dd>{o.buyerName}</dd><dt>Seller</dt><dd>{o.sellerName}</dd>
      <dt>Rider</dt><dd>{o.riderName ?? "—"}</dd><dt>Pickup → drop-off</dt><dd>{o.pickup ?? "—"} → {o.dropoff ?? "—"}</dd>
      <dt>Goods</dt><dd>{k(o.goodsNgwee)}</dd><dt>Delivery</dt><dd>{k(o.deliveryNgwee)}</dd><dt>Total collected</dt><dd>{k(o.totalNgwee)}</dd>
      <dt>Seller leg</dt><dd><StatePill s={o.sellerPayoutStatus} /></dd><dt>Rider leg</dt><dd><StatePill s={o.riderPayoutStatus} /></dd>
      {o.insuranceId && <><dt>Insurance</dt><dd className="mono">{o.insuranceId} · {k(o.declaredValueNgwee)}</dd></>}
      {o.refundReason && <><dt>Refund reason</dt><dd>{o.refundReason}</dd></>}
      <dt>Lipila collection</dt><dd className="mono">{o.lipilaCollectionRef ?? `SELO-COL-${o.id}`}</dd>
    </dl>
    <Panel title="Escrow ledger (append-only)">
      <Table rows={ledger} empty="No ledger entries yet." cols={[{ h: "Type", r: (e) => e.type }, { h: "Leg", r: (e) => e.leg }, { h: "Amount", r: (e) => k(e.amountNgwee) }, { h: "Ref", r: (e) => <span className="mono">{e.lipilaRef ?? "—"}</span> }]} />
    </Panel>
    <p style={{ color: "var(--mute)", margin: 0 }}>Refunds and releases are made from Disputes so each one has a reason on record.</p>
  </Drawer>;
}
