import { useState } from "react";
import { where, orderBy, limit } from "firebase/firestore";
import { useCollection, useDoc } from "../lib/data";
import { when } from "../lib/format";
import { can, useSession } from "../lib/session";
import { Page, Panel, Table, useAction } from "../lib/ui";

type Def = [key: string, label: string, unit: string, hint: string];
const GROUPS: Record<string, Def[]> = {
  "Fees & payouts": [
    ["flatPlatformFeeKwacha", "Flat platform fee", "ZMW", "per order, from the seller leg at pickup"],
    ["buyerServiceFeePct", "Buyer service fee", "%", "of goods value, added at checkout"],
    ["riderPlatformFeePct", "Rider platform fee", "%", "of delivery fee"],
    ["socialFundPct", "Social Fund per delivery", "%", "of delivery fee, ring-fenced"],
    ["socialFundMilestoneDeliveries", "Social Fund auto-release", "#", "every N deliveries · 0 = ring-fenced for claims only"],
    ["heroBonusPct", "Hero rider bonus", "%", "of net delivery earnings, Hero and Ambassador tiers"],
    ["riderWithdrawalFee", "Withdrawal fee", "ZMW", "per withdrawal"], ["minWithdrawal", "Minimum payout", "ZMW", "net of fee"],
    ["agentPlatformCutPct", "Agent platform cut", "%", "of agent commission"], ["sellerRegistrationFee", "Registration fee", "ZMW", "sellers and agents, one-time"],
  ],
  "Delivery & parcels": [
    ["deliveryFeePerKm", "Delivery fee per km", "ZMW", ""], ["deliveryBaseFee", "Delivery base fee", "ZMW", ""],
    ["parcelListingFee", "Parcel listing fee", "ZMW", ""], ["paymentProcessingPct", "Payment processing", "%", "surcharge"],
    ["insurancePremiumPct", "Parcel insurance premium", "%", "of declared value"], ["insuranceMaxDeclaredValue", "Max declared value", "ZMW", ""],
  ],
  "Parcels v2": [
    ["parcelBaseFee", "Parcel base fee", "ZMW", "delivery fee = base + per km, also the minimum"], ["parcelPerKmRate", "Parcel per km", "ZMW", ""],
    ["sendingFeeRate", "Sending fee", "%", "of delivery fee, capped at 50%"], ["minSendingFee", "Minimum sending fee", "ZMW", ""],
    ["parcelPlatformFeePct", "Parcel platform share", "%", "of delivery fee; rider gets the rest minus fund"], ["parcelSocialFundPct", "Parcel Social Fund", "%", "of delivery fee"],
    ["deliveryGeofenceM", "Delivery geofence", "m", "rider must be this close to request a code"], ["deliveryCodeExpiryMin", "Delivery code expiry", "min", ""],
    ["deliveryCodeMaxAttempts", "Code attempts", "#", "then the code locks"], ["disputeWindowHours", "Dispute window", "h", "DELIVERED → COMPLETED after this"],
  ],
  Dispatch: [
    ["broadcastTimeoutMin", "Broadcast timeout", "min", "then full refund"], ["dispatchCountdownSec", "Job card countdown", "s", ""],
    ["maxConcurrentDeliveries", "Max concurrent deliveries", "#", "per rider"], ["autoConfirmHours", "Auto-confirm delivery", "h", "after rider marks delivered"],
  ],
  Security: [
    ["pinMaxAttempts", "PIN attempts", "#", "before lock"], ["pinLockMinutes", "PIN lock", "min", ""],
    ["sqMaxAttempts", "Security-question attempts", "#", "in 30 min"], ["sqLockMinutes", "Security-question lock", "min", ""],
    ["shareLinkExpiryDays", "Share link expiry", "days", ""], ["minAppVersion", "Minimum app version", "#", "older builds must update"],
  ],
  Promotions: [
    ["boostLocalPerDay", "Boost · Local", "ZMW", "per day, seller's district and nearby"], ["boostRegionalPerDay", "Boost · Regional", "ZMW", "per day, chosen provinces"],
    ["boostNationalPerDay", "Boost · National", "ZMW", "per day, all of Zambia"], ["storyPrice24h", "Feed Story", "ZMW", "per 24 hours"],
    ["boostFrequencyCap", "Boost frequency cap", "#", "times per buyer per day"], ["boostSlotsPerPage", "Boost slots per feed page", "#", "at positions 2 and 6"],
    ["unpaidOrderCancelMin", "Unpaid order expiry", "min", "buyer can retry with another number until then"],
  ]
};

export default function Config() {
  const { admin } = useSession();
  const cfg = useDoc("platformConfig/default");
  const history = useCollection("auditLogs", [where("action", "==", "config.update"), orderBy("at", "desc"), limit(30)], "cfg").rows.filter((r) => r.action === "config.update");
  const [tab, setTab] = useState("Fees & payouts");
  const [edits, setEdits] = useState<Record<string, string>>({});
  const { busy, run, Msg } = useAction();
  const editable = can(admin, "finance", "operations");
  const dirty = Object.keys(edits).filter((key) => edits[key] !== String(cfg?.[key] ?? ""));
  const save = async () => {
    const changes = Object.fromEntries(dirty.map((key) => [key, Number(edits[key])]));
    if (await run("admin_updateConfig", { changes, expectedVersion: cfg?.version }, `Saved as version ${(cfg?.version ?? 0) + 1}. Apps pick it up within seconds; orders already in flight keep their fee snapshot.`)) setEdits({});
  };
  return <Page title={`Platform configuration · ${tab}`} sub={`platformConfig/default · version ${cfg?.version ?? "—"} · last change ${when(cfg?.updatedAt)} by ${cfg?.updatedBy ?? "—"}`}
    right={<div className="seg">{[...Object.keys(GROUPS), "History"].map((g) => <button key={g} className={tab === g ? "on" : ""} onClick={() => setTab(g)}>{g}</button>)}</div>}>
    {dirty.length > 0 && <div className="banner">You are editing <b>{dirty.length} setting{dirty.length > 1 ? "s" : ""}</b>. Saving pushes to all apps through the <span className="mono">config-updates</span> topic and writes an audit entry.
      <button className="btn" style={{ marginLeft: "auto" }} disabled={busy || dirty.some((k) => Number.isNaN(Number(edits[k])))} onClick={save}>Save & publish</button>
      <button className="btn ghost" onClick={() => setEdits({})}>Discard</button></div>}
    <Msg />
    {tab === "History" ? <Panel><Table rows={history} cols={[
      { h: "When", r: (h) => when(h.at) }, { h: "Changed", r: (h) => <span className="mono">{Object.keys(h.after ?? {}).join(", ")}</span> },
      { h: "Before", r: (h) => <span className="mono">{JSON.stringify(h.before)}</span> }, { h: "After", r: (h) => <span className="mono">{JSON.stringify(h.after)}</span> },
      { h: "By", r: (h) => `${h.actorEmail} · ${h.actorRole}` }, { h: "IP", r: (h) => <span className="mono">{h.ip}</span> }]} /></Panel>
      : <div className="cfg">{GROUPS[tab].map(([key, label, unit, hint]) => {
        const val = edits[key] ?? String(cfg?.[key] ?? "");
        return <div key={key} className={`cf ${key in edits && edits[key] !== String(cfg?.[key]) ? "edit" : ""}`}>
          <span className="lbl">{label}</span><span className="key">{key}</span>
          <div className="inp"><span>{unit}</span><input aria-label={label} value={val} disabled={!editable} inputMode="decimal" onChange={(e) => setEdits({ ...edits, [key]: e.target.value })} /></div>
          <span className="meta">{key in edits && edits[key] !== String(cfg?.[key]) ? `Was ${cfg?.[key]} · applies to new orders only` : hint}</span>
        </div>;
      })}</div>}
    <Panel title="How a change reaches phones">
      <div className="flow"><span>Admin saves</span>→<span><b>admin_updateConfig</b> checks role + version</span>→<span>platformConfig v{(cfg?.version ?? 0) + 1} + audit entry</span>→<span>App listeners refresh fees</span>→<span>FCM wakes background devices</span></div>
    </Panel>
  </Page>;
}
