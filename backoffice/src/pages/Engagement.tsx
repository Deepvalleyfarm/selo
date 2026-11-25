import { useCollection } from "../lib/data";
import { Kpi, Page, Panel, Table } from "../lib/ui";

/** Expected-outcome tracking for v6.1 (session time, conversion, video vs photo), from adminMetrics read models. */
export default function Engagement() {
  const rows = useCollection("adminMetrics").rows;
  const daily = rows.filter((r) => r.type === "daily").sort((a, b) => String(a.date).localeCompare(String(b.date)));
  const weak = rows.filter((r) => r.type === "weak_categories").sort((a, b) => String(b.date).localeCompare(String(a.date)))[0];
  const avg = (r: any) => (r.sessions ? r.sessionMs / r.sessions / 60_000 : 0);
  const conv = (r: any) => (r.events_listing_view ? r.events_enquiry_open / r.events_listing_view : 0);
  const per = (r: any, t: string) => { const views = r[`${t}_listing_view`] || 0; return views ? ((r[`${t}_like`] || 0) + (r[`${t}_share`] || 0) + (r[`${t}_enquiry_open`] || 0)) / views : 0; };
  const first = daily[0], last = daily.at(-1);
  const sum = (k: string) => daily.reduce((s, r) => s + (r[k] || 0), 0);
  const tot = Object.fromEntries(["video", "photo"].flatMap((t) => ["listing_view", "like", "share", "enquiry_open"].map((k) => [`${t}_${k}`, sum(`${t}_${k}`)])));
  const videoLift = per(tot, "photo") ? per(tot, "video") / per(tot, "photo") : 0;
  const max = Math.max(1, ...daily.map(avg));
  return <Page title="Engagement & outcomes" sub="Read models built from the behaviour event log · updated live">
    <div className="kpis">
      <Kpi label="Avg session time" value={last ? `${avg(last).toFixed(1)} min` : "—"} note={first && last ? `${(avg(last) / Math.max(0.1, avg(first))).toFixed(1)}× vs ${first.date} · target 3×` : ""} tone="up" />
      <Kpi label="View → enquiry conversion" value={last ? `${(conv(last) * 100).toFixed(1)}%` : "—"} note={first && last ? `${(((conv(last) / Math.max(1e-6, conv(first))) - 1) * 100).toFixed(0)}% vs ${first.date} · target +40%` : ""} tone="up" />
      <Kpi label="Video vs photo interactions" value={videoLift ? `${videoLift.toFixed(1)}×` : "—"} note="per view · target 2.5×" tone={videoLift >= 2.5 ? "up" : "wn"} />
      <Kpi label="Sessions today" value={last?.sessions ?? 0} note={`${last?.events_listing_view ?? 0} listing views`} />
    </div>
    <Panel title="Average session minutes per day">
      <div className="hbars">{daily.map((r) => <div className="hb" key={r.id}><span>{r.date}</span><span className="t"><i className="r" style={{ width: `${(avg(r) / max) * 100}%`, background: "var(--brand)" }} /></span><b>{avg(r).toFixed(1)}</b></div>)}</div>
    </Panel>
    <Panel title="Daily KPIs">
      <Table rows={[...daily].reverse()} cols={[
        { h: "Date", r: (r) => r.date }, { h: "Sessions", r: (r) => r.sessions ?? 0 }, { h: "Avg min", r: (r) => avg(r).toFixed(1) },
        { h: "Views", r: (r) => r.events_listing_view ?? 0 }, { h: "Likes", r: (r) => r.events_like ?? 0 }, { h: "Shares", r: (r) => r.events_share ?? 0 },
        { h: "Enquiries", r: (r) => r.events_enquiry_open ?? 0 }, { h: "Conversion", r: (r) => `${(conv(r) * 100).toFixed(1)}%` },
        { h: "Video / photo interaction rate", r: (r) => `${(per(r, "video") * 100).toFixed(1)}% / ${(per(r, "photo") * 100).toFixed(1)}%` }]} />
    </Panel>
    {weak && <Panel title={`Underperforming categories (weekly, ${weak.date})`}>
      <p style={{ margin: 0 }}>{weak.categories?.length ? `${weak.categories.join(", ")} — engagement below half the median across all cohorts. Suggested: seller growth campaigns (video listing coaching, featured adverts).` : "None this week."}</p>
    </Panel>}
  </Page>;
}
