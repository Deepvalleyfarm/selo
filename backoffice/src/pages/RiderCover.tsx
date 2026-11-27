import { useEffect, useState } from "react";
import { limit, orderBy, where } from "firebase/firestore";
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
  const unpaid = useCollection("riderCoverPremiums", [where("remitted", "==", false), orderBy("at", "asc"), limit(400)], "unpaid").rows.filter((r) => !r.remitted);
  const remittances = useCollection("coverRemittances", [orderBy("at", "desc"), limit(50)], "recent").rows;
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [payRef, setPayRef] = useState(""); const [paidK, setPaidK] = useState(""); const [note, setNote] = useState("");
  const [f, setF] = useState<Record<string, string>>({});
  useEffect(() => { if (cur) setF({ ...Object.fromEntries(FIELDS.map(([k]) => [k, String(cur[k] ?? "")])), benefits: String(cur.benefits ?? ""),
    premiumK: cur.premiumNgwee ? String(cur.premiumNgwee / 100) : "", coverK: cur.coverNgwee ? String(cur.coverNgwee / 100) : "" }); }, [cur?.updatedAt]);
  const { busy, run, Msg } = useAction();
  const enabled = cur?.enabled === true;
  const payload = (on: boolean) => ({ enabled: on, ...Object.fromEntries(FIELDS.map(([k]) => [k, f[k] ?? ""])), benefits: f.benefits ?? "",
    premiumNgwee: Math.round(Number(f.premiumK || 0) * 100), coverNgwee: Math.round(Number(f.coverK || 0) * 100) });
  const unheld = unpaid.reduce((s, p) => s + (p.amountNgwee ?? 0), 0);
  const sel = unpaid.filter((p) => picked.has(p.id));
  const selTotal = sel.reduce((s, p) => s + (p.amountNgwee ?? 0), 0);
  const insurers = new Set(sel.map((p) => p.insurer ?? ""));
  const toggle = (id: string) => setPicked((x) => { const n = new Set(x); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const K = (n: number) => `K ${(n / 100).toFixed(2)}`;
  const markPaid = async () => {
    const amountNgwee = Math.round(Number(paidK || 0) * 100);
    if (!confirm(`Mark ${sel.length} premiums (${K(selTotal)}) as paid to ${[...insurers][0]} with reference ${payRef}?`)) return;
    const r = await run("admin_markPremiumsRemitted", { ids: sel.map((p) => p.id), amountNgwee, reference: payRef, note }, `Marked ${sel.length} premiums as paid (${K(selTotal)}).`);
    if (r) { setPicked(new Set()); setPayRef(""); setPaidK(""); setNote(""); }
  };
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
    <Panel title={`Unpaid premiums held for the insurer · ${unpaid.length} · ${K(unheld)}`}>
      {editable && unpaid.length > 0 && <p><button className="btn out" onClick={() => setPicked(picked.size === unpaid.length ? new Set() : new Set(unpaid.map((p) => p.id)))}>
        {picked.size === unpaid.length ? "Clear selection" : `Select all ${unpaid.length}`}</button></p>}
      <Table rows={unpaid} empty="Nothing waiting — every premium has been paid over." cols={[
        ...(editable ? [{ h: "", w: 36, r: (r: any) => <input type="checkbox" aria-label="Select premium" checked={picked.has(r.id)} onChange={() => toggle(r.id)} /> }] : []),
        { h: "When", r: (r) => when(r.at) }, { h: "Rider", r: (r) => r.riderUid }, { h: "Delivery", r: (r) => `${r.kind} ${r.orderId}` },
        { h: "Amount", r: (r) => K(r.amountNgwee) }, { h: "Insurer", r: (r) => r.insurer }]} />
      {editable && sel.length > 0 && <div style={{ marginTop: 12 }}>
        <p><b>{sel.length} selected · {K(selTotal)}</b>{insurers.size > 1 && <span className="err"> · select premiums for one insurer at a time</span>}</p>
        <div className="field"><label htmlFor="payRef">Payment reference (bank / mobile money transfer)</label>
          <input id="payRef" value={payRef} onChange={(e) => setPayRef(e.target.value)} placeholder="e.g. ZANACO FT2611270041" /></div>
        <div className="field"><label htmlFor="paidK">Amount transferred (K) — must equal the selection</label>
          <input id="paidK" inputMode="decimal" value={paidK} onChange={(e) => setPaidK(e.target.value)} placeholder={(selTotal / 100).toFixed(2)} /></div>
        <div className="field"><label htmlFor="note">Note (optional)</label><input id="note" value={note} onChange={(e) => setNote(e.target.value)} placeholder="November premiums" /></div>
        <button className="btn ok" disabled={busy || insurers.size > 1 || payRef.trim().length < 4 || Math.round(Number(paidK || 0) * 100) !== selTotal} onClick={markPaid}>
          Mark {sel.length} as paid</button>
      </div>}
    </Panel>
    <Panel title="Payments to the insurer">
      <Table rows={remittances} empty="No payments recorded yet." cols={[{ h: "Paid", r: (r) => when(r.at) }, { h: "Insurer", r: (r) => r.insurer },
        { h: "Reference", r: (r) => <b>{r.reference}</b> }, { h: "Premiums", r: (r) => r.count }, { h: "Amount", r: (r) => K(r.amountNgwee) },
        { h: "By", r: (r) => r.by }, { h: "Note", r: (r) => r.note ?? "—" }]} />
    </Panel>
  </Page>;
}
