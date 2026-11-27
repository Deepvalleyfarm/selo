import { useEffect, useState } from "react";
import { orderBy } from "firebase/firestore";
import { useCollection, useDoc } from "../lib/data";
import { when } from "../lib/format";
import { can, useSession } from "../lib/session";
import { Page, Panel, StatePill, Table, useAction } from "../lib/ui";

const FIELDS: [key: string, label: string, hint: string][] = [
  ["insurerName", "Insurer (registered name)", "As licensed by PIA Zambia"], ["policyNumber", "Group policy number", "From the signed policy schedule"],
  ["claimsPhone", "Claims phone", "+260…"], ["claimsEmail", "Claims email", "claims@…"], ["termsUrl", "Policy terms link (https)", "Riders must read this before opting in"],
];

/**
 * Rider accident cover. OFF until an insurer is confirmed: finance enters the insurer and policy, then switches it on.
 * Riders can only opt in while it's on, and premiums are only deducted while it's on.
 */
export default function RiderCover() {
  const { admin } = useSession();
  const cur = useDoc("riderCover/active");
  const premiums = useCollection("riderCoverPremiums", [orderBy("at", "desc")], "recent").rows.slice(0, 50);
  const [f, setF] = useState<Record<string, string>>({});
  useEffect(() => { if (cur) setF({ ...Object.fromEntries(FIELDS.map(([k]) => [k, String(cur[k] ?? "")])), benefits: String(cur.benefits ?? ""),
    premiumK: cur.premiumNgwee ? String(cur.premiumNgwee / 100) : "", coverK: cur.coverNgwee ? String(cur.coverNgwee / 100) : "" }); }, [cur?.updatedAt]);
  const { busy, run, Msg } = useAction();
  const enabled = cur?.enabled === true;
  const payload = (on: boolean) => ({ enabled: on, ...Object.fromEntries(FIELDS.map(([k]) => [k, f[k] ?? ""])), benefits: f.benefits ?? "",
    premiumNgwee: Math.round(Number(f.premiumK || 0) * 100), coverNgwee: Math.round(Number(f.coverK || 0) * 100) });
  const unheld = premiums.filter((p) => !p.remitted).reduce((s, p) => s + (p.amountNgwee ?? 0), 0);
  const editable = can(admin, "finance");
  return <Page title="Rider accident cover" sub="Off until an insurer is confirmed · riders can only opt in while it's on">
    <Msg />
    <Panel title="Status">
      <p><StatePill s={enabled ? "ACTIVE" : "OFF"} /> {enabled ? `Live with ${cur?.insurerName} · policy ${cur?.policyNumber} · since ${when(cur?.activatedAt)} by ${cur?.activatedBy}`
        : "Riders see \"Not available yet\". No premiums are deducted."}</p>
      {cur?.deactivatedAt && !enabled && <p style={{ color: "var(--mute)" }}>Last switched off {when(cur.deactivatedAt)} by {cur.deactivatedBy}.</p>}
    </Panel>
    <Panel title="Insurer setup">
      {FIELDS.map(([k, label, hint]) => <div className="field" key={k}><label htmlFor={k}>{label}</label>
        <input id={k} value={f[k] ?? ""} placeholder={hint} disabled={!editable} onChange={(e) => setF({ ...f, [k]: e.target.value })} /></div>)}
      <div className="field"><label htmlFor="premiumK">Premium per completed delivery (K)</label>
        <input id="premiumK" inputMode="decimal" value={f.premiumK ?? ""} disabled={!editable} onChange={(e) => setF({ ...f, premiumK: e.target.value })} /></div>
      <div className="field"><label htmlFor="coverK">Cover amount (K)</label>
        <input id="coverK" inputMode="decimal" value={f.coverK ?? ""} disabled={!editable} onChange={(e) => setF({ ...f, coverK: e.target.value })} /></div>
      <div className="field"><label htmlFor="benefits">What it pays for (shown to riders)</label>
        <textarea id="benefits" rows={3} value={f.benefits ?? ""} disabled={!editable} onChange={(e) => setF({ ...f, benefits: e.target.value })}
          placeholder="Accident medical costs up to K 10,000; temporary disability K 150/day up to 30 days…" /></div>
      {editable ? <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        <button className="btn out" disabled={busy} onClick={() => run("admin_setRiderCover", payload(enabled), "Insurer details saved.")}>Save details</button>
        {enabled
          ? <button className="btn" disabled={busy} onClick={() => confirm("Switch rider cover OFF? Premium deductions stop immediately.") && run("admin_setRiderCover", payload(false), "Rider cover switched off.")}>Switch cover off</button>
          : <button className="btn ok" disabled={busy} onClick={() => confirm(`Switch rider cover ON with ${f.insurerName || "this insurer"}? Riders will be able to opt in.`) && run("admin_setRiderCover", payload(true), "Rider cover is live. Riders can now opt in.")}>Switch cover on</button>}
      </div> : <p className="err">Only Finance can configure rider cover.</p>}
    </Panel>
    <Panel title={`Premiums held for the insurer · not yet remitted K ${(unheld / 100).toFixed(2)}`}>
      <Table rows={premiums} empty="No premiums yet." cols={[{ h: "When", r: (r) => when(r.at) }, { h: "Rider", r: (r) => r.riderUid }, { h: "Order", r: (r) => r.orderId },
        { h: "Amount", r: (r) => `K ${(r.amountNgwee / 100).toFixed(2)}` }, { h: "Insurer", r: (r) => r.insurer }, { h: "Remitted", r: (r) => (r.remitted ? "Yes" : "No") }]} />
    </Panel>
  </Page>;
}
