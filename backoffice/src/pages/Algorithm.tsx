import { useState } from "react";
import { orderBy, limit } from "firebase/firestore";
import { useCollection, useDoc } from "../lib/data";
import { when } from "../lib/format";
import { can, useSession } from "../lib/session";
import { Kpi, Page, Panel, Pill, Table, useAction } from "../lib/ui";

const SIGNALS: [string, string][] = [["watchTimeRatio", "Watch time"], ["like", "Like"], ["share", "Share"], ["enquiry", "Enquiry"], ["save", "Save"],
  ["notInterested", "Not interested"], ["categoryMatch", "Category match"], ["sellerEngagement", "Seller engagement"]];
const DEFAULT: Record<string, number> = { watchTimeRatio: 0.30, like: 0.20, share: 0.18, enquiry: 0.15, save: 0.08, notInterested: -0.25, categoryMatch: 0.05, sellerEngagement: 0.04 };

/** Pillar 3 control room: versioned cohort weights, tuning log, A/B variants, rollback and the guardrails. */
export default function Algorithm() {
  const { admin } = useSession();
  const cur = useDoc("algorithmState/current");
  const versions = useCollection("algorithmState", [orderBy("version", "desc"), limit(40)], "versions").rows.filter((v) => v.id !== "current");
  const guard = useDoc("platformConfig/guardrails");
  const [tab, setTab] = useState("cohorts");
  const { busy, run, Msg } = useAction();
  const cohorts = Object.values((cur?.cohorts ?? {}) as Record<string, any>).sort((a, b) => a.cohortId - b.cohortId);
  const ops = can(admin, "operations");
  const fmt = (v: number, d: number) => <span style={{ color: v > d + 1e-4 ? "var(--ok)" : v < d - 1e-4 ? "var(--brand)" : undefined, fontWeight: v !== d ? 700 : 400 }}>{v?.toFixed(3)}</span>;
  return <Page title="Discovery algorithm" sub={`Version ${cur?.version ?? "—"} · ${cur?.reason ?? ""} · ${when(cur?.updatedAt)} · auto-tunes every 6 h and weekly`}
    right={<div className="seg">{[["cohorts", "Cohorts"], ["ab", "A/B test"], ["history", "Version history"], ["guardrails", "Guardrails"]].map(([k, l]) =>
      <button key={k} className={tab === k ? "on" : ""} onClick={() => setTab(k)}>{l}</button>)}</div>}>
    <div className="kpis">
      <Kpi label="Active cohorts" value={cohorts.length} note="k-means, re-clustered weekly" />
      <Kpi label="Algorithm versions" value={cur?.version ?? 0} note="every change kept, replayable" />
      <Kpi label="A/B traffic" value="10%" note="4 variants × 2.5%" />
      <Kpi label="Daily cap" value={`${guard?.sessionCapMinutes ?? 145} min`} note={guard?.enabled === false ? "guardrails OFF" : `notice at ${guard?.warnAtMinutes ?? 130} min`} tone={guard?.enabled === false ? "dn" : "up"} />
    </div>
    <Msg />
    {tab === "cohorts" && <Panel title="Ranking weights and retrieval mix per cohort (green = tuned up, red = tuned down from PRD default)">
      <Table rows={cohorts.map((c) => ({ ...c, id: String(c.cohortId) }))} empty="No cohorts yet — they appear after the first weekly re-cluster." cols={[
        { h: "Cohort", r: (c) => <b>#{c.cohortId}</b> },
        ...SIGNALS.map(([k, n]) => ({ h: n, r: (c: any) => fmt(c.weights?.[k], DEFAULT[k]) })),
        { h: "Mix: cohort / trend / new / explore", r: (c) => <span className="mono">{["cohort", "trending", "recent", "exploration"].map((k) => Math.round((c.mix?.[k] ?? 0) * 100)).join(" / ")}%</span> },
        { h: "Category share", r: (c) => Object.entries(c.categoryShare ?? {}).map(([k, v]) => `${k} ×${Number(v).toFixed(2)}`).join(", ") || "—" },
        { h: "Type boost", r: (c) => c.typeBoost?.until && c.typeBoost.until > Date.now() ? `video ×${c.typeBoost.video} · photo ×${c.typeBoost.photo}` : "—" }]} />
    </Panel>}
    {tab === "ab" && <Panel title="Weekly A/B test: winner replaces current weights if it beats control by 2% on sessions and conversion">
      <Table rows={(cur?.variants ?? []).map((v: any, i: number) => ({ ...v, id: `v${i}` }))} cols={[
        { h: "Variant", r: (v) => <b>{v.id}</b> }, { h: "Traffic", r: () => "2.5%" }, ...SIGNALS.map(([k, n]) => ({ h: n, r: (v: any) => fmt(v[k], DEFAULT[k]) }))]} />
    </Panel>}
    {tab === "history" && <Panel title="Every algorithm change, newest first">
      <Table rows={versions} cols={[
        { h: "Version", r: (v) => <span className="mono">{v.id}</span> }, { h: "When", r: (v) => when(v.createdAt) }, { h: "Why", r: (v) => v.reason },
        { h: "Changes", r: (v) => <div style={{ display: "grid", gap: 2 }}>{(v.changes ?? []).map((c: any, i: number) =>
          <span key={i}><Pill tone="info">{c.rule}</Pill> {c.cohort !== undefined && `#${c.cohort} `}{c.detail}</span>)}</div> },
        { h: "By", r: (v) => v.createdBy }, { h: "", r: (v) => ops && v.version !== cur?.version && <button className="link" disabled={busy}
          onClick={() => run("admin_rollbackAlgorithm", { versionId: v.id }, `Rolled back to ${v.id} (saved as a new version).`)}>Roll back to this</button> }]} />
    </Panel>}
    {tab === "guardrails" && <Guardrails g={guard} ceo={!!admin?.ceo} canEdit={ops} />}
  </Page>;
}

function Guardrails({ g, ceo, canEdit }: { g: any; ceo: boolean; canEdit: boolean }) {
  const [e, setE] = useState<Record<string, string>>({});
  const { busy, run, Msg } = useAction();
  const v = (k: string) => e[k] ?? String(g?.[k] ?? "");
  const field = (k: string, label: string, unit: string, hint: string) => <div className="cf" key={k}>
    <span className="lbl">{label}</span><span className="key">{k}</span>
    <div className="inp"><span>{unit}</span><input aria-label={label} value={v(k)} disabled={!canEdit} onChange={(x) => setE({ ...e, [k]: x.target.value })} /></div>
    <span className="meta">{hint}</span></div>;
  return <>
    <div className={`banner ${g?.enabled === false ? "err" : "ok"}`}>Anti-addiction guardrails are <b>{g?.enabled === false ? "OFF" : "ON"}</b>.
      The feed never optimises beyond the daily cap, shows a break notice before it, and offers a gentle budget check — never forced.
      {g?.enabled !== false ? " Turning them off needs a CEO-level override." : ""}</div>
    <Msg />
    <div className="cfg">
      {field("sessionCapMinutes", "Daily optimisation cap", "min", "After this the feed is newest-first, not personalised. Above 145 needs CEO.")}
      {field("warnAtMinutes", "Break notice at", "min", "\"You've been browsing for … minutes\"")}
      {field("budgetMultiplier", "Budget check when enquiries grow", "×", "compared with the window below")}
      {field("budgetWindowWeeks", "Budget check window", "weeks", "")}
    </div>
    {canEdit && <div className="toolbar">
      <button className="btn" disabled={busy || !Object.keys(e).length} onClick={async () => { if (await run("admin_updateGuardrails",
        { changes: Object.fromEntries(Object.entries(e).map(([k, x]) => [k, Number(x)])) }, "Guardrails saved. Apps pick it up on the next feed page.")) setE({}); }}>Save guardrails</button>
      {g?.enabled !== false
        ? <button className="btn out" disabled={busy || !ceo} title={ceo ? "" : "CEO-level override required"}
            onClick={() => run("admin_updateGuardrails", { changes: { enabled: false } }, "Guardrails turned off (CEO override recorded).")}>Turn off guardrails{ceo ? "" : " · CEO only"}</button>
        : <button className="btn ok" disabled={busy} onClick={() => run("admin_updateGuardrails", { changes: { enabled: true } }, "Guardrails back on.")}>Turn guardrails back on</button>}
    </div>}
    <p style={{ color: "var(--mute)" }}>Every change is written to the audit log with the admin's name. Grant the CEO override with <span className="mono">scripts/grant-admin.mjs email super_admin --ceo</span>.</p>
  </>;
}
