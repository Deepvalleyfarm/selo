import { useCollection } from "../lib/data";
import { download, toCsv } from "../lib/format";
import { Page, Panel } from "../lib/ui";

export default function Reports() {
  const users = useCollection("users").rows;
  const wallets = useCollection("riderWallets").rows;
  const orders = useCollection("orders").rows;
  const riders = () => users.filter((u) => u.role === "RIDER").map((u) => {
    const w = wallets.find((x) => x.id === u.id) ?? {};
    return { rider_id: u.id, name: u.fullName, province: u.province ?? "", city: u.district ?? "", total_deliveries: (w as any).completedDeliveries ?? 0,
      social_fund_ngwee: (w as any).socialFundNgwee ?? 0, available_ngwee: (w as any).availableNgwee ?? 0, tier: (w as any).tier ?? "", status: u.accountStatus };
  });
  const orderRows = () => orders.map((o) => ({ order_id: o.id, state: o.state, buyer: o.buyerName, seller: o.sellerName, rider: o.riderName ?? "",
    goods_ngwee: o.goodsNgwee, delivery_ngwee: o.deliveryNgwee, total_ngwee: o.totalNgwee, parcel: !!o.isParcel, insured: !!o.isInsured }));
  const reports: [string, string, () => Record<string, unknown>[]][] = [
    ["Rider performance", "Per rider: deliveries, Social Fund, available balance, tier", riders],
    ["Orders and parcels", "Every loaded order with fee breakdown and state", orderRows],
    ["Social Fund statement", "Ring-fenced balances per rider (never merged with revenue)", () => riders().map(({ rider_id, name, social_fund_ngwee }) => ({ rider_id, name, social_fund_ngwee }))],
  ];
  return <Page title="Reports" sub="CSV exports of the data currently loaded from Firestore">
    {reports.map(([name, desc, rows]) => <Panel key={name} title={name} right={<button className="btn out" onClick={() => download(`${name.toLowerCase().replace(/\s+/g, "-")}.csv`, toCsv(rows()))}>Export CSV</button>}>
      <p style={{ margin: 0, color: "var(--mute)" }}>{desc}</p></Panel>)}
  </Page>;
}
