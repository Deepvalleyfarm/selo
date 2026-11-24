import { useState } from "react";
import { useCollection, writeDoc } from "../lib/data";
import { can, useSession } from "../lib/session";
import { Page, Panel, Pill, Table } from "../lib/ui";

const blank = { id: "", name: "", city: "Lusaka", province: "Lusaka", lat: "", lng: "", radiusKm: "2.5" };

export default function Zones() {
  const { admin } = useSession();
  const zones = useCollection("zones").rows;
  const [f, setF] = useState(blank);
  const [msg, setMsg] = useState("");
  const editable = can(admin, "operations");
  const save = async () => {
    const id = f.id || f.name.toLowerCase().replace(/[^a-z0-9]+/g, "-");
    await writeDoc(`zones/${id}`, { name: f.name, city: f.city, province: f.province, radiusKm: Number(f.radiusKm), isActive: true,
      centre: { lat: Number(f.lat), lng: Number(f.lng) } });
    setMsg(`Saved ${f.name}. Riders in ${f.city} can activate it now.`); setF(blank);
  };
  const toggle = (z: any) => writeDoc(`zones/${z.id}`, { isActive: !z.isActive });
  return <Page title="Zones & cities" sub="Zones decide which riders see which jobs. Deactivating a zone notifies its riders; accepted orders are not affected.">
    <Panel><Table rows={zones} cols={[
      { h: "Zone", r: (z) => <b>{z.name}</b> }, { h: "City", r: (z) => z.city }, { h: "Province", r: (z) => z.province },
      { h: "Centre", r: (z) => <span className="mono">{z.centre?.lat}, {z.centre?.lng}</span> }, { h: "Radius", r: (z) => `${z.radiusKm} km` },
      { h: "Status", r: (z) => z.isActive ? <Pill tone="ok">Active</Pill> : <Pill tone="grey">Inactive</Pill> },
      { h: "", r: (z) => editable && <button className="link" onClick={() => toggle(z)}>{z.isActive ? "Deactivate" : "Activate"}</button> },
      { h: " ", r: (z) => editable && <button className="link" onClick={() => setF({ id: z.id, name: z.name, city: z.city, province: z.province, lat: String(z.centre?.lat ?? ""), lng: String(z.centre?.lng ?? ""), radiusKm: String(z.radiusKm) })}>Edit</button> }]} /></Panel>
    {editable && <Panel title={f.id ? `Edit ${f.name}` : "Add a zone"}>
      <div className="toolbar">
        {(["name", "city", "province", "lat", "lng", "radiusKm"] as const).map((k) =>
          <div className="field" key={k}><label htmlFor={k}>{k === "radiusKm" ? "Radius (km)" : k[0].toUpperCase() + k.slice(1)}</label>
            <input id={k} value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} size={k === "name" ? 18 : 9} /></div>)}
        <button className="btn" style={{ alignSelf: "end" }} disabled={!f.name || !f.lat || !f.lng} onClick={save}>Save zone</button>
      </div>
      {msg && <div className="banner ok" style={{ marginTop: 8 }}>{msg}</div>}
    </Panel>}
  </Page>;
}
