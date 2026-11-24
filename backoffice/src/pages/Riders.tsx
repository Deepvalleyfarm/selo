import { useEffect, useState } from "react";
import { onValue, ref } from "firebase/database";
import { useCollection } from "../lib/data";
import { rtdb } from "../lib/firebase";
import { k, maskMsisdn } from "../lib/format";
import { Page, Panel, Pill, StatePill, Table } from "../lib/ui";

type Loc = { lat: number; lng: number; at: number };
// Lusaka bounding box for the live map projection
const BOX = { minLat: -15.50, maxLat: -15.33, minLng: 28.20, maxLng: 28.42 };

export default function Riders() {
  const riders = useCollection("users").rows.filter((u) => u.role === "RIDER");
  const presence = useCollection("riderPresence").rows;
  const wallets = useCollection("riderWallets").rows;
  const zones = useCollection("zones").rows;
  const [locs, setLocs] = useState<Record<string, Loc>>({});
  useEffect(() => {
    if (!rtdb) { setLocs({ u3: { lat: -15.418, lng: 28.31, at: Date.now() } }); return; }
    return onValue(ref(rtdb, "riderLocations"), (s) => setLocs((s.val() ?? {}) as Record<string, Loc>));
  }, []);
  const p = (id: string) => presence.find((x) => x.id === id);
  const w = (id: string) => wallets.find((x) => x.id === id);
  const x = (lng: number) => ((lng - BOX.minLng) / (BOX.maxLng - BOX.minLng)) * 100;
  const y = (lat: number) => ((BOX.maxLat - lat) / (BOX.maxLat - BOX.minLat)) * 100;
  return <Page title="Riders & live map" sub={`${presence.filter((r) => r.isOnline).length} online · ${riders.length} riders`}>
    <Panel title="Live map · Lusaka">
      <div className="map" role="img" aria-label="Rider positions and zones">
        <svg viewBox="0 0 100 100" preserveAspectRatio="none">
          {zones.filter((z) => z.centre).map((z) => <circle key={z.id} cx={x(z.centre.lng)} cy={y(z.centre.lat)} r={z.radiusKm * 1.6}
            fill={z.isActive ? "rgba(204,0,35,.10)" : "rgba(120,120,130,.12)"} stroke={z.isActive ? "#CC0023" : "#999"} strokeWidth=".2" />)}
          {Object.entries(locs).map(([id, l]) => <circle key={id} cx={x(l.lng)} cy={y(l.lat)} r="1.1" fill="#10B981" stroke="#fff" strokeWidth=".4" />)}
        </svg>
        <div style={{ position: "absolute", left: 12, bottom: 10, fontSize: 11, color: "var(--mute)" }}>Red circles: zones · Green: riders on a delivery (location shared only while an order is active)</div>
      </div>
    </Panel>
    <Panel title="All riders">
      <Table rows={riders} cols={[
        { h: "Rider", r: (r) => <b>{r.fullName}</b> }, { h: "Mobile", r: (r) => maskMsisdn(r.msisdn) }, { h: "City", r: (r) => r.district },
        { h: "Vehicle", r: (r) => r.vehicle ?? "—" }, { h: "Status", r: (r) => <StatePill s={r.accountStatus} /> },
        { h: "Online", r: (r) => p(r.id)?.isOnline ? <Pill tone="ok">Online</Pill> : <Pill tone="grey">Offline</Pill> },
        { h: "Active", r: (r) => p(r.id)?.activeCount ?? 0 }, { h: "Deliveries", r: (r) => w(r.id)?.completedDeliveries ?? 0 },
        { h: "Tier", r: (r) => w(r.id)?.tier ?? "Starter" }, { h: "Social Fund", r: (r) => k(w(r.id)?.socialFundNgwee) },
        { h: "Zones", r: (r) => (p(r.id)?.zoneIds ?? []).join(", ") || "—" }]} />
      <p style={{ color: "var(--mute)", margin: "8px 0 0" }}>Admins can view rider zones and ledgers but cannot change a rider's zones or start a withdrawal for them.</p>
    </Panel>
  </Page>;
}
