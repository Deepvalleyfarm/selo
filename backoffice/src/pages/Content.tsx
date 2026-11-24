import { useState } from "react";
import { useCollection, writeDoc } from "../lib/data";
import { k } from "../lib/format";
import { can, useSession } from "../lib/session";
import { Page, Panel } from "../lib/ui";

const COLORS: Record<string, [number, number]> = {
  produce: [0xff7a0016, 0xffe2334d], electronics: [0xff1a1a2e, 0xff4b3b8f], food: [0xff9a3412, 0xfff59e0b],
  solar: [0xff065f46, 0xff0d9488], fashion: [0xff86198f, 0xffdb2777],
};
const hex = (n: number) => `#${(n & 0xffffff).toString(16).padStart(6, "0")}`;

/** The 5 sponsored adverts that slide on the app's welcome screen. Edits reach phones within seconds. */
export default function Content() {
  const { admin } = useSession();
  const ads = useCollection("contentBlocks").rows.filter((r) => r.type === "advert").sort((a, b) => a.slot - b.slot);
  const editable = can(admin, "moderator");
  return <Page title="Banners & content" sub="Welcome-screen adverts (5 paid slots, auto-slide every 3 s) · changes sync to the app live">
    <div className="cfg">
      {[1, 2, 3, 4, 5].map((slot) => <AdCard key={slot} slot={slot} ad={ads.find((a) => a.slot === slot)} editable={editable} />)}
    </div>
    <Panel title="Other content">
      <p style={{ margin: 0, color: "var(--mute)" }}>Categories (<span className="mono">categories</span>), security-question wording (<span className="mono">securityQuestionBank</span>),
        SeloWizard persona and Terms versions are stored in Firestore and edited the same way. Every save is written to the audit log.</p>
    </Panel>
  </Page>;
}

function AdCard({ slot, ad, editable }: { slot: number; ad?: any; editable: boolean }) {
  const [f, setF] = useState({ title: ad?.title ?? "", seller: ad?.seller ?? "", price: ad ? String(ad.priceNgwee / 100) : "", cta: ad?.cta ?? "Shop now",
    category: ad?.category ?? "produce", active: ad?.active ?? true, listingId: ad?.listingId ?? "" });
  const [saved, setSaved] = useState(false);
  const [a, b] = COLORS[f.category] ?? COLORS.produce;
  const save = async () => {
    await writeDoc(`contentBlocks/advert-${slot}`, { type: "advert", slot, title: f.title, seller: f.seller, priceNgwee: Math.round(Number(f.price) * 100),
      cta: f.cta, category: f.category, colorA: a, colorB: b, active: f.active, listingId: f.listingId || null });
    setSaved(true); setTimeout(() => setSaved(false), 2500);
  };
  return <div className="cf">
    <div style={{ borderRadius: 12, padding: 12, color: "#fff", minHeight: 110, background: `linear-gradient(135deg, ${hex(a)}, ${hex(b)})`, display: "grid", alignContent: "space-between", opacity: f.active ? 1 : .45 }}>
      <span style={{ fontSize: 9.5, fontWeight: 800, background: "rgba(0,0,0,.28)", padding: "2px 7px", borderRadius: 6, justifySelf: "start" }}>SPONSORED · {slot}/5</span>
      <div><b style={{ fontSize: 15 }}>{f.title || "Empty slot"}</b><div style={{ fontSize: 11, opacity: .9 }}>{f.seller}</div></div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}><b>{f.price ? k(Number(f.price) * 100) : ""}</b>
        <span style={{ background: "#fff", color: "#1a1a2e", borderRadius: 99, padding: "4px 10px", fontSize: 11, fontWeight: 800 }}>{f.cta}</span></div>
    </div>
    {editable && <>
      <div className="field"><label>Title</label><input value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} maxLength={48} /></div>
      <div className="field"><label>Advertiser · location</label><input value={f.seller} onChange={(e) => setF({ ...f, seller: e.target.value })} /></div>
      <div className="toolbar">
        <div className="field"><label>Price (ZMW)</label><input value={f.price} onChange={(e) => setF({ ...f, price: e.target.value })} size={8} /></div>
        <div className="field"><label>Button</label><input value={f.cta} onChange={(e) => setF({ ...f, cta: e.target.value })} size={9} /></div>
        <div className="field"><label>Theme</label><select value={f.category} onChange={(e) => setF({ ...f, category: e.target.value })}>{Object.keys(COLORS).map((c) => <option key={c}>{c}</option>)}</select></div>
      </div>
      <div className="field"><label>Linked listing ID (optional)</label><input value={f.listingId} onChange={(e) => setF({ ...f, listingId: e.target.value })} /></div>
      <label style={{ display: "flex", gap: 6, alignItems: "center" }}><input type="checkbox" checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} /> Show in app</label>
      <button className="btn" onClick={save} disabled={!f.title}>{saved ? "Saved · live in app" : "Save & publish"}</button>
    </>}
  </div>;
}
