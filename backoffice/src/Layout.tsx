import { useState } from "react";
import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { Icon, Logo } from "./lib/icons";
import { useCollection } from "./lib/data";
import { isDemo } from "./lib/firebase";
import { useSession } from "./lib/session";
import { where } from "firebase/firestore";

const NAV: [string, [string, string, string, string?][]][] = [
  ["MAIN", [["/", "home", "Overview"], ["/users", "users", "Users"], ["/kyc", "idcard", "KYC queue", "kyc"]]],
  ["OPERATIONS", [["/orders", "box", "Orders & parcels"], ["/riders", "bike", "Riders & live map"], ["/zones", "pin", "Zones & cities"]]],
  ["MONEY", [["/payouts", "coins", "Escrow & payouts", "payouts"], ["/disputes", "gavel", "Disputes", "disputes"], ["/social-fund", "heart", "Social Fund"]]],
  ["CONTENT", [["/listings", "flag", "Listings moderation", "moderation"], ["/content", "image", "Banners & content"], ["/notify", "send", "Notifications"]]],
  ["SYSTEM", [["/config", "sliders", "Configuration"], ["/reports", "report", "Reports"], ["/audit", "log", "Audit log"], ["/admins", "lock", "Admin users"]]],
];

export default function Layout() {
  const { admin, logout } = useSession();
  const nav = useNavigate();
  const [q, setQ] = useState("");
  const counts: Record<string, number> = {
    kyc: useCollection("kycQueue", [where("status", "==", "PENDING")], "pending").rows.filter((r) => r.status === "PENDING").length,
    payouts: useCollection("payouts", [where("status", "in", ["FAILED_RETRY", "ESCALATED"])], "failing").rows.filter((r) => ["FAILED_RETRY", "ESCALATED"].includes(r.status)).length,
    disputes: useCollection("disputes", [where("status", "==", "OPEN")], "open").rows.filter((r) => r.status === "OPEN").length,
    moderation: useCollection("moderationQueue").rows.length,
  };
  const initials = (admin?.email ?? "?").slice(0, 2).toUpperCase();
  return <div className="app">
    <nav className="side" aria-label="Back office">
      <div className="brandrow"><span className="logo"><Logo /></span>SeloNaChipa Admin</div>
      {NAV.map(([g, items]) => <div key={g} style={{ display: "contents" }}>
        <div className="grp">{g}</div>
        {items.map(([to, icon, label, c]) => <NavLink key={to} to={to} end={to === "/"}>
          <Icon n={icon} />{label}{c && counts[c] ? <span className="cnt">{counts[c]}</span> : null}
        </NavLink>)}
      </div>)}
    </nav>
    <div className="main">
      <header className="tbar">
        <form className="search" onSubmit={(e) => { e.preventDefault(); nav(`/users?q=${encodeURIComponent(q)}`); }}>
          <Icon n="search" /><input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search user, MSISDN, order ID or Lipila ref" aria-label="Search" />
        </form>
        <span className={`env ${isDemo ? "demo" : ""}`}>{isDemo ? "DEMO DATA" : "PRODUCTION"}</span>
        <span className="sync"><i />{isDemo ? "Demo · local data" : "Live · Firestore synced"}</span>
        <div className="me"><Icon n="bell" size={18} /><span className="av">{initials}</span>{admin?.email} · {admin?.role.replace("_", " ")}
          {!isDemo && <button className="link" onClick={logout}>Log out</button>}</div>
      </header>
      <Outlet />
    </div>
  </div>;
}
