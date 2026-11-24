import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { onIdTokenChanged, signInWithEmailAndPassword, signOut, type User } from "firebase/auth";
import { auth, isDemo } from "./firebase";

export type AdminRole = "super_admin" | "operations" | "finance" | "compliance" | "support" | "moderator" | "analyst";
export interface Admin { uid: string; email: string; role: AdminRole }

const Ctx = createContext<{ admin: Admin | null; ready: boolean; login: (e: string, p: string) => Promise<void>; logout: () => void }>(null!);
export const useSession = () => useContext(Ctx);

const IDLE_MS = 8 * 60 * 60 * 1000; // PRD: admin sessions expire after 8 h of inactivity

export function SessionProvider({ children }: { children: ReactNode }) {
  const [admin, setAdmin] = useState<Admin | null>(isDemo ? { uid: "demo", email: "demo@selonachipa.zm", role: "super_admin" } : null);
  const [ready, setReady] = useState(isDemo);

  useEffect(() => {
    if (isDemo || !auth) return;
    return onIdTokenChanged(auth, async (u: User | null) => {
      if (!u) { setAdmin(null); setReady(true); return; }
      const t = await u.getIdTokenResult();
      const role = t.claims.admin as AdminRole | undefined;
      if (!role) { await signOut(auth!); setAdmin(null); setReady(true); return; }
      setAdmin({ uid: u.uid, email: u.email ?? "", role }); setReady(true);
    });
  }, []);

  useEffect(() => {
    if (isDemo || !admin) return;
    let last = Date.now();
    const bump = () => { last = Date.now(); };
    ["click", "keydown", "mousemove"].forEach((e) => window.addEventListener(e, bump, { passive: true }));
    const t = setInterval(() => { if (Date.now() - last > IDLE_MS) signOut(auth!); }, 60_000);
    return () => { clearInterval(t); ["click", "keydown", "mousemove"].forEach((e) => window.removeEventListener(e, bump)); };
  }, [admin]);

  const login = async (email: string, password: string) => {
    if (!auth) return;
    const cred = await signInWithEmailAndPassword(auth, email, password);
    const t = await cred.user.getIdTokenResult(true);
    if (!t.claims.admin) { await signOut(auth); throw new Error("This account doesn't have back office access."); }
  };
  const logout = () => { if (auth) signOut(auth); };
  return <Ctx.Provider value={{ admin, ready, login, logout }}>{children}</Ctx.Provider>;
}

/** Mirrors requireAdmin() in functions: the UI hides what a role can't do, the API enforces it. */
export function can(admin: Admin | null, ...roles: AdminRole[]) {
  return !!admin && (admin.role === "super_admin" || roles.includes(admin.role));
}
