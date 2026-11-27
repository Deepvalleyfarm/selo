/**
 * Developer & Platform Integrations (Super Admin): the SeloNachipa Open Network control centre.
 * Register/approve platforms, services, scopes, environments, rate limits, partner pricing, credentials
 * (create / rotate / revoke), webhooks, and per-platform usage, products, deliveries, settlement and audit.
 */
import { useEffect, useState } from "react";
import { limit, orderBy, where } from "firebase/firestore";
import { callAdmin, useCollection } from "../lib/data";
import { k, when } from "../lib/format";
import { useSession } from "../lib/session";
import { Drawer, Kpi, Page, Panel, StatePill, Table, useAction } from "../lib/ui";

const SCOPES: [string, string, string][] = [
  ["products:write", "commerce", "Publish & update products"], ["products:read", "commerce", "Read own products"], ["inventory:write", "commerce", "Stock updates"],
  ["catalog:read", "commerce", "See products shared with it"], ["media:write", "commerce", "Make AI videos & ads"], ["media:read", "commerce", "Read AI videos & ads"],
  ["delivery:quote", "logistics", "Delivery quotes"], ["delivery:write", "logistics", "Request & cancel deliveries"], ["delivery:read", "logistics", "Track deliveries"],
  ["coverage:read", "logistics", "Coverage & rider availability"], ["webhooks:manage", "common", "Webhooks"], ["settlements:read", "common", "Settlement statement"]];
const EVENTS = ["product.created", "product.updated", "product.stock_updated", "product.unpublished", "delivery.created", "delivery.assigned", "delivery.picked_up", "delivery.in_transit", "delivery.completed", "delivery.failed", "delivery.cancelled", "media.video.ready", "media.video.failed"];
// Delivery is always SeloNachipa's configured tariff (Configuration → parcel fees; express premium = openExpressPremiumPct).
const PRICING: [string, string][] = [["subscriptionMonthlyNgwee", "Subscription / month (ngwee)"], ["apiCallNgwee", "Per API call (ngwee)"],
  ["mediaVideoNgwee", "Per finished AI video (ngwee)"], ["mediaAdNgwee", "Per AI ad (ngwee)"]];

export default function OpenNetwork() {
  const partners = useCollection("openPartners", [orderBy("name")], "all").rows;
  const usage = useCollection("openUsage", [where("day", ">=", new Date(Date.now() - 7 * 86_400_000).toISOString().slice(0, 10))], "7d").rows;
  const failing = useCollection("openEvents", [where("status", "in", ["RETRYING", "FAILED"])], "failing").rows;
  const [open, setOpen] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const calls = (pid: string) => usage.filter((u) => u.pid === pid).reduce((s, u) => s + (u.calls ?? 0), 0);
  return <Page title="Developer & Platform Integrations" sub="SeloNachipa Open Network · external platforms using the marketplace and rider network"
    right={<button className="btn" onClick={() => setCreating(true)}>Register platform</button>}>
    <div className="kpis">
      <Kpi label="Active platforms" value={partners.filter((p) => p.status === "ACTIVE").length} />
      <Kpi label="Awaiting approval" value={partners.filter((p) => p.status === "PENDING" || p.productionRequested).length} tone="wn" />
      <Kpi label="API calls (7 days)" value={usage.reduce((s, u) => s + (u.calls ?? 0), 0).toLocaleString()} />
      <Kpi label="Webhooks failing" value={failing.length} tone={failing.length ? "dn" : undefined} />
    </div>
    <Panel title="Platforms">
      <Table rows={partners} onRow={(r) => setOpen(r.id)} empty="No platforms yet." cols={[
        { h: "Platform", r: (r) => <b>{r.name}</b> }, { h: "Status", r: (r) => <StatePill s={r.productionRequested ? "PRODUCTION_REQUESTED" : r.status} /> },
        { h: "Services", r: (r) => [r.services?.commerce && "Commerce", r.services?.logistics && "Logistics"].filter(Boolean).join(" + ") || "—" },
        { h: "Environments", r: (r) => [r.environments?.sandbox && "sandbox", r.environments?.production && "production"].filter(Boolean).join(", ") },
        { h: "Calls 7d", r: (r) => calls(r.id).toLocaleString() }, { h: "Contact", r: (r) => r.contact?.email ?? "—" }]} />
    </Panel>
    {failing.length > 0 && <Panel title="Failing webhooks"><EventsTable rows={failing} /></Panel>}
    {(open || creating) && <PartnerDrawer id={open} onClose={() => { setOpen(null); setCreating(false); }} />}
  </Page>;
}

function EventsTable({ rows }: { rows: any[] }) {
  const { busy, run, Msg } = useAction();
  return <><Msg /><Table rows={rows} empty="No events." cols={[{ h: "When", r: (r) => when(r.createdAt) }, { h: "Event", r: (r) => r.type }, { h: "Subject", r: (r) => r.subject },
    { h: "Env", r: (r) => r.env }, { h: "Status", r: (r) => <StatePill s={r.status} /> }, { h: "Tries", r: (r) => r.attempts ?? 0 }, { h: "Last", r: (r) => r.lastError ?? r.lastStatus ?? "—" },
    { h: "", r: (r) => <button className="btn out" disabled={busy} onClick={() => run("admin_openReplay", { eventId: r.id }, "Re-sent.")}>Replay</button> }]} /></>;
}

function PartnerDrawer({ id, onClose }: { id: string | null; onClose: () => void }) {
  const { admin } = useSession();
  const p = useCollection("openPartners", [], "all").rows.find((x) => x.id === id) ?? null;
  const [tab, setTab] = useState("setup");
  const tabs = id ? ["setup", "credentials", "webhooks", "usage", "products", "deliveries", "settlement", "audit"] : ["setup"];
  return <Drawer title={p ? `${p.name} · ${p.status}` : "Register a platform"} onClose={onClose}>
    <div className="tabs" style={{ display: "flex", gap: 6, flexWrap: "wrap", marginBottom: 12 }}>
      {tabs.map((t) => <button key={t} className={`btn ${t === tab ? "" : "out"}`} onClick={() => setTab(t)}>{t[0].toUpperCase() + t.slice(1)}</button>)}</div>
    {admin?.role !== "super_admin" && <p className="err">Only a Super Admin can change integrations.</p>}
    {tab === "setup" && <Setup p={p} />}
    {id && tab === "credentials" && <Credentials pid={id} />}
    {id && tab === "webhooks" && <Webhooks pid={id} p={p} />}
    {id && tab === "usage" && <Usage pid={id} />}
    {id && tab === "products" && <Products pid={id} />}
    {id && tab === "deliveries" && <Deliveries pid={id} />}
    {id && tab === "settlement" && <Settlement pid={id} />}
    {id && tab === "audit" && <Audit pid={id} />}
  </Drawer>;
}

function Setup({ p }: { p: any }) {
  const { busy, run, Msg } = useAction();
  const [f, setF] = useState<any>(() => ({ name: "", website: "", notes: "", contact: {}, services: {}, environments: { sandbox: true }, scopes: [], rateLimitPerMin: 120, pricing: {} }));
  useEffect(() => { if (p) setF({ ...p, pricing: { ...(p.pricing ?? {}) }, contact: { ...(p.contact ?? {}) } }); }, [p?.id, p?.updatedAt]);
  const set = (k: string, v: any) => setF((x: any) => ({ ...x, [k]: v }));
  const toggleScope = (s: string) => set("scopes", f.scopes?.includes(s) ? f.scopes.filter((x: string) => x !== s) : [...(f.scopes ?? []), s]);
  return <>
    <Msg />
    {p && <p><StatePill s={p.status} /> {p.useCase ? <span style={{ color: "var(--mute)" }}>· {p.useCase}</span> : null}</p>}
    {p && <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 12 }}>
      {p.status !== "ACTIVE" && <button className="btn ok" disabled={busy} onClick={() => run("admin_openPartnerStatus", { id: p.id, status: "ACTIVE" }, "Approved (sandbox).")}>Approve</button>}
      {p.productionRequested && <button className="btn ok" disabled={busy} onClick={() => run("admin_openPartnerStatus", { id: p.id, status: "ACTIVE", production: true }, "Production enabled.")}>Approve production</button>}
      {p.status === "ACTIVE" && <button className="btn" disabled={busy} onClick={() => confirm(`Suspend ${p.name}? All its API calls stop immediately.`) && run("admin_openPartnerStatus", { id: p.id, status: "SUSPENDED", reason: "Suspended by admin" }, "Suspended — access blocked now.")}>Suspend</button>}
      {p.status === "PENDING" && <button className="btn out" disabled={busy} onClick={() => run("admin_openPartnerStatus", { id: p.id, status: "REJECTED" }, "Rejected.")}>Reject</button>}
    </div>}
    <div className="field"><label htmlFor="pn">Platform name</label><input id="pn" value={f.name ?? ""} onChange={(e) => set("name", e.target.value)} /></div>
    <div className="field"><label htmlFor="pw">Website</label><input id="pw" value={f.website ?? ""} onChange={(e) => set("website", e.target.value)} /></div>
    <div className="field"><label htmlFor="pc">Contact email</label><input id="pc" value={f.contact?.email ?? ""} onChange={(e) => set("contact", { ...f.contact, email: e.target.value })} /></div>
    <h4>Services</h4>
    {[["commerce", "Open Commerce Network"], ["logistics", "Open Logistics Network"]].map(([k2, l]) =>
      <label key={k2} style={{ display: "block" }}><input type="checkbox" checked={!!f.services?.[k2]} onChange={(e) => set("services", { ...f.services, [k2]: e.target.checked })} /> {l}</label>)}
    <h4>Environments</h4>
    {["sandbox", "production"].map((e) => <label key={e} style={{ display: "block" }}><input type="checkbox" checked={!!f.environments?.[e]} onChange={(ev) => set("environments", { ...f.environments, [e]: ev.target.checked })} /> {e}</label>)}
    <h4>Permissions (scopes)</h4>
    {SCOPES.map(([s, svc, label]) => <label key={s} style={{ display: "block", opacity: svc !== "common" && !f.services?.[svc] ? .45 : 1 }}>
      <input type="checkbox" checked={!!f.scopes?.includes(s)} onChange={() => toggleScope(s)} /> <code>{s}</code> — {label}{svc !== "common" && !f.services?.[svc] ? ` (needs ${svc})` : ""}</label>)}
    <div className="field"><label htmlFor="rl">Rate limit (requests / minute)</label><input id="rl" inputMode="numeric" value={f.rateLimitPerMin ?? 120} onChange={(e) => set("rateLimitPerMin", e.target.value)} /></div>
    <h4>Commercial terms</h4>
    <p style={{ color: "var(--mute)", marginTop: 0 }}>Products are bought on the platform's own checkout. Deliveries are charged at SeloNachipa's configured delivery fee, which the platform owes in full; SeloNachipa splits it to rider, platform and Social Fund as configured.</p>
    {PRICING.map(([k2, l]) => <div className="field" key={k2}><label htmlFor={k2}>{l}</label>
      <input id={k2} inputMode="decimal" value={f.pricing?.[k2] ?? ""} placeholder="standard" onChange={(e) => set("pricing", { ...f.pricing, [k2]: e.target.value })} /></div>)}
    <div className="field"><label htmlFor="sc">Settlement cycle</label><select id="sc" value={f.pricing?.settlementCycle ?? "monthly"} onChange={(e) => set("pricing", { ...f.pricing, settlementCycle: e.target.value })}>
      <option value="weekly">Weekly</option><option value="monthly">Monthly</option></select></div>
    <div className="field"><label htmlFor="nt">Internal notes</label><textarea id="nt" rows={2} value={f.notes ?? ""} onChange={(e) => set("notes", e.target.value)} /></div>
    <button className="btn" disabled={busy} onClick={() => run("admin_openPartnerSave", { ...f, id: p?.id }, p ? "Saved." : "Platform registered (pending approval).")}>{p ? "Save changes" : "Register platform"}</button>
  </>;
}

function Credentials({ pid }: { pid: string }) {
  const { busy, run, Msg } = useAction();
  const [clients, setClients] = useState<any[]>([]); const [shown, setShown] = useState<any>(null); const [n, setN] = useState(0);
  useEffect(() => { callAdmin("admin_openClients", { partnerId: pid }).then((r: any) => setClients(r.clients ?? [])).catch(() => setClients([])); }, [pid, n]);
  const act = async (data: any, okText: string) => { const r = await run("admin_openCredentials", { partnerId: pid, ...data }, okText); if (r?.clientSecret) setShown(r); setN((x) => x + 1); };
  return <>
    <Msg />
    {shown && <div className="banner ok"><b>Copy now — shown once.</b><br />Client ID: <code>{shown.clientId}</code><br />Secret: <code>{shown.clientSecret}</code><br /><small>{shown.note}</small></div>}
    <div style={{ display: "flex", gap: 8, margin: "8px 0" }}>
      <button className="btn" disabled={busy} onClick={() => act({ action: "create", env: "sandbox" }, "Sandbox credentials created.")}>New sandbox credentials</button>
      <button className="btn out" disabled={busy} onClick={() => act({ action: "create", env: "production" }, "Production credentials created.")}>New production credentials</button></div>
    <Table rows={clients.map((c) => ({ id: c.clientId, ...c }))} empty="No credentials yet." cols={[{ h: "Client ID", r: (r) => <code>{r.clientId}</code> }, { h: "Env", r: (r) => r.env },
      { h: "Status", r: (r) => <StatePill s={r.status} /> }, { h: "Last token", r: (r) => (r.lastTokenAt ? when(new Date(r.lastTokenAt)) : "never") },
      { h: "", r: (r) => r.status === "ACTIVE" ? <span style={{ display: "flex", gap: 6 }}>
        <button className="btn out" disabled={busy} onClick={() => confirm("Rotate? The current secret stops working now.") && act({ action: "rotate", clientId: r.clientId }, "Rotated.")}>Rotate</button>
        <button className="btn out" disabled={busy} onClick={() => confirm("Revoke? Access stops immediately.") && act({ action: "revoke", clientId: r.clientId }, "Revoked.")}>Revoke</button></span> : null }]} />
  </>;
}

function Webhooks({ pid, p }: { pid: string; p: any }) {
  const { busy, run, Msg } = useAction();
  const [url, setUrl] = useState(p?.webhook?.url ?? ""); const [ev, setEv] = useState<string[]>(p?.webhook?.events ?? []); const [secret, setSecret] = useState<string | null>(null);
  const events = useCollection("openEvents", [where("pid", "==", pid), orderBy("createdAt", "desc"), limit(50)], `ev-${pid}`).rows;
  const save = async (rotate = false) => { const r = await run("admin_openWebhook", { partnerId: pid, url, events: ev, rotateSecret: rotate }, "Webhook saved."); if (r?.signingSecret) setSecret(r.signingSecret); };
  return <>
    <Msg />{secret && <div className="banner ok"><b>Signing secret (shown once):</b> <code>{secret}</code></div>}
    <div className="field"><label htmlFor="wu">Webhook URL (https)</label><input id="wu" value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://partner.example/webhooks/selonachipa" /></div>
    <div style={{ columns: 2 }}>{EVENTS.map((e) => <label key={e} style={{ display: "block" }}><input type="checkbox" checked={ev.includes(e)} onChange={() => setEv(ev.includes(e) ? ev.filter((x) => x !== e) : [...ev, e])} /> {e}</label>)}</div>
    <div style={{ display: "flex", gap: 8, margin: "8px 0" }}><button className="btn" disabled={busy} onClick={() => save()}>Save</button>
      <button className="btn out" disabled={busy} onClick={() => confirm("Rotate the signing secret?") && save(true)}>Rotate signing secret</button></div>
    <h4>Event history</h4><EventsTable rows={events} />
  </>;
}

function Usage({ pid }: { pid: string }) {
  const rows = useCollection("openUsage", [where("pid", "==", pid), orderBy("day", "desc"), limit(60)], `u-${pid}`).rows;
  const audit = useCollection("openAudit", [where("pid", "==", pid), orderBy("at", "desc"), limit(100)], `a-${pid}`).rows;
  const errors = audit.filter((a) => a.status >= 400).length;
  return <>
    <div className="kpis"><Kpi label="Calls (60 days)" value={rows.reduce((s, r) => s + (r.calls ?? 0), 0).toLocaleString()} />
      <Kpi label="Errors in last 100" value={errors} tone={errors > 10 ? "dn" : undefined} />
      <Kpi label="Avg latency (last 100)" value={`${Math.round(audit.reduce((s, a) => s + (a.ms ?? 0), 0) / Math.max(1, audit.length))} ms`} /></div>
    <Table rows={rows} cols={[{ h: "Day", r: (r) => r.day }, { h: "Env", r: (r) => r.env }, { h: "Calls", r: (r) => r.calls }, { h: "Errors", r: (r) => r.errors ?? 0 },
      { h: "By group", r: (r) => Object.entries(r.groups ?? {}).map(([g, c]) => `${g} ${c}`).join(" · ") }]} />
    <h4>Product sync & API activity</h4>
    <Table rows={audit} cols={[{ h: "When", r: (r) => when(r.at) }, { h: "Call", r: (r) => `${r.method} ${r.path}` }, { h: "Status", r: (r) => r.status }, { h: "ms", r: (r) => r.ms }, { h: "Env", r: (r) => r.env }]} />
  </>;
}

function Products({ pid }: { pid: string }) {
  const rows = useCollection("listings", [where("origin.partnerId", "==", pid), limit(200)], `p-${pid}`).rows;
  return <Table rows={rows} empty="No products published yet." cols={[{ h: "Product", r: (r) => <b>{r.title}</b> }, { h: "External id", r: (r) => <code>{r.origin?.productId}</code> },
    { h: "Seller", r: (r) => r.sellerName }, { h: "Price", r: (r) => k(r.priceNgwee) }, { h: "Buy link", r: (r) => r.origin?.checkoutUrl ? <a href={r.origin.checkoutUrl} target="_blank" rel="noreferrer">open</a> : "—" },
    { h: "Referrals", r: (r) => r.stats?.checkoutReferrals ?? 0 }, { h: "Stock", r: (r) => r.stock }, { h: "Status", r: (r) => <StatePill s={r.status} /> },
    { h: "Shared with", r: (r) => (r.sharing?.partners ?? []).length }, { h: "Last sync", r: (r) => when(r.origin?.syncedAt) }]} />;
}

function Deliveries({ pid }: { pid: string }) {
  const rows = useCollection("parcels", [where("partnerId", "==", pid), orderBy("createdAt", "desc"), limit(100)], `d-${pid}`).rows;
  const { busy, run, Msg } = useAction(); const [rider, setRider] = useState("");
  return <><Msg />
    <div className="field"><label htmlFor="rid">Manual dispatch: rider uid</label><input id="rid" value={rider} onChange={(e) => setRider(e.target.value)} placeholder="Offer a waiting delivery to a specific online rider" /></div>
    <Table rows={rows} empty="No deliveries yet." cols={[{ h: "Delivery", r: (r) => r.id }, { h: "Their ref", r: (r) => r.source?.externalOrderRef }, { h: "Status", r: (r) => <StatePill s={r.status} /> },
      { h: "Rider", r: (r) => r.riderName ?? "—" }, { h: "Route", r: (r) => `${r.pickup?.neighbourhood ?? ""} → ${r.dropoff?.neighbourhood ?? ""}` }, { h: "Price", r: (r) => k(r.fees?.totalNgwee) },
      { h: "Env", r: (r) => r.env }, { h: "", r: (r) => r.status === "AWAITING_RIDER" && r.env !== "sandbox" ? <button className="btn out" disabled={busy || !rider}
        onClick={() => run("admin_openAssignRider", { deliveryId: r.id, riderUid: rider }, "Offered to the rider.")}>Offer to rider</button> : null }]} /></>;
}

function Settlement({ pid }: { pid: string }) {
  const rows = useCollection(`openLedger/${pid}/entries`, [orderBy("at", "desc"), limit(500)], `l-${pid}`).rows;
  const { busy, run, Msg } = useAction(); const [amt, setAmt] = useState(""); const [ref, setRef] = useState("");
  const by: Record<string, number> = {}; rows.filter((r) => r.env !== "sandbox").forEach((r) => { by[r.type] = (by[r.type] ?? 0) + (r.signedNgwee ?? 0); });
  const bal = Object.values(by).reduce((a, b) => a + b, 0);
  return <><Msg />
    <div className="kpis">{["DELIVERY_CHARGE", "DELIVERY_REVERSAL", "API_USAGE", "MEDIA_USAGE", "SUBSCRIPTION", "SETTLEMENT"].map((t) => <Kpi key={t} label={t.replace(/_/g, " ").toLowerCase()} value={k(by[t] ?? 0)} />)}
      <Kpi label={bal >= 0 ? "SeloNachipa owes partner" : "Partner owes SeloNachipa"} value={k(Math.abs(bal))} tone={bal < 0 ? "wn" : "up"} /></div>
    <h4>Record a settlement payment</h4>
    <div style={{ display: "flex", gap: 8 }}><input placeholder="Amount K received from the platform" value={amt} onChange={(e) => setAmt(e.target.value)} />
      <input placeholder="Bank / mobile money reference" value={ref} onChange={(e) => setRef(e.target.value)} />
      <button className="btn" disabled={busy || !amt || ref.length < 4} onClick={() => run("admin_openSettlement", { partnerId: pid, amountNgwee: Math.round(Number(amt) * 100), reference: ref }, "Recorded.")}>Record</button></div>
    <Table rows={rows} empty="No entries yet." cols={[{ h: "When", r: (r) => when(r.at) }, { h: "Type", r: (r) => r.type }, { h: "Amount", r: (r) => k(r.signedNgwee) }, { h: "Ref", r: (r) => `${r.refType} ${r.refId}` },
      { h: "Env", r: (r) => r.env }, { h: "Note", r: (r) => r.note ?? "" }]} /></>;
}

function Audit({ pid }: { pid: string }) {
  const admin = useCollection("auditLogs", [where("target", "==", `openPartners/${pid}`), orderBy("at", "desc"), limit(100)], `au-${pid}`).rows;
  const api = useCollection("openAudit", [where("pid", "==", pid), orderBy("at", "desc"), limit(200)], `aa-${pid}`).rows;
  return <><h4>Admin changes</h4><Table rows={admin} empty="None." cols={[{ h: "When", r: (r) => when(r.at) }, { h: "Who", r: (r) => r.actorEmail }, { h: "Action", r: (r) => r.action }]} />
    <h4>API requests</h4><Table rows={api} cols={[{ h: "When", r: (r) => when(r.at) }, { h: "Client", r: (r) => <code>{r.cid}</code> }, { h: "Call", r: (r) => `${r.method} ${r.path}` },
      { h: "Status", r: (r) => r.status }, { h: "IP", r: (r) => r.ip }, { h: "Idempotency key", r: (r) => r.idempotencyKey ?? "" }]} /></>;
}
