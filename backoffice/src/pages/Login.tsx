import { useState } from "react";
import { Logo } from "../lib/icons";
import { useSession } from "../lib/session";

export default function Login() {
  const { login } = useSession();
  const [email, setEmail] = useState("");
  const [pw, setPw] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  return <div className="login"><form onSubmit={async (e) => { e.preventDefault(); setBusy(true); setErr("");
    try { await login(email, pw); } catch (x) { setErr((x as Error).message.replace("Firebase: ", "")); } finally { setBusy(false); } }}>
    <div style={{ display: "flex", gap: 10, alignItems: "center" }}><span className="logo" style={{ boxShadow: "0 0 0 1px #eee" }}><Logo /></span><h1>SeloNaChipa Admin</h1></div>
    <p style={{ margin: 0, color: "var(--mute)" }}>Staff sign-in. Your account needs an admin role and two-step verification.</p>
    <div className="field"><label htmlFor="em">Work email</label><input id="em" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} /></div>
    <div className="field"><label htmlFor="pw">Password</label><input id="pw" type="password" autoComplete="current-password" value={pw} onChange={(e) => setPw(e.target.value)} /></div>
    {err && <div className="err">{err}</div>}
    <button className="btn" disabled={busy || !email || !pw}>{busy ? "Signing in…" : "Sign in"}</button>
  </form></div>;
}
