import { useState } from "react";
import { useCollection } from "../lib/data";
import { download, k, toCsv, toDate, when } from "../lib/format";
import { can, useSession } from "../lib/session";
import { Drawer, Kpi, Page, Panel, Pill, Table, useAction } from "../lib/ui";

/* Parcels v2 operations: live parcels, escrow ledger, delivery-code monitor, override log and support inbox.
   The delivery code itself is never shown anywhere — only its state, channel and attempt count. */

const STATUS_TONE: Record<string, "ok" | "warn" | "red" | "info" | "fund" | "grey"> = {
  PENDING_PAYMENT: "grey", AWAITING_RIDER: "warn", RIDER_ASSIGNED: "info", IN_TRANSIT: "fund", AWAITING_DELIVERY_CODE: "warn",
  DELIVERED: "ok", COMPLETED: "ok", CANCELLED: "grey", DELIVERY_FAILED: "red", DISPUTED: "red",
};
const S = ({ s }: { s?: string }) => <Pill tone={STATUS_TONE[s ?? ""] ?? "grey"}>{String(s ?? "—").replace(/_/g, " ").toLowerCase()}</Pill>;
const codeTone = (s?: string) => (({ sent: "warn", verified: "ok", overridden: "info", expired: "grey", locked: "red", superseded: "grey" } as const)[s as "sent"] ?? "grey");
export const CodeState = ({ c }: { c?: any }) => c ? <span><Pill tone={codeTone(c.state)}>{c.state}</Pill> <span className="mute">{c.channel ?? ""}{c.attempts ? ` · ${c.attempts} attempt${c.attempts === 1 ? "" : "s"}` : ""}</span></span> : <span className="mute">—</span>;

export function Parcels() {
  const parcels = useCollection("parcels").rows.sort((a, b) => (toDate(b.createdAt)?.getTime() ?? 0) - (toDate(a.createdAt)?.getTime() ?? 0));
  const [filter, setFilter] = useState("active");
  const [city, setCity] = useState("");
  const [open, setOpen] = useState<any>(null);
  const active = ["PENDING_PAYMENT", "AWAITING_RIDER", "RIDER_ASSIGNED", "IN_TRANSIT", "AWAITING_DELIVERY_CODE"];
  const shown = parcels.filter((p) => (filter === "active" ? active.includes(p.status) : filter === "all" ? true : p.status === filter) && (!city || p.cityId === city));
  const cities = [...new Set(parcels.map((p) => p.cityId).filter(Boolean))];
  const today = parcels.filter((p) => (toDate(p.createdAt)?.getTime() ?? 0) > Date.now() - 86_400_000);
  return <Page title="Parcels" sub="Same-city parcels up to 5 kg · delivery confirmed only by the receiver's 5-digit code">
    <div className="kpis">
      <Kpi label="Active now" value={parcels.filter((p) => active.includes(p.status)).length} note={`${parcels.filter((p) => p.status === "AWAITING_RIDER").length} waiting for a rider`} />
      <Kpi label="Rider at the door" value={parcels.filter((p) => p.status === "AWAITING_DELIVERY_CODE").length} note="waiting for the receiver's code" tone="wn" />
      <Kpi label="Delivered today" value={today.filter((p) => ["DELIVERED", "COMPLETED"].includes(p.status)).length} note={`${today.length} booked`} tone="up" />
      <Kpi label="Sending fees today" value={k(today.filter((p) => p.escrowStatus === "RELEASED").reduce((s, p) => s + (p.fees?.sendingNgwee ?? 0), 0))} note="platform revenue" />
    </div>
    <Panel title={`${shown.length} parcels`} right={<div className="toolbar">
      <select value={city} onChange={(e) => setCity(e.target.value)} aria-label="City"><option value="">All cities</option>{cities.map((c) => <option key={c}>{c}</option>)}</select>
      <div className="seg">{[["active", "Active"], ["AWAITING_DELIVERY_CODE", "At door"], ["DELIVERY_FAILED", "Failed"], ["DISPUTED", "Disputed"], ["all", "All"]].map(([v, l]) =>
        <button key={v} className={filter === v ? "on" : ""} onClick={() => setFilter(v)}>{l}</button>)}</div></div>}>
      <Table rows={shown} onRow={setOpen} empty="No parcels." cols={[
        { h: "Parcel", r: (p) => <span className="mono">{p.id}</span> }, { h: "Route", r: (p) => <>{p.pickup?.neighbourhood} → {p.dropoff?.neighbourhood}<div className="mute">{p.cityName}</div></> },
        { h: "Sender → receiver", r: (p) => <>{p.senderName}<div className="mute">→ {p.receiver?.name}</div></> }, { h: "Rider", r: (p) => p.riderName ?? <span className="mute">—</span> },
        { h: "Status", r: (p) => <S s={p.status} /> }, { h: "Code", r: (p) => <CodeState c={p.codeSummary} /> },
        { h: "Total", r: (p) => k(p.fees?.totalNgwee) }, { h: "Booked", r: (p) => when(p.createdAt) }]} />
    </Panel>
    {open && <ParcelDrawer p={parcels.find((x) => x.id === open.id) ?? open} onClose={() => setOpen(null)} />}
  </Page>;
}

function ParcelDrawer({ p, onClose }: { p: any; onClose: () => void }) {
  const { admin } = useSession();
  const ledger = useCollection("escrow_ledger").rows.filter((e) => e.orderId === p.id).sort((a, b) => (toDate(a.at)?.getTime() ?? 0) - (toDate(b.at)?.getTime() ?? 0));
  const attempts = useCollection("deliveryCodeAttempts").rows.filter((a) => a.orderId === p.id);
  const { busy, run, Msg } = useAction();
  const [reason, setReason] = useState(""); const [evidence, setEvidence] = useState(""); const [refund, setRefund] = useState(""); const [phone, setPhone] = useState("");
  const f = p.fees ?? {};
  return <Drawer title={`Parcel ${p.id}`} onClose={onClose}>
    <dl className="kv">
      <dt>Status</dt><dd><S s={p.status} /></dd>
      <dt>Pickup</dt><dd>{p.pickup?.address}<div className="mute">{p.pickup?.neighbourhood}, {p.cityName}</div></dd>
      <dt>Delivery</dt><dd>{p.dropoff?.address}<div className="mute">{p.dropoff?.neighbourhood}</div></dd>
      <dt>Sender</dt><dd>{p.senderName} · {p.senderMsisdn}</dd>
      <dt>Receiver</dt><dd>{p.receiver?.name} · {p.receiver?.msisdn}{p.receiver?.uid ? " (has app)" : " (SMS)"}</dd>
      <dt>Parcel</dt><dd>{p.weightBand} · {p.category}<div className="mute">{p.description}</div></dd>
      <dt>Rider</dt><dd>{p.riderName ?? "—"} {p.riderPlate ? `· ${p.riderPlate}` : ""}</dd>
      <dt>Distance</dt><dd>{p.distanceKm} km{p.distanceApproximate ? " (approx.)" : ""}</dd>
      <dt>Delivery fee</dt><dd>{k(f.deliveryNgwee)} <span className="mute">(rider {k(f.riderEarningsNgwee)} · platform {k(f.platformFeeNgwee)} · fund {k(f.socialFundNgwee)})</span></dd>
      <dt>Sending fee</dt><dd>{k(f.sendingNgwee)}</dd>{f.insuranceNgwee ? <><dt>Insurance</dt><dd>{k(f.insuranceNgwee)}</dd></> : null}
      <dt>Escrow</dt><dd>{p.escrowStatus ?? "—"}</dd>
      <dt>Delivery code</dt><dd><CodeState c={p.codeSummary} /><div className="mute">The code itself is never stored or shown — only its hash exists.</div></dd>
      {p.verification && <><dt>Verified</dt><dd>{p.verification.override ? "Admin override" : when(p.verification.verifiedAt)} {p.verification.riderGeo ? `· rider at ${p.verification.riderGeo.lat.toFixed(5)}, ${p.verification.riderGeo.lng.toFixed(5)}` : ""}</dd></>}
      {p.unavailable?.reportedAt && <><dt>Receiver unavailable</dt><dd>Reported {when(p.unavailable.reportedAt)} · sender response: {p.unavailable.response ?? "none yet"}</dd></>}
    </dl>
    {attempts.length > 0 && <Panel title={`Code attempts (${attempts.length})`}>
      <Table rows={attempts} cols={[{ h: "When", r: (a) => when(a.at) }, { h: "Action", r: (a) => a.action }, { h: "Result", r: (a) => <Pill tone={a.result === "verified" ? "ok" : a.result === "issued" ? "info" : a.result === "wrong" ? "warn" : "red"}>{a.result}</Pill> },
        { h: "GPS", r: (a) => a.lat ? `${Number(a.lat).toFixed(4)}, ${Number(a.lng).toFixed(4)}` : "—" }]} /></Panel>}
    <Panel title="Escrow ledger">
      <Table rows={ledger} empty="No ledger entries yet." cols={[{ h: "When", r: (e) => when(e.at) }, { h: "Type", r: (e) => <Pill tone={e.type === "RELEASED" ? "ok" : e.type === "REFUNDED" ? "red" : "info"}>{e.type}</Pill> },
        { h: "Leg", r: (e) => e.leg }, { h: "Amount", r: (e) => k(e.amountNgwee) }, { h: "Override", r: (e) => e.override ? <Pill tone="red">override</Pill> : "" }]} />
    </Panel>
    <Msg />
    {can(admin, "operations", "support") && ["IN_TRANSIT", "AWAITING_DELIVERY_CODE", "DELIVERY_FAILED", "DISPUTED"].includes(p.status) && p.escrowStatus !== "RELEASED" && <div className="endpromo">
      <b>Force-complete delivery</b>
      <div className="field"><label htmlFor="r">Reason (min. 10 characters, kept in the audit log)</label><textarea id="r" rows={2} value={reason} onChange={(e) => setReason(e.target.value)} /></div>
      <div className="field"><label htmlFor="ev">Evidence (call log, photo or chat reference)</label><input id="ev" value={evidence} onChange={(e) => setEvidence(e.target.value)} /></div>
      <button className="btn" disabled={busy || reason.trim().length < 10 || !evidence.trim()} onClick={() => run("admin_overrideDelivery", { kind: "parcel", orderId: p.id, reason, evidence: [evidence] },
        "Delivery force-completed. Escrow released and flagged as an override.")}>Force-complete and release escrow</button>
    </div>}
    {can(admin, "finance", "support") && !["RELEASED", "REFUNDED"].includes(p.escrowStatus) && p.status !== "PENDING_PAYMENT" && <div className="endpromo">
      <b>Refund the sender</b>
      <div className="toolbar"><input placeholder="Amount in K (blank = full)" value={refund} onChange={(e) => setRefund(e.target.value)} />
        <button className="btn out" disabled={busy || !reason.trim()} onClick={() => run("admin_refundParcel", { parcelId: p.id, reason, amountNgwee: refund ? Math.round(Number(refund) * 100) : undefined },
          "Refund sent to the sender's wallet.")}>Refund</button></div>
      <div className="mute">Uses the reason above.</div>
    </div>}
    {can(admin, "operations", "support") && <div className="endpromo">
      <b>Change receiver's number</b>
      <div className="toolbar"><input placeholder="097… / 096… / 095…" value={phone} onChange={(e) => setPhone(e.target.value)} />
        <button className="btn out" disabled={busy || !phone || !reason.trim()} onClick={() => run("admin_editReceiver", { parcelId: p.id, msisdn: phone, reason }, "Receiver updated and audited.")}>Update</button></div>
    </div>}
  </Drawer>;
}

export function EscrowLedger() {
  const rows = useCollection("escrow_ledger").rows.sort((a, b) => (toDate(b.at)?.getTime() ?? 0) - (toDate(a.at)?.getTime() ?? 0));
  const [type, setType] = useState(""); const [kind, setKind] = useState(""); const [q, setQ] = useState("");
  const shown = rows.filter((r) => (!type || r.type === type) && (!kind || r.kind === kind) && (!q || String(r.orderId).toLowerCase().includes(q.toLowerCase())));
  const sum = (t: string) => rows.filter((r) => r.type === t).reduce((s, r) => s + (r.amountNgwee ?? 0), 0);
  const released = sum("RELEASED"), refunded = sum("REFUNDED"), held = sum("HELD");
  return <Page title="Escrow ledger" sub="Append-only: every amount held, released per leg on a verified code, or refunded"
    right={<button className="btn out" onClick={() => download("escrow-ledger.csv", toCsv(shown.map((r) => ({ at: toDate(r.at)?.toISOString() ?? "", kind: r.kind, order: r.orderId, type: r.type,
      leg: r.leg, party: r.party, amount_zmw: ((r.amountNgwee ?? 0) / 100).toFixed(2), override: r.override ? "yes" : "", by: r.by ?? "" }))))}>Export CSV</button>}>
    <div className="kpis">
      <Kpi label="Held (in)" value={k(held)} /><Kpi label="Released" value={k(released)} tone="up" /><Kpi label="Refunded" value={k(refunded)} />
      <Kpi label="Still in escrow" value={k(Math.max(0, held - released - refunded))} note="held minus released and refunded" tone="wn" />
    </div>
    <Panel right={<div className="toolbar"><input placeholder="Order or parcel id" value={q} onChange={(e) => setQ(e.target.value)} />
      <select value={type} onChange={(e) => setType(e.target.value)} aria-label="Type"><option value="">All types</option><option>HELD</option><option>RELEASED</option><option>REFUNDED</option></select>
      <select value={kind} onChange={(e) => setKind(e.target.value)} aria-label="Kind"><option value="">Orders and parcels</option><option value="order">Orders</option><option value="parcel">Parcels</option></select></div>}>
      <Table rows={shown.slice(0, 300)} empty="No entries." cols={[{ h: "When", r: (r) => when(r.at) }, { h: "Kind", r: (r) => r.kind }, { h: "Ref", r: (r) => <span className="mono">{r.orderId}</span> },
        { h: "Type", r: (r) => <Pill tone={r.type === "RELEASED" ? "ok" : r.type === "REFUNDED" ? "red" : "info"}>{r.type}</Pill> }, { h: "Leg", r: (r) => r.leg }, { h: "Party", r: (r) => r.party },
        { h: "Amount", r: (r) => k(r.amountNgwee) }, { h: "", r: (r) => r.override ? <Pill tone="red">override</Pill> : "" }]} />
    </Panel>
  </Page>;
}

export function DeliveryCodes() {
  const parcels = useCollection("parcels").rows, orders = useCollection("orders").rows;
  const flags = useCollection("riderFlags").rows.filter((f) => f.status === "OPEN");
  const atDoor: any[] = [...parcels.filter((p) => p.status === "AWAITING_DELIVERY_CODE").map((p) => ({ ...p, kind: "parcel" })),
    ...orders.filter((o) => o.state === "AWAITING_DELIVERY_CODE").map((o) => ({ ...o, kind: "order", status: o.state }))];
  const locked = atDoor.filter((x) => x.codeSummary?.state === "locked");
  const failed: any[] = [...parcels.filter((p) => p.status === "DELIVERY_FAILED").map((p) => ({ ...p, kind: "parcel" })), ...orders.filter((o) => o.state === "DELIVERY_FAILED").map((o) => ({ ...o, kind: "order", status: o.state }))];
  const waited = (x: any) => { const t = toDate(x.arrivedAt)?.getTime(); return t ? Math.round((Date.now() - t) / 60_000) : null; };
  return <Page title="Delivery code monitor" sub="Riders at the door, locked codes, failed deliveries and riders with repeated lockouts">
    <div className="kpis"><Kpi label="Waiting for a code" value={atDoor.length} tone="wn" /><Kpi label="Locked codes" value={locked.length} tone={locked.length ? "dn" : undefined} />
      <Kpi label="Failed deliveries" value={failed.length} /><Kpi label="Riders flagged" value={flags.length} note="3+ lockouts in 24 h or low rating" /></div>
    <Panel title="At the door now"><Table rows={atDoor} empty="Nobody waiting." cols={[{ h: "Ref", r: (x) => <span className="mono">{x.id}</span> }, { h: "Kind", r: (x) => x.kind },
      { h: "Rider", r: (x) => x.riderName ?? "—" }, { h: "Code", r: (x) => <CodeState c={x.codeSummary} /> }, { h: "Waiting", r: (x) => waited(x) != null ? `${waited(x)} min` : "—" },
      { h: "Receiver", r: (x) => x.unavailable?.reportedAt ? <Pill tone="red">unavailable · {x.unavailable.response ?? "no reply"}</Pill> : <span className="mute">—</span> }]} /></Panel>
    <Panel title="Failed deliveries"><Table rows={failed} empty="None." cols={[{ h: "Ref", r: (x) => <span className="mono">{x.id}</span> }, { h: "Kind", r: (x) => x.kind },
      { h: "Reason", r: (x) => x.failureReason ?? "—" }, { h: "Return", r: (x) => x.returnStatus ?? "—" }, { h: "Escrow", r: (x) => x.escrowStatus ?? "—" }, { h: "When", r: (x) => when(x.failedAt) }]} /></Panel>
    <Panel title="Riders for review"><Table rows={flags} empty="No riders flagged." cols={[{ h: "Rider", r: (f) => <span className="mono">{f.riderUid}</span> }, { h: "Why", r: (f) => f.reason.replace(/_/g, " ") },
      { h: "Detail", r: (f) => f.count24h ? `${f.count24h} lockouts in 24 h` : f.rating ? `rating ${Number(f.rating).toFixed(2)}` : "—" }, { h: "Since", r: (f) => when(f.flaggedAt) }]} /></Panel>
  </Page>;
}

export function OverrideLog() {
  const rows = useCollection("admin_audit_log").rows.sort((a, b) => (toDate(b.at)?.getTime() ?? 0) - (toDate(a.at)?.getTime() ?? 0));
  return <Page title="Override log" sub="Every admin force-completion, receiver change and parcel refund, with reason and actor">
    <Panel><Table rows={rows} empty="No overrides yet." cols={[{ h: "When", r: (r) => when(r.at) }, { h: "Action", r: (r) => <Pill tone={r.action === "delivery.override" ? "red" : "info"}>{r.action}</Pill> },
      { h: "Ref", r: (r) => <span className="mono">{r.orderId}</span> }, { h: "By", r: (r) => r.actorEmail }, { h: "Reason", r: (r) => r.reason },
      { h: "Evidence", r: (r) => (r.evidence ?? []).join(", ") || "—" }, { h: "Amount", r: (r) => r.riderEarningsNgwee != null ? `rider ${k(r.riderEarningsNgwee)}` : r.amountNgwee != null ? k(r.amountNgwee) : "—" }]} /></Panel>
  </Page>;
}

export function Support() {
  const tickets = useCollection("supportTickets").rows.sort((a, b) => (toDate(b.updatedAt)?.getTime() ?? 0) - (toDate(a.updatedAt)?.getTime() ?? 0));
  const [filter, setFilter] = useState("OPEN");
  const [open, setOpen] = useState<any>(null);
  const shown = tickets.filter((t) => filter === "all" || t.status === filter);
  return <Page title="Support inbox" sub="Requests from the app's Help & support screen · reply in the thread">
    <Panel right={<div className="seg">{[["OPEN", "Open"], ["AWAITING_USER", "Waiting on user"], ["RESOLVED", "Resolved"], ["all", "All"]].map(([v, l]) =>
      <button key={v} className={filter === v ? "on" : ""} onClick={() => setFilter(v)}>{l} ({v === "all" ? tickets.length : tickets.filter((t) => t.status === v).length})</button>)}</div>}>
      <Table rows={shown} onRow={setOpen} empty="Inbox zero." cols={[{ h: "Ref", r: (t) => <span className="mono">{t.reference}</span> }, { h: "From", r: (t) => <>{t.name}<div className="mute">{t.role} · {t.msisdn}</div></> },
        { h: "Subject", r: (t) => <>{t.subject}<div className="mute">{t.category}{t.orderRef ? ` · ${t.orderRef}` : ""}</div></> }, { h: "Last", r: (t) => <span className="mute">{t.lastFrom === "support" ? "You: " : ""}{t.lastMessage}</span> },
        { h: "Updated", r: (t) => when(t.updatedAt) }]} />
    </Panel>
    {open && <TicketDrawer t={tickets.find((x) => x.id === open.id) ?? open} onClose={() => setOpen(null)} />}
  </Page>;
}

function TicketDrawer({ t, onClose }: { t: any; onClose: () => void }) {
  const msgs = useCollection(`supportTickets/${t.id}/messages`).rows.sort((a, b) => (toDate(a.at)?.getTime() ?? 0) - (toDate(b.at)?.getTime() ?? 0));
  const { busy, run, Msg } = useAction();
  const [text, setText] = useState("");
  return <Drawer title={`${t.reference} · ${t.subject}`} onClose={onClose}>
    <div className="thread">{msgs.map((m) => <div key={m.id} className={`bubble ${m.from === "support" ? "me" : ""}`}><div>{m.text}</div><small>{m.from === "support" ? m.agent ?? "Support" : t.name} · {when(m.at)}</small></div>)}</div>
    <div className="field"><label htmlFor="reply">Reply</label><textarea id="reply" rows={3} value={text} onChange={(e) => setText(e.target.value)} /></div>
    <Msg />
    <div className="toolbar">
      <button className="btn" disabled={busy || !text.trim()} onClick={async () => { if (await run("admin_replyTicket", { ticketId: t.id, text }, "Reply sent.")) setText(""); }}>Send reply</button>
      <button className="btn ok" disabled={busy || !text.trim()} onClick={async () => { if (await run("admin_replyTicket", { ticketId: t.id, text, close: true }, "Replied and resolved.")) setText(""); }}>Reply & resolve</button>
    </div>
  </Drawer>;
}
