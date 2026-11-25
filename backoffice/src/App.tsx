import { BrowserRouter, Route, Routes } from "react-router-dom";
import Layout from "./Layout";
import { SessionProvider, useSession } from "./lib/session";
import Admins from "./pages/Admins";
import Algorithm from "./pages/Algorithm";
import Engagement from "./pages/Engagement";
import Leads from "./pages/Leads";
import Promotions from "./pages/Promotions";
import { DeliveryCodes, EscrowLedger, OverrideLog, Parcels, Support } from "./pages/ParcelsOps";
import Audit from "./pages/Audit";
import Config from "./pages/Config";
import Content from "./pages/Content";
import Disputes from "./pages/Disputes";
import Kyc from "./pages/Kyc";
import Listings from "./pages/Listings";
import Login from "./pages/Login";
import Notify from "./pages/Notify";
import Orders from "./pages/Orders";
import Overview from "./pages/Overview";
import Payouts from "./pages/Payouts";
import Reports from "./pages/Reports";
import Riders from "./pages/Riders";
import SocialFund from "./pages/SocialFund";
import Users from "./pages/Users";
import Zones from "./pages/Zones";

function Gate() {
  const { admin, ready } = useSession();
  if (!ready) return <div className="empty">Loading…</div>;
  if (!admin) return <Login />;
  return <Routes>
    <Route element={<Layout />}>
      <Route index element={<Overview />} /><Route path="users" element={<Users />} /><Route path="kyc" element={<Kyc />} />
      <Route path="orders" element={<Orders />} /><Route path="riders" element={<Riders />} /><Route path="zones" element={<Zones />} />
      <Route path="payouts" element={<Payouts />} /><Route path="disputes" element={<Disputes />} /><Route path="social-fund" element={<SocialFund />} />
      <Route path="listings" element={<Listings />} /><Route path="content" element={<Content />} /><Route path="notify" element={<Notify />} />
      <Route path="config" element={<Config />} /><Route path="reports" element={<Reports />} /><Route path="audit" element={<Audit />} />
      <Route path="admins" element={<Admins />} /><Route path="promotions" element={<Promotions />} />
      <Route path="parcels" element={<Parcels />} /><Route path="escrow-ledger" element={<EscrowLedger />} /><Route path="delivery-codes" element={<DeliveryCodes />} />
      <Route path="overrides" element={<OverrideLog />} /><Route path="support" element={<Support />} />
      <Route path="algorithm" element={<Algorithm />} /><Route path="engagement" element={<Engagement />} /><Route path="leads" element={<Leads />} /><Route path="*" element={<Overview />} />
    </Route>
  </Routes>;
}

export default function App() {
  return <SessionProvider><BrowserRouter><Gate /></BrowserRouter></SessionProvider>;
}
