/**
 * SeloNachipa Developers — public developer portal for the Open Network.
 * Read the docs without an account; sign in (email + password) to register a platform, get sandbox credentials
 * (shown once), configure webhooks, run sandbox calls from the test console (executed server-side — no secret in the
 * browser), see usage and request production access.
 */
import { useEffect, useState } from "react";
import { createUserWithEmailAndPassword, onAuthStateChanged, signInWithEmailAndPassword, signOut, type User } from "firebase/auth";
import { httpsCallable } from "firebase/functions";
import { auth, fns, isDemo } from "../lib/firebase";

const API = "/api/v1";
const call = async (name: string, data: Record<string, unknown> = {}) => {
  if (isDemo || !fns) throw new Error("This preview isn't connected to SeloNachipa. Deploy to use your account.");
  return (await httpsCallable(fns, name)(data)).data as any;
};
const EVENTS = ["product.created", "product.updated", "product.stock_updated", "product.unpublished", "delivery.created", "delivery.assigned", "delivery.picked_up", "delivery.in_transit", "delivery.completed", "delivery.failed", "delivery.cancelled", "media.video.ready", "media.video.failed"];

export default function Portal() {
  const [user, setUser] = useState<User | null>(null);
  useEffect(() => (auth ? onAuthStateChanged(auth, setUser) : undefined), []);
  return <div className="dp">
    <header className="dp-top">
      <b style={{ color: "#C8102E", fontSize: 20 }}>SeloNachipa</b><span>Developers</span>
      <nav><a href="#start">Get started</a><a href="#platforms">My platforms</a><a href="#console">Test console</a><a href="#reference">API reference</a><a href="#guides">Guides</a>
        {user && <a href="#" onClick={(e) => { e.preventDefault(); if (auth) void signOut(auth); }}>Sign out</a>}</nav>
    </header>
    <section className="dp-hero" id="start">
      <h1>One marketplace. One rider network. Your platform.</h1>
      <p style={{ maxWidth: 720, fontSize: 17 }}>The SeloNachipa Open Network lets approved platforms list their products on the SeloNachipa marketplace — buyers are sent to your own checkout — and send deliveries to the SeloNachipa rider network, through one secure REST API.</p>
      <div className="dp-grid">
        <div className="dp-card"><h3>Open Commerce</h3><p>Publish products with images, prices and stock for discovery on SeloNachipa and approved platforms. Buyers tap "Buy on your platform" and pay on your own checkout — your orders, payments and stock stay yours.</p></div>
        <div className="dp-card"><h3>Open Logistics</h3><p>Quote, request and track deliveries by verified SeloNachipa riders, with pickup PIN, delivery code and proof of delivery. Charge your buyer SeloNachipa's quoted delivery fee; it's owed to SeloNachipa in full.</p></div>
        <div className="dp-card"><h3>How to start</h3><ol style={{ paddingLeft: 18, margin: 0 }}><li>Create an account and register your platform</li><li>We approve your sandbox</li>
          <li>Get sandbox credentials and build against the sandbox</li><li>Request production access</li></ol></div>
      </div>
    </section>
    <section id="platforms"><h2>My platforms</h2>{user ? <Platforms /> : <SignIn />}</section>
    <section id="console"><h2>Test console (sandbox)</h2>{user ? <Console /> : <p>Sign in to run sandbox calls for your platform.</p>}</section>
    <section id="reference"><h2>API reference</h2><Reference /></section>
    <section id="guides"><h2>Guides</h2><Guides /></section>
    <footer style={{ marginTop: 40, color: "#6b7280", fontSize: 13 }}>SeloNachipa is owned and operated by Deep Valley Farms Limited · support@mabala.cloud · +260 978 070 734</footer>
  </div>;
}

function SignIn() {
  const [email, setEmail] = useState(""); const [pw, setPw] = useState(""); const [mode, setMode] = useState<"in" | "up">("in"); const [err, setErr] = useState<string | null>(null);
  const go = async () => {
    setErr(null);
    if (!auth) { setErr("This preview isn't connected to SeloNachipa."); return; }
    try { if (mode === "in") await signInWithEmailAndPassword(auth, email, pw); else await createUserWithEmailAndPassword(auth, email, pw); }
    catch (e) { setErr((e as Error).message.replace("Firebase: ", "")); }
  };
  return <div className="dp-card" style={{ maxWidth: 420 }}>
    <div className="field"><label htmlFor="em">Work email</label><input id="em" type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></div>
    <div className="field"><label htmlFor="pw">Password (8+ characters)</label><input id="pw" type="password" value={pw} onChange={(e) => setPw(e.target.value)} /></div>
    {err && <p className="err">{err}</p>}
    <button className="btn" onClick={go} disabled={!email || pw.length < 8}>{mode === "in" ? "Sign in" : "Create developer account"}</button>
    <p><a href="#" onClick={(e) => { e.preventDefault(); setMode(mode === "in" ? "up" : "in"); }}>{mode === "in" ? "New here? Create an account" : "Have an account? Sign in"}</a></p>
  </div>;
}

function Platforms() {
  const [list, setList] = useState<any[] | null>(null); const [n, setN] = useState(0); const [msg, setMsg] = useState<string | null>(null);
  const [reg, setReg] = useState({ platformName: "", website: "", contactName: "", phone: "", useCase: "", commerce: true, logistics: true });
  useEffect(() => { call("devportal_mine").then((r) => setList(r.platforms)).catch((e) => { setList([]); setMsg(e.message); }); }, [n]);
  const register = async () => { try { await call("devportal_register", { ...reg, services: { commerce: reg.commerce, logistics: reg.logistics } }); setMsg("Registered. We'll review it and approve your sandbox."); setN(n + 1); } catch (e) { setMsg((e as Error).message); } };
  return <>
    {msg && <p className="banner ok">{msg}</p>}
    {list?.map((p) => <PlatformCard key={p.id} p={p} onChange={() => setN(n + 1)} />)}
    <details className="dp-card" open={list?.length === 0}><summary><b>Register a platform</b></summary>
      {(["platformName", "website", "contactName", "phone"] as const).map((k) => <div className="field" key={k}><label htmlFor={k}>{{ platformName: "Platform name", website: "Website", contactName: "Contact person", phone: "Phone (+260…)" }[k]}</label>
        <input id={k} value={reg[k]} onChange={(e) => setReg({ ...reg, [k]: e.target.value })} /></div>)}
      <div className="field"><label htmlFor="uc">What will you build?</label><textarea id="uc" rows={3} value={reg.useCase} onChange={(e) => setReg({ ...reg, useCase: e.target.value })} /></div>
      <label><input type="checkbox" checked={reg.commerce} onChange={(e) => setReg({ ...reg, commerce: e.target.checked })} /> Open Commerce (product discovery)</label><br />
      <label><input type="checkbox" checked={reg.logistics} onChange={(e) => setReg({ ...reg, logistics: e.target.checked })} /> Open Logistics (deliveries)</label><br />
      <button className="btn" style={{ marginTop: 8 }} onClick={register} disabled={reg.platformName.length < 2}>Request integration approval</button></details>
  </>;
}

function PlatformCard({ p, onChange }: { p: any; onChange: () => void }) {
  const [secret, setSecret] = useState<any>(null); const [err, setErr] = useState<string | null>(null);
  const [url, setUrl] = useState(p.webhook?.url ?? ""); const [ev, setEv] = useState<string[]>(p.webhook?.events ?? []);
  const act = async (f: () => Promise<any>) => { setErr(null); try { const r = await f(); if (r?.clientSecret || r?.signingSecret) setSecret(r); onChange(); } catch (e) { setErr((e as Error).message); } };
  const calls = (p.usage ?? []).reduce((s: number, u: any) => s + u.calls, 0);
  return <div className="dp-card" style={{ marginBottom: 12 }}>
    <h3 style={{ margin: 0 }}>{p.name} <small style={{ color: p.status === "ACTIVE" ? "#166534" : "#b45309" }}>· {p.status}{p.productionRequested ? " · production requested" : ""}</small></h3>
    <p style={{ margin: "4px 0" }}>Services: {[p.services?.commerce && "Commerce", p.services?.logistics && "Logistics"].filter(Boolean).join(" + ")} · Environments: {Object.entries(p.environments ?? {}).filter(([, v]) => v).map(([k]) => k).join(", ")} · {calls} calls in 30 days · {p.rateLimitPerMin}/min limit</p>
    <p style={{ margin: "4px 0", fontSize: 13 }}>Scopes: {(p.scopes ?? []).map((s: string) => <code key={s} style={{ marginRight: 6 }}>{s}</code>)}</p>
    {err && <p className="err">{err}</p>}
    {secret && <div className="dp-secret">{secret.clientSecret ? <>Client ID <code>{secret.clientId}</code><br />Client secret <code>{secret.clientSecret}</code></> : <>Webhook signing secret <code>{secret.signingSecret}</code></>}
      <br /><b>Copy it now — it's shown only once.</b> Keep it on your server; never put it in a web page or mobile app.</div>}
    <div style={{ display: "flex", gap: 8, flexWrap: "wrap", margin: "8px 0" }}>
      <button className="btn" disabled={p.status !== "ACTIVE"} onClick={() => act(() => call("devportal_credentials", { partnerId: p.id, env: "sandbox" }))}>Get sandbox credentials</button>
      {p.environments?.production && <button className="btn out" onClick={() => act(() => call("devportal_credentials", { partnerId: p.id, env: "production" }))}>Get production credentials</button>}
      {p.status === "ACTIVE" && !p.environments?.production && !p.productionRequested && <button className="btn out" onClick={() => act(() => call("devportal_requestProduction", { partnerId: p.id, note: "Ready for production" }))}>Request production access</button>}
    </div>
    {p.clients?.length > 0 && <p style={{ fontSize: 13 }}>Credentials: {p.clients.filter((c: any) => !c.clientId.startsWith("portal_")).map((c: any) => <span key={c.clientId} style={{ marginRight: 10 }}><code>{c.clientId}</code> ({c.env}, {c.status})</span>)}</p>}
    <details><summary>Webhooks</summary>
      <div className="field"><label htmlFor={`u-${p.id}`}>Endpoint (https)</label><input id={`u-${p.id}`} value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://your-platform.example/selonachipa/webhooks" /></div>
      <div style={{ columns: 2, fontSize: 13 }}>{EVENTS.map((e) => <label key={e} style={{ display: "block" }}><input type="checkbox" checked={ev.includes(e)} onChange={() => setEv(ev.includes(e) ? ev.filter((x) => x !== e) : [...ev, e])} /> {e}</label>)}</div>
      <div style={{ display: "flex", gap: 8, marginTop: 8 }}><button className="btn" onClick={() => act(() => call("devportal_webhook", { partnerId: p.id, url, events: ev }))}>Save webhook</button>
        <button className="btn out" onClick={() => act(() => call("devportal_testWebhook", { partnerId: p.id }))}>Send test event</button></div></details>
  </div>;
}

const SAMPLES: Record<string, { method: string; path: string; body?: unknown }> = {
  "Publish a sample product": { method: "POST", path: "/products", body: { checkoutUrl: "https://your-platform.example/products/TEST-SKU-1", externalProductId: "TEST-SKU-1", externalSellerId: "test-seller", sellerName: "Test Farm", title: "Test tomatoes, 5 kg",
    priceNgwee: 8000, stock: 20, category: "Agriculture", images: ["https://images.example.com/tomatoes.jpg"], location: { lat: -15.4167, lng: 28.2833, address: "Soweto Market, Lusaka" } } },
  "Update stock to 12": { method: "POST", path: "/products/TEST-SKU-1/stock", body: { stock: 12 } },
  "List my products": { method: "GET", path: "/products" },
  "Make an AI video (sample in sandbox)": { method: "POST", path: "/products/TEST-SKU-1/video", body: { applyToListing: true, voiceover: true } },
  "AI video status": { method: "GET", path: "/products/TEST-SKU-1/video" },
  "Make an AI ad (sample in sandbox)": { method: "POST", path: "/ads", body: { externalProductId: "TEST-SKU-1", offer: "10% off this week" } },
  "Delivery quote": { method: "POST", path: "/delivery/quotes", body: { pickup: { lat: -15.4167, lng: 28.2833 }, dropoff: { lat: -15.43, lng: 28.30 } } },
  "Request a test delivery": { method: "POST", path: "/deliveries", body: { externalOrderRef: "TEST-ORDER-1", pickup: { lat: -15.4167, lng: 28.2833, address: "Soweto Market" },
    dropoff: { lat: -15.43, lng: 28.30, address: "Kabwata" }, weightBand: "1–2 kg", description: "Test parcel", receiver: { name: "Test Receiver", msisdn: "+260971234567" } } },
  "List deliveries": { method: "GET", path: "/deliveries" },
  "Webhook event history": { method: "GET", path: "/webhooks/events" },
  "Settlement statement": { method: "GET", path: "/settlements" },
};
function Console() {
  const [plats, setPlats] = useState<any[]>([]); const [pid, setPid] = useState(""); const [sel, setSel] = useState(Object.keys(SAMPLES)[0]);
  const [body, setBody] = useState(JSON.stringify(SAMPLES[Object.keys(SAMPLES)[0]].body ?? {}, null, 2)); const [out, setOut] = useState<string>(""); const [busy, setBusy] = useState(false);
  useEffect(() => { call("devportal_mine").then((r) => { const a = r.platforms.filter((p: any) => p.status === "ACTIVE"); setPlats(a); setPid(a[0]?.id ?? ""); }).catch(() => undefined); }, []);
  const pick = (k: string) => { setSel(k); setBody(JSON.stringify(SAMPLES[k].body ?? {}, null, 2)); };
  const run = async () => { setBusy(true); try { const s = SAMPLES[sel]; const r = await call("devportal_try", { partnerId: pid, method: s.method, path: s.path, body: s.method === "GET" ? undefined : JSON.parse(body || "{}") });
    setOut(`HTTP ${r.status}\n${JSON.stringify(r.body, null, 2)}`); } catch (e) { setOut((e as Error).message); } setBusy(false); };
  if (!plats.length) return <p>Once your platform is approved for sandbox, you can run calls here.</p>;
  const s = SAMPLES[sel];
  return <div className="dp-grid">
    <div className="dp-card">
      <div className="field"><label htmlFor="pl">Platform</label><select id="pl" value={pid} onChange={(e) => setPid(e.target.value)}>{plats.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select></div>
      <div className="field"><label htmlFor="op">Call</label><select id="op" value={sel} onChange={(e) => pick(e.target.value)}>{Object.keys(SAMPLES).map((k) => <option key={k}>{k}</option>)}</select></div>
      <p><span className={`dp-m ${s.method}`}>{s.method}</span> <code>{API}{s.path}</code></p>
      {s.method !== "GET" && <textarea rows={10} style={{ width: "100%", fontFamily: "JetBrains Mono, monospace", fontSize: 12 }} value={body} onChange={(e) => setBody(e.target.value)} />}
      <button className="btn" disabled={busy || !pid} onClick={run}>{busy ? "Running…" : "Run in sandbox"}</button>
      <p style={{ fontSize: 12, color: "#6b7280" }}>Runs on our servers with your platform's sandbox access. Sandbox deliveries move one status per minute and fire your webhooks.</p>
    </div>
    <div className="dp-card"><b>Response</b><pre>{out || "—"}</pre></div>
  </div>;
}

function Reference() {
  const [spec, setSpec] = useState<any>(null); const [err, setErr] = useState<string | null>(null);
  useEffect(() => { fetch(`${API}/openapi.json`).then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`)))).then(setSpec).catch((e) => setErr(String(e.message))); }, []);
  if (!spec) return <p>{err ? <>The live reference isn't reachable here ({err}). Download it from <code>{API}/openapi.json</code> once deployed.</> : "Loading…"}</p>;
  const byTag: Record<string, { m: string; path: string; op: any }[]> = {};
  Object.entries(spec.paths).forEach(([path, ops]: any) => Object.entries(ops).forEach(([m, op]: any) => (byTag[op.tags?.[0] ?? "Other"] ??= []).push({ m: m.toUpperCase(), path, op })));
  return <>
    <p>{spec.info.description.split("\n\n").map((t: string, i: number) => <span key={i}>{t}<br /><br /></span>)}</p>
    <p><a href={`${API}/openapi.json`} download>Download OpenAPI 3.1 (JSON)</a> — import it into Postman, Insomnia or a code generator for SDKs.</p>
    {Object.entries(byTag).map(([tag, eps]) => <div key={tag} className="dp-card" style={{ marginBottom: 10 }}><h3 style={{ marginTop: 0 }}>{tag}</h3>
      {eps.map((e) => <details key={e.m + e.path} className="dp-ep"><summary style={{ display: "flex", gap: 10, alignItems: "baseline", width: "100%" }}>
        <span className={`dp-m ${e.m}`}>{e.m}</span><code>{e.path}</code><span>{e.op.summary}</span><span className="dp-scope">{e.op.security?.[0]?.oauth2?.[0] ?? "public"}</span></summary>
        {e.op.requestBody?.content?.["application/json"]?.example && <pre>{JSON.stringify(e.op.requestBody.content["application/json"].example, null, 2)}</pre>}</details>)}</div>)}
    <div className="dp-card"><h3 style={{ marginTop: 0 }}>Errors</h3><p>Errors return <code>{`{ "error": { "code", "message", "details" } }`}</code>. Codes: <code>unauthorized</code> 401, <code>forbidden</code>/<code>insufficient_scope</code> 403,
      <code>not_found</code> 404, <code>idempotency_conflict</code>/<code>insufficient_stock</code>/<code>conflict</code> 409, <code>validation_failed</code> 422, <code>rate_limited</code> 429 (honour Retry-After), <code>internal</code> 500 (retry with the same Idempotency-Key).</p></div>
  </>;
}

function Guides() {
  return <div className="dp-grid">
    <div className="dp-card"><h3>1 · Get a token</h3><pre>{`curl -X POST ${location.origin}${API}/oauth/token \\
  -H 'content-type: application/json' \\
  -d '{"grant_type":"client_credentials",
       "client_id":"$CLIENT_ID",
       "client_secret":"$CLIENT_SECRET"}'`}</pre><p>Call this from your server only. Cache the token for up to an hour.</p></div>
    <div className="dp-card"><h3>2 · Publish and sync stock</h3><pre>{`curl -X POST ${location.origin}${API}/products \\
  -H "authorization: Bearer $TOKEN" \\
  -H "idempotency-key: publish-SKU-42-v7" \\
  -H 'content-type: application/json' \\
  -d @product.json

# whenever your stock changes:
curl -X POST .../products/SKU-42/stock \\
  -H "idempotency-key: stock-SKU-42-$(date +%s)" \\
  -d '{"stock": 28}'`}</pre><p>Your system stays the source of truth. When stock reaches 0 the product shows as sold out.</p></div>
    <div className="dp-card"><h3>3 · Checkout stays yours</h3><p>Every product needs a <code>checkoutUrl</code>. Buyers who find it on SeloNachipa tap "Buy on your platform" and pay on your checkout. Record the sale once, in your own books.</p></div>
    <div className="dp-card"><h3>AI videos and ads</h3><p><code>POST /products/:id/video</code> turns your product photo into a short vertical video: AI product motion, a voiceover, captions, your headline and price. It takes a few minutes; you get <code>media.video.ready</code> with <code>videoUrl</code>, which you can also use on your own platform. <code>POST /ads</code> returns story and square ad images built around your real photo, plus WhatsApp, Facebook and TikTok copy, in about 20 seconds. Sandbox returns sample media.</p></div>
    <div className="dp-card"><h3>4 · Delivery fee and deliveries</h3><p>At checkout call <code>POST /delivery/quotes</code> and charge your buyer exactly <code>priceNgwee</code> — that is SeloNachipa's fee, owed to SeloNachipa in full (see <code>GET /settlements</code>). After payment, <code>POST /deliveries</code> with your order id as <code>externalOrderRef</code>: sending it again never creates a second delivery. Give the 4-digit <code>pickupPin</code> to whoever hands over the goods; the receiver gets a delivery code by SMS.</p></div>
    <div className="dp-card"><h3>5 · Verify webhooks (Node.js)</h3><pre>{`import crypto from "node:crypto";
function verify(req, secret) {
  const id = req.headers["webhook-id"], ts = req.headers["webhook-timestamp"];
  if (Math.abs(Date.now()/1000 - Number(ts)) > 300) return false;
  const key = Buffer.from(secret.replace("whsec_", ""), "base64");
  const want = "v1," + crypto.createHmac("sha256", key)
    .update(\`\${id}.\${ts}.\${req.rawBody}\`).digest("base64");
  return req.headers["webhook-signature"].split(" ")
    .some((s) => s.length === want.length &&
      crypto.timingSafeEqual(Buffer.from(s), Buffer.from(want)));
}`}</pre><p>Reply 2xx within 10 s. De-duplicate by <code>webhook-id</code>; we retry for about 2 days.</p></div>
    <div className="dp-card"><h3>Python</h3><pre>{`import base64, hmac, hashlib, time
def verify(headers, raw_body: bytes, secret: str) -> bool:
    i, ts = headers["webhook-id"], headers["webhook-timestamp"]
    if abs(time.time() - int(ts)) > 300: return False
    key = base64.b64decode(secret.removeprefix("whsec_"))
    mac = hmac.new(key, f"{i}.{ts}.".encode() + raw_body, hashlib.sha256)
    want = "v1," + base64.b64encode(mac.digest()).decode()
    return any(hmac.compare_digest(s, want)
               for s in headers["webhook-signature"].split())`}</pre></div>
    <div className="dp-card"><h3>Rules of the road</h3><ul style={{ paddingLeft: 18 }}><li>Mobile money only — never collect cash.</li><li>Deliveries: same city, up to 5 kg.</li>
      <li>You see a rider's first name and plate only while the job is active, and a buyer's first name only.</li><li>Use buyer and rider data only to complete the delivery (Data Protection Act No. 3 of 2021).</li>
      <li>Sandbox never reaches real buyers or riders.</li></ul></div>
  </div>;
}
